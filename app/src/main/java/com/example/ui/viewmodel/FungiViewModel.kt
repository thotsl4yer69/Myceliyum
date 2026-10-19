package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.MyceliumApplication
import com.example.data.local.SettingsStore
import com.example.data.repository.FungiRepository
import com.example.model.HotspotCell
import com.example.model.MapObservation
import com.example.model.Observation
import com.example.model.Species
import com.example.model.UserSighting
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface HotspotState {
    object Idle : HotspotState
    object Loading : HotspotState
    data class Success(val cells: List<HotspotCell>) : HotspotState
    data class Error(val message: String) : HotspotState
}

/** Two-tier "Deep Search": a fine sub-grid drilled into one overview cell. */
sealed interface DeepSearchState {
    object Idle : DeepSearchState
    data class Loading(val parent: HotspotCell) : DeepSearchState
    data class Success(val parent: HotspotCell, val cells: List<HotspotCell>) : DeepSearchState
    data class Error(val message: String) : DeepSearchState
}

private data class HotspotRequest(
    val lat: Double,
    val lng: Double,
    val radiusKm: Double,
    val speciesId: String?,
    val allSpecies: Boolean
)

class FungiViewModel(
    application: Application,
    private val repository: FungiRepository,
    private val settingsStore: SettingsStore
) : AndroidViewModel(application) {

    // False until the map centre is set from a real source (GPS / user action).
    private val _locationResolved = MutableStateFlow(false)
    val locationResolved: StateFlow<Boolean> = _locationResolved.asStateFlow()

    // 1. Core Species Flows
    val speciesList: StateFlow<List<Species>> = repository.allSpeciesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * A gallery of reference photos for a species, pulled from iNaturalist
     * taxon photos (cached). Used by the detail screen to show multiple images
     * for every species without bundling them in the APK.
     */
    suspend fun fetchSpeciesImages(scientificName: String): List<String> =
        repository.fetchSpeciesImages(scientificName)

    /** Reference photos with CC attribution for the detail gallery. */
    suspend fun fetchSpeciesPhotos(scientificName: String): List<com.example.model.SpeciesPhoto> =
        repository.fetchSpeciesPhotos(scientificName)

    /** Field-ID diagnostics (checklist + enriched look-alikes) for a species. */
    suspend fun fetchSpeciesDiagnostics(speciesId: String) =
        repository.fetchSpeciesDiagnostics(speciesId)

    /** Total worldwide GBIF record count for a species (detail screen). */
    suspend fun fetchGlobalRecordCount(scientificName: String): Int? =
        repository.getGlobalRecordCount(scientificName)

    // 2. User Sightings Flows
    val userSightings: StateFlow<List<UserSighting>> = repository.allUserSightingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 3. Search Screen Filtering State
    val searchQuery = MutableStateFlow("")
    val selectedHabitatFilter = MutableStateFlow<String?>(null)
    val selectedSeasonFilter = MutableStateFlow<Int?>(null) // Month 1-12
    val selectedSporeFilter = MutableStateFlow<String?>(null)

    val filteredSpecies: StateFlow<List<Species>> = combine(
        speciesList,
        searchQuery,
        selectedHabitatFilter,
        selectedSeasonFilter,
        selectedSporeFilter
    ) { list, query, habitat, season, spore ->
        // Apply the attribute filters first, then rank what survives by how well
        // its name matches the query (best suggestions first). Ranking is lenient
        // on purpose — a close or fuzzy match still surfaces rather than showing
        // an empty screen.
        val byAttributes = list.filter { spec ->
            val matchesHabitat = habitat == null || spec.habitatTypes.any { it.equals(habitat, ignoreCase = true) }
            val matchesSeason = season == null || isMonthInSeason(season, spec.seasonStart, spec.seasonEnd)
            val matchesSpore = spore == null || spec.sporeColor.equals(spore, ignoreCase = true)
            matchesHabitat && matchesSeason && matchesSpore
        }
        com.example.util.SpeciesSearch.rank(byAttributes, query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Whether a global (worldwide) taxonomy search is in flight.
    private val _isGlobalSearching = MutableStateFlow(false)
    val isGlobalSearching: StateFlow<Boolean> = _isGlobalSearching.asStateFlow()

    /**
     * Worldwide fungal results from the GBIF taxonomy backbone, driven by the
     * same search box (debounced). Excludes anything already in the curated
     * local catalogue so the two lists don't duplicate. Makes the catalogue a
     * searchable front-end over every described fungus on Earth.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val globalResults: StateFlow<List<Species>> = searchQuery
        .debounce(350)
        .combine(speciesList) { query, local -> query.trim() to local }
        .mapLatest { (query, local) ->
            if (query.length < 3) {
                _isGlobalSearching.value = false
                emptyList()
            } else {
                _isGlobalSearching.value = true
                try {
                    val localNames = local.map { it.scientificName.lowercase() }.toSet()
                    repository.searchGlobalFungi(query)
                        .filter { it.scientificName.lowercase() !in localNames }
                } finally {
                    _isGlobalSearching.value = false
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 4. Map & Hotspots Calculation View State
    val mapCenter = MutableStateFlow(Pair(-37.8136, 144.9631)) // Default to Melbourne, Victoria
    val searchRadiusKm = MutableStateFlow(10.0) // 1.0 to 30.0 km
    val selectedSpeciesForHotspot = MutableStateFlow<Species?>(null)
    // Default to aggregate ("any fungi") mode so the map populates with rich
    // evidence as soon as the user opens it during peak season. They can switch
    // to a specific species via the bottom drawer.
    val isAllSpeciesMode = MutableStateFlow(true)

    private val _hotspotState = MutableStateFlow<HotspotState>(HotspotState.Idle)
    val hotspotState: StateFlow<HotspotState> = _hotspotState.asStateFlow()

    // Deep Search (drill-down) — independent of the overview hotspot state so the
    // broad grid stays put underneath while a fine sub-grid is computed/overlaid.
    private val _deepSearchState = MutableStateFlow<DeepSearchState>(DeepSearchState.Idle)
    val deepSearchState: StateFlow<DeepSearchState> = _deepSearchState.asStateFlow()
    private var deepJob: kotlinx.coroutines.Job? = null

    private val _observationPins = MutableStateFlow<List<Observation>>(emptyList())
    val observationPins: StateFlow<List<Observation>> = _observationPins.asStateFlow()

    // "All fungi" map layer — every nearby fungal sighting from iNaturalist,
    // labelled by taxon. Shown on the map independent of the selected species.
    private val _allFungiPins = MutableStateFlow<List<MapObservation>>(emptyList())
    val allFungiPins: StateFlow<List<MapObservation>> = _allFungiPins.asStateFlow()

    // User toggle for the raw all-sightings layer. Default OFF so the map opens on
    // the prediction surface (the point of the screen) rather than a clutter of raw
    // iNaturalist records that read like predictions. The user can switch it on, and
    // when on the markers are drawn in a distinct muted style (see MapScreen).
    val showAllSightings = MutableStateFlow(false)

    private val _weatherSummary = MutableStateFlow<Pair<Double, Double>?>(null) // Rainfall, MaxTemp
    val weatherSummary: StateFlow<Pair<Double, Double>?> = _weatherSummary.asStateFlow()

    private val _isRecomputationsRunning = MutableStateFlow(false)
    val isRecomputationsRunning: StateFlow<Boolean> = _isRecomputationsRunning.asStateFlow()

    // 5. Settings Configuration State (persisted via DataStore)
    val measureUnits: StateFlow<String> = settingsStore.measureUnits
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_UNITS)
    val mapTheme: StateFlow<String> = settingsStore.mapTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_MAP_THEME)
    val appTheme: StateFlow<String> = settingsStore.appTheme
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_APP_THEME)
    // User-supplied Anthropic API key for AI identification (stored on-device only).
    val anthropicApiKey: StateFlow<String> = settingsStore.anthropicApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    // Safe default until persisted consent has been loaded.
    val splashNoticeAccepted: StateFlow<Boolean> = settingsStore.splashAccepted
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setMeasureUnits(value: String) {
        viewModelScope.launch { settingsStore.setMeasureUnits(value) }
    }

    fun setMapTheme(value: String) {
        viewModelScope.launch { settingsStore.setMapTheme(value) }
    }

    fun setAppTheme(value: String) {
        viewModelScope.launch { settingsStore.setAppTheme(value) }
    }

    val credentialError = settingsStore.credentialError

    fun setAnthropicApiKey(value: String, onResult: (String?) -> Unit = {}) {
        viewModelScope.launch {
            try { settingsStore.setAnthropicApiKey(value); onResult(null) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onResult("The API key could not be saved securely. Try again on an unlocked device.")
            }
        }
    }

    fun acceptSplashNotice() {
        viewModelScope.launch { settingsStore.setSplashAccepted(true) }
    }

    private var computeJob: kotlinx.coroutines.Job? = null
    private var allPinsJob: kotlinx.coroutines.Job? = null
    private val _isClearingCache = MutableStateFlow(false)
    val isClearingCache: StateFlow<Boolean> = _isClearingCache.asStateFlow()
    // The exact request currently being computed, to dedupe rapid identical
    // re-fires (which would otherwise keep cancelling the in-flight grid so it
    // never completes — leaving it stuck in Loading).
    private var inFlightComputeKey: String? = null
    // How many times computeHotspots has actually started — surfaced in the
    // on-map diagnostic so a runaway re-trigger loop is visible.
    private val _computeRuns = MutableStateFlow(0)
    val computeRuns: StateFlow<Int> = _computeRuns.asStateFlow()

    /**
     * Recomputes hotspot overlay + pins for the current map parameters.
     * Branches between single-species and aggregate "all species" mode.
     */
    /**
     * Geocodes [query] (Google Geocoding when a key is set, else the device
     * geocoder), recentres the map, recomputes hotspots, and reports a
     * human-readable label via [onResult] (null when nothing was found).
     */
    fun searchLocation(query: String, onResult: (String?) -> Unit) {
        if (query.isBlank()) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            val place = repository.geocodePlace(query)
            if (place != null) {
                setMapCenter(place.lat, place.lng)
                onResult(place.label)
            } else {
                onResult(null)
            }
        }
    }

    /** Reverse-geocodes [lat]/[lng] to a place label for the map header. */
    fun reverseGeocode(lat: Double, lng: Double, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val name = repository.reverseGeocode(lat, lng)
            onResult(name ?: String.format(java.util.Locale.US, "GPS: %.3f, %.3f", lat, lng))
        }
    }

    fun setMapCenter(lat: Double, lng: Double) {
        require(com.example.util.SightingPolicy.validCoordinates(lat, lng))
        if (_locationResolved.value && mapCenter.value == Pair(lat, lng)) return
        usingSavedArea = false
        _locationResolved.value = true
        mapCenter.value = Pair(lat, lng)
    }

    fun setSelectedSpeciesForHotspot(species: Species?) {
        selectedSpeciesForHotspot.value = species
    }

    fun computeHotspots() {
        if (!_locationResolved.value || _isClearingCache.value) return
        usingSavedArea = false
        val (lat, lng) = mapCenter.value
        val radius = searchRadiusKm.value
        val multiSpecies = isAllSpeciesMode.value
        val species = selectedSpeciesForHotspot.value
        if (!multiSpecies && species == null) return
        if (lat !in -39.3..-33.9 || lng !in 140.8..150.1 || (!multiSpecies && species?.id?.startsWith("gbif_") == true)) {
            _hotspotState.value = HotspotState.Error("Suitability scoring is currently tuned for Victoria and its bundled catalogue. Worldwide taxonomy search remains available.")
            _weatherSummary.value = null
            return
        }

        // Dedupe identical in-flight requests so map-center jitter / rapid
        // recompositions can't keep cancelling and restarting the same grid
        // (which leaves it perpetually Loading and never finishing). lat/lng are
        // rounded so sub-~10 m jitter maps to the same key.
        val key = "${String.format(java.util.Locale.US, "%.4f|%.4f", lat, lng)}|$radius|$multiSpecies|${species?.id}"
        if (key == inFlightComputeKey && computeJob?.isActive == true) return
        inFlightComputeKey = key
        _computeRuns.value += 1

        computeJob?.cancel()
        clearDeepSearch()  // a new overview invalidates any open drill-down
        computeJob = viewModelScope.launch {
            _hotspotState.value = HotspotState.Loading
            completedRequest = null
            _weatherSummary.value = null
            _isRecomputationsRunning.value = true

            // Refresh the all-fungi sightings layer in parallel — independent of
            // the hotspot computation, so the map populates pins quickly and a
            // hotspot failure never blocks the sightings (and vice versa).
            launch {
                _allFungiPins.value =
                    if (showAllSightings.value) repository.getAllFungiObservations(lat, lng, radius)
                    else emptyList()
            }

            try {
                // Bound the grid computation so a stuck/slow upstream (Overpass,
                // Open-Meteo, Earth Engine over mobile data) can't leave the map
                // spinning forever — it surfaces a retryable error instead.
                val cells = withTimeout(GRID_TIMEOUT_MS) {
                    if (multiSpecies) {
                        repository.generateMultiSpeciesHotspots(lat, lng, radius)
                    } else {
                        repository.generateHotspots(species!!, lat, lng, radius)
                    }
                }

                // Publish the grid as soon as it's ready. The weather summary and
                // species pins below are non-essential extras for other tabs — they
                // must NEVER discard a perfectly good grid if they fail, so they run
                // best-effort AFTER Success is set.
                completedRequest = HotspotRequest(lat, lng, radius, species?.id, multiSpecies)
                _hotspotState.value = HotspotState.Success(cells)

                try {
                    _weatherSummary.value = repository.getWeatherLast30Days(lat, lng)
                    // Recent iNaturalist records for the selected species (Home tab).
                    _observationPins.value = species?.let {
                        repository.getObservations(it, lat, lng, radius)
                    } ?: emptyList()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("FungiViewModel", "Optional weather or observation refresh failed")
                }
            } catch (e: TimeoutCancellationException) {
                // A timeout IS a CancellationException, so it must be caught before
                // the generic cancellation check below or it would be swallowed and
                // leave the map stuck on the spinner.
                _hotspotState.value =
                    HotspotState.Error("timed out — slow or blocked connection. Tap Retry.")
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _hotspotState.value = HotspotState.Error(e.message ?: "Failed to compute hotspots.")
                }
            } finally {
                _isRecomputationsRunning.value = false
            }
        }
    }

    /** Whether a cell can be drilled into: single-species mode, VeryGood+ tier. */
    fun canDeepSearch(cell: HotspotCell): Boolean =
        !usingSavedArea && !isAllSpeciesMode.value &&
            selectedSpeciesForHotspot.value != null &&
            (cell.tier == "Excellent" || cell.tier == "VeryGood")

    /**
     * Drill into one promising overview cell, computing a fine sub-grid via the
     * repository (single-species only in v1). Runs independently of the overview.
     */
    fun deepSearch(parentCell: HotspotCell) {
        val species = selectedSpeciesForHotspot.value ?: return
        if (isAllSpeciesMode.value) return
        val radius = searchRadiusKm.value
        deepJob?.cancel()
        deepJob = viewModelScope.launch {
            _deepSearchState.value = DeepSearchState.Loading(parentCell)
            try {
                val sub = repository.deepSearchCell(species, parentCell, radius)
                _deepSearchState.value = DeepSearchState.Success(parentCell, sub)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _deepSearchState.value = DeepSearchState.Error(e.message ?: "Deep search failed.")
                }
            }
        }
    }

    /** Dismiss the drill-down and return to the broad overview grid. */
    fun clearDeepSearch() {
        deepJob?.cancel()
        _deepSearchState.value = DeepSearchState.Idle
    }

    /** Toggle between single-species and aggregate "all species" hotspot mode. */
    fun setAllSpeciesMode(enabled: Boolean) {
        isAllSpeciesMode.value = enabled
    }

    /** Toggle the "all fungi sightings" map layer. */
    fun setShowAllSightings(enabled: Boolean) {
        allPinsJob?.cancel()
        showAllSightings.value = enabled
        if (!enabled) {
            _allFungiPins.value = emptyList()
        } else if (_locationResolved.value && !_isClearingCache.value) {
            if (usingSavedArea) return
            val (lat, lng) = mapCenter.value
            allPinsJob = viewModelScope.launch {
                _allFungiPins.value = repository.getAllFungiObservations(lat, lng, searchRadiusKm.value)
            }
        }
    }

    /**
     * Resets search screen filters to null
     */
    fun resetFilters() {
        selectedHabitatFilter.value = null
        selectedSeasonFilter.value = null
        selectedSporeFilter.value = null
        searchQuery.value = ""
    }

    /**
     * Adds a new sighting to local Room DB
     */
    fun addUserSighting(
        speciesId: String,
        latitude: Double,
        longitude: Double,
        notes: String,
        photoPath: String?,
        isPrivate: Boolean,
        existing: UserSighting? = null,
        onResult: (String?) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (!com.example.util.SightingPolicy.validCoordinates(latitude, longitude)) {
                onResult("Enter valid coordinates."); return@launch
            }
            val sighting = UserSighting(
                id = existing?.id ?: 0,
                speciesId = speciesId,
                lat = latitude,
                lng = longitude,
                timestamp = existing?.timestamp ?: System.currentTimeMillis(),
                photoLocalPath = photoPath,
                notes = notes,
                isPrivate = isPrivate
            )
            try { repository.insertUserSighting(sighting); onResult(null) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; onResult("Could not save. Your draft is still open.") }
        }
    }

    /**
     * Deletes user observation sighting
     */
    fun deleteUserSighting(sighting: UserSighting, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try { repository.deleteUserSighting(sighting); onResult(true) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; onResult(false) }
        }
    }

    fun restoreSighting(sighting: UserSighting, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            try { repository.insertUserSighting(sighting); onResult(true) }
            catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onResult(false)
            }
        }
    }

    /**
     * Exports sightings in Darwin Core format for contribution to
     * Fungimap/ALA/GBIF biodiversity databases.
     */
    private val _darwinCoreExport = MutableStateFlow<String?>(null)
    val darwinCoreExport: StateFlow<String?> = _darwinCoreExport.asStateFlow()

    fun exportDarwinCore() {
        viewModelScope.launch {
            val csv = repository.exportDarwinCore()
            _darwinCoreExport.value = csv
        }
    }

    fun clearExport() {
        _darwinCoreExport.value = null
    }

    /**
     * Clear local observation query cache (TTL management)
     */
    fun clearCache(onResult: (Boolean) -> Unit = {}) {
        if (_isClearingCache.value || _isPreparingArea.value) { onResult(false); return }
        _isClearingCache.value = true
        val jobs = listOfNotNull(computeJob, deepJob, allPinsJob)
        jobs.forEach { it.cancel() }
        viewModelScope.launch {
            try {
                jobs.forEach { it.join() }
                repository.clearCaches()
                _observationPins.value = emptyList()
                _allFungiPins.value = emptyList()
                _weatherSummary.value = null
                _hotspotState.value = HotspotState.Idle
                _deepSearchState.value = DeepSearchState.Idle
                completedRequest = null
                usingSavedArea = false
                onResult(true)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                onResult(false)
            } finally {
                _isClearingCache.value = false
            }
        }
    }

    private fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            month in start..end
        } else {
            month >= start || month <= end
        }
    }

    private val fieldPackStore = com.example.data.local.FieldPackStore(getApplication())
    private var usingSavedArea = false
    private var savedAreaRequest: HotspotRequest? = null
    private var completedRequest: HotspotRequest? = null
    private val _offlineAreaStatus = MutableStateFlow("Prepare the current grid or load a previously saved area.")
    val offlineAreaStatus: StateFlow<String> = _offlineAreaStatus.asStateFlow()
    private val _isPreparingArea = MutableStateFlow(false)
    val isPreparingArea: StateFlow<Boolean> = _isPreparingArea.asStateFlow()

    fun prepareArea() {
        if (_isPreparingArea.value || _isClearingCache.value) return
        val request = completedRequest
        val cells = (_hotspotState.value as? HotspotState.Success)?.cells
        val current = HotspotRequest(mapCenter.value.first, mapCenter.value.second, searchRadiusKm.value, selectedSpeciesForHotspot.value?.id, isAllSpeciesMode.value)
        if (request == null || request != current || cells.isNullOrEmpty() || usingSavedArea) {
            _offlineAreaStatus.value = "Finish a current area calculation before saving an offline snapshot."
            return
        }
        val photoSpecies = if (request.allSpecies) speciesList.value.take(8) else listOfNotNull(selectedSpeciesForHotspot.value)
        _isPreparingArea.value = true
        viewModelScope.launch {
            try {
                _offlineAreaStatus.value = "Saving observations and reusable reference photos…"
                val records = repository.getAllFungiObservations(request.lat, request.lng, request.radiusKm)
                val photos = repository.prepareReferencePhotos(photoSpecies)
                val pack = com.example.data.local.FieldPack(
                    System.currentTimeMillis(), request.lat, request.lng, request.radiusKm,
                    request.speciesId, request.allSpecies, cells, records, photos
                )
                withContext(Dispatchers.IO) { fieldPackStore.write(pack) }
                _offlineAreaStatus.value = "Saved ${cells.size} cells, ${records.size} observations and ${photos.values.sumOf { it.size }} photos for ${photos.size} species. Basemap tiles are not included."
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _offlineAreaStatus.value = "Could not prepare this area. The previous saved area is intact."
            } finally {
                _isPreparingArea.value = false
            }
        }
    }

    fun openPreparedArea() {
        if (_isPreparingArea.value) return
        _isPreparingArea.value = true
        viewModelScope.launch {
            try {
                val pack = withContext(Dispatchers.IO) { fieldPackStore.read() }
                if (pack == null) {
                    _offlineAreaStatus.value = "No saved area was found. Calculate an area while connected, then prepare it."
                    return@launch
                }
                val catalogue = withTimeout(5_000L) { speciesList.first { it.isNotEmpty() } }
                computeJob?.cancel()
                computeJob?.join()
                clearDeepSearch()
                usingSavedArea = true
                savedAreaRequest = HotspotRequest(pack.lat, pack.lng, pack.radiusKm, pack.speciesId, pack.allSpecies)
                _locationResolved.value = true
                mapCenter.value = pack.lat to pack.lng
                searchRadiusKm.value = pack.radiusKm
                isAllSpeciesMode.value = pack.allSpecies
                selectedSpeciesForHotspot.value = catalogue.firstOrNull { it.id == pack.speciesId }
                // Match the selected object if the catalogue is still loading.
                savedAreaRequest = savedAreaRequest?.copy(speciesId = selectedSpeciesForHotspot.value?.id)
                val savedAt = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(pack.savedAt))
                _hotspotState.value = HotspotState.Success(pack.cells.map {
                    it.copy(confidence = minOf(it.confidence, 0.32), contributingFactors = listOf("SAVED SNAPSHOT — $savedAt. Conditions may have changed.") + it.contributingFactors)
                })
                _allFungiPins.value = pack.observations
                showAllSightings.value = true
                _observationPins.value = emptyList()
                _weatherSummary.value = null
                _offlineAreaStatus.value = "Loaded snapshot from $savedAt. Open Hotspots to view it. ${pack.photos.values.sumOf { it.size }} reference photos available; basemap tiles are not included."
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _offlineAreaStatus.value = "The saved area could not be opened. Your sightings are unchanged."
            } finally {
                _isPreparingArea.value = false
            }
        }
    }

    // Seed state (ensure database is initialized on start)
    init {
        viewModelScope.launch {
            repository.seedDatabase()
            speciesList.first { it.isNotEmpty() }.let { list ->
                if (selectedSpeciesForHotspot.value == null) {
                    selectedSpeciesForHotspot.value = list.first()
                }
            }
        }
        // The map opens on a neutral default coordinate; mark the location
        // "resolved" the first time it's set from a real source (device GPS, a
        // search, presets, manual coords) so the UI can show "Locating…" instead
        // of a misleading default city.
        viewModelScope.launch { mapCenter.drop(1).collect { _locationResolved.value = true } }
        viewModelScope.launch {
            combine(
                mapCenter,
                searchRadiusKm,
                selectedSpeciesForHotspot,
                isAllSpeciesMode
            ) { center, radius, species, allSpecies ->
                HotspotRequest(center.first, center.second, radius, species?.id, allSpecies)
            }
                .debounce(HOTSPOT_RECOMPUTE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { request ->
                    if (usingSavedArea && request == savedAreaRequest) return@collect
                    usingSavedArea = false
                    if (_locationResolved.value && (request.allSpecies || request.speciesId != null)) {
                        computeHotspots()
                    }
                }
        }
    }


    // Factory Class
    companion object {
        /** Upper bound on a hotspot-grid computation before it fails retryably. */
        private const val GRID_TIMEOUT_MS = 60_000L
        /** Brief settle time so pans/sliders coalesce without feeling laggy. */
        private const val HOTSPOT_RECOMPUTE_DEBOUNCE_MS = 350L

        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as MyceliumApplication
                    return FungiViewModel(application, app.repository, app.settingsStore) as T
                }
            }
        }
}

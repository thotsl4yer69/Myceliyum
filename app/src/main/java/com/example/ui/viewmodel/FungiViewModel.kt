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

class FungiViewModel(
    application: Application,
    private val repository: FungiRepository,
    private val settingsStore: SettingsStore
) : AndroidViewModel(application) {

    // Seed state (ensure database is initialized on start)
    init {
        viewModelScope.launch {
            repository.seedDatabase()
            speciesList.first { it.isNotEmpty() }.let { list ->
                if (selectedSpeciesForHotspot.value == null) {
                    selectedSpeciesForHotspot.value = list.first()
                    computeHotspots()
                }
            }
        }
    }

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
    // Initial value true so the returning-user case doesn't flash the disclaimer
    // before DataStore has loaded; a brand-new user sees it once it emits false.
    val splashNoticeAccepted: StateFlow<Boolean> = settingsStore.splashAccepted
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setMeasureUnits(value: String) {
        viewModelScope.launch { settingsStore.setMeasureUnits(value) }
    }

    fun setMapTheme(value: String) {
        viewModelScope.launch { settingsStore.setMapTheme(value) }
    }

    fun setAppTheme(value: String) {
        viewModelScope.launch { settingsStore.setAppTheme(value) }
    }

    fun setAnthropicApiKey(value: String) {
        viewModelScope.launch { settingsStore.setAnthropicApiKey(value) }
    }

    fun acceptSplashNotice() {
        viewModelScope.launch { settingsStore.setSplashAccepted(true) }
    }

    private var computeJob: kotlinx.coroutines.Job? = null

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
                mapCenter.value = Pair(place.lat, place.lng)
                computeHotspots()
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

    fun computeHotspots() {
        val (lat, lng) = mapCenter.value
        val radius = searchRadiusKm.value
        val multiSpecies = isAllSpeciesMode.value
        val species = selectedSpeciesForHotspot.value
        if (!multiSpecies && species == null) return

        computeJob?.cancel()
        clearDeepSearch()  // a new overview invalidates any open drill-down
        computeJob = viewModelScope.launch {
            _hotspotState.value = HotspotState.Loading
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
                _hotspotState.value = HotspotState.Success(cells)

                try {
                    _weatherSummary.value = repository.getWeatherLast30Days(lat, lng)
                    // Recent iNaturalist records for the selected species (Home tab).
                    _observationPins.value = species?.let {
                        repository.getObservations(it, lat, lng, radius)
                    } ?: emptyList()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("FungiViewModel", "Hotspot extras (weather/pins) failed: ${e.message}")
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
        !isAllSpeciesMode.value &&
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
        computeHotspots()
    }

    /** Toggle the "all fungi sightings" map layer. */
    fun setShowAllSightings(enabled: Boolean) {
        showAllSightings.value = enabled
        if (!enabled) {
            _allFungiPins.value = emptyList()
        } else {
            val (lat, lng) = mapCenter.value
            viewModelScope.launch {
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
        isPrivate: Boolean
    ) {
        viewModelScope.launch {
            val sighting = UserSighting(
                speciesId = speciesId,
                lat = latitude,
                lng = longitude,
                timestamp = System.currentTimeMillis(),
                photoLocalPath = photoPath,
                notes = notes,
                isPrivate = isPrivate
            )
            repository.insertUserSighting(sighting)
        }
    }

    /**
     * Deletes user observation sighting
     */
    fun deleteUserSighting(sighting: UserSighting) {
        viewModelScope.launch {
            repository.deleteUserSighting(sighting)
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
    fun clearCache() {
        viewModelScope.launch {
            repository.clearCaches()
            _observationPins.value = emptyList()
            if (selectedSpeciesForHotspot.value != null) {
                computeHotspots()
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

    // Factory Class
    companion object {
        /** Upper bound on a hotspot-grid computation before it fails retryably. */
        private const val GRID_TIMEOUT_MS = 60_000L

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

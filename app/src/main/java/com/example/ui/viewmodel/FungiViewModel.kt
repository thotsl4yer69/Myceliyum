package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.MyceliumApplication
import com.example.data.local.SettingsStore
import com.example.data.repository.FungiRepository
import com.example.model.HotspotCell
import com.example.model.Observation
import com.example.model.Species
import com.example.model.UserSighting
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface HotspotState {
    object Idle : HotspotState
    object Loading : HotspotState
    data class Success(val cells: List<HotspotCell>) : HotspotState
    data class Error(val message: String) : HotspotState
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
        list.filter { spec ->
            val matchesQuery = query.isEmpty() ||
                    spec.scientificName.contains(query, ignoreCase = true) ||
                    spec.commonNames.any { it.contains(query, ignoreCase = true) } ||
                    spec.genus.contains(query, ignoreCase = true)

            val matchesHabitat = habitat == null || spec.habitatTypes.any { it.equals(habitat, ignoreCase = true) }

            val matchesSeason = season == null || isMonthInSeason(season, spec.seasonStart, spec.seasonEnd)

            val matchesSpore = spore == null || spec.sporeColor.equals(spore, ignoreCase = true)

            matchesQuery && matchesHabitat && matchesSeason && matchesSpore
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    private val _observationPins = MutableStateFlow<List<Observation>>(emptyList())
    val observationPins: StateFlow<List<Observation>> = _observationPins.asStateFlow()

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

    fun computeHotspots() {
        val (lat, lng) = mapCenter.value
        val radius = searchRadiusKm.value
        val multiSpecies = isAllSpeciesMode.value
        val species = selectedSpeciesForHotspot.value
        if (!multiSpecies && species == null) return

        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            _hotspotState.value = HotspotState.Loading
            _isRecomputationsRunning.value = true
            try {
                val cells = if (multiSpecies) {
                    repository.generateMultiSpeciesHotspots(lat, lng, radius)
                } else {
                    repository.generateHotspots(species!!, lat, lng, radius)
                }

                val weather = repository.getWeatherLast30Days(lat, lng)
                _weatherSummary.value = weather

                // Always populate pins for the current "selected species" — the
                // Home tab uses these to show recent iNaturalist records. The
                // map view chooses whether to render them based on mode.
                _observationPins.value = species?.let {
                    repository.getObservations(it, lat, lng, radius)
                } ?: emptyList()

                _hotspotState.value = HotspotState.Success(cells)
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    _hotspotState.value = HotspotState.Error(e.message ?: "Failed to compute hotspots.")
                }
            } finally {
                _isRecomputationsRunning.value = false
            }
        }
    }

    /** Toggle between single-species and aggregate "all species" hotspot mode. */
    fun setAllSpeciesMode(enabled: Boolean) {
        isAllSpeciesMode.value = enabled
        computeHotspots()
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

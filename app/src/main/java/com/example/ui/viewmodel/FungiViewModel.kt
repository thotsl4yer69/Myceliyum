package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.MyceliumApplication
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
    private val repository: FungiRepository
) : AndroidViewModel(application) {

    // Seed state (ensure database is initialized on start)
    init {
        viewModelScope.launch {
            repository.seedDatabase()
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
    val searchRadiusKm = MutableStateFlow(5.0) // 1.0 to 20.0 km
    val selectedSpeciesForHotspot = MutableStateFlow<Species?>(null)

    private val _hotspotState = MutableStateFlow<HotspotState>(HotspotState.Idle)
    val hotspotState: StateFlow<HotspotState> = _hotspotState.asStateFlow()

    private val _observationPins = MutableStateFlow<List<Observation>>(emptyList())
    val observationPins: StateFlow<List<Observation>> = _observationPins.asStateFlow()

    private val _weatherSummary = MutableStateFlow<Pair<Double, Double>?>(null) // Rainfall, MaxTemp
    val weatherSummary: StateFlow<Pair<Double, Double>?> = _weatherSummary.asStateFlow()

    private val _isRecomputationsRunning = MutableStateFlow(false)
    val isRecomputationsRunning: StateFlow<Boolean> = _isRecomputationsRunning.asStateFlow()

    // 5. Settings Configuration State
    val measureUnits = MutableStateFlow("Metric") // "Metric" / "Imperial"
    val mapTheme = MutableStateFlow("Topo Field Plan") // Style options
    val splashNoticeAccepted = MutableStateFlow(false)

    /**
     * Recomputes hotspots overlay and pins based on active map parameters
     */
    fun computeHotspots() {
        val species = selectedSpeciesForHotspot.value ?: return
        val (lat, lng) = mapCenter.value
        val radius = searchRadiusKm.value

        viewModelScope.launch {
            _hotspotState.value = HotspotState.Loading
            _isRecomputationsRunning.value = true
            try {
                // Generate cells
                val cells = repository.generateHotspots(
                    species = species,
                    centerLat = lat,
                    centerLng = lng,
                    radiusKm = radius,
                    forceRefresh = false
                )

                // Fetch weather signals for dashboard
                val weather = repository.getWeatherLast30Days(lat, lng)
                _weatherSummary.value = weather

                // Fetch pins for rendering
                val pins = repository.getObservations(species, lat, lng, radius)
                _observationPins.value = pins

                _hotspotState.value = HotspotState.Success(cells)
            } catch (e: Exception) {
                _hotspotState.value = HotspotState.Error(e.message ?: "Failed to query research engine.")
            } finally {
                _isRecomputationsRunning.value = false
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
                    return FungiViewModel(application, app.repository) as T
                }
            }
        }
}

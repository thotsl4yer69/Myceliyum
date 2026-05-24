package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.FungiDao
import com.example.data.remote.INatObservation
import com.example.data.remote.INaturalistApi
import com.example.data.remote.OpenMeteoApi
import com.example.model.HotspotCell
import com.example.model.Observation
import com.example.model.Species
import com.example.model.UserSighting
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.*

class FungiRepository(
    private val context: Context,
    private val dao: FungiDao,
    private val iNatApi: INaturalistApi,
    private val openMeteoApi: OpenMeteoApi
) {
    private val moshi = Moshi.Builder().build()
    private val TAG = "FungiRepository"

    // TTL for iNaturalist observations cache (24 hours)
    private val CACHE_TTL_MS = 24 * 60 * 60 * 1000L

    val allSpeciesFlow: Flow<List<Species>> = dao.getAllSpeciesFlow()
    val allUserSightingsFlow: Flow<List<UserSighting>> = dao.getAllUserSightingsFlow()

    /**
     * Seeds the local database with species from the bundled assets species.json.
     */
    suspend fun seedDatabase() = withContext(Dispatchers.IO) {
        try {
            val existing = dao.getAllSpecies()
            if (existing.isEmpty()) {
                context.assets.open("species.json").use { inputStream ->
                    val reader = InputStreamReader(inputStream)
                    val jsonString = reader.readText()
                    val listType = Types.newParameterizedType(List::class.java, Species::class.java)
                    val adapter = moshi.adapter<List<Species>>(listType)
                    val speciesList = adapter.fromJson(jsonString)
                    if (speciesList != null) {
                        dao.insertSpecies(speciesList)
                        Log.d(TAG, "Successfully seeded ${speciesList.size} species to Room database.")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to seed species database from assets or check existing record schema", e)
        }
    }

    suspend fun getSpeciesById(id: String): Species? = withContext(Dispatchers.IO) {
        dao.getSpeciesById(id)
    }

    suspend fun getAllSpecies(): List<Species> = withContext(Dispatchers.IO) {
        dao.getAllSpecies()
    }

    suspend fun insertUserSighting(sighting: UserSighting) = withContext(Dispatchers.IO) {
        dao.insertUserSighting(sighting)
    }

    suspend fun deleteUserSighting(sighting: UserSighting) = withContext(Dispatchers.IO) {
        dao.deleteUserSighting(sighting)
    }

    suspend fun getAllUserSightings(): List<UserSighting> = withContext(Dispatchers.IO) {
        dao.getAllUserSightings()
    }

    /**
     * Fetch iNaturalist observations with offline-first Room cache with TTL.
     */
    suspend fun getObservations(
        species: Species,
        lat: Double,
        lng: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false
    ): List<Observation> = withContext(Dispatchers.IO) {
        // 1. Check cached observations in Room
        val cached = dao.getCachedObservations(species.id)
        val now = System.currentTimeMillis()

        // Filter those within radius distance from the center target (cached observations might be broader)
        val inRadiusCached = cached.filter {
            calculateDistanceMeters(lat, lng, it.lat, it.lng) <= radiusKm * 1000.0
        }

        val isCacheValid = cached.isNotEmpty() && (now - cached.maxOf { it.cachedAt }) < CACHE_TTL_MS

        if (isCacheValid && !forceRefresh) {
            Log.d(TAG, "Returning ${inRadiusCached.size} cached observations from Room (cache fresh)")
            return@withContext inRadiusCached
        }

        // 2. Cache is stale or empty or forceRefresh requested: Fetch from network
        try {
            Log.d(TAG, "Fetching observations from iNaturalist API for ${species.scientificName} at ($lat, $lng)")
            // 5 years ago date
            val cal = Calendar.getInstance()
            cal.add(Calendar.YEAR, -5)
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val sinceDate = sdf.format(cal.time)

            val response = iNatApi.getObservations(
                taxonName = species.scientificName,
                lat = lat,
                lng = lng,
                radiusKm = radiusKm,
                sinceDate = sinceDate
            )

            val iNatObsList = response.results
            val freshObservations = iNatObsList.mapNotNull { iObs ->
                // Parse coordinates
                val parsedLat = iObs.latitude ?: iObs.location?.split(",")?.getOrNull(0)?.toDoubleOrNull()
                val parsedLng = iObs.longitude ?: iObs.location?.split(",")?.getOrNull(1)?.toDoubleOrNull()
                
                if (parsedLat != null && parsedLng != null) {
                    val obsTime = parseObsDate(iObs.observedOn)
                    Observation(
                        id = iObs.id,
                        speciesId = species.id,
                        lat = parsedLat,
                        lng = parsedLng,
                        observedAt = obsTime,
                        source = "iNaturalist",
                        photoUrl = iObs.photos?.firstOrNull()?.url,
                        qualityGrade = iObs.qualityGrade ?: "research",
                        cachedAt = now
                    )
                } else {
                    null
                }
            }

            // Save new network result to local Room Cache
            if (freshObservations.isNotEmpty()) {
                // Clear and replace cache for this species to maintain fresh cache representation
                dao.clearObservationsForSpecies(species.id)
                dao.insertObservations(freshObservations)
                Log.d(TAG, "Fetched and cached ${freshObservations.size} fresh observations in Room.")
            }
            
            // Return observations filtered by radius
            return@withContext freshObservations.filter {
                calculateDistanceMeters(lat, lng, it.lat, it.lng) <= radiusKm * 1000.0
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch from iNaturalist API, returning stale cache", e)
            // Offline fallback: return whatever elements we have cached (even if stale) as it is offline-first!
            return@withContext inRadiusCached
        }
    }

    /**
     * Fetches rainfall and temperature in past 30 days from Open-Meteo.
     * Returns total rainfall in mm, and average max recorded temperature in C.
     */
    suspend fun getWeatherLast30Days(lat: Double, lng: Double): Pair<Double, Double> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching rainfall and temperature from Open-Meteo for ($lat, $lng)")
            val response = openMeteoApi.getPastWeather(limitLat = lat, limitLng = lng)
            val sumList = response.daily.precipitationSum
            val maxList = response.daily.temperatureMax

            // Sum of last 30 days rainfall
            val totalRainfall = if (sumList != null && sumList.isNotEmpty()) {
                sumList.sum()
            } else {
                0.0
            }

            // Average max temp over past 30 days
            val avgMaxTemp = if (maxList != null && maxList.isNotEmpty()) {
                maxList.average()
            } else {
                15.0
            }

            Log.d(TAG, "Historical weather (last 30 days): Rainfall: $totalRainfall mm, Avg Max Temp: $avgMaxTemp C")
            return@withContext Pair(totalRainfall, avgMaxTemp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch weather from Open-Meteo, using default fallback (95.0mm, 15.5C)", e)
            // Returning a typical Victorian autumn default which permits moderate fruiting
            return@withContext Pair(95.0, 15.5)
        }
    }

    /**
     * Clear all caches for management.
     */
    suspend fun clearCaches() = withContext(Dispatchers.IO) {
        dao.clearAllCachedObservations()
    }

    /**
     * Hotspot Grid Calculation Algorithm (COMPUTED AT 500M RESOLUTION)
     */
    suspend fun generateHotspots(
        species: Species,
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false
    ): List<HotspotCell> = withContext(Dispatchers.Default) {
        // 1. Get observations (including user sightings + iNaturalist)
        val iNatObs = getObservations(species, centerLat, centerLng, radiusKm, forceRefresh)
        val userSightings = dao.getAllUserSightings().filter {
            it.speciesId == species.id && calculateDistanceMeters(centerLat, centerLng, it.lat, it.lng) <= radiusKm * 1000.0
        }

        // 2. Fetch the weather (rainfall and max temp in last 30 days)
        val (rainfall, maxTemp) = getWeatherLast30Days(centerLat, centerLng)

        // Generate grid coordinates centered on centerLat, centerLng
        // 1 deg lat is ~111km, 500m is 0.0045 deg lat.
        // 1 deg lng at -37.8 deg lat is ~87.7km, 500m is 0.0057 deg lng.
        val latStep = 0.0045
        val lngStep = 0.0057

        // Number of steps in each direction based on search radius
        val latRangeSteps = ceil((radiusKm * 1000.0) / 500.0).toInt()
        val cells = mutableListOf<HotspotCell>()

        val nowMs = System.currentTimeMillis()

        // Pre-evaluate seasonScore
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH) + 1 // 1-12
        val isInSeason = isMonthInSeason(currentMonth, species.seasonStart, species.seasonEnd)
        val seasonScore = if (isInSeason) 1.0 else 0.3

        // Pre-evaluate rainfallScore (concerning both 30-day precipitation and 30-day average max temperature limits)
        // Optimal range for past 30 days rainfall: 60.0mm to 200.0mm
        val rainfallFactor = when {
            rainfall in 60.0..200.0 -> 1.0
            rainfall < 60.0 -> {
                rainfall / 60.0
            }
            else -> {
                maxOf(0.0, 1.0 - (rainfall - 200.0) / 200.0)
            }
        }

        // Optimal range for past 30 days average max temperature: 10.0C to 20.0C (ideal conditions for fungi growth)
        val tempFactor = when {
            maxTemp in 10.0..20.0 -> 1.0
            maxTemp < 10.0 -> {
                maxOf(0.0, maxTemp / 10.0)
            }
            else -> {
                maxOf(0.0, 1.0 - (maxTemp - 20.0) / 12.0)
            }
        }

        // Combine factors: Rainfall defines 60% of moisture suitability, and Temperature defines 40% of microclimate fit
        val rainfallScore = 0.6 * rainfallFactor + 0.4 * tempFactor

        // Generate grid
        for (i in -latRangeSteps..latRangeSteps) {
            for (j in -latRangeSteps..latRangeSteps) {
                val cellLat = centerLat + i * latStep
                val cellLng = centerLng + j * lngStep

                // Check if grid point is within the radius circle
                val distanceToCenter = calculateDistanceMeters(centerLat, centerLng, cellLat, cellLng)
                if (distanceToCenter <= radiusKm * 1000.0) {
                    
                    // A. Calculate observationScore:
                    // Normalized count of research observations within 2km in last 5 years, weighted by exponential decay (half-life 365 days).
                    // Halflife math: lambda = ln(2)/365 = 0.0019
                    val lambda = ln(2.0) / 365.0
                    var weightedCount = 0.0

                    // Combine iNat + user sightings
                    val localObsList = iNatObs.filter {
                        calculateDistanceMeters(cellLat, cellLng, it.lat, it.lng) <= 2000.0
                    }
                    val localUserSightings = userSightings.filter {
                        calculateDistanceMeters(cellLat, cellLng, it.lat, it.lng) <= 2000.0
                    }

                    // Process iNat observations
                    for (obs in localObsList) {
                        val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
                        if (diffDays in 0.0..(5.0 * 365.0)) {
                            val weight = exp(-lambda * diffDays)
                            weightedCount += weight
                        }
                    }

                    // Process user sightings
                    for (sig in localUserSightings) {
                        val diffDays = (nowMs - sig.timestamp).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)
                        if (diffDays in 0.0..(5.0 * 365.0)) {
                            // User sightings can have a slightly higher multiplier for validation because they are offline-first verified!
                            val weight = exp(-lambda * diffDays) * 1.5 
                            weightedCount += weight
                        }
                    }

                    // Normalize weight (cap at max 5.0 weighted observations)
                    val observationScore = minOf(1.0, weightedCount / 5.0)

                    // B. Final score calculation
                    val finalScore = 0.5 * observationScore + 0.2 * seasonScore + 0.3 * rainfallScore

                    // C. Determine Tier: >=0.66 High, >=0.33 Medium, else Low
                    val tier = when {
                        finalScore >= 0.66 -> "High"
                        finalScore >= 0.33 -> "Medium"
                        else -> "Low"
                    }

                    // D. Contributing factors
                    val factors = mutableListOf<String>()
                    factors.add("Microhabitat Model at (${String.format(Locale.getDefault(), "%.4f", cellLat)}, ${String.format(Locale.getDefault(), "%.4f", cellLng)})")
                    factors.add("Local Activity Index: ${String.format(Locale.getDefault(), "%.1f", weightedCount)} weighted historical records within 2km (Contribution: ${String.format(Locale.getDefault(), "%.0f%%", observationScore * 50.0)})")
                    
                    if (isInSeason) {
                        factors.add("Optimal Season Window: Active in ${monthName(species.seasonStart)}-${monthName(species.seasonEnd)} (Current month: ${monthName(currentMonth)} | Contribution: +20%)")
                    } else {
                        factors.add("Out-of-Season Offset applied (Current month: ${monthName(currentMonth)}, Season: ${monthName(species.seasonStart)}-${monthName(species.seasonEnd)} | Contribution: +6%)")
                    }

                    factors.add("Microclimate Hydration & Temp (Past 30d): ${String.format(Locale.getDefault(), "%.1f", rainfall)}mm rain (Target 60-200mm), ${String.format(Locale.getDefault(), "%.1f", maxTemp)}C avg max temp (Target 10-20C) | Weather Contribution: ${String.format(Locale.getDefault(), "%.0f%%", rainfallScore * 30.0)}")

                    cells.add(
                        HotspotCell(
                            lat = cellLat,
                            lng = cellLng,
                            score = finalScore,
                            tier = tier,
                            contributingFactors = factors
                        )
                    )
                }
            }
        }
        return@withContext cells
    }


    // --- Support Math & Date Help Helpers ---

    private fun parseObsDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000 // default 1 year ago
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            )
            var parsedDate: Date? = null
            for (f in formats) {
                try {
                    parsedDate = f.parse(dateStr)
                    if (parsedDate != null) break
                } catch (e: Exception) { /* continue */ }
            }
            parsedDate?.time ?: (System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        } catch (e: Exception) {
            System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        }
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371e3 // metres of earth radius
        val phi1 = lat1 * PI / 180.0
        val phi2 = lat2 * PI / 180.0
        val deltaPhi = (lat2 - lat1) * PI / 180.0
        val deltaLambda = (lon2 - lon1) * PI / 180.0

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return R * c
    }

    private fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            month in start..end
        } else {
            month >= start || month <= end
        }
    }

    private fun monthName(month: Int): String {
        return when (month) {
            1 -> "Jan"
            2 -> "Feb"
            3 -> "Mar"
            4 -> "Apr"
            5 -> "May"
            6 -> "Jun"
            7 -> "Jul"
            8 -> "Aug"
            9 -> "Sep"
            10 -> "Oct"
            11 -> "Nov"
            12 -> "Dec"
            else -> ""
        }
    }
}

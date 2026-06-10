package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.FungiDao
import com.example.data.remote.ALAApi
import com.example.data.remote.GBIFApi
import com.example.data.remote.INatObservation
import com.example.data.remote.EnvGridRequest
import com.example.data.remote.EnvLayersApi
import com.example.data.remote.GeocodingApi
import com.example.data.remote.INaturalistApi
import com.example.data.remote.OpenMeteoApi
import com.example.data.remote.OverpassApi
import com.example.model.HotspotCell
import com.example.model.MapObservation
import com.example.model.Observation
import com.example.model.Species
import com.example.model.UserSighting
import com.example.util.MycoMath
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
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
    private val openMeteoApi: OpenMeteoApi,
    private val alaApi: ALAApi,
    private val gbifApi: GBIFApi,
    private val overpassApi: OverpassApi,
    private val envLayersApi: EnvLayersApi? = null,
    private val backendToken: String = "",
    private val geocodingApi: GeocodingApi? = null,
    private val googleApiKey: String = ""
) {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
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
            context.assets.open("species.json").use { inputStream ->
                val reader = InputStreamReader(inputStream)
                val jsonString = reader.readText()
                val listType = Types.newParameterizedType(List::class.java, Species::class.java)
                val adapter = moshi.adapter<List<Species>>(listType)
                val speciesList = adapter.fromJson(jsonString)
                if (speciesList != null) {
                    // Upsert when the bundled catalogue and the DB differ in size
                    // (fresh install, or an app update that adds species). The
                    // species table is independent of user sightings, and inserts
                    // REPLACE on conflict, so this never touches user data.
                    val existingCount = dao.getAllSpecies().size
                    if (existingCount != speciesList.size) {
                        dao.insertSpecies(speciesList)
                        Log.d(TAG, "Seeded/updated species catalogue: $existingCount -> ${speciesList.size}.")
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
     * Retry a suspending IO block with exponential backoff. Network calls to
     * iNat/ALA/GBIF/Open-Meteo are flaky on mobile; one transient failure
     * shouldn't drop a whole data source. Re-throws only after the last try.
     */
    private suspend fun <T> retryIO(
        times: Int = 3,
        initialDelayMs: Long = 400L,
        block: suspend () -> T
    ): T {
        var delayMs = initialDelayMs
        var lastError: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
                if (attempt < times - 1) {
                    Log.w(TAG, "retry ${attempt + 1}/$times after: ${e.message}")
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        throw lastError ?: IllegalStateException("retryIO failed")
    }

    // All-fungi map layer: every fungal observation in an area, keyed by a
    // coarse grid + radius and cached briefly so panning is cheap.
    private val areaObsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<MapObservation>>>()
    private val AREA_OBS_TTL_MS = 30 * 60 * 1000L

    /**
     * Every fungal sighting in the map area, fetched in ONE kingdom-wide
     * iNaturalist request (taxon_name=Fungi) rather than per species. Powers
     * the "all sightings" map layer and a generic fungal-activity signal in the
     * aggregate prediction. Fails soft to an empty list (never breaks the map).
     */
    suspend fun getAllFungiObservations(
        lat: Double,
        lng: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false
    ): List<MapObservation> = withContext(Dispatchers.IO) {
        val key = "${"%.2f".format(lat)}_${"%.2f".format(lng)}_${radiusKm.toInt()}"
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            areaObsCache[key]?.let { (ts, value) ->
                if (now - ts < AREA_OBS_TTL_MS) return@withContext value
            }
        }
        val result = try {
            val cal = Calendar.getInstance().apply { add(Calendar.YEAR, -5) }
            val since = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            val resp = retryIO {
                iNatApi.getAreaObservations(
                    lat = lat, lng = lng, radiusKm = radiusKm, sinceDate = since
                )
            }
            resp.results.mapNotNull { o ->
                val geoLng = o.geojson?.coordinates?.getOrNull(0)
                val geoLat = o.geojson?.coordinates?.getOrNull(1)
                val pLat = o.latitude ?: o.location?.split(",")?.getOrNull(0)?.trim()?.toDoubleOrNull() ?: geoLat
                val pLng = o.longitude ?: o.location?.split(",")?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: geoLng
                if (pLat == null || pLng == null) return@mapNotNull null
                MapObservation(
                    id = o.id,
                    lat = pLat,
                    lng = pLng,
                    taxonName = o.taxon?.name ?: "Unidentified fungus",
                    commonName = o.taxon?.commonName,
                    source = "iNaturalist",
                    observedAt = parseObsDate(o.observedOn),
                    qualityGrade = o.qualityGrade ?: "needs_id",
                    photoUrl = o.photos?.firstOrNull()?.url,
                    placeGuess = o.placeGuess
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "all-fungi observations fetch failed: ${e.message}")
            emptyList()
        }
        areaObsCache[key] = now to result
        result
    }

    // Global fungal taxonomy search results, cached per query.
    private val globalSearchCache = java.util.concurrent.ConcurrentHashMap<String, List<Species>>()

    /**
     * Search the ENTIRE described fungal kingdom via the GBIF species backbone
     * (~150k species worldwide), returning lightweight Species records that
     * reuse the normal detail screen (photos load live from iNaturalist by
     * scientific name). This makes the catalogue a front-end over every
     * described fungus on Earth, not just the bundled field guide. Fails soft.
     */
    suspend fun searchGlobalFungi(query: String): List<Species> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 3) return@withContext emptyList()
        globalSearchCache[q.lowercase()]?.let { return@withContext it }
        val result = try {
            val resp = retryIO { gbifApi.searchSpecies(query = q) }
            (resp.results ?: emptyList()).mapNotNull { g ->
                val sci = (g.canonicalName ?: g.scientificName)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val key = g.key ?: sci.hashCode().toLong()
                val common = g.vernacularNames
                    ?.firstOrNull { it.language.equals("eng", true) || it.language.equals("en", true) }
                    ?.vernacularName
                    ?: g.vernacularNames?.firstOrNull()?.vernacularName
                Species(
                    id = "gbif_$key",
                    scientificName = sci,
                    commonNames = listOfNotNull(common?.takeIf { it.isNotBlank() }),
                    genus = g.genus?.takeIf { it.isNotBlank() } ?: sci.substringBefore(" "),
                    family = g.family?.takeIf { it.isNotBlank() } ?: "Unknown family",
                    habitatTypes = emptyList(),
                    substrates = emptyList(),
                    seasonStart = 1,
                    seasonEnd = 12,
                    capDescription = "No curated field description yet — this is a global " +
                        "taxonomy record. Swipe the reference photos below (live from " +
                        "iNaturalist) and always cross-check with an expert before relying " +
                        "on any identification.",
                    gillDescription = "Not catalogued.",
                    stemDescription = "Not catalogued.",
                    sporeColor = "—",
                    bruisingReaction = "—",
                    lookAlikes = emptyList(),
                    notes = "Global catalogue entry from the GBIF fungal taxonomy" +
                        (g.family?.let { " · $it" } ?: "") +
                        (g.order?.let { " · $it" } ?: "") + ". Photos and observations are " +
                        "pulled live from iNaturalist.",
                    imageUrls = emptyList()
                )
            }.distinctBy { it.scientificName.lowercase() }
        } catch (e: Exception) {
            Log.w(TAG, "global fungi search failed for '$q': ${e.message}")
            emptyList()
        }
        globalSearchCache[q.lowercase()] = result
        result
    }

    // Reference-photo gallery per species (scientific name → photos with
    // attribution), pulled from iNaturalist taxon photos. Cached for the
    // process lifetime so revisiting a species detail is instant and free.
    private val speciesPhotoCache = java.util.concurrent.ConcurrentHashMap<String, List<com.example.model.SpeciesPhoto>>()

    /** Tidy iNaturalist's attribution string for compact on-image display. */
    private fun cleanAttribution(raw: String?): String? =
        raw?.replace("(c)", "©")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

    /**
     * A gallery of reference photos (with CC attribution) for a species,
     * sourced from iNaturalist's curated taxon photos. URLs load directly via
     * Coil — no images bundled in the APK. Fails soft to an empty list.
     */
    suspend fun fetchSpeciesPhotos(scientificName: String): List<com.example.model.SpeciesPhoto> =
        withContext(Dispatchers.IO) {
            speciesPhotoCache[scientificName]?.let { return@withContext it }
            val photos = try {
                val taxon = iNatApi.getTaxa(scientificName).results?.firstOrNull()
                val gallery = taxon?.taxonPhotos.orEmpty().mapNotNull { wrap ->
                    val p = wrap.photo ?: return@mapNotNull null
                    val url = p.mediumUrl ?: p.url ?: return@mapNotNull null
                    com.example.model.SpeciesPhoto(url, cleanAttribution(p.attribution))
                }
                val withDefault = if (gallery.isEmpty()) {
                    val dp = taxon?.defaultPhoto
                    val url = dp?.mediumUrl ?: dp?.url
                    if (url != null) listOf(com.example.model.SpeciesPhoto(url, cleanAttribution(dp?.attribution)))
                    else emptyList()
                } else gallery
                withDefault.distinctBy { it.url }.take(12)
            } catch (e: Exception) {
                Log.w(TAG, "species photos fetch failed for $scientificName: ${e.message}")
                emptyList()
            }
            speciesPhotoCache[scientificName] = photos
            photos
        }

    /** Image URLs only — used for list thumbnails and the global-search filter. */
    suspend fun fetchSpeciesImages(scientificName: String): List<String> =
        fetchSpeciesPhotos(scientificName).map { it.url }

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

            val response = retryIO {
                iNatApi.getObservations(
                    taxonName = species.scientificName,
                    lat = lat,
                    lng = lng,
                    radiusKm = radiusKm,
                    sinceDate = sinceDate
                )
            }

            val iNatObsList = response.results
            val freshObservations = iNatObsList.mapNotNull { iObs ->
                // Parse coordinates. iNaturalist usually returns coordinates in the
                // `geojson` field as [lng, lat], and/or `location` as "lat,lng".
                // Try the explicit lat/lng first, then location string, then geojson.
                val geoLng = iObs.geojson?.coordinates?.getOrNull(0)
                val geoLat = iObs.geojson?.coordinates?.getOrNull(1)
                val parsedLat = iObs.latitude
                    ?: iObs.location?.split(",")?.getOrNull(0)?.trim()?.toDoubleOrNull()
                    ?: geoLat
                val parsedLng = iObs.longitude
                    ?: iObs.location?.split(",")?.getOrNull(1)?.trim()?.toDoubleOrNull()
                    ?: geoLng

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

            // Also pull museum/herbarium records from ALA and GBIF, in PARALLEL
            // with each other and with retry. Failures are logged (not silently
            // swallowed) and degrade to empty so iNat data still comes through.
            val (alaObs, gbifObs) = coroutineScope {
                val alaDeferred = async {
                    try { retryIO { getALAObservations(species, lat, lng, radiusKm) } }
                    catch (e: Exception) { Log.w(TAG, "ALA fetch failed for ${species.scientificName}: ${e.message}"); emptyList() }
                }
                val gbifDeferred = async {
                    try { retryIO { getGBIFObservations(species, lat, lng, radiusKm) } }
                    catch (e: Exception) { Log.w(TAG, "GBIF fetch failed for ${species.scientificName}: ${e.message}"); emptyList() }
                }
                alaDeferred.await() to gbifDeferred.await()
            }

            val allFresh = freshObservations + alaObs + gbifObs

            // Save new network result to local Room Cache
            if (allFresh.isNotEmpty()) {
                // Clear and replace cache for this species to maintain fresh cache representation
                dao.clearObservationsForSpecies(species.id)
                dao.insertObservations(allFresh)
                Log.d(TAG, "Fetched and cached ${allFresh.size} observations (iNat: ${freshObservations.size}, ALA: ${alaObs.size}, GBIF: ${gbifObs.size}) in Room.")
            }

            // Return observations filtered by radius
            return@withContext allFresh.filter {
                calculateDistanceMeters(lat, lng, it.lat, it.lng) <= radiusKm * 1000.0
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch from iNaturalist API, returning stale cache", e)
            // Offline fallback: return whatever elements we have cached (even if stale) as it is offline-first!
            return@withContext inRadiusCached
        }
    }

    // ─── ALA (Atlas of Living Australia) ─────────────────────────────

    /**
     * Fetches fungal occurrence records from the Atlas of Living Australia.
     * Herbarium specimens (PRESERVED_SPECIMEN) from institutions like
     * Royal Botanic Gardens Melbourne are weighted highest — verified by
     * professional mycologists.
     */
    suspend fun getALAObservations(
        species: Species,
        lat: Double,
        lng: Double,
        radiusKm: Double
    ): List<Observation> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching ALA records for ${species.scientificName}")
            val response = alaApi.searchOccurrences(
                query = species.scientificName,
                lat = lat,
                lon = lng,
                radiusKm = radiusKm
            )
            val nowMs = System.currentTimeMillis()
            response.occurrences?.mapNotNull { occ ->
                val olat = occ.decimalLatitude ?: return@mapNotNull null
                val olng = occ.decimalLongitude ?: return@mapNotNull null
                val obsTime = parseYearMonth(occ.year, occ.month)
                // Herbarium specimens are research-grade by definition
                val quality = if (occ.basisOfRecord == "PRESERVED_SPECIMEN") "research" else "needs_id"
                Observation(
                    id = occ.uuid.hashCode().toLong(),
                    speciesId = species.id,
                    lat = olat, lng = olng,
                    observedAt = obsTime,
                    source = "ALA",
                    photoUrl = null,
                    qualityGrade = quality,
                    cachedAt = nowMs
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "ALA fetch failed for ${species.scientificName}: ${e.message}")
            emptyList()
        }
    }

    // ─── GBIF (Global Biodiversity Information Facility) ──────────

    /**
     * Fetches occurrence records from GBIF for Australian fungi.
     * Includes museum/herbarium records with DOIs — highest scientific value.
     */
    suspend fun getGBIFObservations(
        species: Species,
        lat: Double,
        lng: Double,
        radiusKm: Double
    ): List<Observation> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching GBIF records for ${species.scientificName}")
            // GBIF uses bounding box via lat/lon ranges
            val latRange = radiusKm / 111.0
            val lngRange = radiusKm / (111.0 * cos(lat * PI / 180.0))
            val response = gbifApi.searchOccurrences(
                scientificName = species.scientificName,
                lat = "${String.format(Locale.US, "%.2f", lat - latRange)},${String.format(Locale.US, "%.2f", lat + latRange)}",
                lon = "${String.format(Locale.US, "%.2f", lng - lngRange)},${String.format(Locale.US, "%.2f", lng + lngRange)}"
            )
            val nowMs = System.currentTimeMillis()
            response.results?.mapNotNull { occ ->
                val olat = occ.decimalLatitude ?: return@mapNotNull null
                val olng = occ.decimalLongitude ?: return@mapNotNull null
                val obsTime = parseYearMonth(occ.year, occ.month)
                val quality = if (occ.basisOfRecord == "PRESERVED_SPECIMEN") "research" else "needs_id"
                Observation(
                    id = occ.key ?: occ.hashCode().toLong(),
                    speciesId = species.id,
                    lat = olat, lng = olng,
                    observedAt = obsTime,
                    source = "GBIF",
                    photoUrl = null,
                    qualityGrade = quality,
                    cachedAt = nowMs
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "GBIF fetch failed for ${species.scientificName}: ${e.message}")
            emptyList()
        }
    }

    private fun parseYearMonth(year: Int?, month: Int?): Long {
        if (year == null) return System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, (month ?: 6) - 1)
        cal.set(Calendar.DAY_OF_MONTH, 15)
        return cal.timeInMillis
    }

    // ─── Weather Data ────────────────────────────────────────────────

    /**
     * Detailed weather data for the prediction engine.
     * Returns daily rainfall array (oldest first), avg max temp, avg min temp,
     * and total rainfall over the period.
     */
    data class WeatherData(
        val dailyRainfallMm: List<Double>,
        val totalRainfallMm: Double,
        val avgMaxTemp: Double,
        val avgMinTemp: Double,
        val avgTemp: Double,
        val avgSoilMoisture: Double? = null // volumetric m³/m³, 0-7cm layer; null if unavailable
    )

    suspend fun getDetailedWeather(lat: Double, lng: Double): WeatherData = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching detailed weather from Open-Meteo for ($lat, $lng)")
            val response = openMeteoApi.getPastWeather(limitLat = lat, limitLng = lng)

            val rainfall = response.daily.precipitationSum
                ?.map { it ?: 0.0 } ?: emptyList()
            val maxTemps = response.daily.temperatureMax
                ?.mapNotNull { it } ?: emptyList()
            val minTemps = response.daily.temperatureMin
                ?.mapNotNull { it } ?: emptyList()

            val totalRainfall = rainfall.sum()
            val avgMax = if (maxTemps.isNotEmpty()) maxTemps.average() else 15.0
            val avgMin = if (minTemps.isNotEmpty()) minTemps.average() else 8.0

            // Recent soil moisture (last ~7 days of hourly readings, 0-7cm layer).
            val soil = response.hourly?.soilMoisture0to7cm?.mapNotNull { it } ?: emptyList()
            val avgSoil = if (soil.isNotEmpty()) soil.takeLast(minOf(168, soil.size)).average() else null

            Log.d(TAG, "Weather (${rainfall.size} days): Rain: ${totalRainfall}mm, AvgMax: ${avgMax}C, AvgMin: ${avgMin}C, Soil: $avgSoil")
            WeatherData(rainfall, totalRainfall, avgMax, avgMin, (avgMax + avgMin) / 2.0, avgSoil)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch detailed weather, using Victorian autumn defaults", e)
            // Default: moderate Victorian autumn conditions
            val defaultDaily = List(45) { 3.0 } // ~3mm per day
            WeatherData(defaultDaily, 135.0, 15.5, 8.0, 11.75)
        }
    }

    // ── Session caches, keyed by a global ~500 m grid index ─────────────
    // Elevation and land cover are static; canopy/NDVI drift slowly. Caching
    // makes re-scoring the same area (species/radius changes, revisits, small
    // pans) instant and avoids repeat network / Earth-Engine cost.
    private val elevCache = java.util.concurrent.ConcurrentHashMap<Long, Double>()
    private data class EnvCell(val landcover: Int?, val canopyPct: Double?, val ndvi: Double?, val waterDistM: Double?)
    private val envCache = java.util.concurrent.ConcurrentHashMap<Long, EnvCell>()

    /** Snap a coordinate to a global grid index so the same place always keys the same. */
    private fun gridKey(lat: Double, lng: Double): Long {
        val la = Math.round(lat / 0.0045) + 4_000_000L
        val ln = Math.round(lng / 0.0057) + 4_000_000L
        return la * 10_000_000L + ln
    }

    // ── Persistent (disk) backing for the session caches ────────────────
    // Elevation and land cover are static, so persisting them across app
    // restarts makes revisiting a region instant and free (no re-fetch). All
    // file I/O is guarded — on any error it silently behaves like in-memory.
    @Volatile private var cachesLoaded = false
    private val elevCacheFile by lazy { java.io.File(context.cacheDir, "elev_cache.tsv") }
    private val envCacheFile by lazy { java.io.File(context.cacheDir, "env_cache.tsv") }
    private val CACHE_MAX = 8000

    private fun ensureCachesLoaded() {
        if (cachesLoaded) return
        synchronized(this) {
            if (cachesLoaded) return
            try {
                if (elevCacheFile.exists()) elevCacheFile.forEachLine { line ->
                    val p = line.split('\t')
                    if (p.size == 2) p[0].toLongOrNull()?.let { k -> p[1].toDoubleOrNull()?.let { v -> elevCache[k] = v } }
                }
            } catch (e: Exception) { Log.w(TAG, "elev cache load failed: ${e.message}") }
            try {
                if (envCacheFile.exists()) envCacheFile.forEachLine { line ->
                    val p = line.split('\t')
                    if (p.size == 5) p[0].toLongOrNull()?.let { k ->
                        envCache[k] = EnvCell(p[1].toIntOrNull(), p[2].toDoubleOrNull(), p[3].toDoubleOrNull(), p[4].toDoubleOrNull())
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "env cache load failed: ${e.message}") }
            cachesLoaded = true
        }
    }

    private fun persistElevCache() {
        try {
            elevCacheFile.bufferedWriter().use { w ->
                elevCache.entries.asSequence().take(CACHE_MAX).forEach { w.write("${it.key}\t${it.value}\n") }
            }
        } catch (e: Exception) { Log.w(TAG, "elev cache save failed: ${e.message}") }
    }

    private fun persistEnvCache() {
        try {
            envCacheFile.bufferedWriter().use { w ->
                envCache.entries.asSequence().take(CACHE_MAX).forEach { (k, v) ->
                    w.write("$k\t${v.landcover ?: ""}\t${v.canopyPct ?: ""}\t${v.ndvi ?: ""}\t${v.waterDistM ?: ""}\n")
                }
            }
        } catch (e: Exception) { Log.w(TAG, "env cache save failed: ${e.message}") }
    }

    /**
     * Fetches ground elevation for a list of coordinates, batched into
     * requests of ≤100 points (Open-Meteo's per-call limit) and served from a
     * session cache where possible. Returns a list aligned 1:1 with [coords];
     * entries are null where elevation could not be resolved, so callers can
     * fall back to neutral terrain scoring.
     */
    suspend fun fetchElevations(coords: List<Pair<Double, Double>>): List<Double?> = withContext(Dispatchers.IO) {
        if (coords.isEmpty()) return@withContext emptyList()
        ensureCachesLoaded()
        val out = MutableList<Double?>(coords.size) { null }
        val missIdx = ArrayList<Int>()
        val missCoords = ArrayList<Pair<Double, Double>>()
        for (i in coords.indices) {
            val cached = elevCache[gridKey(coords[i].first, coords[i].second)]
            if (cached != null) out[i] = cached else { missIdx.add(i); missCoords.add(coords[i]) }
        }
        if (missCoords.isNotEmpty()) {
            try {
                val fetched = ArrayList<Double?>(missCoords.size)
                for (chunk in missCoords.chunked(100)) {
                    val latCsv = chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.first) }
                    val lngCsv = chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.second) }
                    val resp = openMeteoApi.getElevation(latCsv, lngCsv)
                    val elevs = resp.elevation ?: emptyList()
                    for (k in chunk.indices) fetched.add(elevs.getOrNull(k))
                }
                for (k in missIdx.indices) {
                    val v = fetched.getOrNull(k)
                    out[missIdx[k]] = v
                    if (v != null) elevCache[gridKey(missCoords[k].first, missCoords[k].second)] = v
                }
            } catch (e: Exception) {
                Log.w(TAG, "Elevation fetch failed, using neutral terrain: ${e.message}")
            }
            persistElevCache()
        }
        out
    }

    /**
     * Fetches canopy/green-cover features (woods, forests, parks, reserves)
     * within a bounding box from OpenStreetMap's Overpass API (free, no key).
     * Returns representative coordinates; empty on failure so callers fall
     * back to neutral canopy scoring.
     */
    suspend fun fetchCanopyFeatures(
        minLat: Double, minLng: Double, maxLat: Double, maxLng: Double
    ): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        try {
            val bbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f", minLat, minLng, maxLat, maxLng)
            val ql = """
                [out:json][timeout:25];
                (
                  way["natural"="wood"]($bbox);
                  way["landuse"="forest"]($bbox);
                  way["leisure"="park"]($bbox);
                  way["boundary"="protected_area"]($bbox);
                );
                out center;
            """.trimIndent()
            val resp = overpassApi.query(ql)
            resp.elements.orEmpty().mapNotNull { el ->
                val la = el.resolvedLat()
                val lo = el.resolvedLon()
                if (la != null && lo != null) la to lo else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Canopy (Overpass) fetch failed, neutral canopy: ${e.message}")
            emptyList()
        }
    }

    /** Per-cell Earth Engine layers, aligned 1:1 with the grid points. */
    data class EnvLayers(
        val landcover: List<Int?>,
        val canopyPct: List<Double?>,
        val ndvi: List<Double?>,
        val waterDistM: List<Double?>
    )

    /**
     * Fetches Earth Engine land-cover / tree-canopy / NDVI for the grid from
     * the optional Cloud Run backend. Returns null when the backend isn't
     * configured or the call fails, so callers fall back to free OSM canopy.
     */
    suspend fun fetchEnvLayers(points: List<Pair<Double, Double>>): EnvLayers? {
        val api = envLayersApi ?: return null
        if (points.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            ensureCachesLoaded()
            val landcover = arrayOfNulls<Int>(points.size)
            val canopyPct = arrayOfNulls<Double>(points.size)
            val ndvi = arrayOfNulls<Double>(points.size)
            val waterDist = arrayOfNulls<Double>(points.size)
            var haveAny = false
            val missIdx = ArrayList<Int>()
            val missPts = ArrayList<Pair<Double, Double>>()
            for (i in points.indices) {
                val c = envCache[gridKey(points[i].first, points[i].second)]
                if (c != null) {
                    landcover[i] = c.landcover; canopyPct[i] = c.canopyPct; ndvi[i] = c.ndvi; waterDist[i] = c.waterDistM
                    haveAny = true
                } else {
                    missIdx.add(i); missPts.add(points[i])
                }
            }
            // Fetch misses in chunks — the backend caps a request at 600 points,
            // so a large search radius must be split or EE is lost for the grid.
            var k = 0
            while (k < missPts.size) {
                val end = minOf(k + 500, missPts.size)
                try {
                    val chunk = missPts.subList(k, end)
                    val resp = api.envGrid(backendToken, EnvGridRequest(chunk.map { listOf(it.first, it.second) }))
                    for (c in chunk.indices) {
                        val lc = resp.landcover?.getOrNull(c)?.toInt()
                        val cp = resp.canopy?.getOrNull(c)
                        val nv = resp.ndvi?.getOrNull(c)
                        val wd = resp.waterDist?.getOrNull(c)
                        val orig = missIdx[k + c]
                        landcover[orig] = lc; canopyPct[orig] = cp; ndvi[orig] = nv; waterDist[orig] = wd
                        envCache[gridKey(missPts[k + c].first, missPts[k + c].second)] = EnvCell(lc, cp, nv, wd)
                        haveAny = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Earth Engine chunk fetch failed: ${e.message}")
                }
                k = end
            }
            if (missPts.isNotEmpty()) persistEnvCache()
            // Fall back to OSM canopy only if we got nothing at all.
            if (!haveAny) return@withContext null
            EnvLayers(landcover.toList(), canopyPct.toList(), ndvi.toList(), waterDist.toList())
        }
    }

    /** A geocoded place: coordinates plus a human-readable label. */
    data class GeoPlace(val lat: Double, val lng: Double, val label: String)

    /**
     * Resolves a place name to coordinates for the map's "Area" search.
     * Prefers the Google Geocoding API when a key is configured (reliable),
     * and falls back to the on-device Android Geocoder otherwise.
     */
    suspend fun geocodePlace(query: String): GeoPlace? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext null
        if (geocodingApi != null && googleApiKey.isNotBlank()) {
            try {
                val resp = geocodingApi.geocode(query, googleApiKey)
                val result = resp.results?.firstOrNull()
                val loc = result?.geometry?.location
                if (resp.status == "OK" && loc?.lat != null && loc.lng != null) {
                    return@withContext GeoPlace(loc.lat, loc.lng, result.formattedAddress ?: query)
                }
                Log.w(TAG, "Google geocoding returned status=${resp.status}; falling back")
            } catch (e: Exception) {
                Log.w(TAG, "Google geocoding failed, trying device geocoder: ${e.message}")
            }
        }
        try {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val res = geocoder.getFromLocationName(query, 1)
            val a = res?.firstOrNull()
            if (a != null) GeoPlace(a.latitude, a.longitude, a.locality ?: a.subAdminArea ?: a.adminArea ?: query) else null
        } catch (e: Exception) {
            Log.w(TAG, "Device geocoder failed: ${e.message}")
            null
        }
    }

    /**
     * Reverse-geocodes coordinates to a short place name for the map header.
     * Google Geocoding when a key is set (reliable, descriptive), else the
     * on-device geocoder, else a GPS string.
     */
    suspend fun reverseGeocode(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        if (geocodingApi != null && googleApiKey.isNotBlank()) {
            try {
                val resp = geocodingApi.reverseGeocode(String.format(Locale.US, "%.6f,%.6f", lat, lng), googleApiKey)
                val label = resp.results?.firstOrNull()?.formattedAddress
                if (resp.status == "OK" && !label.isNullOrBlank()) {
                    // Trim a verbose address to its first two components.
                    return@withContext label.split(",").take(2).joinToString(",").trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google reverse geocoding failed: ${e.message}")
            }
        }
        try {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val a = geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
            if (a != null) {
                val city = a.locality ?: a.subAdminArea ?: a.adminArea ?: ""
                val country = a.countryCode ?: a.countryName ?: ""
                listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ").ifBlank { null }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Distance (m) to the nearest green feature, or null if none provided. */
    private fun nearestFeatureMeters(lat: Double, lng: Double, features: List<Pair<Double, Double>>): Double? {
        if (features.isEmpty()) return null
        var best = Double.MAX_VALUE
        for ((flat, flng) in features) {
            val d = calculateDistanceMeters(lat, lng, flat, flng)
            if (d < best) best = d
        }
        return best
    }

    /**
     * Fetches rainfall and temperature in past 30 days from Open-Meteo.
     * Returns total rainfall in mm, and average max recorded temperature in C.
     * (Legacy method — kept for weather summary display in UI)
     */
    suspend fun getWeatherLast30Days(lat: Double, lng: Double): Pair<Double, Double> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching rainfall and temperature from Open-Meteo for ($lat, $lng)")
            val response = openMeteoApi.getPastWeather(limitLat = lat, limitLng = lng)
            val sumList = response.daily.precipitationSum
            val maxList = response.daily.temperatureMax

            // Sum of last 30 days rainfall
            val totalRainfall = sumList?.filterNotNull()?.sum() ?: 0.0

            // Average max temp over past 30 days
            val avgMaxTemp = maxList?.filterNotNull()?.average() ?: 15.0

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
     * Multi-factor Bayesian hotspot prediction engine (500m resolution).
     *
     * Scoring factors and weights (sum to 1.0):
     *   1. Observation evidence (iNat + ALA + GBIF + user)          — 0.22
     *   2. Seasonal fitness (week-level precision)                 — 0.16
     *   3. Rainfall trigger (20mm+ event 10-21 days ago with lag)  — 0.11
     *   4. Canopy/forest (EE land cover/canopy/NDVI, or OSM)       — 0.11
     *   5. Terrain moisture (per-cell slope/concavity from DEM)    — 0.08
     *   6. Habitat suitability (species substrate/habitat breadth) — 0.08
     *   7. Elevation fitness (per-cell altitude vs species band)   — 0.06
     *   8. Temperature fitness (species-specific ideal range)      — 0.06
     *   9. Riparian (per-cell distance to surface water, EE)       — 0.04
     *  10. Slope aspect (per-cell; south/east-facing favoured)     — 0.04
     *  11. Background moisture (rainfall + real 0-7cm soil moisture)— 0.03
     *  12. Moon phase (optional, traditional forager signal)       — 0.01
     *
     * Factors 4, 5, 7, 9 and 10 vary cell-to-cell (real elevation + EE/OSM),
     * so the map reflects genuine landscape instead of only record density.
     * The weighted score is then multiplied by a season/rain penalty AND a
     * habitat gate that collapses built-up/water/bare cells toward zero.
     */
    suspend fun generateHotspots(
        species: Species,
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false
    ): List<HotspotCell> = withContext(Dispatchers.Default) {
        // ── 1. Gather all evidence sources ──────────────────────────
        val iNatObs = getObservations(species, centerLat, centerLng, radiusKm, forceRefresh)
        val userSightings = dao.getAllUserSightings().filter {
            it.speciesId == species.id &&
            calculateDistanceMeters(centerLat, centerLng, it.lat, it.lng) <= radiusKm * 1000.0
        }
        // ── 2. Fetch detailed weather for lag analysis ──────────────
        val weather = getDetailedWeather(centerLat, centerLng)

        // ── 3. Pre-compute global (non-cell-varying) factors ────────
        val nowMs = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH) + 1
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

        // 3a. Seasonal fitness (week-level, species-specific)
        val seasonScore = MycoMath.seasonalFitness(dayOfYear, species.seasonStart, species.seasonEnd)

        // 3b. Rainfall trigger (lag analysis: was there a trigger event 10-21 days ago?)
        val rainTriggerScore = MycoMath.rainfallTriggerScore(weather.dailyRainfallMm)

        // 3c. Temperature fitness (species-specific ideal range)
        val tempScore = MycoMath.temperatureFitness(weather.avgTemp, species.id)

        // 3d. Background moisture proxy (sustained rain, not just trigger events)
        val recentRain30d = weather.dailyRainfallMm.takeLast(minOf(30, weather.dailyRainfallMm.size)).sum()
        val rainMoistureScore = when {
            recentRain30d in 40.0..180.0 -> 1.0
            recentRain30d < 40.0 -> recentRain30d / 40.0
            else -> maxOf(0.3, 1.0 - (recentRain30d - 180.0) / 200.0)
        }
        // Blend in real 0-7cm soil moisture when available — a far better
        // dampness signal than rainfall totals alone.
        val soilMoistureScore = weather.avgSoilMoisture?.let { MycoMath.soilMoistureFitness(it) }
        val moistureScore = if (soilMoistureScore != null)
            (0.4 * rainMoistureScore + 0.6 * soilMoistureScore) else rainMoistureScore

        // 3e. Moon phase (low-weight traditional signal)
        val moonScore = MycoMath.moonFruitingScore(nowMs)

        // 3f. Habitat suitability (species breadth → higher baseline)
        val habitatScore = MycoMath.habitatDiversityScore(species.habitatTypes, species.substrates)
        val habitatWeight = MycoMath.speciesHabitatWeight(species.id)

        // ── 4. Grid generation ──────────────────────────────────────
        val latStep = 0.0045
        val lngStep = 0.0057
        val latRangeSteps = ceil((radiusKm * 1000.0) / 500.0).toInt()
        val cells = mutableListOf<HotspotCell>()

        val kernelRadiusMeters = 2500.0  // Wider search for evidence
        val maxDaysBack = 5.0 * 365.0

        // 4a. Enumerate the in-radius cells up front so the real terrain
        // elevation for the whole grid can be fetched in one batched call.
        val cellIJ = mutableListOf<Pair<Int, Int>>()
        val cellLL = mutableListOf<Pair<Double, Double>>()
        for (i in -latRangeSteps..latRangeSteps) {
            for (j in -latRangeSteps..latRangeSteps) {
                val cLat = centerLat + i * latStep
                val cLng = centerLng + j * lngStep
                if (calculateDistanceMeters(centerLat, centerLng, cLat, cLng) > radiusKm * 1000.0) continue
                cellIJ.add(i to j)
                cellLL.add(cLat to cLng)
            }
        }

        // 4b. Per-cell ground elevation (Open-Meteo, no key). Build a lookup
        // so each cell can read its neighbours' elevations for slope/aspect.
        val elevations = fetchElevations(cellLL)
        val elevByIJ = HashMap<Pair<Int, Int>, Double>()
        cellIJ.forEachIndexed { idx, ij -> elevations[idx]?.let { elevByIJ[ij] = it } }

        // 4c. Canopy/vegetation. Prefer Earth Engine layers (land cover, tree
        // canopy %, NDVI) when the backend is configured; otherwise fall back
        // to free OSM forest proximity.
        val env = fetchEnvLayers(cellLL)
        val canopy = if (env == null && cellLL.isNotEmpty()) fetchCanopyFeatures(
            cellLL.minOf { it.first }, cellLL.minOf { it.second },
            cellLL.maxOf { it.first }, cellLL.maxOf { it.second }
        ) else emptyList()

        for (idx in cellIJ.indices) {
            coroutineContext.ensureActive()
            val (i, j) = cellIJ[idx]
            val (cellLat, cellLng) = cellLL[idx]

                // ── A. Observation evidence score ───────────────────
                var weightedEvidence = 0.0
                var nearbyRecords = 0
                val sourceCounts = mutableMapOf<String, Int>()

                // All observations — weighted by quality, source, recency, proximity
                for (obs in iNatObs) {
                    if (abs(obs.lat - cellLat) > 0.025 || abs(obs.lng - cellLng) > 0.035) continue
                    val d = calculateDistanceMeters(cellLat, cellLng, obs.lat, obs.lng)
                    if (d > kernelRadiusMeters) continue
                    val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60 * 60 * 24)
                    if (diffDays !in 0.0..maxDaysBack) continue

                    val quality = MycoMath.qualityWeight(obs.qualityGrade)
                    val sourceW = MycoMath.sourceWeight(obs.source)
                    val recency = MycoMath.recencyWeight(diffDays, halfLifeDays = 365.0)
                    val spatial = MycoMath.spatialKernel(d, sigma = 800.0)
                    weightedEvidence += quality * sourceW * recency * spatial
                    nearbyRecords++
                    sourceCounts[obs.source] = (sourceCounts[obs.source] ?: 0) + 1
                }

                // User sightings — higher trust (first-hand, georeferenced)
                for (sig in userSightings) {
                    if (abs(sig.lat - cellLat) > 0.025 || abs(sig.lng - cellLng) > 0.035) continue
                    val d = calculateDistanceMeters(cellLat, cellLng, sig.lat, sig.lng)
                    if (d > kernelRadiusMeters) continue
                    val diffDays = (nowMs - sig.timestamp).toDouble() / (1000.0 * 60 * 60 * 24)
                    if (diffDays !in 0.0..maxDaysBack) continue

                    val recency = MycoMath.recencyWeight(diffDays, halfLifeDays = 365.0)
                    val spatial = MycoMath.spatialKernel(d, sigma = 800.0)
                    weightedEvidence += 1.5 * recency * spatial // 1.5x for first-hand
                    nearbyRecords++
                }

                // Saturate: ~4 strong, recent, nearby records max out evidence
                val observationScore = minOf(1.0, weightedEvidence / 4.0)

                // ── B. Per-cell terrain factors (real elevation) ────
                // Elevation fitness and local slope/concavity vary cell-to-
                // cell, so the map reflects genuine landscape rather than
                // only observation-record density.
                val cellElev = elevations[idx]
                val neighbourElevs = listOf(
                    (i - 1) to j, (i + 1) to j, i to (j - 1), i to (j + 1),
                    (i - 1) to (j - 1), (i + 1) to (j + 1), (i - 1) to (j + 1), (i + 1) to (j - 1)
                ).mapNotNull { elevByIJ[it] }
                val elevationScore = if (cellElev != null)
                    MycoMath.elevationFitness(cellElev, species.id) else 0.6
                val terrainScore = if (cellElev != null && neighbourElevs.isNotEmpty())
                    MycoMath.terrainMoistureScore(cellElev, neighbourElevs) else 0.5
                // Slope aspect (south/east-facing favoured in S. Hemisphere).
                val aspectScore = if (cellElev != null) MycoMath.slopeAspectMoistureScore(
                    cellElev,
                    elevByIJ[(i + 1) to j], elevByIJ[(i - 1) to j],
                    elevByIJ[i to (j + 1)], elevByIJ[i to (j - 1)]
                ) else 0.7
                // Canopy/vegetation suitability — Earth Engine layers when
                // available, else OSM forest proximity (mycorrhizal & wood-rot).
                val canopyDist = if (canopy.isEmpty()) null else nearestFeatureMeters(cellLat, cellLng, canopy)
                val canopyScore = if (env != null) MycoMath.richCanopyScore(
                    env.canopyPct.getOrNull(idx), env.ndvi.getOrNull(idx), env.landcover.getOrNull(idx), species.id
                ) else MycoMath.canopyProximityScore(canopyDist)
                // Riparian: closeness to surface water (EE only; neutral otherwise).
                val riparianScore = if (env != null) MycoMath.riparianScore(env.waterDistM.getOrNull(idx)) else 0.45

                // ── C. Weighted factor combination ──────────────────
                // Evidence stays dominant; terrain, elevation, aspect and
                // canopy give real per-cell differentiation alongside the
                // global climate factors. Weights sum to 1.0.
                val adjustedHabitat = (habitatScore * habitatWeight).coerceIn(0.0, 1.0)

                val factorWeights = mapOf(
                    "evidence"    to 0.22,
                    "season"      to 0.16,
                    "rainTrigger" to 0.11,
                    "canopy"      to 0.11,
                    "terrain"     to 0.08,
                    "habitat"     to 0.08,
                    "elevation"   to 0.06,
                    "temperature" to 0.06,
                    "riparian"    to 0.04,
                    "aspect"      to 0.04,
                    "moisture"    to 0.03,
                    "moon"        to 0.01
                )
                val factorScores = mapOf(
                    "evidence"    to observationScore,
                    "season"      to seasonScore,
                    "rainTrigger" to rainTriggerScore,
                    "canopy"      to canopyScore,
                    "terrain"     to terrainScore,
                    "habitat"     to adjustedHabitat,
                    "elevation"   to elevationScore,
                    "temperature" to tempScore,
                    "riparian"    to riparianScore,
                    "aspect"      to aspectScore,
                    "moisture"    to moistureScore,
                    "moon"        to moonScore
                )

                // Weighted sum with multiplicative penalty:
                // If season OR rain trigger is very low, cap the score —
                // you won't find fungi out of season in dry conditions
                // regardless of historical evidence.
                val weightedSum = factorWeights.entries.sumOf { (k, w) ->
                    w * (factorScores[k] ?: 0.0)
                }
                val seasonRainFloor = minOf(seasonScore, rainTriggerScore + 0.2)
                val penaltyMultiplier = (0.3 + 0.7 * seasonRainFloor).coerceIn(0.0, 1.0)

                // Multiplicative HABITAT GATE — built-up/water/bare collapse the
                // score toward zero so cities, roads and car parks can't rank
                // high no matter how good the weather or how many records cluster
                // there. Uses real EE land cover/NDVI when available, else an OSM
                // canopy-proximity fallback.
                val habitatGate = if (env != null)
                    MycoMath.habitatGate(env.landcover.getOrNull(idx), env.ndvi.getOrNull(idx), species.id)
                else
                    (0.35 + 0.65 * canopyScore)

                val finalScore = (weightedSum * penaltyMultiplier * habitatGate).coerceIn(0.0, 1.0)

                // ── D. 5-tier classification ────────────────────────
                val tier = MycoMath.classifyTier(finalScore)

                // ── E. Contributing factors (human-readable) ────────
                val factors = mutableListOf<String>()
                factors.add("📍 Cell (${String.format(Locale.US, "%.4f", cellLat)}, ${String.format(Locale.US, "%.4f", cellLng)})")

                // Source breakdown badges
                val sourceStr = sourceCounts.entries.joinToString(" | ") { "${it.key}: ${it.value}" }
                val totalSources = if (sourceStr.isNotEmpty()) " [$sourceStr]" else ""
                factors.add("🔬 Evidence: $nearbyRecords record(s) within 2.5 km$totalSources → ${String.format(Locale.US, "%.0f", observationScore * 100)}%")

                factors.add("📅 Season: ${if (seasonScore > 0.5) "In window" else "Outside/shoulder"} ${monthName(species.seasonStart)}–${monthName(species.seasonEnd)} → ${String.format(Locale.US, "%.0f", seasonScore * 100)}%")
                factors.add("🌧️ Rain trigger: ${if (rainTriggerScore > 0.5) "Trigger event detected" else "No strong trigger"} (10-21d lag) → ${String.format(Locale.US, "%.0f", rainTriggerScore * 100)}%")
                factors.add("🌡️ Temperature: avg ${String.format(Locale.US, "%.1f", weather.avgTemp)}°C → ${String.format(Locale.US, "%.0f", tempScore * 100)}% fit for ${species.scientificName}")
                factors.add("🌲 Habitat: ${species.habitatTypes.joinToString(", ")} → ${String.format(Locale.US, "%.0f", adjustedHabitat * 100)}%")
                factors.add("🪵 Substrate: ${species.substrates.joinToString(", ")}")
                factors.add("⛰️ Elevation: ${if (cellElev != null) String.format(Locale.US, "%.0f m", cellElev) else "n/a"} → ${String.format(Locale.US, "%.0f", elevationScore * 100)}% fit")
                factors.add("🏞️ Terrain (slope/moisture): ${String.format(Locale.US, "%.0f", terrainScore * 100)}% | Aspect: ${String.format(Locale.US, "%.0f", aspectScore * 100)}%")
                if (env != null) {
                    val cv = env.canopyPct.getOrNull(idx)
                    val nv = env.ndvi.getOrNull(idx)
                    factors.add("🛰️ Earth Engine: canopy ${if (cv != null) String.format(Locale.US, "%.0f%%", cv) else "n/a"}, NDVI ${if (nv != null) String.format(Locale.US, "%.2f", nv) else "n/a"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%")
                } else {
                    factors.add("🌳 Canopy: ${if (canopyDist != null) "~${String.format(Locale.US, "%.0f m", canopyDist)} to woodland" else "no map data"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%")
                }
                if (env != null) {
                    val wd = env.waterDistM.getOrNull(idx)
                    factors.add("🌊 Water: ${if (wd != null) "~${String.format(Locale.US, "%.0f m", wd)} to water" else ">2 km away"} → ${String.format(Locale.US, "%.0f", riparianScore * 100)}%")
                }
                weather.avgSoilMoisture?.let { factors.add("💧 Soil moisture: ${String.format(Locale.US, "%.2f", it)} m³/m³ → ${String.format(Locale.US, "%.0f", MycoMath.soilMoistureFitness(it) * 100)}%") }
                if (moonScore > 0.7) factors.add("🌙 Moon phase favourable (traditional signal)")
                if (habitatGate < 0.95) factors.add("⛔ Habitat gate ×${String.format(Locale.US, "%.2f", habitatGate)} — built-up/water/bare ground suppresses this cell")
                factors.add("Multi-factor Bayesian estimate — not a guarantee of presence.")

                cells.add(HotspotCell(cellLat, cellLng, finalScore, tier, factors))
        }
        return@withContext cells
    }


    /**
     * Multi-species aggregate hotspot scoring.
     *
     * Answers "where am I likely to find ANY fungi here?" — combining
     * evidence and environmental factors across the entire catalogue.
     * Diversity (how many species are recorded nearby) is a first-class
     * signal that differentiates genuinely rich sites from monoculture spots.
     */
    suspend fun generateMultiSpeciesHotspots(
        centerLat: Double,
        centerLng: Double,
        radiusKm: Double,
        forceRefresh: Boolean = false
    ): List<HotspotCell> = withContext(Dispatchers.Default) {
        val allSpecies = dao.getAllSpecies()
        if (allSpecies.isEmpty()) return@withContext emptyList()

        // Pull observations for every catalogue species — in bounded-parallel
        // batches (not one-at-a-time) so the aggregate map isn't gated on 40
        // sequential round trips. Each failure is isolated and logged.
        val allObservations = mutableListOf<Observation>()
        for (batch in allSpecies.chunked(6)) {
            coroutineContext.ensureActive()
            val fetched = coroutineScope {
                batch.map { species ->
                    async {
                        try {
                            getObservations(species, centerLat, centerLng, radiusKm, forceRefresh)
                        } catch (e: Exception) {
                            Log.w(TAG, "Skipping ${species.scientificName} in aggregate fetch: ${e.message}")
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
            fetched.forEach { allObservations.addAll(it) }
        }

        // Fold in EVERY nearby fungal sighting (kingdom-wide, incl. species not
        // in the catalogue) so the aggregate prediction reflects real fungal
        // activity on the ground, not only our 40 species. Deduped by iNat id
        // against the per-species records above.
        val seenIds = allObservations.map { it.id }.toHashSet()
        try {
            getAllFungiObservations(centerLat, centerLng, radiusKm, forceRefresh).forEach { m ->
                if (seenIds.add(m.id)) {
                    allObservations.add(
                        Observation(
                            id = m.id,
                            speciesId = m.taxonName,
                            lat = m.lat,
                            lng = m.lng,
                            observedAt = m.observedAt,
                            source = m.source,
                            photoUrl = m.photoUrl,
                            qualityGrade = m.qualityGrade,
                            cachedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "aggregate all-fungi enrichment failed: ${e.message}")
        }
        val userSightings = dao.getAllUserSightings().filter {
            calculateDistanceMeters(centerLat, centerLng, it.lat, it.lng) <= radiusKm * 1000.0
        }
        // Detailed weather for lag analysis
        val weather = getDetailedWeather(centerLat, centerLng)

        val nowMs = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        val currentMonth = calendar.get(Calendar.MONTH) + 1
        val dayOfYear = calendar.get(Calendar.DAY_OF_YEAR)

        // Aggregate seasonal fitness: fraction of catalogue currently in season
        val inSeasonCount = allSpecies.count { isMonthInSeason(currentMonth, it.seasonStart, it.seasonEnd) }
        val inSeasonFraction = inSeasonCount.toDouble() / allSpecies.size
        val seasonScore = (0.3 + 0.7 * inSeasonFraction).coerceIn(0.0, 1.0)

        // Weather factors (shared across grid)
        val rainTriggerScore = MycoMath.rainfallTriggerScore(weather.dailyRainfallMm)
        val tempScore = MycoMath.temperatureFitness(weather.avgTemp, "aggregate_default")
        val recentRain30d = weather.dailyRainfallMm.takeLast(minOf(30, weather.dailyRainfallMm.size)).sum()
        val rainMoistureScore = when {
            recentRain30d in 40.0..180.0 -> 1.0
            recentRain30d < 40.0 -> recentRain30d / 40.0
            else -> maxOf(0.3, 1.0 - (recentRain30d - 180.0) / 200.0)
        }
        val soilMoistureScore = weather.avgSoilMoisture?.let { MycoMath.soilMoistureFitness(it) }
        val moistureScore = if (soilMoistureScore != null)
            (0.4 * rainMoistureScore + 0.6 * soilMoistureScore) else rainMoistureScore
        val moonScore = MycoMath.moonFruitingScore(nowMs)

        val latStep = 0.0045
        val lngStep = 0.0057
        val latRangeSteps = ceil((radiusKm * 1000.0) / 500.0).toInt()
        val cells = mutableListOf<HotspotCell>()
        val kernelRadiusMeters = 2500.0
        val maxDaysBack = 5.0 * 365.0

        // Enumerate in-radius cells, then batch-fetch real terrain elevation.
        val cellIJ = mutableListOf<Pair<Int, Int>>()
        val cellLL = mutableListOf<Pair<Double, Double>>()
        for (i in -latRangeSteps..latRangeSteps) {
            for (j in -latRangeSteps..latRangeSteps) {
                val cLat = centerLat + i * latStep
                val cLng = centerLng + j * lngStep
                if (calculateDistanceMeters(centerLat, centerLng, cLat, cLng) > radiusKm * 1000.0) continue
                cellIJ.add(i to j)
                cellLL.add(cLat to cLng)
            }
        }
        val elevations = fetchElevations(cellLL)
        val elevByIJ = HashMap<Pair<Int, Int>, Double>()
        cellIJ.forEachIndexed { idx, ij -> elevations[idx]?.let { elevByIJ[ij] = it } }

        val env = fetchEnvLayers(cellLL)
        val canopy = if (env == null && cellLL.isNotEmpty()) fetchCanopyFeatures(
            cellLL.minOf { it.first }, cellLL.minOf { it.second },
            cellLL.maxOf { it.first }, cellLL.maxOf { it.second }
        ) else emptyList()

        for (idx in cellIJ.indices) {
            coroutineContext.ensureActive()
            val (i, j) = cellIJ[idx]
            val (cellLat, cellLng) = cellLL[idx]

                var weightedEvidence = 0.0
                var nearbyRecords = 0
                val nearbySpecies = mutableSetOf<String>()

                for (obs in allObservations) {
                    if (abs(obs.lat - cellLat) > 0.025 || abs(obs.lng - cellLng) > 0.035) continue
                    val d = calculateDistanceMeters(cellLat, cellLng, obs.lat, obs.lng)
                    if (d > kernelRadiusMeters) continue
                    val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60 * 60 * 24)
                    if (diffDays !in 0.0..maxDaysBack) continue

                    val quality = MycoMath.qualityWeight(obs.qualityGrade)
                    val sourceW = MycoMath.sourceWeight(obs.source)
                    val recency = MycoMath.recencyWeight(diffDays)
                    val spatial = MycoMath.spatialKernel(d)
                    weightedEvidence += quality * sourceW * recency * spatial
                    nearbyRecords++
                    nearbySpecies.add(obs.speciesId)
                }

                for (sig in userSightings) {
                    if (abs(sig.lat - cellLat) > 0.025 || abs(sig.lng - cellLng) > 0.035) continue
                    val d = calculateDistanceMeters(cellLat, cellLng, sig.lat, sig.lng)
                    if (d > kernelRadiusMeters) continue
                    val diffDays = (nowMs - sig.timestamp).toDouble() / (1000.0 * 60 * 60 * 24)
                    if (diffDays !in 0.0..maxDaysBack) continue
                    weightedEvidence += 1.5 * MycoMath.recencyWeight(diffDays) * MycoMath.spatialKernel(d)
                    nearbyRecords++
                    nearbySpecies.add(sig.speciesId)
                }

                // Diversity bonus: more species nearby → richer site
                val diversityBonus = minOf(0.3, nearbySpecies.size * 0.06)
                val observationScore = minOf(1.0, weightedEvidence / 6.0 + diversityBonus)

                // Per-cell terrain (real elevation) — same landform logic as
                // the single-species engine, using a broad aggregate band.
                val cellElev = elevations[idx]
                val neighbourElevs = listOf(
                    (i - 1) to j, (i + 1) to j, i to (j - 1), i to (j + 1),
                    (i - 1) to (j - 1), (i + 1) to (j + 1), (i - 1) to (j + 1), (i + 1) to (j - 1)
                ).mapNotNull { elevByIJ[it] }
                val elevationScore = if (cellElev != null)
                    MycoMath.elevationFitness(cellElev, "aggregate_default") else 0.6
                val terrainScore = if (cellElev != null && neighbourElevs.isNotEmpty())
                    MycoMath.terrainMoistureScore(cellElev, neighbourElevs) else 0.5
                val aspectScore = if (cellElev != null) MycoMath.slopeAspectMoistureScore(
                    cellElev,
                    elevByIJ[(i + 1) to j], elevByIJ[(i - 1) to j],
                    elevByIJ[i to (j + 1)], elevByIJ[i to (j - 1)]
                ) else 0.7
                val canopyDist = if (canopy.isEmpty()) null else nearestFeatureMeters(cellLat, cellLng, canopy)
                val canopyScore = if (env != null) MycoMath.richCanopyScore(
                    env.canopyPct.getOrNull(idx), env.ndvi.getOrNull(idx), env.landcover.getOrNull(idx), "aggregate_default"
                ) else MycoMath.canopyProximityScore(canopyDist)
                val riparianScore = if (env != null) MycoMath.riparianScore(env.waterDistM.getOrNull(idx)) else 0.45

                // Weighted combination (weights sum to 1.0)
                val weightedSum = 0.22 * observationScore +
                        0.16 * seasonScore +
                        0.11 * rainTriggerScore +
                        0.11 * canopyScore +
                        0.08 * terrainScore +
                        0.08 * 0.7 + // Aggregate habitat baseline (diverse catalogue)
                        0.06 * elevationScore +
                        0.06 * tempScore +
                        0.04 * riparianScore +
                        0.04 * aspectScore +
                        0.03 * moistureScore +
                        0.01 * moonScore

                val seasonRainFloor = minOf(seasonScore, rainTriggerScore + 0.2)
                val penaltyMultiplier = (0.3 + 0.7 * seasonRainFloor).coerceIn(0.0, 1.0)
                // Habitat gate — suppress built-up/water/bare cells (see single-species note).
                val habitatGate = if (env != null)
                    MycoMath.habitatGate(env.landcover.getOrNull(idx), env.ndvi.getOrNull(idx), "aggregate_default")
                else
                    (0.35 + 0.65 * canopyScore)
                val finalScore = (weightedSum * penaltyMultiplier * habitatGate).coerceIn(0.0, 1.0)

                val tier = MycoMath.classifyTier(finalScore)

                val factors = mutableListOf<String>()
                factors.add("📍 Cell (${String.format(Locale.US, "%.4f", cellLat)}, ${String.format(Locale.US, "%.4f", cellLng)})")
                factors.add("🔬 Evidence: $nearbyRecords record(s) across ${nearbySpecies.size} species within 2.5 km → ${String.format(Locale.US, "%.0f", observationScore * 100)}%")
                factors.add("📅 $inSeasonCount of ${allSpecies.size} species fruiting in ${monthName(currentMonth)} → ${String.format(Locale.US, "%.0f", seasonScore * 100)}%")
                factors.add("🌧️ Rain trigger (10-21d lag): ${String.format(Locale.US, "%.0f", rainTriggerScore * 100)}% | Background moisture: ${String.format(Locale.US, "%.0f", recentRain30d)}mm/30d")
                factors.add("🌡️ Avg temp ${String.format(Locale.US, "%.1f", weather.avgTemp)}°C → ${String.format(Locale.US, "%.0f", tempScore * 100)}%")
                factors.add("⛰️ Elevation: ${if (cellElev != null) String.format(Locale.US, "%.0f m", cellElev) else "n/a"} → ${String.format(Locale.US, "%.0f", elevationScore * 100)}% | 🏞️ Terrain: ${String.format(Locale.US, "%.0f", terrainScore * 100)}% | Aspect: ${String.format(Locale.US, "%.0f", aspectScore * 100)}%")
                if (env != null) {
                    val cv = env.canopyPct.getOrNull(idx)
                    val nv = env.ndvi.getOrNull(idx)
                    factors.add("🛰️ Earth Engine: canopy ${if (cv != null) String.format(Locale.US, "%.0f%%", cv) else "n/a"}, NDVI ${if (nv != null) String.format(Locale.US, "%.2f", nv) else "n/a"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%")
                } else {
                    factors.add("🌳 Canopy: ${if (canopyDist != null) "~${String.format(Locale.US, "%.0f m", canopyDist)} to woodland" else "no map data"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%")
                }
                if (habitatGate < 0.95) factors.add("⛔ Habitat gate ×${String.format(Locale.US, "%.2f", habitatGate)} — built-up/water/bare ground suppresses this cell")
                if (nearbySpecies.size >= 3) factors.add("🌿 Diversity bonus: ${nearbySpecies.size} distinct species recorded nearby")
                factors.add("Multi-factor aggregate estimate — not species-specific.")

                cells.add(HotspotCell(cellLat, cellLng, finalScore, tier, factors))
        }
        return@withContext cells
    }

    // ─── Darwin Core Export ─────────────────────────────────────────

    /**
     * Exports user sightings in Darwin Core standard format (CSV).
     * This format is accepted by Atlas of Living Australia, GBIF, and
     * Fungimap for contribution to biodiversity databases.
     *
     * Returns the CSV content as a String.
     */
    suspend fun exportDarwinCore(): String = withContext(Dispatchers.IO) {
        val sightings = dao.getAllUserSightings().filter { !it.isPrivate }
        val allSpecies = dao.getAllSpecies()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

        val header = "occurrenceID,basisOfRecord,scientificName,kingdom,phylum,class,order,family,genus," +
                "decimalLatitude,decimalLongitude,coordinateUncertaintyInMeters," +
                "eventDate,recordedBy,occurrenceRemarks,identificationVerificationStatus"

        val rows = sightings.map { sighting ->
            val species = allSpecies.find { it.id == sighting.speciesId }
            val sciName = species?.scientificName ?: sighting.speciesId
            val family = species?.family ?: ""
            val genus = species?.genus ?: ""
            val dateStr = sdf.format(Date(sighting.timestamp))
            val notes = sighting.notes.replace("\"", "'").replace(",", ";")

            "\"MM-${sighting.id}\"," +
                    "\"HUMAN_OBSERVATION\"," +
                    "\"$sciName\"," +
                    "\"Fungi\"," +
                    "\"Basidiomycota\"," +
                    "\"Agaricomycetes\"," +
                    "\"\"," + // order not in model
                    "\"$family\"," +
                    "\"$genus\"," +
                    "${sighting.lat}," +
                    "${sighting.lng}," +
                    "10," + // GPS uncertainty ~10m
                    "\"$dateStr\"," +
                    "\"Mycilliyums User\"," +
                    "\"$notes\"," +
                    "\"unverified\""
        }

        return@withContext "$header\n${rows.joinToString("\n")}"
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

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        MycoMath.haversineMeters(lat1, lon1, lat2, lon2)

    private fun isMonthInSeason(month: Int, start: Int, end: Int): Boolean =
        MycoMath.isMonthInSeason(month, start, end)

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

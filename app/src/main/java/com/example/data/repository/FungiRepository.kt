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
import com.example.model.ObservationCacheArea
import com.example.model.Species
import com.example.model.SpeciesDiagnostics
import com.example.model.UserSighting
import com.example.util.MycoMath
import com.example.util.SpeciesSearch
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
import kotlinx.coroutines.withTimeoutOrNull
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

    // Field-ID diagnostics parsed ONCE from species.json (extra keys Moshi
    // ignores during the Species seed parse). Not persisted in Room — purely an
    // in-memory cache, keyed by species id. @Volatile + double-checked locking.
    @Volatile private var diagnosticsCache: Map<String, SpeciesDiagnostics>? = null

    // Shorter than the previous 24h TTL so map panning refreshes stale areas
    // within the same day while still avoiding excessive API churn.
    private val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private val GEOCODER_TIMEOUT_MS = 5_000L
    private val METERS_PER_KM = 1000.0

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

    /**
     * Field-ID diagnostics (checklist markers + enriched look-alikes) for a
     * species, parsed lazily from the bundled species.json. Moshi ignores the
     * many unrelated keys in each object and falls back to the data-class
     * defaults for the diagnostic keys, so objects without diagnostic content
     * still parse (just empty). Cached for the process lifetime; fails soft to
     * an empty cache so the detail screen simply shows nothing extra.
     */
    suspend fun fetchSpeciesDiagnostics(speciesId: String): SpeciesDiagnostics? = withContext(Dispatchers.IO) {
        val cache = diagnosticsCache ?: synchronized(this@FungiRepository) {
            diagnosticsCache ?: run {
                val built = try {
                    context.assets.open("species.json").use { inputStream ->
                        val jsonString = InputStreamReader(inputStream).readText()
                        val listType = Types.newParameterizedType(List::class.java, SpeciesDiagnostics::class.java)
                        val adapter = moshi.adapter<List<SpeciesDiagnostics>>(listType)
                        adapter.fromJson(jsonString)?.associateBy { it.id } ?: emptyMap()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load species diagnostics from assets", e)
                    emptyMap()
                }
                diagnosticsCache = built
                built
            }
        }
        cache[speciesId]
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
    private val areaObsCache = android.util.LruCache<String, Pair<Long, List<MapObservation>>>(32)
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
            areaObsCache.get(key)?.let { (ts, value) ->
                if (now - ts < AREA_OBS_TTL_MS) return@withContext value
            }
        }
        val cal = Calendar.getInstance().apply { add(Calendar.YEAR, -5) }
        val since = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        // Bounding box for GBIF (it ranges by min,max lat/lng, not radius).
        val latDelta = radiusKm / 111.0
        val lngDelta = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        val latRange = "${"%.4f".format(lat - latDelta)},${"%.4f".format(lat + latDelta)}"
        val lngRange = "${"%.4f".format(lng - lngDelta)},${"%.4f".format(lng + lngDelta)}"

        val result = coroutineScope {
            // iNaturalist citizen-science sightings (one kingdom-wide call)
            val iNatDeferred = async {
                try {
                    val resp = retryIO {
                        iNatApi.getAreaObservations(lat = lat, lng = lng, radiusKm = radiusKm, sinceDate = since)
                    }
                    resp.results.mapNotNull { o ->
                        val geoLng = o.geojson?.coordinates?.getOrNull(0)
                        val geoLat = o.geojson?.coordinates?.getOrNull(1)
                        val pLat = o.latitude ?: o.location?.split(",")?.getOrNull(0)?.trim()?.toDoubleOrNull() ?: geoLat
                        val pLng = o.longitude ?: o.location?.split(",")?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: geoLng
                        if (pLat == null || pLng == null) return@mapNotNull null
                        MapObservation(
                            id = o.id, lat = pLat, lng = pLng,
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
                    Log.w(TAG, "all-fungi iNat fetch failed: ${e.message}"); emptyList()
                }
            }
            // GBIF museum/herbarium + research occurrences in the same box
            val gbifDeferred = async {
                try {
                    val resp = retryIO { gbifApi.searchAreaOccurrences(lat = latRange, lon = lngRange) }
                    (resp.results ?: emptyList()).mapNotNull { o ->
                        val pLat = o.decimalLatitude ?: return@mapNotNull null
                        val pLng = o.decimalLongitude ?: return@mapNotNull null
                        val whenMs = parseObsDate(o.eventDate) // null-safe; falls back to ~30d ago
                        MapObservation(
                            id = (o.key ?: (pLat * 1e6 + pLng).toLong()),
                            lat = pLat, lng = pLng,
                            taxonName = o.scientificName ?: "Fungus (GBIF record)",
                            commonName = null,
                            source = "GBIF",
                            observedAt = whenMs,
                            qualityGrade = o.basisOfRecord ?: "GBIF record",
                            photoUrl = null,
                            placeGuess = o.institutionCode ?: o.datasetName
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "all-fungi GBIF fetch failed: ${e.message}"); emptyList()
                }
            }
            (iNatDeferred.await() + gbifDeferred.await())
        }
        areaObsCache.put(key, now to result)
        result
    }

    // Per-species global GBIF record count, cached.
    private val recordCountCache = android.util.LruCache<String, Int>(256)

    /**
     * Total worldwide GBIF occurrence records for a species (museum, herbarium
     * and observation records). Surfaced on the detail screen as a measure of
     * how widely the species has been recorded. Fails soft to null.
     */
    suspend fun getGlobalRecordCount(scientificName: String): Int? = withContext(Dispatchers.IO) {
        recordCountCache.get(scientificName)?.let { return@withContext it }
        val count = try {
            retryIO { gbifApi.countOccurrences(scientificName) }.count
        } catch (e: Exception) {
            Log.w(TAG, "GBIF record count failed for $scientificName: ${e.message}"); null
        }
        if (count != null) recordCountCache.put(scientificName, count)
        count
    }

    // Global fungal taxonomy search results, cached per query.
    private val globalSearchCache = android.util.LruCache<String, List<Species>>(64)

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
        globalSearchCache.get(q.lowercase())?.let { return@withContext it }
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
                // GBIF matched these server-side (now including synonyms/genera);
                // re-rank by our own relevance so the closest names lead.
                .let { SpeciesSearch.sortByRelevance(it, q) }
        } catch (e: Exception) {
            Log.w(TAG, "global fungi search failed for '$q': ${e.message}")
            emptyList()
        }
        globalSearchCache.put(q.lowercase(), result)
        result
    }

    // Reference-photo gallery per species (scientific name → photos with
    // attribution), pulled from iNaturalist taxon photos. Cached for the
    // process lifetime so revisiting a species detail is instant and free.
    private val speciesPhotoCache = android.util.LruCache<String, List<com.example.model.SpeciesPhoto>>(128)

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
            speciesPhotoCache.get(scientificName)?.let { return@withContext it }
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
            speciesPhotoCache.put(scientificName, photos)
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
        val cachedArea = dao.getObservationCacheArea(species.id)
        val now = System.currentTimeMillis()

        // Filter those within radius distance from the center target (cached observations might be broader)
        val inRadiusCached = cached.filter {
            calculateDistanceMeters(lat, lng, it.lat, it.lng) <= radiusKm * 1000.0
        }

        val isCacheFresh = cachedArea != null && (now - cachedArea.cachedAt) < CACHE_TTL_MS
        val isCacheCoverageValid = cachedArea?.covers(lat, lng, radiusKm) == true

        if (isCacheFresh && isCacheCoverageValid && !forceRefresh) {
            Log.d(TAG, "Returning ${inRadiusCached.size} cached observations from Room (cache fresh + covered)")
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

            if (allFresh.isNotEmpty()) {
                dao.clearObservationsForSpecies(species.id)
                dao.insertObservations(allFresh)
            } else {
                // Intentionally clear here too: an empty fetch for this area must
                // replace any previous area's observations so panning can't surface
                // stale records as if they belonged to the new search.
                dao.clearObservationsForSpecies(species.id)
            }
            dao.upsertObservationCacheArea(
                ObservationCacheArea(
                    speciesId = species.id,
                    centerLat = lat,
                    centerLng = lng,
                    radiusKm = radiusKm,
                    cachedAt = now
                )
            )
            Log.d(TAG, "Fetched and cached ${allFresh.size} observations (iNat: ${freshObservations.size}, ALA: ${alaObs.size}, GBIF: ${gbifObs.size}) in Room.")

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
    private val elevCache = android.util.LruCache<Long, Double>(8000) // bounded == CACHE_MAX (disk cap)
    private data class EnvCell(
        val landcover: Int?, val canopyPct: Double?, val ndvi: Double?, val waterDistM: Double?,
        val soilPh: Double? = null, val soilSand: Double? = null,
        val soilMoisture: Double? = null, val twi: Double? = null, val forestType: Int? = null
    )
    private val envCache = android.util.LruCache<Long, EnvCell>(8000) // bounded == CACHE_MAX
    // Deep Search uses its OWN session caches at a ~12 m snap. The overview caches
    // snap at ~250 m, which would collapse every fine sub-cell onto one value, so
    // a finer key is essential for the drill-down to actually resolve detail.
    private val deepElevCache = android.util.LruCache<Long, Double>(20_000)
    private val deepEnvCache = android.util.LruCache<Long, EnvCell>(20_000)

    /** Snap a coordinate to a global grid index so the same place always keys the
     *  same. [fine] uses a ~12 m snap (Deep Search, in separate caches); the
     *  default ~250 m snap backs the broad overview grid. The coarse formula is
     *  unchanged so existing on-disk caches stay valid. */
    private fun gridKey(lat: Double, lng: Double, fine: Boolean = false): Long {
        if (fine) {
            val la = Math.round(lat / 0.000108) + 40_000_000L
            val ln = Math.round(lng / 0.000137) + 40_000_000L
            return la * 100_000_000L + ln
        }
        val la = Math.round(lat / 0.00225) + 4_000_000L
        val ln = Math.round(lng / 0.00285) + 4_000_000L
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
                    if (p.size == 2) p[0].toLongOrNull()?.let { k -> p[1].toDoubleOrNull()?.let { v -> elevCache.put(k, v) } }
                }
            } catch (e: Exception) { Log.w(TAG, "elev cache load failed: ${e.message}") }
            try {
                if (envCacheFile.exists()) envCacheFile.forEachLine { line ->
                    val p = line.split('\t')
                    // ≥5 fields: core layers; ≥9 adds soil/moisture/twi; ≥10 adds
                    // forest_type. Older shorter rows still load (extras stay null).
                    if (p.size >= 5) p[0].toLongOrNull()?.let { k ->
                        envCache.put(k, EnvCell(
                            p[1].toIntOrNull(), p[2].toDoubleOrNull(), p[3].toDoubleOrNull(), p[4].toDoubleOrNull(),
                            p.getOrNull(5)?.toDoubleOrNull(), p.getOrNull(6)?.toDoubleOrNull(),
                            p.getOrNull(7)?.toDoubleOrNull(), p.getOrNull(8)?.toDoubleOrNull(),
                            p.getOrNull(9)?.toIntOrNull()
                        ))
                    }
                }
            } catch (e: Exception) { Log.w(TAG, "env cache load failed: ${e.message}") }
            cachesLoaded = true
        }
    }

    private fun persistElevCache() {
        try {
            elevCacheFile.bufferedWriter().use { w ->
                elevCache.snapshot().entries.asSequence().take(CACHE_MAX).forEach { w.write("${it.key}\t${it.value}\n") }
            }
        } catch (e: Exception) { Log.w(TAG, "elev cache save failed: ${e.message}") }
    }

    private fun persistEnvCache() {
        try {
            envCacheFile.bufferedWriter().use { w ->
                envCache.snapshot().entries.asSequence().take(CACHE_MAX).forEach { (k, v) ->
                    w.write(
                        "$k\t${v.landcover ?: ""}\t${v.canopyPct ?: ""}\t${v.ndvi ?: ""}\t${v.waterDistM ?: ""}" +
                            "\t${v.soilPh ?: ""}\t${v.soilSand ?: ""}\t${v.soilMoisture ?: ""}\t${v.twi ?: ""}" +
                            "\t${v.forestType ?: ""}\n"
                    )
                }
            }
        } catch (e: Exception) { Log.w(TAG, "env cache save failed: ${e.message}") }
    }

    // Hard cap on the number of grid cells per overview/aggregate build, so a
    // very large radius can't enumerate an unbounded grid (memory + request fan-out).
    private val MAX_GRID_CELLS = 5000

    // Bounded concurrency for the chunked network fetches. Open-Meteo elevation is
    // free/lenient (6 in flight); the Earth Engine backend is metered and 600-pt
    // capped per call, so its fan-out is kept lower.
    private val ELEV_INFLIGHT = 6
    private val EE_INFLIGHT = 4

    /**
     * Adaptive overview/aggregate cell size in metres. Keeps the existing
     * ~60-steps-per-axis floor, the 250 m minimum, and additionally coarsens
     * enough that the total in-radius cell count stays under [MAX_GRID_CELLS]
     * (πr² / cell² ≤ MAX_GRID_CELLS ⇒ cell ≥ r·√(π/MAX)).
     */
    private fun adaptiveCellMeters(radiusKm: Double): Double {
        val radiusM = radiusKm * 1000.0
        val byStepCap = radiusM / 60.0
        val byCellCap = radiusM * Math.sqrt(Math.PI / MAX_GRID_CELLS)
        return maxOf(250.0, byStepCap, byCellCap)
    }

    /**
     * Fetches ground elevation for a list of coordinates, batched into
     * requests of ≤100 points (Open-Meteo's per-call limit) and served from a
     * session cache where possible. Returns a list aligned 1:1 with [coords];
     * entries are null where elevation could not be resolved, so callers can
     * fall back to neutral terrain scoring.
     */
    suspend fun fetchElevations(
        coords: List<Pair<Double, Double>>,
        fine: Boolean = false
    ): List<Double?> = withContext(Dispatchers.IO) {
        if (coords.isEmpty()) return@withContext emptyList()
        ensureCachesLoaded()
        val cache = if (fine) deepElevCache else elevCache
        val out = MutableList<Double?>(coords.size) { null }
        val missIdx = ArrayList<Int>()
        val missCoords = ArrayList<Pair<Double, Double>>()
        for (i in coords.indices) {
            val cached = cache.get(gridKey(coords[i].first, coords[i].second, fine))
            if (cached != null) out[i] = cached else { missIdx.add(i); missCoords.add(coords[i]) }
        }
        if (missCoords.isNotEmpty()) {
            // Each chunk owns a disjoint [start, end) window of the miss list, so its
            // parallel writes to out[missIdx[…]] and the (synchronized) cache never
            // collide and the output stays 1:1 aligned with [coords] — identical to
            // the old sequential build, just faster. Open-Meteo caps a call at 100
            // points; ELEV_INFLIGHT bounds concurrent requests so we stay polite.
            val chunkBounds = ArrayList<Pair<Int, Int>>()
            run {
                var start = 0
                while (start < missCoords.size) {
                    val end = minOf(start + 100, missCoords.size)
                    chunkBounds.add(start to end)
                    start = end
                }
            }
            for (wave in chunkBounds.chunked(ELEV_INFLIGHT)) {
                coroutineContext.ensureActive()
                coroutineScope {
                    wave.map { (start, end) ->
                        async {
                            // retry inside (transient flakiness), soft-fail outside
                            // (one exhausted chunk degrades to null-fill, never aborts).
                            try {
                                val chunk = missCoords.subList(start, end)
                                val latCsv = chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.first) }
                                val lngCsv = chunk.joinToString(",") { String.format(Locale.US, "%.5f", it.second) }
                                val resp = retryIO { openMeteoApi.getElevation(latCsv, lngCsv) }
                                val elevs = resp.elevation ?: emptyList()
                                for (k in start until end) {
                                    val v = elevs.getOrNull(k - start)
                                    out[missIdx[k]] = v
                                    if (v != null) cache.put(gridKey(missCoords[k].first, missCoords[k].second, fine), v)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Elevation chunk fetch failed, using neutral terrain: ${e.message}")
                            }
                        }
                    }.awaitAll()
                }
            }
            if (!fine) persistElevCache()
        }
        out
    }

    // ── Overpass caching (be polite to overpass-api.de) ─────────────────
    // Overpass features (woods, mulch beds, land-use polygons) drift slowly, so we
    // cache them per query kind keyed on an outward-quantized bbox. Panning within
    // the same ~2 km tile reuses the cached result instead of re-hitting the API.
    private val OVERPASS_TTL_MS = 6 * 60 * 60 * 1000L
    private val OVERPASS_BBOX_QUANT = 0.02
    // Hard per-call time box for the (best-effort) Overpass habitat fetches. OSM
    // is only a fallback signal, so it must never block the grid: if a call
    // doesn't return inside this window the cell falls back to neutral habitat.
    private val OVERPASS_CALL_TIMEOUT_MS = 10_000L
    private val overpassCanopyCache = android.util.LruCache<String, Pair<Long, List<Pair<Double, Double>>>>(16)
    private val overpassMulchCache = android.util.LruCache<String, Pair<Long, List<Pair<Double, Double>>>>(16)
    private val overpassLandUseCache = android.util.LruCache<String, Pair<Long, List<LandPolygon>>>(16)

    /** A bbox snapped OUTWARD to the Overpass quant grid, with a stable cache key. */
    private class SnappedBbox(
        val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double,
        val key: String
    )

    /**
     * Snap [minLat,minLng,maxLat,maxLng] OUTWARD (floor mins, ceil maxs) to the
     * [OVERPASS_BBOX_QUANT] grid so nearby pans collapse onto the same tile, and
     * build a stable key prefixed by query kind. The snapped (slightly larger)
     * bbox is used in the query itself so the cached features cover the snapped
     * extent — no edge gaps on small pans.
     */
    private fun snapOverpassBbox(
        prefix: String, minLat: Double, minLng: Double, maxLat: Double, maxLng: Double
    ): SnappedBbox {
        val q = OVERPASS_BBOX_QUANT
        val sMinLat = Math.floor(minLat / q) * q
        val sMinLng = Math.floor(minLng / q) * q
        val sMaxLat = Math.ceil(maxLat / q) * q
        val sMaxLng = Math.ceil(maxLng / q) * q
        val key = "$prefix@" + String.format(
            Locale.US, "%.2f,%.2f,%.2f,%.2f", sMinLat, sMinLng, sMaxLat, sMaxLng
        )
        return SnappedBbox(sMinLat, sMinLng, sMaxLat, sMaxLng, key)
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
        val snapped = snapOverpassBbox("canopy", minLat, minLng, maxLat, maxLng)
        val now = System.currentTimeMillis()
        overpassCanopyCache.get(snapped.key)?.let { (ts, value) ->
            if (now - ts < OVERPASS_TTL_MS) return@withContext value
        }
        try {
            val bbox = String.format(
                Locale.US, "%.5f,%.5f,%.5f,%.5f",
                snapped.minLat, snapped.minLng, snapped.maxLat, snapped.maxLng
            )
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
            val result = resp.elements.orEmpty().mapNotNull { el ->
                val la = el.resolvedLat()
                val lo = el.resolvedLon()
                if (la != null && lo != null) la to lo else null
            }
            overpassCanopyCache.put(snapped.key, now to result)
            result
        } catch (e: Exception) {
            Log.w(TAG, "Canopy (Overpass) fetch failed, neutral canopy: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetches tanbark / woodchip / mulch-bed features (centre points) in the
     * bbox from OpenStreetMap via Overpass. These are prime substrate for
     * wood-chip-loving fungi (gold tops etc.) and aren't resolvable from Earth
     * Engine land cover, so they're fetched separately — but only for
     * mulch-associated species (see [MycoMath.mulchAffinity]). Tags: explicit
     * `surface=woodchips|bark_mulch|tan`, `landuse=flowerbed|plant_nursery`,
     * `leisure=garden|playground` (play areas are commonly bark-mulched), and
     * garden centres. Returns empty on failure (graceful — just no bonus).
     */
    suspend fun fetchMulchFeatures(
        minLat: Double, minLng: Double, maxLat: Double, maxLng: Double
    ): List<Pair<Double, Double>> = withContext(Dispatchers.IO) {
        val snapped = snapOverpassBbox("mulch", minLat, minLng, maxLat, maxLng)
        val now = System.currentTimeMillis()
        overpassMulchCache.get(snapped.key)?.let { (ts, value) ->
            if (now - ts < OVERPASS_TTL_MS) return@withContext value
        }
        try {
            val bbox = String.format(
                Locale.US, "%.5f,%.5f,%.5f,%.5f",
                snapped.minLat, snapped.minLng, snapped.maxLat, snapped.maxLng
            )
            val ql = """
                [out:json][timeout:25];
                (
                  way["surface"~"woodchips|bark_mulch|tan"]($bbox);
                  way["landuse"="flowerbed"]($bbox);
                  way["landuse"="plant_nursery"]($bbox);
                  way["leisure"="garden"]($bbox);
                  way["leisure"="playground"]($bbox);
                  node["leisure"="playground"]($bbox);
                  way["shop"="garden_centre"]($bbox);
                );
                out center;
            """.trimIndent()
            val resp = overpassApi.query(ql)
            val result = resp.elements.orEmpty().mapNotNull { el ->
                val la = el.resolvedLat()
                val lo = el.resolvedLon()
                if (la != null && lo != null) la to lo else null
            }
            overpassMulchCache.put(snapped.key, now to result)
            result
        } catch (e: Exception) {
            Log.w(TAG, "Mulch (Overpass) fetch failed, no tanbark bonus: ${e.message}")
            emptyList()
        }
    }

    // ─── OSM land-use classification (offline habitat discrimination) ───
    //
    // When the Earth Engine backend isn't configured, this is what lets the map
    // actually tell good ground from bad. We pull real land-use *polygons* —
    // green space (forest/park/reserve/scrub/wetland…) and built-up
    // (residential/industrial/retail/parking/aerodrome…) — and classify each
    // grid cell by point-in-polygon. Green cells get a high habitat gate;
    // built-up cells are suppressed toward zero so suburbs go dark instead of
    // every cell scoring the same.

    /** A classified land-use ring. [ring] is a flat [lat0,lng0,lat1,lng1,…] array. */
    private class LandPolygon(
        val green: Boolean,
        val ring: DoubleArray,
        val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double
    )

    private enum class LandClass { GREEN, BUILT, NEUTRAL }

    private fun isGreenTags(tags: Map<String, String>?): Boolean {
        if (tags == null) return false
        if (tags["boundary"] == "protected_area") return true
        when (tags["leisure"]) { "park", "nature_reserve", "garden", "golf_course" -> return true }
        when (tags["natural"]) { "wood", "scrub", "heath", "grassland", "wetland", "scree" -> return true }
        when (tags["landuse"]) {
            "forest", "meadow", "recreation_ground", "village_green", "allotments", "orchard" -> return true
        }
        return false
    }

    private fun isBuiltTags(tags: Map<String, String>?): Boolean {
        if (tags == null) return false
        if (tags["amenity"] == "parking") return true
        if (tags["aeroway"] == "aerodrome") return true
        when (tags["landuse"]) {
            "residential", "industrial", "commercial", "retail",
            "construction", "garages", "railway", "quarry" -> return true
        }
        return false
    }

    /**
     * Fetches classified green / built-up land-use polygons in the bbox via
     * Overpass `out geom`. Returns empty on failure (callers fall back to the
     * canopy-proximity heuristic), so the map degrades gracefully.
     */
    private suspend fun fetchLandUsePolygons(
        minLat: Double, minLng: Double, maxLat: Double, maxLng: Double
    ): List<LandPolygon> = withContext(Dispatchers.IO) {
        val snapped = snapOverpassBbox("landuse", minLat, minLng, maxLat, maxLng)
        val now = System.currentTimeMillis()
        overpassLandUseCache.get(snapped.key)?.let { (ts, value) ->
            if (now - ts < OVERPASS_TTL_MS) return@withContext value
        }
        try {
            val bbox = String.format(
                Locale.US, "%.5f,%.5f,%.5f,%.5f",
                snapped.minLat, snapped.minLng, snapped.maxLat, snapped.maxLng
            )
            val ql = """
                [out:json][timeout:40];
                (
                  way["leisure"~"park|nature_reserve|garden|golf_course"]($bbox);
                  way["natural"~"wood|scrub|heath|grassland|wetland|scree"]($bbox);
                  way["landuse"~"forest|meadow|recreation_ground|village_green|allotments|orchard"]($bbox);
                  way["boundary"="protected_area"]($bbox);
                  way["landuse"~"residential|industrial|commercial|retail|construction|garages|railway|quarry"]($bbox);
                  way["amenity"="parking"]($bbox);
                  way["aeroway"="aerodrome"]($bbox);
                );
                out geom;
            """.trimIndent()
            val resp = overpassApi.query(ql)
            val result = resp.elements.orEmpty().mapNotNull { el ->
                val geom = el.geometry ?: return@mapNotNull null
                val green = isGreenTags(el.tags)
                val built = isBuiltTags(el.tags)
                // Green wins ties; ignore anything we can't classify.
                if (!green && !built) return@mapNotNull null
                val pts = geom.mapNotNull { p ->
                    val la = p.lat; val lo = p.lon
                    if (la != null && lo != null) la to lo else null
                }
                if (pts.size < 3) return@mapNotNull null
                val ring = DoubleArray(pts.size * 2)
                pts.forEachIndexed { k, (la, lo) -> ring[k * 2] = la; ring[k * 2 + 1] = lo }
                LandPolygon(
                    green = green,
                    ring = ring,
                    minLat = pts.minOf { it.first }, minLng = pts.minOf { it.second },
                    maxLat = pts.maxOf { it.first }, maxLng = pts.maxOf { it.second }
                )
            }
            overpassLandUseCache.put(snapped.key, now to result)
            result
        } catch (e: Exception) {
            Log.w(TAG, "Land-use (Overpass) fetch failed, neutral habitat: ${e.message}")
            emptyList()
        }
    }

    /** Ray-casting point-in-polygon test. [ring] is flat [lat,lng,lat,lng,…]. */
    private fun pointInRing(lat: Double, lng: Double, ring: DoubleArray): Boolean {
        var inside = false
        val n = ring.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val yi = ring[i * 2]; val xi = ring[i * 2 + 1]
            val yj = ring[j * 2]; val xj = ring[j * 2 + 1]
            if (((yi > lat) != (yj > lat)) &&
                (lng < (xj - xi) * (lat - yi) / (yj - yi) + xi)
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** Classify a cell against the land-use polygons; green takes priority. */
    private fun classifyLandCell(lat: Double, lng: Double, polys: List<LandPolygon>): LandClass {
        if (polys.isEmpty()) return LandClass.NEUTRAL
        var built = false
        for (p in polys) {
            if (lat < p.minLat || lat > p.maxLat || lng < p.minLng || lng > p.maxLng) continue
            if (pointInRing(lat, lng, p.ring)) {
                if (p.green) return LandClass.GREEN
                built = true
            }
        }
        return if (built) LandClass.BUILT else LandClass.NEUTRAL
    }

    /** Per-cell Earth Engine layers, aligned 1:1 with the grid points. */
    data class EnvLayers(
        val landcover: List<Int?>,
        val canopyPct: List<Double?>,
        val ndvi: List<Double?>,
        val waterDistM: List<Double?>,
        val soilPh: List<Double?>,
        val soilSand: List<Double?>,
        val soilMoisture: List<Double?>,
        val twi: List<Double?>,
        val forestType: List<Int?>
    )

    /**
     * Fetches Earth Engine land-cover / tree-canopy / NDVI for the grid from
     * the optional Cloud Run backend. Returns null when the backend isn't
     * configured or the call fails, so callers fall back to free OSM canopy.
     */
    suspend fun fetchEnvLayers(points: List<Pair<Double, Double>>, fine: Boolean = false): EnvLayers? {
        val api = envLayersApi ?: return null
        if (points.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            ensureCachesLoaded()
            val cache = if (fine) deepEnvCache else envCache
            val landcover = arrayOfNulls<Int>(points.size)
            val canopyPct = arrayOfNulls<Double>(points.size)
            val ndvi = arrayOfNulls<Double>(points.size)
            val waterDist = arrayOfNulls<Double>(points.size)
            val soilPh = arrayOfNulls<Double>(points.size)
            val soilSand = arrayOfNulls<Double>(points.size)
            val soilMoisture = arrayOfNulls<Double>(points.size)
            val twi = arrayOfNulls<Double>(points.size)
            val forestType = arrayOfNulls<Int>(points.size)
            var haveAny = false
            val missIdx = ArrayList<Int>()
            val missPts = ArrayList<Pair<Double, Double>>()
            for (i in points.indices) {
                val c = cache.get(gridKey(points[i].first, points[i].second, fine))
                if (c != null) {
                    landcover[i] = c.landcover; canopyPct[i] = c.canopyPct; ndvi[i] = c.ndvi; waterDist[i] = c.waterDistM
                    soilPh[i] = c.soilPh; soilSand[i] = c.soilSand; soilMoisture[i] = c.soilMoisture; twi[i] = c.twi
                    forestType[i] = c.forestType
                    haveAny = true
                } else {
                    missIdx.add(i); missPts.add(points[i])
                }
            }
            // Fetch misses in chunks — the backend caps a request at 600 points,
            // so a large search radius must be split or EE is lost for the grid.
            // Each chunk owns a disjoint [start, end) miss window and writes only its
            // own orig = missIdx[start+c] slots + cache entries, so the chunks run in
            // bounded-parallel waves without colliding; output stays index-aligned.
            val chunkBounds = ArrayList<Pair<Int, Int>>()
            run {
                var start = 0
                while (start < missPts.size) {
                    val end = minOf(start + 500, missPts.size)
                    chunkBounds.add(start to end)
                    start = end
                }
            }
            for (wave in chunkBounds.chunked(EE_INFLIGHT)) {
                coroutineContext.ensureActive()
                coroutineScope {
                    wave.map { (start, end) ->
                        async {
                            // retry inside (transient flakiness), soft-fail outside
                            // (one exhausted chunk degrades to null-fill, never aborts).
                            try {
                                val chunk = missPts.subList(start, end)
                                val resp = retryIO { api.envGrid(backendToken, EnvGridRequest(chunk.map { listOf(it.first, it.second) })) }
                                for (c in chunk.indices) {
                                    val lc = resp.landcover?.getOrNull(c)?.toInt()
                                    val cp = resp.canopy?.getOrNull(c)
                                    val nv = resp.ndvi?.getOrNull(c)
                                    val wd = resp.waterDist?.getOrNull(c)
                                    val sph = resp.soilPh?.getOrNull(c)
                                    val ssand = resp.soilSand?.getOrNull(c)
                                    val smoist = resp.soilMoisture?.getOrNull(c)
                                    val tw = resp.twi?.getOrNull(c)
                                    val ft = resp.forestType?.getOrNull(c)?.toInt()
                                    val orig = missIdx[start + c]
                                    landcover[orig] = lc; canopyPct[orig] = cp; ndvi[orig] = nv; waterDist[orig] = wd
                                    soilPh[orig] = sph; soilSand[orig] = ssand; soilMoisture[orig] = smoist; twi[orig] = tw
                                    forestType[orig] = ft
                                    cache.put(
                                        gridKey(missPts[start + c].first, missPts[start + c].second, fine),
                                        EnvCell(lc, cp, nv, wd, sph, ssand, smoist, tw, ft)
                                    )
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Earth Engine chunk fetch failed: ${e.message}")
                            }
                        }
                    }.awaitAll()
                }
            }
            if (!fine && missPts.isNotEmpty()) persistEnvCache()
            // Derive haveAny AFTER the joins (never mutate a shared flag inside async):
            // true if any cell resolved from cache (set above) or from any fetched chunk.
            haveAny = haveAny ||
                landcover.any { it != null } || canopyPct.any { it != null } || ndvi.any { it != null } ||
                waterDist.any { it != null } || soilPh.any { it != null } || soilSand.any { it != null } ||
                soilMoisture.any { it != null } || twi.any { it != null } || forestType.any { it != null }
            // Fall back to OSM canopy only if we got nothing at all.
            if (!haveAny) return@withContext null
            EnvLayers(
                landcover.toList(), canopyPct.toList(), ndvi.toList(), waterDist.toList(),
                soilPh.toList(), soilSand.toList(), soilMoisture.toList(), twi.toList(), forestType.toList()
            )
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
                val resp = withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                    geocodingApi.geocode(query, googleApiKey)
                }
                val result = resp?.results?.firstOrNull()
                val loc = result?.geometry?.location
                if (resp?.status == "OK" && loc?.lat != null && loc.lng != null) {
                    return@withContext GeoPlace(loc.lat, loc.lng, result.formattedAddress ?: query)
                }
                Log.w(TAG, "Google geocoding returned status=${resp?.status}; falling back")
            } catch (e: Exception) {
                Log.w(TAG, "Google geocoding failed, trying device geocoder: ${e.message}")
            }
        }
        try {
            val res = withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                val geocoder = android.location.Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 1)
            }
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
                val resp = withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                    geocodingApi.reverseGeocode(String.format(Locale.US, "%.6f,%.6f", lat, lng), googleApiKey)
                }
                val label = resp?.results?.firstOrNull()?.formattedAddress
                if (resp?.status == "OK" && !label.isNullOrBlank()) {
                    // Trim a verbose address to its first two components.
                    return@withContext label.split(",").take(2).joinToString(",").trim()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google reverse geocoding failed: ${e.message}")
            }
        }
        try {
            val a = withTimeoutOrNull(GEOCODER_TIMEOUT_MS) {
                val geocoder = android.location.Geocoder(context, Locale.getDefault())
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
            }
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
        dao.clearAllObservationCacheAreas()
        // Also empty every in-memory cache so a recompute actually re-fetches.
        elevCache.evictAll()
        envCache.evictAll()
        deepElevCache.evictAll()
        deepEnvCache.evictAll()
        deepCache.evictAll()
        areaObsCache.evictAll()
        recordCountCache.evictAll()
        globalSearchCache.evictAll()
        speciesPhotoCache.evictAll()
    }

    private fun ObservationCacheArea.covers(
        targetLat: Double,
        targetLng: Double,
        targetRadiusKm: Double
    ): Boolean {
        val centerDistanceKm =
            calculateDistanceMeters(centerLat, centerLng, targetLat, targetLng) / METERS_PER_KM
        // The requested search circle is reusable only when it fits entirely
        // inside the cached circle: distance between centres + requested radius
        // must not exceed the radius of the cached fetch.
        return centerDistanceKm + targetRadiusKm <= radiusKm
    }

    /**
     * Multi-factor weighted habitat-suitability engine — weighted multi-criteria
     * (Σ wᵢ·factorᵢ × moisture penalty × habitat gate) at adaptive ~250m
     * resolution; cell size grows for very large radii to bound cost.
     *
     * Scoring factors and weights (sum to 1.0):
     *   1. Observation evidence (iNat + ALA + GBIF + user)          — 0.21
     *   2. Seasonal fitness (week-level precision)                 — 0.14
     *   3. Rainfall trigger (20mm+ event 10-21 days ago with lag)  — 0.11
     *   4. Canopy/forest (EE land cover/canopy/NDVI, or OSM)       — 0.08
     *   5. Habitat suitability (species substrate/habitat breadth) — 0.08
     *   6. Temperature fitness (species-specific ideal range)      — 0.06
     *   7. Host tree match (EE forest leaf-type vs mycorrhizal host)— 0.05
     *   8. Terrain moisture (per-cell slope/concavity from DEM)    — 0.05
     *   9. Elevation fitness (per-cell altitude vs species band)   — 0.05
     *  10. Soil (EE surface pH + texture, OpenLandMap)             — 0.04
     *  11. Background + per-cell soil moisture (rain + EE 14-day)  — 0.03
     *  12. Topographic Wetness Index (EE MERIT Hydro)             — 0.03
     *  13. Riparian (per-cell distance to surface water, EE)       — 0.03
     *  14. Slope aspect (per-cell; south/east-facing favoured)     — 0.03
     *  15. Moon phase (optional, traditional forager signal)       — 0.01
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
    ): List<HotspotCell> {
        // Kingdom-wide nearby fungal activity — a habitat-productivity floor on the
        // evidence factor, so areas thick with real sightings aren't rated
        // "Unlikely" just because the target species wasn't logged in that cell.
        val ambient = try {
            getAllFungiObservations(centerLat, centerLng, radiusKm, forceRefresh)
        } catch (e: Exception) {
            Log.w(TAG, "ambient fungi fetch failed: ${e.message}"); emptyList()
        }
        return runSpeciesGrid(
            species, centerLat, centerLng,
            halfExtentMeters = radiusKm * 1000.0,
            cellMeters = adaptiveCellMeters(radiusKm),
            obsRadiusKm = radiusKm,
            terrainSpacingM = 500.0,   // preserve the overview grid's terrain calibration
            circularClip = true,
            fine = false,
            forceRefresh = forceRefresh,
            ambientObs = ambient
        )
    }

    /**
     * Shared single-species scoring pipeline used by both the broad overview grid
     * (generateHotspots) and the fine Deep-Search sub-grid (deepSearchCell). Builds
     * a grid of [cellMeters] cells out to [halfExtentMeters] from the centre
     * (circular for the overview, square for deep), fetches per-cell terrain +
     * Earth-Engine layers ([fine] selects the ~12 m caches for deep), and runs the
     * full multi-factor scoring. [terrainSpacingM] feeds the scale-aware
     * terrain/aspect curves (500 m for the overview, the sub-cell size for deep).
     */
    private suspend fun runSpeciesGrid(
        species: Species,
        centerLat: Double,
        centerLng: Double,
        halfExtentMeters: Double,
        cellMeters: Double,
        obsRadiusKm: Double,
        terrainSpacingM: Double,
        circularClip: Boolean,
        fine: Boolean,
        forceRefresh: Boolean,
        // Kingdom-wide nearby fungal records (the "all fungi" sightings layer).
        // Used as a habitat-productivity floor on the evidence factor, so an area
        // rich in fungal activity scores up even if the TARGET species itself
        // hasn't been logged in this exact cell.
        ambientObs: List<MapObservation> = emptyList()
    ): List<HotspotCell> = withContext(Dispatchers.Default) {
        // ── 1. Gather all evidence sources ──────────────────────────
        val iNatObs = getObservations(species, centerLat, centerLng, obsRadiusKm, forceRefresh)
        val userSightings = dao.getAllUserSightings().filter {
            it.speciesId == species.id &&
            calculateDistanceMeters(centerLat, centerLng, it.lat, it.lng) <= obsRadiusKm * 1000.0
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
        // Host tree groups for mycorrhizal matching against the EE forest-type
        // layer — derived once from the species' habitat/substrate descriptors.
        val hostGroups = MycoMath.hostGroupsFor(species.habitatTypes, species.substrates)

        // ── 4. Grid generation ──────────────────────────────────────
        // cellMeters / halfExtentMeters come from the caller: the overview uses an
        // adaptive ~250 m cell out to the search radius; Deep Search a fine sub-cell
        // over a single overview square.
        val latStep = cellMeters / 111_000.0
        val lngStep = cellMeters / (111_000.0 * Math.cos(Math.toRadians(centerLat)))
        val steps = ceil(halfExtentMeters / cellMeters).toInt()
        val cells = mutableListOf<HotspotCell>()

        val kernelRadiusMeters = 2500.0  // Wider search for evidence
        val maxDaysBack = 5.0 * 365.0

        // 4a. Enumerate the in-extent cells up front so the real terrain elevation
        // for the whole grid can be fetched in one batched call. The overview clips
        // to a circle (the search radius); Deep Search fills the square sub-area.
        val cellIJ = mutableListOf<Pair<Int, Int>>()
        val cellLL = mutableListOf<Pair<Double, Double>>()
        for (i in -steps..steps) {
            for (j in -steps..steps) {
                val cLat = centerLat + i * latStep
                val cLng = centerLng + j * lngStep
                if (circularClip && calculateDistanceMeters(centerLat, centerLng, cLat, cLng) > halfExtentMeters) continue
                cellIJ.add(i to j)
                cellLL.add(cLat to cLng)
            }
        }

        // 4b. Per-cell ground elevation (Open-Meteo, no key). Build a lookup
        // so each cell can read its neighbours' elevations for slope/aspect.
        val elevations = fetchElevations(cellLL, fine)
        val elevByIJ = HashMap<Pair<Int, Int>, Double>()
        cellIJ.forEachIndexed { idx, ij -> elevations[idx]?.let { elevByIJ[ij] = it } }

        // 4c. Canopy/vegetation. Prefer Earth Engine layers (land cover, tree
        // canopy %, NDVI) when the backend is configured; otherwise fall back
        // to free OSM forest proximity.
        val env = fetchEnvLayers(cellLL, fine)
        val speciesMulchAffinity = MycoMath.mulchAffinity(species.habitatTypes, species.substrates)
        // Habitat layers from OSM/Overpass (only when EE is off). Run CONCURRENTLY
        // and time-box each call so a slow/unreachable overpass-api.de can never
        // sink the grid — they degrade to neutral habitat instead of blocking past
        // the grid timeout. (The old sequential, retried calls could exceed the
        // 45 s budget and error the whole map to blank.) Canopy + land-use always;
        // mulch only for mulch-associated species (gold tops etc.).
        val needOsm = env == null && cellLL.isNotEmpty()
        val (canopy, landPolys, mulchFeatures) = if (needOsm) coroutineScope {
            val minLat = cellLL.minOf { it.first }; val minLng = cellLL.minOf { it.second }
            val maxLat = cellLL.maxOf { it.first }; val maxLng = cellLL.maxOf { it.second }
            val canopyD = async { withTimeoutOrNull(OVERPASS_CALL_TIMEOUT_MS) { fetchCanopyFeatures(minLat, minLng, maxLat, maxLng) } ?: emptyList() }
            val landD = async { withTimeoutOrNull(OVERPASS_CALL_TIMEOUT_MS) { fetchLandUsePolygons(minLat, minLng, maxLat, maxLng) } ?: emptyList() }
            val mulchD = async { if (speciesMulchAffinity > 0.0) (withTimeoutOrNull(OVERPASS_CALL_TIMEOUT_MS) { fetchMulchFeatures(minLat, minLng, maxLat, maxLng) } ?: emptyList()) else emptyList() }
            Triple(canopyD.await(), landD.await(), mulchD.await())
        } else Triple(emptyList<Pair<Double, Double>>(), emptyList<LandPolygon>(), emptyList<Pair<Double, Double>>())

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

                // Ambient fungal activity: nearby records of ANY fungus (the
                // kingdom-wide layer shown as sightings on the map) indicate
                // productive, fruiting habitat. They provide a modest evidence
                // FLOOR even when the target species itself isn't logged in this
                // exact cell — a weaker, non-species-specific proxy, so it's
                // capped well below a direct hit and never overrides real evidence.
                var ambientWeighted = 0.0
                for (obs in ambientObs) {
                    if (abs(obs.lat - cellLat) > 0.025 || abs(obs.lng - cellLng) > 0.035) continue
                    val d = calculateDistanceMeters(cellLat, cellLng, obs.lat, obs.lng)
                    if (d > kernelRadiusMeters) continue
                    val diffDays = (nowMs - obs.observedAt).toDouble() / (1000.0 * 60 * 60 * 24)
                    if (diffDays !in 0.0..maxDaysBack) continue
                    ambientWeighted += MycoMath.recencyWeight(diffDays, halfLifeDays = 365.0) *
                        MycoMath.spatialKernel(d, sigma = 1000.0)
                }
                val ambientActivity = minOf(1.0, ambientWeighted / 5.0)
                // Direct target-species evidence wins; ambient only raises a floor.
                val evidenceScore = maxOf(observationScore, 0.45 * ambientActivity)

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
                    MycoMath.terrainMoistureScore(cellElev, neighbourElevs, terrainSpacingM) else 0.5
                // Slope aspect (south/east-facing favoured in S. Hemisphere).
                val aspectScore = if (cellElev != null) MycoMath.slopeAspectMoistureScore(
                    cellElev,
                    elevByIJ[(i + 1) to j], elevByIJ[(i - 1) to j],
                    elevByIJ[i to (j + 1)], elevByIJ[i to (j - 1)],
                    cellSpacingM = terrainSpacingM
                ) else 0.7
                // Canopy/vegetation suitability — Earth Engine layers when
                // available, else OSM forest proximity (mycorrhizal & wood-rot).
                val canopyDist = if (canopy.isEmpty()) null else nearestFeatureMeters(cellLat, cellLng, canopy)
                val landClass = if (env == null) classifyLandCell(cellLat, cellLng, landPolys) else LandClass.NEUTRAL
                val canopyScore = if (env != null) MycoMath.richCanopyScore(
                    env.canopyPct.getOrNull(idx), env.ndvi.getOrNull(idx), env.landcover.getOrNull(idx), species.id
                ) else when (landClass) {
                    LandClass.GREEN -> maxOf(0.85, MycoMath.canopyProximityScore(canopyDist))
                    LandClass.BUILT -> 0.10
                    LandClass.NEUTRAL -> MycoMath.canopyProximityScore(canopyDist)
                }
                // Riparian: closeness to surface water (EE only; neutral otherwise).
                val riparianScore = if (env != null) MycoMath.riparianScore(env.waterDistM.getOrNull(idx)) else 0.45
                // Soil (pH + texture) and topographic wetness — Earth Engine only;
                // neutral when the backend isn't configured (no penalty).
                val soilScore = if (env != null)
                    MycoMath.richSoilScore(env.soilPh.getOrNull(idx), env.soilSand.getOrNull(idx)) else 0.6
                val twiScore = if (env != null) MycoMath.twiWetnessScore(env.twi.getOrNull(idx)) else 0.5
                // Host-tree (mycorrhizal) match: does this cell's forest leaf-type
                // contain one of the species' host trees? Neutral when EE is off.
                val hostTreeScore = if (env != null)
                    MycoMath.hostTreeMatchScore(env.forestType.getOrNull(idx), hostGroups) else 0.6
                // Per-cell soil moisture (EE 14-day mean) blended with the area-wide
                // rain/soil moisture signal, for real per-cell differentiation.
                val cellSoilMoisture = env?.soilMoisture?.getOrNull(idx)?.let { MycoMath.soilMoistureFitness(it) }
                val moistureScoreCell = if (cellSoilMoisture != null)
                    (0.5 * moistureScore + 0.5 * cellSoilMoisture) else moistureScore

                // ── C. Weighted factor combination ──────────────────
                // Evidence stays dominant; terrain, elevation, aspect and
                // canopy give real per-cell differentiation alongside the
                // global climate factors. Weights sum to 1.0.
                // Tanbark / woodchip signal: how close this cell is to a mapped
                // mulch bed, scaled by the species' mulch affinity (0 for forest
                // fungi → no effect). For gold tops & co. a woodchip bed under
                // foot is prime substrate, so it lifts the habitat factor and the
                // habitat gate (so urban mulch beds aren't suppressed as "built-up").
                val mulchDist = if (mulchFeatures.isEmpty()) null
                    else nearestFeatureMeters(cellLat, cellLng, mulchFeatures)
                val mulchSignal = speciesMulchAffinity * MycoMath.mulchProximityScore(mulchDist)
                val adjustedHabitat = maxOf(
                    (habitatScore * habitatWeight).coerceIn(0.0, 1.0),
                    mulchSignal
                )

                // Per-cell factor scores combined with the canonical, shared
                // weights (MycoMath.FACTOR_WEIGHTS) so this and the aggregate
                // pipeline can never drift apart.
                val factorScores = mapOf(
                    "evidence"    to evidenceScore,
                    "season"      to seasonScore,
                    "rainTrigger" to rainTriggerScore,
                    "canopy"      to canopyScore,
                    "hostTree"    to hostTreeScore,
                    "terrain"     to terrainScore,
                    "habitat"     to adjustedHabitat,
                    "elevation"   to elevationScore,
                    "temperature" to tempScore,
                    "riparian"    to riparianScore,
                    "aspect"      to aspectScore,
                    "moisture"    to moistureScoreCell,
                    "soil"        to soilScore,
                    "twi"         to twiScore,
                    "moon"        to moonScore
                )

                // Weighted sum with multiplicative penalty:
                // If season OR rain trigger is very low, cap the score —
                // you won't find fungi out of season in dry conditions
                // regardless of historical evidence.
                val weightedSum = MycoMath.weightedFactorScore(factorScores)
                // Conditions modifier — driven by ACTUAL ground wetness (recent
                // rain trigger + soil moisture), not the calendar. Fungi fruit
                // after rain regardless of the textbook season, and a shifting
                // climate makes those windows unreliable, so this keys off real
                // moisture; the calendar season is only a light weighted factor
                // above, never a gate. Range 0.55 (bone-dry) .. 1.0 (wet).
                val fruitingConditions = maxOf(rainTriggerScore, 0.85 * moistureScore)
                val penaltyMultiplier = (0.55 + 0.45 * fruitingConditions).coerceIn(0.0, 1.0)

                // Multiplicative HABITAT GATE — built-up/water/bare collapse the
                // score toward zero so cities, roads and car parks can't rank
                // high no matter how good the weather or how many records cluster
                // there. Uses real EE land cover/NDVI when available, else an OSM
                // canopy-proximity fallback.
                val rawGate = if (env != null)
                    MycoMath.habitatGate(env.landcover.getOrNull(idx), env.ndvi.getOrNull(idx), species.id)
                else when (landClass) {
                    LandClass.GREEN -> 0.95
                    LandClass.BUILT -> 0.10
                    LandClass.NEUTRAL -> (0.45 + 0.40 * MycoMath.canopyProximityScore(canopyDist))
                }
                // A mulch-loving species sitting in a mapped tanbark/woodchip bed
                // genuinely fruits there even on "built-up" ground, so the bed
                // lifts the gate floor (never lowers it) — keeping gold tops in
                // suburban garden beds on the map instead of gated to zero.
                val habitatGate = if (mulchSignal > 0.0)
                    maxOf(rawGate, (0.50 + 0.45 * mulchSignal).coerceAtMost(1.0)) else rawGate

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
                if (0.45 * ambientActivity > observationScore && ambientActivity > 0.0)
                    factors.add("🍄 Fungal activity nearby: lots of recorded sightings → evidence floor ${String.format(Locale.US, "%.0f", evidenceScore * 100)}%")

                factors.add("📅 Season: ${if (seasonScore > 0.5) "In window" else "Outside/shoulder"} ${monthName(species.seasonStart)}–${monthName(species.seasonEnd)} → ${String.format(Locale.US, "%.0f", seasonScore * 100)}%")
                factors.add("🌧️ Rain trigger: ${if (rainTriggerScore > 0.5) "Trigger event detected" else "No strong trigger"} (10-21d lag) → ${String.format(Locale.US, "%.0f", rainTriggerScore * 100)}%")
                factors.add("🌡️ Temperature: avg ${String.format(Locale.US, "%.1f", weather.avgTemp)}°C → ${String.format(Locale.US, "%.0f", tempScore * 100)}% fit for ${species.scientificName}")
                factors.add("🌲 Habitat: ${species.habitatTypes.joinToString(", ")} → ${String.format(Locale.US, "%.0f", adjustedHabitat * 100)}%")
                factors.add("🪵 Substrate: ${species.substrates.joinToString(", ")}")
                if (mulchSignal > 0.05) factors.add(
                    "🌰 Tanbark/woodchip bed ${if (mulchDist != null) "~${String.format(Locale.US, "%.0f m", mulchDist)} away" else "nearby"} — prime mulch substrate → habitat lifted to ${String.format(Locale.US, "%.0f", adjustedHabitat * 100)}%"
                )
                factors.add("⛰️ Elevation: ${if (cellElev != null) String.format(Locale.US, "%.0f m", cellElev) else "n/a"} → ${String.format(Locale.US, "%.0f", elevationScore * 100)}% fit")
                factors.add("🏞️ Terrain (slope/moisture): ${String.format(Locale.US, "%.0f", terrainScore * 100)}% | Aspect: ${String.format(Locale.US, "%.0f", aspectScore * 100)}%")
                if (env != null) {
                    val cv = env.canopyPct.getOrNull(idx)
                    val nv = env.ndvi.getOrNull(idx)
                    factors.add("🛰️ Earth Engine: canopy ${if (cv != null) String.format(Locale.US, "%.0f%%", cv) else "n/a"}, NDVI ${if (nv != null) String.format(Locale.US, "%.2f", nv) else "n/a"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%")
                } else {
                    factors.add(when (landClass) {
                        LandClass.GREEN -> "🌳 Green space (park / forest / reserve) → strong habitat → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
                        LandClass.BUILT -> "🏙️ Built-up land (residential / industrial / car park) → habitat suppressed → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
                        LandClass.NEUTRAL -> "🌳 Canopy: ${if (canopyDist != null) "~${String.format(Locale.US, "%.0f m", canopyDist)} to woodland" else "no land-use map data"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
                    })
                }
                if (env != null) {
                    val wd = env.waterDistM.getOrNull(idx)
                    factors.add("🌊 Water: ${if (wd != null) "~${String.format(Locale.US, "%.0f m", wd)} to water" else ">2 km away"} → ${String.format(Locale.US, "%.0f", riparianScore * 100)}%")
                    val ph = env.soilPh.getOrNull(idx)
                    val sand = env.soilSand.getOrNull(idx)
                    if (ph != null || sand != null) factors.add(
                        "🧪 Soil: ${if (ph != null) "pH ${String.format(Locale.US, "%.1f", ph)}" else "pH n/a"}, ${if (sand != null) "${String.format(Locale.US, "%.0f", sand)}% sand" else "texture n/a"} → ${String.format(Locale.US, "%.0f", soilScore * 100)}%"
                    )
                    env.soilMoisture.getOrNull(idx)?.let { sm ->
                        factors.add("💧 Soil moisture (14-day): ${String.format(Locale.US, "%.2f", sm)} m³/m³ → ${String.format(Locale.US, "%.0f", MycoMath.soilMoistureFitness(sm) * 100)}%")
                    }
                    env.twi.getOrNull(idx)?.let { tw ->
                        factors.add("🏞️ Wetness index (TWI): ${String.format(Locale.US, "%.1f", tw)} → ${String.format(Locale.US, "%.0f", twiScore * 100)}%")
                    }
                    if (hostGroups.isNotEmpty()) factors.add(
                        "🌲 Host tree: ${forestTypeLabel(env.forestType.getOrNull(idx))} vs ${hostGroups.joinToString("/") { hostGroupLabel(it) }} → ${String.format(Locale.US, "%.0f", hostTreeScore * 100)}%"
                    )
                }
                if (env == null) weather.avgSoilMoisture?.let { factors.add("💧 Soil moisture: ${String.format(Locale.US, "%.2f", it)} m³/m³ → ${String.format(Locale.US, "%.0f", MycoMath.soilMoistureFitness(it) * 100)}%") }
                if (moonScore > 0.7) factors.add("🌙 Moon phase favourable (traditional signal)")
                if (habitatGate < 0.95) factors.add("⛔ Habitat gate ×${String.format(Locale.US, "%.2f", habitatGate)} — built-up/water/bare ground suppresses this cell")
                val confidence = MycoMath.predictionConfidence(nearbyRecords, weightedEvidence, env != null, cellElev != null)
                factors.add("📶 Confidence: ${MycoMath.confidenceLabel(confidence)} — $nearbyRecords nearby record(s), ${if (env != null) "full" else "limited"} map data")
                factors.add("Multi-factor habitat-suitability estimate — not a guarantee of presence.")

                cells.add(HotspotCell(cellLat, cellLng, finalScore, tier, factors, cellSizeMeters = cellMeters, confidence = confidence))
        }
        return@withContext cells
    }

    // ── Deep Search (two-tier drill-down) ───────────────────────────────
    // In-memory cache of fine sub-grid results, keyed by parent cell + resolution.
    private val deepCache = android.util.LruCache<String, List<HotspotCell>>(24)

    /**
     * Refines a single promising overview cell into a fine (~[subResolutionMeters] m)
     * sub-grid for pinpoint foraging, WITHOUT touching the broad overview grid.
     * Reuses the exact single-species scoring pipeline (runSpeciesGrid) at a finer
     * cell size over a small area — the parent cell grown by [extentFactor] (≈300–600 m
     * for a 250 m parent at factor 2). The sub-cell count is capped (~1500) by
     * coarsening the resolution when the area is large, and the fine terrain/Earth
     * Engine samples use the dedicated ~12 m caches. Results are memoised per
     * parent cell + resolution so re-tapping a square is instant.
     *
     * @param parentRadiusKm the overview search radius the parent cell came from —
     *   used only as a fallback if the cell predates the cellSizeMeters field.
     */
    suspend fun deepSearchCell(
        species: Species,
        parentCell: HotspotCell,
        parentRadiusKm: Double,
        subResolutionMeters: Double = 15.0,
        extentFactor: Double = 2.0
    ): List<HotspotCell> {
        val parentCellMeters = parentCell.cellSizeMeters.takeIf { it > 0.0 }
            ?: maxOf(250.0, parentRadiusKm * 1000.0 / 60.0)
        val extentMeters = parentCellMeters * extentFactor.coerceIn(1.0, 4.0)
        // Adaptive coarsening: keep (extent/cell)² under the cell cap.
        val maxSubCells = 1500
        val minCell = extentMeters / sqrt(maxSubCells.toDouble())
        val cell = maxOf(subResolutionMeters, minCell)

        // Key by species too: env/elevation are species-independent (shared fine
        // caches), but the SCORED result is per-species, so two species drilled
        // into the same square must not share a cached result.
        val key = "${species.id}@${gridKey(parentCell.lat, parentCell.lng)}@${cell.toInt()}"
        deepCache.get(key)?.let { return it }

        val result = runSpeciesGrid(
            species, parentCell.lat, parentCell.lng,
            halfExtentMeters = extentMeters / 2.0,
            cellMeters = cell,
            // Cover the 2.5 km evidence kernel around the parent, not just the cell.
            obsRadiusKm = maxOf(3.0, extentMeters / 2.0 / 1000.0 + 2.6),
            terrainSpacingM = cell,    // fine-scale terrain/aspect discrimination
            circularClip = false,      // fill the square sub-area
            fine = true,               // use the ~12 m elevation/Earth-Engine caches
            forceRefresh = false
        )
        deepCache.put(key, result)
        return result
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

        // Evidence for the aggregate map = EVERY nearby fungal sighting
        // (kingdom-wide), fetched in ONE call below. We deliberately do NOT loop
        // the whole catalogue here: ~74 per-species iNaturalist calls hammer the
        // API rate limit and time the grid out regardless of radius (1 km failed
        // too) — and they're redundant, since getAllFungiObservations already
        // captures on-the-ground fungal activity across every species.
        val allObservations = mutableListOf<Observation>()

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

        // Adaptive cell size: ~250 m for typical searches (fine per-cell detail),
        // growing only for very large radii so the cell count — and Earth Engine
        // cost — stays bounded (~60 steps per axis max).
        val cellMeters = adaptiveCellMeters(radiusKm)
        val latStep = cellMeters / 111_000.0
        val lngStep = cellMeters / (111_000.0 * Math.cos(Math.toRadians(centerLat)))
        val latRangeSteps = ceil((radiusKm * 1000.0) / cellMeters).toInt()
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
        // Habitat layers from OSM/Overpass (only when EE is off) — concurrent +
        // time-boxed so a slow overpass-api.de can't sink the aggregate grid.
        val needOsm = env == null && cellLL.isNotEmpty()
        val (canopy, landPolys) = if (needOsm) coroutineScope {
            val minLat = cellLL.minOf { it.first }; val minLng = cellLL.minOf { it.second }
            val maxLat = cellLL.maxOf { it.first }; val maxLng = cellLL.maxOf { it.second }
            val canopyD = async { withTimeoutOrNull(OVERPASS_CALL_TIMEOUT_MS) { fetchCanopyFeatures(minLat, minLng, maxLat, maxLng) } ?: emptyList() }
            val landD = async { withTimeoutOrNull(OVERPASS_CALL_TIMEOUT_MS) { fetchLandUsePolygons(minLat, minLng, maxLat, maxLng) } ?: emptyList() }
            canopyD.await() to landD.await()
        } else Pair(emptyList<Pair<Double, Double>>(), emptyList<LandPolygon>())

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
                val landClass = if (env == null) classifyLandCell(cellLat, cellLng, landPolys) else LandClass.NEUTRAL
                val canopyScore = if (env != null) MycoMath.richCanopyScore(
                    env.canopyPct.getOrNull(idx), env.ndvi.getOrNull(idx), env.landcover.getOrNull(idx), "aggregate_default"
                ) else when (landClass) {
                    LandClass.GREEN -> maxOf(0.85, MycoMath.canopyProximityScore(canopyDist))
                    LandClass.BUILT -> 0.10
                    LandClass.NEUTRAL -> MycoMath.canopyProximityScore(canopyDist)
                }
                val riparianScore = if (env != null) MycoMath.riparianScore(env.waterDistM.getOrNull(idx)) else 0.45
                val soilScore = if (env != null)
                    MycoMath.richSoilScore(env.soilPh.getOrNull(idx), env.soilSand.getOrNull(idx)) else 0.6
                val twiScore = if (env != null) MycoMath.twiWetnessScore(env.twi.getOrNull(idx)) else 0.5
                // Aggregate spans the whole catalogue, so match against all host
                // groups — any forest type then rewards a likely host present.
                val hostTreeScore = if (env != null) MycoMath.hostTreeMatchScore(
                    env.forestType.getOrNull(idx),
                    setOf(MycoMath.HostGroup.NEEDLELEAF, MycoMath.HostGroup.EVERGREEN_BROADLEAF, MycoMath.HostGroup.DECIDUOUS_BROADLEAF)
                ) else 0.6
                val cellSoilMoisture = env?.soilMoisture?.getOrNull(idx)?.let { MycoMath.soilMoistureFitness(it) }
                val moistureScoreCell = if (cellSoilMoisture != null)
                    (0.5 * moistureScore + 0.5 * cellSoilMoisture) else moistureScore

                // Weighted combination using the same canonical weights as the
                // single-species grid (MycoMath.FACTOR_WEIGHTS); only the habitat
                // factor differs — a flat baseline for the diverse catalogue.
                val weightedSum = MycoMath.weightedFactorScore(
                    mapOf(
                        "evidence"    to observationScore,
                        "season"      to seasonScore,
                        "rainTrigger" to rainTriggerScore,
                        "canopy"      to canopyScore,
                        "hostTree"    to hostTreeScore,
                        "terrain"     to terrainScore,
                        "habitat"     to 0.7, // aggregate habitat baseline (diverse catalogue)
                        "elevation"   to elevationScore,
                        "temperature" to tempScore,
                        "riparian"    to riparianScore,
                        "aspect"      to aspectScore,
                        "moisture"    to moistureScoreCell,
                        "soil"        to soilScore,
                        "twi"         to twiScore,
                        "moon"        to moonScore
                    )
                )

                // Conditions modifier — driven by ACTUAL ground wetness (recent
                // rain trigger + soil moisture), not the calendar. Fungi fruit
                // after rain regardless of the textbook season, and a shifting
                // climate makes those windows unreliable, so this keys off real
                // moisture; the calendar season is only a light weighted factor
                // above, never a gate. Range 0.55 (bone-dry) .. 1.0 (wet).
                val fruitingConditions = maxOf(rainTriggerScore, 0.85 * moistureScore)
                val penaltyMultiplier = (0.55 + 0.45 * fruitingConditions).coerceIn(0.0, 1.0)
                // Habitat gate — suppress built-up/water/bare cells (see single-species note).
                val habitatGate = if (env != null)
                    MycoMath.habitatGate(env.landcover.getOrNull(idx), env.ndvi.getOrNull(idx), "aggregate_default")
                else when (landClass) {
                    LandClass.GREEN -> 0.95
                    LandClass.BUILT -> 0.10
                    LandClass.NEUTRAL -> (0.45 + 0.40 * MycoMath.canopyProximityScore(canopyDist))
                }
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
                    val ph = env.soilPh.getOrNull(idx)
                    val sand = env.soilSand.getOrNull(idx)
                    if (ph != null || sand != null) factors.add(
                        "🧪 Soil: ${if (ph != null) "pH ${String.format(Locale.US, "%.1f", ph)}" else "pH n/a"}, ${if (sand != null) "${String.format(Locale.US, "%.0f", sand)}% sand" else "texture n/a"} → ${String.format(Locale.US, "%.0f", soilScore * 100)}%"
                    )
                    env.soilMoisture.getOrNull(idx)?.let { sm ->
                        factors.add("💧 Soil moisture (14-day): ${String.format(Locale.US, "%.2f", sm)} m³/m³ → ${String.format(Locale.US, "%.0f", MycoMath.soilMoistureFitness(sm) * 100)}%")
                    }
                    env.twi.getOrNull(idx)?.let { tw ->
                        factors.add("🏞️ Wetness index (TWI): ${String.format(Locale.US, "%.1f", tw)} → ${String.format(Locale.US, "%.0f", twiScore * 100)}%")
                    }
                    env.forestType.getOrNull(idx)?.let { ft ->
                        factors.add("🌲 Forest type: ${forestTypeLabel(ft)} → ${String.format(Locale.US, "%.0f", hostTreeScore * 100)}%")
                    }
                } else {
                    factors.add(when (landClass) {
                        LandClass.GREEN -> "🌳 Green space (park / forest / reserve) → strong habitat → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
                        LandClass.BUILT -> "🏙️ Built-up land (residential / industrial / car park) → habitat suppressed → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
                        LandClass.NEUTRAL -> "🌳 Canopy: ${if (canopyDist != null) "~${String.format(Locale.US, "%.0f m", canopyDist)} to woodland" else "no land-use map data"} → ${String.format(Locale.US, "%.0f", canopyScore * 100)}%"
                    })
                }
                if (habitatGate < 0.95) factors.add("⛔ Habitat gate ×${String.format(Locale.US, "%.2f", habitatGate)} — built-up/water/bare ground suppresses this cell")
                if (nearbySpecies.size >= 3) factors.add("🌿 Diversity bonus: ${nearbySpecies.size} distinct species recorded nearby")
                val confidence = MycoMath.predictionConfidence(nearbyRecords, weightedEvidence, env != null, cellElev != null)
                factors.add("📶 Confidence: ${MycoMath.confidenceLabel(confidence)} — $nearbyRecords nearby record(s), ${if (env != null) "full" else "limited"} map data")
                factors.add("Multi-factor aggregate estimate — not species-specific.")

                cells.add(HotspotCell(cellLat, cellLng, finalScore, tier, factors, cellSizeMeters = cellMeters, confidence = confidence))
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
                    "\"Myceliyums User\"," +
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

    /** Human label for a Copernicus forest leaf-type class code. */
    private fun forestTypeLabel(forestType: Int?): String = when (forestType) {
        1 -> "evergreen-needleleaf forest"
        2 -> "evergreen-broadleaf forest"
        3 -> "deciduous-needleleaf forest"
        4 -> "deciduous-broadleaf forest"
        5 -> "mixed forest"
        else -> "no mapped forest"
    }

    /** Short label for a host tree group. */
    private fun hostGroupLabel(g: MycoMath.HostGroup): String = when (g) {
        MycoMath.HostGroup.NEEDLELEAF -> "pine/conifer"
        MycoMath.HostGroup.EVERGREEN_BROADLEAF -> "eucalypt/native"
        MycoMath.HostGroup.DECIDUOUS_BROADLEAF -> "oak/birch"
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

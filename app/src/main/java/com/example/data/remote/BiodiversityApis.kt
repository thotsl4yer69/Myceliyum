package com.example.data.remote

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Atlas of Living Australia biocache web service.
 * Provides access to Australian biodiversity occurrence records including
 * herbarium specimens (verified by mycologists — weighted highest).
 *
 * Base URL: https://biocache-ws.ala.org.au/ws/
 */
interface ALAApi {
    @GET("occurrences/search")
    suspend fun searchOccurrences(
        @Query("q") query: String,             // e.g. "Psilocybe subaeruginosa"
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("radius") radiusKm: Double,
        @Query("fq") filterQuery: String = "kingdom:Fungi",
        @Query("pageSize") pageSize: Int = 100,
        @Query("sort") sort: String = "year",
        @Query("dir") dir: String = "desc"
    ): ALAResponse
}

data class ALAResponse(
    @Json(name = "totalRecords") val totalRecords: Int,
    @Json(name = "occurrences") val occurrences: List<ALAOccurrence>?
)

data class ALAOccurrence(
    @Json(name = "uuid") val uuid: String?,
    @Json(name = "scientificName") val scientificName: String?,
    @Json(name = "decimalLatitude") val decimalLatitude: Double?,
    @Json(name = "decimalLongitude") val decimalLongitude: Double?,
    @Json(name = "eventDate") val eventDate: String?,      // ISO date
    @Json(name = "year") val year: Int?,
    @Json(name = "month") val month: Int?,
    @Json(name = "basisOfRecord") val basisOfRecord: String?,  // "PRESERVED_SPECIMEN", "HUMAN_OBSERVATION", etc.
    @Json(name = "institutionCode") val institutionCode: String?,
    @Json(name = "dataResourceName") val dataResourceName: String?
)

/**
 * GBIF Occurrence API.
 * Global Biodiversity Information Facility — aggregates occurrence records
 * from museums, herbaria, and citizen science platforms worldwide.
 *
 * Base URL: https://api.gbif.org/v1/
 */
interface GBIFApi {
    @GET("occurrence/search")
    suspend fun searchOccurrences(
        @Query("scientificName") scientificName: String,
        @Query("decimalLatitude") lat: String,       // e.g. "-38,-37" for range
        @Query("decimalLongitude") lon: String,      // e.g. "144,146" for range
        @Query("kingdomKey") kingdomKey: Int = 5,    // Fungi kingdom key in GBIF
        @Query("country") country: String = "AU",
        @Query("limit") limit: Int = 100,
        @Query("hasCoordinate") hasCoordinate: Boolean = true
    ): GBIFResponse

    /**
     * All fungal occurrence records (museum/herbarium + observations) in a map
     * area — used to plot global GBIF specimen pins alongside iNaturalist
     * sightings. No species filter; kingdomKey=5 keeps it to Fungi.
     */
    @GET("occurrence/search")
    suspend fun searchAreaOccurrences(
        @Query("decimalLatitude") lat: String,        // "min,max"
        @Query("decimalLongitude") lon: String,       // "min,max"
        @Query("kingdomKey") kingdomKey: Int = 5,
        @Query("hasCoordinate") hasCoordinate: Boolean = true,
        @Query("hasGeospatialIssue") hasGeoIssue: Boolean = false,
        @Query("limit") limit: Int = 200
    ): GBIFResponse

    /**
     * Total GBIF record count for a species worldwide (limit=0 returns the
     * count without the records). Surfaced on the detail screen as how widely
     * the species has been recorded.
     */
    @GET("occurrence/search")
    suspend fun countOccurrences(
        @Query("scientificName") scientificName: String,
        @Query("kingdomKey") kingdomKey: Int = 5,
        @Query("limit") limit: Int = 0
    ): GBIFResponse

    /**
     * Global fungal taxonomy search — the GBIF species backbone covers every
     * described fungus on Earth (~150k species). `highertaxonKey=5` constrains
     * to kingdom Fungi. This turns the catalogue into a searchable front-end
     * over the entire global fungal taxonomy, not just the bundled field guide.
     *
     * Deliberately loose: [rank] and [status] are nullable and omitted by
     * default, so synonyms, genus-level hits and infraspecific names all
     * surface (a search for a well-known synonym or a bare genus shouldn't come
     * back empty). Callers re-rank the results client-side by relevance.
     */
    @GET("species/search")
    suspend fun searchSpecies(
        @Query("q") query: String,
        @Query("highertaxonKey") fungiKingdomKey: Int = 5,
        @Query("rank") rank: String? = null,
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 60
    ): GBIFSpeciesResponse
}

data class GBIFSpeciesResponse(
    @Json(name = "results") val results: List<GBIFSpecies>?
)

data class GBIFSpecies(
    @Json(name = "key") val key: Long?,
    @Json(name = "scientificName") val scientificName: String?,
    @Json(name = "canonicalName") val canonicalName: String?,
    @Json(name = "kingdom") val kingdom: String?,
    @Json(name = "phylum") val phylum: String?,
    @Json(name = "order") val order: String?,
    @Json(name = "family") val family: String?,
    @Json(name = "genus") val genus: String?,
    @Json(name = "rank") val rank: String?,
    @Json(name = "vernacularNames") val vernacularNames: List<GBIFVernacular>?
)

data class GBIFVernacular(
    @Json(name = "vernacularName") val vernacularName: String?,
    @Json(name = "language") val language: String?
)

data class GBIFResponse(
    @Json(name = "count") val count: Int,
    @Json(name = "results") val results: List<GBIFOccurrence>?
)

data class GBIFOccurrence(
    @Json(name = "key") val key: Long?,
    @Json(name = "scientificName") val scientificName: String?,
    @Json(name = "decimalLatitude") val decimalLatitude: Double?,
    @Json(name = "decimalLongitude") val decimalLongitude: Double?,
    @Json(name = "eventDate") val eventDate: String?,
    @Json(name = "year") val year: Int?,
    @Json(name = "month") val month: Int?,
    @Json(name = "basisOfRecord") val basisOfRecord: String?,  // "PRESERVED_SPECIMEN", "HUMAN_OBSERVATION"
    @Json(name = "institutionCode") val institutionCode: String?,
    @Json(name = "datasetName") val datasetName: String?,
    @Json(name = "issues") val issues: List<String>?
)

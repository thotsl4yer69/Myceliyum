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
}

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

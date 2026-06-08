package com.example.data.remote

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

interface INaturalistApi {
    @GET("observations")
    suspend fun getObservations(
        @Query("taxon_name") taxonName: String,
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius") radiusKm: Double,
        @Query("d1") sinceDate: String, // YYYY-MM-DD
        @Query("quality_grade") qualityGrade: String = "research",
        @Query("per_page") perPage: Int = 100
    ): INatResponse
}

data class INatResponse(
    @Json(name = "results") val results: List<INatObservation>
)

data class INatObservation(
    @Json(name = "id") val id: Long,
    @Json(name = "latitude") val latitude: Double?,
    @Json(name = "longitude") val longitude: Double?,
    @Json(name = "location") val location: String?,
    @Json(name = "geojson") val geojson: INatGeoJson?,
    @Json(name = "observed_on") val observedOn: String?,
    @Json(name = "quality_grade") val qualityGrade: String?,
    @Json(name = "photos") val photos: List<INatPhoto>?
)

// iNaturalist returns coordinates as GeoJSON: { "coordinates": [lng, lat] }
data class INatGeoJson(
    @Json(name = "coordinates") val coordinates: List<Double>?
)

data class INatPhoto(
    @Json(name = "url") val url: String?
)

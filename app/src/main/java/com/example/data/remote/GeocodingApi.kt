package com.example.data.remote

import com.squareup.moshi.Json
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Google Maps Geocoding API — converts a place name ("Dandenong Ranges",
 * "Sherbrooke Forest") into coordinates for the map's "Area" search. More
 * reliable than the on-device Android Geocoder, which frequently returns no
 * results when the device lacks a backing geocoder service.
 *
 * Base URL: https://maps.googleapis.com/  (needs BuildConfig.GOOGLE_API_KEY)
 */
interface GeocodingApi {
    @GET("maps/api/geocode/json")
    suspend fun geocode(
        @Query("address") address: String,
        @Query("key") key: String
    ): GeocodeResponse

    /** Reverse geocode: coordinates → a place name for the map header. */
    @GET("maps/api/geocode/json")
    suspend fun reverseGeocode(
        @Query("latlng") latlng: String,
        @Query("key") key: String,
        @Query("result_type") resultType: String = "locality|administrative_area_level_2|administrative_area_level_1|natural_feature|park"
    ): GeocodeResponse
}

data class GeocodeResponse(
    @Json(name = "status") val status: String?,
    @Json(name = "results") val results: List<GeocodeResult>?
)

data class GeocodeResult(
    @Json(name = "formatted_address") val formattedAddress: String?,
    @Json(name = "geometry") val geometry: GeocodeGeometry?
)

data class GeocodeGeometry(
    @Json(name = "location") val location: GeocodeLocation?
)

data class GeocodeLocation(
    @Json(name = "lat") val lat: Double?,
    @Json(name = "lng") val lng: Double?
)

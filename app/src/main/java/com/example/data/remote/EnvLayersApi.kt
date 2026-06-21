package com.example.data.remote

import com.squareup.moshi.Json
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Client for the Mycelium Mapper environmental-layers backend (Cloud Run +
 * Google Earth Engine). The backend keeps the service-account credential
 * server-side, so the app only ever talks HTTPS — no GCP key ships in the APK.
 *
 * Base URL is supplied at build time via BuildConfig.BACKEND_BASE_URL; when
 * blank the app skips this entirely and falls back to the free OSM canopy.
 */
interface EnvLayersApi {
    @POST("env-grid")
    suspend fun envGrid(
        @Header("X-Api-Token") token: String,
        @Body request: EnvGridRequest
    ): EnvGridResponse
}

/** Grid points as [[lat, lng], ...]. */
data class EnvGridRequest(
    @Json(name = "points") val points: List<List<Double>>
)

/** Arrays aligned 1:1 with the request points; entries may be null. */
data class EnvGridResponse(
    @Json(name = "landcover") val landcover: List<Double?>?, // ESA WorldCover class codes
    @Json(name = "canopy") val canopy: List<Double?>?,       // tree-canopy cover %
    @Json(name = "ndvi") val ndvi: List<Double?>?,           // NDVI greenness (−1..1)
    @Json(name = "water_dist") val waterDist: List<Double?>? = null, // metres to nearest surface water
    @Json(name = "soil_ph") val soilPh: List<Double?>? = null,       // surface soil pH (H2O)
    @Json(name = "soil_sand") val soilSand: List<Double?>? = null,   // surface sand mass-fraction %
    @Json(name = "soil_moisture") val soilMoisture: List<Double?>? = null, // 14-day mean vol. soil water (m³/m³)
    @Json(name = "twi") val twi: List<Double?>? = null,      // topographic wetness index
    @Json(name = "forest_type") val forestType: List<Double?>? = null // Copernicus forest leaf-type class (1-5)
)

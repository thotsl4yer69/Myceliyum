package com.example.data.remote

import com.squareup.moshi.Json
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * OpenStreetMap Overpass API — free, no API key.
 *
 * Used to pull canopy/green-cover features (woods, forests, parks, nature
 * reserves) within the hotspot grid's bounding box in a single query. Each
 * cell is then scored by its proximity to the nearest such feature, giving a
 * real per-cell "is this woodland?" signal for mycorrhizal / wood-rotting fungi.
 *
 * Base URL: https://overpass-api.de/
 */
interface OverpassApi {
    @FormUrlEncoded
    @POST("api/interpreter")
    suspend fun query(@Field("data") overpassQl: String): OverpassResponse
}

data class OverpassResponse(
    @Json(name = "elements") val elements: List<OverpassElement>?
)

/**
 * An Overpass element. For ways/relations we request `out center;`, so the
 * representative coordinate arrives in [center]; nodes carry [lat]/[lon].
 * When a query uses `out geom;` instead, the full ring is in [geometry] and the
 * OSM [tags] are present so callers can classify the feature (green vs built).
 */
data class OverpassElement(
    @Json(name = "type") val type: String?,
    @Json(name = "lat") val lat: Double?,
    @Json(name = "lon") val lon: Double?,
    @Json(name = "center") val center: OverpassCenter?,
    @Json(name = "tags") val tags: Map<String, String>? = null,
    @Json(name = "geometry") val geometry: List<OverpassGeom>? = null
) {
    /** Best-available latitude for this element (node lat or way/relation centre). */
    fun resolvedLat(): Double? = lat ?: center?.lat

    /** Best-available longitude for this element (node lon or way/relation centre). */
    fun resolvedLon(): Double? = lon ?: center?.lon
}

data class OverpassCenter(
    @Json(name = "lat") val lat: Double?,
    @Json(name = "lon") val lon: Double?
)

/** A single vertex of a way's geometry, present when a query uses `out geom;`. */
data class OverpassGeom(
    @Json(name = "lat") val lat: Double?,
    @Json(name = "lon") val lon: Double?
)

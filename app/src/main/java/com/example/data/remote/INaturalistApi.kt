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

    /**
     * Taxon lookup — pulls a gallery of reference photos for a species by
     * scientific name. iNaturalist returns curated `taxon_photos` (CC
     * licensed, attributed) plus a `default_photo`, giving every species
     * multiple images without bundling them in the APK.
     */
    @GET("taxa")
    suspend fun getTaxa(
        @Query("q") query: String,
        @Query("rank") rank: String = "species",
        @Query("per_page") perPage: Int = 1
    ): INatTaxaResponse

    /**
     * All fungal observations in a map area in a SINGLE request, by querying
     * the whole Fungi kingdom (taxon_name=Fungi) rather than 40 per-species
     * calls. Powers the "all sightings" map layer and the aggregate
     * fungal-activity signal in predictions. `quality_grade` is left
     * unconstrained so casual + needs-id records show too; `photos`/`geo`
     * keep only mappable, evidenced records.
     */
    @GET("observations")
    suspend fun getAreaObservations(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius") radiusKm: Double,
        @Query("d1") sinceDate: String,
        @Query("taxon_name") taxonName: String = "Fungi",
        @Query("photos") hasPhotos: Boolean = true,
        @Query("geo") hasGeo: Boolean = true,
        @Query("order_by") orderBy: String = "observed_on",
        @Query("order") order: String = "desc",
        @Query("per_page") perPage: Int = 200
    ): INatResponse
}

data class INatTaxaResponse(
    @Json(name = "results") val results: List<INatTaxon>?
)

data class INatTaxon(
    @Json(name = "name") val name: String?,
    @Json(name = "default_photo") val defaultPhoto: INatTaxonPhoto?,
    @Json(name = "taxon_photos") val taxonPhotos: List<INatTaxonPhotoWrap>?
)

data class INatTaxonPhotoWrap(
    @Json(name = "photo") val photo: INatTaxonPhoto?
)

data class INatTaxonPhoto(
    @Json(name = "medium_url") val mediumUrl: String?,
    @Json(name = "url") val url: String?,
    @Json(name = "attribution") val attribution: String?
)

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
    @Json(name = "photos") val photos: List<INatPhoto>?,
    @Json(name = "taxon") val taxon: INatObsTaxon?,
    @Json(name = "place_guess") val placeGuess: String?
)

// Taxon attached to an observation — used to label map pins with the
// species/genus name and common name when querying the whole Fungi kingdom.
data class INatObsTaxon(
    @Json(name = "name") val name: String?,
    @Json(name = "preferred_common_name") val commonName: String?,
    @Json(name = "rank") val rank: String?
)

// iNaturalist returns coordinates as GeoJSON: { "coordinates": [lng, lat] }
data class INatGeoJson(
    @Json(name = "coordinates") val coordinates: List<Double>?
)

data class INatPhoto(
    @Json(name = "url") val url: String?
)

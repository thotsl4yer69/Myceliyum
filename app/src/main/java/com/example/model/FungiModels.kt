package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "species")
data class Species(
    @PrimaryKey val id: String,
    val scientificName: String,
    val commonNames: List<String>,
    val genus: String,
    val family: String,
    val habitatTypes: List<String>,
    val substrates: List<String>,
    val seasonStart: Int, // 1 = January, 12 = December
    val seasonEnd: Int,
    val capDescription: String,
    val gillDescription: String,
    val stemDescription: String,
    val sporeColor: String,
    val bruisingReaction: String,
    val lookAlikes: List<String>,
    val notes: String,
    val imageUrls: List<String>
)

@Entity(tableName = "observations")
data class Observation(
    @PrimaryKey val id: Long,
    val speciesId: String,
    val lat: Double,
    val lng: Double,
    val observedAt: Long, // timestamp
    val source: String, // "iNaturalist", "MushroomObserver", "ALA", or "user"
    val photoUrl: String?,
    val qualityGrade: String, // "research", "needs_id", "casual"
    val cachedAt: Long = System.currentTimeMillis() // for TTL check
)

/**
 * A reference photo with its attribution/credit line (iNaturalist taxon
 * photos are CC-licensed and require attribution). Bundled images have no
 * attribution (null).
 */
data class SpeciesPhoto(
    val url: String,
    val attribution: String?
)

/**
 * A lightweight, in-memory observation for the "all fungi sightings" map
 * layer — NOT persisted to Room (so no schema migration). Carries the taxon
 * label so pins can show the species/genus and common name straight from the
 * kingdom-wide iNaturalist query.
 */
data class MapObservation(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val taxonName: String,      // e.g. "Amanita muscaria" or "Cortinarius"
    val commonName: String?,    // e.g. "Fly Agaric"
    val source: String,         // "iNaturalist", "ALA", "GBIF"
    val observedAt: Long,
    val qualityGrade: String,
    val photoUrl: String?,
    val placeGuess: String?
)

@Entity(tableName = "user_sightings")
data class UserSighting(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val speciesId: String,
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val photoLocalPath: String?,
    val notes: String,
    val isPrivate: Boolean
)

/**
 * 5-tier hotspot cell for the prediction grid.
 *
 * Tiers:
 *   Excellent  (>80%)  — perfect storm of evidence, season, and conditions
 *   VeryGood   (60-80%) — strong indicators across multiple factors
 *   Promising  (40-60%) — several positive factors present
 *   Possible   (20-40%) — some positive indicators
 *   Unlikely   (<20%)  — few or no positive indicators
 */
data class HotspotCell(
    val lat: Double,
    val lng: Double,
    val score: Double, // 0 to 1
    val tier: String,  // "Excellent" / "VeryGood" / "Promising" / "Possible" / "Unlikely"
    val contributingFactors: List<String>,
    // Edge length (metres) of this cell's square — the grid resolution it was
    // scored at. Lets the map draw each cell at its true size, so the broad
    // ~250 m overview grid and a fine Deep-Search sub-grid render correctly on
    // the same map. Defaults to the overview grid's nominal size.
    val cellSizeMeters: Double = 250.0
)

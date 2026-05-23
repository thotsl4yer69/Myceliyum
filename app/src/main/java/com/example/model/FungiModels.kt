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
    val source: String, // "iNaturalist" or "user"
    val photoUrl: String?,
    val qualityGrade: String,
    val cachedAt: Long = System.currentTimeMillis() // for TTL check
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

data class HotspotCell(
    val lat: Double,
    val lng: Double,
    val score: Double, // 0 to 1
    val tier: String, // "Low" / "Medium" / "High"
    val contributingFactors: List<String>
)

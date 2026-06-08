package com.example.data.local

import androidx.room.*
import com.example.model.Observation
import com.example.model.Species
import com.example.model.UserSighting
import kotlinx.coroutines.flow.Flow

@Dao
interface FungiDao {
    // Species
    @Query("SELECT * FROM species")
    fun getAllSpeciesFlow(): Flow<List<Species>>

    @Query("SELECT * FROM species")
    suspend fun getAllSpecies(): List<Species>

    @Query("SELECT * FROM species WHERE id = :id")
    suspend fun getSpeciesById(id: String): Species?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpecies(species: List<Species>)

    // Observations cache
    @Query("SELECT * FROM observations WHERE speciesId = :speciesId")
    suspend fun getCachedObservations(speciesId: String): List<Observation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertObservations(observations: List<Observation>)

    @Query("DELETE FROM observations WHERE speciesId = :speciesId")
    suspend fun clearObservationsForSpecies(speciesId: String)

    @Query("DELETE FROM observations")
    suspend fun clearAllCachedObservations()

    // User Sightings
    @Query("SELECT * FROM user_sightings ORDER BY timestamp DESC")
    fun getAllUserSightingsFlow(): Flow<List<UserSighting>>

    @Query("SELECT * FROM user_sightings ORDER BY timestamp DESC")
    suspend fun getAllUserSightings(): List<UserSighting>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserSighting(sighting: UserSighting)

    @Delete
    suspend fun deleteUserSighting(sighting: UserSighting)

}

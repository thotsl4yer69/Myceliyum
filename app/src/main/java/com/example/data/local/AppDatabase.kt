package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.model.Observation
import com.example.model.ObservationCacheArea
import com.example.model.Species
import com.example.model.UserSighting

@Database(
    entities = [Species::class, Observation::class, ObservationCacheArea::class, UserSighting::class],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fungiDao(): FungiDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mycelium_mapper_db"
                )
                .addMigrations(*MIGRATIONS)
                .build()
                INSTANCE = instance
                instance
            }
        }

        // Only derived catalogues/caches are rebuilt. Personal records are never dropped.
        val MIGRATIONS: Array<Migration> = (1..4).map { old ->
            object : Migration(old, 5) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    val userColumns = mutableSetOf<String>()
                    database.query("PRAGMA table_info(user_sightings)").use { c ->
                        while (c.moveToNext()) userColumns.add(c.getString(c.getColumnIndexOrThrow("name")))
                    }
                    check(userColumns.containsAll(listOf("id", "speciesId", "lat", "lng", "timestamp"))) {
                        "Unsupported personal-record schema: preserve the database for recovery"
                    }
                    if ("photoLocalPath" !in userColumns) database.execSQL("ALTER TABLE user_sightings ADD COLUMN photoLocalPath TEXT")
                    if ("notes" !in userColumns) database.execSQL("ALTER TABLE user_sightings ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
                    if ("isPrivate" !in userColumns) database.execSQL("ALTER TABLE user_sightings ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 1")
                    database.execSQL("DROP TABLE IF EXISTS observations")
                    database.execSQL("CREATE TABLE observations (id TEXT NOT NULL PRIMARY KEY, speciesId TEXT NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL, observedAt INTEGER NOT NULL, source TEXT NOT NULL, photoUrl TEXT, qualityGrade TEXT NOT NULL, cachedAt INTEGER NOT NULL)")
                    database.execSQL("DROP TABLE IF EXISTS observation_cache_areas")
                    database.execSQL("CREATE TABLE observation_cache_areas (speciesId TEXT NOT NULL PRIMARY KEY, centerLat REAL NOT NULL, centerLng REAL NOT NULL, radiusKm REAL NOT NULL, cachedAt INTEGER NOT NULL)")
                }
            }
        }.toTypedArray()
    }
}

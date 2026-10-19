package com.example.data.local

import android.content.Context
import android.util.AtomicFile
import com.example.model.HotspotCell
import com.example.model.MapObservation
import com.example.model.SpeciesPhoto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

data class FieldPack(
    val savedAt: Long,
    val lat: Double,
    val lng: Double,
    val radiusKm: Double,
    val speciesId: String?,
    val allSpecies: Boolean,
    val cells: List<HotspotCell>,
    val observations: List<MapObservation>,
    val photos: Map<String, List<SpeciesPhoto>>
)

/** A deliberately saved snapshot, separate from disposable network caches. */
class FieldPackStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "prepared_area.json"))
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(FieldPack::class.java)
    @Synchronized fun read(): FieldPack? = runCatching {
        file.openRead().bufferedReader().use { adapter.fromJson(it.readText()) }
    }.getOrNull()
    @Synchronized fun write(pack: FieldPack) {
        val stream = file.startWrite()
        try {
            stream.write(adapter.toJson(pack).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}

package com.example.data.local

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.model.UserSighting
import com.example.util.SightingPolicy
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable personal backup. Includes private records and their photos, never API keys. */
class SightingBackup(private val context: Context, private val dao: FungiDao) {
    private val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        .adapter<List<UserSighting>>(Types.newParameterizedType(List::class.java, UserSighting::class.java))

    suspend fun write(uri: Uri): Int = withContext(Dispatchers.IO) {
        val records = dao.getAllUserSightings()
        val portable = records.mapIndexed { i, s -> s.copy(photoLocalPath = s.photoLocalPath?.let { "photos/$i.jpg" }) }
        val stream = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open backup destination" }
        ZipOutputStream(stream).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            val manifest = JSONObject().put("format", "myceliyums-sightings").put("version", 1)
                .put("records", org.json.JSONArray(adapter.toJson(portable)))
            zip.write(manifest.toString().toByteArray()); zip.closeEntry()
            records.forEachIndexed { i, s -> s.photoLocalPath?.let { path ->
                val photo = if (path.startsWith("/")) File(path).inputStream()
                    else requireNotNull(context.contentResolver.openInputStream(Uri.parse(path))) { "A sighting photo is missing; backup was not completed" }
                zip.putNextEntry(ZipEntry("photos/$i.jpg"))
                photo.use { it.copyTo(zip) }; zip.closeEntry()
            } }
        }
        records.size
    }

    suspend fun restore(uri: Uri): Int = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        val installed = mutableListOf<File>()
        var committed = false
        try {
            var total = 0L
            val names = mutableSetOf<String>()
            ZipInputStream(requireNotNull(context.contentResolver.openInputStream(uri))).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entry.name == "manifest.json" || entry.name.matches(Regex("photos/[0-9]+\\.jpg"))) { "Invalid backup entry" }
                    require(names.add(entry.name) && names.size <= 5001) { "Duplicate or oversized backup" }
                    val target = File(staging, entry.name).apply { parentFile!!.mkdirs() }
                    target.outputStream().use { out ->
                        val bytes = ByteArray(8192); var entrySize = 0L
                        while (true) {
                            val n = zip.read(bytes); if (n < 0) break
                            total += n; entrySize += n
                            require(total <= 200L * 1024 * 1024 && entrySize <= (if (entry.name == "manifest.json") 2L else 20L) * 1024 * 1024) { "Backup exceeds size limit" }
                            out.write(bytes, 0, n)
                        }
                    }
                }
            }
            val manifest = JSONObject(File(staging, "manifest.json").readText())
            require(manifest.getString("format") == "myceliyums-sightings" && manifest.getInt("version") == 1) { "Unsupported backup format" }
            val records = requireNotNull(adapter.fromJson(manifest.getJSONArray("records").toString()))
            require(records.size <= 5000)
            records.forEach { s ->
                require(SightingPolicy.validCoordinates(s.lat, s.lng) && s.timestamp > 0 && s.speciesId.isNotBlank()) { "Invalid sighting in backup" }
                s.photoLocalPath?.let { path -> require(path.matches(Regex("photos/[0-9]+\\.jpg")) && File(staging, path).isFile) { "Missing photo in backup" } }
            }
            val existing = dao.getAllUserSightings().map { it.backupIdentity() }.toHashSet()
            val additions = records.filter { existing.add(it.backupIdentity()) }.map { s ->
                val photoUri = s.photoLocalPath?.let { path ->
                    val target = File(context.filesDir, "voucher_photos/restored-${UUID.randomUUID()}.jpg")
                    target.parentFile!!.mkdirs(); File(staging, path).copyTo(target); installed.add(target)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target).toString()
                }
                s.copy(id = 0, photoLocalPath = photoUri)
            }
            dao.importUserSightings(additions)
            committed = true
            additions.size
        } finally {
            staging.deleteRecursively()
            if (!committed) installed.forEach { it.delete() }
        }
    }
    private fun UserSighting.backupIdentity() = listOf(speciesId, lat, lng, timestamp, notes, isPrivate)
}

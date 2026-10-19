package com.example.data.local

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.example.model.SpeciesPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Saves only explicitly selected, reusable reference images with their credit lines. */
class ReferencePhotoStore(context: Context) {
    private val directory = File(context.filesDir, "reference_photos").apply { mkdirs() }

    suspend fun prepare(photos: List<SpeciesPhoto>): List<SpeciesPhoto> = withContext(Dispatchers.IO) {
        photos.take(2).mapNotNull { photo ->
            currentCoroutineContext().ensureActive()
            try {
                if (photo.url.startsWith("file:")) return@mapNotNull photo.takeIf { File(Uri.parse(it.url).path.orEmpty()).isFile }
                require(photo.url.startsWith("https://"))
                val name = MessageDigest.getInstance("SHA-256").digest(photo.url.toByteArray())
                    .joinToString("") { "%02x".format(it) }
                val target = File(directory, "$name.jpg")
                if (!target.isFile) {
                    val bytes = client.newCall(Request.Builder().url(photo.url).build()).execute().use { response ->
                        require(response.isSuccessful) { "Photo download failed" }
                        val body = requireNotNull(response.body)
                        require(body.contentLength() <= MAX_BYTES)
                        body.byteStream().use { input ->
                            val out = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(out.size() + count <= MAX_BYTES) { "Reference photo exceeds size limit" }
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                    }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid reference image" }
                    val atomic = AtomicFile(target)
                    val stream = atomic.startWrite()
                    try { stream.write(bytes); atomic.finishWrite(stream) }
                    catch (error: Exception) { atomic.failWrite(stream); throw error }
                }
                photo.copy(url = Uri.fromFile(target).toString())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 5 * 1024 * 1024
        private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
}

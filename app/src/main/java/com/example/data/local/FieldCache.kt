package com.example.data.local

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Type
import java.security.MessageDigest

/** Bounded, versioned, atomic storage of real provider responses. Never invents a fallback. */
class FieldCache(context: Context) {
    private val root = File(context.filesDir, "field_data_v1")
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private fun file(kind: String, key: String): File {
        require(kind.matches(Regex("[a-z_]+")))
        val name = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(root, kind), "$name.json")
    }
    @Synchronized fun <T> read(kind: String, key: String, type: Type, maxAgeMs: Long): T? = runCatching {
        val obj = JSONObject(file(kind, key).readText())
        val age = System.currentTimeMillis() - obj.getLong("savedAt")
        if (age !in 0..maxAgeMs) return null
        moshi.adapter<T>(type).fromJson(obj.getString("data"))
    }.getOrNull()

    @Synchronized fun <T> write(kind: String, key: String, type: Type, value: T) {
        val target = file(kind, key)
        target.parentFile!!.mkdirs()
        val atomic = AtomicFile(target)
        val bytes = JSONObject().put("savedAt", System.currentTimeMillis())
            .put("data", moshi.adapter<T>(type).toJson(value)).toString().toByteArray()
        val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
        target.parentFile!!.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?.drop(24)?.forEach { it.delete() }
    }
    @Synchronized fun clear() { root.deleteRecursively() }
}

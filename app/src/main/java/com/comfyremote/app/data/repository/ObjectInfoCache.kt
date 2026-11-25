package com.comfyremote.app.data.repository

import com.comfyremote.app.ComfyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Memory + disk cache of /object_info, keyed by server URL and invalidated
 * when the ComfyUI version changes. Callers never see the cache file.
 */
class ObjectInfoCache(private val cacheDir: File) {

    private data class Cached(val server: String, val version: String, val loadedAt: Long, val data: JSONObject)
    @Volatile private var cached: Cached? = null
    @Volatile private var invalidated = false
    private val mutex = Mutex()

    suspend fun get(a: ComfyApi, serverVersion: String, force: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                val cacheFile = File(cacheDir, "object_info_${a.base.hashCode()}.json")
                if (!force && !invalidated) {
                    cached?.takeIf { it.server == a.base && it.version == serverVersion && isFresh(it.loadedAt, now) }
                        ?.let { return@withLock it.data }
                    if (cacheFile.isFile) {
                        val saved = runCatching { JSONObject(cacheFile.readText()) }.getOrNull()
                        val time = saved?.optLong("loadedAt") ?: 0L
                        if (saved?.optString("server") == a.base &&
                            saved.optString("version") == serverVersion && isFresh(time, now)) {
                            saved.optJSONObject("data")?.let { data ->
                                cached = Cached(a.base, serverVersion, time, data)
                                return@withLock data
                            }
                        }
                    }
                }
                val fresh = a.objectInfo()
                val loadedAt = System.currentTimeMillis()
                runCatching {
                    cacheFile.writeText(JSONObject().put("server", a.base).put("version", serverVersion)
                        .put("loadedAt", loadedAt).put("data", fresh).toString())
                }
                cached = Cached(a.base, serverVersion, loadedAt, fresh)
                invalidated = false
                fresh
            }
        }

    fun invalidate() {
        cached = null
        invalidated = true
    }

    private fun isFresh(time: Long, now: Long): Boolean = now - time in 0..5 * 60_000L
}

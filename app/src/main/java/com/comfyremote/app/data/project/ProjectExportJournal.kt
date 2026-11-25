package com.comfyremote.app.data.project

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONObject

/**
 * Tiny durable crash marker, not a queue: a killed export becomes Failed on
 * next launch and must be retried explicitly. No paid work is ever resumed.
 */
internal class ProjectExportJournal(private val file: File) {
    data class Session(val projectId: String, val sessionId: String?)

    fun interrupted(): Session? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val projectId = (json.opt("projectId") as? String)?.takeIf(String::isNotBlank)
            if (projectId == null || json.optString("state") != "running") null
            else Session(projectId, (json.opt("sessionId") as? String)?.takeIf(String::isNotBlank))
        }.getOrNull()
    }

    fun markRunning(projectId: String, sessionId: String) {
        require(projectId.isNotBlank())
        require(sessionId.isNotBlank())
        file.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(JSONObject().put("schema", 1).put("state", "running")
                    .put("projectId", projectId).put("sessionId", sessionId).toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) throw IOException("Cannot save export recovery marker")
        } finally {
            tmp.delete()
        }
    }

    fun clear() {
        if (file.exists() && !file.delete()) throw IOException("Cannot clear export recovery marker")
    }
}

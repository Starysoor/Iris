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
    fun interruptedProjectId(): String? {
        if (!file.isFile) return null
        return runCatching {
            val json = JSONObject(file.readText())
            json.optString("projectId").takeIf { it.isNotBlank() &&
                json.optString("state") == "running" }
        }.getOrNull()
    }

    fun markRunning(projectId: String) {
        require(projectId.isNotBlank())
        file.parentFile?.mkdirs()
        val tmp = File(file.absolutePath + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(JSONObject().put("schema", 1).put("state", "running")
                    .put("projectId", projectId).toString().toByteArray(Charsets.UTF_8))
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

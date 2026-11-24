package com.comfyremote.app.data.repository

import java.util.Base64

/** A project can retain a local workflow without accidentally selecting it on another server. */
data class LocalWorkflowReference(val serverUrl: String, val workflowPath: String) {
    fun key(): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(serverUrl.encodeToByteArray()) + ":" + workflowPath

    companion object {
        private const val PREFIX = "comfyui:"

        fun parse(key: String): LocalWorkflowReference? {
            if (!key.startsWith(PREFIX)) return null
            val value = key.removePrefix(PREFIX)
            val server = value.substringBefore(':', "").takeIf(String::isNotBlank) ?: return null
            val path = value.substringAfter(':', "").takeIf(String::isNotBlank) ?: return null
            val url = runCatching { Base64.getUrlDecoder().decode(server).decodeToString() }.getOrNull() ?: return null
            if (!url.startsWith("https://") && !url.startsWith("http://")) return null
            return LocalWorkflowReference(url, path)
        }
    }
}

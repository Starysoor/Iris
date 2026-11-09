package com.comfyremote.app.data.cloud

import android.content.Context
import android.net.Uri
import com.comfyremote.app.data.media.ImagePreprocessor
import com.comfyremote.app.data.media.BoundedMediaInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64

/**
 * Durable copy of images selected for a hosted workflow. AutoDL accepts
 * `data:image/...;base64,...` values, so the image never needs to be uploaded to
 * a ComfyUI input folder. Files live in filesDir so history reuse still works
 * after a restart.
 */
class CloudInputStore(private val context: Context) {
    private val dir = File(context.filesDir, "cloud_inputs").apply { mkdirs() }
    private val audioDir = File(context.filesDir, "cloud_audio").apply { mkdirs() }

    /** Copies a gallery/camera Uri into app storage and returns `local:<path>`. */
    suspend fun saveFromUri(uri: Uri, maxDimension: Int = 1024): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            BoundedMediaInput.read(it, BoundedMediaInput.MAX_IMAGE_BYTES)
        }
            ?: throw IllegalStateException("读取图片失败")
        val mime = context.contentResolver.getType(uri)
        saveBytes(bytes, mime, maxDimension)
    }

    /** Copies a generated result (image) and returns `local:<path>`. */
    suspend fun saveFromFile(path: String, maxDimension: Int = 1024): String = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) throw IllegalStateException("图片文件不存在")
        if (file.length() > BoundedMediaInput.MAX_IMAGE_BYTES) {
            throw IllegalArgumentException("Image source exceeds 32 MiB")
        }
        saveBytes(file.readBytes(), mimeForExt(file.extension), maxDimension)
    }

    /** Copies an audio file used by a hosted TTS/lip-sync workflow. */
    suspend fun saveAudioFromUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)
        val ext = extensionForAudio(mime, uri.lastPathSegment)
        val target = File(audioDir, "cloud_audio_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext")
        val tmp = File(audioDir, target.name + ".tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("读取音频失败")
            input.use { source ->
                FileOutputStream(tmp).use { output ->
                    BoundedMediaInput.copy(source, output, BoundedMediaInput.MAX_AUDIO_BYTES)
                    output.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot store audio safely")
            PREFIX + target.absolutePath
        } finally {
            tmp.delete()
        }
    }

    /**
     * Copy an already imported project audio file into the hosted input cache.
     * Never loads a large audio file wholly into heap; never mutates the source.
     */
    suspend fun saveAudioFromFile(path: String): String = withContext(Dispatchers.IO) {
        val source = File(path)
        if (!source.isFile || !source.canRead() || source.length() <= 0L) {
            throw IllegalStateException("Project audio source is missing or unreadable")
        }
        val extension = source.extension.lowercase()
        if (extension !in AUDIO_EXTENSIONS) {
            throw IllegalArgumentException("Unsupported project audio format")
        }
        if (source.length() > BoundedMediaInput.MAX_AUDIO_BYTES) {
            throw IllegalArgumentException("Project audio exceeds size limit")
        }
        val target = File(audioDir,
            "project_audio_${System.currentTimeMillis()}_${(1000..9999).random()}.$extension")
        val tmp = File(audioDir, target.name + ".tmp")
        try {
            source.inputStream().use { input ->
                FileOutputStream(tmp).use { output ->
                    BoundedMediaInput.copy(input, output, BoundedMediaInput.MAX_AUDIO_BYTES)
                    output.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot store project audio safely")
            PREFIX + target.absolutePath
        } finally {
            tmp.delete()
        }
    }

    fun isAudioValue(value: String): Boolean {
        val path = localPath(value) ?: return false
        return File(path).extension.lowercase() in AUDIO_EXTENSIONS
    }

    suspend fun saveBytes(bytes: ByteArray, mime: String?, maxDimension: Int = 1024): String =
        withContext(Dispatchers.IO) {
            if (bytes.size > BoundedMediaInput.MAX_IMAGE_BYTES) {
                throw IllegalArgumentException("Image source exceeds 32 MiB")
            }
            // Always normalize to JPEG: phone galleries may return HEIC/AVIF/GIF or a
            // null MIME, and hosted workflows only accept JPEG/PNG/WebP. Re-encoding
            // also keeps the base64 request small.
            val (prepared, ext) = ImagePreprocessor.prepare(bytes, mime, maxDimension, 84, forceJpeg = true)
            val name = "cloud_${System.currentTimeMillis()}_${(1000..9999).random()}.$ext"
            val target = File(dir, name)
            target.writeBytes(prepared)
            PREFIX + target.absolutePath
        }

    /** Reads a `local:` value; null for remote URLs, data URLs, or missing files. */
    fun readBytes(value: String): ByteArray? {
        val path = localPath(value) ?: return null
        val file = File(path)
        return if (file.isFile && file.length() <= BoundedMediaInput.MAX_AUDIO_BYTES) {
            runCatching { file.readBytes() }.getOrNull()
        } else null
    }

    fun localPath(value: String): String? =
        value.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }

    fun mimeType(value: String): String {
        val path = localPath(value) ?: return "image/jpeg"
        return mimeForExt(File(path).extension)
    }

    /** Converts a local image to a data URL; passes remote/data URLs through. */
    fun toRequestValue(value: String): String? {
        if (value.isBlank()) return null
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
            return value
        }
        val path = localPath(value) ?: return null
        val file = File(path)
        if (!file.isFile || file.length() > BoundedMediaInput.MAX_AUDIO_BYTES) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "data:${mimeForExt(file.extension)};base64,$encoded"
    }

    /** Deletes cached inputs except files referenced by queued tasks, drafts or history. */
    fun clearAll(protectedValues: Set<String> = emptySet()) {
        val keep = protectedValues
            .mapNotNull { localPath(it)?.let { path -> File(path).absolutePath } }
            .toSet()
        listOf(dir, audioDir).forEach { folder ->
            folder.listFiles()?.forEach { file ->
                if (file.absolutePath !in keep) runCatching { file.delete() }
            }
        }
    }

    companion object {
        const val PREFIX = "local:"

        private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "ogg", "flac", "aac")

        fun mimeForExt(ext: String): String = when (ext.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "wav" -> "audio/wav"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            else -> "image/jpeg"
        }

        private fun extensionForAudio(mime: String?, name: String?): String {
            val fromName = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            if (fromName in AUDIO_EXTENSIONS) return fromName
            return when (mime?.lowercase()) {
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/x-m4a" -> "m4a"
                "audio/ogg" -> "ogg"
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/aac" -> "aac"
                else -> "wav"
            }
        }
    }
}

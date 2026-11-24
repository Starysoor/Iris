package com.comfyremote.app.data.cloud

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.comfyremote.app.FieldKind
import com.comfyremote.app.data.media.ImagePreprocessor
import com.comfyremote.app.data.media.BoundedMediaInput
import com.comfyremote.app.data.media.MediaTypeResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import java.util.UUID

/**
 * Durable copy of images selected for a hosted workflow. AutoDL accepts
 * `data:image/...;base64,...` values, so the image never needs to be uploaded to
 * a ComfyUI input folder. Files live in filesDir so history reuse still works
 * after a restart.
 */
class CloudInputStore(private val context: Context) {
    private val dir = File(context.filesDir, "cloud_inputs").apply { mkdirs() }
    private val audioDir = File(context.filesDir, "cloud_audio").apply { mkdirs() }
    private val videoDir = File(context.filesDir, "cloud_video").apply { mkdirs() }

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

    /** Copies a timed-media file for a hosted workflow or stages a local ComfyUI upload. */
    suspend fun copyMedia(
        uri: Uri,
        kind: FieldKind,
        acceptedTypes: List<String> = emptyList(),
        forCloud: Boolean,
    ): File = withContext(Dispatchers.IO) {
        require(kind == FieldKind.AUDIO || kind == FieldKind.VIDEO)
        val resolver = context.contentResolver
        val name = if (uri.scheme == "content") {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } else uri.lastPathSegment
        val ext = extensionForMedia(resolver.getType(uri), name ?: uri.lastPathSegment, kind)
        val mime = mimeForExt(ext)
        if (acceptedTypes.isNotEmpty() && acceptedTypes.none {
                it.equals(mime, true) || it.equals("${kind.mediaType}/*", true)
            }) throw IOException("Media format $mime is unsupported. Accepted: ${acceptedTypes.joinToString()}")
        val folder = when {
            !forCloud -> File(context.cacheDir, "comfy_uploads").apply { mkdirs() }
            kind == FieldKind.VIDEO -> videoDir
            else -> audioDir
        }
        val limit = when {
            !forCloud && kind == FieldKind.VIDEO -> 1024L * 1024 * 1024
            !forCloud -> 512L * 1024 * 1024
            kind == FieldKind.VIDEO -> BoundedMediaInput.MAX_INLINE_VIDEO_BYTES
            else -> BoundedMediaInput.MAX_AUDIO_BYTES
        }
        val target = File(folder, "iris_${kind.mediaType}_${UUID.randomUUID()}.$ext")
        val tmp = File(folder, target.name + ".tmp")
        val operation = currentCoroutineContext()
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the media file")
            input.use { source ->
                FileOutputStream(tmp).use { output ->
                    val bytes = BoundedMediaInput.copy(source, output, limit) { operation.ensureActive() }
                    if (bytes == 0L) throw IOException("The media file is empty")
                    output.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot store the media file safely")
            target
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
        val limit = if (MediaTypeResolver.isVideo(file.name)) BoundedMediaInput.MAX_INLINE_VIDEO_BYTES
            else BoundedMediaInput.MAX_AUDIO_BYTES
        if (!file.isFile || file.length() <= 0L || file.length() > limit) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "data:${mimeForExt(file.extension)};base64,$encoded"
    }

    /** Deletes cached inputs except files referenced by queued tasks, drafts or history. */
    fun clearAll(protectedValues: Set<String> = emptySet()) {
        val keep = protectedValues
            .mapNotNull { localPath(it)?.let { path -> File(path).absolutePath } }
            .toSet()
        listOf(dir, audioDir, videoDir).forEach { folder ->
            folder.listFiles()?.forEach { file ->
                if (file.absolutePath !in keep) runCatching { file.delete() }
            }
        }
    }

    companion object {
        const val PREFIX = "local:"

        private val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "ogg", "flac", "aac")

        fun mimeForExt(ext: String): String =
            MediaTypeResolver.mimeType("file.$ext").takeUnless { it == "image/*" } ?: "image/jpeg"

        private fun extensionForMedia(mime: String?, name: String?, kind: FieldKind): String {
            val fromMime = when (mime?.substringBefore(';')?.lowercase()) {
                "audio/wav", "audio/wave", "audio/x-wav" -> "wav"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/x-m4a" -> "m4a"
                "audio/ogg", "application/ogg" -> "ogg"
                "audio/flac", "audio/x-flac" -> "flac"
                "audio/aac" -> "aac"
                "video/mp4", "video/x-m4v" -> "mp4"
                "video/webm" -> "webm"
                "video/quicktime" -> "mov"
                "video/x-matroska" -> "mkv"
                "video/x-msvideo" -> "avi"
                "video/3gpp" -> "3gp"
                else -> null
            }
            val ext = fromMime ?: name?.substringAfterLast('.', "")?.lowercase()
            if (ext != null && mimeForExt(ext).startsWith("${kind.mediaType}/")) return ext
            throw IOException("Could not identify a supported ${kind.mediaType} file")
        }
    }
}

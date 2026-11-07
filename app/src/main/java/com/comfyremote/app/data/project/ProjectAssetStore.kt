package com.comfyremote.app.data.project

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.comfyremote.app.data.media.BoundedMediaInput
import com.comfyremote.app.data.media.ImagePreprocessor
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Imports project assets of every supported media kind into app-owned storage
 * (filesDir/storyboards/assets, the media root the project GC walks).
 *
 * Images keep the cloud-friendly normalization (bounded read + JPEG re-encode
 * at 1536px); video/audio/subtitle files are copied verbatim after the same
 * per-kind streaming limits so untrusted sources cannot exhaust memory.
 */
class ProjectAssetStore private constructor(
    private val context: Context?,
    private val dir: File,
) {
    constructor(context: Context) : this(context, File(context.filesDir, "storyboards/assets"))

    /** File-injected constructor for JVM tests (file imports only). */
    internal constructor(dir: File) : this(null, dir)

    /** Test helper: every file this store has written so far. */
    internal fun listImportedFiles(): List<File> = dir.listFiles()?.filter { it.isFile }.orEmpty()

    suspend fun importUri(
        uri: Uri,
        role: ReferenceRole,
        label: String = "",
        tags: List<String> = emptyList(),
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): ProjectAsset = withContext(Dispatchers.IO) {
        val resolver = context?.contentResolver
            ?: throw IllegalStateException("ProjectAssetStore requires a Context for content imports")
        val mime = resolver.getType(uri)
        val name = displayName(resolver, uri)
        val expectedSize = displaySize(resolver, uri)
        when (val kind = resolveAssetKind(mime, name)) {
            AssetKind.IMAGE -> {
                val bytes = resolver.openInputStream(uri)?.use {
                    BoundedMediaInput.read(it, BoundedMediaInput.MAX_IMAGE_BYTES)
                } ?: throw IllegalStateException("Cannot read image")
                onProgress(bytes.size.toLong(), expectedSize)
                saveImage(bytes, mime, role, label, tags)
            }
            else -> {
                val ext = name?.extension()
                    ?: mime?.let(::extForMime)
                    ?: "bin"
                val limit = limitFor(kind)
                if (expectedSize != null && expectedSize > limit) {
                    throw IllegalArgumentException("Media source exceeds ${limit / (1024 * 1024)} MiB")
                }
                ensureFreeSpace(expectedSize)
                resolver.openInputStream(uri)?.use { input ->
                    copyBounded(input, ext, role, label, tags, kind) { copied ->
                        onProgress(copied, expectedSize)
                    }
                } ?: throw IllegalStateException("Cannot read media")
            }
        }
    }

    suspend fun importFile(
        path: String,
        role: ReferenceRole,
        label: String = "",
        tags: List<String> = emptyList(),
    ): ProjectAsset = withContext(Dispatchers.IO) {
        val source = File(path)
        if (!source.isFile) throw IllegalStateException("Media file does not exist")
        val kind = resolveAssetKind(null, source.name)
        val limit = limitFor(kind)
        if (source.length() > limit) {
            throw IllegalArgumentException("Media source exceeds ${limit / (1024 * 1024)} MiB")
        }
        ensureFreeSpace(source.length())
        // Stream long videos; never load their entire contents into the heap.
        when (kind) {
            AssetKind.IMAGE -> saveImage(source.readBytes(), null, role, label, tags)
            else -> source.inputStream().use {
                copyBounded(it, source.extension.ifBlank { "bin" }, role, label, tags, kind)
            }
        }
    }

    private suspend fun saveImage(
        bytes: ByteArray,
        mime: String?,
        role: ReferenceRole,
        label: String,
        tags: List<String>,
    ): ProjectAsset {
        val (prepared, ext) = withContext(Dispatchers.IO) {
            ImagePreprocessor.prepare(bytes, mime, 1536, 88, forceJpeg = true)
        }
        val file = newAssetFile(ext)
        withContext(Dispatchers.IO) { file.writeBytes(prepared) }
        return asset(file, role, label, tags, AssetKind.IMAGE)
    }

    /** Streams a bounded copy through a tmp file, then atomically renames it. */
    private suspend fun copyBounded(
        input: java.io.InputStream,
        ext: String,
        role: ReferenceRole,
        label: String,
        tags: List<String>,
        kind: AssetKind,
        onProgress: (Long) -> Unit = {},
    ): ProjectAsset {
        val file = newAssetFile(ext)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                FileOutputStream(tmp).use { out ->
                    BoundedMediaInput.copy(input, out, limitFor(kind)) { copied ->
                        context.ensureActive()
                        onProgress(copied)
                    }
                    out.fd.sync()
                }
                context.ensureActive()
                if (!tmp.renameTo(file) || !file.isFile) {
                    throw IOException("Cannot save media asset")
                }
            }
        } catch (error: Exception) {
            tmp.delete()
            throw error
        }
        return asset(file, role, label, tags, kind)
    }

    /** Preflight only when the provider supplies a size; streaming also handles unknown sizes. */
    private fun ensureFreeSpace(expectedBytes: Long?) {
        if (expectedBytes == null || expectedBytes <= 0L) return
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create media directory")
        val available = dir.usableSpace
        if (available > 0 && expectedBytes > (available - 8L * 1024 * 1024).coerceAtLeast(0L)) {
            throw IOException("Not enough free storage for media import")
        }
    }

    private fun newAssetFile(ext: String): File {
        dir.mkdirs()
        val safeExt = ext.lowercase(Locale.US).takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        return File(dir, "asset_${UUID.randomUUID()}.$safeExt")
    }

    private fun asset(
        file: File,
        role: ReferenceRole,
        label: String,
        tags: List<String>,
        kind: AssetKind,
    ) = ProjectAsset(
        role = role,
        path = file.absolutePath,
        label = label.ifBlank { role.name },
        kind = kind,
        tags = tags,
    )

    private fun displaySize(resolver: ContentResolver, uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it >= 0 } else null
        }
    }.getOrNull()

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun String.extension(): String = substringAfterLast('.', "").takeIf { it.isNotBlank() }.orEmpty()

    companion object {
        internal const val MAX_VIDEO_BYTES = 1024L * 1024 * 1024
        internal const val MAX_PROJECT_AUDIO_BYTES = 512L * 1024 * 1024
        internal const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024

        internal fun limitFor(kind: AssetKind): Long = when (kind) {
            AssetKind.IMAGE -> BoundedMediaInput.MAX_IMAGE_BYTES
            AssetKind.VIDEO -> MAX_VIDEO_BYTES
            AssetKind.AUDIO -> MAX_PROJECT_AUDIO_BYTES
            AssetKind.SUBTITLE -> MAX_SUBTITLE_BYTES
        }

        /**
         * Explicit video/audio/subtitle mimes win over extensions; otherwise the
         * file name decides (unknown extensions stay images, matching AssetKind).
         */
        internal fun resolveAssetKind(mime: String?, fileName: String?): AssetKind {
            when (mime?.lowercase(Locale.US)) {
                in VIDEO_MIMES -> return AssetKind.VIDEO
                in AUDIO_MIMES -> return AssetKind.AUDIO
                "text/vtt", "application/x-subrip", "text/plain" -> if (looksLikeSubtitle(fileName)) {
                    return AssetKind.SUBTITLE
                }
            }
            val byName = fileName?.let { AssetKind.fromPath(it) } ?: AssetKind.IMAGE
            if (byName != AssetKind.IMAGE) return byName
            // text/plain with no subtitle extension stays an image candidate so
            // ImagePreprocessor produces the descriptive decode error.
            return AssetKind.IMAGE
        }

        private val VIDEO_MIMES = setOf(
            "video/mp4", "video/webm", "video/quicktime", "video/x-matroska", "video/avi", "video/x-msvideo",
        )
        private val AUDIO_MIMES = setOf(
            "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav", "audio/aac", "audio/ogg",
            "audio/flac", "audio/x-flac", "audio/opus",
        )

        private fun looksLikeSubtitle(fileName: String?): Boolean =
            fileName != null && AssetKind.fromPath(fileName) == AssetKind.SUBTITLE

        private fun extForMime(mime: String): String? = when (mime.lowercase(Locale.US)) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            "video/x-matroska" -> "mkv"
            "video/avi", "video/x-msvideo" -> "avi"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/aac" -> "aac"
            "audio/ogg" -> "ogg"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/opus" -> "opus"
            "text/vtt" -> "vtt"
            "application/x-subrip" -> "srt"
            else -> null
        }
    }
}

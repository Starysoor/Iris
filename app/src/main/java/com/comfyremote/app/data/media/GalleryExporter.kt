package com.comfyremote.app.data.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.os.Environment
import android.provider.MediaStore
import com.comfyremote.app.R
import java.io.File

/** Saves generated images into the phone gallery (Pictures/ComfyRemote). */
class GalleryExporter(private val context: Context) {
    /** Returns the public Uri of the saved image. */
    fun savePng(bitmap: Bitmap): android.net.Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "iris_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Iris")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create media file")
        return try {
            val output = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open media output")
            output.use {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                    throw IllegalStateException("Could not encode image")
                }
            }
            uri
        } catch (t: Throwable) {
            deleteQuietly(uri)
            throw t
        }
    }

    /** Copies a generated image/video file into the phone gallery. */
    fun saveMediaFile(path: String, onProgress: (Float) -> Unit = {}): android.net.Uri {
        val file = File(path)
        if (!file.isFile) throw IllegalStateException("Media file does not exist")
        val totalBytes = file.length()
        if (totalBytes <= 0L) throw IllegalStateException("Media file is empty")
        val ext = MediaTypeResolver.extension(path)
        val kind = MediaTypeResolver.kind(path)
        val mime = MediaTypeResolver.mimeType(path)
        val collection = when (kind) {
            MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            MediaKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "iris_${System.currentTimeMillis()}.$ext")
            // Keep large, partially copied movies and pictures invisible until success.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                when (kind) {
                    MediaKind.VIDEO -> Environment.DIRECTORY_MOVIES
                    MediaKind.AUDIO -> Environment.DIRECTORY_MUSIC
                    MediaKind.IMAGE -> Environment.DIRECTORY_PICTURES
                } + "/Iris",
            )
        }
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create media file")
        return try {
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Could not open media output")
            // The previous InputStream.copyTo default was just 8 KiB. Large videos
            // use a 256 KiB buffer, streaming directly from their local file.
            out.use { target ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    var lastTick = 0L
                    var lastReported = -1
                    onProgress(0f)
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        target.write(buffer, 0, n)
                        copied += n
                        val percent = ((copied * 100L) / totalBytes).toInt().coerceIn(0, 100)
                        val now = SystemClock.elapsedRealtime()
                        if (percent != lastReported && (percent == 100 || now - lastTick >= 120)) {
                            lastReported = percent
                            lastTick = now
                            onProgress(percent / 100f)
                        }
                    }
                    if (copied != totalBytes) throw IllegalStateException("Media changed while saving")
                    target.flush()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val count = context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null,
                )
                if (count <= 0) throw IllegalStateException("Could not publish media file")
            }
            onProgress(1f)
            uri
        } catch (t: Throwable) {
            deleteQuietly(uri)
            throw t
        }
    }

    private fun deleteQuietly(uri: android.net.Uri) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }

    fun savedMessage(): String = context.getString(R.string.gallery_saved)
    fun failMessage(error: Throwable): String = context.getString(R.string.gallery_save_failed, error.message ?: "error")

    companion object {
        fun shareFile(context: Context, path: String): File? {
            val file = File(path)
            return if (file.isFile) file else null
        }
    }
}

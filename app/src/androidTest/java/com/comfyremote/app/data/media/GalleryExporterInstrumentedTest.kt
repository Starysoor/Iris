package com.comfyremote.app.data.media

import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real MediaStore writes on API 29/34 emulators: stream sizable media without
 * loading it into memory, publish only after final byte, then verify all bytes.
 * Uses local QA fixtures, never a cloud endpoint or paid task.
 */
@RunWith(AndroidJUnit4::class)
class GalleryExporterInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun publishesLargeVideoAndPreservesExactBytes() {
        // App was populated with a small *valid* MP4 by qa_audio_mix_fixture.sh.
        val original = File(context.filesDir, "qa-source.mp4")
        assertTrue("Media3 QA MP4 fixture absent", original.isFile && original.length() > 0)
        roundTrip(".mp4", original, 48)
    }

    @Test fun publishesLargeImageAndRejectsMissingSource() {
        val original = File(context.cacheDir, "qa-gallery-small.png")
        // Valid 1x1 PNG header+image, padded for repeated streaming I/O.
        android.graphics.Bitmap.createBitmap(
            2, 2, android.graphics.Bitmap.Config.ARGB_8888
        ).useBitmap { bmp ->
            original.outputStream().use { out ->
                assertTrue(bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
            }
        }
        try {
            roundTrip(".png", original, 16)
            val missing = File(context.cacheDir, "qa-no-such-file.mp4")
            missing.delete()
            assertFalse(missing.isFile)
            val failure = runCatching { GalleryExporter(context).saveMediaFile(missing.absolutePath) }
            assertTrue("Missing source must fail", failure.isFailure)
        } finally {
            original.delete()
        }
    }

    private fun roundTrip(ext: String, prefix: File, extraMiB: Int) {
        val source = File(context.cacheDir, "qa-gallery-large" + ext)
        var published: android.net.Uri? = null
        try {
            source.outputStream().use { out ->
                prefix.inputStream().use { it.copyTo(out) }
                val chunk = ByteArray(256 * 1024) { i -> ((i * 17) and 0xff).toByte() }
                repeat(extraMiB * 4) { out.write(chunk) }
            }
            val progress = mutableListOf<Float>()
            published = GalleryExporter(context).saveMediaFile(source.absolutePath) { fraction ->
                progress.add(fraction)
            }
            assertTrue("No progress callbacks", progress.size > 1)
            assertEquals(0f, progress.first(), 0f)
            assertEquals(1f, progress.last(), 0f)
            assertTrue("Out-of-order save progress", progress.zipWithNext().all { (a,b) -> a <= b })
            val uri = published ?: throw AssertionError("No MediaStore URI")
            context.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null
            ).use { cursor ->
                assertTrue("Saved media invisible", cursor != null && cursor.moveToFirst())
                assertEquals("MediaStore item remains pending", 0, cursor!!.getInt(0))
            }
            val actualHash = context.contentResolver.openInputStream(uri)!!.use { digest(it) }
            val sourceHash = source.inputStream().use { digest(it) }
            assertTrue("Saved file changed", sourceHash.contentEquals(actualHash))
        } finally {
            published?.let { context.contentResolver.delete(it, null, null) }
            source.delete()
        }
    }

    private fun digest(input: java.io.InputStream): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        while (true) {
            val bytes = input.read(buffer)
            if (bytes == -1) break
            sha.update(buffer, 0, bytes)
        }
        return sha.digest()
    }
}

/** Bitmap implements AutoCloseable-like cleanup explicitly, not via use(). */
private inline fun android.graphics.Bitmap.useBitmap(block: (android.graphics.Bitmap) -> Unit) {
    try { block(this) } finally { recycle() }
}

package com.comfyremote.app.data.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlin.math.abs
import kotlin.math.max

/**
 * Frame-strip tooling for the continuity assistant (#57 P3): bounded thumbnails, evenly spaced
 * frames from one clip plus a pure pixel-distance metric so two frames can be
 * compared cheaply. All Android calls stay in [extractFrames]; the comparison
 * is JVM-testable.
 */
object FrameStrip {

    /**
     * Extracts up to [maxFrames] evenly spaced frames from [path]. One frame
     * per second when the clip is short, evenly divided otherwise; a null
     * result list means the file could not be opened at all.
     */
    fun extractFrames(path: String, maxFrames: Int = 8): List<Bitmap>? {
        if (maxFrames <= 0) return emptyList()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            if (durationMs <= 0L) return null
            val count = max(1, minOf(maxFrames, (durationMs / 1000L).toInt()))
            val frames = ArrayList<Bitmap>(count)
            for (i in 0 until count) {
                // Evenly spread across (0, duration]; the first sample sits at
                // half a step so 1-frame strips show the middle, not black.
                val atUs = (durationMs * (i + 0.5) / count * 1000).toLong()
                    .coerceIn(0L, (durationMs - 1).coerceAtLeast(0L) * 1000)
                // Decode a thumbnail directly; full 1080p/4K Bitmaps multiply memory usage
                // by clips * frames and can OOM a project with only a dozen shots.
                retriever.getScaledFrameAtTime(
                    atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT,
                )?.let(frames::add)
            }
            frames
        } catch (error: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Downsamples both bitmaps to [grid]x[grid] average colors and returns the
     * mean per-channel distance in 0..255. Pure and JVM-testable: callers pass
     * the averaged grids ([grid]*[grid] ARGB ints, alpha ignored).
     */
    fun gridDistance(a: IntArray, b: IntArray, grid: Int = GRID): Double {
        if (a.size != grid * grid || b.size != grid * grid) return 255.0
        var total = 0.0
        for (i in a.indices) {
            total += abs(((a[i] shr 16) and 0xFF) - ((b[i] shr 16) and 0xFF))
            total += abs(((a[i] shr 8) and 0xFF) - ((b[i] shr 8) and 0xFF))
            total += abs((a[i] and 0xFF) - (b[i] and 0xFF))
        }
        return total / (a.size * 3)
    }

    /** Average-color grid of [bitmap]: [GRID]x[GRID] ARGB ints (alpha 0xFF). */
    fun averageGrid(bitmap: Bitmap, grid: Int = GRID): IntArray {
        val out = IntArray(grid * grid)
        val cellW = max(1, bitmap.width / grid)
        val cellH = max(1, bitmap.height / grid)
        for (gy in 0 until grid) {
            for (gx in 0 until grid) {
                val x0 = gx * cellW
                val y0 = gy * cellH
                var r = 0L
                var g = 0L
                var b = 0L
                var n = 0L
                val stepX = max(1, cellW / 8)
                val stepY = max(1, cellH / 8)
                var y = y0
                while (y < y0 + cellH && y < bitmap.height) {
                    var x = x0
                    while (x < x0 + cellW && x < bitmap.width) {
                        val c = bitmap.getPixel(x, y)
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                        n++
                        x += stepX
                    }
                    y += stepY
                }
                out[gy * grid + gx] = if (n == 0L) 0xFF000000.toInt() else {
                    0xFF000000.toInt() or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
                }
            }
        }
        return out
    }

    const val THUMBNAIL_WIDTH = 320
    const val THUMBNAIL_HEIGHT = 320
    const val GRID = 8
}

package com.comfyremote.app.data.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * True decoded PCM amplitude preview. Work is off the UI thread, memory stays
 * O(bar count), and unsupported media returns null rather than fake waveforms.
 */
object AudioWaveformExtractor {
    private const val BARS = 48
    private const val MAX_ANALYZE_MS = 180_000L
    private val lock = Any()
    private val cache = object : LinkedHashMap<String, List<Float>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Float>>?) =
            size > 64
    }

    suspend fun sample(path: String, sourceInMs: Long, sourceOutMs: Long): List<Float>? =
        withContext(Dispatchers.IO) {
            if (sourceOutMs <= sourceInMs || sourceOutMs - sourceInMs > MAX_ANALYZE_MS) {
                return@withContext null
            }
            val file = File(path)
            if (!file.isFile) return@withContext null
            val key = file.absolutePath + ":" + file.lastModified() + ":" + file.length() +
                ":" + sourceInMs + ":" + sourceOutMs
            synchronized(lock) { cache[key] }?.let { return@withContext it }
            val result = try {
                decode(path, sourceInMs * 1000L, sourceOutMs * 1000L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (result != null) synchronized(lock) { cache[key] = result }
            result
        }

    private suspend fun decode(path: String, startUs: Long, endUs: Long): List<Float>? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            val peaks = WaveformBuckets(startUs, endUs, BARS)
            val bufferInfo = MediaCodec.BufferInfo()
            var inputEnded = false
            var idle = 0
            var loops = 0
            while (loops++ < 50_000 && idle < 200) {
                currentCoroutineContext().ensureActive()
                var madeProgress = false
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = codec.getInputBuffer(index) ?: return null
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0 || extractor.sampleTime > endUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        madeProgress = true
                    }
                }
                val index = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                if (index >= 0) {
                    madeProgress = true
                    val outputFormat = codec.outputFormat
                    val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else AudioFormat.ENCODING_PCM_16BIT
                    if (encoding != AudioFormat.ENCODING_PCM_16BIT) return null
                    val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (sampleRate <= 0 || channels <= 0) return null
                    val pcm = codec.getOutputBuffer(index)
                    if (pcm != null && bufferInfo.size > 0) {
                        pcm.order(ByteOrder.LITTLE_ENDIAN)
                        val sampleBytes = channels * 2
                        val frames = bufferInfo.size / sampleBytes
                        // Read one sample per 16 frames. Each output chunk retains
                        // real presentation timing and will map across the clip range.
                        var frame = 0
                        while (frame < frames) {
                            val position = bufferInfo.offset + frame * sampleBytes
                            if (position + 2 > pcm.limit()) break
                            val amplitude = abs(pcm.getShort(position).toInt()) / 32768f
                            val t = bufferInfo.presentationTimeUs + frame * 1_000_000L / sampleRate
                            peaks.add(t, amplitude)
                            frame += 16
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (bufferInfo.presentationTimeUs > endUs) break
                }
                idle = if (madeProgress) 0 else idle + 1
            }
            return peaks.values().takeIf { it.any { amplitude -> amplitude > 0f } }
        } finally {
            if (started) runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
}

/** Pure deterministic histogram logic; absent/zero-peak waveform is not guessed. */
internal class WaveformBuckets(
    private val startUs: Long,
    private val endUs: Long,
    count: Int,
) {
    private val peaks = FloatArray(count.coerceAtLeast(1))
    fun add(timeUs: Long, amplitude: Float) {
        if (timeUs < startUs || timeUs >= endUs || !amplitude.isFinite() || amplitude < 0f) return
        val span = endUs - startUs
        if (span <= 0L) return
        val index = (((timeUs - startUs).toDouble() / span) * peaks.size).toInt()
            .coerceIn(0, peaks.lastIndex)
        peaks[index] = maxOf(peaks[index], amplitude.coerceIn(0f, 1f))
    }
    fun values(): List<Float> = peaks.toList()
}

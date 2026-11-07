package com.comfyremote.app.data.project

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.ProgressHolder
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Renders an [ExportPlanBuilder.Plan] to MP4 with media3 Transformer:
 * parallel video and audio EditedMediaItemSequences, with per-clip clipping,
 * speed and gain. Original audio from video clips is mixed with the audio track. The output is written to a `.tmp` file and only validated + left
 * in place after the transform completes, so an interrupted export can never
 * leave a broken "finished" file behind (source media stays read-only).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    sealed interface Result {
        data class Success(val output: File, val durationMs: Long) : Result
        data object Cancelled : Result
    }

    private var transformer: Transformer? = null
    private val completed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private var cancelRequested = false

    /**
     * Exports the plan into [target] (a `.tmp` path in the final directory),
     * reporting 0..1 progress through [onProgress]. Returns [Result.Cancelled]
     * when [cancel] was requested; throws on transform or verification errors.
     */
    suspend fun export(plan: ExportPlanBuilder.Plan, target: File, onProgress: (Float) -> Unit): Result {
        check(plan.items.isNotEmpty()) { "Export plan is empty" }
        completed.set(false)
        failed.set(false)
        cancelRequested = false

        fun edited(item: ExportPlanBuilder.Item): EditedMediaItem {
            val mediaItem = MediaItem.Builder()
                .setUri(android.net.Uri.fromFile(File(item.sourcePath)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(item.inMs)
                        .setEndPositionMs(item.outMs)
                        .build(),
                )
                .build()
            val audioEffects = if (item.muted || item.volume != 1f) {
                listOf<AudioProcessor>(GainAudioProcessor(if (item.muted) 0f else item.volume))
            } else {
                emptyList()
            }
            return EditedMediaItem.Builder(mediaItem)
                .setDurationUs(item.durationMs * 1000L)
                .setEffects(Effects(audioEffects, listOf(SpeedChangeEffect(item.speed))))
                .build()
        }

        val videoSequence = EditedMediaItemSequence(plan.items.map(::edited))
        // In Media3 Composition, separate sequences start at t=0 and audio is
        // mixed, not appended. The export plan already trims audio to the video
        // duration, and sequence-local clipping/volume apply to both tracks.
        val composition = if (plan.audioItems.isEmpty()) {
            Composition.Builder(videoSequence).build()
        } else {
            val audioSequence = EditedMediaItemSequence(plan.audioItems.map(::edited))
            Composition.Builder(videoSequence, audioSequence).build()
        }

        coroutineScope {
            val completion = async {
                suspendCancellableCoroutine { continuation ->
                    val exporter = Transformer.Builder(context)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, result: ExportResult) {
                                completed.set(true)
                                if (continuation.isActive) continuation.resume(result)
                            }

                            override fun onError(composition: Composition, result: ExportResult, error: ExportException) {
                                failed.set(true)
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        })
                        .build()
                    transformer = exporter
                    exporter.start(composition, target.absolutePath)
                    continuation.invokeOnCancellation { exporter.cancel() }
                }
            }
            // Progress pump while the transform runs elsewhere.
            val holder = ProgressHolder()
            while (completion.isActive && !completed.get() && !failed.get()) {
                val state = transformer?.getProgress(holder)
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                }
                delay(PROGRESS_POLL_MS)
            }
            completion.await()
        }
        if (cancelRequested) return Result.Cancelled
        onProgress(1f)

        target.parentFile?.mkdirs()
        if (!target.isFile || target.length() == 0L) {
            throw IllegalStateException("Export produced no output file")
        }
        val durationMs = verifyOutput(target, plan.expectedDurationMs, plan.audioItems.isNotEmpty())
        return Result.Success(target, durationMs)
    }

    /** Cancels a running transform; export() then reports Result.Cancelled. */
    fun cancel() {
        cancelRequested = true
        transformer?.cancel()
    }

    /**
     * Full-file verification: the result must be decodable and its duration
     * must land within [DURATION_TOLERANCE] of the plan expectation.
     */
    private fun verifyOutput(file: File, expectedMs: Long, requireAudio: Boolean): Long {
        val metadata = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                    .equals("yes", ignoreCase = true)
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    .equals("yes", ignoreCase = true)
                Triple(duration, hasAudio, hasVideo)
            } finally {
                retriever.release()
            }
        }.getOrNull()
        val measured = metadata?.first
        if (measured == null || measured <= 0L || metadata?.third != true) {
            file.delete()
            throw IllegalStateException("Exported file is not playable")
        }
        if (requireAudio && metadata?.second != true) {
            file.delete()
            throw IllegalStateException("Exported file has no audio track despite an audio overlay")
        }
        if (expectedMs > 0) {
            val low = expectedMs * (1 - DURATION_TOLERANCE)
            val high = expectedMs * (1 + DURATION_TOLERANCE)
            if (measured < low || measured > high) {
                file.delete()
                throw IllegalStateException(
                    String.format(
                        Locale.US,
                        "Export duration %.1fs deviates from expected %.1fs",
                        measured / 1000f, expectedMs / 1000f,
                    ),
                )
            }
        }
        return measured
    }

    /**
     * Constant-gain PCM16 processor; Transformer's PCM output is 16-bit, so
     * per-sample short scaling covers both mute (0) and partial volume.
     */
    private class GainAudioProcessor(private val gain: Float) : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
            inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            val output = replaceOutputBuffer(remaining)
            if (gain == 1f) {
                output.put(inputBuffer)
                return
            }
            while (inputBuffer.hasRemaining()) {
                if (inputBuffer.remaining() < 2) {
                    output.put(inputBuffer.get())
                    continue
                }
                val sample = inputBuffer.getShort()
                val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(scaled.toShort())
            }
        }
    }

    companion object {
        private const val PROGRESS_POLL_MS = 200L
        private const val DURATION_TOLERANCE = 0.10
    }
}

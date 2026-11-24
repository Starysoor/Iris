package com.comfyremote.app.data.project

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
    private var activeExport: CancellableContinuation<ExportResult?>? = null

    /**
     * Exports the plan into [target] (a `.tmp` path in the final directory),
     * reporting 0..1 progress through [onProgress]. Returns [Result.Cancelled]
     * when [cancel] was requested; throws on transform or verification errors.
     */
    suspend fun export(plan: ExportPlanBuilder.Plan, target: File, onProgress: (Float) -> Unit): Result {
        check(plan.items.isNotEmpty()) { "Export plan is empty" }
        check(activeExport == null) { "An export is already running" }
        target.parentFile?.mkdirs()

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
                .setEffects(Effects(
                    audioEffects,
                    if (item.speed == 1f) emptyList() else listOf(SpeedChangeEffect(item.speed)),
                ))
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

        val result = try {
            coroutineScope {
                val completion = async {
                    suspendCancellableCoroutine<ExportResult?> { continuation ->
                        val exporter = Transformer.Builder(context)
                            // Some API 29 software codecs need more than 10 s for the first mixed sample.
                            .setMaxDelayBetweenMuxerSamplesMs(30_000L)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, result: ExportResult) {
                                    if (continuation.isActive) continuation.resume(result)
                                }

                                override fun onError(composition: Composition, result: ExportResult, error: ExportException) {
                                    if (continuation.isActive) continuation.resumeWithException(error)
                                }
                            })
                            .build()
                        transformer = exporter
                        activeExport = continuation
                        continuation.invokeOnCancellation { onTransformerThread(exporter) { exporter.cancel() } }
                        if (continuation.isActive) exporter.start(composition, target.absolutePath)
                    }
                }
                val holder = ProgressHolder()
                while (completion.isActive) {
                    if (transformer?.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                    }
                    delay(PROGRESS_POLL_MS)
                }
                completion.await()
            }
        } finally {
            activeExport = null
            transformer = null
        }
        if (result == null) return Result.Cancelled

        val durationMs = withContext(Dispatchers.IO) {
            if (!target.isFile || target.length() == 0L) {
                throw IllegalStateException("Export produced no output file")
            }
            verifyOutput(target, plan.expectedDurationMs, plan.audioItems.isNotEmpty())
        }
        onProgress(1f)
        return Result.Success(target, durationMs)
    }

    /** Cancels a running transform; export() then reports Result.Cancelled. */
    fun cancel() {
        val exporter = transformer ?: return
        val continuation = activeExport ?: return
        onTransformerThread(exporter) {
            if (continuation.isActive) {
                try {
                    exporter.cancel()
                } finally {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private fun onTransformerThread(exporter: Transformer, action: () -> Unit) {
        val looper = exporter.applicationLooper
        if (Looper.myLooper() == looper) action() else Handler(looper).post { action() }
    }

    /**
     * Checks the container duration and required tracks before publishing the export.
     */
    private fun verifyOutput(file: File, expectedMs: Long, requireAudio: Boolean): Long {
        val metadata = try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                ExportOutputVerification.Metadata(
                    durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION,
                    )?.toLongOrNull(),
                    // Android can return null for absent tracks; never dereference
                    // platform-type String! here (see #108).
                    hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
                    hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                )
            } finally {
                retriever.release()
            }
        } catch (failure: Exception) {
            file.delete() // only our temporary rendered output, never source media
            throw ExportOutputVerification.Invalid(
                ExportOutputVerification.Reason.METADATA_UNREADABLE,
                "Cannot inspect exported MP4 metadata: ${failure.message ?: failure.javaClass.simpleName}",
                failure,
            )
        }
        return try {
            ExportOutputVerification.requireValid(metadata, expectedMs, requireAudio)
        } catch (failure: ExportOutputVerification.Invalid) {
            file.delete()
            throw failure
        }
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
    }
}

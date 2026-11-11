package com.comfyremote.app.data.project

import java.util.Locale

/**
 * Pure, device-independent output metadata verification. Android's metadata
 * provider may return null for absent video/audio tracks; null audio is not an
 * error unless the user requested an audio overlay.
 */
internal object ExportOutputVerification {
    data class Metadata(val durationMs: Long?, val hasAudio: String?, val hasVideo: String?)

    enum class Reason { METADATA_UNREADABLE, INVALID_DURATION, NO_VIDEO_TRACK, AUDIO_MISSING, DURATION_DRIFT }

    class Invalid(val reason: Reason, message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    private const val DURATION_TOLERANCE = 0.10

    fun requireValid(metadata: Metadata, expectedMs: Long, requireAudio: Boolean): Long {
        val duration = metadata.durationMs
            ?.takeIf { it > 0L }
            ?: throw Invalid(Reason.INVALID_DURATION, "Exported MP4 has no valid duration metadata")
        if (metadata.hasVideo?.equals("yes", ignoreCase = true) != true) {
            throw Invalid(Reason.NO_VIDEO_TRACK, "Exported MP4 has no confirmed video track")
        }
        if (requireAudio && metadata.hasAudio?.equals("yes", ignoreCase = true) != true) {
            throw Invalid(Reason.AUDIO_MISSING, "Exported MP4 is missing the requested audio track")
        }
        if (expectedMs > 0L) {
            val low = expectedMs * (1 - DURATION_TOLERANCE)
            val high = expectedMs * (1 + DURATION_TOLERANCE)
            if (duration < low || duration > high) {
                throw Invalid(
                    Reason.DURATION_DRIFT,
                    String.format(
                        Locale.US,
                        "Export duration %.1fs deviates from expected %.1fs",
                        duration / 1000f, expectedMs / 1000f,
                    ),
                )
            }
        }
        return duration
    }
}

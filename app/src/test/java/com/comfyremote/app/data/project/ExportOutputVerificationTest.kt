package com.comfyremote.app.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExportOutputVerificationTest {
    private fun verify(audio: String?, video: String? = "yes", duration: Long? = 10_000L,
                       requireAudio: Boolean = false) =
        ExportOutputVerification.requireValid(
            ExportOutputVerification.Metadata(duration, audio, video),
            expectedMs = 10_000L, requireAudio = requireAudio,
        )

    @Test fun absentAudioMetadataDoesNotRejectValidSilentMp4() {
        assertEquals(10_000L, verify(audio = null))
        assertEquals(10_000L, verify(audio = "no"))
        assertEquals(10_000L, verify(audio = "YES"))
    }

    @Test fun missingRequiredOverlayIsStillRejected() {
        assertReason(ExportOutputVerification.Reason.AUDIO_MISSING) {
            verify(audio = null, requireAudio = true)
        }
        assertReason(ExportOutputVerification.Reason.AUDIO_MISSING) {
            verify(audio = "no", requireAudio = true)
        }
        assertEquals(10_000L, verify(audio = "YeS", requireAudio = true))
    }

    @Test fun nullVideoAndMissingVideoAreNotMistakenForSuccessfulExports() {
        assertReason(ExportOutputVerification.Reason.NO_VIDEO_TRACK) { verify(null, video = null) }
        assertReason(ExportOutputVerification.Reason.NO_VIDEO_TRACK) { verify(null, video = "no") }
    }

    @Test fun invalidDurationAndLargeDriftHaveDistinctReasons() {
        assertReason(ExportOutputVerification.Reason.INVALID_DURATION) { verify(null, duration = null) }
        assertReason(ExportOutputVerification.Reason.INVALID_DURATION) { verify(null, duration = 0) }
        assertReason(ExportOutputVerification.Reason.DURATION_DRIFT) { verify(null, duration = 12_000L) }
        assertEquals(10_900L, verify(null, duration = 10_900L))
    }

    private fun assertReason(reason: ExportOutputVerification.Reason, action: () -> Unit) {
        try {
            action()
            fail("Expected export verification to fail with $reason")
        } catch (failure: ExportOutputVerification.Invalid) {
            assertEquals(reason, failure.reason)
            assertTrue(failure.message?.isNotBlank() == true)
        }
    }
}

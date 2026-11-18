package com.comfyremote.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GallerySaveFeedbackTest {
    @Test fun progressIsBoundedForLargeMedia() {
        val state = GallerySaveFeedback.saving("/media/a.mp4", 1.5f)
        assertEquals(1f, state.progress ?: -1f, 0f)
        assertTrue(state.isSaving)
        assertEquals(GallerySavePhase.SAVING, state.phase)
        assertEquals(0f, GallerySaveFeedback.saving("/media/a.mp4", -1f).progress ?: -1f, 0f)
    }

    @Test fun successAndFailureRemainDistinctFromInProgress() {
        val success = GallerySaveFeedback.success("/media/a.jpg")
        assertEquals("/media/a.jpg", success.path)
        assertEquals(1f, success.progress ?: -1f, 0f)
        assertFalse(success.isSaving)
        assertEquals(GallerySavePhase.SUCCEEDED, success.phase)

        val failed = GallerySaveFeedback.failure("/media/b.mp4", "out of space")
        assertEquals(GallerySavePhase.FAILED, failed.phase)
        assertEquals("out of space", failed.error)
        assertFalse(failed.isSaving)
    }
}

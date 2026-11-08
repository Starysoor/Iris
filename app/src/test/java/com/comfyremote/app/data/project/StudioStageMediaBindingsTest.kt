package com.comfyremote.app.data.project

import com.comfyremote.app.data.cloud.CloudInputRule
import com.comfyremote.app.data.cloud.CloudWorkflowSpec
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioStageMediaBindingsTest {
    private fun report() = StudioStagePreflight.inspect(
        CloudWorkflowSpec(
            id = "video-v1",
            name = "Video",
            kind = "video",
            description = "",
            rules = listOf(
                CloudInputRule("image_start", "image", true, referenceRole = ReferenceRole.START_FRAME),
                CloudInputRule("image_end", "image", true, referenceRole = ReferenceRole.END_FRAME),
                CloudInputRule("voice", "audio", false),
                CloudInputRule("prompt", "prompt", true),
            ),
        ),
        expectedKind = "video",
        projectAssets = emptyList(),
    )

    private val projectAssets = listOf(
        ProjectAsset(id = "start", path = "/assets/start.jpg",
            kind = AssetKind.IMAGE, role = ReferenceRole.START_FRAME),
        ProjectAsset(id = "end", path = "/assets/end.jpg",
            kind = AssetKind.IMAGE, role = ReferenceRole.END_FRAME),
        ProjectAsset(id = "voice", path = "/assets/voice.wav",
            kind = AssetKind.AUDIO),
    )

    @Test fun exactFieldsCanResolveTwoFramesAndAudioInArbitraryInputOrder() {
        val bindings = listOf(
            StudioStageMediaBinding("autodl:video-v1", "image_end", "end"),
            StudioStageMediaBinding("autodl:video-v1", "voice", "voice"),
            StudioStageMediaBinding("autodl:video-v1", "image_start", "start"),
        )
        val resolved = StudioStageMediaBindings.resolve(
            "autodl:video-v1", report(), bindings, projectAssets, readable = { true })
        assertEquals("/assets/start.jpg", resolved?.get("image_start"))
        assertEquals("/assets/end.jpg", resolved?.get("image_end"))
        assertEquals("/assets/voice.wav", resolved?.get("voice"))
        assertEquals(3, resolved?.size)
    }

    @Test fun rolesKindsAndUnsupportedTextFieldsCannotBeBoundAsMedia() {
        val inputs = report().inputs
        assertTrue(StudioStageMediaBindings.accepts(inputs[0], projectAssets[0]))
        assertFalse(StudioStageMediaBindings.accepts(inputs[0], projectAssets[1]))
        assertFalse(StudioStageMediaBindings.accepts(inputs[0], projectAssets[2]))
        assertFalse(StudioStageMediaBindings.accepts(inputs[3], projectAssets[0]))
        assertFalse(StudioStageMediaBindings.accepts(inputs[0],
            projectAssets[0].copy(path = "/assets/unknown.zip")))
        assertFalse(StudioStageMediaBindings.accepts(inputs[2],
            projectAssets[2].copy(path = "/assets/not-audio.mp4")))
    }

    @Test fun failsClosedOnStaleMissingUnreadableOrWrongProviderBindings() {
        val valid = StudioStageMediaBinding("autodl:video-v1", "image_start", "start")
        val missing = StudioStageMediaBinding("autodl:video-v1", "image_end", "missing")
        val wrongField = StudioStageMediaBinding("autodl:video-v1", "imaginary", "end")
        val mismatch = StudioStageMediaBinding("autodl:video-v1", "image_start", "end")
        assertNull(StudioStageMediaBindings.resolve(
            "autodl:video-v1", report(), listOf(valid, missing), projectAssets, { true }))
        assertNull(StudioStageMediaBindings.resolve(
            "autodl:video-v1", report(), listOf(wrongField), projectAssets, { true }))
        assertNull(StudioStageMediaBindings.resolve(
            "autodl:video-v1", report(), listOf(mismatch), projectAssets, { true }))
        assertNull(StudioStageMediaBindings.resolve(
            "autodl:video-v1", report(), listOf(valid), projectAssets, { false }))
        assertTrue(StudioStageMediaBindings.resolve(
            "autodl:another-model", report(), listOf(valid), projectAssets, { false })!!.isEmpty())
    }
}

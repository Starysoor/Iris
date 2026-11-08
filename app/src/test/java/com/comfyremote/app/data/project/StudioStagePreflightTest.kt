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

class StudioStagePreflightTest {
    private fun workflow(
        kind: String = "video",
        rules: List<CloudInputRule>,
    ) = CloudWorkflowSpec(
        id = "provider-model",
        name = "Provider model",
        kind = kind,
        description = "Provider input schema",
        rules = rules,
    )

    @Test
    fun actualTwoFrameRulesDoNotInventExtraImageInputsOrMappings() {
        val spec = workflow(rules = listOf(
            CloudInputRule("prompt", "prompt", required = true),
            CloudInputRule("start_frame", "image", required = true,
                referenceRole = ReferenceRole.START_FRAME),
            CloudInputRule("end_frame", "image", required = false,
                referenceRole = ReferenceRole.END_FRAME),
            CloudInputRule("duration", "integer", required = true,
                min = 3.0, max = 5.0),
        ))
        val assets = listOf(
            ProjectAsset(path = "/tmp/start.png", role = ReferenceRole.START_FRAME, kind = AssetKind.IMAGE),
            ProjectAsset(path = "/tmp/outfit.png", role = ReferenceRole.OUTFIT_REFERENCE, kind = AssetKind.IMAGE),
        )
        val report = StudioStagePreflight.inspect(spec, "video", assets)
        assertEquals(StudioStagePreflight.Support.CONFIGURABLE, report.support)
        assertEquals(2, report.imageInputs)
        assertEquals(1, report.requiredImageInputs)
        assertEquals(0, report.audioInputs)
        assertEquals(1, report.inputs.first { it.field == "start_frame" }.candidateAssets)
        assertEquals(0, report.inputs.first { it.field == "end_frame" }.candidateAssets)
        assertEquals(5.0, report.inputs.first { it.field == "duration" }.max!!, 0.0)
        assertTrue(report.unsupportedRequired.isEmpty())
        assertNull(report.providerPrice)
        // Inventory only. No model submission, upload or asset assignment occurs.
    }

    @Test
    fun twoRequiredFramesCannotBeCountedAsFilledByTheSameGenericAsset() {
        val bothFrames = workflow(rules = listOf(
            CloudInputRule("first", "image", required = true,
                referenceRole = ReferenceRole.START_FRAME),
            CloudInputRule("last", "image", required = true,
                referenceRole = ReferenceRole.END_FRAME),
        ))
        val single = ProjectAsset(
            path = "/tmp/generic.png",
            kind = AssetKind.IMAGE,
            role = ReferenceRole.GENERIC_REFERENCE,
        )
        val missing = StudioStagePreflight.inspect(bothFrames, "video", listOf(single))
        assertEquals(1, missing.potentialMissingMediaSlots.size)
        assertEquals(StudioStagePreflight.Support.CONFIGURABLE, missing.support)
        val ready = StudioStagePreflight.inspect(
            bothFrames, "video",
            listOf(single, ProjectAsset(
                path = "/tmp/end.png",
                kind = AssetKind.IMAGE,
                role = ReferenceRole.END_FRAME,
            )),
        )
        assertTrue(ready.potentialMissingMediaSlots.isEmpty())
        // Duplicated records for the same path cannot impersonate two files.
        assertEquals(1, StudioStagePreflight.inspect(
            bothFrames, "video", listOf(single, single.copy(id = "duplicate"))
        ).potentialMissingMediaSlots.size)
    }

    @Test
    fun requiredVideoUploadIsBlockedBecauseCreateCannotEditVideoFields() {
        val report = StudioStagePreflight.inspect(
            workflow(rules = listOf(
                CloudInputRule("source_video", "video", required = true),
                CloudInputRule("prompt", "prompt", required = true),
            )),
            "video",
            emptyList(),
        )
        assertEquals(StudioStagePreflight.Support.REQUIRES_UNSUPPORTED_INPUT, report.support)
        assertEquals(listOf("source_video"), report.unsupportedRequired)
    }

    @Test
    fun unknownRequiredRuleIsBlockedButOptionalUnrecognizedRuleIsInformational() {
        val spec = workflow(rules = listOf(
            CloudInputRule("required_hex_mesh", "mesh", required = true),
            CloudInputRule("optional_file", "mesh", required = false),
        ))
        val report = StudioStagePreflight.inspect(spec, "video", emptyList())
        assertEquals(listOf("required_hex_mesh"), report.unsupportedRequired)
        assertEquals(StudioStagePreflight.Support.REQUIRES_UNSUPPORTED_INPUT, report.support)
    }

    @Test
    fun audioInputsAndProviderEnumValuesAreReadFromSpecWithoutNameGuessing() {
        val report = StudioStagePreflight.inspect(
            workflow("audio", listOf(
                CloudInputRule("voice_sample", "audio", required = true),
                CloudInputRule("voice", "enum", required = true,
                    options = listOf("actor_a", "actor_b")),
                CloudInputRule("seed", "custom-seed", required = true),
            )),
            "audio",
            listOf(ProjectAsset(path = "/tmp/voice.wav", kind = AssetKind.AUDIO)),
        )
        assertEquals(StudioStagePreflight.Support.CONFIGURABLE, report.support)
        assertEquals(1, report.requiredAudioInputs)
        assertEquals(1, report.inputs.first().candidateAssets)
        assertEquals(listOf("actor_a", "actor_b"), report.inputs[1].options)
        assertFalse(report.inputs[0].defaultProvided)
    }

    @Test
    fun contractMustMatchSelectedStageKindAndContainRealRules() {
        val spec = workflow("image", listOf(
            CloudInputRule("prompt", "prompt", required = true),
        ))
        assertEquals(StudioStagePreflight.Support.REQUIRES_UNSUPPORTED_INPUT,
            StudioStagePreflight.inspect(spec, "video", emptyList()).support)
        assertEquals(StudioStagePreflight.Support.UNKNOWN_CONTRACT,
            StudioStagePreflight.inspect(workflow(rules = emptyList()), "video", emptyList()).support)
    }
}

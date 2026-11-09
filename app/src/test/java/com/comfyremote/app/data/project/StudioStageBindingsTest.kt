package com.comfyremote.app.data.project

import com.comfyremote.app.data.cloud.CloudInputRule
import com.comfyremote.app.data.cloud.CloudWorkflowSpec
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioStageBindingsTest {
    private val spec = CloudWorkflowSpec(
        id = "video-with-frames",
        name = "Video with frames",
        kind = "video",
        description = "",
        rules = listOf(
            CloudInputRule("first_frame", "image", required = true,
                referenceRole = ReferenceRole.START_FRAME),
            CloudInputRule("last_frame", "image", required = true,
                referenceRole = ReferenceRole.END_FRAME),
            CloudInputRule("voice", "audio", required = false),
        ),
    )

    private val start = ProjectAsset(id = "start", path = "/tmp/first.png",
        kind = AssetKind.IMAGE, role = ReferenceRole.START_FRAME)
    private val end = ProjectAsset(id = "end", path = "/tmp/last.png",
        kind = AssetKind.IMAGE, role = ReferenceRole.END_FRAME)
    private val audio = ProjectAsset(id = "audio", path = "/tmp/voice.wav", kind = AssetKind.AUDIO)

    @Test fun fieldBindingsPreserveSemanticRolesAndExactProviderInputNames() {
        val report = StudioStagePreflight.inspect(spec, "video", listOf(start, end, audio))
        val project = VideoProject(title = "Film", workflowId = "video-with-frames",
            assets = listOf(start, end, audio), stageMediaBindings = mapOf(
                StudioStageBindings.key("autodl:video-with-frames", "first_frame") to "start",
                StudioStageBindings.key("autodl:video-with-frames", "last_frame") to "end",
                StudioStageBindings.key("autodl:video-with-frames", "voice") to "audio",
                StudioStageBindings.key("autodl:other-video", "first_frame") to "end",
            ))
        val resolved = StudioStageBindings.resolve(project, "autodl:video-with-frames", report)
        assertTrue(resolved.valid)
        assertEquals(mapOf(
            "first_frame" to "/tmp/first.png",
            "last_frame" to "/tmp/last.png",
            "voice" to "/tmp/voice.wav",
        ), resolved.fieldPaths)
        assertEquals(listOf(start),
            StudioStageBindings.candidates(report.inputs.first(), project.assets))
        assertFalse(StudioStageBindings.matches(report.inputs[0], end))
    }

    @Test fun changedProviderContractOrDeletedAssetIsAnErrorNotSilentFallback() {
        val key = StudioStageBindings.key("autodl:video-with-frames", "first_frame")
        val report = StudioStagePreflight.inspect(spec, "video", listOf(start))
        val project = VideoProject(title = "Film", workflowId = "", assets = listOf(start),
            stageMediaBindings = mapOf(key to "deleted-asset"))
        val missing = StudioStageBindings.resolve(project, "autodl:video-with-frames", report)
        assertFalse(missing.valid)
        assertTrue(missing.errors.single().contains("Missing project asset"))

        val wrong = project.copy(assets = listOf(end),
            stageMediaBindings = mapOf(key to "end"))
        val mismatch = StudioStageBindings.resolve(wrong, "autodl:video-with-frames", report)
        assertFalse(mismatch.valid)
        assertTrue(mismatch.errors.single().contains("Wrong media type or reference role"))
        assertTrue(mismatch.fieldPaths.isEmpty())
    }

    @Test fun noBindingsRemainValidAndCannotAttachToAnUnrelatedModel() {
        val report = StudioStagePreflight.inspect(spec, "video", listOf(start))
        val project = VideoProject(title = "Film", workflowId = "")
        assertTrue(StudioStageBindings.resolve(project, "autodl:video-with-frames", report).valid)
        assertTrue(StudioStageBindings.resolve(project, "autodl:video-with-frames", report).fieldPaths.isEmpty())
    }
}

package com.comfyremote.app.data.project

import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationRequest
import com.comfyremote.app.data.generation.GenerationTask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioSubmissionPolicyTest {
    private fun request(project: String?, shot: String?, cost: Int = 2) = GenerationRequest(
        serverUrl = "server", workflowPath = "test", workflowName = "test",
        fieldValues = emptyMap(), randomSeed = false, nodeTitles = emptyMap(),
        fields = emptyList(), convertedPrompt = JSONObject(),
        provider = "cloud", storyboardProjectId = project, storyboardShotId = shot,
        estimatedCloudCost = cost,
    )

    @Test fun blocksHiddenProjectBudgetOverrunInBatch() {
        val existing = listOf(GenerationTask("old", request("p2", "s2", cost = 7), GenerationPhase.SUCCEEDED))
        val batch = listOf(request("p1", "s1"), request("p2", "s2", cost = 4))
        val reject = StudioSubmissionPolicy.evaluate(batch, existing) { id ->
            StudioSubmissionPolicy.ProjectInfo(budget = 10.0, shotStatuses = mapOf((if (id == "p1") "s1" else "s2") to "done"))
        }
        assertTrue(reject is StudioSubmissionPolicy.Rejection.OverBudget)
        assertEquals("p2", (reject as StudioSubmissionPolicy.Rejection.OverBudget).projectId)
    }

    @Test fun aRunningShotCannotBeRetriedFromTasks() {
        val reject = StudioSubmissionPolicy.evaluate(listOf(request("p1", "shot")), emptyList()) {
            StudioSubmissionPolicy.ProjectInfo(50.0, mapOf("shot" to "running"))
        }
        assertTrue(reject is StudioSubmissionPolicy.Rejection.BusyShot)
    }

    @Test fun aShotStageCannotBeSubmittedWhileThatStageIsQueuedOrRepeatedInTheBatch() {
        val audio = request("p1", "shot").copy(studioStageKind = "audio")
        val image = request("p1", "shot").copy(studioStageKind = "image")
        val queued = listOf(GenerationTask("existing", audio, GenerationPhase.QUEUED))
        val project = StudioSubmissionPolicy.ProjectInfo(null, mapOf("shot" to "draft"))

        assertEquals(
            StudioSubmissionPolicy.Rejection.BusyShot("p1", "shot"),
            StudioSubmissionPolicy.evaluate(listOf(audio), queued) { project },
        )
        assertEquals(
            StudioSubmissionPolicy.Rejection.BusyShot("p1", "shot"),
            StudioSubmissionPolicy.evaluate(listOf(audio, audio.copy(id = "another")), emptyList()) { project },
        )
        assertNull(StudioSubmissionPolicy.evaluate(listOf(image), queued) { project })
    }

    @Test fun rejectsMissingProjectOrShotInsteadOfSilentlyDetaching() {
        val missingProject = StudioSubmissionPolicy.evaluate(listOf(request("unknown", null)), emptyList()) { null }
        assertTrue(missingProject is StudioSubmissionPolicy.Rejection.MissingProject)
        val missingShot = StudioSubmissionPolicy.evaluate(listOf(request("p1", "gone")), emptyList()) {
            StudioSubmissionPolicy.ProjectInfo(null, emptyMap())
        }
        assertTrue(missingShot is StudioSubmissionPolicy.Rejection.MissingShot)
    }

    @Test fun independentAndInBudgetRequestsStayAllowed() {
        assertNull(StudioSubmissionPolicy.evaluate(listOf(request(null, null)), emptyList()) { null })
        assertNull(StudioSubmissionPolicy.evaluate(listOf(request("p1", null)), emptyList()) {
            StudioSubmissionPolicy.ProjectInfo(3.0, emptyMap())
        })
    }
}

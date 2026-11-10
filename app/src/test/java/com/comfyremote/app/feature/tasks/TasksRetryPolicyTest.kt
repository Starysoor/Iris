package com.comfyremote.app.feature.tasks

import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationRequest
import com.comfyremote.app.data.generation.GenerationTask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksRetryPolicyTest {
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
        val reject = TasksRetryPolicy.evaluate(batch, existing) { id ->
            TasksRetryPolicy.ProjectInfo(budget = 10.0, shotStatuses = mapOf((if (id == "p1") "s1" else "s2") to "done"))
        }
        assertTrue(reject is TasksRetryPolicy.Rejection.OverBudget)
        assertEquals("p2", (reject as TasksRetryPolicy.Rejection.OverBudget).projectId)
    }

    @Test fun aRunningShotCannotBeRetriedFromTasks() {
        val reject = TasksRetryPolicy.evaluate(listOf(request("p1", "shot")), emptyList()) {
            TasksRetryPolicy.ProjectInfo(50.0, mapOf("shot" to "running"))
        }
        assertTrue(reject is TasksRetryPolicy.Rejection.BusyShot)
    }

    @Test fun rejectsMissingProjectOrShotInsteadOfSilentlyDetaching() {
        val missingProject = TasksRetryPolicy.evaluate(listOf(request("unknown", null)), emptyList()) { null }
        assertTrue(missingProject is TasksRetryPolicy.Rejection.MissingProject)
        val missingShot = TasksRetryPolicy.evaluate(listOf(request("p1", "gone")), emptyList()) {
            TasksRetryPolicy.ProjectInfo(null, emptyMap())
        }
        assertTrue(missingShot is TasksRetryPolicy.Rejection.MissingShot)
    }

    @Test fun independentAndInBudgetRequestsStayAllowed() {
        assertNull(TasksRetryPolicy.evaluate(listOf(request(null, null)), emptyList()) { null })
        assertNull(TasksRetryPolicy.evaluate(listOf(request("p1", null)), emptyList()) {
            TasksRetryPolicy.ProjectInfo(3.0, emptyMap())
        })
    }
}

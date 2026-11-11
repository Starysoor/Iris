package com.comfyremote.app.data.project

import com.comfyremote.app.data.cloud.CloudSpendEntry
import com.comfyremote.app.data.generation.GenerationRequest
import com.comfyremote.app.data.generation.GenerationTask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectAcceptedSpendTest {
    private fun task(project: String, remote: String?, cost: Int): GenerationTask {
        val request = GenerationRequest(
            provider = "mock", serverUrl = "https://provider.example",
            workflowPath = "mock:video", workflowName = "film", fieldValues = emptyMap(),
            randomSeed = false, nodeTitles = emptyMap(), fields = emptyList(),
            convertedPrompt = JSONObject(), storyboardProjectId = project,
            estimatedCloudCost = cost,
        )
        return GenerationTask(taskId = "task-$remote-$project", request = request, promptId = remote)
    }
    private fun accepted(project: String?, remote: String, cost: Int?) = CloudSpendEntry(
        workflowName = "film", providerId = "mock", endpoint = "https://provider.example",
        projectId = project, remoteTaskId = remote, points = cost,
    )

    @Test fun useAcceptedEstimateForTaskButNeverDoubleCountIt() {
        val tasks = listOf(task("p1", "r1", 6), task("p1", "r1", 6), task("p1", null, 2))
        val ledger = listOf(accepted("p1", "r1", 7))
        val stats = taskCostStats(tasks, "p1", ledger)
        assertEquals(3, stats.count)
        assertEquals(9.0, stats.costSum, 0.001)
    }

    @Test fun keepPrunedAcceptedTasksInTheProjectTotal() {
        val tasks = listOf(task("p1", "r1", 2))
        val ledger = listOf(accepted("p1", "r1", 2), accepted("p1", "r2", 8))
        assertEquals(10.0, taskCostStats(tasks, "p1", ledger).costSum, 0.001)
    }

    @Test fun neverAttributeAnotherProjectOrLegacyEntriesWithoutIdentity() {
        val tasks = listOf(task("p1", "r1", 4))
        val ledger = listOf(accepted("p2", "r1", 9), accepted(null, "r1", 8))
        assertEquals(4.0, taskCostStats(tasks, "p1", ledger).costSum, 0.001)
    }

    @Test fun unknownLedgerCostFallsBackToTaskEstimate() {
        val tasks = listOf(task("p1", "r1", 5))
        assertEquals(5.0, taskCostStats(tasks, "p1", listOf(accepted("p1", "r1", null))).costSum, 0.001)
    }
}

package com.comfyremote.app.feature.tasks

import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationRequest
import com.comfyremote.app.data.generation.GenerationTask
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBulkSelectionTest {
    private fun task(id: String, projectId: String?, server: String, phase: GenerationPhase,
                     pending: Boolean = false) = GenerationTask(
        taskId = id,
        request = GenerationRequest(
            id = id, serverUrl = server, workflowPath = "wf", workflowName = "Workflow",
            fieldValues = emptyMap(), randomSeed = false, nodeTitles = emptyMap(),
            fields = emptyList(), convertedPrompt = JSONObject(), storyboardProjectId = projectId,
        ),
        phase = phase, localPending = pending,
    )

    private val tasks = listOf(
        task("p1-failed", "p1", "server-a", GenerationPhase.FAILED),
        task("p1-success", "p1", "server-a", GenerationPhase.SUCCEEDED),
        task("p1-queued", "p1", "server-a", GenerationPhase.QUEUED),
        task("p1-running", "p1", "server-a", GenerationPhase.RUNNING),
        task("p2-failed", "p2", "server-a", GenerationPhase.FAILED),
        task("p2-pending", "p2", "server-a", GenerationPhase.PREPARING, pending = true),
        task("independent", null, "server-a", GenerationPhase.FAILED),
        task("other-server", "p1", "server-b", GenerationPhase.FAILED),
    )

    @Test fun actionsTargetOnlySelectedProjectAndServer() {
        assertEquals(listOf("p1-failed"), TaskBulkSelection.failed(tasks, "server-a", "p1").map { it.taskId })
        assertEquals(listOf("p1-queued"), TaskBulkSelection.queued(tasks, "server-a", "p1").map { it.taskId })
        assertEquals(listOf("p1-failed", "p1-success"),
            TaskBulkSelection.finished(tasks, "server-a", "p1").map { it.taskId })
    }

    @Test fun independentScopeDoesNotSelectProjectTasks() {
        assertEquals(listOf("independent"), TaskBulkSelection.failed(tasks, null, "").map { it.taskId })
        assertTrue(TaskBulkSelection.queued(tasks, null, "").isEmpty())
        assertEquals(listOf("independent"), TaskBulkSelection.finished(tasks, null, "").map { it.taskId })
    }

    @Test fun unfilteredViewRetainsOriginalAllScope() {
        assertEquals(4, TaskBulkSelection.failed(tasks, null, null).size)
        assertEquals(listOf("p1-queued", "p2-pending"), TaskBulkSelection.queued(tasks, null, null).map { it.taskId })
        assertEquals(6, TaskBulkSelection.finished(tasks, null, null).size)
    }

    @Test fun statusChipsRemainBoundToTheSameScope() {
        val ui = TasksUiState(tasks = tasks, projectFilter = "p1", serverUrl = "server-a", filter = TaskFilter.ALL)
        assertEquals(listOf("p1-failed", "p1-success", "p1-queued", "p1-running"), ui.visible.map { it.taskId })
        assertEquals(ui.queuedTargets, TaskBulkSelection.queued(ui.tasks, ui.serverUrl, ui.projectFilter))
    }
}

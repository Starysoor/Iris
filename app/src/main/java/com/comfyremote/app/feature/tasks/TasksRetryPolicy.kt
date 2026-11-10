package com.comfyremote.app.feature.tasks

import com.comfyremote.app.data.generation.GenerationRequest
import com.comfyremote.app.data.generation.GenerationTask
import com.comfyremote.app.data.project.BudgetGate
import com.comfyremote.app.data.project.taskCostStats

/** Budget and shot-state preflight shared by single and batch retries in Tasks. */
internal object TasksRetryPolicy {
    data class ProjectInfo(val budget: Double?, val shotStatuses: Map<String, String>)

    sealed interface Rejection {
        data class MissingProject(val id: String) : Rejection
        data class MissingShot(val projectId: String, val shotId: String) : Rejection
        data class BusyShot(val projectId: String, val shotId: String) : Rejection
        data class OverBudget(val projectId: String, val decision: BudgetGate.Decision.Blocked) : Rejection
    }

    fun evaluate(
        requests: List<GenerationRequest>,
        existingTasks: List<GenerationTask>,
        projectInfo: (String) -> ProjectInfo?,
    ): Rejection? {
        val byProject = requests.filter { it.storyboardProjectId != null }
            .groupBy { it.storyboardProjectId.orEmpty() }
        for ((projectId, projectRequests) in byProject) {
            val context = projectInfo(projectId) ?: return Rejection.MissingProject(projectId)
            for (request in projectRequests) {
                val shotId = request.storyboardShotId ?: continue
                val status = context.shotStatuses[shotId] ?: return Rejection.MissingShot(projectId, shotId)
                if (status == "running") return Rejection.BusyShot(projectId, shotId)
            }
            val spent = taskCostStats(existingTasks, projectId).costSum
            val newCost = projectRequests.sumOf { it.estimatedCloudCost?.toDouble() ?: 0.0 }
            when (val result = BudgetGate.check(spent, newCost, context.budget)) {
                is BudgetGate.Decision.Blocked -> return Rejection.OverBudget(projectId, result)
                BudgetGate.Decision.Allowed -> Unit
            }
        }
        return null
    }
}

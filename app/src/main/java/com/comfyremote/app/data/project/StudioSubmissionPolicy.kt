package com.comfyremote.app.data.project

import com.comfyremote.app.data.cloud.CloudSpendEntry
import com.comfyremote.app.data.generation.GenerationRequest
import com.comfyremote.app.data.generation.GenerationTask

/** Budget and shot-state preflight for all Studio submissions. */
internal object StudioSubmissionPolicy {
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
        ledger: List<CloudSpendEntry> = emptyList(),
        projectInfo: (String) -> ProjectInfo?,
    ): Rejection? {
        val byProject = requests.filter { it.storyboardProjectId != null }
            .groupBy { it.storyboardProjectId.orEmpty() }
        for ((projectId, projectRequests) in byProject) {
            val context = projectInfo(projectId) ?: return Rejection.MissingProject(projectId)
            val occupiedStages = existingTasks.asSequence()
                .filter { it.isActive && it.request.storyboardProjectId == projectId }
                .mapNotNull { task ->
                    task.request.storyboardShotId?.let { it to (task.request.studioStageKind ?: "video") }
                }
                .toMutableSet()
            for (request in projectRequests) {
                val shotId = request.storyboardShotId ?: continue
                val status = context.shotStatuses[shotId] ?: return Rejection.MissingShot(projectId, shotId)
                val stage = shotId to (request.studioStageKind ?: "video")
                if (status == "running" || !occupiedStages.add(stage)) return Rejection.BusyShot(projectId, shotId)
            }
            val spent = taskCostStats(existingTasks, projectId, ledger).costSum
            val newCost = projectRequests.sumOf { it.estimatedCloudCost?.toDouble() ?: 0.0 }
            when (val result = BudgetGate.check(spent, newCost, context.budget)) {
                is BudgetGate.Decision.Blocked -> return Rejection.OverBudget(projectId, result)
                BudgetGate.Decision.Allowed -> Unit
            }
        }
        return null
    }
}

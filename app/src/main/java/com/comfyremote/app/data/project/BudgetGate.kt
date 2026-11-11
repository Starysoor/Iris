package com.comfyremote.app.data.project

import com.comfyremote.app.data.generation.GenerationTask
import com.comfyremote.app.data.cloud.CloudSpendEntry

/** Aggregated estimate spend for one project's generation tasks. */
data class TaskCostStats(val count: Int, val costSum: Double)

/**
 * Sums the estimated cloud cost of the tasks that belong to [projectId] via
 * the storyboard association. Pure so both the project home banner and the
 * pre-submission budget gate aggregate identically.
 */
fun taskCostStats(
    tasks: List<GenerationTask>,
    projectId: String,
    ledger: List<CloudSpendEntry> = emptyList(),
): TaskCostStats {
    val projectTasks = tasks.filter { it.request.storyboardProjectId == projectId }
    val associatedLedger = ledger.filter {
        it.projectId == projectId && !it.remoteTaskId.isNullOrBlank()
    }

    // Remote identity is provider + endpoint + remote task ID. Legacy entries
    // lacking projectId cannot safely be attributed to this project.
    fun key(provider: String?, endpoint: String?, remoteId: String?) =
        if (remoteId.isNullOrBlank()) null else Triple(provider, endpoint, remoteId)
    val accepted = associatedLedger.associateBy {
        key(it.providerId, it.endpoint, it.remoteTaskId)
    }
    val seen = mutableSetOf<Triple<String?, String?, String>>()
    val fromTasks = projectTasks.sumOf { task ->
        val remoteKey = key(task.request.provider, task.request.serverUrl, task.promptId)
        if (remoteKey != null && !seen.add(remoteKey)) {
            0.0 // Never count the same accepted remote request twice.
        } else {
            val estimate = accepted[remoteKey]?.points ?: task.request.estimatedCloudCost
            estimate?.toDouble() ?: 0.0
        }
    }
    // Jobs may be pruned from Tasks while their accepted estimates survive in
    // the ledger. Count those too, not just the visible task list.
    val historical = associatedLedger
        .filter { key(it.providerId, it.endpoint, it.remoteTaskId) !in seen }
        .distinctBy { key(it.providerId, it.endpoint, it.remoteTaskId) }
        .sumOf { it.points?.toDouble() ?: 0.0 }
    return TaskCostStats(projectTasks.size, fromTasks + historical)
}

/**
 * Pre-submission budget enforcement for project shots (#57 P3): blocks cloud
 * submissions whose estimated cost would push the project past its stored
 * budget. Estimates only — the decision compares the sum of this project's
 * task cost estimates with the new batch estimate.
 */
object BudgetGate {

    sealed interface Decision {
        /** Budget absent/zero, or the new cost still fits. */
        data object Allowed : Decision

        /** Spent + new exceeds the budget; the caller must refuse to submit. */
        data class Blocked(
            val spentEstimate: Double,
            val newCost: Double,
            val budget: Double,
        ) : Decision {
            val projectedTotal: Double get() = spentEstimate + newCost
        }
    }

    /**
     * [spentEstimate] — sum of the project's task cost estimates;
     * [newCost] — estimate of the batch about to be submitted;
     * [budget] — project budget in points; null/zero disables the gate.
     * Reaching the budget exactly is still allowed; only going past blocks.
     */
    fun check(spentEstimate: Double, newCost: Double, budget: Double?): Decision {
        if (budget == null || budget <= 0.0) return Decision.Allowed
        val projected = spentEstimate + newCost.coerceAtLeast(0.0)
        return if (projected > budget) {
            Decision.Blocked(spentEstimate, newCost, budget)
        } else {
            Decision.Allowed
        }
    }
}

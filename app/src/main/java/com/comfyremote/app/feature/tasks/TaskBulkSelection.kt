package com.comfyremote.app.feature.tasks

import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationTask

/**
 * Shared display/action scoping for task bulk operations. The project filter is
 * optional: null means all projects; "" means independent tasks. Never use the
 * selected visual status chip to widen an action to unrelated projects.
 */
internal object TaskBulkSelection {
    fun matchesScope(task: GenerationTask, serverUrl: String?, projectFilter: String?): Boolean =
        (serverUrl == null || task.request.serverUrl == serverUrl) &&
            (projectFilter == null || task.request.storyboardProjectId.orEmpty() == projectFilter)

    fun queued(tasks: List<GenerationTask>, serverUrl: String?, projectFilter: String?): List<GenerationTask> =
        tasks.filter { task ->
            matchesScope(task, serverUrl, projectFilter) && task.isActive &&
                (task.localPending || task.phase == GenerationPhase.PREPARING ||
                    task.phase == GenerationPhase.QUEUEING || task.phase == GenerationPhase.QUEUED)
        }

    fun failed(tasks: List<GenerationTask>, serverUrl: String?, projectFilter: String?): List<GenerationTask> =
        tasks.filter { matchesScope(it, serverUrl, projectFilter) && it.phase == GenerationPhase.FAILED }

    fun finished(tasks: List<GenerationTask>, serverUrl: String?, projectFilter: String?): List<GenerationTask> =
        tasks.filter { matchesScope(it, serverUrl, projectFilter) && it.isTerminal }
}

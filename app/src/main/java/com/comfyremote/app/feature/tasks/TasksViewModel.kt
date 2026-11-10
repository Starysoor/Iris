package com.comfyremote.app.feature.tasks

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comfyremote.app.AppContainer
import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationTask
import com.comfyremote.app.data.project.BudgetGate
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.navigation.Routes
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TaskFilter { ALL, ACTIVE, QUEUED, FAILED, DONE }

data class TasksUiState(
    val tasks: List<GenerationTask> = emptyList(),
    val filter: TaskFilter = TaskFilter.ALL,
    val serverUrl: String? = null,
    val localQueuePaused: Boolean = false,
    /** Null = all, empty = independent, otherwise the Studio project ID. */
    val projectFilter: String? = null,
) {
    val visible: List<GenerationTask>
        get() = tasks.asSequence()
            .filter { task ->
                when (filter) {
                    TaskFilter.ALL -> true
                    TaskFilter.ACTIVE -> task.isActive
                    TaskFilter.QUEUED -> task.localPending || task.phase == GenerationPhase.QUEUEING || task.phase == GenerationPhase.QUEUED
                    TaskFilter.FAILED -> task.phase == GenerationPhase.FAILED
                    TaskFilter.DONE -> task.isTerminal
                }
            }
            .filter { TaskBulkSelection.matchesScope(it, serverUrl, projectFilter) }
            .sortedWith(compareByDescending<GenerationTask> { it.isActive }.thenByDescending { it.createdAt })
            .toList()

    val queuedTargets: List<GenerationTask>
        get() = TaskBulkSelection.queued(tasks, serverUrl, projectFilter)

    val failedTargets: List<GenerationTask>
        get() = TaskBulkSelection.failed(tasks, serverUrl, projectFilter)

    val finishedTargets: List<GenerationTask>
        get() = TaskBulkSelection.finished(tasks, serverUrl, projectFilter)

    val serverUrls: List<String>
        get() = tasks.map { it.request.serverUrl }.filter(String::isNotBlank).distinct()
}

class TasksViewModel(private val container: AppContainer) : ViewModel() {
    val tasks: StateFlow<List<GenerationTask>> = container.generationManager.tasks
    private val _filter = MutableStateFlow(TaskFilter.ALL)
    private val _serverUrl = MutableStateFlow<String?>(null)
    private val _projectFilter = MutableStateFlow<String?>(null)
    val projects = container.projectStore.projects

    val uiState: StateFlow<TasksUiState> = combine(
        tasks,
        _filter,
        _serverUrl,
        container.generationManager.localQueuePaused,
        _projectFilter,
    ) { list, filter, serverUrl, paused, projectFilter ->
        TasksUiState(list, filter, serverUrl, paused, projectFilter)
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        TasksUiState(tasks.value, _filter.value, _serverUrl.value, container.generationManager.localQueuePaused.value, _projectFilter.value),
    )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            container.generationManager.persistenceError.collect { error ->
                if (error != null) {
                    _message.value = AppStrings.get(R.string.tasks_msg_persist_failed, error)
                    container.generationManager.clearPersistenceError()
                }
            }
        }
    }

    fun task(id: String): GenerationTask? = tasks.value.firstOrNull { it.taskId == id }

    fun setFilter(filter: TaskFilter) { _filter.value = filter }
    fun setServerFilter(url: String?) { _serverUrl.value = url }
    fun setProjectFilter(projectId: String?) { _projectFilter.value = projectId }
    fun toggleLocalQueuePaused() = container.generationManager.toggleLocalQueuePaused()

    fun delete(id: String) { container.generationManager.deleteTask(id) }
    fun deleteFinished() {
        // Bulk actions must never erase finished tasks from another selected project
        // or server. Preserve the original atomic global path for the unfiltered view.
        if (_projectFilter.value == null && _serverUrl.value == null) {
            container.generationManager.deleteFinished()
        } else {
            TaskBulkSelection.finished(tasks.value, _serverUrl.value, _projectFilter.value)
                .forEach { container.generationManager.deleteTask(it.taskId) }
        }
    }
    fun cancel(id: String) {
        viewModelScope.launch {
            val outcome = container.generationManager.cancel(id)
            if (!outcome.cancelled && outcome.error != null) {
                _message.value = AppStrings.get(R.string.tasks_msg_cancel_failed, outcome.error)
            }
        }
    }

    fun cancelQueued() {
        viewModelScope.launch {
            // Capture the user-visible scope before any suspension or remote call.
            val outcomes = container.generationManager.cancelMany(
                TaskBulkSelection.queued(tasks.value, _serverUrl.value, _projectFilter.value)
                    .map { it.taskId },
            )
            val failed = outcomes.count { !it.cancelled && it.error != null }
            _message.value = if (failed > 0) {
                AppStrings.get(R.string.tasks_msg_cancel_partial, failed)
            } else {
                AppStrings.get(R.string.tasks_msg_queued_cancelled)
            }
        }
    }

    /** A tasks-page retry is still a *new* paid request: do not bypass Studio budget
     * enforcement simply because the user retried from Tasks rather than Create. */
    private fun projectRetryError(requests: List<com.comfyremote.app.data.generation.GenerationRequest>): String? {
        val rejection = TasksRetryPolicy.evaluate(requests, tasks.value) { projectId ->
            container.projectStore.project(projectId)?.let { project ->
                TasksRetryPolicy.ProjectInfo(project.budget, project.shots.associate { it.id to it.status })
            }
        } ?: return null
        return when (rejection) {
            is TasksRetryPolicy.Rejection.MissingProject,
            is TasksRetryPolicy.Rejection.MissingShot -> AppStrings.get(R.string.proj_not_found)
            is TasksRetryPolicy.Rejection.BusyShot -> AppStrings.get(R.string.gen_vm_storyboard_running)
            is TasksRetryPolicy.Rejection.OverBudget -> AppStrings.get(
                R.string.gen_vm_budget_blocked,
                rejection.decision.budget,
                rejection.decision.spentEstimate,
                rejection.decision.newCost,
            )
        }
    }

    fun retryFailed() {
        val serverUrl = _serverUrl.value
        val requests = TaskBulkSelection.failed(tasks.value, serverUrl, _projectFilter.value)
            .sortedBy { it.createdAt }
            .map {
                it.request.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                )
            }
        if (requests.isEmpty()) {
            _message.value = AppStrings.get(R.string.tasks_msg_no_failed)
            return
        }
        projectRetryError(requests)?.let { _message.value = it; return }
        viewModelScope.launch {
            val ids = try {
                container.cloudSubmissionGate.submit(requests, localQueue = true)
            } catch (error: Exception) {
                _message.value = error.message ?: error.javaClass.simpleName
                return@launch
            }
            if (ids.isNotEmpty()) {
                _message.value = AppStrings.plural(R.plurals.tasks_msg_retries_queued, ids.size, ids.size)
            }
        }
    }

    fun retry(id: String) {
        retryRequest(id) { newId -> container.navigationRequests.request(Routes.task(newId)) }
    }

    fun duplicate(id: String) {
        retryRequest(id) { newId -> container.navigationRequests.request(Routes.task(newId)) }
    }

    private fun retryRequest(id: String, onSubmitted: (String) -> Unit) {
        val task = task(id) ?: return
        val request = task.request.copy(
            id = java.util.UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
        )
        projectRetryError(listOf(request))?.let { _message.value = it; return }
        viewModelScope.launch {
            val ids = try {
                container.cloudSubmissionGate.submit(listOf(request), localQueue = false)
            } catch (error: Exception) {
                _message.value = error.message ?: error.javaClass.simpleName
                return@launch
            }
            ids.firstOrNull()?.let(onSubmitted)
        }
    }

    fun recover(id: String): Boolean {
        val ok = container.generationManager.recoverExisting(id)
        _message.value = if (ok) AppStrings.get(R.string.tasks_msg_reconnecting)
        else AppStrings.get(R.string.tasks_msg_cannot_recover)
        return ok
    }

    fun storyboardLabel(task: GenerationTask): String? {
        val projectId = task.request.storyboardProjectId ?: return null
        val project = container.projectStore.project(projectId) ?: return projectId
        val index = project.shots.indexOfFirst { it.id == task.request.storyboardShotId }
        return if (index >= 0) "${project.title} · ${index + 1}" else project.title
    }

    fun serverLabel(url: String): String = container.serverRepository.load()
        .firstOrNull { it.url == url }
        ?.name
        ?.takeIf { it.isNotBlank() }
        ?: url

    fun prepareReuse(id: String): DraftTransfer? {
        val task = task(id) ?: return null
        val hasActualSeed = task.seedValues.isNotEmpty()
        val transfer = DraftTransfer(
            workflowPath = task.request.workflowPath,
            workflowName = task.request.workflowName,
            values = if (hasActualSeed) task.request.fieldValues + task.seedValues else task.request.fieldValues,
            sourceFields = task.request.fields,
            randomSeed = if (hasActualSeed) false else task.request.randomSeed,
            serverUrl = task.request.serverUrl,
            reuseAcrossWorkflows = container.appSettingsStore.settings.value.reuseChooseWorkflow,
        )
        container.draftTransferRepository.put(transfer)
        return transfer
    }

    fun diagnostics(id: String): String {
        val task = task(id) ?: return ""
        return buildString {
            appendLine("Workflow: ${task.request.workflowName}")
            appendLine("Server: ${serverLabel(task.request.serverUrl)}")
            appendLine("Task: ${task.taskId}")
            task.batchId?.let { appendLine("Batch: $it ${task.batchIndex ?: "?"}/${task.batchSize ?: "?"}") }
            task.promptId?.let { appendLine("Prompt: $it") }
            appendLine("Phase: ${task.phase}")
            appendLine("Local pending: ${task.localPending}")
            task.queuePosition?.let { appendLine("Queue position: $it") }
            task.nodeId?.let { appendLine("Node: #$it ${task.nodeTitle.orEmpty()}") }
            if (task.seedInfo.isNotBlank()) appendLine("Seed: ${task.seedInfo}")
            appendLine("Created: ${task.createdAt}")
            task.startedAt?.let { appendLine("Started: $it") }
            task.completedAt?.let { appendLine("Completed: $it") }
            appendLine("Elapsed ms: ${task.elapsedMs()}")
            if (task.recovered) appendLine("Recovered: ${task.recoveryNote.orEmpty()}")
            task.error?.let { error ->
                appendLine("Error kind: ${error.kind}")
                error.nodeType?.let { appendLine("Node type: $it") }
                appendLine("Message: ${error.message}")
            }
        }.trim()
    }

    fun saveResult(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.galleryExporter.savePng(bitmap) }
                .onSuccess { _message.value = container.galleryExporter.savedMessage() }
                .onFailure { _message.value = container.galleryExporter.failMessage(it) }
        }
    }

    fun saveMedia(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.galleryExporter.saveMediaFile(path) }
                .onSuccess { _message.value = container.galleryExporter.savedMessage() }
                .onFailure { _message.value = container.galleryExporter.failMessage(it) }
        }
    }

    fun clearMessage() { _message.value = null }
}

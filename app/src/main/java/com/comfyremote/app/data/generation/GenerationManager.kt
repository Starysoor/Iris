package com.comfyremote.app.data.generation

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.comfyremote.app.AppContainer
import com.comfyremote.app.ComfyApi
import com.comfyremote.app.ComfyException
import com.comfyremote.app.PromptValidationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** App-scoped runtime owner for all generation jobs, including process-death recovery. */
class GenerationManager(
    private val container: AppContainer,
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val taskStore = container.generationTaskStore
    private val taskLock = Any()

    private val _tasks = MutableStateFlow(prune(normalizeLoaded(taskStore.load())))
    val tasks: StateFlow<List<GenerationTask>> = _tasks.asStateFlow()
    val activeTasks: StateFlow<List<GenerationTask>> = _tasks
        .map { list -> list.filter { it.isActive } }
        .stateIn(scope, SharingStarted.Eagerly, _tasks.value.filter { it.isActive })

    private val _localQueuePaused = MutableStateFlow(false)
    val localQueuePaused: StateFlow<Boolean> = _localQueuePaused.asStateFlow()

    /** Last durable-state write failure; surfaced by Generate/Tasks instead of being silent. */
    private val _persistenceError = MutableStateFlow<String?>(null)
    val persistenceError: StateFlow<String?> = _persistenceError.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val spendRecordedIds = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(_tasks.value.filter { it.spendRecorded }.map { it.taskId })
    }

    init {
        // Persist normalization before any recovery/local-queue work starts.
        runCatching { taskStore.save(_tasks.value) }
            .onFailure { _persistenceError.value = it.message ?: it.javaClass.simpleName }
        val persistedActive = _tasks.value.filter { it.isActive }
        if (persistedActive.isNotEmpty()) startService()
        persistedActive
            .filter { !it.localPending && !it.promptId.isNullOrBlank() }
            .forEach(::launchRecovery)
        pumpLocalQueue()
    }

    /** Submits one request immediately to its bound server. */
    fun submit(request: GenerationRequest): String {
        val task = newTask(request)
        if (!mutateTasks { prune(listOf(task) + it) }) {
            throw ComfyException(persistenceMessage("task was not submitted"))
        }
        startService()
        launchTracked(task.taskId) { execute(task) }
        return task.taskId
    }

    fun clearPersistenceError() {
        _persistenceError.value = null
    }

    private fun persistenceMessage(action: String): String =
        "Cannot persist task state ($action): ${_persistenceError.value ?: "unknown storage error"}"

    /**
     * Adds a batch. When [localQueue] is true, each server receives at most one app-managed
     * batch job at a time; the remaining requests stay durably queued on-device.
     */
    fun submitBatch(requests: List<GenerationRequest>, localQueue: Boolean = true): List<String> {
        if (requests.isEmpty()) return emptyList()
        val batchId = UUID.randomUUID().toString()
        val tasks = requests.mapIndexed { index, request ->
            newTask(request).copy(
                localPending = localQueue,
                batchId = batchId,
                batchIndex = index + 1,
                batchSize = requests.size,
                recoveryNote = if (localQueue) "Waiting in local queue" else null,
            )
        }
        if (!mutateTasks { prune(tasks + it) }) {
            throw ComfyException(persistenceMessage("batch was not submitted"))
        }
        startService()
        if (localQueue) {
            pumpLocalQueue()
        } else {
            tasks.forEach { task -> launchTracked(task.taskId) { execute(task) } }
        }
        return tasks.map { it.taskId }
    }

    fun pauseLocalQueue() {
        _localQueuePaused.value = true
    }

    fun resumeLocalQueue() {
        _localQueuePaused.value = false
        pumpLocalQueue()
    }

    fun toggleLocalQueuePaused() {
        if (_localQueuePaused.value) resumeLocalQueue() else pauseLocalQueue()
    }

    suspend fun cancel(taskId: String): CancelOutcome {
        val task = _tasks.value.firstOrNull { it.taskId == taskId }
            ?: return CancelOutcome(taskId, false, "Task not found")
        if (!task.isActive || task.phase == GenerationPhase.CANCELLING) {
            return CancelOutcome(taskId, false)
        }

        val cloud = task.request.provider != "comfyui"
        return when (cancelActionFor(task.phase, cloud, task.localPending)) {
            CancelAction.LOCAL_PENDING -> {
                var cancelledWhilePending = false
                // Queue pumping and cancellation compete for the same lock.
                // Only treat this as a not-yet-submitted cancellation when the
                // *current* persisted task still belongs to the local queue.
                val committed = mutateTasks { list ->
                    val updated = list.firstOrNull { it.taskId == taskId }
                        ?.cancelIfStillLocalPending(System.currentTimeMillis())
                    if (updated == null) list else {
                        cancelledWhilePending = true
                        prune(list.map { if (it.taskId == taskId) updated else it })
                    }
                }
                when {
                    !committed -> CancelOutcome(taskId, false, persistenceMessage("cancel was not saved; task may still run"))
                    !cancelledWhilePending -> cancel(taskId) // It left the queue: apply remote cancellation semantics.
                    else -> {
                        pumpLocalQueue()
                        CancelOutcome(taskId, true)
                    }
                }
            }
            CancelAction.CANCEL_JOB -> {
                if (!patch(taskId) { it.copy(phase = GenerationPhase.CANCELLING, recoveryNote = null) }) {
                    CancelOutcome(taskId, false, persistenceMessage("cancel could not be persisted"))
                } else {
                    finishCancel(taskId)
                    CancelOutcome(taskId, true)
                }
            }
            CancelAction.DELETE_QUEUE -> {
                val promptId = task.promptId
                if (promptId.isNullOrBlank()) {
                    return CancelOutcome(taskId, false, "Missing server prompt id; task remains queued")
                }
                val result = runCatching { ComfyApi(task.request.serverUrl).deleteFromQueue(promptId) }
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message ?: "Server queue delete failed"
                    patch(taskId) { it.copy(recoveryNote = "Server queue delete failed: $message") }
                    return CancelOutcome(taskId, false, message)
                }
                if (!patch(taskId) { it.copy(phase = GenerationPhase.CANCELLING, recoveryNote = null) }) {
                    CancelOutcome(taskId, false, persistenceMessage("remote queue removal succeeded; local cancel was not saved"))
                } else {
                    finishCancel(taskId)
                    CancelOutcome(taskId, true)
                }
            }
            CancelAction.INTERRUPT_SERVER -> {
                // ComfyUI /interrupt is server-scoped, not a per-prompt cancel.
                val result = runCatching { ComfyApi(task.request.serverUrl).interrupt() }
                if (result.isFailure) {
                    val message = result.exceptionOrNull()?.message ?: "Server interrupt failed"
                    patch(taskId) {
                        it.copy(
                            phase = GenerationPhase.RUNNING,
                            recoveryNote = "Interrupt failed: $message",
                        )
                    }
                    return CancelOutcome(taskId, false, message)
                }
                if (!patch(taskId) { it.copy(phase = GenerationPhase.CANCELLING, recoveryNote = null) }) {
                    CancelOutcome(taskId, false, persistenceMessage("server interrupt succeeded; local cancel was not saved"))
                } else {
                    finishCancel(taskId)
                    CancelOutcome(taskId, true)
                }
            }
            CancelAction.NOT_CANCELLABLE -> CancelOutcome(taskId, false)
        }
    }

    private fun finishCancel(taskId: String) {
        val job = jobs[taskId]
        if (job != null) {
            job.cancel()
        } else {
            patch(taskId) {
                it.copy(
                    phase = GenerationPhase.CANCELLED,
                    completedAt = System.currentTimeMillis(),
                    localPending = false,
                    queuePosition = null,
                    progress = null,
                    preview = null,
                )
            }
        }
    }

    suspend fun cancelMany(taskIds: Collection<String>): List<CancelOutcome> =
        taskIds.distinct().map { cancel(it) }

    suspend fun cancelQueued(serverUrl: String? = null): List<CancelOutcome> {
        val ids = _tasks.value
            .filter { task ->
                task.isActive &&
                    (serverUrl == null || task.request.serverUrl == serverUrl) &&
                    (task.localPending || task.phase == GenerationPhase.PREPARING ||
                        task.phase == GenerationPhase.QUEUEING || task.phase == GenerationPhase.QUEUED)
            }
            .map { it.taskId }
        return cancelMany(ids)
    }

    /** Removes a finished task from the list (user-visible delete). */
    fun deleteTask(taskId: String) {
        val task = _tasks.value.firstOrNull { it.taskId == taskId } ?: return
        if (task.isActive) return
        jobs.remove(taskId)?.cancel()
        mutateTasks { list -> list.filter { it.taskId != taskId } }
    }

    /** Removes every non-active task (convenience "clear finished"). */
    fun deleteFinished() {
        val activeIds = _tasks.value.filter { it.isActive }.map { it.taskId }.toSet()
        mutateTasks { list -> list.filter { it.isActive } }
        jobs.keys.toList().forEach { id -> if (id !in activeIds) jobs.remove(id)?.cancel() }
    }

    /** Re-attach a previously failed recovery without submitting a duplicate prompt. */
    fun recoverExisting(taskId: String): Boolean {
        val current = _tasks.value.firstOrNull { it.taskId == taskId } ?: return false
        if (current.promptId.isNullOrBlank() || current.isActive) return false
        val recovering = current.copy(
            phase = GenerationPhase.QUEUED,
            error = null,
            completedAt = null,
            recovered = true,
            recoveryNote = "Retrying recovery",
            preview = null,
            queuePosition = null,
            localPending = false,
        )
        if (!patch(taskId, allowTerminalExit = true) { recovering }) return false
        startService()
        launchRecovery(recovering)
        return true
    }

    fun observe(taskId: String) = _tasks
        .map { list -> list.firstOrNull { it.taskId == taskId } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private fun newTask(request: GenerationRequest) = GenerationTask(
        taskId = request.id,
        request = request,
        clientId = UUID.randomUUID().toString(),
    )

    /** Persists runner progress and records spend exactly once, after the remote task id exists. */
    private fun persistProgress(updated: GenerationTask, allowTerminalExit: Boolean = false) {
        val firstCloudSubmit = updated.shouldRecordCloudSpend && spendRecordedIds.add(updated.taskId)
        if (firstCloudSubmit) {
            container.cloudSpendStore.record(
                workflowName = updated.request.workflowName,
                points = updated.request.estimatedCloudCost,
                remoteTaskId = updated.promptId,
                providerId = updated.request.provider,
                endpoint = updated.request.serverUrl,
                projectId = updated.request.storyboardProjectId,
            )
        }
        val persisted = patch(updated.taskId, allowTerminalExit) {
            updated.copy(
                localPending = false,
                spendRecorded = updated.spendRecorded || firstCloudSubmit || updated.taskId in spendRecordedIds,
            )
        }
        if (!persisted) {
            // Never discard provider recovery data after a failed local commit.
            // The remote job may already be paid for, so include its ID and
            // prevent the runner from reporting a durable SUCCESS.
            val remote = updated.promptId?.takeIf { it.isNotBlank() }
            val suffix = remote?.let { "; remote task ID=$it; check the provider before retrying" }.orEmpty()
            throw ComfyException(persistenceMessage("generation task could not be saved$suffix"))
        }
    }

    private suspend fun execute(task: GenerationTask) {
        val runner = runnerFor(task)
        try {
            val final = runner.run(task.copy(localPending = false, recoveryNote = null)) { updated ->
                persistProgress(updated)
            }
            persistProgress(final)
            forgetIfTerminal(final)
        } catch (e: kotlinx.coroutines.CancellationException) {
            patch(task.taskId) {
                it.copy(
                    phase = GenerationPhase.CANCELLED,
                    completedAt = System.currentTimeMillis(),
                    preview = null,
                    queuePosition = null,
                    localPending = false,
                )
            }
            forgetIfTerminal(task.copy(phase = GenerationPhase.CANCELLED))
            throw e
        } catch (e: Exception) {
            patchFailure(task.taskId, e)
        }
    }

    private fun launchRecovery(task: GenerationTask) {
        if (jobs.containsKey(task.taskId)) return
        launchTracked(task.taskId) { recoverExecute(task) }
    }

    private suspend fun recoverExecute(original: GenerationTask) {
        val task = original.copy(
            phase = if (original.phase == GenerationPhase.RUNNING) GenerationPhase.RUNNING else GenerationPhase.QUEUED,
            preview = null,
            recovered = true,
            error = null,
            completedAt = null,
            recoveryNote = original.recoveryNote ?: "Recovering after restart",
            localPending = false,
        )
        patch(task.taskId, allowTerminalExit = true) { task }
        val runner = runnerFor(task)
        try {
            val final = runner.recover(task) { updated -> persistProgress(updated, allowTerminalExit = true) }
            persistProgress(final, allowTerminalExit = true)
            forgetIfTerminal(final)
        } catch (e: kotlinx.coroutines.CancellationException) {
            patch(task.taskId, allowTerminalExit = true) {
                it.copy(
                    phase = GenerationPhase.CANCELLED,
                    completedAt = System.currentTimeMillis(),
                    preview = null,
                    queuePosition = null,
                    localPending = false,
                )
            }
            forgetIfTerminal(task.copy(phase = GenerationPhase.CANCELLED))
            throw e
        } catch (e: Exception) {
            patch(task.taskId, allowTerminalExit = true) {
                it.copy(
                    phase = GenerationPhase.FAILED,
                    error = GenerationError(ErrorKind.CONNECTION, "Recovery failed: ${e.message ?: e}"),
                    completedAt = System.currentTimeMillis(),
                    preview = null,
                    queuePosition = null,
                    recoveryNote = "Reconnect failed",
                    localPending = false,
                )
            }
        }
    }

    /** Provider recovery state is only needed while a task can still be resumed. */
    private fun forgetIfTerminal(task: GenerationTask) {
        if (task.request.provider == "comfyui") return
        if (task.phase != GenerationPhase.SUCCEEDED && task.phase != GenerationPhase.CANCELLED) return
        val promptId = task.promptId?.takeIf { it.isNotBlank() } ?: return
        container.cloudProviders.byId(task.request.provider)?.forgetTask(promptId)
    }

    private fun runnerFor(task: GenerationTask): GenerationRunner {
        val request = task.request
        val history = RepositoryGenerationHistorySink(container.historyRepository, request.serverUrl)
        val resolver: (String) -> String? = { container.cloudInputStore.toRequestValue(it) }
        return if (request.provider != "comfyui") {
            val provider = container.cloudProviders.byId(request.provider)
                ?: error("Unknown cloud provider ${request.provider}")
            val workflowId = request.workflowPath.removePrefix(provider.pathPrefix)
            val settings = container.cloudProviderSettingsStore
            val pollMs = settings.pollIntervalSeconds(provider.id) * 1_000L
            GenerationRunner(
                backend = CloudWorkflowBackend(
                    repository = provider,
                    workflowId = workflowId,
                    providerLabel = provider.label,
                    retryCount = settings.pollRetryCount(provider.id),
                    retryDelayMs = pollMs,
                ),
                history = history,
                pollDelayMs = pollMs,
                timeoutMs = settings.taskTimeoutMinutes(provider.id) * 60_000L,
                resolveCloudImage = resolver,
            )
        } else {
            GenerationRunner(
                backend = ComfyGenerationBackend(request.serverUrl),
                history = history,
                resolveCloudImage = resolver,
            )
        }
    }

    private fun patchFailure(taskId: String, e: Exception) {
        patch(taskId) {
            it.copy(
                phase = GenerationPhase.FAILED,
                error = GenerationError(
                    kind = when (e) {
                        is PromptValidationException -> ErrorKind.PROMPT_VALIDATION
                        is GenerationTimeoutException -> ErrorKind.TIMEOUT
                        else -> ErrorKind.CONNECTION
                    },
                    message = e.message ?: e.toString(),
                ),
                completedAt = System.currentTimeMillis(),
                preview = null,
                queuePosition = null,
                localPending = false,
            )
        }
    }

    private fun launchTracked(taskId: String, block: suspend () -> Unit) {
        if (jobs.containsKey(taskId)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                jobs.remove(taskId)
                pumpLocalQueue()
            }
        }
        jobs[taskId] = job
        job.start()
    }

    /** Starts the oldest local-pending task for each idle server. */
    private fun pumpLocalQueue() {
        if (_localQueuePaused.value) return
        scope.launch {
            val snapshot = _tasks.value
            val remoteBusy = snapshot
                .filter { it.isActive && !it.localPending }
                .mapTo(hashSetOf()) { it.request.serverUrl }
            val candidates = snapshot
                .filter { it.localPending && it.isActive }
                .sortedBy { it.createdAt }
                .groupBy { it.request.serverUrl }
                .mapNotNull { (server, list) -> if (server in remoteBusy) null else list.firstOrNull() }

            candidates.forEach { candidate ->
                var launch: GenerationTask? = null
                synchronized(taskLock) {
                    val current = _tasks.value.firstOrNull { it.taskId == candidate.taskId }
                    val serverBusyNow = _tasks.value.any {
                        it.taskId != candidate.taskId && it.request.serverUrl == candidate.request.serverUrl &&
                            it.isActive && !it.localPending
                    }
                    if (current?.localPending == true && current.isActive && !serverBusyNow && !_localQueuePaused.value) {
                        val updated = current.copy(localPending = false, recoveryNote = null)
                        val next = prune(_tasks.value.map { if (it.taskId == current.taskId) updated else it })
                        try {
                            taskStore.save(next)
                            _tasks.value = next
                            launch = updated
                        } catch (error: Exception) {
                            _persistenceError.value = error.message ?: error.javaClass.simpleName
                        }
                    }
                }
                launch?.let { task -> launchTracked(task.taskId) { execute(task) } }
            }
        }
    }

    private fun startService() {
        runCatching {
            ContextCompat.startForegroundService(appContext, Intent(appContext, GenerationService::class.java))
        }
    }

    private fun patch(
        taskId: String,
        allowTerminalExit: Boolean = false,
        transform: (GenerationTask) -> GenerationTask,
    ): Boolean = mutateTasks { list ->
        prune(list.map { current ->
            if (current.taskId != taskId) current
            else {
                val next = transform(current)
                if (current.isTerminal && !next.isTerminal && !allowTerminalExit) current else next
            }
        })
    }

    /**
     * Publishes memory only after durable state was written. A failed write keeps
     * the previous queue (and surfaces [persistenceError]) instead of pretending
     * that a paid/recoverable task was saved.
     */
    private fun mutateTasks(transform: (List<GenerationTask>) -> List<GenerationTask>): Boolean {
        synchronized(taskLock) {
            val old = _tasks.value
            val next = transform(old)
            // Preview/progress can update many times per second; only durable recovery state hits disk.
            if (durable(old) == durable(next)) {
                _tasks.value = next
                return true
            }
            return try {
                taskStore.save(next)
                _tasks.value = next
                true
            } catch (error: Exception) {
                _persistenceError.value = error.message ?: error.javaClass.simpleName
                false
            }
        }
    }

    private fun durable(list: List<GenerationTask>): List<GenerationTask> = list.map {
        it.copy(
            preview = null,
            progress = null,
            queueRemaining = null,
            nodeId = null,
            nodeTitle = null,
        )
    }

    private fun normalizeLoaded(list: List<GenerationTask>): List<GenerationTask> = list.map { task ->
        when {
            task.localPending -> task.copy(
                phase = GenerationPhase.PREPARING,
                promptId = null,
                preview = null,
                queuePosition = null,
                progress = null,
                error = null,
                completedAt = null,
                recoveryNote = "Waiting in local queue",
            )
            task.phase == GenerationPhase.CANCELLING -> task.copy(
                phase = GenerationPhase.CANCELLED,
                completedAt = task.completedAt ?: System.currentTimeMillis(),
                preview = null,
                recoveryNote = "Cancellation persisted across restart",
            )
            task.isActive && task.promptId.isNullOrBlank() -> task.copy(
                phase = GenerationPhase.FAILED,
                error = GenerationError(ErrorKind.CONNECTION, "App restarted before the server returned a prompt id"),
                completedAt = System.currentTimeMillis(),
                preview = null,
                recoveryNote = "Submission interrupted before recovery became possible",
            )
            else -> task.copy(preview = null)
        }
    }

    private fun prune(list: List<GenerationTask>): List<GenerationTask> {
        val active = list.filter { it.isActive }
        val terminal = list.filterNot { it.isActive }
            .sortedByDescending { it.completedAt ?: it.createdAt }
            .take(MAX_TERMINAL_TASKS)
        return (active + terminal).distinctBy { it.taskId }.sortedByDescending { it.createdAt }
    }

    companion object { private const val MAX_TERMINAL_TASKS = 80 }
}

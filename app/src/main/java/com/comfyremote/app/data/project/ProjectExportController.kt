package com.comfyremote.app.data.project

import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import com.comfyremote.app.AppContainer
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Application-owned export state; UI/ViewModel leaving the screen cannot cancel
 * it. Android foreground service owns the running coroutine and notification.
 */
class ProjectExportController(private val container: AppContainer) {
    sealed interface State {
        val projectId: String?
        data object Idle : State { override val projectId: String? = null }
        data class Running(override val projectId: String, val progress: Float, val sessionId: String) : State
        data class Succeeded(override val projectId: String, val outputPath: String) : State
        data class Failed(override val projectId: String, val message: String) : State
    }

    private val journal = ProjectExportJournal(
        File(container.app.filesDir, "project-export-session.json"))
    private val exporter = VideoExporter(container.app)
    private val _state = MutableStateFlow(recoverState())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var cancelRequested = false
    private var request: VideoProject? = null
    @Volatile internal var sourcePaths: Set<String> = emptySet()
        private set

    /** Explicit user action. Persist crash marker BEFORE requesting foreground service. */
    fun requestStart(projectId: String): Boolean {
        if (_state.value is State.Running || job != null) return false
        val project = container.projectStore.project(projectId) ?: return false
        if (project.timeline?.videoTrack.isNullOrEmpty()) {
            _state.value = State.Failed(projectId, AppStrings.get(R.string.proj_export_no_clips))
            return false
        }
        val sessionId = UUID.randomUUID().toString()
        try {
            journal.markRunning(projectId, sessionId)
            request = project
            val timeline = requireNotNull(project.timeline)
            sourcePaths = (timeline.videoTrack + timeline.audioTrack).mapTo(mutableSetOf(), TimelineClip::sourcePath)
            _state.value = State.Running(projectId, 0f, sessionId)
            val intent = Intent(container.app, ProjectExportService::class.java)
                .setAction(ProjectExportService.ACTION_START)
                .putExtra(ProjectExportService.EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(container.app, intent)
            return true
        } catch (error: Exception) {
            finish(State.Failed(projectId, error.message ?: error.javaClass.simpleName))
            return false
        }
    }

    /** Invoked ONLY by the foreground Service; never by the ViewModel. */
    fun runInService(sessionId: String, scope: CoroutineScope) {
        val current = _state.value as? State.Running ?: return
        val project = request ?: return
        if (current.sessionId != sessionId || job != null) return
        cancelRequested = false
        val running = scope.launch(start = CoroutineStart.LAZY) { execute(project, current) }
        job = running
        running.start()
    }

    fun cancel(sessionId: String? = null) {
        val current = _state.value as? State.Running ?: return
        if (sessionId != null && current.sessionId != sessionId) return
        cancelRequested = true
        val running = job
        if (running == null) {
            finish(State.Idle)
        } else {
            running.cancel()
        }
    }

    fun dismiss(projectId: String) {
        val current = _state.value
        if (current !is State.Running && current.projectId == projectId) finish(State.Idle)
    }

    /** Unexpected Service teardown must not leave the UI reporting "Running". */
    fun onServiceDestroyed(sessionId: String?) {
        val running = _state.value as? State.Running ?: return
        if (running.sessionId != sessionId) return
        val activeJob = job
        if (activeJob != null) activeJob.cancel()
        else failed(running.projectId, AppStrings.get(R.string.proj_export_interrupted))
    }

    private fun failed(projectId: String, message: String) {
        finish(State.Failed(projectId, message))
    }

    private fun finish(state: State) {
        runCatching { journal.clear() }
        sourcePaths = emptySet()
        request = null
        _state.value = state
    }

    private fun recoverState(): State {
        val session = journal.interrupted() ?: return State.Idle
        val output = session.sessionId?.let { container.historyRepository.find("project-export-$it") }
            ?.images?.firstOrNull { File(it).isFile }
        if (output != null) {
            runCatching { journal.clear() }
            return State.Succeeded(session.projectId, output)
        }
        return State.Failed(session.projectId, AppStrings.get(R.string.proj_export_interrupted))
    }

    private fun durationMs(path: String): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    private suspend fun execute(project: VideoProject, initial: State.Running) {
        val projectId = project.id
        var tmp: File? = null
        try {
            val timeline = requireNotNull(project.timeline)
            val clips = timeline.videoTrack + timeline.audioTrack
            val (available, durationByPath) = withContext(Dispatchers.IO) {
                val paths = clips.map { it.sourcePath }.toSet()
                val existing = paths.filter { File(it).isFile }.toSet()
                existing to existing.associateWith(::durationMs)
            }
            val missing = clips.filter { it.sourcePath !in available }
            if (missing.isNotEmpty()) {
                failed(projectId, AppStrings.get(
                    R.string.proj_export_skipped,
                    missing.joinToString(", ") {
                        it.sourceLabel.ifBlank { it.sourcePath.substringAfterLast('/') }
                    },
                ))
                return
            }
            val unreadable = durationByPath.filterValues { it == null || it <= 0L }.keys
            if (unreadable.isNotEmpty()) {
                failed(projectId, AppStrings.get(
                    R.string.proj_export_unreadable,
                    unreadable.joinToString(", ") { it.substringAfterLast('/') },
                ))
                return
            }
            val plan = ExportPlanBuilder.build(
                timeline, available, durationByPath.mapValues { it.value!! },
            )
            if (plan.invalidTrims.isNotEmpty()) {
                failed(projectId, AppStrings.get(R.string.proj_export_invalid_trim,
                    plan.invalidTrims.joinToString(", ")))
                return
            }
            if (plan.isEmpty) {
                failed(projectId, AppStrings.get(R.string.proj_export_no_clips))
                return
            }
            val target = container.historyRepository.newMediaFile(
                "project-export-" + initial.sessionId, 0, "mp4")
            val temporary = File(target.parentFile, target.name + ".tmp")
            tmp = temporary
            val result = exporter.export(plan, temporary) { progress ->
                _state.value = initial.copy(progress = progress)
            }
            when (result) {
                is VideoExporter.Result.Cancelled -> {
                    temporary.delete()
                    finish(State.Idle)
                }
                is VideoExporter.Result.Success -> {
                    kotlin.coroutines.coroutineContext.ensureActive()
                    withContext(NonCancellable) {
                        withContext(Dispatchers.IO) {
                            if (!temporary.renameTo(target)) {
                                throw IllegalStateException(AppStrings.get(R.string.proj_export_commit_failed))
                            }
                            try {
                                container.historyRepository.add(
                                    HistoryItem(
                                        id = "project-export-" + initial.sessionId,
                                        wfPath = project.workflowPath,
                                        wfName = AppStrings.get(R.string.proj_export_history_name, project.title),
                                        time = System.currentTimeMillis(),
                                        values = emptyMap(), fields = emptyList(), randomSeed = false,
                                        images = listOf(target.absolutePath),
                                        summary = project.title.take(50),
                                        prompts = emptyList(), seeds = "", serverUrl = "",
                                        tags = listOf("project-export"),
                                    ),
                                )
                            } catch (error: Exception) {
                                target.delete()
                                throw error
                            }
                        }
                        finish(State.Succeeded(projectId, target.absolutePath))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            tmp?.delete()
            if (_state.value is State.Running) {
                if (cancelRequested) finish(State.Idle)
                else failed(projectId, AppStrings.get(R.string.proj_export_interrupted))
            }
        } catch (error: Exception) {
            tmp?.delete()
            failed(projectId, error.message ?: error.javaClass.simpleName)
        } finally {
            job = null
        }
    }
}

package com.comfyremote.app.data.project

import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import com.comfyremote.app.AppContainer
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        data object Idle : State
        data class Running(val projectId: String, val progress: Float) : State
        data class Succeeded(val projectId: String, val outputPath: String) : State
        data class Failed(val projectId: String, val message: String) : State
    }

    private val journal = ProjectExportJournal(
        File(container.app.filesDir, "project-export-session.json"))
    private val exporter = VideoExporter(container.app)
    private val _state = MutableStateFlow<State>(
        journal.interruptedProjectId()?.let {
            State.Failed(it, "Previous export interrupted by process termination. Please retry.")
        } ?: State.Idle,
    )
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var cancelRequested = false

    /** Explicit user action. Persist crash marker BEFORE requesting foreground service. */
    fun requestStart(projectId: String): Boolean {
        if (_state.value is State.Running || job?.isActive == true) return false
        val project = container.projectStore.project(projectId) ?: return false
        if (project.timeline?.videoTrack.isNullOrEmpty()) {
            _state.value = State.Failed(projectId, AppStrings.get(R.string.proj_export_no_clips))
            return false
        }
        try {
            journal.markRunning(projectId)
            _state.value = State.Running(projectId, 0f)
            val intent = Intent(container.app, ProjectExportService::class.java)
                .setAction(ProjectExportService.ACTION_START)
                .putExtra(ProjectExportService.EXTRA_PROJECT_ID, projectId)
            ContextCompat.startForegroundService(container.app, intent)
            return true
        } catch (error: Exception) {
            runCatching { journal.clear() }
            _state.value = State.Failed(projectId, error.message ?: error.javaClass.simpleName)
            return false
        }
    }

    /** Invoked ONLY by the foreground Service; never by the ViewModel. */
    fun runInService(projectId: String, scope: CoroutineScope) {
        val current = _state.value as? State.Running ?: return
        if (current.projectId != projectId || job?.isActive == true) return
        cancelRequested = false
        job = scope.launch { execute(projectId) }
    }

    fun cancel() {
        if (_state.value !is State.Running) return
        cancelRequested = true
        exporter.cancel()
        val running = job
        if (running == null) {
            runCatching { journal.clear() }
            _state.value = State.Idle
        } else {
            running.cancel()
        }
    }

    fun dismiss() {
        if (_state.value !is State.Running) _state.value = State.Idle
    }

    /** Unexpected Service teardown must not leave the UI reporting "Running". */
    fun onServiceDestroyed() {
        val running = _state.value as? State.Running ?: return
        exporter.cancel()
        job?.cancel()
        _state.value = State.Failed(running.projectId,
            "Export service stopped unexpectedly. Please retry.")
        runCatching { journal.clear() }
    }

    private fun failed(projectId: String, message: String) {
        _state.value = State.Failed(projectId, message)
        runCatching { journal.clear() }
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

    private suspend fun execute(projectId: String) {
        var tmp: File? = null
        try {
            val project = container.projectStore.project(projectId)
                ?: throw IllegalStateException("Project was deleted before export")
            val timeline = project.timeline
                ?: throw IllegalStateException("Project timeline is missing")
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
            val stamp = System.currentTimeMillis()
            val target = container.historyRepository.newMediaFile(
                "project-export-" + stamp, 0, "mp4")
            val temporary = File(target.parentFile, target.name + ".tmp")
            tmp = temporary
            val result = exporter.export(plan, temporary) { progress ->
                _state.value = State.Running(projectId, progress)
            }
            when (result) {
                is VideoExporter.Result.Cancelled -> {
                    temporary.delete()
                    runCatching { journal.clear() }
                    _state.value = State.Idle
                }
                is VideoExporter.Result.Success -> {
                    // Atomic same-directory commit; no partial final .mp4.
                    if (!temporary.renameTo(target)) {
                        throw IllegalStateException(AppStrings.get(R.string.proj_export_commit_failed))
                    }
                    try {
                        container.historyRepository.add(
                            HistoryItem(
                                id = "project-export-" + project.id + "-" + stamp,
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
                    runCatching { journal.clear() }
                    _state.value = State.Succeeded(projectId, target.absolutePath)
                }
            }
        } catch (cancelled: CancellationException) {
            tmp?.delete()
            runCatching { journal.clear() }
            _state.value = if (cancelRequested) State.Idle else State.Failed(
                projectId, "Export was interrupted; the original media is intact. Retry when ready.")
        } catch (error: Exception) {
            tmp?.delete()
            failed(projectId, error.message ?: error.javaClass.simpleName)
        } finally {
            job = null
        }
    }
}

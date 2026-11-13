package com.comfyremote.app.feature.project

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comfyremote.app.AppContainer
import com.comfyremote.app.AiImagePayload
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.LabeledText
import com.comfyremote.app.data.cloud.AutoDlConfig
import com.comfyremote.app.data.media.FrameStrip
import com.comfyremote.app.data.media.ContinuityPaging
import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationTask
import com.comfyremote.app.data.project.AssetProfile
import com.comfyremote.app.data.project.ProjectArchive
import com.comfyremote.app.data.project.ExportPlanBuilder
import com.comfyremote.app.data.project.ProfileKind
import com.comfyremote.app.data.project.ProjectShot
import com.comfyremote.app.data.project.StudioStagePreflight
import com.comfyremote.app.data.project.StudioStageMediaBindings
import com.comfyremote.app.data.project.TimelineClip
import com.comfyremote.app.data.project.TrackKind
import com.comfyremote.app.data.project.VideoExporter
import com.comfyremote.app.data.project.VideoProject
import com.comfyremote.app.data.project.isOrphanedRunningShot
import com.comfyremote.app.feature.project.DirectorPrompts
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.feature.generate.isVideoPath
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream

data class ProjectWorkflowOption(
    val path: String,
    val workflowId: String,
    val name: String,
    val providerId: String,
    val providerLabel: String,
    val kind: String,
)

/** Studio-side ViewModel: projects, shots, assets and generation-task sync. */
class ProjectViewModel(private val container: AppContainer) : ViewModel() {
    val projects: StateFlow<List<VideoProject>> = container.projectStore.projects
    val projectLoadError: StateFlow<String?> = container.projectStore.loadError
    fun setProjectArchived(projectId: String, archived: Boolean): Boolean {
        val ok = container.projectStore.setArchived(projectId, archived)
        if (!ok) _message.value = AppStrings.get(R.string.board_err_persist)
        return ok
    }

    fun duplicateProject(projectId: String): VideoProject? {
        val duplicate = container.projectStore.duplicateProject(projectId)
        if (duplicate == null) _message.value = AppStrings.get(R.string.board_err_persist)
        return duplicate
    }

    private val projectArchive by lazy {
        ProjectArchive(
            container.projectStore,
            File(container.app.filesDir, "storyboards/assets"),
            listOfNotNull(
                container.app.filesDir,
                container.app.cacheDir,
                container.app.getExternalFilesDir(null),
                container.app.externalCacheDir,
            ),
        )
    }
    private var archiveJob: Job? = null

    /** System document picker supplies a user-chosen destination, never app-owned media. */
    fun exportProjectPackage(projectId: String, uri: Uri) {
        if (archiveJob?.isActive == true) {
            _message.value = AppStrings.get(R.string.proj_package_busy)
            return
        }
        archiveJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.app.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        projectArchive.export(projectId, output)
                    } ?: throw java.io.IOException("Destination could not be opened")
                }
                _message.value = AppStrings.get(R.string.proj_package_exported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = AppStrings.get(R.string.proj_package_failed) + ": " +
                    (error.message ?: error.javaClass.simpleName)
            }
        }
    }

    /** Import is a detached snapshot: no remote paid task is resumed or submitted. */
    fun importProjectPackage(uri: Uri) {
        if (archiveJob?.isActive == true) {
            _message.value = AppStrings.get(R.string.proj_package_busy)
            return
        }
        archiveJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    container.app.contentResolver.openInputStream(uri)?.use { input ->
                        projectArchive.importSnapshot(input)
                    } ?: throw java.io.IOException("Archive could not be opened")
                }
                _message.value = AppStrings.get(R.string.proj_package_imported)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = AppStrings.get(R.string.proj_package_failed) + ": " +
                    (error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun availableProjectBackups() = container.projectStore.availableBackups()

    fun restoreProjectBackup(filename: String): Boolean {
        val restored = container.projectStore.restoreBackup(filename)
        _message.value = AppStrings.get(
            if (restored) R.string.proj_backup_restored else R.string.proj_backup_failed,
        )
        return restored
    }


    /** Read-only engine feed for the in-project task view; submission stays on the create page. */
    val tasks: StateFlow<List<GenerationTask>> = container.generationManager.tasks
    val cloudSpend = container.cloudSpendStore.entries
    private val _workflows = MutableStateFlow<List<ProjectWorkflowOption>>(emptyList())
    val workflows: StateFlow<List<ProjectWorkflowOption>> = _workflows.asStateFlow()
    private val _stageWorkflows = MutableStateFlow<List<ProjectWorkflowOption>>(emptyList())
    val stageWorkflows: StateFlow<List<ProjectWorkflowOption>> = _stageWorkflows.asStateFlow()

    data class StageInspection(
        val projectId: String = "",
        val workflowPath: String = "",
        val kind: String = "",
        val loading: Boolean = false,
        val report: StudioStagePreflight.Report? = null,
        val error: String? = null,
    )

    private val _stageInspection = MutableStateFlow(StageInspection())
    val stageInspection: StateFlow<StageInspection> = _stageInspection.asStateFlow()
    private val stageInspectionVersion = AtomicLong(0)
    private var stageInspectionJob: Job? = null

    /** Inspect only real provider detail metadata. Never submits or uploads media. */
    fun inspectStage(projectId: String, kind: String, workflowPath: String) {
        val project = container.projectStore.project(projectId) ?: return
        val option = _stageWorkflows.value.firstOrNull {
            it.kind == kind && it.path == workflowPath
        } ?: return
        val current = stageInspectionVersion.incrementAndGet()
        stageInspectionJob?.cancel()
        val provider = container.cloudProviders.forPath(option.path)
        if (provider == null || !provider.configured() ||
            !container.cloudProviders.isEnabled(provider.id)) {
            _stageInspection.value = StageInspection(
                projectId = projectId,
                workflowPath = workflowPath,
                kind = kind,
                error = "Provider is not configured or is disabled",
            )
            return
        }
        _stageInspection.value = StageInspection(
            projectId = projectId,
            workflowPath = workflowPath,
            kind = kind,
            loading = true,
        )
        stageInspectionJob = viewModelScope.launch {
            val outcome = try {
                Result.success(withContext(Dispatchers.IO) {
                    StudioStagePreflight.inspect(
                        provider.detail(option.workflowId),
                        expectedKind = kind,
                        projectAssets = project.assets,
                    )
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (!isActive || stageInspectionVersion.get() != current) return@launch
            _stageInspection.value = StageInspection(
                projectId = projectId,
                workflowPath = workflowPath,
                kind = kind,
                report = outcome.getOrNull(),
                error = outcome.exceptionOrNull()?.message ?: outcome.exceptionOrNull()?.javaClass?.simpleName,
            )
        }
    }
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    data class AssetImportProgress(val running: Boolean = false, val copiedBytes: Long = 0, val totalBytes: Long? = null)
    private val _assetImport = MutableStateFlow(AssetImportProgress())
    val assetImport: StateFlow<AssetImportProgress> = _assetImport.asStateFlow()
    private var assetImportJob: Job? = null
    private val syncing = mutableSetOf<String>()

    private companion object {
        const val STRIP_FRAMES = 5
        const val CONTINUITY_PAGE_SHOTS = 16
    }

    /** Providers whose catalogue loaded at least once; lets us flag removed workflows. */
    private val catalogReadyProviders = mutableSetOf<String>()
    private val catalogRefreshVersion = AtomicLong(0L)
    private var catalogRefreshJob: Job? = null

    init {
        refreshWorkflows()
        migrateLegacyTailFrames()
        viewModelScope.launch {
            container.projectStore.persistenceError.collect { error ->
                if (error != null) {
                    _message.value = error
                    container.projectStore.clearPersistenceError()
                }
            }
        }
        viewModelScope.launch {
            container.generationManager.tasks.collect { tasks -> syncTasks(tasks) }
        }
        viewModelScope.launch {
            var first = true
            container.cloudProviderSettingsStore.revision.collect {
                if (first) {
                    first = false
                    return@collect
                }
                refreshWorkflows()
            }
        }
    }

    fun refreshWorkflows() {
        // Earlier catalogue calls may finish after a later provider/settings change.
        // Cancel the previous job AND verify a monotonic generation before publishing.
        val version = catalogRefreshVersion.incrementAndGet()
        catalogRefreshJob?.cancel()
        catalogRefreshJob = viewModelScope.launch {
            val (options, errors, ready) = withContext(Dispatchers.IO) {
                val options = mutableListOf<ProjectWorkflowOption>()
                val errors = mutableListOf<String>()
                val ready = mutableSetOf<String>()
                container.cloudProviders.enabled.forEach { provider ->
                    // An unconfigured provider cannot contribute usable shot models.
                    if (!provider.configured()) return@forEach
                    runCatching { provider.catalog(force = false) }
                        .onSuccess { list ->
                            ready += provider.id
                            options += list.filter { it.kind in setOf("image", "video", "audio") }.map { summary ->
                                ProjectWorkflowOption(
                                    path = provider.pathPrefix + summary.id,
                                    workflowId = summary.id,
                                    name = summary.name,
                                    providerId = provider.id,
                                    providerLabel = provider.label,
                                    kind = summary.kind,
                                )
                            }
                        }
                        .onFailure { error ->
                            errors += "${provider.label}: ${
                                error.message?.takeIf(String::isNotBlank)
                                    ?: error.javaClass.simpleName
                            }"
                        }
                }
                Triple(options, errors, ready)
            }
            if (!isActive || version != catalogRefreshVersion.get()) return@launch
            catalogReadyProviders.clear()
            catalogReadyProviders += ready
            val sorted = options.distinctBy { it.path }.sortedWith(
                compareBy<ProjectWorkflowOption> { it.providerLabel.lowercase() }
                    .thenBy { it.name.lowercase() }
            )
            _stageWorkflows.value = sorted
            _workflows.value = sorted.filter { it.kind == "video" }
            if (options.isEmpty() && errors.isNotEmpty()) _message.value = errors.first()
        }
    }

    fun setWorkflowRecipe(projectId: String, recipeId: String) {
        if (!container.projectStore.setWorkflowRecipe(projectId, recipeId)) {
            _message.value = AppStrings.get(R.string.board_err_persist)
        }
    }

    fun setStageWorkflow(projectId: String, kind: String, workflowPath: String) {
        val option = _stageWorkflows.value.firstOrNull { it.path == workflowPath && it.kind == kind }
        if (option == null) {
            _message.value = AppStrings.get(R.string.board_err_choose_workflow)
            return
        }
        if (!container.projectStore.setStageWorkflow(projectId, kind, workflowPath)) {
            _message.value = AppStrings.get(R.string.board_err_persist)
        }
    }

    /** Only bind an existing project asset to an actual, just-inspected image/audio slot. */
    fun setStageMediaBinding(
        projectId: String,
        kind: String,
        workflowPath: String,
        fieldName: String,
        assetId: String?,
    ) {
        val project = container.projectStore.project(projectId) ?: return
        val chosenPath = project.stageWorkflowPaths[kind]
            ?.takeIf(String::isNotBlank) ?: if (kind == "video") project.workflowPath else ""
        if (chosenPath != workflowPath) return
        if (assetId != null) {
            val verified = _stageInspection.value.takeIf {
                it.projectId == projectId && it.workflowPath == workflowPath && it.kind == kind &&
                    it.report?.support == StudioStagePreflight.Support.CONFIGURABLE
            }?.report
            val input = verified?.inputs?.firstOrNull { it.field == fieldName }
            val asset = project.assets.firstOrNull { it.id == assetId }
            if (input == null || asset == null || !StudioStageMediaBindings.accepts(input, asset)) {
                _message.value = AppStrings.get(R.string.studio_binding_wrong_type)
                return
            }
        }
        if (!container.projectStore.setStageMediaBinding(projectId, workflowPath, fieldName, assetId)) {
            _message.value = AppStrings.get(R.string.board_err_persist)
        }
    }

    /** Open an independently submitted image/video/audio stage with a persistent project id. */
    fun openStage(projectId: String, kind: String): Pair<String, String>? {
        val project = container.projectStore.project(projectId) ?: return null
        if (!com.comfyremote.app.data.project.StudioWorkflowRecipes.isModelKind(kind)) return null
        val path = project.stageWorkflowPaths[kind]?.takeIf(String::isNotBlank)
            ?: if (kind == "video") project.workflowPath else ""
        val option = _stageWorkflows.value.firstOrNull { it.kind == kind && it.path == path }
        if (option == null) {
            _message.value = AppStrings.get(R.string.board_err_choose_workflow)
            return null
        }
        val provider = container.cloudProviders.forPath(path)
        if (provider == null || !provider.configured()) {
            _message.value = AppStrings.get(R.string.board_err_choose_workflow)
            return null
        }
        val bindings = project.stageMediaBindings.filter { it.workflowPath == path }
        val fieldPaths = if (bindings.isNotEmpty()) {
            val report = _stageInspection.value.takeIf {
                it.projectId == projectId && it.kind == kind && it.workflowPath == path &&
                    it.report?.support == StudioStagePreflight.Support.CONFIGURABLE
            }?.report
            if (report == null) {
                _message.value = AppStrings.get(R.string.studio_binding_reinspect)
                return null
            }
            StudioStageMediaBindings.resolve(
                path,
                report,
                bindings,
                project.assets,
                readable = { media -> File(media).let { it.isFile && it.canRead() } },
            ) ?: run {
                _message.value = AppStrings.get(R.string.studio_binding_stale)
                return null
            }
        } else emptyMap()
        val name = "${project.title} · ${option.name}"
        container.draftTransferRepository.put(DraftTransfer(
            workflowPath = path,
            workflowName = name,
            // Only inherit field-keyed defaults when the actual workflow matches.
            // Cross-provider keys must be re-entered through the validated Create form.
            values = if (kind == "video" && path == project.workflowPath) project.defaultValues
                else emptyMap(),
            randomSeed = true,
            storyboardProjectId = projectId,
            storyboardShotId = null,
            referenceFieldPaths = fieldPaths,
        ))
        return path to name
    }

    fun createProject(
        title: String,
        workflowPath: String,
        stylePrompt: String,
        synopsis: String = "",
        chainFrames: Boolean = true,
    ): VideoProject? {
        val option = _workflows.value.firstOrNull { it.path == workflowPath }
        if (workflowPath.isNotBlank() && option == null) {
            _message.value = AppStrings.get(R.string.board_err_choose_workflow)
            return null
        }
        return container.projectStore.create(
            title = title,
            chainFrames = chainFrames,
            workflowId = option?.workflowId.orEmpty(),
            stylePrompt = stylePrompt,
            synopsis = synopsis,
            workflowPath = option?.path.orEmpty(),
            providerId = option?.providerId.orEmpty(),
        ).also { created ->
            if (created == null) _message.value = AppStrings.get(R.string.board_err_persist)
        }
    }

    fun deleteProject(id: String): Boolean {
        val project = container.projectStore.project(id) ?: return false
        if (project.shots.any(::shotRunning)) {
            _message.value = AppStrings.get(R.string.board_err_delete_running_project)
            return false
        }
        return container.projectStore.delete(id)
    }

    fun setStyle(projectId: String, style: String) = container.projectStore.setStyle(projectId, style)

    fun setChainFrames(projectId: String, enabled: Boolean) =
        container.projectStore.setChainFrames(projectId, enabled)

    fun updateProjectSettings(
        projectId: String,
        title: String,
        synopsis: String,
        workflowPath: String,
        aspectRatio: String,
        defaultValues: Map<String, String>,
        targetDurationSeconds: Int?,
        budget: Double?,
    ) {
        val providerId = container.cloudProviders.forPath(workflowPath)?.id.orEmpty()
        container.projectStore.updateSettings(
            projectId, title, synopsis, workflowPath, providerId, aspectRatio, defaultValues,
            targetDurationSeconds = targetDurationSeconds,
            budget = budget,
            updateBudget = true,
        )
    }

    fun addShot(projectId: String, prompt: String, cameraNotes: String? = null) {
        if (prompt.isBlank()) {
            _message.value = AppStrings.get(R.string.board_err_empty_prompt)
            return
        }
        container.projectStore.addShot(projectId, prompt, cameraNotes)
    }

    fun deleteShot(projectId: String, shotId: String) {
        val shot = container.projectStore.project(projectId)
            ?.shots?.firstOrNull { it.id == shotId } ?: return
        if (shotRunning(shot)) {
            _message.value = AppStrings.get(R.string.board_err_delete_running_shot)
            return
        }
        container.projectStore.deleteShot(projectId, shotId)
    }

    private fun shotRunning(shot: ProjectShot): Boolean =
        shot.status == "running" || shot.taskId?.let { taskId ->
            container.generationManager.tasks.value.firstOrNull { it.taskId == taskId }?.isActive
        } == true

    fun updateShot(projectId: String, shotId: String, prompt: String) =
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(prompt = prompt.trim())
        }

    fun updateShotCameraNotes(projectId: String, shotId: String, notes: String) =
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(cameraNotes = notes.trim().ifBlank { null })
        }

    /**
     * Ordered-reference helpers: the submission maps references to workflow
     * image fields by order, so "first frame" inserts at the head and "last
     * frame" appends at the tail.
     */
    fun setShotFrameAsset(projectId: String, shotId: String, assetId: String, first: Boolean) {
        val project = container.projectStore.project(projectId) ?: return
        if (project.assets.none { it.id == assetId }) return
        container.projectStore.updateShot(projectId, shotId) { shot ->
            val ids = (shot.referenceAssetIds - assetId).toMutableList()
            if (first) ids.add(0, assetId) else ids.add(assetId)
            val assets = ids.mapNotNull { id -> project.assets.firstOrNull { it.id == id } }
            shot.copy(
                referenceAssetIds = ids,
                referencePath = assets.firstOrNull()?.path,
                referenceManual = true,
                referenceRole = assets.firstOrNull()?.role ?: ReferenceRole.AUTO,
            )
        }
    }

    /** Promotes a finished take and rebuilds the shot's tail-frame artifacts. */
    fun switchToTake(projectId: String, shotId: String, takeId: String) {
        val project = container.projectStore.project(projectId) ?: return
        val shot = project.shots.firstOrNull { it.id == shotId } ?: return
        val take = shot.takes.firstOrNull { it.id == takeId } ?: return
        if (!container.projectStore.selectTake(projectId, shotId, takeId)) return
        val resultPath = take.resultPath ?: return
        // Each switch can complete out of order; only the currently selected take may write back.
        viewModelScope.launch {
            val index = project.shots.indexOfFirst { it.id == shotId }
            val lastFrame = extractLastFrame(shotId, resultPath) ?: return@launch
            fun isCurrent(): Boolean = container.projectStore.project(projectId)
                ?.shots?.firstOrNull { it.id == shotId }
                ?.let { it.selectedTakeId == takeId && it.resultPath == resultPath } == true
            if (!isCurrent()) return@launch
            if (!container.projectStore.updateShot(projectId, shotId) { current ->
                    if (current.selectedTakeId == takeId && current.resultPath == resultPath) {
                        current.copy(lastFramePath = lastFrame, tailFrameStored = false)
                    } else current
                }) return@launch
            if (!isCurrent()) return@launch
            val fresh = container.projectStore.project(projectId)
                ?.shots?.firstOrNull { it.id == shotId } ?: return@launch
            if (addTailFrameArtifacts(project, fresh, index, resultPath, lastFrame) && isCurrent()) {
                markTailFrameStored(projectId, shotId)
            }
        }
    }

    fun setTakeFavorite(projectId: String, shotId: String, takeId: String, favorite: Boolean) {
        container.projectStore.setTakeFavorite(projectId, shotId, takeId, favorite)
    }

    fun discardTake(projectId: String, shotId: String, takeId: String) {
        container.projectStore.discardTake(projectId, shotId, takeId)
    }

    fun deleteTake(projectId: String, shotId: String, takeId: String) {
        if (!container.projectStore.deleteTake(projectId, shotId, takeId)) {
            _message.value = AppStrings.get(R.string.board_err_delete_running_shot)
            return
        }
        val selected = container.projectStore.project(projectId)
            ?.shots?.firstOrNull { it.id == shotId }?.selectedTakeId
        if (selected != null) switchToTake(projectId, shotId, selected)
    }

    fun moveShot(projectId: String, shotId: String, delta: Int) =
        container.projectStore.moveShot(projectId, shotId, delta)

    fun duplicateShot(projectId: String, shotId: String) =
        container.projectStore.duplicateShot(projectId, shotId)

    fun insertShotAfter(projectId: String, shotId: String, prompt: String, cameraNotes: String? = null) =
        container.projectStore.insertShotAfter(projectId, shotId, prompt, cameraNotes)

    fun assignAssetsToShot(projectId: String, shotId: String, assetIds: List<String>) {
        val project = container.projectStore.project(projectId) ?: return
        val assets = assetIds.mapNotNull { id -> project.assets.firstOrNull { it.id == id } }
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(
                referenceAssetIds = assetIds,
                referencePath = assets.firstOrNull()?.path,
                referenceManual = true,
                referenceRole = assets.firstOrNull()?.role ?: ReferenceRole.AUTO,
            )
        }
    }

    fun clearShotReference(projectId: String, shotId: String) =
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(
                referencePath = null,
                referenceAssetIds = emptyList(),
                referenceManual = true,
                referenceRole = ReferenceRole.AUTO,
            )
        }

    /**
     * Sets a provider workflow for one shot. A null path restores the project default.
     * Cached parameter values belong to the previous workflow, so they are dropped.
     */
    fun setShotWorkflow(projectId: String, shotId: String, workflowPath: String?) {
        val shot = shot(projectId, shotId) ?: return
        if (shot.workflowPath == workflowPath?.takeIf { it.isNotBlank() }) return
        if (workflowPath.isNullOrBlank()) {
            container.projectStore.updateShot(projectId, shotId) {
                it.copy(workflowId = null, workflowPath = null, providerId = null, values = emptyMap())
            }
            return
        }
        val option = _workflows.value.firstOrNull { it.path == workflowPath }
        if (option == null) {
            _message.value = AppStrings.get(R.string.board_err_choose_workflow)
            return
        }
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(
                workflowId = option.workflowId,
                workflowPath = option.path,
                providerId = option.providerId,
                values = emptyMap(),
            )
        }
    }

    /** Copies a picked image into project-owned storage so it survives gallery changes. */
    fun addAsset(projectId: String, role: ReferenceRole, uri: Uri) {
        viewModelScope.launch {
            runCatching { container.projectAssetStore.importUri(uri, role) }
                .onSuccess { asset -> container.projectStore.addAsset(projectId, asset) }
                .onFailure { e ->
                    _message.value = AppStrings.get(R.string.board_err_add_asset, e.message ?: "error")
                }
        }
    }

    /** Library import: bounded streaming copy with progress and cancellation. */
    fun importAsset(projectId: String, role: ReferenceRole, uri: Uri) {
        if (assetImportJob?.isActive == true) return
        assetImportJob = viewModelScope.launch {
            _assetImport.value = AssetImportProgress(running = true)
            try {
                val asset = container.projectAssetStore.importUri(uri, role) { copied, total ->
                    _assetImport.value = AssetImportProgress(true, copied, total)
                }
                if (!container.projectStore.addAsset(projectId, asset)) {
                    // The file must not be leaked if the project index cannot be written.
                    File(asset.path).delete()
                    _message.value = AppStrings.get(R.string.board_err_persist)
                }
            } catch (cancelled: CancellationException) {
                // The import store removes its .tmp file; cancellation is user-initiated.
            } catch (e: Exception) {
                _message.value = AppStrings.get(R.string.board_err_add_asset, e.message ?: "error")
            } finally {
                _assetImport.value = AssetImportProgress()
            }
        }
    }

    fun cancelAssetImport() { assetImportJob?.cancel() }

    /** Label/tag edits upsert the asset in place; the media file is untouched. */
    fun updateAsset(projectId: String, asset: ProjectAsset) {
        container.projectStore.addAsset(projectId, asset)
    }

    fun removeAsset(projectId: String, assetId: String) {
        container.projectStore.removeAsset(projectId, assetId)
    }

    fun createProfile(
        projectId: String,
        name: String,
        summary: String,
        kind: ProfileKind,
        assetIds: List<String>,
    ) {
        if (name.isBlank()) return
        container.projectStore.addProfile(
            projectId,
            AssetProfile(name = name, summary = summary, kind = kind, assetIds = assetIds),
        )
    }

    fun updateProfile(projectId: String, profile: AssetProfile) {
        container.projectStore.addProfile(projectId, profile)
    }

    fun deleteProfile(projectId: String, profileId: String) {
        container.projectStore.deleteProfile(projectId, profileId)
    }

    /** Assigning a profile expands its assets into the shot's ordered references. */
    fun applyProfileToShot(projectId: String, shotId: String, profileId: String) {
        val project = container.projectStore.project(projectId) ?: return
        val profile = project.profiles.firstOrNull { it.id == profileId } ?: return
        val known = profile.assetIds.filter { id -> project.assets.any { it.id == id } }
        if (known.isEmpty()) return
        assignAssetsToShot(projectId, shotId, known)
    }

    fun project(projectId: String): VideoProject? =
        projects.value.firstOrNull { it.id == projectId }

    fun shot(projectId: String, shotId: String): ProjectShot? =
        project(projectId)?.shots?.firstOrNull { it.id == shotId }

    /**
     * Prepares a cloud-video draft for one shot. The previous shot's last frame
     * is used as this shot's reference so the sequence stays visually coherent.
     */
    fun openShot(projectId: String, shotId: String): Pair<String, String>? {
        val project = project(projectId) ?: return null
        val index = project.shots.indexOfFirst { it.id == shotId }
        if (index < 0) return null
        val shot = project.shots[index]
        if (shotRunning(shot)) return null
        val previous = project.shots.getOrNull(index - 1)
        val assetReferences = shot.referenceAssetIds.mapNotNull { id ->
            project.assets.firstOrNull { it.id == id }?.path
        }
        val reference = if (shot.referenceManual) shot.referencePath
        else shot.referencePath ?: if (project.chainFrames) previous?.lastFramePath else null
        val prompt = listOf(project.stylePrompt, shot.prompt, shot.cameraNotes)
            .filterNotNull()
            .filter(String::isNotBlank)
            .joinToString(", ")
        val name = "${project.title} · ${index + 1}"
        val workflowPath = shot.workflowPath?.takeIf { it.isNotBlank() }
            ?: project.workflowPath.takeIf { it.isNotBlank() }
            ?: AutoDlConfig.PATH_PREFIX + (shot.workflowId ?: project.workflowId)
        val provider = container.cloudProviders.forPath(workflowPath)
        if (provider == null) {
            _message.value = AppStrings.get(R.string.board_err_unknown_provider, workflowPath)
            return null
        }
        if (!provider.configured()) {
            _message.value = AppStrings.get(R.string.board_err_provider_not_configured, provider.label)
            return null
        }
        if (provider.id in catalogReadyProviders && _workflows.value.none { it.path == workflowPath }) {
            _message.value = AppStrings.get(R.string.board_err_workflow_missing, workflowPath)
            return null
        }
        val values = project.defaultValues + shot.values +
            (if (prompt.isBlank()) emptyMap() else mapOf("prompt" to prompt))
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(status = if (it.status == "running") it.status else "draft", error = null)
        }
        container.draftTransferRepository.put(
            DraftTransfer(
                workflowPath = workflowPath,
                workflowName = name,
                values = values,
                randomSeed = true,
                serverUrl = null,
                referenceLocalPath = if (assetReferences.isEmpty()) reference else null,
                referenceLocalPaths = assetReferences,
                storyboardProjectId = project.id,
                storyboardShotId = shot.id,
            )
        )
        return workflowPath to name
    }

    fun nextShot(projectId: String): ProjectShot? = container.projectStore.nextShot(projectId)

    fun setReference(projectId: String, shotId: String, path: String?, manual: Boolean = true) =
        container.projectStore.setReference(projectId, shotId, path, manual)

    /** Explicitly reuses the previous shot's tail frame, even when auto relay is off. */
    fun usePreviousFrame(projectId: String, shotId: String, path: String?) {
        if (path.isNullOrBlank()) return
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(
                referencePath = path,
                referenceAssetIds = emptyList(),
                // This is a deliberate choice, so later auto relinking must not replace it.
                referenceManual = true,
                referenceRole = ReferenceRole.AUTO,
            )
        }
    }

    fun clearMessage() { _message.value = null }

    // ---- Timeline ----

    fun ensureTimeline(projectId: String) {
        container.projectStore.ensureTimeline(projectId)
    }

    /** Adds a full-length clip for [sourcePath]; duration is read on IO. */
    fun addTimelineClip(projectId: String, kind: TrackKind, sourcePath: String, sourceLabel: String) {
        viewModelScope.launch {
            val duration = withContext(Dispatchers.IO) { mediaDurationMs(sourcePath) }
            if (duration == null || duration <= 0L) {
                _message.value = AppStrings.get(R.string.proj_timeline_unreadable)
                return@launch
            }
            container.projectStore.addClip(
                projectId,
                kind,
                TimelineClip(sourcePath = sourcePath, sourceLabel = sourceLabel, sourceOutMs = duration),
            )
        }
    }

    fun updateTimelineClip(
        projectId: String,
        kind: TrackKind,
        clipId: String,
        sourceInMs: Long,
        sourceOutMs: Long,
        speed: Float,
        volume: Float,
        muted: Boolean,
    ) {
        container.projectStore.updateClip(projectId, kind, clipId) {
            it.copy(
                sourceInMs = sourceInMs.coerceAtLeast(0L),
                sourceOutMs = sourceOutMs.coerceAtLeast(sourceInMs.coerceAtLeast(0L) + 1),
                speed = speed.coerceIn(0.25f, 4f),
                volume = volume.coerceIn(0f, 2f),
                muted = muted,
            )
        }
    }

    fun moveTimelineClip(projectId: String, kind: TrackKind, clipId: String, delta: Int) {
        container.projectStore.moveClip(projectId, kind, clipId, delta)
    }

    fun removeTimelineClip(projectId: String, kind: TrackKind, clipId: String) {
        container.projectStore.removeClip(projectId, kind, clipId)
    }

    /** Best-effort media duration in ms; null when the file cannot be probed. */
    private fun mediaDurationMs(path: String): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    // ---- MP4 export ----

    sealed interface ExportState {
        data object Idle : ExportState
        data class Running(val progress: Float) : ExportState
        data class Succeeded(val outputPath: String) : ExportState
        data class Failed(val message: String) : ExportState
    }

    private val exporter by lazy { VideoExporter(container.app) }
    private var exportJob: Job? = null
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    /**
     * Exports the video track and its parallel audio overlay to MP4, then
     * registers the verified result in History.
     * Export runs in the ViewModel scope: leaving the app kills it (the tmp
     * file never becomes a finished result); the user retries manually.
     */
    fun startExport(projectId: String) {
        if (_exportState.value is ExportState.Running || exportJob?.isActive == true) return
        val project = container.projectStore.project(projectId) ?: return
        val timeline = project.timeline
        if (timeline == null || timeline.videoTrack.isEmpty()) {
            _exportState.value = ExportState.Failed(AppStrings.get(R.string.proj_export_no_clips))
            return
        }
        exportJob = viewModelScope.launch {
            val available = withContext(Dispatchers.IO) {
                (timeline.videoTrack + timeline.audioTrack)
                    .map { it.sourcePath }.filter { File(it).isFile }.toSet()
            }
            val missing = (timeline.videoTrack + timeline.audioTrack)
                .filter { it.sourcePath !in available }
            if (missing.isNotEmpty()) {
                _exportState.value = ExportState.Failed(AppStrings.get(
                    R.string.proj_export_skipped,
                    missing.joinToString(", ") { it.sourceLabel.ifBlank { it.sourcePath.substringAfterLast('/') } },
                ))
                return@launch
            }
            // Validate actual media metadata before invoking the encoder. An existing file
            // may be corrupt or have a clip out-point past its playable duration.
            val durationByPath = withContext(Dispatchers.IO) {
                available.associateWith { mediaDurationMs(it) }
            }
            val unreadable = durationByPath.filterValues { it == null || it <= 0L }.keys
            if (unreadable.isNotEmpty()) {
                _exportState.value = ExportState.Failed(AppStrings.get(
                    R.string.proj_export_unreadable,
                    unreadable.joinToString(", ") { it.substringAfterLast('/') },
                ))
                return@launch
            }
            val plan = ExportPlanBuilder.build(
                timeline, available, durationByPath.mapValues { it.value!! },
            )
            if (plan.invalidTrims.isNotEmpty()) {
                _exportState.value = ExportState.Failed(AppStrings.get(
                    R.string.proj_export_invalid_trim, plan.invalidTrims.joinToString(", "),
                ))
                return@launch
            }
            if (plan.isEmpty) {
                _exportState.value = ExportState.Failed(AppStrings.get(R.string.proj_export_no_clips))
                return@launch
            }
            _exportState.value = ExportState.Running(0f)
            val stamp = System.currentTimeMillis()
            val target = container.historyRepository.newMediaFile("project-export-$stamp", 0, "mp4")
            val tmp = File(target.parentFile, target.name + ".tmp")
            runCatching {
                exporter.export(plan, tmp) { progress ->
                    _exportState.value = ExportState.Running(progress)
                }
            }.onSuccess { result ->
                when (result) {
                    is VideoExporter.Result.Cancelled -> {
                        tmp.delete()
                        _exportState.value = ExportState.Idle
                    }
                    is VideoExporter.Result.Success -> {
                        // Same-directory rename is atomic; never copy directly into a
                        // final .mp4 name, because an interrupted copy leaves a partial movie.
                        if (!tmp.renameTo(target)) {
                            tmp.delete()
                            _exportState.value = ExportState.Failed(
                                AppStrings.get(R.string.proj_export_commit_failed),
                            )
                            return@onSuccess
                        }
                        val saved = runCatching { container.historyRepository.add(
                            HistoryItem(
                                id = "project-export-${project.id}-$stamp",
                                wfPath = project.workflowPath,
                                wfName = AppStrings.get(R.string.proj_export_history_name, project.title),
                                time = System.currentTimeMillis(),
                                values = emptyMap(),
                                fields = emptyList(),
                                randomSeed = false,
                                images = listOf(target.absolutePath),
                                summary = project.title.take(50),
                                prompts = emptyList(),
                                seeds = "",
                                serverUrl = "",
                                tags = listOf("project-export"),
                            ))
                        }
                        saved.onSuccess {
                            _exportState.value = ExportState.Succeeded(target.absolutePath)
                        }.onFailure { error ->
                            target.delete()
                            _exportState.value = ExportState.Failed(error.message ?: error.javaClass.simpleName)
                        }
                    }
                }
            }.onFailure { error ->
                tmp.delete()
                _exportState.value = if (error is CancellationException) ExportState.Idle
                    else ExportState.Failed(error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun cancelExport() {
        exporter.cancel()
        // Media3 cancellation may not invoke the completion listener; cancelling
        // the coroutine resumes the cancellable continuation and unblocks the UI.
        exportJob?.cancel()
    }

    fun dismissExport() {
        if (_exportState.value !is ExportState.Running) _exportState.value = ExportState.Idle
    }

    fun saveExportToGallery(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.galleryExporter.saveMediaFile(path) }
                .onSuccess { _message.value = AppStrings.get(R.string.gallery_saved) }
                .onFailure { _message.value = AppStrings.get(R.string.gallery_save_failed) }
        }
    }

    // ---- Continuity strips (P3-2) ----

    data class ShotStrip(
        val shotId: String,
        val index: Int,
        val label: String,
        val durationMs: Long,
        val frames: List<Bitmap>,
    )

    data class SeamCheck(
        val fromShotId: String,
        val toShotId: String,
        val fromLabel: String,
        val toLabel: String,
        val diff: Double,
        val frameA: Bitmap,
        val frameB: Bitmap,
    )
    data class ContinuityState(
        val strips: List<ShotStrip> = emptyList(),
        val seams: List<SeamCheck> = emptyList(),
        val loading: Boolean = false,
        val pageIndex: Int = 0,
        val pageCount: Int = 1,
    )

    private val _continuity = MutableStateFlow(ContinuityState())
    val continuity: StateFlow<ContinuityState> = _continuity.asStateFlow()

    private var continuityJob: Job? = null

    /** Replaces the previous page rather than retaining hundreds of bitmap strips. */
    fun loadContinuity(projectId: String, requestedPage: Int = 0) {
        val project = container.projectStore.project(projectId) ?: return
        continuityJob?.cancel()
        clearSeamVision()
        _continuity.value = ContinuityState(loading = true, pageIndex = requestedPage)
        continuityJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val done = project.shots.withIndex().filter { (index, shot) ->
                    val path = shot.resultPath
                    shot.status == "done" && path != null && File(path).isFile
                }
                val window = ContinuityPaging.window(done.size, requestedPage, CONTINUITY_PAGE_SHOTS)
                val visible = done.subList(window.start, window.endExclusive)
                // Include only the immediate previous shot for the page seam.
                val seamsScope = done.subList(window.seamStart, window.endExclusive)
                val strips = visible.mapNotNull { (index, shot) ->
                    val duration = mediaDurationMs(shot.resultPath!!) ?: 0L
                    val frames = FrameStrip.extractFrames(shot.resultPath, STRIP_FRAMES)
                    if (frames.isNullOrEmpty()) null
                    else ShotStrip(shot.id, index, "镜头 ${index + 1}", duration, frames)
                }
                val seams = seamsScope.zipWithNext().mapNotNull { (a, b) ->
                    val lastA = seamFrame(a.value, seamAtEnd = true) ?: return@mapNotNull null
                    val firstB = seamFrame(b.value, seamAtEnd = false) ?: return@mapNotNull null
                    val indexA = project.shots.indexOfFirst { it.id == a.value.id }
                    val indexB = project.shots.indexOfFirst { it.id == b.value.id }
                    SeamCheck(
                        a.value.id,
                        b.value.id,
                        "镜头 ${indexA + 1}",
                        "镜头 ${indexB + 1}",
                        FrameStrip.gridDistance(FrameStrip.averageGrid(lastA), FrameStrip.averageGrid(firstB)),
                        lastA,
                        firstB,
                    )
                }
                ContinuityState(strips = strips, seams = seams, loading = false,
                    pageIndex = window.page, pageCount = window.pages)
            }
            _continuity.value = result
        }
    }

    fun clearContinuity() {
        clearSeamVision()
        continuityJob?.cancel()
        continuityJob = null
        // Drop every cached Bitmap reference when leaving the director.
        _continuity.value = ContinuityState()
    }

    data class SeamVisionState(
        val projectId: String = "",
        val fromShotId: String = "",
        val toShotId: String = "",
        val loading: Boolean = false,
        val feedback: String? = null,
    )
    private val _seamVision = MutableStateFlow(SeamVisionState())
    val seamVision: StateFlow<SeamVisionState> = _seamVision.asStateFlow()
    private val seamVisionGeneration = AtomicLong(0L)
    private var seamVisionJob: Job? = null

    private fun clearSeamVision() {
        seamVisionGeneration.incrementAndGet()
        seamVisionJob?.cancel()
        seamVisionJob = null
        _seamVision.value = SeamVisionState()
    }

    /**
     * User-initiated visual cross-check. The two already-thumbnail-sized frames
     * are sent to the explicitly configured AI endpoint, never without a click.
     * Results are advice only and are not written back to project/shot data.
     */
    fun reviewSeamVision(projectId: String, fromShotId: String, toShotId: String) {
        val project = container.projectStore.project(projectId) ?: return
        val seam = _continuity.value.seams.firstOrNull {
            it.fromShotId == fromShotId && it.toShotId == toShotId
        } ?: return
        if (!container.aiSettingsStore.load().configured) {
            _message.value = AppStrings.get(R.string.gen_vm_ai_not_configured)
            return
        }
        clearSeamVision()
        val token = seamVisionGeneration.incrementAndGet()
        _seamVision.value = SeamVisionState(projectId, fromShotId, toShotId, loading = true)
        seamVisionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    fun jpeg(bitmap: Bitmap): ByteArray {
                        val buffer = ByteArrayOutputStream()
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 72, buffer)) {
                            throw IllegalStateException("Cannot encode seam thumbnail")
                        }
                        return buffer.toByteArray().also { bytes ->
                            if (bytes.size > 1_000_000) {
                                throw IllegalArgumentException("Seam thumbnail too large")
                            }
                        }
                    }
                    Result.success(container.aiRepository.visionChat(
                        systemPrompt = SeamVisionPrompts.system(),
                        userText = SeamVisionPrompts.user(
                            seam.fromLabel, seam.toLabel, seam.diff),
                        images = listOf(
                            AiImagePayload(jpeg(seam.frameA), role = ReferenceRole.END_FRAME,
                                order = 0, label = "前镜头尾帧"),
                            AiImagePayload(jpeg(seam.frameB), role = ReferenceRole.START_FRAME,
                                order = 1, label = "后镜头首帧"),
                        ),
                    ))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            if (!isActive || token != seamVisionGeneration.get() ||
                container.projectStore.project(projectId)?.updatedAt != project.updatedAt ||
                _continuity.value.seams.none {
                    it.fromShotId == fromShotId && it.toShotId == toShotId
                }) return@launch
            _seamVision.value = SeamVisionState(
                projectId, fromShotId, toShotId,
                feedback = result.fold(
                    onSuccess = SeamVisionPrompts::presentation,
                    onFailure = { "AI 复核失败：" + (it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }

    // ---- AI shot plan (P3-3, advice only) ----

    data class PlanState(
        val loading: Boolean = false,
        val suggestions: List<DirectorPrompts.SuggestedShot> = emptyList(),
        val selected: Set<Int> = emptySet(),
        val projectId: String? = null,
        val projectUpdatedAt: Long = 0L,
    )

    private val _planState = MutableStateFlow(PlanState())
    val planState: StateFlow<PlanState> = _planState.asStateFlow()
    private val planGeneration = AtomicLong(0)
    private var planJob: Job? = null

    fun togglePlanSuggestion(index: Int) {
        val current = _planState.value
        val selected = current.selected.toMutableSet()
        if (!selected.add(index)) selected.remove(index)
        _planState.value = current.copy(selected = selected)
    }

    fun selectAllPlanSuggestions(selected: Boolean) {
        val current = _planState.value
        _planState.value = current.copy(
            selected = if (selected) current.suggestions.indices.toSet() else emptySet(),
        )
    }

    fun clearPlanSuggestions() {
        planGeneration.incrementAndGet()
        planJob?.cancel()
        _planState.value = PlanState()
    }

    /** The user can correct the AI plan locally before applying any changes. */
    fun editPlanSuggestion(
        index: Int, prompt: String, cameraNotes: String, seconds: Int?,
        referenceAssetIds: List<String>? = null,
    ) {
        if (prompt.isBlank() || (seconds != null && seconds !in 1..3600)) return
        val state = _planState.value
        if (index !in state.suggestions.indices || state.loading) return
        _planState.value = state.copy(suggestions = state.suggestions.mapIndexed { i, previous ->
            if (i == index) previous.copy(
                prompt = prompt.trim(),
                cameraNotes = cameraNotes.trim(),
                suggestedSeconds = seconds,
                referenceAssetIds = referenceAssetIds ?: previous.referenceAssetIds,
            ) else previous
        })
    }

    /** Ask the configured AI for grounded v1 JSON; legacy line outputs remain parseable. */
    fun suggestShotPlan(projectId: String) {
        val project = container.projectStore.project(projectId) ?: return
        val settings = container.aiSettingsStore.load()
        if (!settings.configured) {
            _message.value = AppStrings.get(R.string.gen_vm_ai_not_configured)
            return
        }
        planJob?.cancel()
        val generation = planGeneration.incrementAndGet()
        _planState.value = PlanState(
            loading = true,
            projectId = projectId,
            projectUpdatedAt = project.updatedAt,
        )
        planJob = viewModelScope.launch {
            val suggestions = withContext(Dispatchers.IO) {
                try {
                    val text = container.aiRepository.chat(
                        DirectorPrompts.system(),
                        DirectorPrompts.user(project, _workflows.value.map {
                            "${it.path} · ${it.providerLabel} · ${it.name}"
                        }),
                    )
                    DirectorPrompts.parsePlan(text)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    _message.value = error.message ?: error.javaClass.simpleName
                    emptyList()
                }
            }
            if (!isActive || generation != planGeneration.get()) return@launch
            val availableProject = container.projectStore.project(projectId)
            if (availableProject == null || availableProject.updatedAt != project.updatedAt) {
                _message.value = AppStrings.get(R.string.studio_ai_stale_plan)
                _planState.value = PlanState()
                return@launch
            }
            _planState.value = PlanState(
                loading = false,
                projectId = projectId,
                projectUpdatedAt = project.updatedAt,
                suggestions = suggestions,
                // Never auto-select invented, stale or mismatched image references.
                selected = suggestions.indices.filter { index ->
                    DirectorPrompts.validationIssues(suggestions[index], project).isEmpty()
                }.toSet(),
            )
        }
    }

    /** Applies only selected, validated local draft shots; never generates or pays. */
    fun applyPlanSuggestions(projectId: String) {
        val state = _planState.value
        val project = container.projectStore.project(projectId) ?: return
        if (state.projectId != projectId || state.projectUpdatedAt != project.updatedAt) {
            _message.value = AppStrings.get(R.string.studio_ai_stale_plan)
            return
        }
        val chosen = state.suggestions.filterIndexed { index, _ -> index in state.selected }
        if (chosen.isEmpty()) return
        val errors = chosen.flatMap { DirectorPrompts.validationIssues(it, project) }
        if (errors.isNotEmpty()) {
            _message.value = AppStrings.get(R.string.studio_ai_invalid_plan, errors.first())
            return
        }
        val drafts = chosen.map { suggestion ->
            ProjectShot(
                prompt = suggestion.prompt,
                cameraNotes = suggestion.cameraNotes,
                plannedDurationSeconds = suggestion.suggestedSeconds,
                referenceAssetIds = suggestion.referenceAssetIds,
            )
        }
        if (container.projectStore.addPlannedShots(projectId, drafts)) {
            _message.value = AppStrings.get(R.string.proj_director_plan_applied, chosen.size)
            _planState.value = PlanState()
        } else {
            _message.value = AppStrings.get(R.string.board_err_persist)
        }
    }

    /** The seam frame of a shot: its own tail frame, else the media edge. */
    private fun seamFrame(shot: ProjectShot, seamAtEnd: Boolean): Bitmap? {
        val path = shot.resultPath ?: return null
        val side = if (seamAtEnd) shot.lastFramePath else null
        if (side != null && File(side).isFile) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(side, bounds)
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                val sample = if (longest > FrameStrip.THUMBNAIL_WIDTH) {
                    var factor = 1
                    while (longest / factor > FrameStrip.THUMBNAIL_WIDTH) factor *= 2
                    factor
                } else 1
                val bitmap = BitmapFactory.decodeFile(side, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })
                if (bitmap != null) return bitmap
            }
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                if (seamAtEnd) {
                    val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    retriever.getScaledFrameAtTime(
                        (durationMs * 1000L - 1000L).coerceAtLeast(0L),
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        FrameStrip.THUMBNAIL_WIDTH,
                        FrameStrip.THUMBNAIL_HEIGHT,
                    )
                } else {
                    retriever.getScaledFrameAtTime(
                        0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        FrameStrip.THUMBNAIL_WIDTH, FrameStrip.THUMBNAIL_HEIGHT,
                    )
                }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    private fun syncTasks(tasks: List<GenerationTask>) {
        val byId = tasks.associateBy { it.taskId }
        projects.value.forEach { project ->
            project.shots.forEach { shot ->
                if (isOrphanedRunningShot(shot, byId.keys)) {
                    // The remote job may still exist: unlock for manual recovery,
                    // but never automatically create another paid generation.
                    container.projectStore.markFailed(
                        project.id,
                        shot.id,
                        AppStrings.get(R.string.board_err_missing_task),
                    )
                    return@forEach
                }
                // A user-promoted finished Take must not be overwritten by an old task emission.
                if (shot.status == "done" && shot.selectedTakeId != null &&
                    shot.takes.any { it.id == shot.selectedTakeId && it.status == "done" && it.resultPath == shot.resultPath }
                ) return@forEach
                val task = shot.taskId?.let(byId::get) ?: return@forEach
                if (task.isActive) {
                    if (shot.status != "running") {
                        container.projectStore.updateShot(project.id, shot.id) {
                            it.copy(status = "running", error = null)
                        }
                    }
                    return@forEach
                }
                if (task.phase == GenerationPhase.SUCCEEDED) {
                    if (shot.status == "done" && shot.resultPath == task.results.firstOrNull()?.localPath) {
                        val frame = shot.lastFramePath
                        if (!shot.tailFrameStored && frame != null && File(frame).isFile &&
                            syncing.add(shot.id)
                        ) {
                            val index = project.shots.indexOfFirst { it.id == shot.id }
                            viewModelScope.launch {
                                try {
                                    if (addTailFrameArtifacts(
                                            project,
                                            shot,
                                            index,
                                            shot.resultPath.orEmpty(),
                                            frame,
                                        )
                                    ) {
                                        markTailFrameStored(project.id, shot.id)
                                    }
                                } finally {
                                    syncing.remove(shot.id)
                                }
                            }
                        }
                        return@forEach
                    }
                    val resultPath = task.results.firstOrNull()?.localPath
                    if (resultPath == null) {
                        container.projectStore.markFailed(project.id, shot.id, AppStrings.get(R.string.board_err_no_result))
                        return@forEach
                    }
                    if (!syncing.add(shot.id)) return@forEach
                    viewModelScope.launch {
                        try {
                            val index = project.shots.indexOfFirst { it.id == shot.id }
                            val lastFrame = extractLastFrame(shot.id, resultPath)
                            val done = container.projectStore.markDone(
                                project.id,
                                shot.id,
                                resultPath,
                                lastFrame,
                            )
                            if (done && lastFrame != null &&
                                addTailFrameArtifacts(project, shot, index, resultPath, lastFrame)
                            ) {
                                markTailFrameStored(project.id, shot.id)
                                _message.value = AppStrings.get(R.string.board_tail_frame_saved)
                            }
                        } finally {
                            syncing.remove(shot.id)
                        }
                    }
                } else if (task.phase == GenerationPhase.FAILED) {
                    container.projectStore.markFailed(
                        project.id,
                        shot.id,
                        task.error?.message ?: AppStrings.get(R.string.board_err_generation_failed),
                    )
                } else if (task.phase == GenerationPhase.CANCELLED) {
                    // Closes the running take as cancelled and returns the shot to draft.
                    container.projectStore.markTaskCancelled(project.id, shot.id, shot.taskId.orEmpty())
                }
            }
        }
    }

    /** Imports already-extracted tail frames once, including migrated projects. */
    private fun migrateLegacyTailFrames() {
        val pending = projects.value.flatMap { project ->
            project.shots.mapIndexed { index, shot -> Triple(project, index, shot) }
        }.filter { (_, _, shot) ->
            shot.status == "done" && !shot.tailFrameStored && shot.lastFramePath != null
        }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            pending.forEach { (project, index, shot) ->
                val frame = shot.lastFramePath ?: return@forEach
                if (!File(frame).isFile || !syncing.add(shot.id)) return@forEach
                try {
                    if (addTailFrameArtifacts(project, shot, index, shot.resultPath.orEmpty(), frame)) {
                        markTailFrameStored(project.id, shot.id)
                    }
                } finally {
                    syncing.remove(shot.id)
                }
            }
        }
    }

    private fun tailAssetId(shotId: String) = "tail:$shotId"

    private fun markTailFrameStored(projectId: String, shotId: String) =
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(tailFrameStored = true)
        }

    /** One stable tail-frame asset per shot, plus a separate History result for videos. */
    private suspend fun addTailFrameArtifacts(
        project: VideoProject,
        shot: ProjectShot,
        index: Int,
        resultPath: String,
        lastFramePath: String,
    ): Boolean {
        if (!File(lastFramePath).isFile) return false
        val current = container.projectStore.project(project.id) ?: project
        val existing = current.assets.firstOrNull { it.id == tailAssetId(shot.id) }
        val asset = ProjectAsset(
            id = tailAssetId(shot.id),
            role = ReferenceRole.END_FRAME,
            path = lastFramePath,
            label = AppStrings.get(R.string.board_tail_asset_label, index.coerceAtLeast(0) + 1),
            order = existing?.order ?: current.assets.size,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        if (existing?.path != asset.path || existing?.label != asset.label) {
            if (!container.projectStore.addAsset(project.id, asset)) return false
        }
        // An image result is already in History; only a video needs its tail frame added.
        return lastFramePath == resultPath ||
            addTailFrameToHistory(project, shot, index, lastFramePath)
    }

    private suspend fun addTailFrameToHistory(
        project: VideoProject,
        shot: ProjectShot,
        index: Int,
        lastFramePath: String,
    ): Boolean {
        val sourceName = File(lastFramePath).nameWithoutExtension
        val historyId = "storyboard-tail:${shot.id}:$sourceName"
        if (container.historyRepository.find(historyId) != null) return true
        // History owns its copy, so project GC can never delete an old result.
        val historyImage = withContext(Dispatchers.IO) {
            runCatching {
                val target = container.historyRepository.newImageFile(
                    "storyboard-tail-${shot.id}-$sourceName",
                    0,
                )
                target.parentFile?.mkdirs()
                File(lastFramePath).copyTo(target, overwrite = true)
                target.absolutePath
            }.getOrNull()
        } ?: return false
        val workflowPath = shot.workflowPath?.takeIf { it.isNotBlank() }
            ?: project.workflowPath.takeIf { it.isNotBlank() }
            ?: AutoDlConfig.PATH_PREFIX + (shot.workflowId ?: project.workflowId)
        val provider = container.cloudProviders.forPath(workflowPath)
        val prompt = listOf(project.stylePrompt, shot.prompt)
            .filter(String::isNotBlank)
            .joinToString(", ")
        val values = buildMap {
            putAll(project.defaultValues)
            putAll(shot.values)
            if (prompt.isNotBlank()) put("prompt", prompt)
        }
        return runCatching {
            container.historyRepository.add(
                HistoryItem(
                    id = historyId,
                    wfPath = workflowPath,
                    wfName = AppStrings.get(
                        R.string.board_tail_result_name,
                        project.title,
                        index.coerceAtLeast(0) + 1,
                    ),
                    time = System.currentTimeMillis(),
                    values = values,
                    fields = emptyList(),
                    randomSeed = true,
                    images = listOf(historyImage),
                    summary = (shot.prompt.ifBlank { project.title }).take(50),
                    prompts = if (shot.prompt.isNotBlank()) {
                        listOf(LabeledText(AppStrings.get(R.string.board_shot_prompt), shot.prompt))
                    } else {
                        emptyList()
                    },
                    seeds = "",
                    serverUrl = provider?.endpoint().orEmpty(),
                    tags = listOf("storyboard", "tail-frame"),
                )
            )
            true
        }.getOrDefault(false)
    }

    /** Extracts a frame near the end of a video; images are used directly. */
    private suspend fun extractLastFrame(shotId: String, path: String): String? = withContext(Dispatchers.IO) {
        if (!isVideoPath(path)) return@withContext path.takeIf { File(it).isFile }
        val dir = File(container.app.filesDir, "storyboards").apply { mkdirs() }
        val target = File(dir, "${shotId}_tail_${System.currentTimeMillis()}.png")
        val frame = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val at = (durationMs * 1000L - 1L).coerceAtLeast(0L)
                retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
            } finally {
                retriever.release()
            }
        }.getOrNull() ?: return@withContext null
        runCatching {
            target.outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            target.absolutePath
        }.getOrNull()
    }
}

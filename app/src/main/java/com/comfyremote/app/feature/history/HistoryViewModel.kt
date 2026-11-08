package com.comfyremote.app.feature.history

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.comfyremote.app.data.project.ProjectMediaLinks
import com.comfyremote.app.data.project.ShotTake
import com.comfyremote.app.data.project.TimelineClip
import com.comfyremote.app.data.project.TrackKind
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comfyremote.app.AppContainer
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.data.cloud.AutoDlConfig
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.data.repository.PromptPreset
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    val historyLoadError: StateFlow<String?> = container.historyRepository.loadError
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _favoritesOnly = MutableStateFlow(false)
    val favoritesOnly: StateFlow<Boolean> = _favoritesOnly.asStateFlow()

    val items: StateFlow<List<HistoryItem>> = combine(
        container.historyRepository.items,
        _query,
        _favoritesOnly,
    ) { all, query, favoritesOnly ->
        val q = query.trim()
        all.filter { item ->
            (!favoritesOnly || item.favorite) && (
                q.isBlank() || item.wfName.contains(q, true) || item.wfPath.contains(q, true) ||
                    item.summary.contains(q, true) || item.seeds.contains(q, true) ||
                    item.serverUrl.contains(q, true) || item.prompts.any { it.value.contains(q, true) } ||
                    item.tags.any { it.contains(q, true) }
                )
        }.sortedWith(compareByDescending<HistoryItem> { it.favorite }.thenByDescending { it.time })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, container.historyRepository.load())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Projects shown by the "add to project" picker; reads the Studio index. */
    val storyboardProjects = container.projectStore.projects

    data class LinkedResult(val projectId: String, val description: String)
    private val _linkedResult = MutableStateFlow<LinkedResult?>(null)
    val linkedResult: StateFlow<LinkedResult?> = _linkedResult.asStateFlow()
    fun clearLinkedResult() { _linkedResult.value = null }

    /**
     * Add an already-rendered video as a *new candidate*, preserving the currently
     * selected Take, running jobs, original History media and existing timeline.
     */
    fun attachAsTake(projectId: String, shotId: String, item: HistoryItem, path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(path)
            if (ProjectMediaLinks.kind(path) != ProjectMediaLinks.Kind.VIDEO ||
                !file.isFile || !file.canRead()) {
                _message.value = AppStrings.get(R.string.studio_history_import_failed, "video unavailable")
                return@launch
            }
            val owner = container.projectStore.project(projectId)
            if (owner == null || owner.shots.none { it.id == shotId }) {
                _message.value = AppStrings.get(R.string.studio_history_import_failed, "shot missing")
                return@launch
            }
            val take = ShotTake(
                providerId = item.wfPath.substringBefore(':').takeIf { ':' in item.wfPath }.orEmpty(),
                workflowPath = item.wfPath,
                prompt = item.prompts.firstOrNull()?.value ?: item.summary,
                seed = item.seeds.takeIf { it.isNotBlank() },
                values = item.values,
                status = "done",
                taskId = item.id.takeIf { id -> container.generationManager.tasks.value.any { it.taskId == id } },
                resultPath = path,
            )
            if (container.projectStore.attachExistingTake(projectId, shotId, take)) {
                _linkedResult.value = LinkedResult(projectId, AppStrings.get(R.string.studio_history_take_added))
            } else {
                _message.value = AppStrings.get(R.string.studio_history_import_failed, "already linked, active shot or save failed")
            }
        }
    }

    /** Reuse a rendered local video/audio as a timeline clip without copying or rewriting it. */
    fun addToProjectTimeline(projectId: String, item: HistoryItem, path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val kind = ProjectMediaLinks.track(path)
            val file = File(path)
            if (kind == null || !file.isFile || !file.canRead() ||
                container.projectStore.project(projectId) == null) {
                _message.value = AppStrings.get(R.string.studio_history_import_failed, "media unavailable")
                return@launch
            }
            val duration = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(path)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            if (duration == null || duration <= 0L) {
                _message.value = AppStrings.get(R.string.studio_history_import_failed, "cannot read duration")
                return@launch
            }
            val clip = TimelineClip(
                sourcePath = path,
                sourceLabel = item.wfName,
                sourceOutMs = duration,
            )
            if (container.projectStore.addClip(projectId, kind, clip)) {
                _linkedResult.value = LinkedResult(projectId, AppStrings.get(R.string.studio_history_timeline_added))
            } else {
                _message.value = AppStrings.get(R.string.studio_history_import_failed, "could not save timeline")
            }
        }
    }

    fun addToProject(projectId: String, role: ReferenceRole, path: String, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.projectAssetStore.importFile(path, role, label) }
                .onSuccess { asset ->
                    if (container.projectStore.addAsset(projectId, asset)) {
                        _linkedResult.value = LinkedResult(
                            projectId, AppStrings.get(R.string.history_msg_added_to_project),
                        )
                    } else {
                        // Only the freshly imported private copy is unreferenced;
                        // never remove the original History result on failed index writes.
                        File(asset.path).delete()
                        _message.value = AppStrings.get(R.string.studio_history_import_failed, "project index save failed")
                    }
                }
                .onFailure { e ->
                    _message.value = AppStrings.get(R.string.history_msg_add_project_failed, e.message ?: "error")
                }
        }
    }

    fun setQuery(value: String) { _query.value = value }
    fun toggleFavoritesOnly() { _favoritesOnly.value = !_favoritesOnly.value }
    fun toggleFavorite(id: String) = container.historyRepository.toggleFavorite(id)
    fun setTags(id: String, raw: String) = container.historyRepository.setTags(id, raw.split(',', '，', '#').map(String::trim).filter(String::isNotBlank))
    fun delete(id: String) = container.historyRepository.delete(id)
    fun deleteMany(ids: Collection<String>) = container.historyRepository.deleteMany(ids)

    fun bookmark(item: HistoryItem) {
        if (item.prompts.isEmpty()) return
        container.promptPresetRepository.add(
            PromptPreset(
                id = "history_${item.id}_${System.currentTimeMillis()}",
                title = item.summary.take(32).ifBlank { item.wfName },
                prompts = item.prompts,
                workflowPath = item.wfPath.ifBlank { null },
                createdAt = System.currentTimeMillis(),
                tags = (listOf("result") + item.tags).distinct(),
                folder = item.wfName,
            )
        )
        _message.value = AppStrings.get(R.string.history_msg_saved_preset)
    }

    fun prepareReuse(item: HistoryItem): DraftTransfer? = prepareTransfer(
        item,
        null,
        reproduceSeed = true,
        allowWorkflowChoice = container.appSettingsStore.settings.value.reuseChooseWorkflow,
    )

    fun prepareReference(item: HistoryItem, localPath: String): DraftTransfer? = prepareTransfer(
        item,
        localPath,
        reproduceSeed = false,
        allowWorkflowChoice = false,
    )

    /** Continue a still image into the configured cloud video workflow. */
    fun prepareVideo(item: HistoryItem, localPath: String): DraftTransfer? {
        val workflowId = container.appSettingsStore.settings.value.defaultVideoWorkflowId.trim()
        if (workflowId.isBlank()) {
            _message.value = AppStrings.get(R.string.history_msg_need_video_wf)
            return null
        }
        val transfer = DraftTransfer(
            workflowPath = AutoDlConfig.PATH_PREFIX + workflowId,
            workflowName = AppStrings.get(R.string.history_cloud_video),
            values = emptyMap(),
            randomSeed = true,
            serverUrl = null,
            referenceLocalPath = localPath,
        )
        container.draftTransferRepository.put(transfer)
        return transfer
    }

    private fun prepareTransfer(
        item: HistoryItem,
        referenceLocalPath: String?,
        reproduceSeed: Boolean,
        allowWorkflowChoice: Boolean = false,
    ): DraftTransfer? {
        if (item.wfPath.isBlank()) return null
        val transfer = DraftTransfer(
            workflowPath = item.wfPath,
            workflowName = item.wfName,
            values = item.values,
            sourceFields = item.fields,
            randomSeed = if (reproduceSeed && item.seeds.isNotBlank()) false else item.randomSeed,
            serverUrl = item.serverUrl.takeIf(String::isNotBlank),
            referenceLocalPath = referenceLocalPath,
            reuseAcrossWorkflows = allowWorkflowChoice,
        )
        container.draftTransferRepository.put(transfer)
        return transfer
    }

    fun serverLabel(url: String): String {
        if (url.isBlank()) return ""
        return container.serverRepository.load().firstOrNull { it.url == url }?.name?.takeIf { it.isNotBlank() } ?: url
    }

    fun save(bitmap: Bitmap) {
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

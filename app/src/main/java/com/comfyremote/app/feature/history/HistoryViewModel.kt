package com.comfyremote.app.feature.history

import android.graphics.Bitmap
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

    fun addToProject(projectId: String, role: ReferenceRole, path: String, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.projectAssetStore.importFile(path, role, label) }
                .onSuccess { asset ->
                    container.projectStore.addAsset(projectId, asset)
                    _message.value = AppStrings.get(R.string.history_msg_added_to_project)
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

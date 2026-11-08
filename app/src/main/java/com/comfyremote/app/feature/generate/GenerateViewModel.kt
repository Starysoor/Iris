package com.comfyremote.app.feature.generate

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.comfyremote.app.AiImagePayload
import com.comfyremote.app.AiResult
import com.comfyremote.app.AiSkillDef
import com.comfyremote.app.SkillExecution
import com.comfyremote.app.TagProfile
import com.comfyremote.app.tagProfileInstruction
import com.comfyremote.app.AppContainer
import com.comfyremote.app.ComfyApi
import com.comfyremote.app.ComfyException
import com.comfyremote.app.ConvertedWorkflow
import com.comfyremote.app.FieldKind
import com.comfyremote.app.LabeledText
import com.comfyremote.app.PromptField
import com.comfyremote.app.WorkflowConverter
import com.comfyremote.app.WorkflowEntry
import com.comfyremote.app.data.cloud.AutoDlConfig
import com.comfyremote.app.data.cloud.draftScopeKey
import com.comfyremote.app.data.cloud.CloudInputStore
import com.comfyremote.app.data.cloud.CloudWorkflowMapping
import com.comfyremote.app.data.cloud.CloudWorkflowSpec
import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.project.BudgetGate
import com.comfyremote.app.data.project.ShotTake
import com.comfyremote.app.data.project.taskCostStats
import com.comfyremote.app.data.generation.GenerationRequest
import com.comfyremote.app.data.generation.GenerationTask
import com.comfyremote.app.data.media.ImagePreprocessor
import com.comfyremote.app.data.media.BoundedMediaInput
import com.comfyremote.app.data.media.decodeSampled
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.data.repository.PromptPreset
import com.comfyremote.app.data.repository.WorkflowInsight
import com.comfyremote.app.data.repository.WorkflowInsights
import com.comfyremote.app.R
import com.comfyremote.app.ui.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class WorkflowDraft(
    val workflowPath: String,
    val workflowName: String,
    val fields: List<PromptField>,
    val values: Map<String, String> = emptyMap(),
    val randomSeed: Boolean = true,
    val warnings: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val nodeTitles: Map<String, String> = emptyMap(),
    /** Non-null for a hosted cloud workflow snapshot. */
    val cloud: CloudWorkflowSpec? = null,
    val cloudBaseUrl: String? = null,
    /** The provider whose credential billed this workflow. */
    val cloudProviderLabel: String = "",
) {
    val isCloud: Boolean get() = cloud != null

    /** Drafts are scoped per server; cloud drafts use a stable provider+endpoint key. */
    val storageServerUrl: String
        get() = if (isCloud) cloudBaseUrl ?: "cloud:unknown" else ""
}

enum class ImageUploadPhase { UPLOADING, FAILED }

data class ImageUploadStatus(
    val phase: ImageUploadPhase,
    val message: String? = null,
)

enum class PresetApplyMode { ALL, PROMPTS, PARAMETERS }

data class GenerateUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val draft: WorkflowDraft? = null,
    val insight: WorkflowInsight? = null,
    val activeTaskId: String? = null,
    val skills: List<AiSkillDef> = AiSkillDef.defaults(),
    val aiBusy: String? = null,
    val aiResult: AiResult? = null,
    val presets: List<PromptPreset> = emptyList(),
    val presetQuery: String = "",
    val uploads: Map<String, ImageUploadStatus> = emptyMap(),
    val batchCount: Int = 1,
    val batchLocalQueue: Boolean = true,
    /** Storyboard shots bind one task per shot, so batch controls are locked to 1. */
    val storyboardShot: Boolean = false,
    val message: String? = null,
) {
    val filteredPresets: List<PromptPreset>
        get() {
            val q = presetQuery.trim()
            val path = draft?.workflowPath
            return presets.asSequence()
                .filter { p ->
                    q.isBlank() || p.title.contains(q, true) || p.folder.contains(q, true) ||
                        p.prompts.any { it.value.contains(q, true) || it.label.contains(q, true) } ||
                        p.tags.any { it.contains(q, true) } ||
                        p.values.values.any { it.contains(q, true) }
                }
                .sortedWith(
                    compareByDescending<PromptPreset> { it.favorite }
                        .thenByDescending { it.workflowPath != null && it.workflowPath == path }
                        .thenBy { it.folder.lowercase() }
                        .thenByDescending { it.lastUsedAt ?: 0L }
                        .thenByDescending { it.createdAt }
                )
                .toList()
        }
}

internal fun PromptField.looksLikeNegativePrompt(): Boolean {
    val text = "$label $input".lowercase()
    return listOf("negative", "neg_", "neg ", "负面", "负向", "反向").any(text::contains)
}

internal fun resolveNegativePromptSource(
    draft: WorkflowDraft,
    fieldKey: String,
    currentText: String,
): String? {
    val currentField = draft.fields.firstOrNull { it.key == fieldKey }
    if (currentField == null || !currentField.looksLikeNegativePrompt()) {
        return currentText.takeIf(String::isNotBlank)
    }
    val positive = draft.fields.firstOrNull {
        it.kind == FieldKind.TEXT && it.multiline && !it.looksLikeNegativePrompt()
    } ?: return null
    return (draft.values[positive.key] ?: positive.value).takeIf(String::isNotBlank)
}

data class LinkedStudioShot(
    val projectId: String,
    val projectTitle: String,
    val shotNumber: Int,
)

class GenerateViewModel(private val container: AppContainer) : ViewModel() {
    private val _linkedShot = MutableStateFlow<LinkedStudioShot?>(null)
    val linkedShot: StateFlow<LinkedStudioShot?> = _linkedShot.asStateFlow()
    val projects = container.projectStore.projects
    private val appSettings = container.appSettingsStore.settings.value
    private val _state = MutableStateFlow(
        GenerateUiState(
            skills = container.aiSkillStore.load(),
            presets = container.promptPresetRepository.load(),
            batchCount = appSettings.defaultBatchCount,
            batchLocalQueue = appSettings.defaultLocalQueue,
        )
    )
    val state: StateFlow<GenerateUiState> = _state.asStateFlow()
    val tasks: StateFlow<List<GenerationTask>> = container.generationManager.tasks

    private var converted: ConvertedWorkflow? = null
    private var loadedPath: String? = null
    private val appliedSeedTasks = mutableSetOf<String>()
    private var draftSaveJob: Job? = null
    private var submitJob: Job? = null
    private var pendingStoryboardProjectId: String? = null
    private var pendingStoryboardShotId: String? = null
    private val storyboardReferenceValuesAtLoad = mutableMapOf<String, String>()
    /** Prevent a missing/failed explicit stage input from silently becoming a paid request. */
    private val failedMappedFieldKeys = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            container.generationManager.persistenceError.collect { error ->
                if (error != null) {
                    _state.update {
                        it.copy(message = AppStrings.get(R.string.gen_vm_persist_failed, error))
                    }
                    container.generationManager.clearPersistenceError()
                }
            }
        }
        viewModelScope.launch {
            container.promptPresetRepository.presets.collect { presets ->
                _state.update { it.copy(presets = presets) }
            }
        }
        viewModelScope.launch {
            container.incomingMedia.pending.collect { pending ->
                if (pending.isNotEmpty() && _state.value.draft?.fields?.any { it.kind == FieldKind.IMAGE } == true) {
                    consumeIncomingMedia()
                }
            }
        }
        viewModelScope.launch {
            container.generationManager.tasks.collect { list ->
                val activeId = _state.value.activeTaskId ?: return@collect
                val task = list.firstOrNull { it.taskId == activeId } ?: return@collect
                if (task.phase == GenerationPhase.SUCCEEDED && task.seedValues.isNotEmpty() && appliedSeedTasks.add(task.taskId)) {
                    updateDraft { draft ->
                        if (draft.workflowPath == task.request.workflowPath) {
                            draft.copy(values = draft.values + task.seedValues)
                        } else draft
                    }
                }
                val batchId = task.batchId
                if (!task.isActive && batchId != null) {
                    val next = list.asSequence()
                        .filter { it.batchId == batchId && it.isActive }
                        .minByOrNull { it.batchIndex ?: Int.MAX_VALUE }
                    if (next != null && next.taskId != activeId) {
                        _state.update { it.copy(activeTaskId = next.taskId) }
                    }
                }
            }
        }
    }

    fun loadWorkflow(path: String, name: String) {
        if (path.isBlank()) return
        if (loadedPath == path && (_state.value.loading || _state.value.draft != null)) return
        loadedPath = path
        pendingStoryboardProjectId = null
        pendingStoryboardShotId = null
        _linkedShot.value = null
        storyboardReferenceValuesAtLoad.clear()
        failedMappedFieldKeys.clear()
        _state.update { it.copy(loading = true, error = null, draft = null, insight = null) }
        viewModelScope.launch {
            runCatching { buildDraft(path, name) }
                .onSuccess { (baseDraft, conv) ->
                    converted = conv
                    val transfer = container.draftTransferRepository.consume(path)
                    pendingStoryboardProjectId = transfer?.storyboardProjectId
                    pendingStoryboardShotId = transfer?.storyboardShotId
                    _linkedShot.value = transfer?.storyboardProjectId?.let { projectId ->
                        container.projectStore.project(projectId)?.let { project ->
                            val index = project.shots.indexOfFirst { it.id == transfer.storyboardShotId }
                            // A workflow-recipe stage belongs to a project even without a Shot.
                            LinkedStudioShot(projectId, project.title, if (index >= 0) index + 1 else 0)
                        }
                    }
                    val storyboard = pendingStoryboardProjectId != null && pendingStoryboardShotId != null
                    val remembered = if (transfer == null &&
                        container.appSettingsStore.settings.value.autoRestoreDrafts
                    ) {
                        val primaryKey = baseDraft.storageServerUrl
                            .ifBlank { container.serverSession.url }
                        container.workflowDraftStore.load(path, primaryKey)
                            ?: if (baseDraft.isCloud) {
                                // Older builds scoped cloud drafts by class baseUrl or the local server.
                                legacyCloudDraftKeys(path).firstNotNullOfOrNull { key ->
                                    container.workflowDraftStore.load(path, key)
                                }
                            } else null
                    } else null
                    val mapped = transfer?.let { pending ->
                        if (pending.reuseAcrossWorkflows && pending.workflowPath != path) {
                            ReuseValueMapper.map(pending, baseDraft)
                        } else null
                    }
                    val reuseMessage = when {
                        transfer == null || mapped == null -> null
                        mapped.values.isEmpty() && transfer.values.isNotEmpty() ->
                            AppStrings.get(R.string.gen_vm_reuse_none)
                        mapped.unmapped > 0 ->
                            AppStrings.get(R.string.gen_vm_reuse_partial, mapped.values.size, transfer.values.size)
                        else -> null
                    }
                    val draft = baseDraft.copy(
                        values = mapped?.values ?: transfer?.values ?: remembered?.values.orEmpty(),
                        randomSeed = transfer?.randomSeed ?: remembered?.randomSeed
                            ?: container.appSettingsStore.settings.value.defaultRandomSeed,
                    )
                    _state.update {
                        it.copy(
                            loading = false,
                            draft = draft,
                            insight = insightFor(draft, conv),
                            skills = container.aiSkillBindingStore.filter(container.aiSkillStore.load(), name, path),
                            activeTaskId = null,
                            batchCount = if (storyboard) 1
                                else container.appSettingsStore.settings.value.defaultBatchCount,
                            storyboardShot = storyboard,
                            message = reuseMessage,
                        )
                    }
                    val exactFields = transfer?.referenceFieldPaths.orEmpty()
                    val multipleReferences = transfer?.referenceLocalPaths.orEmpty()
                        .filter(String::isNotBlank)
                    if (exactFields.isNotEmpty()) {
                        applyFieldReferencePaths(exactFields, initial = storyboard)
                    } else if (multipleReferences.isNotEmpty()) {
                        applyReferencePaths(multipleReferences, initial = storyboard)
                    } else {
                        transfer?.referenceLocalPath?.takeIf(String::isNotBlank)?.let { reference ->
                            useLocalResultAsReference(reference, initial = storyboard)
                        }
                    }
                    consumeIncomingMedia()
                }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "error") }
                }
        }
    }

    /**
     * Optional Create→Studio association. Provider image/audio/local custom workflows
     * may be linked to a project, but only catalog-confirmed video workflows may
     * bind directly to a Shot; other media must flow through Results verification.
     */
    fun linkCurrentDraft(projectId: String?, shotId: String?) {
        val draft = _state.value.draft ?: return
        val active = _state.value.activeTaskId?.let { id -> tasks.value.firstOrNull { it.taskId == id } }
        if (active?.isActive == true) return
        if (projectId == null) {
            pendingStoryboardProjectId = null
            pendingStoryboardShotId = null
            _linkedShot.value = null
            failedMappedFieldKeys.clear()
            _state.update { it.copy(storyboardShot = false) }
            return
        }
        val project = container.projectStore.project(projectId) ?: return
        val index = if (shotId != null) project.shots.indexOfFirst { it.id == shotId } else -1
        if (shotId != null && (draft.cloud?.kind != "video" || index < 0 ||
                project.shots[index].status == "running")) {
            _state.update { it.copy(message = AppStrings.get(R.string.board_err_choose_workflow)) }
            return
        }
        pendingStoryboardProjectId = projectId
        pendingStoryboardShotId = shotId
        _linkedShot.value = LinkedStudioShot(projectId, project.title, index + 1)
        _state.update { current ->
            current.copy(storyboardShot = shotId != null,
                batchCount = if (shotId != null) 1 else current.batchCount)
        }
    }

    /** Pre-2.3.0 cloud drafts used a raw base URL or the active local server as their key. */
    private fun legacyCloudDraftKeys(path: String): List<String> = buildList {
        container.cloudProviders.forPath(path)?.baseUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { add(it) }
        container.serverSession.url.takeIf { it.isNotBlank() }?.let { add(it) }
    }.distinct()

    private suspend fun buildDraft(path: String, name: String): Pair<WorkflowDraft, ConvertedWorkflow> {
        val cloudProvider = container.cloudProviders.forPath(path)
        if (cloudProvider != null) {
            val workflowId = path.removePrefix(cloudProvider.pathPrefix)
            val spec = cloudProvider.detail(workflowId)
            val fields = CloudWorkflowMapping.fields(spec)
            val warnings = CloudWorkflowMapping.warnings(spec)
            val notes = listOf(
                spec.name,
                AppStrings.get(R.string.gen_workflow_id, spec.id),
                spec.description,
                spec.content,
                AppStrings.get(R.string.gen_vm_cloud_note, cloudProvider.label),
                if (spec.kind == "video" && spec.rules.any { it.name.startsWith("ref_image_") }) {
                    AppStrings.get(R.string.gen_vm_multi_ref_note)
                } else {
                    null
                },
            ).filterNotNull().filter(String::isNotBlank)
            val prompt = spec.toJson()
            return WorkflowDraft(
                workflowPath = path,
                workflowName = spec.name.ifBlank { name },
                fields = fields,
                warnings = warnings,
                notes = notes,
                cloud = spec,
                cloudBaseUrl = cloudProvider.draftScopeKey(),
                cloudProviderLabel = cloudProvider.label,
            ) to ConvertedWorkflow(prompt, fields, warnings, notes)
        }
        val conv = container.workflowRepository.convert(WorkflowEntry(path, name, ""))
        val titles = conv.prompt.keys().asSequence().associateWith { nodeId ->
            conv.prompt.optJSONObject(nodeId)?.optJSONObject("_meta")?.optString("title").orEmpty()
        }
        return WorkflowDraft(
            workflowPath = path,
            workflowName = name,
            fields = conv.fields,
            warnings = conv.warnings,
            notes = conv.notes,
            nodeTitles = titles,
        ) to conv
    }

    fun updateValue(key: String, value: String) {
        failedMappedFieldKeys.remove(key)
        updateDraft { it.copy(values = it.values + (key to value)) }
    }
    fun toggleRandomSeed() = updateDraft { it.copy(randomSeed = !it.randomSeed) }
    fun setPresetQuery(value: String) = _state.update { it.copy(presetQuery = value) }
    fun setBatchCount(value: Int) {
        if (_state.value.storyboardShot) return
        _state.update { it.copy(batchCount = value.coerceIn(1, MAX_BATCH)) }
    }
    fun toggleBatchLocalQueue() = _state.update { it.copy(batchLocalQueue = !it.batchLocalQueue) }
    fun clearMessage() = _state.update { it.copy(message = null) }
    fun dismissAiResult() = _state.update { it.copy(aiResult = null) }

    fun resetRememberedDraft() {
        val draft = _state.value.draft ?: return
        container.workflowDraftStore.clear(
            draft.workflowPath,
            draft.storageServerUrl.ifBlank { container.serverSession.url },
        )
        updateDraft {
            it.copy(
                values = emptyMap(),
                randomSeed = container.appSettingsStore.settings.value.defaultRandomSeed,
            )
        }
        _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_defaults_restored)) }
    }

    fun generate() {
        if (submitJob?.isActive == true) return
        val draft = _state.value.draft ?: return
        val cv = converted ?: return
        if (!draft.isCloud && !container.serverSession.connected) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_server_disconnected)) }
            return
        }
        if (draft.isCloud) {
            val provider = container.cloudProviders.forPath(draft.workflowPath)
            if (provider == null || !provider.configured()) {
                val name = provider?.label ?: draft.workflowName
                _state.update {
                    it.copy(message = AppStrings.get(R.string.gen_vm_cloud_token_missing, name))
                }
                return
            }
            if (!container.cloudProviders.isEnabled(provider.id)) {
                _state.update {
                    it.copy(message = AppStrings.get(R.string.gen_vm_cloud_provider_disabled, provider.label))
                }
                return
            }
        }
        if (_state.value.uploads.values.any { it.phase == ImageUploadPhase.UPLOADING }) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_wait_media)) }
            return
        }
        if (failedMappedFieldKeys.isNotEmpty()) {
            _state.update { it.copy(message = AppStrings.get(R.string.studio_binding_upload_failed)) }
            return
        }
        val blocking = _state.value.insight?.blockingIssues.orEmpty()
        if (blocking.isNotEmpty()) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_fix_compat, blocking.first().message)) }
            return
        }
        val current = _state.value.activeTaskId?.let { id -> tasks.value.firstOrNull { it.taskId == id } }
        if (current?.isActive == true || (current?.batchId != null && tasks.value.any { it.batchId == current.batchId && it.isActive })) return

        val projectId = pendingStoryboardProjectId
        val shotId = pendingStoryboardShotId
        val storyboard = projectId != null && shotId != null
        if (storyboard) {
            val status = container.projectStore.project(projectId)
                ?.shots?.firstOrNull { it.id == shotId }?.status
            if (status == "running") {
                _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_storyboard_running)) }
                return
            }
            syncStoryboardReference(projectId, shotId, draft)
            syncStoryboardValues(projectId, shotId, draft)
        }

        persistDraftNow(draft)
        val count = effectiveBatchCount(storyboard, _state.value.batchCount)
        val requests = (0 until count).map { index -> buildRequest(draft, cv, index) }
        // Project budget applies to *all* project-linked stages, including standalone
        // image/audio prep with no Shot. Never silently bypass the budget via recipes.
        if (projectId != null) {
            val budget = container.projectStore.project(projectId)?.budget
            val stats = taskCostStats(tasks.value, projectId)
            when (val decision = BudgetGate.check(stats.costSum, requests.sumOf { it.estimatedCloudCost?.toDouble() ?: 0.0 }, budget)) {
                is BudgetGate.Decision.Blocked -> {
                    _state.update {
                        it.copy(
                            message = AppStrings.get(
                                R.string.gen_vm_budget_blocked,
                                decision.budget,
                                decision.spentEstimate,
                                decision.newCost,
                            ),
                        )
                    }
                    return
                }
                BudgetGate.Decision.Allowed -> Unit
            }
        }
        val localQueue = _state.value.batchLocalQueue
        submitJob = viewModelScope.launch {
            val ids = try {
                container.cloudSubmissionGate.submit(requests, localQueue, draft.cloud?.pricing?.label)
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: error.javaClass.simpleName) }
                return@launch
            }
            if (ids.isEmpty()) return@launch
            if (projectId != null && shotId != null) {
                // Record the attempt's model/params/seed/cost snapshot on the take.
                val request = requests.firstOrNull()
                val seed = request?.fields?.firstOrNull { it.kind == FieldKind.SEED }
                    ?.let { field -> request.fieldValues[field.key] }
                container.projectStore.attachTask(
                    projectId,
                    shotId,
                    ids.first(),
                    takeSeed = ShotTake(
                        providerId = request?.provider.orEmpty(),
                        workflowPath = request?.workflowPath.orEmpty(),
                        values = request?.fieldValues.orEmpty(),
                        seed = seed,
                        costEstimate = request?.estimatedCloudCost?.toDouble(),
                    ),
                )
                pendingStoryboardProjectId = null
                pendingStoryboardShotId = null
            }
            _state.update {
                it.copy(
                    activeTaskId = ids.firstOrNull(),
                    message = if (ids.size > 1) AppStrings.plural(R.plurals.gen_vm_batch_created, ids.size, ids.size) else null,
                )
            }
        }
    }

    /** Remembers the shot's workflow and portable params for the next Regenerate. */
    private fun syncStoryboardValues(projectId: String, shotId: String, draft: WorkflowDraft) {
        val values = draft.fields
            .filter { it.kind == FieldKind.TEXT || it.kind == FieldKind.PARAM || it.kind == FieldKind.SEED }
            .associate { field -> field.key to (draft.values[field.key] ?: field.value) }
        val workflowPath = if (draft.isCloud) draft.workflowPath else ""
        val workflowId = workflowPath.substringAfter(':').takeIf { workflowPath.isNotBlank() }
        val providerId = container.cloudProviders.forPath(workflowPath)?.id.orEmpty()
        container.projectStore.updateShot(projectId, shotId) {
            it.copy(
                values = values,
                workflowPath = workflowPath.takeIf { it.isNotBlank() },
                providerId = providerId.takeIf { it.isNotBlank() },
                workflowId = workflowId,
            )
        }
    }

    /** Persists a manually changed reference so Regenerate opens the same image. */
    private fun syncStoryboardReference(projectId: String, shotId: String, draft: WorkflowDraft) {
        val shot = container.projectStore.project(projectId)
            ?.shots?.firstOrNull { it.id == shotId } ?: return
        // Multi-reference shots are already described by their project asset ids.
        if (shot.referenceAssetIds.isNotEmpty()) return
        val imageField = draft.fields.firstOrNull { it.kind == FieldKind.IMAGE } ?: return
        val current = draft.values[imageField.key] ?: imageField.value
        val initial = storyboardReferenceValuesAtLoad[imageField.key]
        if (current.isBlank()) {
            if (initial != null) {
                container.projectStore.setReference(projectId, shotId, null, manual = true)
            }
            return
        }
        if (current == initial) return
        val localPath = container.cloudInputStore.localPath(current) ?: return
        container.projectStore.setReference(projectId, shotId, localPath, manual = true)
    }

    private fun fieldValue(
        draft: WorkflowDraft,
        values: Map<String, String>,
        inputName: String,
    ): String? {
        val field = draft.fields.firstOrNull { it.input == inputName } ?: return null
        return (values[field.key] ?: field.value).takeIf { it.isNotBlank() }
    }

    private fun cloudCostEstimate(draft: WorkflowDraft, values: Map<String, String>): Int? {
        val spec = draft.cloud ?: return null
        val duration = fieldValue(draft, values, "duration")
            ?: fieldValue(draft, values, "audio_duration")
        if (spec.kind == "audio" && duration == null) return null
        val resolution = fieldValue(draft, values, "resolution")
        return spec.pricing?.estimate(resolution, duration?.toIntOrNull())
    }

    private fun buildRequest(draft: WorkflowDraft, cv: ConvertedWorkflow, batchOffset: Int): GenerationRequest {
        var values = draft.values
        if (!draft.randomSeed && batchOffset > 0) {
            draft.fields.filter { it.kind == FieldKind.SEED }.forEach { field ->
                val raw = values[field.key] ?: field.value
                val seed = raw.toLongOrNull()
                if (seed != null) {
                    val base = Math.floorMod(seed, WorkflowConverter.SEED_MAX)
                    val varied = (base + batchOffset) % WorkflowConverter.SEED_MAX
                    values = values + (field.key to varied.toString())
                }
            }
        }
        val cloudProvider = if (draft.isCloud) container.cloudProviders.forPath(draft.workflowPath) else null
        return GenerationRequest(
            id = UUID.randomUUID().toString(),
            provider = cloudProvider?.id ?: "comfyui",
            providerLabel = cloudProvider?.label.orEmpty(),
            serverUrl = cloudProvider?.endpoint() ?: container.serverSession.url,
            workflowPath = draft.workflowPath,
            workflowName = draft.workflowName,
            fieldValues = values,
            randomSeed = draft.randomSeed,
            nodeTitles = draft.nodeTitles,
            fields = draft.fields,
            convertedPrompt = JSONObject(cv.prompt.toString()),
            createdAt = System.currentTimeMillis() + batchOffset,
            estimatedCloudCost = if (draft.isCloud) cloudCostEstimate(draft, values) else null,
            storyboardProjectId = pendingStoryboardProjectId,
            storyboardShotId = pendingStoryboardShotId,
        )
    }

    fun cancelActiveTask() {
        val id = _state.value.activeTaskId ?: return
        val task = tasks.value.firstOrNull { it.taskId == id } ?: return
        viewModelScope.launch {
            val batchId = task.batchId
            if (batchId == null) {
                container.generationManager.cancel(id)
            } else {
                val ids = tasks.value.filter { it.batchId == batchId && it.isActive }.map { it.taskId }
                container.generationManager.cancelMany(ids)
            }
        }
    }

    fun retryActiveTask() {
        val id = _state.value.activeTaskId ?: return
        val task = tasks.value.firstOrNull { it.taskId == id } ?: return
        // A running remote task is not a retry candidate: duplicate submits can incur charges.
        if (task.isActive) return
        val request = task.request.copy(
            id = UUID.randomUUID().toString(),
            createdAt = System.currentTimeMillis(),
        )
        val projectId = request.storyboardProjectId
        val shotId = request.storyboardShotId
        if (projectId != null && shotId != null) {
            val budget = container.projectStore.project(projectId)?.budget
            val spent = taskCostStats(tasks.value, projectId)
            when (val decision = BudgetGate.check(
                spent.costSum, request.estimatedCloudCost?.toDouble() ?: 0.0, budget,
            )) {
                is BudgetGate.Decision.Blocked -> {
                    _state.update { it.copy(message = AppStrings.get(
                        R.string.gen_vm_budget_blocked,
                        decision.budget, decision.spentEstimate, decision.newCost,
                    )) }
                    return
                }
                BudgetGate.Decision.Allowed -> Unit
            }
            val shot = container.projectStore.project(projectId)?.shots?.firstOrNull { it.id == shotId }
            if (shot?.status == "running") {
                _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_storyboard_running)) }
                return
            }
        }
        viewModelScope.launch {
            val ids = try {
                container.cloudSubmissionGate.submit(listOf(request), localQueue = false)
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: error.javaClass.simpleName) }
                return@launch
            }
            ids.firstOrNull()?.let { newId ->
                if (projectId != null && shotId != null) {
                    val original = container.projectStore.project(projectId)
                        ?.shots?.firstOrNull { it.id == shotId }
                    container.projectStore.attachTask(
                        projectId, shotId, newId,
                        takeSeed = ShotTake(
                            providerId = request.provider,
                            workflowPath = request.workflowPath,
                            prompt = original?.prompt.orEmpty(),
                            values = request.fieldValues,
                            referenceAssetIds = original?.referenceAssetIds.orEmpty(),
                            costEstimate = request.estimatedCloudCost?.toDouble(),
                        ),
                    )
                }
                _state.update { it.copy(activeTaskId = newId) }
            }
        }
    }

    fun aiExpand(fieldKey: String, currentText: String, skill: AiSkillDef) {
        var source = currentText
        if (skill.id == AiSkillDef.DEFAULT_NEG_ID) {
            val draft = _state.value.draft
            val resolved = draft?.let { resolveNegativePromptSource(it, fieldKey, currentText) }
            if (resolved.isNullOrBlank()) {
                _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_need_positive)) }
                return
            }
            source = resolved
        }
        if (source.isBlank() && skill.id != AiSkillDef.DEFAULT_NEG_ID) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_need_prompt)) }
            return
        }
        val settings = container.aiSettingsStore.load()
        if (!settings.configured) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_ai_not_configured)) }
            return
        }
        val busy = "$fieldKey|${skill.id}"
        _state.update { it.copy(aiBusy = busy) }
        val draft = _state.value.draft
        val tagSkill = skill.id == AiSkillDef.DEFAULT_TAGS_ID || skill.id == AiSkillDef.DEFAULT_NSFW_TAGS_ID
        val profile = TagProfile.entries.firstOrNull { it.name == settings.tagProfile } ?: TagProfile.AUTO
        val systemPrompt = if (tagSkill) {
            listOf(skill.systemPrompt, tagProfileInstruction(profile, draft?.workflowName.orEmpty()))
                .filter(String::isNotBlank)
                .joinToString("\n\n")
        } else {
            skill.systemPrompt
        }
        viewModelScope.launch {
            runCatching { container.aiRepository.chat(systemPrompt, source) }
                .onSuccess { out -> _state.update { it.copy(aiBusy = null, aiResult = AiResult(fieldKey, out)) } }
                .onFailure { e -> _state.update { it.copy(aiBusy = null, message = e.message ?: "AI error") } }
        }
    }

    fun applyAi(replace: Boolean, newText: String) {
        val result = _state.value.aiResult ?: return
        updateDraft { draft ->
            val field = draft.fields.firstOrNull { it.key == result.fieldKey }
            val currentText = draft.values[result.fieldKey] ?: field?.value.orEmpty()
            val merged = if (replace || currentText.isBlank()) newText else "$currentText, $newText"
            draft.copy(values = draft.values + (result.fieldKey to merged))
        }
        dismissAiResult()
    }

    fun saveCurrentPreset() {
        val draft = _state.value.draft ?: return
        val prompts = currentPrompts(draft)
        if (prompts.isEmpty()) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_nothing_to_save)) }
            return
        }
        container.promptPresetRepository.add(
            PromptPreset(
                id = "preset_${System.currentTimeMillis()}",
                title = prompts.first().value.take(32),
                prompts = prompts,
                workflowPath = draft.workflowPath,
                createdAt = System.currentTimeMillis(),
                tags = listOf("prompt"),
                folder = draft.workflowName,
            )
        )
        _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_saved_to_library)) }
    }

    fun saveCurrentWorkflowPreset() {
        val draft = _state.value.draft ?: return
        val values = draft.fields.associate { field -> field.key to (draft.values[field.key] ?: field.value) }
        val prompts = currentPrompts(draft)
        container.promptPresetRepository.add(
            PromptPreset(
                id = "workflow_${System.currentTimeMillis()}",
                title = (prompts.firstOrNull()?.value?.take(28)?.takeIf(String::isNotBlank) ?: draft.workflowName),
                prompts = prompts,
                workflowPath = draft.workflowPath,
                createdAt = System.currentTimeMillis(),
                serverUrl = draft.storageServerUrl.ifBlank { container.serverSession.url }
                    .takeIf { it.isNotBlank() },
                values = values,
                randomSeed = draft.randomSeed,
                tags = listOf("workflow"),
                folder = draft.workflowName,
            )
        )
        _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_workflow_preset_saved)) }
    }

    fun deletePreset(id: String) { container.promptPresetRepository.delete(id) }
    fun togglePresetFavorite(id: String) { container.promptPresetRepository.toggleFavorite(id) }
    fun updatePresetMeta(id: String, title: String, tags: String, folder: String) {
        container.promptPresetRepository.updateMeta(
            id,
            title,
            tags.split(',', '，', '#').map(String::trim).filter(String::isNotBlank),
            folder,
        )
    }

    fun exportPresets(): String = container.promptPresetRepository.exportJson()

    fun importPresets(raw: String) {
        val count = runCatching { container.promptPresetRepository.importJson(raw) }.getOrElse {
            _state.update { state -> state.copy(message = AppStrings.get(R.string.gen_vm_import_failed, it.message ?: "invalid JSON")) }
            return
        }
        _state.update { it.copy(message = AppStrings.plural(R.plurals.gen_vm_imported, count, count)) }
    }

    fun applyPreset(preset: PromptPreset, mode: PresetApplyMode = PresetApplyMode.ALL) {
        container.promptPresetRepository.markUsed(preset.id)
        val current = _state.value.draft
        val targetServer = current?.storageServerUrl?.ifBlank { container.serverSession.url }.orEmpty()
        val fieldsByKey = current?.fields?.associateBy { it.key }.orEmpty()
        val mediaAllowed = preset.serverUrl != null && preset.serverUrl == targetServer
        val skippedMedia = if (current != null && !mediaAllowed && preset.isWorkflowPreset) {
            preset.values.keys.count { key ->
                fieldsByKey[key]?.kind == FieldKind.IMAGE || fieldsByKey[key]?.kind == FieldKind.AUDIO
            }
        } else 0
        updateDraft { draft ->
            val textFields = draft.fields.filter { it.kind == FieldKind.TEXT }
            val textKeys = textFields.mapTo(hashSetOf()) { it.key }
            val parameterKeys = draft.fields
                .filter { it.kind == FieldKind.PARAM || it.kind == FieldKind.SEED }
                .mapTo(hashSetOf()) { it.key }
            val knownKeys = draft.fields.mapTo(hashSetOf()) { it.key }
            val fields = draft.fields.associateBy { it.key }
            var values = draft.values

            if (preset.isWorkflowPreset && preset.workflowPath == draft.workflowPath) {
                val accepted = preset.values.filterKeys { key ->
                    key in knownKeys &&
                        (mediaAllowed || fields[key]?.kind !in setOf(FieldKind.IMAGE, FieldKind.AUDIO))
                }
                when (mode) {
                    PresetApplyMode.ALL -> values = values + accepted
                    PresetApplyMode.PROMPTS -> values = values + accepted.filterKeys { it in textKeys }
                    PresetApplyMode.PARAMETERS -> values = values + accepted.filterKeys { it in parameterKeys }
                }
            }

            if (!preset.isWorkflowPreset || mode != PresetApplyMode.PARAMETERS) {
                val byLabel = preset.prompts.associateBy { it.label }
                textFields.forEachIndexed { index, field ->
                    val prompt = byLabel[field.label] ?: preset.prompts.getOrNull(index)
                    if (prompt != null) values = values + (field.key to prompt.value)
                }
            }

            draft.copy(
                values = values,
                randomSeed = if (mode != PresetApplyMode.PROMPTS) preset.randomSeed ?: draft.randomSeed else draft.randomSeed,
            )
        }
        if (skippedMedia > 0) {
            _state.update {
                it.copy(message = AppStrings.get(R.string.gen_vm_preset_media_skipped, skippedMedia))
            }
        }
    }

    fun uploadImage(fieldKey: String, uri: Uri) {
        val draft = _state.value.draft ?: return
        if (!draft.isCloud && !container.serverSession.connected) return
        viewModelScope.launch {
            setUpload(fieldKey, ImageUploadStatus(ImageUploadPhase.UPLOADING))
            runCatching {
                if (draft.isCloud) {
                    container.cloudInputStore.saveFromUri(uri, cloudImageDimension())
                } else {
                    uploadOne(container.serverSession.require(), uri, 0)
                }
            }.onSuccess { name ->
                updateValue(fieldKey, name)
                clearUpload(fieldKey)
            }.onFailure { e -> setUpload(fieldKey, ImageUploadStatus(ImageUploadPhase.FAILED, e.message)) }
        }
    }

    fun uploadAudio(fieldKey: String, uri: Uri) {
        val draft = _state.value.draft ?: return
        if (!draft.isCloud) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_local_audio_unsupported)) }
            return
        }
        viewModelScope.launch {
            setUpload(fieldKey, ImageUploadStatus(ImageUploadPhase.UPLOADING))
            runCatching { container.cloudInputStore.saveAudioFromUri(uri) }
                .onSuccess { name ->
                    updateValue(fieldKey, name)
                    clearUpload(fieldKey)
                }
                .onFailure { e -> setUpload(fieldKey, ImageUploadStatus(ImageUploadPhase.FAILED, e.message)) }
        }
    }

    fun uploadImagesBatch(uris: List<Uri>) {
        val draft = _state.value.draft ?: return
        val imageFields = draft.fields.filter { it.kind == FieldKind.IMAGE }
        if (uris.isEmpty() || imageFields.isEmpty()) return
        if (!draft.isCloud && !container.serverSession.connected) return
        viewModelScope.launch {
            imageFields.zip(uris).forEachIndexed { index, (field, uri) ->
                setUpload(field.key, ImageUploadStatus(ImageUploadPhase.UPLOADING))
                runCatching {
                    if (draft.isCloud) {
                        container.cloudInputStore.saveFromUri(uri, cloudImageDimension())
                    } else {
                        uploadOne(container.serverSession.require(), uri, index)
                    }
                }.onSuccess { name -> updateValue(field.key, name); clearUpload(field.key) }
                    .onFailure { e -> setUpload(field.key, ImageUploadStatus(ImageUploadPhase.FAILED, e.message)) }
            }
        }
    }

    fun useLocalResultAsReference(
        localPath: String,
        initial: Boolean = false,
        fieldKey: String? = null,
    ) {
        val draft = _state.value.draft ?: return
        val field = fieldKey?.let { key -> draft.fields.firstOrNull { it.key == key } }
            ?: draft.fields.firstOrNull { it.kind == FieldKind.IMAGE }
        if (field == null) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_no_reference_input)) }
            return
        }
        if (!draft.isCloud && !container.serverSession.connected) return
        viewModelScope.launch {
            setUpload(field.key, ImageUploadStatus(ImageUploadPhase.UPLOADING))
            runCatching { referenceValueFor(draft, field, localPath) }
                .onSuccess { name ->
                    if (initial) storyboardReferenceValuesAtLoad[field.key] = name
                    updateValue(field.key, name)
                    clearUpload(field.key)
                    if (!initial) {
                        _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_result_as_reference)) }
                    }
                }.onFailure { e ->
                    setUpload(field.key, ImageUploadStatus(ImageUploadPhase.FAILED, e.message))
                }
        }
    }

    /**
     * Explicit image/audio input handoff. Keys must match real draft field keys;
     * no positional fallback and no silent truncation. All staged uploads are
     * awaited by the existing paid-submission gate.
     */
    private fun applyFieldReferencePaths(paths: Map<String, String>, initial: Boolean) {
        val draft = _state.value.draft ?: return
        val fields = draft.fields.associateBy { it.key }
        val unknown = paths.keys.filter { key ->
            fields[key]?.kind !in setOf(FieldKind.IMAGE, FieldKind.AUDIO)
        }
        if (unknown.isNotEmpty()) {
            failedMappedFieldKeys += unknown
            _state.update { it.copy(message = AppStrings.get(R.string.studio_binding_upload_failed)) }
            return
        }
        viewModelScope.launch {
            paths.forEach { (key, path) ->
                if (_state.value.draft?.workflowPath != draft.workflowPath) return@launch
                val field = fields.getValue(key)
                setUpload(key, ImageUploadStatus(ImageUploadPhase.UPLOADING))
                val result = runCatching {
                    if (field.kind == FieldKind.IMAGE) {
                        referenceValueFor(draft, field, path)
                    } else {
                        container.cloudInputStore.saveAudioFromUri(Uri.fromFile(File(path)))
                    }
                }
                result.onSuccess { value ->
                    if (_state.value.draft?.workflowPath != draft.workflowPath) return@onSuccess
                    if (initial) storyboardReferenceValuesAtLoad[key] = value
                    updateValue(key, value)
                    clearUpload(key)
                }.onFailure { error ->
                    if (_state.value.draft?.workflowPath != draft.workflowPath) return@onFailure
                    failedMappedFieldKeys += key
                    setUpload(key, ImageUploadStatus(ImageUploadPhase.FAILED, error.message))
                }
            }
        }
    }

    /** Fills consecutive image inputs from a multi-reference storyboard shot. */
    private fun applyReferencePaths(paths: List<String>, initial: Boolean) {
        val draft = _state.value.draft ?: return
        val fields = draft.fields.filter { it.kind == FieldKind.IMAGE }.take(paths.size)
        if (fields.isEmpty()) return
        viewModelScope.launch {
            fields.zip(paths).forEach { (field, path) ->
                setUpload(field.key, ImageUploadStatus(ImageUploadPhase.UPLOADING))
                runCatching { referenceValueFor(draft, field, path) }
                    .onSuccess { name ->
                        if (initial) storyboardReferenceValuesAtLoad[field.key] = name
                        updateValue(field.key, name)
                        clearUpload(field.key)
                    }.onFailure { e ->
                        setUpload(field.key, ImageUploadStatus(ImageUploadPhase.FAILED, e.message))
                    }
            }
        }
    }

    private suspend fun referenceValueFor(
        draft: WorkflowDraft,
        field: PromptField,
        localPath: String,
    ): String {
        if (draft.isCloud) {
            return container.cloudInputStore.saveFromFile(localPath, cloudImageDimension())
        }
        val file = File(localPath)
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        val ext = file.extension.ifBlank { "png" }
        return container.serverSession.require()
            .uploadImage(bytes, "result_ref_${System.currentTimeMillis()}_${field.key.hashCode()}.$ext")
    }

    /** One-tap handoff from a generated image to the configured cloud video workflow. */
    fun prepareCloudVideo(localPath: String): Pair<String, String>? {
        val workflow = container.appSettingsStore.settings.value.defaultVideoWorkflowId.trim()
        val path = when {
            workflow.isBlank() -> null
            // Newer settings store the provider-scoped path already.
            ':' in workflow -> workflow
            // Legacy setting: a bare AutoDL workflow id. Never route it to
            // AutoDL when the user disabled that provider.
            else -> {
                val autoDl = container.cloudProviders.byId(AutoDlConfig.PROVIDER)
                if (autoDl != null && autoDl.configured() && container.cloudProviders.isEnabled(autoDl.id)) {
                    autoDl.pathPrefix + workflow
                } else {
                    null
                }
            }
        }
        if (path == null) {
            _state.update {
                it.copy(message = AppStrings.get(R.string.gen_vm_no_video_workflow))
            }
            return null
        }
        val name = AppStrings.get(R.string.gen_vm_cloud_video)
        container.draftTransferRepository.put(
            DraftTransfer(
                workflowPath = path,
                workflowName = name,
                values = emptyMap(),
                randomSeed = true,
                referenceLocalPath = localPath,
            )
        )
        return path to name
    }

    /**
     * Sends the actual image + the user's instruction to the vision model and
     * proposes a rewritten prompt. The model sees the picture; this is not a
     * text-only "imagine the image" request.
     */
    fun visionRewrite(targetFieldKey: String?, imageValue: String, instruction: String) {
        if (_state.value.aiBusy != null) return
        val draft = _state.value.draft ?: return
        val target = draft.fields.firstOrNull { it.key == targetFieldKey && it.kind == FieldKind.TEXT }
            ?: draft.fields.firstOrNull {
                it.kind == FieldKind.TEXT && it.multiline && !it.looksLikeNegativePrompt()
            }
            ?: draft.fields.firstOrNull { it.kind == FieldKind.TEXT }
        if (target == null) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_no_prompt_field)) }
            return
        }
        val settings = container.aiSettingsStore.load()
        if (!settings.configured) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_ai_not_configured)) }
            return
        }
        val references = VisionContext.references(draft, draft.values, imageValue)
        if (references.isEmpty()) {
            _state.update { it.copy(message = AppStrings.get(R.string.gen_vm_pick_image)) }
            return
        }
        val mode = VisionContext.mode(draft)
        val skill = container.aiSkillStore.load().firstOrNull { it.id == AiSkillDef.DEFAULT_VISION_ID }
            ?: AiSkillDef.VISION_REWRITE_DEFAULT
        val busy = "vision:${target.key}"
        _state.update { it.copy(aiBusy = busy) }
        viewModelScope.launch {
            runCatching {
                val payloads = mutableListOf<AiImagePayload>()
                references.take(MAX_VISION_IMAGES).forEach { reference ->
                    val bytes = loadVisionImageBytes(reference.value) ?: return@forEach
                    val (prepared, ext) = withContext(Dispatchers.IO) {
                        ImagePreprocessor.prepare(bytes, null, 1024, 84)
                    }
                    payloads += AiImagePayload(
                        bytes = prepared,
                        mime = CloudInputStore.mimeForExt(ext),
                        role = reference.role,
                        order = reference.order,
                        label = reference.label,
                    )
                }
                if (payloads.isEmpty()) {
                    throw ComfyException(AppStrings.get(R.string.gen_vm_cannot_read_image))
                }
                val current = draft.values[target.key] ?: target.value
                val requestText = VisionContext.userText(instruction, current, mode, references)
                if (skill.execution == SkillExecution.MULTI_STAGE) {
                    val analysis = container.aiRepository.visionChat(
                        systemPrompt = SkillRuntime.visionAnalysisSystemPrompt(mode),
                        userText = requestText,
                        images = payloads,
                    )
                    container.aiRepository.chat(
                        systemPrompt = SkillRuntime.plannerSystemPrompt(skill.systemPrompt, mode, references),
                        userText = SkillRuntime.plannerUserText(
                            instruction, current, analysis, references,
                        ),
                    )
                } else {
                    container.aiRepository.visionChat(
                        systemPrompt = VisionContext.systemPrompt(skill.systemPrompt, mode, references),
                        userText = requestText,
                        images = payloads,
                    )
                }
            }.onSuccess { out ->
                _state.update { it.copy(aiBusy = null, aiResult = AiResult(target.key, out)) }
            }.onFailure { e ->
                _state.update { it.copy(aiBusy = null, message = e.message ?: "AI vision error") }
            }
        }
    }

    private suspend fun loadVisionImageBytes(value: String): ByteArray? = withContext(Dispatchers.IO) {
        container.cloudInputStore.readBytes(value)?.let { return@withContext it }
        if (value.startsWith("data:")) {
            return@withContext runCatching {
                java.util.Base64.getDecoder().decode(value.substringAfter(','))
            }.getOrNull()
        }
        val bitmap = runCatching { container.serverSession.require().fetchInputImage(value) }.getOrNull()
            ?: return@withContext null
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    }

    private fun cloudImageDimension(): Int =
        container.appSettingsStore.settings.value.maxImageDimension.coerceIn(1024, 2048)

    suspend fun loadInputBitmap(name: String): Bitmap? = withContext(Dispatchers.IO) {
        val local = container.cloudInputStore.localPath(name)
        if (local != null) decodeSampled(local, 512)
        else runCatching { container.serverSession.require().fetchInputImage(name) }.getOrNull()
    }

    fun saveResult(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.galleryExporter.savePng(bitmap) }
                .onSuccess { _state.update { it.copy(message = container.galleryExporter.savedMessage()) } }
                .onFailure { e -> _state.update { it.copy(message = container.galleryExporter.failMessage(e)) } }
        }
    }

    fun saveMedia(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { container.galleryExporter.saveMediaFile(path) }
                .onSuccess { _state.update { it.copy(message = container.galleryExporter.savedMessage()) } }
                .onFailure { e -> _state.update { it.copy(message = container.galleryExporter.failMessage(e)) } }
        }
    }

    private fun currentPrompts(draft: WorkflowDraft): List<LabeledText> = draft.fields
        .filter { it.kind == FieldKind.TEXT }
        .map { LabeledText(it.label, draft.values[it.key] ?: it.value) }
        .filter { it.value.isNotBlank() }

    private fun consumeIncomingMedia() {
        val draft = _state.value.draft ?: return
        if (draft.fields.none { it.kind == FieldKind.IMAGE }) return
        val pending = container.incomingMedia.drain()
        if (pending.isNotEmpty()) uploadImagesBatch(pending)
    }

    private suspend fun uploadOne(api: ComfyApi, uri: Uri, seq: Int): String {
        val ctx = container.app
        val raw = withContext(Dispatchers.IO) {
            ctx.contentResolver.openInputStream(uri)?.use {
                BoundedMediaInput.read(it, BoundedMediaInput.MAX_IMAGE_BYTES)
            }
        } ?: throw ComfyException(AppStrings.get(R.string.gen_vm_read_image_failed))
        val (bytes, ext) = withContext(Dispatchers.IO) {
            ImagePreprocessor.prepare(
                raw,
                ctx.contentResolver.getType(uri),
                container.appSettingsStore.settings.value.maxImageDimension,
            )
        }
        return api.uploadImage(bytes, "mobile_${System.currentTimeMillis()}_$seq.$ext")
    }

    private fun insightFor(draft: WorkflowDraft, cv: ConvertedWorkflow? = converted): WorkflowInsight {
        draft.cloud?.let { spec ->
            return CloudWorkflowMapping.analyze(spec, draft.fields, draft.values)
        }
        val actual = cv ?: return WorkflowInsight(emptySet(), emptyList())
        return WorkflowInsights.analyze(actual.prompt, draft.fields, draft.values, draft.warnings)
    }

    private fun updateDraft(block: (WorkflowDraft) -> WorkflowDraft) {
        val current = _state.value.draft ?: return
        val next = block(current)
        _state.update { state -> state.copy(draft = next, insight = insightFor(next)) }
        scheduleDraftSave(next)
    }

    private fun scheduleDraftSave(draft: WorkflowDraft) {
        draftSaveJob?.cancel()
        val serverUrl = draft.storageServerUrl.ifBlank { container.serverSession.url }
        draftSaveJob = viewModelScope.launch {
            delay(450)
            persistDraftNow(draft, serverUrl)
        }
    }

    private fun persistDraftNow(draft: WorkflowDraft, serverUrl: String = "") {
        if (!container.appSettingsStore.settings.value.autoSaveDrafts) return
        val key = serverUrl.ifBlank { draft.storageServerUrl.ifBlank { container.serverSession.url } }
        if (key.isNotBlank()) {
            container.workflowDraftStore.save(draft.workflowPath, key, draft.values, draft.randomSeed)
        }
    }

    private fun setUpload(key: String, status: ImageUploadStatus) {
        _state.update { it.copy(uploads = it.uploads + (key to status)) }
    }

    private fun clearUpload(key: String) {
        _state.update { it.copy(uploads = it.uploads - key) }
    }

    companion object {
        internal const val MAX_BATCH = 16
        private const val MAX_VISION_IMAGES = 8
    }
}

/** Storyboard shots bind exactly one task; ordinary workflows keep their configured batch. */
internal fun effectiveBatchCount(storyboard: Boolean, configured: Int): Int =
    if (storyboard) 1 else configured.coerceIn(1, GenerateViewModel.MAX_BATCH)

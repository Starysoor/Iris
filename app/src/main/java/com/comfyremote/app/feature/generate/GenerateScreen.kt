package com.comfyremote.app.feature.generate

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.comfyremote.app.FieldKind
import com.comfyremote.app.R
import com.comfyremote.app.ui.LocalAppSettings
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.BannerTone
import com.comfyremote.app.ui.components.BottomActionBar
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.ErrorCard
import com.comfyremote.app.ui.components.InfoBanner
import com.comfyremote.app.ui.components.IrisPrimaryButton
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisSecondaryButton
import com.comfyremote.app.ui.components.SectionHeader
import com.comfyremote.app.ui.components.TextAction
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateScreen(
    viewModel: GenerateViewModel,
    workflowPath: String,
    workflowName: String,
    onBack: () -> Unit,
    onOpenTask: (String) -> Unit,
    onOpenCloudVideo: (String, String) -> Unit,
    onOpenProject: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val linkedShot by viewModel.linkedShot.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val appSettings = LocalAppSettings.current
    val draft = state.draft
    val task = tasks.firstOrNull { it.taskId == state.activeTaskId }
    val running = task?.isActive == true
    val results = task?.results.orEmpty()
    val visibleResults = results.takeLast(2)
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(workflowPath, workflowName) { viewModel.loadWorkflow(workflowPath, workflowName) }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var pendingField by remember { mutableStateOf<String?>(null) }
    var pendingAudioField by remember { mutableStateOf<String?>(null) }
    var audioPreview by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val key = pendingField
        pendingField = null
        if (uri != null && key != null) viewModel.uploadImage(key, uri)
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val key = pendingAudioField
        pendingAudioField = null
        if (uri != null && key != null) viewModel.uploadAudio(key, uri)
    }
    val multiPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadImagesBatch(uris)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val key = pendingField
        val uri = cameraUri
        pendingField = null
        cameraUri = null
        if (ok && key != null && uri != null) viewModel.uploadImage(key, uri)
    }

    var advancedOpen by rememberSaveable { mutableStateOf(appSettings.advancedExpandedByDefault) }
    var notesOpen by rememberSaveable { mutableStateOf(appSettings.notesExpandedByDefault) }
    var libraryOpen by remember { mutableStateOf(false) }
    var linkPickerOpen by remember { mutableStateOf(false) }
    var linkProjectId by remember { mutableStateOf<String?>(null) }
    var compareOpen by remember { mutableStateOf(false) }
    var viewingResult by remember { mutableStateOf<Int?>(null) }
    var selectedResult by rememberSaveable { mutableStateOf(0) }
    var visionTarget by remember { mutableStateOf<VisionRequest?>(null) }
    var confirmCloudCancel by remember { mutableStateOf(false) }
    val primaryTextKey = draft?.fields
        ?.firstOrNull { it.kind == FieldKind.TEXT && it.multiline && !it.looksLikeNegativePrompt() }
        ?.key
        ?: draft?.fields?.firstOrNull { it.kind == FieldKind.TEXT }?.key
    val resultLabel = stringResource(R.string.gen_result_label)

    LaunchedEffect(task?.taskId) {
        selectedResult = 0
        viewingResult = null
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            DetailTopBar(
                title = draft?.workflowName ?: workflowName,
                onBack = onBack,
                subtitle = if (draft?.isCloud == true) {
                    stringResource(
                        R.string.gen_cloud_subtitle,
                        draft.cloudProviderLabel.ifBlank { stringResource(R.string.gen_cloud_provider_generic) },
                    )
                } else null,
                subtitleColor = MaterialTheme.colorScheme.secondary,
                actions = {
                    if (draft != null) {
                        TextAction(stringResource(R.string.gen_defaults), viewModel::resetRememberedDraft, enabled = !running)
                    }
                    task?.let { t -> TextAction(stringResource(R.string.gen_task), { onOpenTask(t.taskId) }) }
                },
            )
        },
        bottomBar = if (draft == null) null else ({
            BottomActionBar {
                if (running) {
                    IrisSecondaryButton(
                        text = stringResource(R.string.gen_cancel_generation),
                        onClick = { if (draft.isCloud) confirmCloudCancel = true else viewModel.cancelActiveTask() },
                        icon = Icons.Outlined.Close,
                        destructive = true,
                    )
                } else {
                    IrisPrimaryButton(
                        text = if (state.batchCount > 1) {
                            stringResource(R.string.gen_generate_batch, state.batchCount)
                        } else {
                            stringResource(R.string.gen_generate)
                        },
                        onClick = { viewModel.generate() },
                        enabled = state.insight?.compatible != false &&
                            state.uploads.values.none { it.phase == ImageUploadPhase.UPLOADING },
                        icon = Icons.Outlined.AutoAwesome,
                    )
                }
            }
        }),
    ) { pad ->
        when {
            state.loading -> Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error != null -> Box(Modifier.padding(pad).fillMaxSize().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                ErrorCard(title = stringResource(R.string.gen_load_failed), detail = state.error)
            }
            draft == null -> Unit
            else -> {
                val imageFields = draft.fields.filter { it.kind == FieldKind.IMAGE }
                val audioFields = draft.fields.filter { it.kind == FieldKind.AUDIO }
                val textFields = draft.fields.filter { it.kind == FieldKind.TEXT }
                val seedFields = draft.fields.filter { it.kind == FieldKind.SEED }
                val paramFields = draft.fields.filter { it.kind == FieldKind.PARAM }
                val aiIdle = state.aiBusy == null

                LazyColumn(
                    modifier = Modifier.padding(pad).fillMaxSize().imePadding(),
                    contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(if (appSettings.compactUi) Spacing.sm else Spacing.md),
                ) {
                    if (linkedShot == null && projects.isNotEmpty()) {
                        item("studio-optional-link") {
                            InfoBanner(
                                title = stringResource(R.string.studio_link_optional_title),
                                text = stringResource(R.string.studio_link_optional_hint),
                                tone = BannerTone.Info,
                                actionLabel = stringResource(R.string.studio_link_choose),
                                onAction = { linkProjectId = null; linkPickerOpen = true },
                            )
                        }
                    }
                    linkedShot?.let { linked ->
                        item("studio-linked") {
                            InfoBanner(
                                title = if (linked.shotNumber > 0)
                                    stringResource(R.string.studio_linked_title, linked.projectTitle, linked.shotNumber)
                                else stringResource(R.string.studio_linked_project_title, linked.projectTitle),
                                text = stringResource(R.string.studio_linked_detail),
                                tone = BannerTone.Info,
                                actionLabel = stringResource(R.string.studio_back_to_project),
                                onAction = { onOpenProject(linked.projectId) },
                            )
                            if (!running) {
                                TextButton(onClick = { viewModel.linkCurrentDraft(null, null) }) {
                                    Text(stringResource(R.string.studio_link_unlink))
                                }
                            }
                        }
                    }
                    task?.let { t -> item("status") { GenerationStatusCard(t, running) } }
                    state.insight?.let { insight ->
                        item("workflow-insight") {
                            WorkflowInsightCard(
                                insight,
                                workflowId = draft.cloud?.id,
                                workflowDescription = draft.cloud?.description,
                            )
                        }
                    }
                    task?.error?.let { error ->
                        item("error") {
                            ErrorCard(
                                title = errorKindLabel(error.kind),
                                detail = buildString {
                                    error.nodeType?.let { append("$it · ") }
                                    error.nodeId?.let { append("#$it · ") }
                                    append(error.message)
                                },
                            )
                        }
                    }
                    if (draft.warnings.isNotEmpty()) item("warnings") {
                        InfoBanner(text = draft.warnings.joinToString("\n"), tone = BannerTone.Warning)
                    }
                    if (draft.notes.isNotEmpty()) item("notes") {
                        WorkflowNotesCard(draft.notes, notesOpen) { notesOpen = !notesOpen }
                    }
                    task?.preview?.let { preview -> item("preview") { LivePreview(preview) } }

                    item("prompt-header") {
                        SectionHeader(stringResource(R.string.gen_prompt)) {
                            TextAction(stringResource(R.string.gen_presets), { libraryOpen = true })
                        }
                    }
                    items(textFields, key = { it.key }) { field ->
                        val value = draft.values[field.key] ?: field.value
                        PromptFieldBlock(
                            field = field,
                            value = value,
                            hasOverride = draft.values[field.key] != null,
                            workflowName = draft.workflowName,
                            skills = state.skills,
                            aiBusy = state.aiBusy,
                            running = running,
                            onValueChange = { viewModel.updateValue(field.key, it) },
                            onSkill = { skill -> viewModel.aiExpand(field.key, draft.values[field.key] ?: field.value, skill) },
                        )
                    }

                    if (imageFields.isNotEmpty()) {
                        item("image-header") {
                            SectionHeader(stringResource(R.string.gen_reference_images)) {
                                if (imageFields.size > 1) TextAction(stringResource(R.string.gen_multi_pick), { multiPicker.launch("image/*") })
                            }
                        }
                        items(imageFields, key = { it.key }) { field ->
                            val current = draft.values[field.key] ?: field.value
                            ReferenceImageField(
                                label = field.label,
                                value = current,
                                upload = state.uploads[field.key],
                                visionEnabled = aiIdle && !running,
                                visionBusy = state.aiBusy?.startsWith("vision:") == true,
                                loadBitmap = { name -> viewModel.loadInputBitmap(name) },
                                onPick = { pendingField = field.key; picker.launch("image/*") },
                                onCamera = {
                                    pendingField = field.key
                                    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
                                    val file = File(dir, "cam_${System.currentTimeMillis()}.jpg")
                                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                                    cameraUri = uri
                                    camera.launch(uri)
                                },
                                onClear = { viewModel.updateValue(field.key, "") },
                                onVision = { visionTarget = VisionRequest(primaryTextKey, current, field.label) },
                            )
                        }
                    }

                    if (audioFields.isNotEmpty()) {
                        item("audio-header") { SectionHeader(stringResource(R.string.gen_audio_references)) }
                        items(audioFields, key = { it.key }) { field ->
                            AudioReferenceField(
                                label = field.label,
                                value = draft.values[field.key] ?: field.value,
                                upload = state.uploads[field.key],
                                running = running,
                                onPreview = { audioPreview = it },
                                onClear = { viewModel.updateValue(field.key, "") },
                                onPick = { pendingAudioField = field.key; audioPicker.launch("audio/*") },
                            )
                        }
                    }

                    item("seed-batch") {
                        SeedBatchCard(
                            seedFields = seedFields,
                            values = draft.values,
                            randomSeed = draft.randomSeed,
                            batchCount = state.batchCount,
                            batchLocalQueue = state.batchLocalQueue,
                            running = running,
                            batchEnabled = !state.storyboardShot,
                            onToggleRandomSeed = viewModel::toggleRandomSeed,
                            onSeedChange = viewModel::updateValue,
                            onBatchCount = viewModel::setBatchCount,
                            onToggleLocalQueue = viewModel::toggleBatchLocalQueue,
                        )
                    }

                    if (paramFields.isNotEmpty()) item("advanced") {
                        AdvancedParamsCard(
                            fields = paramFields,
                            values = draft.values,
                            expanded = advancedOpen,
                            onToggle = { advancedOpen = !advancedOpen },
                            onChange = viewModel::updateValue,
                        )
                    }

                    resultsSection(
                        results = results,
                        visibleResults = visibleResults,
                        selected = selectedResult,
                        seedInfo = task?.seedInfo,
                        running = running,
                        hasImageFields = imageFields.isNotEmpty(),
                        aiIdle = aiIdle,
                        actions = ResultActions(
                            onCompare = { compareOpen = true },
                            onView = { viewingResult = it },
                            onSelect = { selectedResult = it },
                            onShare = { shareMedia(context, it) },
                            onSave = { viewModel.saveMedia(it) },
                            onVision = { visionTarget = VisionRequest(primaryTextKey, it, resultLabel) },
                            onMakeVideo = { path ->
                                viewModel.prepareCloudVideo(path)?.let { (p, name) -> onOpenCloudVideo(p, name) }
                            },
                            onUseAsReference = { viewModel.useLocalResultAsReference(it) },
                            onRerun = { viewModel.generate() },
                        ),
                    )
                }
            }
        }
    }

    if (linkPickerOpen) {
        val selectedProject = projects.firstOrNull { it.id == linkProjectId }
        AlertDialog(
            onDismissRequest = { linkPickerOpen = false },
            title = { Text(stringResource(R.string.studio_link_choose)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (selectedProject == null) {
                        projects.forEach { project ->
                            TextButton(onClick = { linkProjectId = project.id }) {
                                Text(project.title)
                            }
                        }
                    } else {
                        TextButton(onClick = {
                            viewModel.linkCurrentDraft(selectedProject.id, null)
                            linkPickerOpen = false
                        }) { Text(stringResource(R.string.studio_link_project_level)) }
                        if (draft?.cloud?.kind == "video") {
                            selectedProject.shots.forEachIndexed { index, shot ->
                                TextButton(
                                    onClick = {
                                        viewModel.linkCurrentDraft(selectedProject.id, shot.id)
                                        linkPickerOpen = false
                                    },
                                    enabled = shot.status != "running",
                                ) {
                                    Text(stringResource(R.string.studio_link_shot, index + 1) +
                                        " · " + shot.prompt.take(44))
                                }
                            }
                        }
                        TextButton(onClick = { linkProjectId = null }) {
                            Text(stringResource(R.string.action_back))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { linkPickerOpen = false }) {
                Text(stringResource(R.string.action_cancel))
            } },
        )
    }

    state.aiResult?.let { result ->
        AiResultDialog(
            initial = result.text,
            onDismiss = viewModel::dismissAiResult,
            onReplace = { viewModel.applyAi(true, it) },
            onAppend = { viewModel.applyAi(false, it) },
        )
    }
    if (libraryOpen) ModalBottomSheet(
        onDismissRequest = { libraryOpen = false },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        PromptLibrarySheetContent(
            presets = state.filteredPresets,
            query = state.presetQuery,
            onQuery = viewModel::setPresetQuery,
            onApply = { preset, mode -> viewModel.applyPreset(preset, mode); libraryOpen = false },
            onDelete = viewModel::deletePreset,
            onToggleFavorite = viewModel::togglePresetFavorite,
            onUpdateMeta = viewModel::updatePresetMeta,
            onSaveCurrent = viewModel::saveCurrentPreset,
            onSaveWorkflow = viewModel::saveCurrentWorkflowPreset,
            onExport = viewModel::exportPresets,
            onImport = viewModel::importPresets,
        )
    }
    if (compareOpen) CompareDialog(results) { compareOpen = false }
    audioPreview?.let { path ->
        MediaViewerDialog(
            path = path,
            onClose = { audioPreview = null },
            onShare = { shareMedia(context, path) },
        )
    }
    viewingResult?.let { index ->
        visibleResults.getOrNull(index)?.let { result ->
            MediaViewerDialog(
                path = result.localPath,
                onClose = { viewingResult = null },
                onSave = { viewModel.saveMedia(result.localPath) },
                onShare = { shareMedia(context, result.localPath) },
            )
        }
    }
    visionTarget?.let { target ->
        VisionInstructionDialog(
            targetLabel = target.label,
            onDismiss = { visionTarget = null },
            onRun = { instruction ->
                viewModel.visionRewrite(target.fieldKey, target.imageValue, instruction)
                visionTarget = null
            },
        )
    }
    if (confirmCloudCancel) {
        CancelCloudTaskDialog(
            onConfirm = {
                confirmCloudCancel = false
                viewModel.cancelActiveTask()
            },
            onDismiss = { confirmCloudCancel = false },
        )
    }
}

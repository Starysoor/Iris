package com.comfyremote.app.feature.project

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationTask
import com.comfyremote.app.data.project.StudioWorkflowRecipes
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.BottomActionBar
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.CollapsibleCard
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisPrimaryButton
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisSwitch
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.ListRow
import com.comfyremote.app.ui.components.SectionHeader
import com.comfyremote.app.ui.components.StatusChip
import com.comfyremote.app.ui.components.TextAction
import kotlinx.coroutines.launch

/**
 * The shot workspace: shared style meta, per-shot timeline and the generate
 * flow. A slot inside the project, never a replacement for the create page.
 */
@Composable
fun ShotsWorkspaceScreen(
    viewModel: ProjectViewModel,
    projectId: String,
    initialShotId: String?,
    onBack: () -> Unit,
    onOpenShot: (String, String) -> Unit,
    onOpenOverview: () -> Unit,
    onOpenAssets: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val projects by viewModel.projects.collectAsState()
    val workflows by viewModel.workflows.collectAsState()
    val stageWorkflows by viewModel.stageWorkflows.collectAsState()
    val stageInspection by viewModel.stageInspection.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val message by viewModel.message.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var addingShot by remember { mutableStateOf(false) }
    var insertAfterShotId by remember { mutableStateOf<String?>(null) }
    var selectedShotId by rememberSaveable(projectId) { mutableStateOf(initialShotId) }
    var styleOpen by rememberSaveable(projectId) { mutableStateOf(false) }
    var assetShotId by remember { mutableStateOf<String?>(null) }
    var selectedAssetIds by remember { mutableStateOf(setOf<String>()) }
    var shotWorkflowFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var inspecting by remember { mutableStateOf<Pair<String, String>?>(null) }
    var takesForShotId by remember { mutableStateOf<String?>(null) }
    var editingShotId by remember { mutableStateOf<String?>(null) }
    var framePick by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val activeShotId = selectedShotId?.takeIf { id -> project?.shots?.any { it.id == id } == true }
        ?: project?.shots?.firstOrNull { it.status != "done" }?.id ?: project?.shots?.firstOrNull()?.id
    val allDoneText = stringResource(R.string.board_all_done)
    val runningText = stringResource(R.string.gen_vm_storyboard_running)
    val anyRunning = project?.shots?.any { it.status == "running" } == true
    val allDone = project?.shots?.isNotEmpty() == true && project.shots.all { it.status == "done" }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    LaunchedEffect(initialShotId, project?.id) {
        if (initialShotId != null && selectedShotId == initialShotId) {
            val index = project?.shots?.indexOfFirst { it.id == initialShotId } ?: -1
            if (index >= 0) listState.scrollToItem(index + 4)
        }
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            DetailTopBar(
                title = project?.title ?: stringResource(R.string.proj_title),
                onBack = onBack,
                subtitle = project?.let { p ->
                    pluralStringResource(
                        R.plurals.board_progress,
                        p.shots.size,
                        p.shots.count { it.status == "done" },
                        p.shots.size,
                    )
                },
            )
        },
        bottomBar = if (project == null) null else ({
            BottomActionBar {
                IrisPrimaryButton(
                    text = when {
                        allDone -> allDoneText
                        anyRunning -> stringResource(R.string.board_status_running)
                        else -> stringResource(R.string.board_next_shot)
                    },
                    icon = Icons.Outlined.PlayArrow,
                    enabled = project.shots.isNotEmpty() && !anyRunning && !allDone,
                    onClick = {
                        viewModel.nextShot(project.id)?.let { shot ->
                            viewModel.openShot(project.id, shot.id)?.let { (path, name) ->
                                onOpenShot(path, name)
                            }
                        } ?: run {
                            scope.launch {
                                snackbar.showSnackbar(
                                    if (allDone) allDoneText else runningText,
                                )
                            }
                        }
                    },
                )
            }
        }),
    ) { pad ->
        if (project == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.proj_not_found), icon = Icons.Outlined.Movie)
            }
            return@IrisScaffold
        }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item("project-navigation") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    TextAction(stringResource(R.string.studio_workspace_overview), onClick = onOpenOverview)
                    TextAction(stringResource(R.string.board_assets), onClick = onOpenAssets)
                    TextAction(stringResource(R.string.proj_open_timeline), onClick = onOpenTimeline)
                }
            }
            item("meta") {
                CollapsibleCard(
                    title = stringResource(R.string.board_style_prompt),
                    expanded = styleOpen,
                    onToggle = { styleOpen = !styleOpen },
                ) {
                    Text(
                        workflows.firstOrNull {
                            it.path == project.workflowPath || it.workflowId == project.workflowId
                        }?.let { "${it.providerLabel} · ${it.name}" }
                            ?: project.workflowPath.ifBlank { project.workflowId },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IrisTextField(
                        value = project.stylePrompt,
                        onValueChange = { viewModel.setStyle(project.id, it) },
                        label = stringResource(R.string.board_style_prompt),
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                stringResource(R.string.board_chain_frames),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(R.string.board_chain_frames_hint),
                                style = IrisType.metaSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IrisSwitch(
                            checked = project.chainFrames,
                            onCheckedChange = { enabled ->
                                viewModel.setChainFrames(project.id, enabled)
                            },
                        )
                    }
                }
            }

            item("overview") {
                val chips = listOf(
                    Triple(stringResource(R.string.board_status_running), project.shots.count { it.status == "running" }, ChipTone.Accent),
                    Triple(stringResource(R.string.board_status_draft), project.shots.count { it.status != "running" && it.status != "done" && it.status != "failed" }, ChipTone.Neutral),
                    Triple(stringResource(R.string.board_status_failed), project.shots.count { it.status == "failed" }, ChipTone.Error),
                    Triple(stringResource(R.string.board_status_done), project.shots.count { it.status == "done" }, ChipTone.Success),
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    chips.filter { it.second > 0 }.forEach { (label, count, tone) ->
                        StatusChip("$label $count", tone = tone)
                    }
                }
            }

            item("shots-heading") {
                SectionHeader(stringResource(R.string.board_shots)) {
                    TextAction(stringResource(R.string.board_add_shot), onClick = { addingShot = true })
                }
            }

            if (project.shots.isEmpty()) {
                item("empty") {
                    EmptyState(
                        title = stringResource(R.string.board_no_shots),
                        detail = stringResource(R.string.board_no_shots_detail),
                        icon = Icons.Outlined.Movie,
                        action = {
                            TextAction(stringResource(R.string.board_add_shot), onClick = { addingShot = true })
                        },
                    )
                }
            } else {
                itemsIndexed(project.shots, key = { _, shot -> shot.id }) { index, shot ->
                    if (shot.id != activeShotId) {
                        val (status, tone) = when (shot.status) {
                            "done" -> R.string.board_status_done to ChipTone.Success
                            "running" -> R.string.board_status_running to ChipTone.Accent
                            "failed" -> R.string.board_status_failed to ChipTone.Error
                            else -> R.string.board_status_draft to ChipTone.Neutral
                        }
                        ListRow(
                            title = stringResource(R.string.board_shot_number, index + 1),
                            subtitle = shot.prompt.takeIf(String::isNotBlank),
                            subtitleMaxLines = 2,
                            meta = { StatusChip(stringResource(status), tone = tone) },
                            onClick = { selectedShotId = shot.id },
                        )
                        return@itemsIndexed
                    }
                    val previous = project.shots.getOrNull(index - 1)
                    val next = project.shots.getOrNull(index + 1)
                    ShotTimelineItem(
                        index = index,
                        shot = shot,
                        workflowLabel = shotWorkflowLabel(shot, project, workflows),
                        referenceAssets = shot.referenceAssetIds.mapNotNull { id ->
                            project.assets.firstOrNull { it.id == id }
                        },
                        referenceFromPrevious = !shot.referenceManual && shot.referencePath != null &&
                            shot.referencePath == previous?.lastFramePath,
                        feedsNext = project.chainFrames && shot.lastFramePath != null &&
                            next != null && !next.referenceManual &&
                            next.referenceAssetIds.isEmpty() &&
                            next.referencePath == shot.lastFramePath,
                        canMoveUp = index > 0,
                        canMoveDown = index < project.shots.lastIndex,
                        canUseAssets = project.assets.isNotEmpty(),
                        previousFramePath = previous?.lastFramePath,
                        onUsePreviousFrame = {
                            viewModel.usePreviousFrame(project.id, shot.id, previous?.lastFramePath)
                        },
                        onOpen = {
                            viewModel.openShot(project.id, shot.id)?.let { (path, name) ->
                                onOpenShot(path, name)
                            }
                        },
                        onDelete = { viewModel.deleteShot(project.id, shot.id) },
                        onMoveUp = { viewModel.moveShot(project.id, shot.id, -1) },
                        onMoveDown = { viewModel.moveShot(project.id, shot.id, 1) },
                        onDuplicate = { viewModel.duplicateShot(project.id, shot.id) },
                        onInsertAfter = { insertAfterShotId = shot.id },
                        onPickWorkflow = { shotWorkflowFor = shot.id to "video" },
                        onPickAsset = {
                            assetShotId = shot.id
                            selectedAssetIds = shot.referenceAssetIds.toSet()
                        },
                        onEditShot = { editingShotId = shot.id },
                        onPickFirstFrame = { framePick = shot.id to true },
                        onPickLastFrame = { framePick = shot.id to false },
                        onOpenTakes = if (shot.takes.isNotEmpty()) {
                            { takesForShotId = shot.id }
                        } else {
                            null
                        },
                        onClearReference = { viewModel.clearShotReference(project.id, shot.id) },
                    )
                    val shotTasks = tasks.filter {
                        it.request.storyboardProjectId == project.id && it.request.storyboardShotId == shot.id
                    }
                    val videoPath = StudioWorkflowRecipes.workflowPath(project, "video", shot)
                    val videoTask = shotTasks.firstOrNull { it.taskId == shot.taskId }
                    if (videoPath != null || videoTask != null || shot.resultPath != null) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            if (videoPath != null && stageWorkflows.any { it.path == videoPath && it.kind == "video" }) {
                                TextAction(stringResource(R.string.studio_contract_inspect), onClick = {
                                    inspecting = shot.id to "video"
                                    viewModel.inspectStage(projectId, "video", videoPath, shot.id)
                                })
                            }
                            videoTask?.let { task ->
                                TextAction(stringResource(R.string.gen_task), onClick = { onOpenTask(task.taskId) })
                                if (task.phase == GenerationPhase.SUCCEEDED) {
                                    TextAction(stringResource(R.string.studio_history_as_asset), onClick = {
                                        viewModel.keepStageResult(projectId, shot.id, task.taskId)
                                    })
                                }
                            }
                            if (shot.status == "done" && shot.resultPath != null) {
                                TextAction(stringResource(R.string.studio_workspace_add_timeline), onClick = {
                                    viewModel.addShotToTimeline(project.id, shot.id)
                                })
                            }
                        }
                    }
                    for ((kind, title) in listOf("image" to R.string.studio_workspace_keyframe,
                        "audio" to R.string.studio_workspace_audio)) {
                        val path = StudioWorkflowRecipes.workflowPath(project, kind, shot)
                        val model = stageWorkflows.firstOrNull { it.path == path && it.kind == kind }
                        val task = shotTasks.filter { it.request.studioStageKind == kind }
                            .maxByOrNull { it.createdAt }
                        ShotPreparationCard(
                            title = stringResource(title),
                            model = model,
                            task = task,
                            audio = kind == "audio",
                            canRun = shot.status != "running",
                            onPick = { shotWorkflowFor = shot.id to kind },
                            onInspect = {
                                if (path != null) {
                                    inspecting = shot.id to kind
                                    viewModel.inspectStage(projectId, kind, path, shot.id)
                                }
                            },
                            onLaunch = {
                                viewModel.openShotStage(project.id, shot.id, kind)?.let { (path, name) ->
                                    onOpenShot(path, name)
                                }
                            },
                            onTask = onOpenTask,
                            onUse = { id, firstFrame -> viewModel.useShotStageResult(project.id, shot.id, id, firstFrame) },
                            onKeep = { id -> viewModel.keepStageResult(project.id, shot.id, id) },
                        )
                    }
                }
            }
        }
    }

    if (addingShot) {
        AddShotDialog(
            onDismiss = { addingShot = false },
            onAdd = { prompt, notes ->
                viewModel.addShot(projectId, prompt, notes)
                selectedShotId = viewModel.project(projectId)?.shots?.lastOrNull()?.id
                addingShot = false
            },
        )
    }
    insertAfterShotId?.let { shotId ->
        AddShotDialog(
            onDismiss = { insertAfterShotId = null },
            onAdd = { prompt, notes ->
                viewModel.insertShotAfter(projectId, shotId, prompt, notes)
                insertAfterShotId = null
            },
        )
    }
    shotWorkflowFor?.let { (shotId, kind) ->
        val shot = project?.shots?.firstOrNull { it.id == shotId }
        val currentPath = if (kind == "video") shot?.workflowPath else shot?.stageWorkflowPaths?.get(kind)
        ShotWorkflowDialog(
            currentPath = currentPath,
            workflows = stageWorkflows.filter { it.kind == kind },
            onDismiss = { shotWorkflowFor = null },
            onFollowProject = {
                viewModel.setShotStageWorkflow(projectId, shotId, kind, null)
                shotWorkflowFor = null
            },
            onPick = { wf ->
                viewModel.setShotStageWorkflow(projectId, shotId, kind, wf.path)
                shotWorkflowFor = null
            },
        )
    }
    assetShotId?.let { shotId ->
        val current = project
        if (current != null) {
            AssetPickerDialog(
                project = current,
                selectedAssetIds = selectedAssetIds,
                onToggle = { id ->
                    selectedAssetIds = if (id in selectedAssetIds) {
                        selectedAssetIds - id
                    } else {
                        selectedAssetIds + id
                    }
                },
                onDismiss = { assetShotId = null },
                onApply = {
                    viewModel.assignAssetsToShot(projectId, shotId, selectedAssetIds.toList())
                    assetShotId = null
                },
            )
        } else {
            assetShotId = null
        }
    }
    editingShotId?.let { shotId ->
        val shot = project?.shots?.firstOrNull { it.id == shotId }
        if (shot == null) {
            editingShotId = null
        } else {
            EditShotDialog(
                shot = shot,
                onDismiss = { editingShotId = null },
                onSave = { prompt, notes ->
                    viewModel.updateShot(projectId, shotId, prompt)
                    viewModel.updateShotCameraNotes(projectId, shotId, notes.orEmpty())
                    editingShotId = null
                },
            )
        }
    }
    takesForShotId?.let { shotId ->
        val shot = project?.shots?.firstOrNull { it.id == shotId }
        if (shot == null) {
            takesForShotId = null
        } else {
            TakeWorkshopDialog(
                shot = shot,
                onDismiss = { takesForShotId = null },
                onSelect = { takeId ->
                    viewModel.switchToTake(projectId, shotId, takeId)
                },
                onToggleFavorite = { takeId, favorite ->
                    viewModel.setTakeFavorite(projectId, shotId, takeId, favorite)
                },
                onDiscard = { takeId ->
                    viewModel.discardTake(projectId, shotId, takeId)
                },
                onDelete = { takeId ->
                    viewModel.deleteTake(projectId, shotId, takeId)
                },
            )
        }
    }
    inspecting?.let { (shotId, kind) ->
        val shot = project?.shots?.firstOrNull { it.id == shotId }
        val path = if (project != null && shot != null) StudioWorkflowRecipes.workflowPath(project, kind, shot) else null
        if (project != null && path != null) {
            StudioContractDialog(
                project = project, kind = kind, workflowPath = path, inspection = stageInspection, shotId = shotId,
                onBindField = { field, assetId ->
                    viewModel.setStageMediaBinding(projectId, kind, path, field, assetId, shotId)
                },
                onDismiss = { inspecting = null },
                onRun = {
                    viewModel.openShotStage(projectId, shotId, kind)?.let { (workflowPath, name) ->
                        inspecting = null
                        onOpenShot(workflowPath, name)
                    }
                },
            )
        }
    }
    framePick?.let { (shotId, first) ->
        if (project == null) {
            framePick = null
        } else {
            AssetSinglePickerDialog(
                project = project,
                title = stringResource(
                    if (first) R.string.proj_shot_set_first_frame else R.string.proj_shot_set_last_frame,
                ),
                onDismiss = { framePick = null },
                onPick = { assetId ->
                    viewModel.setShotFrameAsset(projectId, shotId, assetId, first)
                    framePick = null
                },
            )
        }
    }
}

@Composable
private fun ShotPreparationCard(
    title: String,
    model: ProjectWorkflowOption?,
    task: GenerationTask?,
    audio: Boolean,
    canRun: Boolean,
    onPick: () -> Unit,
    onInspect: () -> Unit,
    onLaunch: () -> Unit,
    onTask: (String) -> Unit,
    onUse: (String, Boolean) -> Unit,
    onKeep: (String) -> Unit,
) {
    IrisCard {
        SectionHeader(title) {
            val status = when {
                task?.isActive == true -> R.string.board_status_running to ChipTone.Accent
                task?.phase == GenerationPhase.SUCCEEDED -> R.string.board_status_done to ChipTone.Success
                task?.phase == GenerationPhase.FAILED -> R.string.board_status_failed to ChipTone.Error
                else -> null
            }
            status?.let { (label, tone) -> StatusChip(stringResource(label), tone = tone) }
        }
        Text(
            model?.let { "${it.providerLabel} · ${it.name}" }
                ?: stringResource(R.string.studio_workspace_pick_model),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            TextAction(stringResource(R.string.board_shot_workflow), onClick = onPick)
            if (model != null) TextAction(stringResource(R.string.studio_contract_inspect), onClick = onInspect)
            TextAction(
                stringResource(R.string.studio_recipe_run_stage),
                onClick = onLaunch,
                enabled = canRun && model != null && task?.isActive != true,
            )
            task?.let { TextAction(stringResource(R.string.gen_task), onClick = { onTask(it.taskId) }) }
        }
        if (task?.phase == GenerationPhase.SUCCEEDED && task.results.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                TextAction(stringResource(R.string.studio_history_as_asset), onClick = { onKeep(task.taskId) })
                if (audio) {
                    TextAction(stringResource(R.string.studio_workspace_add_timeline),
                        onClick = { onUse(task.taskId, true) })
                } else {
                    TextAction(stringResource(R.string.studio_workspace_use_first),
                        onClick = { onUse(task.taskId, true) }, enabled = canRun)
                    TextAction(stringResource(R.string.studio_workspace_use_last),
                        onClick = { onUse(task.taskId, false) }, enabled = canRun)
                }
            }
        }
    }
}

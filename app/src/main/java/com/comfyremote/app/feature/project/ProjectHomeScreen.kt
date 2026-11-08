package com.comfyremote.app.feature.project

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.project.taskCostStats
import com.comfyremote.app.data.project.StudioJourney
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.feature.tasks.ElapsedText
import com.comfyremote.app.feature.tasks.RunningNodeProgress
import com.comfyremote.app.feature.tasks.TaskChips
import com.comfyremote.app.feature.tasks.TaskStatusLines
import com.comfyremote.app.feature.tasks.taskTone
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import androidx.compose.material.icons.outlined.Image
import com.comfyremote.app.ui.components.BannerTone
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.InfoBanner
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisPrimaryButton
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.IrisTonalButton
import com.comfyremote.app.ui.components.SectionHeader
import com.comfyremote.app.ui.components.StatusChip
import com.comfyremote.app.ui.components.TextAction

/**
 * The project home hub: overview, progress, assets and entries into the shot
 * workspace and the in-project task view. Generation itself stays in the
 * workspace and the create page.
 */
@Composable
fun ProjectHomeScreen(
    viewModel: ProjectViewModel,
    projectId: String,
    onBack: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenDirector: () -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val projects by viewModel.projects.collectAsState()
    val workflows by viewModel.workflows.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val message by viewModel.message.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var projectSettings by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            DetailTopBar(
                title = project?.title ?: stringResource(R.string.proj_title),
                onBack = onBack,
                subtitle = project?.let { p ->
                    androidx.compose.ui.res.pluralStringResource(
                        R.plurals.board_progress,
                        p.shots.size,
                        p.shots.count { it.status == "done" },
                        p.shots.size,
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
                        }
                        DropdownMenu(menuOpen, { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_project_settings)) },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = {
                                    menuOpen = false
                                    projectSettings = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_delete_project), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuOpen = false
                                    if (viewModel.deleteProject(projectId)) onBack()
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (project == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.proj_not_found), icon = Icons.Outlined.Movie)
            }
            return@IrisScaffold
        }
        val projectTasks = tasks.filter { it.request.storyboardProjectId == projectId }
            .sortedByDescending { it.request.createdAt }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item("overview") {
                OverviewCard(project, workflows)
            }
            item("progress") {
                ProgressChips(project)
            }
            item("next-step") {
                StudioNextStep(
                    project = project,
                    onOpenWorkspace = onOpenWorkspace,
                    onOpenTimeline = onOpenTimeline,
                )
            }
            item("workspace") {
                IrisPrimaryButton(
                    text = stringResource(R.string.proj_open_workspace),
                    icon = Icons.Outlined.Movie,
                    onClick = onOpenWorkspace,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item("timeline") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    TextAction(stringResource(R.string.proj_open_timeline), onClick = onOpenTimeline)
                    TextAction(stringResource(R.string.proj_open_director), onClick = onOpenDirector)
                }
            }
            item("assets-heading") {
                SectionHeader(stringResource(R.string.board_assets)) {
                    if (project.assets.isNotEmpty()) {
                        Text(
                            project.assets.size.toString(),
                            style = IrisType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = Spacing.xs),
                        )
                    }
                    TextAction(stringResource(R.string.proj_assets_manage), onClick = onOpenLibrary)
                }
            }
            item("assets") {
                if (project.assets.isEmpty()) {
                    Text(
                        stringResource(R.string.board_assets_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        items(project.assets, key = { it.id }) { asset ->
                            AssetCard(
                                asset = asset,
                                label = assetDisplayLabel(project, asset),
                            ) { viewModel.removeAsset(project.id, asset.id) }
                        }
                    }
                }
            }
            item("tasks-heading") {
                val stats = taskCostStats(tasks, projectId)
                SectionHeader(stringResource(R.string.proj_tasks)) {
                    if (stats.count > 0) {
                        Text(
                            stringResource(R.string.proj_tasks_summary, stats.count, stats.costSum),
                            style = IrisType.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = Spacing.xs),
                        )
                    }
                }
            }
            if (project.budget != null && taskCostStats(tasks, projectId).costSum > project.budget!!) {
                item("budget-warning") {
                    InfoBanner(
                        text = stringResource(
                            R.string.proj_budget_exceeded,
                            project.budget!!,
                            taskCostStats(tasks, projectId).costSum,
                        ),
                        tone = BannerTone.Warning,
                    )
                }
            }
            if (projectTasks.isEmpty()) {
                item("tasks-empty") {
                    Text(
                        stringResource(R.string.proj_tasks_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(projectTasks.size, key = { projectTasks[it].taskId }) { index ->
                    val task = projectTasks[index]
                    ProjectTaskRow(task = task, onClick = { onOpenTask(task.taskId) })
                }
            }
        }
    }

    if (projectSettings && project != null) {
        ProjectSettingsDialog(
            project = project,
            workflows = workflows,
            onDismiss = { projectSettings = false },
            onSave = { title, synopsis, workflowPath, aspect, defaults, duration, budget ->
                viewModel.updateProjectSettings(
                    project.id, title, synopsis, workflowPath, aspect, defaults, duration, budget,
                )
                projectSettings = false
            },
        )
    }
}

@Composable
private fun OverviewCard(
    project: com.comfyremote.app.data.project.VideoProject,
    workflows: List<ProjectWorkflowOption>,
) {
    IrisCard {
        Text(
            workflows.firstOrNull {
                it.path == project.workflowPath || it.workflowId == project.workflowId
            }?.let { "${it.providerLabel} · ${it.name}" }
                ?: project.workflowPath.ifBlank { project.workflowId },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (project.synopsis.isNotBlank()) {
            Text(
                project.synopsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            if (project.aspectRatio.isNotBlank()) {
                StatusChip(project.aspectRatio, tone = ChipTone.Neutral)
            }
            project.targetDurationSeconds?.let { seconds ->
                StatusChip(
                    if (seconds >= 60) {
                        stringResource(R.string.proj_duration_minutes, seconds / 60)
                    } else {
                        stringResource(R.string.proj_duration_seconds, seconds)
                    },
                    tone = ChipTone.Neutral,
                )
            }
            if (project.stylePrompt.isNotBlank()) {
                StatusChip(
                    stringResource(R.string.proj_style_set),
                    tone = ChipTone.Neutral,
                )
            }
        }
        if (project.stylePrompt.isNotBlank()) {
            Text(
                project.stylePrompt,
                style = IrisType.metaSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgressChips(project: com.comfyremote.app.data.project.VideoProject) {
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

/** Read-only row over an engine task that belongs to this project. */
@Composable
private fun ProjectTaskRow(
    task: com.comfyremote.app.data.generation.GenerationTask,
    onClick: () -> Unit,
) {
    IrisCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val result = task.results.firstOrNull()
            Box(
                Modifier
                    .size(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (result != null) {
                    MediaThumbnail(result.localPath, 48.dp, Modifier.size(48.dp))
                } else {
                    Icon(
                        Icons.Outlined.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    task.request.workflowName.ifBlank { task.request.workflowPath },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TaskStatusLines(task)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TaskChips(task, recoveredLabel = stringResource(R.string.tasks_recovered))
                    if (task.request.provider != "comfyui") {
                        StatusChip(
                            task.request.providerLabel.ifBlank { task.request.provider },
                            tone = ChipTone.Neutral,
                        )
                    }
                    task.request.estimatedCloudCost?.let {
                        StatusChip(
                            stringResource(R.string.proj_task_cost, it),
                            tone = ChipTone.Neutral,
                        )
                    }
                    ElapsedText(task, IrisType.metaSmall)
                }
            }
        }
        RunningNodeProgress(task)
    }
}

/** Next action is derived from real project state; asset import is recommended, not required. */
@Composable
private fun StudioNextStep(
    project: com.comfyremote.app.data.project.VideoProject,
    onOpenWorkspace: () -> Unit,
    onOpenTimeline: () -> Unit,
) {
    val progress = StudioJourney.inspect(project)
    val nextLabel = when (progress.next) {
        StudioJourney.NextStep.ADD_SHOTS -> R.string.studio_next_add_shots
        StudioJourney.NextStep.GENERATE -> R.string.studio_next_generate
        StudioJourney.NextStep.ARRANGE -> R.string.studio_next_arrange
        StudioJourney.NextStep.REVIEW -> R.string.studio_next_review
    }
    IrisCard {
        Text(stringResource(R.string.studio_progress_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                R.string.studio_progress_detail,
                progress.completedShots, progress.totalShots,
                project.assets.size, progress.timelineClips,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (project.assets.isEmpty()) {
            Text(
                stringResource(R.string.studio_assets_optional),
                style = IrisType.metaSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IrisTonalButton(
            text = stringResource(nextLabel),
            onClick = if (progress.next == StudioJourney.NextStep.ARRANGE ||
                progress.next == StudioJourney.NextStep.REVIEW) onOpenTimeline else onOpenWorkspace,
            icon = Icons.Outlined.Movie,
        )
    }
}

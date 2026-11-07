package com.comfyremote.app.feature.project

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.data.project.ProjectShot
import com.comfyremote.app.data.project.ShotTake
import com.comfyremote.app.data.project.VideoProject
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.IrisSwitch
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.TextAction

/** Field-like dropdown for picking the cloud video workflow. */
@Composable
internal fun WorkflowPicker(
    workflows: List<ProjectWorkflowOption>,
    selectedPath: String,
    onSelect: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable { menu = true },
        ) {
            Row(
                Modifier.padding(start = Spacing.lg, end = Spacing.md, top = 14.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    workflows.firstOrNull { it.path == selectedPath }?.let {
                        "${it.providerLabel} · ${it.name}"
                    } ?: stringResource(R.string.proj_no_default_workflow),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.proj_no_default_workflow)) },
                onClick = { onSelect(""); menu = false },
            )
            workflows.forEach { workflow ->
                DropdownMenuItem(
                    text = { Text("${workflow.providerLabel} · ${workflow.name}") },
                    onClick = { onSelect(workflow.path); menu = false },
                )
            }
        }
    }
}

@Composable
internal fun CreateProjectDialog(
    workflows: List<ProjectWorkflowOption>,
    onDismiss: () -> Unit,
    onCreate: (title: String, workflowPath: String, style: String, synopsis: String, chainFrames: Boolean) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var synopsis by remember { mutableStateOf("") }
    var chainFrames by remember { mutableStateOf(true) }
    var workflowPath by remember {
        mutableStateOf(
            ""
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_new)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IrisTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = stringResource(R.string.board_field_title),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = synopsis,
                    onValueChange = { synopsis = it },
                    label = stringResource(R.string.proj_field_synopsis),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = style,
                    onValueChange = { style = it },
                    label = stringResource(R.string.board_field_style_optional),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                WorkflowPicker(
                    workflows = workflows,
                    selectedPath = workflowPath,
                    onSelect = { workflowPath = it },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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
                        checked = chainFrames,
                        onCheckedChange = { chainFrames = it },
                    )
                }
            }
        },
        confirmButton = {
            TextAction(
                text = stringResource(R.string.board_create),
                onClick = { onCreate(title, workflowPath, style, synopsis, chainFrames) },
                enabled = title.isNotBlank(),
            )
        },
        dismissButton = {
            TextAction(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
internal fun ProjectSettingsDialog(
    project: VideoProject,
    workflows: List<ProjectWorkflowOption>,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        synopsis: String,
        workflowPath: String,
        aspectRatio: String,
        defaults: Map<String, String>,
        targetDurationSeconds: Int?,
        budget: Double?,
    ) -> Unit,
) {
    var title by remember(project.title) { mutableStateOf(project.title) }
    var synopsis by remember(project.synopsis) { mutableStateOf(project.synopsis) }
    var workflowPath by remember(project.workflowPath) {
        mutableStateOf(project.workflowPath.ifBlank { project.workflowId })
    }
    var aspectRatio by remember(project.aspectRatio) { mutableStateOf(project.aspectRatio) }
    var durationText by remember(project.targetDurationSeconds) {
        mutableStateOf(project.targetDurationSeconds?.toString().orEmpty())
    }
    var budgetText by remember(project.budget) {
        mutableStateOf(project.budget?.toString().orEmpty())
    }
    val budgetValid = budgetText.isBlank() ||
        (budgetText.toDoubleOrNull()?.let { it.isFinite() && it > 0.0 } == true)
    var defaultsText by remember(project.defaultValues) {
        mutableStateOf(project.defaultValues.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }
    var workflowMenu by remember { mutableStateOf(false) }
    val workflowName = workflows.firstOrNull {
        it.path == workflowPath || it.workflowId == workflowPath
    }?.let { "${it.providerLabel} · ${it.name}" } ?: workflowPath

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_project_settings)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IrisTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = stringResource(R.string.board_project_title),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = synopsis,
                    onValueChange = { synopsis = it },
                    label = stringResource(R.string.proj_field_synopsis),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box {
                    TextAction(
                        text = workflowName.ifBlank { stringResource(R.string.board_project_workflow) },
                        onClick = { workflowMenu = true },
                    )
                    DropdownMenu(workflowMenu, { workflowMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.proj_no_default_workflow)) },
                            onClick = { workflowPath = ""; workflowMenu = false },
                        )
                        workflows.forEach { wf ->
                            DropdownMenuItem(
                                text = { Text("${wf.providerLabel} · ${wf.name}") },
                                onClick = {
                                    workflowPath = wf.path
                                    workflowMenu = false
                                },
                            )
                        }
                    }
                }
                IrisTextField(
                    value = aspectRatio,
                    onValueChange = { aspectRatio = it },
                    label = stringResource(R.string.board_aspect_ratio),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = durationText,
                    onValueChange = { durationText = it.filter(Char::isDigit).take(4) },
                    label = stringResource(R.string.proj_field_duration),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = budgetText,
                    onValueChange = { budgetText = it.take(16) },
                    label = stringResource(R.string.proj_field_budget),
                    supportingText = stringResource(R.string.proj_field_budget_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = defaultsText,
                    onValueChange = { defaultsText = it },
                    label = stringResource(R.string.board_default_params),
                    supportingText = stringResource(R.string.board_default_params_hint),
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val defaults = defaultsText.lineSequence().mapNotNull { line ->
                    val index = line.indexOf('=')
                    if (index <= 0) null else {
                        val key = line.substring(0, index).trim()
                        val value = line.substring(index + 1).trim()
                        if (key.isBlank()) null else key to value
                    }
                }.toMap()
                onSave(title, synopsis, workflowPath, aspectRatio, defaults, durationText.toIntOrNull(), budgetText.toDoubleOrNull())
            }, enabled = budgetValid) { Text(stringResource(R.string.board_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun AddShotDialog(onDismiss: () -> Unit, onAdd: (prompt: String, cameraNotes: String?) -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var cameraNotes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_add_shot)) },
        icon = { Icon(Icons.Outlined.Add, null) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                IrisTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = stringResource(R.string.board_shot_prompt),
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = cameraNotes,
                    onValueChange = { cameraNotes = it },
                    label = stringResource(R.string.proj_shot_camera_notes),
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextAction(
                stringResource(R.string.board_add),
                onClick = { onAdd(prompt, cameraNotes.trim().ifBlank { null }) },
                enabled = prompt.isNotBlank(),
            )
        },
        dismissButton = {
            TextAction(stringResource(R.string.action_cancel), onClick = onDismiss, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
internal fun EditShotDialog(
    shot: ProjectShot,
    onDismiss: () -> Unit,
    onSave: (prompt: String, cameraNotes: String?) -> Unit,
) {
    var prompt by remember(shot.id) { mutableStateOf(shot.prompt) }
    var cameraNotes by remember(shot.id) { mutableStateOf(shot.cameraNotes.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_shot_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                IrisTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = stringResource(R.string.board_shot_prompt),
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = cameraNotes,
                    onValueChange = { cameraNotes = it },
                    label = stringResource(R.string.proj_shot_camera_notes),
                    minLines = 1,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextAction(
                stringResource(R.string.board_save),
                onClick = { onSave(prompt, cameraNotes.trim().ifBlank { null }) },
                enabled = prompt.isNotBlank(),
            )
        },
        dismissButton = {
            TextAction(stringResource(R.string.action_cancel), onClick = onDismiss, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

/**
 * The take workshop: every recorded generation attempt for one shot with its
 * outcome, params and cost. Promoting a take changes the shot's live result;
 * discarding only marks the record and never touches files.
 */
@Composable
internal fun TakeWorkshopDialog(
    shot: ProjectShot,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String, Boolean) -> Unit,
    onDiscard: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showDiscarded by remember(shot.id) { mutableStateOf(false) }
    val takes = shot.takes
        .filter { showDiscarded || it.status != "discarded" }
        .sortedWith(compareByDescending<ShotTake> { it.favorite }.thenByDescending { it.createdAt })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_takes_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (shot.takes.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showDiscarded, onCheckedChange = { showDiscarded = it })
                        Text(
                            stringResource(R.string.proj_takes_show_discarded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (takes.isEmpty()) {
                    Text(
                        stringResource(R.string.proj_takes_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    takes.forEach { take ->
                        val selected = shot.selectedTakeId == take.id
                        Column(Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                take.resultPath?.let { path ->
                                    MediaThumbnail(
                                        path,
                                        56.dp,
                                        Modifier.clip(MaterialTheme.shapes.medium),
                                    )
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    ) {
                                        if (take.favorite) {
                                            Icon(
                                                Icons.Filled.Star,
                                                null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                        if (selected) {
                                            Text(
                                                stringResource(R.string.proj_takes_primary),
                                                style = IrisType.metaSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        Text(
                                            takeStatusLabel(take.status),
                                            style = IrisType.metaSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        buildString {
                                            append(take.workflowPath.ifBlank { "-" })
                                            take.seed?.let { append(" · seed ").append(it) }
                                        },
                                        style = IrisType.metaSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    take.costEstimate?.let {
                                        Text(
                                            stringResource(R.string.proj_takes_cost, it),
                                            style = IrisType.metaSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    take.error?.let {
                                        Text(
                                            it,
                                            style = IrisType.metaSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            maxLines = 2,
                                        )
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                if (take.status == "done" && !selected && shot.status != "running") {
                                    TextAction(stringResource(R.string.proj_takes_select), onClick = { onSelect(take.id) })
                                }
                                TextAction(
                                    stringResource(if (take.favorite) R.string.proj_takes_unfavorite else R.string.proj_takes_favorite),
                                    onClick = { onToggleFavorite(take.id, !take.favorite) },
                                )
                                if (take.status == "done") {
                                    TextAction(
                                        stringResource(R.string.proj_takes_discard),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        onClick = { onDiscard(take.id) },
                                    )
                                }
                                if (take.status != "running" && shot.status != "running") {
                                    TextAction(
                                        stringResource(R.string.action_delete),
                                        color = MaterialTheme.colorScheme.error,
                                        onClick = { onDelete(take.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextAction(stringResource(R.string.action_cancel), onClick = onDismiss, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

@Composable
private fun takeStatusLabel(status: String): String = when (status) {
    "done" -> stringResource(R.string.board_status_done)
    "running" -> stringResource(R.string.board_status_running)
    "failed" -> stringResource(R.string.board_status_failed)
    "cancelled" -> stringResource(R.string.phase_cancelled)
    "discarded" -> stringResource(R.string.proj_takes_discarded)
    else -> status
}

/** Single-asset picker used by the first/last-frame quick actions. */
@Composable
internal fun AssetSinglePickerDialog(
    project: VideoProject,
    title: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (project.assets.isEmpty()) {
                Text(stringResource(R.string.board_assets_empty))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    project.assets.forEach { asset ->
                        TextButton(onClick = { onPick(asset.id) }) {
                            Text(
                                "${referenceRoleLabel(asset.role)} · " +
                                    assetDisplayLabel(project, asset).ifBlank { asset.path.substringAfterLast('/') },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun AssetRoleDialog(
    onDismiss: () -> Unit,
    onRole: (ReferenceRole) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_asset_role)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ReferenceRole.entries.forEach { role ->
                    TextButton(onClick = { onRole(role) }) { Text(referenceRoleLabel(role)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun ShotWorkflowDialog(
    currentPath: String?,
    workflows: List<ProjectWorkflowOption>,
    onDismiss: () -> Unit,
    onFollowProject: () -> Unit,
    onPick: (ProjectWorkflowOption) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_shot_workflow)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.board_shot_workflow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onFollowProject) {
                    Text(
                        (if (currentPath.isNullOrBlank()) "✓ " else "") +
                            stringResource(R.string.board_follow_project),
                    )
                }
                if (workflows.isEmpty()) {
                    Text(
                        stringResource(R.string.board_no_shot_workflows),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                workflows.forEach { wf ->
                    TextButton(onClick = { onPick(wf) }) {
                        Text(
                            (if (currentPath == wf.path) "✓ " else "") +
                                "${wf.providerLabel} · ${wf.name}",
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
internal fun AssetPickerDialog(
    project: VideoProject,
    selectedAssetIds: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onApply: () -> Unit,
) {
    val assets = project.assets
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_use_asset)) },
        text = {
            if (assets.isEmpty()) {
                Text(stringResource(R.string.board_assets_empty))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(R.string.proj_shot_order_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    assets.forEach { asset ->
                        val checked = asset.id in selectedAssetIds
                        Row(
                            Modifier.fillMaxWidth().clickable { onToggle(asset.id) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                "${referenceRoleLabel(asset.role)} · " +
                                    assetDisplayLabel(project, asset).ifBlank {
                                        asset.path.substringAfterLast('/')
                                    },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (assets.isNotEmpty()) {
                TextButton(onClick = onApply) { Text(stringResource(R.string.board_apply_assets)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

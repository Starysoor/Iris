package com.comfyremote.app.feature.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.project.DirectorChecks
import com.comfyremote.app.data.project.DirectorChecks.Severity
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisPrimaryButton
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.StatusChip
import com.comfyremote.app.ui.components.IrisTextField

/**
 * The AI director hub: local rule checks, frame-strip continuity and the
 * suggestion-only shot planner. Everything here is advisory — nothing in this
 * screen submits paid work.
 */
@Composable
fun DirectorScreen(
    viewModel: ProjectViewModel,
    projectId: String,
    onBack: () -> Unit,
) {
    val projects by viewModel.projects.collectAsState()
    val message by viewModel.message.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    val continuity by viewModel.continuity.collectAsState()
    val planState by viewModel.planState.collectAsState()
    var editingPlanIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(projectId) { viewModel.loadContinuity(projectId) }
    DisposableEffect(viewModel) { onDispose { viewModel.clearContinuity() } }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.proj_director_title),
                onBack = onBack,
            )
        },
    ) { pad ->
        if (project == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.proj_not_found), icon = Icons.Outlined.Movie)
            }
            return@IrisScaffold
        }
        // Files that exist on disk drive the "missing media" rules; built once
        // per composition here and refreshed by recomposition on edits.
        val existingPaths = remember(project.updatedAt) {
            buildSet {
                project.shots.forEach { shot ->
                    shot.resultPath?.let(::add)
                    shot.lastFramePath?.let(::add)
                    shot.referencePath?.let(::add)
                }
                project.assets.forEach { add(it.path) }
            }.filter { it.isNotBlank() && java.io.File(it).isFile }.toSet()
        }
        val issues = remember(project.updatedAt) { DirectorChecks.analyze(project, existingPaths) }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item("checks-heading") {
                SectionTitle(stringResource(R.string.proj_director_checks))
            }
            if (issues.isEmpty()) {
                item("checks-empty") {
                    Text(
                        stringResource(R.string.proj_director_checks_clean),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(issues.size, key = { "issue-$it" }) { index ->
                    val issue = issues[index]
                    IrisCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(
                                stringResource(
                                    if (issue.severity == Severity.WARNING) {
                                        R.string.proj_director_warning
                                    } else {
                                        R.string.proj_director_info
                                    },
                                ),
                                tone = if (issue.severity == Severity.WARNING) ChipTone.Warning else ChipTone.Neutral,
                            )
                            Text(
                                issue.message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = Spacing.sm),
                            )
                        }
                    }
                }
            }
            item("continuity-heading") {
                SectionTitle(stringResource(R.string.proj_director_continuity))
            }
            if (continuity.pageCount > 1 && !continuity.loading) {
                item("continuity-pagination") {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = { viewModel.loadContinuity(projectId, continuity.pageIndex - 1) },
                            enabled = continuity.pageIndex > 0,
                        ) { Text(stringResource(R.string.proj_director_page_previous)) }
                        Text(
                            stringResource(R.string.proj_director_page_count,
                                continuity.pageIndex + 1, continuity.pageCount),
                            style = IrisType.metaSmall,
                        )
                        TextButton(
                            onClick = { viewModel.loadContinuity(projectId, continuity.pageIndex + 1) },
                            enabled = continuity.pageIndex < continuity.pageCount - 1,
                        ) { Text(stringResource(R.string.proj_director_page_next)) }
                    }
                }
            }
            if (continuity.loading) {
                item("continuity-loading") {
                    Text(
                        stringResource(R.string.proj_director_continuity_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (continuity.strips.isEmpty()) {
                item("continuity-empty") {
                    Text(
                        stringResource(R.string.proj_director_continuity_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(continuity.strips.size, key = { "strip-" + continuity.strips[it].shotId }) { index ->
                    val strip = continuity.strips[index]
                    IrisCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(strip.label, style = MaterialTheme.typography.titleSmall)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                items(strip.frames.size, key = { index * 1000 + it }) { frameIndex ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Image(
                                            bitmap = strip.frames[frameIndex].asImageBitmap(),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(MaterialTheme.shapes.small),
                                        )
                                        Text(
                                            formatStripSeconds(strip.durationMs, frameIndex, strip.frames.size),
                                            style = IrisType.metaSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                items(continuity.seams.size, key = { "seam-" + continuity.seams[it].fromShotId }) { index ->
                    val seam = continuity.seams[index]
                    IrisCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                Text(
                                    seam.fromLabel + " → " + seam.toLabel,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                StatusChip(seamBadge(seam.diff), tone = seamTone(seam.diff))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    bitmap = seam.frameA.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                                )
                                Text("→", style = MaterialTheme.typography.titleMedium)
                                Image(
                                    bitmap = seam.frameB.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                                )
                                Text(
                                    stringResource(R.string.proj_director_seam_hint),
                                    style = IrisType.metaSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            item("plan-heading") {
                SectionTitle(stringResource(R.string.proj_director_plan))
            }
            item("plan-hint") {
                Text(
                    stringResource(R.string.studio_ai_more_context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item("plan-generate") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    IrisPrimaryButton(
                        text = stringResource(
                            if (planState.loading) R.string.proj_director_plan_loading else R.string.proj_director_plan_generate,
                        ),
                        onClick = { viewModel.suggestShotPlan(projectId) },
                        enabled = !planState.loading,
                        modifier = Modifier.weight(1f),
                    )
                    if (planState.suggestions.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = planState.selected.size == planState.suggestions.size,
                                onCheckedChange = { viewModel.selectAllPlanSuggestions(it) },
                            )
                            Text(
                                stringResource(R.string.proj_director_plan_select_all),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
            if (planState.loading) {
                item("plan-loading") {
                    Text(
                        stringResource(R.string.proj_director_plan_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (planState.suggestions.isEmpty()) {
                item("plan-empty") {
                    Text(
                        stringResource(R.string.proj_director_plan_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item("plan-duration-summary") {
                    val proposedSeconds = planState.suggestions.withIndex()
                        .filter { it.index in planState.selected }
                        .sumOf { it.value.suggestedSeconds ?: 0 }
                    val existingSeconds = project.shots.sumOf { it.plannedDurationSeconds ?: 0 }
                    Text(
                        stringResource(
                            R.string.studio_ai_duration_summary,
                            existingSeconds + proposedSeconds,
                            project.targetDurationSeconds ?: 0,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(planState.suggestions.size, key = { "plan-$it" }) { index ->
                    val suggestion = planState.suggestions[index]
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        Checkbox(
                            checked = index in planState.selected,
                            onCheckedChange = { viewModel.togglePlanSuggestion(index) },
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                suggestion.prompt,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (suggestion.referenceAssetIds.isNotEmpty()) {
                                Text(
                                    stringResource(
                                        R.string.studio_ai_ref_count,
                                        suggestion.referenceAssetIds.size,
                                    ),
                                    style = IrisType.metaSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            suggestion.workflowPath?.let { proposed ->
                                Text(
                                    stringResource(R.string.studio_ai_model_hint, proposed),
                                    style = IrisType.metaSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                            if (suggestion.rationale.isNotBlank()) {
                                Text(
                                    stringResource(R.string.studio_ai_rationale, suggestion.rationale),
                                    style = IrisType.metaSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                )
                            }
                            if (suggestion.risk.isNotBlank()) {
                                Text(
                                    stringResource(R.string.studio_ai_risk, suggestion.risk),
                                    style = IrisType.metaSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 3,
                                )
                            }
                            val issues = DirectorPrompts.validationIssues(suggestion, project)
                            if (issues.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.studio_ai_invalid_plan, issues.first()),
                                    style = IrisType.metaSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (suggestion.cameraNotes.isNotBlank()) {
                                    StatusChip(suggestion.cameraNotes, tone = ChipTone.Neutral)
                                }
                                suggestion.suggestedSeconds?.let {
                                    StatusChip(
                                        stringResource(R.string.proj_director_plan_seconds, it),
                                        tone = ChipTone.Accent,
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { editingPlanIndex = index }) {
                            Text(stringResource(R.string.studio_ai_edit_plan))
                        }
                    }
                }
                item("plan-apply") {
                    IrisPrimaryButton(
                        text = stringResource(R.string.proj_director_plan_apply),
                        onClick = { viewModel.applyPlanSuggestions(projectId) },
                        enabled = planState.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(R.string.proj_director_plan_hint),
                        style = IrisType.metaSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }
        }
    }

    editingPlanIndex?.let { index ->
        planState.suggestions.getOrNull(index)?.let { suggestion ->
            EditStudioDirectorPlan(
                suggestion = suggestion,
                imageAssets = project?.assets.orEmpty().filter { it.kind == AssetKind.IMAGE },
                onDismiss = { editingPlanIndex = null },
                onApply = { prompt, camera, duration, refs ->
                    viewModel.editPlanSuggestion(index, prompt, camera, duration, refs)
                    editingPlanIndex = null
                },
            )
        }
    }

}

@Composable
private fun SectionTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Icon(Icons.Outlined.Build, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

private fun formatStripSeconds(durationMs: Long, frameIndex: Int, frameCount: Int): String {
    if (durationMs <= 0 || frameCount <= 0) return ""
    val second = durationMs * frameIndex / frameCount / 1000
    return "${second + 1}s"
}

private fun seamBadge(diff: Double): String = when {
    diff < DirectorChecks.CONTINUITY_DIFF_THRESHOLD -> "连续"
    diff < DirectorChecks.CONTINUITY_DIFF_THRESHOLD * 2 -> "可能切镜"
    else -> "不连续"
}

private fun seamTone(diff: Double): ChipTone = when {
    diff < DirectorChecks.CONTINUITY_DIFF_THRESHOLD -> ChipTone.Success
    diff < DirectorChecks.CONTINUITY_DIFF_THRESHOLD * 2 -> ChipTone.Warning
    else -> ChipTone.Error
}

@Composable
private fun EditStudioDirectorPlan(
    suggestion: DirectorPrompts.SuggestedShot,
    imageAssets: List<ProjectAsset>,
    onDismiss: () -> Unit,
    onApply: (String, String, Int?, List<String>) -> Unit,
) {
    var prompt by remember(suggestion) { mutableStateOf(suggestion.prompt) }
    var camera by remember(suggestion) { mutableStateOf(suggestion.cameraNotes) }
    var duration by remember(suggestion) { mutableStateOf(suggestion.suggestedSeconds?.toString().orEmpty()) }
    var selectedAssets by remember(suggestion) {
        mutableStateOf(suggestion.referenceAssetIds.filter { id ->
            imageAssets.any { it.id == id }
        }.toSet())
    }
    val seconds = duration.trim().takeIf(String::isNotBlank)?.toIntOrNull()
    val validDuration = duration.isBlank() || (seconds != null && seconds in 1..3600)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.studio_ai_edit_plan)) },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IrisTextField(
                    value = prompt, onValueChange = { prompt = it },
                    label = stringResource(R.string.gen_prompt),
                    minLines = 3, maxLines = 5, modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = camera, onValueChange = { camera = it },
                    label = stringResource(R.string.studio_ai_camera),
                    minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = duration, onValueChange = { duration = it },
                    label = stringResource(R.string.studio_ai_seconds),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.studio_ai_seconds_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    stringResource(R.string.studio_ai_select_references),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (imageAssets.isEmpty()) {
                    Text(
                        stringResource(R.string.studio_binding_no_assets),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                imageAssets.take(32).forEach { asset ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = asset.id in selectedAssets,
                            onCheckedChange = { checked ->
                                selectedAssets = if (checked) selectedAssets + asset.id
                                    else selectedAssets - asset.id
                            },
                        )
                        Text(
                            asset.label.ifBlank { asset.path.substringAfterLast('/') },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(prompt, camera, seconds, selectedAssets.toList()) },
                enabled = prompt.isNotBlank() && validDuration,
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

package com.comfyremote.app.feature.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.data.project.StudioWorkflowRecipes
import com.comfyremote.app.data.project.StudioStagePreflight
import com.comfyremote.app.data.project.StudioStageBindings
import com.comfyremote.app.data.project.VideoProject
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.TextAction

/**
 * Non-automatic, project-persistent staged model selector.
 * UI never claims a cross-provider media mapping that has not been validated.
 */
@Composable
internal fun StudioRecipeCard(
    project: VideoProject,
    models: List<ProjectWorkflowOption>,
    inspection: ProjectViewModel.StageInspection,
    onInspectStage: (String, String) -> Unit,
    onBindStageInput: (String, String, String?) -> Unit,
    onChooseRecipe: (String) -> Unit,
    onChooseModel: (String, String) -> Unit,
    onRunStage: (String) -> Unit,
    onOpenAssets: () -> Unit,
    onOpenShots: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenCreate: () -> Unit,
) {
    var recipePicker by remember { mutableStateOf(false) }
    var modelKind by remember { mutableStateOf<String?>(null) }
    var inspectKind by remember(project.id) { mutableStateOf<String?>(null) }
    val recipe = StudioWorkflowRecipes.resolve(project.workflowRecipeId)

    IrisCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.studio_recipe_title), style = MaterialTheme.typography.titleMedium)
            TextAction(stringResource(R.string.studio_recipe_choose), onClick = { recipePicker = true })
        }
        Text(recipeLabel(recipe.id), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.studio_recipe_manual_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        recipe.steps.forEachIndexed { index, step ->
            val kind = step.providerKind
            val path = if (kind == "video") {
                project.stageWorkflowPaths[kind]?.takeIf(String::isNotBlank) ?: project.workflowPath
            } else if (kind != null) project.stageWorkflowPaths[kind].orEmpty() else ""
            val model = models.firstOrNull { it.kind == kind && it.path == path }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("${index + 1}. " + stepLabel(step), style = MaterialTheme.typography.titleSmall)
                if (kind != null) {
                    Text(
                        model?.let { "${it.providerLabel} · ${it.name}" }
                            ?: stringResource(R.string.studio_recipe_missing_model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextAction(
                                text = stringResource(R.string.studio_recipe_select_model),
                                onClick = { modelKind = kind },
                            )
                            if (model != null) {
                                TextAction(
                                    text = stringResource(R.string.studio_contract_inspect),
                                    onClick = {
                                        inspectKind = kind
                                        onInspectStage(kind, path)
                                    },
                                )
                            }
                        }
                        if (model != null) {
                            TextAction(
                                text = stringResource(R.string.studio_recipe_run_stage),
                                onClick = { onRunStage(kind) },
                            )
                        }
                    }
                } else {
                    TextAction(
                        text = stringResource(R.string.studio_recipe_open_step),
                        onClick = when (step) {
                            StudioWorkflowRecipes.Step.ASSETS -> onOpenAssets
                            StudioWorkflowRecipes.Step.SHOTS -> onOpenShots
                            StudioWorkflowRecipes.Step.TIMELINE -> onOpenTimeline
                            else -> onOpenCreate
                        },
                    )
                }
            }
        }
        TextAction(stringResource(R.string.studio_recipe_browse_create), onClick = onOpenCreate)
    }

    inspectKind?.let { kind ->
        // The contract displayed must match both this project and its current model.
        // A late response from a previous selection cannot be presented as current.
        val selectedPath = project.stageWorkflowPaths[kind]
            ?.takeIf(String::isNotBlank)
            ?: if (kind == "video") project.workflowPath else ""
        val matched = inspection.projectId == project.id &&
            inspection.kind == kind && inspection.workflowPath == selectedPath
        AlertDialog(
            onDismissRequest = { inspectKind = null },
            title = { Text(stringResource(R.string.studio_contract_title)) },
            text = {
                Column(
                    Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    when {
                        !matched || inspection.loading -> Text(
                            stringResource(R.string.studio_contract_loading),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        inspection.error != null -> {
                            Text(
                                stringResource(R.string.studio_contract_failed, inspection.error),
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(stringResource(R.string.studio_contract_safe_hint),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        inspection.report != null ->
                            StudioContractDetails(
                                report = inspection.report,
                                project = project,
                                workflowPath = selectedPath,
                                onBindAsset = { field, assetId ->
                                    onBindStageInput(kind, field, assetId)
                                },
                            )
                        else -> Text(stringResource(R.string.studio_contract_loading))
                    }
                }
            },
            confirmButton = {
                if (matched && inspection.report?.support == StudioStagePreflight.Support.CONFIGURABLE) {
                    TextButton(onClick = {
                        inspectKind = null
                        onRunStage(kind)
                    }) { Text(stringResource(R.string.studio_recipe_run_stage)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { inspectKind = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (recipePicker) {
        AlertDialog(
            onDismissRequest = { recipePicker = false },
            title = { Text(stringResource(R.string.studio_recipe_choose)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    StudioWorkflowRecipes.options.forEach { choice ->
                        TextButton(onClick = {
                            onChooseRecipe(choice.id)
                            recipePicker = false
                        }) {
                            Text((if (choice.id == recipe.id) "✓ " else "") + recipeLabel(choice.id))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { recipePicker = false }) {
                Text(stringResource(R.string.action_cancel))
            } },
        )
    }

    modelKind?.let { kind ->
        val suitable = models.filter { it.kind == kind }
        var query by remember(kind) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { modelKind = null },
            title = { Text(stringResource(R.string.studio_recipe_select_model)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    IrisTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = stringResource(R.string.studio_workflow_search),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Column(Modifier.heightIn(max = 330.dp)
                        .verticalScroll(rememberScrollState())) {
                        val filtered = suitable.filter {
                            query.isBlank() || it.name.contains(query, true) ||
                                it.providerLabel.contains(query, true)
                        }
                        if (filtered.isEmpty()) {
                            Text(stringResource(R.string.studio_recipe_no_models),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        filtered.forEach { option ->
                            TextButton(onClick = {
                                onChooseModel(kind, option.path)
                                modelKind = null
                            }) { Text("${option.providerLabel} · ${option.name}") }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { modelKind = null }) {
                Text(stringResource(R.string.action_cancel))
            } },
        )
    }
}

@Composable
private fun recipeLabel(id: String): String = stringResource(when (id) {
    StudioWorkflowRecipes.REFERENCES_TO_VIDEO -> R.string.studio_recipe_refs
    StudioWorkflowRecipes.KEYFRAMES_AND_AUDIO -> R.string.studio_recipe_keyframes
    else -> R.string.studio_recipe_text
})

@Composable
private fun stepLabel(step: StudioWorkflowRecipes.Step): String = stringResource(when (step) {
    StudioWorkflowRecipes.Step.ASSETS -> R.string.studio_recipe_step_assets
    StudioWorkflowRecipes.Step.IMAGE -> R.string.studio_recipe_step_image
    StudioWorkflowRecipes.Step.SHOTS -> R.string.studio_recipe_step_shots
    StudioWorkflowRecipes.Step.VIDEO -> R.string.studio_recipe_step_video
    StudioWorkflowRecipes.Step.AUDIO -> R.string.studio_recipe_step_audio
    StudioWorkflowRecipes.Step.TIMELINE -> R.string.studio_recipe_step_timeline
})

/** Displays only facts from the provider's detailed schema, never name guesses. */
@Composable
private fun StudioContractDetails(
    report: StudioStagePreflight.Report,
    project: VideoProject,
    workflowPath: String,
    onBindAsset: (String, String?) -> Unit,
) {
    var choosingField by remember(report.workflowId, project.id) { mutableStateOf<String?>(null) }
    Text(report.modelName, style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.studio_contract_summary, report.imageInputs,
            report.requiredImageInputs, report.audioInputs, report.requiredAudioInputs),
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        report.providerPrice ?: stringResource(R.string.studio_contract_unknown_price),
        style = MaterialTheme.typography.bodySmall,
    )
    when (report.support) {
        StudioStagePreflight.Support.CONFIGURABLE -> Text(
            stringResource(R.string.studio_contract_configurable),
            style = MaterialTheme.typography.bodySmall,
        )
        StudioStagePreflight.Support.UNKNOWN_CONTRACT -> Text(
            stringResource(R.string.studio_contract_unknown),
            color = MaterialTheme.colorScheme.error,
        )
        StudioStagePreflight.Support.REQUIRES_UNSUPPORTED_INPUT -> Text(
            stringResource(R.string.studio_contract_blocked,
                report.unsupportedRequired.joinToString().ifBlank {
                    "${report.expectedKind} / ${report.providerKind}"
                }),
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (report.potentialMissingMediaSlots.isNotEmpty()) {
        Text(
            stringResource(
                R.string.studio_contract_shortfall,
                report.potentialMissingMediaSlots.joinToString(", "),
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    report.inputs.forEach { input ->
        Text(
            input.field + " · " + input.type +
                (if (input.required) " · " + stringResource(R.string.studio_contract_required) else ""),
            style = MaterialTheme.typography.titleSmall,
        )
        if (input.type in listOf("image", "audio", "video")) {
            Text(
                stringResource(R.string.studio_contract_inventory, input.candidateAssets),
                style = MaterialTheme.typography.bodySmall,
            )
            if (input.role != com.comfyremote.app.data.reference.ReferenceRole.AUTO) {
                Text(input.role.name, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (input.type == "image" || input.type == "audio") {
            val key = StudioStageBindings.key(workflowPath, input.field)
            val selectedId = project.stageMediaBindings[key]
            val selectedAsset = project.assets.firstOrNull { it.id == selectedId }
            Text(
                selectedAsset?.label?.ifBlank { selectedAsset.path.substringAfterLast('/') }
                    ?: stringResource(R.string.studio_bind_unselected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = {
                choosingField = if (choosingField == input.field) null else input.field
            }) {
                Text(stringResource(R.string.studio_bind_choose))
            }
            if (choosingField == input.field) {
                val candidates = StudioStageBindings.candidates(input, project.assets)
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.studio_bind_no_candidates),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (selectedId != null) {
                    TextButton(onClick = {
                        onBindAsset(input.field, null)
                        choosingField = null
                    }) { Text(stringResource(R.string.studio_bind_clear)) }
                }
                candidates.forEach { asset ->
                    TextButton(onClick = {
                        onBindAsset(input.field, asset.id)
                        choosingField = null
                    }) {
                        Text((if (asset.id == selectedId) "✓ " else "") +
                            asset.label.ifBlank { asset.path.substringAfterLast('/') })
                    }
                }
            }
        }
        if (input.min != null || input.max != null) {
            Text(
                stringResource(
                    R.string.studio_contract_bounds,
                    input.min?.toString() ?: "?",
                    input.max?.toString() ?: "?",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (input.options.isNotEmpty()) {
            Text(
                stringResource(R.string.studio_contract_options, input.options.joinToString(" / ")),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Text(
        stringResource(R.string.studio_contract_safe_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

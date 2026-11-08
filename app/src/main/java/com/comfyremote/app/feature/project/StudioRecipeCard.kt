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
import com.comfyremote.app.R
import com.comfyremote.app.data.project.StudioWorkflowRecipes
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
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TextAction(
                            text = stringResource(R.string.studio_recipe_select_model),
                            onClick = { modelKind = kind },
                        )
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
                    Column(Modifier.heightIn(max = androidx.compose.ui.unit.Dp(330f))
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

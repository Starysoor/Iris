package com.comfyremote.app.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.comfyremote.app.AiSkillDef
import com.comfyremote.app.R
import com.comfyremote.app.SkillExecution
import com.comfyremote.app.SkillInputSource
import com.comfyremote.app.SkillOutput
import com.comfyremote.app.SkillScope
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.IrisChip
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.TextAction

/** Create or edit a prompt skill and its workflow keyword bindings. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillEditDialog(
    skill: AiSkillDef?,
    initialPatterns: List<String>,
    onDismiss: () -> Unit,
    onSave: (AiSkillDef, List<String>) -> Unit,
    onDelete: (() -> Unit)?,
    onReset: (() -> Unit)?,
) {
    var label by remember(skill) { mutableStateOf(skill?.label.orEmpty()) }
    var description by remember(skill) { mutableStateOf(skill?.description.orEmpty()) }
    var prompt by remember(skill) { mutableStateOf(skill?.systemPrompt.orEmpty()) }
    var patterns by remember(skill, initialPatterns) { mutableStateOf(initialPatterns.joinToString(", ")) }
    var scopes by remember(skill) { mutableStateOf(skill?.scopes.orEmpty()) }
    var inputs by remember(skill) { mutableStateOf(skill?.inputSources.orEmpty()) }
    var execution by remember(skill) { mutableStateOf(skill?.execution ?: SkillExecution.TEXT_MODEL) }
    var output by remember(skill) { mutableStateOf(skill?.output ?: SkillOutput.REPLACE_FIELD) }
    var showHelp by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row {
                Text(
                    if (skill == null) stringResource(R.string.ai_skill_new_title)
                    else stringResource(R.string.ai_skill_edit_title),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { showHelp = true }) {
                    Icon(Icons.Outlined.HelpOutline,
                        contentDescription = stringResource(R.string.ai_help_open))
                }
            }
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IrisTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = stringResource(R.string.ai_skill_label),
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.ai_skill_description),
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = patterns,
                    onValueChange = { patterns = it },
                    label = stringResource(R.string.ai_skill_patterns),
                    supportingText = stringResource(R.string.ai_skill_patterns_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = stringResource(R.string.ai_skill_prompt),
                    minLines = 6,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
                SkillChipSection(
                    title = stringResource(R.string.ai_skill_scopes),
                    values = SkillScope.entries,
                    selected = scopes,
                    onToggle = { scope ->
                        scopes = if (scope in scopes) scopes - scope else scopes + scope
                    },
                )
                SkillChipSection(
                    title = stringResource(R.string.ai_skill_inputs),
                    values = SkillInputSource.entries,
                    selected = inputs,
                    onToggle = { input ->
                        inputs = if (input in inputs) inputs - input else inputs + input
                    },
                )
                SkillSingleChoice(
                    title = stringResource(R.string.ai_skill_execution),
                    values = SkillExecution.entries,
                    selected = execution,
                    onSelect = { execution = it },
                )
                SkillSingleChoice(
                    title = stringResource(R.string.ai_skill_output),
                    values = SkillOutput.entries,
                    selected = output,
                    onSelect = { output = it },
                )
            }
        },
        confirmButton = {
            TextAction(
                text = stringResource(R.string.action_save),
                onClick = {
                    val parsed = patterns.split(',', '，', '\n').map(String::trim).filter(String::isNotBlank)
                    val next = skill?.copy(
                        label = label.trim(),
                        systemPrompt = prompt,
                        description = description.trim(),
                        scopes = scopes,
                        inputSources = inputs,
                        execution = execution,
                        output = output,
                    ) ?: AiSkillDef(
                        id = "custom_${System.currentTimeMillis()}",
                        label = label.trim(),
                        systemPrompt = prompt,
                        builtin = false,
                        description = description.trim(),
                        scopes = scopes,
                        inputSources = inputs,
                        execution = execution,
                        output = output,
                    )
                    onSave(next, parsed)
                },
                enabled = label.isNotBlank() && prompt.isNotBlank(),
            )
        },
        dismissButton = {
            Row {
                onReset?.let { TextAction(stringResource(R.string.ai_reset), onClick = it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                onDelete?.let { TextAction(stringResource(R.string.action_delete), onClick = it, color = MaterialTheme.colorScheme.error) }
                TextAction(stringResource(R.string.action_cancel), onClick = onDismiss, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
    if (showHelp) SkillUsageHelpDialog { showHelp = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SkillChipSection(
    title: String,
    values: List<T>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            values.forEach { value ->
                IrisChip(
                    label = value.toString().replace('_', ' '),
                    selected = value in selected,
                    onClick = { onToggle(value) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SkillSingleChoice(
    title: String,
    values: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            values.forEach { value ->
                IrisChip(
                    label = value.toString().replace('_', ' '),
                    selected = value == selected,
                    onClick = { onSelect(value) },
                )
            }
        }
    }
}

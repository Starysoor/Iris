package com.comfyremote.app.feature.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.SkillExecution
import com.comfyremote.app.SkillInputSource
import com.comfyremote.app.SkillOutput
import com.comfyremote.app.SkillScope

/** Read-only help dialog; never changes skill configuration. */
@Composable
internal fun SkillUsageHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_help_title)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.ai_help_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                HelpSection(stringResource(R.string.ai_help_scope_heading)) {
                    SkillScope.entries.forEach { entry ->
                        HelpTag(entry.name, stringResource(SkillHelpCatalog.scopeDescription(entry)))
                    }
                }
                HelpSection(stringResource(R.string.ai_help_input_heading)) {
                    SkillInputSource.entries.forEach { entry ->
                        HelpTag(entry.name, stringResource(SkillHelpCatalog.inputDescription(entry)))
                    }
                }
                HelpSection(stringResource(R.string.ai_help_execution_heading)) {
                    SkillExecution.entries.forEach { entry ->
                        HelpTag(entry.name, stringResource(SkillHelpCatalog.executionDescription(entry)))
                    }
                }
                HelpSection(stringResource(R.string.ai_help_output_heading)) {
                    SkillOutput.entries.forEach { entry ->
                        HelpTag(entry.name, stringResource(SkillHelpCatalog.outputDescription(entry)))
                    }
                }
                HelpSection(stringResource(R.string.ai_help_patterns_heading)) {
                    Text(stringResource(R.string.ai_help_patterns_body),
                        style = MaterialTheme.typography.bodySmall)
                }
                HelpSection(stringResource(R.string.ai_help_steps_heading)) {
                    Text(stringResource(R.string.ai_help_steps_body),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ai_help_close)) }
        },
    )
}

@Composable
private fun HelpSection(title: String, children: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        children()
    }
}

@Composable
private fun HelpTag(tag: String, description: String) {
    Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(tag, style = MaterialTheme.typography.labelMedium)
        Text(description, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

package com.comfyremote.app.feature.generate

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.comfyremote.app.ui.components.IrisSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.AiSkillDef
import com.comfyremote.app.PromptField
import com.comfyremote.app.R
import com.comfyremote.app.data.generation.ErrorKind
import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationTask
import com.comfyremote.app.data.repository.WorkflowInsight
import com.comfyremote.app.ui.AppStrings
import com.comfyremote.app.ui.Iris
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.components.BannerTone
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.CollapsibleCard
import com.comfyremote.app.ui.components.InfoBanner
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisChip
import com.comfyremote.app.ui.components.IrisDivider
import com.comfyremote.app.ui.components.IrisProgressBar
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.StatusChip
import com.comfyremote.app.ui.components.Stepper

/* Stateless building blocks of the Generate screen. Each takes plain values and callbacks. */

@Composable
internal fun GenerationStatusCard(task: GenerationTask, running: Boolean) {
    IrisCard(highlighted = running) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip(statusLabel(task).uppercase(), tone = phaseTone(task))
            if (task.recovered) StatusChip(stringResource(R.string.gen_recovered))
            task.batchIndex?.let { index -> StatusChip("$index/${task.batchSize ?: index}") }
            Text(
                task.nodeTitle?.takeIf(String::isNotBlank).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 2.dp),
            )
            task.queuePosition?.let { Text("#$it", style = IrisType.meta, color = MaterialTheme.colorScheme.primary) }
                ?: task.queueRemaining?.let {
                    Text(
                        stringResource(R.string.gen_queue_remaining, it),
                        style = IrisType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
        task.recoveryNote?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (task.phase == GenerationPhase.RUNNING) {
            val p = task.progress?.coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IrisProgressBar(progress = p, modifier = Modifier.weight(1f))
                if (p != null) Text("${(p * 100).toInt()}%", style = IrisType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (task.localPending) {
            Text(
                stringResource(R.string.gen_local_pending_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkflowInsightCard(
    insight: WorkflowInsight,
    workflowId: String? = null,
    workflowDescription: String? = null,
) {
    IrisCard {
        Text(
            stringResource(R.string.gen_insight_title).uppercase(),
            style = IrisType.overline,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        workflowId?.takeIf(String::isNotBlank)?.let {
            Text(
                stringResource(R.string.gen_workflow_id, it),
                style = IrisType.metaSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        workflowDescription?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (insight.capabilities.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                insight.capabilities.forEach { StatusChip(it.label) }
            }
        }
        if (insight.issues.isEmpty()) {
            Text(
                stringResource(R.string.gen_insight_ok),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            insight.issues.take(4).forEach { issue ->
                if (issue.blocking) {
                    InfoBanner(text = issue.message, tone = BannerTone.Error)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            tint = Iris.colors.warning,
                            modifier = Modifier.padding(top = 1.dp).size(16.dp),
                        )
                        Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
internal fun WorkflowNotesCard(notes: List<String>, expanded: Boolean, onToggle: () -> Unit) {
    CollapsibleCard(title = stringResource(R.string.gen_notes), expanded = expanded, onToggle = onToggle) {
        notes.forEach {
            Text(stripMarkdown(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LivePreview(preview: Bitmap) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.gen_live_preview).uppercase(),
            style = IrisType.overline,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Image(
            preview.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentScale = ContentScale.FillWidth,
        )
    }
}

/**
 * One text field. Multiline prompts clear their workflow default on first focus
 * and show the AI skill pills underneath.
 */
@Composable
internal fun PromptFieldBlock(
    field: PromptField,
    value: String,
    hasOverride: Boolean,
    workflowName: String,
    workflowPath: String,
    skills: List<AiSkillDef>,
    aiBusy: String?,
    running: Boolean,
    onValueChange: (String) -> Unit,
    onSkill: (AiSkillDef) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IrisTextField(
            value = value,
            onValueChange = onValueChange,
            label = field.label,
            minLines = if (field.multiline) 4 else 1,
            maxLines = if (field.multiline) 14 else 2,
            modifier = Modifier.fillMaxWidth().onFocusChanged { st ->
                if (field.multiline && st.isFocused && !hasOverride && field.value.isNotBlank()) {
                    onValueChange("")
                }
            },
        )
        if (field.multiline) {
            val ordered = remember(workflowName, workflowPath, skills) {
                AiSkillDef.recommendedOrder(
                    skills.filter { it.id != AiSkillDef.DEFAULT_VISION_ID },
                    "$workflowName $workflowPath",
                )
            }
            val primary = ordered.take(2)
            val rest = ordered.drop(2)
            val enabled = aiBusy == null && !running
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                primary.forEach { skill ->
                    if (aiBusy == "${field.key}|${skill.id}") {
                        BusyChip()
                    } else {
                        IrisChip(
                            label = skill.label,
                            selected = false,
                            onClick = { onSkill(skill) },
                            enabled = enabled,
                            leadingIcon = Icons.Outlined.AutoAwesome,
                        )
                    }
                }
                if (rest.isNotEmpty()) {
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IrisChip(
                            label = stringResource(R.string.action_more),
                            selected = false,
                            onClick = { more = true },
                            enabled = enabled,
                            leadingIcon = Icons.Outlined.ExpandMore,
                        )
                        DropdownMenu(more, { more = false }) {
                            rest.forEach { skill ->
                                DropdownMenuItem(
                                    text = { Text(skill.label) },
                                    onClick = { more = false; onSkill(skill) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Chip-shaped placeholder shown while an AI skill is running. */
@Composable
private fun BusyChip() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(36.dp),
    ) {
        Box(Modifier.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

/** Seed and batch controls in a single card. */
@Composable
internal fun SeedBatchCard(
    seedFields: List<PromptField>,
    values: Map<String, String>,
    randomSeed: Boolean,
    batchCount: Int,
    batchLocalQueue: Boolean,
    running: Boolean,
    batchEnabled: Boolean = true,
    onToggleRandomSeed: () -> Unit,
    onSeedChange: (key: String, value: String) -> Unit,
    onBatchCount: (Int) -> Unit,
    onToggleLocalQueue: () -> Unit,
) {
    IrisCard {
        if (seedFields.isNotEmpty()) {
            ControlRow(stringResource(R.string.gen_random_seed)) {
                IrisSwitch(checked = randomSeed, onCheckedChange = { onToggleRandomSeed() })
            }
            if (!randomSeed) seedFields.forEach { field ->
                IrisTextField(
                    value = values[field.key] ?: field.value,
                    onValueChange = { onSeedChange(field.key, it.filter(Char::isDigit)) },
                    label = field.label,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IrisDivider(Modifier.padding(vertical = 2.dp))
        }
        if (batchEnabled) {
            ControlRow(stringResource(R.string.gen_batch)) {
                Stepper(value = batchCount, onChange = onBatchCount, range = 1..16, enabled = !running)
            }
            ControlRow(stringResource(R.string.gen_local_queue)) {
                IrisSwitch(
                    checked = batchLocalQueue,
                    onCheckedChange = { onToggleLocalQueue() },
                    enabled = batchCount > 1 && !running,
                )
            }
            if (batchCount > 1 && !randomSeed) {
                Text(
                    stringResource(R.string.gen_batch_seed_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlRow(title: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
internal fun AdvancedParamsCard(
    fields: List<PromptField>,
    values: Map<String, String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (key: String, value: String) -> Unit,
) {
    CollapsibleCard(
        title = stringResource(R.string.gen_advanced),
        expanded = expanded,
        onToggle = onToggle,
        badge = fields.size.toString(),
    ) {
        fields.forEach { field -> ParamField(field, values[field.key] ?: field.value) { onChange(field.key, it) } }
    }
}

internal fun audioDisplayName(value: String): String =
    value.substringAfterLast('/').substringAfterLast(':').ifBlank { value }

@Composable
private fun statusLabel(task: GenerationTask?): String = when {
    task?.localPending == true -> stringResource(R.string.phase_local_pending)
    else -> when (task?.phase) {
        null -> stringResource(R.string.phase_ready)
        GenerationPhase.PREPARING -> stringResource(R.string.phase_preparing)
        GenerationPhase.QUEUEING, GenerationPhase.QUEUED -> stringResource(R.string.phase_queued)
        GenerationPhase.RUNNING -> stringResource(R.string.phase_running)
        GenerationPhase.CANCELLING -> stringResource(R.string.phase_cancelling)
        GenerationPhase.CANCELLED -> stringResource(R.string.phase_cancelled)
        GenerationPhase.SUCCEEDED -> stringResource(R.string.phase_succeeded)
        GenerationPhase.FAILED -> stringResource(R.string.phase_failed)
    }
}

private fun phaseTone(task: GenerationTask): ChipTone = when {
    task.localPending -> ChipTone.Neutral
    else -> when (task.phase) {
        GenerationPhase.PREPARING, GenerationPhase.QUEUEING, GenerationPhase.QUEUED, GenerationPhase.RUNNING -> ChipTone.Accent
        GenerationPhase.SUCCEEDED -> ChipTone.Success
        GenerationPhase.FAILED -> ChipTone.Error
        GenerationPhase.CANCELLING, GenerationPhase.CANCELLED -> ChipTone.Neutral
    }
}

fun errorKindLabel(kind: ErrorKind): String = when (kind) {
    ErrorKind.EXECUTION -> AppStrings.get(R.string.error_execution)
    ErrorKind.CONNECTION -> AppStrings.get(R.string.error_connection)
    ErrorKind.PROMPT_VALIDATION -> AppStrings.get(R.string.error_validation)
    ErrorKind.TIMEOUT -> AppStrings.get(R.string.error_timeout)
    ErrorKind.DOWNLOAD -> AppStrings.get(R.string.error_download)
    ErrorKind.CANCELLED -> AppStrings.get(R.string.error_cancelled)
    ErrorKind.UNKNOWN -> AppStrings.get(R.string.error_unknown)
}

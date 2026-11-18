package com.comfyremote.app.feature.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.AiSkillDef
import com.comfyremote.app.R
import com.comfyremote.app.TagProfile
import com.comfyremote.app.data.repository.AiEndpointPolicy
import com.comfyremote.app.data.repository.AiModelCapabilities
import com.comfyremote.app.data.repository.ReasoningKind
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.BannerTone
import com.comfyremote.app.ui.components.BottomActionBar
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.CollapsibleCard
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.InfoBanner
import com.comfyremote.app.ui.components.IrisChip
import com.comfyremote.app.ui.components.IrisPrimaryButton
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.IrisTonalButton
import com.comfyremote.app.ui.components.ListRow
import com.comfyremote.app.ui.components.ScreenContentPadding
import com.comfyremote.app.ui.components.SectionHeader
import com.comfyremote.app.ui.components.SettingsGroup
import com.comfyremote.app.ui.components.StatusChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(
    viewModel: AiSettingsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val settings = state.settings
    var advanced by rememberSaveable { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<AiSkillDef?>(null) }
    var addingSkill by remember { mutableStateOf(false) }
    var showSkillHelp by remember { mutableStateOf(false) }
    val okPrefix = stringResource(R.string.ai_test_ok, "")

    IrisScaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.ai_title), onBack = onBack) },
        bottomBar = {
            BottomActionBar {
                IrisPrimaryButton(text = stringResource(R.string.action_save), onClick = viewModel::save)
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    stringResource(R.string.ai_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionHeader(stringResource(R.string.ai_quick_providers))
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    AI_PRESETS.forEach { preset ->
                        IrisChip(
                            label = preset.label,
                            selected = settings.baseUrl == preset.baseUrl && settings.model == preset.model,
                            onClick = { viewModel.applyPreset(preset.baseUrl, preset.model, preset.visionModel) },
                        )
                    }
                }
            }

            SettingsGroup(stringResource(R.string.ai_group_endpoint)) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    IrisTextField(settings.baseUrl, viewModel::updateBase, label = stringResource(R.string.ai_base_url), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (AiEndpointPolicy.isInsecureRemote(settings.baseUrl)) {
                        InfoBanner(
                            text = stringResource(R.string.ai_insecure_http_warning),
                            tone = BannerTone.Warning,
                        )
                    }
                    IrisTextField(
                        settings.apiKey,
                        viewModel::updateKey,
                        label = stringResource(R.string.ai_api_key),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    IrisTextField(settings.model, viewModel::updateModel, label = stringResource(R.string.ai_model), singleLine = true, modifier = Modifier.fillMaxWidth())
                    IrisTextField(
                        settings.visionModel,
                        viewModel::updateVisionModel,
                        label = stringResource(R.string.ai_vision_model),
                        supportingText = stringResource(R.string.ai_vision_model_hint),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            CollapsibleCard(
                title = stringResource(R.string.ai_advanced),
                expanded = advanced,
                onToggle = { advanced = !advanced },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    IrisTextField(
                        settings.temperatureText,
                        viewModel::updateTemp,
                        label = stringResource(R.string.ai_temperature),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IrisTextField(
                        settings.maxTokensText,
                        viewModel::updateMaxTokens,
                        label = stringResource(R.string.ai_max_tokens),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                when (AiModelCapabilities.reasoningKind(settings.baseUrl, settings.model)) {
                    ReasoningKind.GLM_THINKING -> DropdownField(
                        label = stringResource(R.string.ai_thinking),
                        value = thinkingLabel(settings.thinkingMode),
                        options = GLM_THINKING_MODES.map { it to thinkingLabel(it) },
                        onSelect = viewModel::applyThinkingMode,
                    )
                    ReasoningKind.REASONING_EFFORT -> DropdownField(
                        label = stringResource(R.string.ai_thinking),
                        value = thinkingLabel(settings.thinkingMode),
                        options = REASONING_MODES.map { it to thinkingLabel(it) },
                        onSelect = viewModel::applyThinkingMode,
                    )
                    ReasoningKind.NONE -> Unit
                }
                DropdownField(
                    label = stringResource(R.string.ai_tag_profile),
                    value = settings.tagProfile,
                    options = TagProfile.entries.map { it.name to it.name },
                    onSelect = viewModel::updateTagProfile,
                )
                IrisTextField(
                    settings.extraJson,
                    viewModel::updateExtra,
                    label = stringResource(R.string.ai_extra_json),
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SectionHeader(stringResource(R.string.ai_skills_title, state.skills.size)) {
                    IconButton(onClick = { showSkillHelp = true }) {
                        Icon(Icons.Outlined.HelpOutline,
                            contentDescription = stringResource(R.string.ai_help_open),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { addingSkill = true }) {
                        Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(R.string.ai_skill_new),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = Spacing.xs),
                        )
                    }
                }
                Text(
                    stringResource(R.string.ai_skills_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.skills.forEach { skill ->
                    val bindings = state.skillBindings[skill.id].orEmpty()
                    ListRow(
                        title = skill.label,
                        subtitle = if (bindings.isEmpty()) stringResource(R.string.ai_all_workflows) else bindings.joinToString(" · "),
                        onClick = { editingSkill = skill },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                if (skill.builtin) StatusChip(stringResource(R.string.ai_builtin), tone = ChipTone.Accent)
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = stringResource(R.string.action_edit),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = Spacing.xs).size(18.dp),
                                )
                            }
                        },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                IrisTonalButton(
                    text = if (state.testing) stringResource(R.string.ai_testing) else stringResource(R.string.ai_test),
                    onClick = viewModel::test,
                    enabled = !state.testing,
                    icon = Icons.Outlined.NetworkCheck,
                )
                state.testResult?.let {
                    InfoBanner(text = it, tone = if (it.startsWith(okPrefix)) BannerTone.Success else BannerTone.Error)
                }
            }
        }
    }

    if (showSkillHelp) SkillUsageHelpDialog { showSkillHelp = false }

    editingSkill?.let { skill ->
        SkillEditDialog(
            skill = skill,
            initialPatterns = state.skillBindings[skill.id].orEmpty(),
            onDismiss = { editingSkill = null },
            onSave = { def, patterns -> viewModel.saveSkill(def, patterns); editingSkill = null },
            onDelete = if (!skill.builtin) ({ viewModel.deleteSkill(skill.id); editingSkill = null }) else null,
            onReset = if (skill.builtin) ({ viewModel.resetSkill(skill.id); editingSkill = null }) else null,
        )
    }
    if (addingSkill) {
        SkillEditDialog(
            skill = null,
            initialPatterns = emptyList(),
            onDismiss = { addingSkill = false },
            onSave = { def, patterns -> viewModel.addSkill(def.label, def.systemPrompt, patterns); addingSkill = false },
            onDelete = null,
            onReset = null,
        )
    }
}

/** Read-only field that opens a menu of [options] (value to label). */
@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { open = true },
        ) {
            Row(
                Modifier.padding(start = Spacing.lg, end = Spacing.md, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); open = false })
            }
        }
    }
}

private val GLM_THINKING_MODES = listOf("default", "glm_off", "glm_on")
private val REASONING_MODES = listOf("default", "low", "medium", "high", "max")

@Composable
private fun thinkingLabel(mode: String): String = when (mode) {
    "glm_off" -> stringResource(R.string.ai_thinking_glm_off)
    "glm_on" -> stringResource(R.string.ai_thinking_glm_on)
    "low", "medium", "high", "max" -> stringResource(R.string.ai_thinking_reasoning, mode)
    else -> stringResource(R.string.ai_thinking_default)
}

private data class AiProviderPreset(
    val label: String,
    val baseUrl: String,
    val model: String,
    val visionModel: String = "",
)

private val AI_PRESETS = listOf(
    AiProviderPreset("DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash", "deepseek-flash"),
    AiProviderPreset("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-5.3", "glm-5.3-flash"),
    AiProviderPreset("OpenAI", "https://api.openai.com/v1", "gpt-5.5", "gpt-5.5"),
    AiProviderPreset(
        "Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "qwen3.8-max", "qwen3.5-397b-a17b",
    ),
    AiProviderPreset("Moonshot", "https://api.moonshot.cn/v1", "kimi-k2.6", "kimi-k2.6"),
    AiProviderPreset("Ollama", "http://10.0.2.2:11434/v1", "qwen3.5", "qwen3.5"),
)

package com.comfyremote.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.comfyremote.app.R
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.ChoiceRow
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.ScreenContentPadding
import com.comfyremote.app.ui.components.SettingRow
import com.comfyremote.app.ui.components.SettingsGroup
import com.comfyremote.app.ui.components.StepperRow
import com.comfyremote.app.ui.components.SwitchRow
import com.comfyremote.app.ui.components.TextAction

@Composable
fun AppSettingsScreen(
    viewModel: AppSettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()

    IrisScaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.settings_app), onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            SettingsGroup(stringResource(R.string.settings_group_appearance)) {
                ChoiceRow(
                    title = stringResource(R.string.settings_theme),
                    detail = stringResource(R.string.settings_theme_detail),
                    options = listOf(
                        "system" to stringResource(R.string.settings_theme_system),
                        "light" to stringResource(R.string.settings_theme_light),
                        "dark" to stringResource(R.string.settings_theme_dark),
                    ),
                    selected = settings.themeMode,
                    onSelect = { mode -> viewModel.update { it.copy(themeMode = mode) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_compact),
                    detail = stringResource(R.string.settings_compact_detail),
                    checked = settings.compactUi,
                    onCheckedChange = { value -> viewModel.update { it.copy(compactUi = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_intro),
                    detail = stringResource(R.string.settings_intro_detail),
                    checked = settings.playIntro,
                    onCheckedChange = { value -> viewModel.update { it.copy(playIntro = value) } },
                )
                StepperRow(
                    title = stringResource(R.string.settings_recent_results),
                    detail = stringResource(R.string.settings_recent_results_detail),
                    value = settings.recentResultsCount,
                    range = 0..4,
                    onChange = { value -> viewModel.update { it.copy(recentResultsCount = value.coerceIn(0..4)) } },
                )
                StepperRow(
                    title = stringResource(R.string.settings_recent_presets),
                    detail = stringResource(R.string.settings_recent_presets_detail),
                    value = settings.recentPresetsCount,
                    range = 0..4,
                    onChange = { value -> viewModel.update { it.copy(recentPresetsCount = value.coerceIn(0..4)) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_project_link_prompt),
                    detail = stringResource(R.string.settings_project_link_prompt_detail),
                    checked = settings.showProjectLinkPrompt,
                    onCheckedChange = { enabled ->
                        viewModel.update { it.copy(showProjectLinkPrompt = enabled) }
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_server_workspace),
                    detail = stringResource(R.string.settings_server_workspace_detail),
                    checked = settings.showServerWorkspace,
                    onCheckedChange = { value -> viewModel.update { it.copy(showServerWorkspace = value) } },
                    divider = false,
                )
            }

            SettingsGroup(stringResource(R.string.settings_group_detail_sections)) {
                SwitchRow(
                    title = stringResource(R.string.settings_notes_expanded),
                    detail = stringResource(R.string.settings_notes_expanded_detail),
                    checked = settings.notesExpandedByDefault,
                    onCheckedChange = { value -> viewModel.update { it.copy(notesExpandedByDefault = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_advanced_expanded),
                    detail = stringResource(R.string.settings_advanced_expanded_detail),
                    checked = settings.advancedExpandedByDefault,
                    onCheckedChange = { value -> viewModel.update { it.copy(advancedExpandedByDefault = value) } },
                    divider = false,
                )
            }

            SettingsGroup(stringResource(R.string.settings_group_creation)) {
                StepperRow(
                    title = stringResource(R.string.settings_batch_count),
                    detail = stringResource(R.string.settings_batch_count_detail),
                    value = settings.defaultBatchCount,
                    range = 1..16,
                    onChange = { value -> viewModel.update { it.copy(defaultBatchCount = value.coerceIn(1..16)) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_local_queue),
                    detail = stringResource(R.string.settings_local_queue_detail),
                    checked = settings.defaultLocalQueue,
                    onCheckedChange = { value -> viewModel.update { it.copy(defaultLocalQueue = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_random_seed),
                    detail = stringResource(R.string.settings_random_seed_detail),
                    checked = settings.defaultRandomSeed,
                    onCheckedChange = { value -> viewModel.update { it.copy(defaultRandomSeed = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_auto_save),
                    detail = stringResource(R.string.settings_auto_save_detail),
                    checked = settings.autoSaveDrafts,
                    onCheckedChange = { value -> viewModel.update { it.copy(autoSaveDrafts = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_auto_restore),
                    detail = stringResource(R.string.settings_auto_restore_detail),
                    checked = settings.autoRestoreDrafts,
                    onCheckedChange = { value -> viewModel.update { it.copy(autoRestoreDrafts = value) } },
                )
                SwitchRow(
                    title = stringResource(R.string.settings_reuse_choose_workflow),
                    detail = stringResource(R.string.settings_reuse_choose_workflow_detail),
                    checked = settings.reuseChooseWorkflow,
                    onCheckedChange = { value -> viewModel.update { it.copy(reuseChooseWorkflow = value) } },
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_max_upload),
                    detail = stringResource(R.string.settings_max_upload_detail),
                    options = listOf("1024", "1536", "2048").map { it to stringResource(R.string.settings_px, it) },
                    selected = settings.maxImageDimension.toString(),
                    onSelect = { value ->
                        viewModel.update { it.copy(maxImageDimension = value.toIntOrNull() ?: 1536) }
                    },
                    divider = false,
                )
            }

            SettingsGroup(stringResource(R.string.settings_group_cloud_submit)) {
                SwitchRow(
                    title = stringResource(R.string.settings_confirm_cloud),
                    detail = stringResource(R.string.settings_confirm_cloud_detail),
                    checked = settings.confirmCloudSubmit,
                    onCheckedChange = { value -> viewModel.update { it.copy(confirmCloudSubmit = value) } },
                    divider = false,
                )
            }

            SettingsGroup(stringResource(R.string.settings_group_tasks)) {
                SwitchRow(
                    title = stringResource(R.string.settings_notify),
                    detail = stringResource(R.string.settings_notify_detail),
                    checked = settings.notifyOnFinish,
                    onCheckedChange = { value -> viewModel.update { it.copy(notifyOnFinish = value) } },
                    divider = false,
                )
            }

            SettingsGroup(stringResource(R.string.settings_group_storage)) {
                SettingRow(
                    title = stringResource(R.string.settings_cloud_input_cache),
                    detail = stringResource(R.string.settings_cloud_input_cache_detail),
                    trailing = { TextAction(stringResource(R.string.action_clear), onClick = viewModel::clearCloudInputs) },
                    divider = false,
                )
            }

            Text(
                stringResource(R.string.settings_apply_immediately),
                style = IrisType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

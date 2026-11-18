package com.comfyremote.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.comfyremote.app.R
import com.comfyremote.app.data.cloud.CloudProviderInfo
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.ScreenContentPadding
import com.comfyremote.app.ui.components.SettingRow
import com.comfyremote.app.ui.components.SettingsGroup

private data class SettingsEntry(
    val group: String,
    val title: String,
    val detail: String,
    val keywords: String,
    val icon: ImageVector,
    val onClick: (() -> Unit)? = null,
    val value: String? = null,
)

@Composable
fun SettingsScreen(
    version: String,
    onBack: () -> Unit,
    onApp: () -> Unit,
    onServers: () -> Unit,
    onAi: () -> Unit,
    onCloud: () -> Unit,
    onVersion: () -> Unit,
    cloudProviders: List<CloudProviderInfo> = emptyList(),
    onOpenProvider: (String) -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    val connections = stringResource(R.string.settings_group_connections)
    val aiCreation = stringResource(R.string.settings_group_ai_creation)
    val about = stringResource(R.string.settings_group_about)
    val providerGroup = stringResource(R.string.cloud_services_title)
    val providerKeywords = stringResource(R.string.settings_keywords_provider)

    val entries = listOf(
        SettingsEntry(
            group = connections,
            title = stringResource(R.string.settings_servers),
            detail = stringResource(R.string.settings_servers_detail),
            keywords = stringResource(R.string.settings_keywords_servers),
            icon = Icons.Outlined.Dns,
            onClick = onServers,
        ),
        SettingsEntry(
            group = connections,
            title = stringResource(R.string.settings_cloud),
            detail = stringResource(R.string.settings_cloud_detail),
            keywords = stringResource(R.string.settings_keywords_cloud),
            icon = Icons.Outlined.Cloud,
            onClick = onCloud,
        ),
        SettingsEntry(
            group = aiCreation,
            title = stringResource(R.string.settings_ai),
            detail = stringResource(R.string.settings_ai_detail),
            keywords = stringResource(R.string.settings_keywords_ai),
            icon = Icons.Outlined.AutoAwesome,
            onClick = onAi,
        ),
        SettingsEntry(
            group = aiCreation,
            title = stringResource(R.string.settings_app),
            detail = stringResource(R.string.settings_app_detail),
            keywords = stringResource(R.string.settings_keywords_app),
            icon = Icons.Outlined.Tune,
            onClick = onApp,
        ),
        SettingsEntry(
            group = about,
            title = stringResource(R.string.settings_version),
            detail = stringResource(R.string.settings_version_detail),
            keywords = stringResource(R.string.settings_keywords_update),
            icon = Icons.Outlined.Info,
            onClick = onVersion,
            value = stringResource(R.string.settings_version_value, version),
        ),
    )
    val providerEntries = cloudProviders.map { info ->
        SettingsEntry(
            group = providerGroup,
            title = info.label,
            detail = info.summary,
            keywords = "$providerKeywords ${info.label} ${info.summary}",
            icon = Icons.Outlined.Cloud,
            onClick = { onOpenProvider(info.id) },
            value = if (info.enabled) null else stringResource(R.string.cloud_provider_models_hidden),
        )
    }
    val allEntries = entries + providerEntries
    val trimmed = query.trim()
    val filtered = if (trimmed.isBlank()) emptyList() else allEntries.filter { entry ->
        entry.title.contains(trimmed, ignoreCase = true) ||
            entry.detail.contains(trimmed, ignoreCase = true) ||
            entry.keywords.contains(trimmed, ignoreCase = true)
    }

    IrisScaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.action_settings), onBack = onBack) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl),
        ) {
            IrisTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.settings_search_hint),
                singleLine = true,
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (trimmed.isNotBlank()) {
                if (filtered.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_search_no_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SettingsGroup(title = null) {
                        filtered.forEachIndexed { index, entry ->
                            SettingRow(
                                title = entry.title,
                                detail = entry.detail.ifBlank { null },
                                icon = entry.icon,
                                value = entry.value,
                                onClick = entry.onClick,
                                divider = index < filtered.lastIndex,
                            )
                        }
                    }
                }
            } else {
                entries.groupBy { it.group }.forEach { (group, items) ->
                    SettingsGroup(title = group) {
                        items.forEachIndexed { index, entry ->
                            SettingRow(
                                title = entry.title,
                                detail = entry.detail.ifBlank { null },
                                icon = entry.icon,
                                value = entry.value,
                                onClick = entry.onClick,
                                divider = index < items.lastIndex,
                            )
                        }
                    }
                }
            }
        }
    }
}

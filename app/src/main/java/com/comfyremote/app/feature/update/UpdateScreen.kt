package com.comfyremote.app.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Mono
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.BannerTone
import com.comfyremote.app.ui.components.BottomActionBar
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.InfoBanner
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisDivider
import com.comfyremote.app.ui.components.IrisPrimaryButton
import com.comfyremote.app.ui.components.IrisProgressBar
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisSecondaryButton
import com.comfyremote.app.ui.components.ScreenContentPadding
import com.comfyremote.app.ui.components.TextAction

@Composable
fun VersionScreen(
    viewModel: UpdateViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val release = state.release

    IrisScaffold(
        topBar = { DetailTopBar(title = stringResource(R.string.version_page_title), onBack = onBack) },
        bottomBar = if (release == null) null else ({
            BottomActionBar {
                IrisPrimaryButton(
                    text = stringResource(R.string.update_download_install),
                    onClick = viewModel::downloadAndInstall,
                    loading = state.downloading,
                    icon = Icons.Outlined.Download,
                )
            }
        }),
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            IrisCard {
                Text(
                    stringResource(R.string.update_current).uppercase(),
                    style = IrisType.overline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.update_version, state.currentVersion),
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = Mono),
                )
                Text(
                    stringResource(R.string.version_page_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IrisCard {
                Text(
                    stringResource(R.string.version_page_feedback_heading),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.version_page_feedback_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextAction(
                    text = stringResource(R.string.version_page_repository),
                    onClick = { uriHandler.openUri("https://github.com/Starysoor/Iris") },
                )
                TextAction(
                    text = stringResource(R.string.version_page_report_issue),
                    onClick = { uriHandler.openUri("https://github.com/Starysoor/Iris/issues/new") },
                )
                TextAction(
                    text = stringResource(R.string.version_page_release_history),
                    onClick = { uriHandler.openUri("https://github.com/Starysoor/Iris/releases") },
                )
            }

            Text(
                stringResource(R.string.version_page_update_heading),
                style = MaterialTheme.typography.titleMedium,
            )
            IrisSecondaryButton(
                text = if (state.checking) stringResource(R.string.update_checking) else stringResource(R.string.update_check),
                onClick = viewModel::check,
                enabled = !state.checking && !state.downloading,
                icon = Icons.Outlined.Refresh,
                height = 48.dp,
            )

            release?.let { rel ->
                IrisCard(highlighted = true) {
                    Text(
                        stringResource(R.string.update_new_version).uppercase(),
                        style = IrisType.overline,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(rel.name.ifBlank { rel.tag }, style = MaterialTheme.typography.titleLarge)
                    Text(rel.tag, style = IrisType.meta, color = MaterialTheme.colorScheme.primary)
                    rel.notes.takeIf(String::isNotBlank)?.let { notes ->
                        IrisDivider(Modifier.padding(vertical = Spacing.xs))
                        Text(
                            stringResource(R.string.update_release_notes),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            notes.take(1600),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                    if (state.downloading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                            modifier = Modifier.padding(top = Spacing.xs),
                        ) {
                            IrisProgressBar(progress = state.progress ?: 0f, modifier = Modifier.weight(1f))
                            Text(
                                stringResource(R.string.update_percent, ((state.progress ?: 0f) * 100).toInt()),
                                style = IrisType.meta,
                            )
                        }
                    } else {
                        TextAction(
                            text = stringResource(R.string.update_copy_link),
                            onClick = { clipboard.setText(AnnotatedString(rel.browserUrl)) },
                        )
                    }
                }
            }

            state.status?.let {
                InfoBanner(text = it, tone = if (state.isError) BannerTone.Error else BannerTone.Info)
            }

            Text(
                stringResource(R.string.update_unknown_apps_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
        }
    }
}

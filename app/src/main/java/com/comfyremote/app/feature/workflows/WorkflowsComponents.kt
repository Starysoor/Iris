package com.comfyremote.app.feature.workflows

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.R
import com.comfyremote.app.ServerEntry
import com.comfyremote.app.WorkflowEntry
import com.comfyremote.app.data.repository.PromptPreset
import com.comfyremote.app.data.repository.ServerHealth
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.ui.Iris
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.ListRow
import com.comfyremote.app.ui.components.StatusChip
import com.comfyremote.app.ui.components.StatusDot

/** Compact card in the server workspace strip: name, health dot and load counters. */
@Composable
internal fun ServerCard(
    server: ServerEntry,
    health: ServerHealth?,
    selected: Boolean,
    appActive: Int,
    switching: Boolean,
    onClick: () -> Unit,
) {
    val online = health?.online == true
    val busy = online && (health!!.running + health.pending + appActive) > 0
    IrisCard(
        modifier = Modifier.width(180.dp),
        onClick = if (switching) null else onClick,
        highlighted = selected,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                server.name.ifBlank { server.url },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (switching) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
            } else {
                StatusDot(
                    when {
                        busy -> Iris.colors.warning
                        online -> Iris.colors.success
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }
        }
        Text(
            if (online) stringResource(R.string.wf_server_stats, health!!.running, health.pending, appActive)
            else health?.error?.take(40) ?: stringResource(R.string.wf_not_checked),
            style = IrisType.metaSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "All providers" card for the Cloud workspace strip. */
@Composable
internal fun AllCloudProviderCard(
    modelCount: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IrisCard(
        modifier = Modifier.width(180.dp),
        onClick = onClick,
        highlighted = selected,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.wf_cloud_all),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusDot(if (modelCount > 0) Iris.colors.success else MaterialTheme.colorScheme.outline)
        }
        Text(
            stringResource(R.string.wf_cloud_all_count, modelCount),
            style = IrisType.metaSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** One provider card in the Cloud workspace strip. */
@Composable
internal fun CloudProviderCardItem(
    provider: CloudProviderCard,
    active: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val status = when {
        provider.error != null -> MaterialTheme.colorScheme.error
        !provider.configured -> Iris.colors.warning
        provider.modelCount > 0 -> Iris.colors.success
        else -> MaterialTheme.colorScheme.outline
    }
    IrisCard(
        modifier = Modifier.width(180.dp),
        onClick = onClick,
        highlighted = selected,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                provider.label,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            StatusDot(status)
        }
        Text(
            when {
                !provider.configured -> stringResource(R.string.wf_cloud_provider_token)
                provider.error != null -> provider.error
                provider.modelCount == 0 -> stringResource(R.string.wf_cloud_provider_empty)
                else -> stringResource(R.string.wf_cloud_provider_stats, provider.modelCount, active)
            },
            style = IrisType.metaSmall,
            color = if (provider.error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Square image tile for a recent result that has a local media file. */
@Composable
internal fun RecentResultTile(item: HistoryItem, path: String, onClick: () -> Unit) {
    Column(
        Modifier
            .width(120.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(120.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            MediaThumbnail(path, 120.dp, Modifier.clip(MaterialTheme.shapes.medium))
            if (item.favorite) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(4.dp).size(14.dp),
                    )
                }
            }
        }
        Text(
            item.wfName,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Fallback row for a recent result without a usable local media file. */
@Composable
internal fun RecentResultRow(item: HistoryItem, onClick: () -> Unit) {
    ListRow(
        title = item.wfName,
        subtitle = item.summary,
        leading = {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Icon(
                    Icons.Outlined.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                )
            }
        },
        meta = {
            if (item.favorite) FavoriteMark()
            StatusChip(stringResource(R.string.action_reuse), tone = ChipTone.Accent)
        },
        onClick = onClick,
    )
}

@Composable
internal fun RecentPresetRow(preset: PromptPreset, onClick: () -> Unit) {
    ListRow(
        title = preset.title,
        subtitle = preset.prompts.firstOrNull()?.value.orEmpty(),
        meta = {
            if (preset.favorite) FavoriteMark()
            StatusChip(stringResource(R.string.wf_setup))
        },
        onClick = onClick,
    )
}

@Composable
internal fun WorkflowRow(
    workflow: WorkflowEntry,
    favorite: Boolean,
    recent: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
) {
    ListRow(
        title = workflow.name,
        subtitle = if (workflow.cloud) workflow.description else null,
        titleMaxLines = 2,
        subtitleMaxLines = if (workflow.cloud) 2 else 1,
        footer = if (workflow.cloud) {
            {
                Column {
                    Text(
                        stringResource(R.string.wf_cloud_id, workflow.path.substringAfter(':')) +
                            workflow.version.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(),
                        style = IrisType.metaSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (workflow.unavailableReason.isNotBlank()) {
                        Text(workflow.unavailableReason, style = IrisType.metaSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else null,
        accent = favorite,
        meta = {
            StatusChip(workflowType(workflow))
            if (workflow.providerLabel.isNotBlank()) {
                StatusChip(workflow.providerLabel, tone = ChipTone.Neutral)
            }
            if (recent) StatusChip(stringResource(R.string.wf_recent), tone = ChipTone.Accent)
            val price = when {
                workflow.priceLabel.isNotBlank() -> workflow.priceLabel
                workflow.priceType.isNotBlank() -> workflow.priceType.replace('_', ' ')
                else -> null
            }
            price?.let {
                Text(
                    it,
                    style = IrisType.metaSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (workflow.sizeKb.isNotBlank()) {
                Text(workflow.sizeKb, style = IrisType.metaSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        },
        trailing = {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(if (favorite) R.string.wf_unfavorite else R.string.wf_favorite),
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        onClick = onClick,
    )
}

@Composable
private fun FavoriteMark() {
    Icon(
        Icons.Filled.Star,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(14.dp),
    )
}

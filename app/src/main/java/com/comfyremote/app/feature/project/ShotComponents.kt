package com.comfyremote.app.feature.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.data.project.ProjectShot
import com.comfyremote.app.data.project.VideoProject
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Sizes
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.AccentRail
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.StatusChip
import com.comfyremote.app.ui.components.TextAction

internal fun shotWorkflowLabel(
    shot: ProjectShot,
    project: VideoProject,
    workflows: List<ProjectWorkflowOption>,
): String? {
    val path = shot.workflowPath?.takeIf { it.isNotBlank() } ?: return null
    if (path == project.workflowPath) return null
    return workflows.firstOrNull { it.path == path }?.let { "${it.providerLabel} · ${it.name}" } ?: path
}

/** Tail-frame assets follow their shot when the timeline is reordered. */
@Composable
internal fun assetDisplayLabel(project: VideoProject, asset: ProjectAsset): String {
    val shotId = asset.id.removePrefix("tail:").takeIf { asset.id.startsWith("tail:") }
    val index = shotId?.let { id -> project.shots.indexOfFirst { it.id == id } } ?: -1
    return if (index >= 0) {
        stringResource(R.string.board_tail_asset_label, index + 1)
    } else {
        asset.label.ifBlank { referenceRoleLabel(asset.role) }
    }
}

@Composable
internal fun referenceRoleLabel(role: ReferenceRole): String = when (role) {
    ReferenceRole.AUTO -> stringResource(R.string.reference_role_auto)
    ReferenceRole.START_FRAME -> stringResource(R.string.reference_role_start)
    ReferenceRole.END_FRAME -> stringResource(R.string.reference_role_end)
    ReferenceRole.CHARACTER_REFERENCE -> stringResource(R.string.reference_role_character)
    ReferenceRole.SCENE_REFERENCE -> stringResource(R.string.reference_role_scene)
    ReferenceRole.STYLE_REFERENCE -> stringResource(R.string.reference_role_style)
    ReferenceRole.OUTFIT_REFERENCE -> stringResource(R.string.reference_role_outfit)
    ReferenceRole.COMPOSITION_REFERENCE -> stringResource(R.string.reference_role_composition)
    ReferenceRole.ACTION_REFERENCE -> stringResource(R.string.reference_role_action)
    ReferenceRole.GENERIC_REFERENCE -> stringResource(R.string.reference_role_generic)
}

@Composable
internal fun AssetCard(asset: ProjectAsset, label: String, onDelete: () -> Unit) {
    Column(
        Modifier.width(96.dp),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box {
            MediaThumbnail(asset.path, 96.dp, Modifier.clip(MaterialTheme.shapes.medium))
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    Icons.Outlined.Delete,
                    stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val roleLabel = referenceRoleLabel(asset.role)
        if (label != roleLabel) {
            Text(
                roleLabel,
                style = IrisType.metaSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** One shot on the timeline: accent rail, media, prompt, status and actions. */
@Composable
internal fun ShotTimelineItem(
    index: Int,
    shot: ProjectShot,
    workflowLabel: String?,
    referenceAssets: List<ProjectAsset>,
    referenceFromPrevious: Boolean,
    feedsNext: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canUseAssets: Boolean,
    previousFramePath: String?,
    onUsePreviousFrame: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onInsertAfter: () -> Unit,
    onPickWorkflow: () -> Unit,
    onPickAsset: () -> Unit,
    onClearReference: () -> Unit,
    onEditShot: () -> Unit,
    onPickFirstFrame: (() -> Unit)?,
    onPickLastFrame: (() -> Unit)?,
    onOpenTakes: (() -> Unit)?,
) {
    var shotMenu by remember { mutableStateOf(false) }
    val (statusLabel, tone) = when (shot.status) {
        "done" -> stringResource(R.string.board_status_done) to ChipTone.Success
        "running" -> stringResource(R.string.board_status_running) to ChipTone.Accent
        "failed" -> stringResource(R.string.board_status_failed) to ChipTone.Error
        else -> stringResource(R.string.board_status_draft) to ChipTone.Neutral
    }
    val takeCount = shot.takes.size
    val running = shot.status == "running"
    val tailFrame = shot.lastFramePath?.takeIf {
        it != shot.resultPath && shot.referenceAssetIds.isEmpty()
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        IrisCard {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AccentRail()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.board_shot_number, index + 1),
                            style = IrisType.overline,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (takeCount > 0) {
                            Text(
                                stringResource(R.string.proj_takes_count, takeCount),
                                style = IrisType.metaSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = Spacing.sm),
                            )
                        }
                        StatusChip(statusLabel, tone = tone)
                    }
                    Text(
                        shot.prompt.ifBlank { stringResource(R.string.board_empty_shot) },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    shot.plannedDurationSeconds?.let { seconds ->
                        Text(
                            stringResource(R.string.studio_shot_planned_seconds, seconds),
                            style = IrisType.metaSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    shot.cameraNotes?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            Icon(
                                Icons.Outlined.Videocam,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                it,
                                style = IrisType.metaSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    workflowLabel?.let {
                        Text(
                            stringResource(R.string.board_shot_workflow_value, it),
                            style = IrisType.metaSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Ordered references carry their semantic role as a badge;
                    // the submission maps them to image fields in this order.
                    if (referenceAssets.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            referenceAssets.forEach { asset ->
                                ShotMedia(
                                    asset.path,
                                    if (asset.role != ReferenceRole.AUTO) referenceRoleLabel(asset.role) else null,
                                )
                            }
                        }
                    } else if (shot.referencePath != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            ShotMedia(
                                shot.referencePath!!,
                                if (shot.referenceRole != ReferenceRole.AUTO) referenceRoleLabel(shot.referenceRole) else null,
                            )
                        }
                    }
                    if (shot.resultPath != null || tailFrame != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            shot.resultPath?.let { ShotMedia(it, stringResource(R.string.board_result)) }
                            tailFrame?.let { ShotMedia(it, stringResource(R.string.board_tail_frame)) }
                        }
                    }
                    if (referenceFromPrevious) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Icon(Icons.Outlined.Link, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Text(
                                stringResource(R.string.board_reference_from_previous),
                                style = IrisType.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    shot.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { shotMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
                            }
                            DropdownMenu(shotMenu, { shotMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.proj_shot_edit)) },
                                    enabled = !running,
                                    onClick = { shotMenu = false; onEditShot() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.proj_takes_open, takeCount)) },
                                    enabled = takeCount > 0,
                                    onClick = { shotMenu = false; onOpenTakes?.invoke() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_move_up)) },
                                    enabled = canMoveUp && !running,
                                    onClick = { shotMenu = false; onMoveUp() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_move_down)) },
                                    enabled = canMoveDown && !running,
                                    onClick = { shotMenu = false; onMoveDown() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_duplicate)) },
                                    enabled = !running,
                                    onClick = { shotMenu = false; onDuplicate() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_insert_after)) },
                                    enabled = !running,
                                    onClick = { shotMenu = false; onInsertAfter() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_shot_workflow)) },
                                    enabled = !running,
                                    onClick = { shotMenu = false; onPickWorkflow() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_use_asset)) },
                                    enabled = canUseAssets && !running,
                                    onClick = { shotMenu = false; onPickAsset() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.proj_shot_set_first_frame)) },
                                    enabled = canUseAssets && !running && onPickFirstFrame != null,
                                    onClick = { shotMenu = false; onPickFirstFrame?.invoke() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.proj_shot_set_last_frame)) },
                                    enabled = canUseAssets && !running && onPickLastFrame != null,
                                    onClick = { shotMenu = false; onPickLastFrame?.invoke() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_use_previous_frame)) },
                                    enabled = previousFramePath != null && !running,
                                    onClick = { shotMenu = false; onUsePreviousFrame() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.board_clear_reference)) },
                                    enabled = shot.referencePath != null && !running,
                                    onClick = { shotMenu = false; onClearReference() },
                                )
                            }
                        }
                        TextAction(
                            text = when {
                                shot.status == "done" -> stringResource(R.string.board_regenerate)
                                running -> stringResource(R.string.phase_running)
                                else -> stringResource(R.string.board_prepare)
                            },
                            onClick = onOpen,
                            enabled = !running,
                        )
                        TextAction(
                            stringResource(R.string.action_delete),
                            onClick = onDelete,
                            enabled = !running,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        if (feedsNext) {
            Row(
                Modifier.padding(start = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Icon(Icons.Outlined.ArrowDownward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.board_chain_next, index + 2),
                    style = IrisType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ShotMedia(path: String, caption: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        MediaThumbnail(path, Sizes.thumbMd, Modifier.clip(MaterialTheme.shapes.medium))
        caption?.let {
            Text(it, style = IrisType.metaSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

package com.comfyremote.app.feature.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.comfyremote.app.R
import com.comfyremote.app.data.project.ProjectShot
import com.comfyremote.app.data.project.ShotTake
import com.comfyremote.app.data.project.TakeComparisonFacts
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.feature.generate.isVideoPath
import com.comfyremote.app.ui.Spacing

/** Side-by-side, independently selected variants. Read-only until explicit promotion. */
@Composable
internal fun TakeComparisonDialog(
    shot: ProjectShot,
    takes: List<ShotTake>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var leftId by remember(shot.id) { mutableStateOf(takes.firstOrNull()?.id) }
    var rightId by remember(shot.id) { mutableStateOf(takes.drop(1).firstOrNull()?.id) }
    var previewPath by remember(shot.id) { mutableStateOf<String?>(null) }
    val left = takes.firstOrNull { it.id == leftId } ?: takes.firstOrNull()
    val right = takes.firstOrNull { it.id == rightId }
        ?: takes.firstOrNull { it.id != left?.id }
    val comparison = if (left != null && right != null) TakeComparisonFacts.compare(left, right) else null

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.97f).heightIn(max = 760.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier.padding(Spacing.gutter).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(stringResource(R.string.proj_take_compare_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.proj_take_compare_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (comparison != null && left != null && right != null) {
                    Text(
                        stringResource(R.string.proj_take_compare_count, comparison.differenceCount),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        TakeSide(
                            sideLabel = "A", takes = takes, current = left,
                            selectedId = shot.selectedTakeId, allowPromotion = shot.status != "running",
                            modifier = Modifier.weight(1f),
                            onChoose = { leftId = it }, onPreview = { previewPath = it }, onSelect = onSelect,
                        )
                        TakeSide(
                            sideLabel = "B", takes = takes, current = right,
                            selectedId = shot.selectedTakeId, allowPromotion = shot.status != "running",
                            modifier = Modifier.weight(1f),
                            onChoose = { rightId = it }, onPreview = { previewPath = it }, onSelect = onSelect,
                        )
                    }
                    val differences = listOf(
                        stringResource(R.string.proj_take_compare_provider) to comparison.provider,
                        stringResource(R.string.proj_take_compare_model) to comparison.model,
                        stringResource(R.string.proj_take_compare_seed) to comparison.seed,
                        stringResource(R.string.proj_take_compare_cost) to comparison.cost,
                        stringResource(R.string.proj_take_compare_refs) to comparison.references,
                        stringResource(R.string.proj_take_compare_params) to comparison.parameters,
                        stringResource(R.string.proj_take_compare_prompt) to comparison.prompt,
                    )
                    differences.forEach { (label, values) ->
                        if (values.differs) {
                            Text(
                                "$label: A ≠ B",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
    previewPath?.let { path ->
        TakeMediaPreviewDialog(path = path, onDismiss = { previewPath = null })
    }
}

@Composable
private fun TakeSide(
    sideLabel: String,
    takes: List<ShotTake>,
    current: ShotTake,
    selectedId: String?,
    allowPromotion: Boolean,
    modifier: Modifier,
    onChoose: (String) -> Unit,
    onPreview: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    var dropdown by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Box {
            TextButton(onClick = { dropdown = true }) {
                Text("$sideLabel · " + current.id.take(8) + " ▾", maxLines = 1)
            }
            DropdownMenu(expanded = dropdown, onDismissRequest = { dropdown = false }) {
                takes.forEach { take ->
                    DropdownMenuItem(
                        text = { Text(take.id.take(8) + " · " + take.status, maxLines = 1) },
                        onClick = { onChoose(take.id); dropdown = false },
                    )
                }
            }
        }
        current.resultPath?.let { path ->
            MediaThumbnail(
                path, 124.dp,
                Modifier.fillMaxWidth().height(124.dp).clip(MaterialTheme.shapes.medium)
                    .clickable { onPreview(path) },
            )
            TextButton(onClick = { onPreview(path) }) {
                Text(stringResource(R.string.proj_take_compare_preview))
            }
        }
        if (current.id == selectedId) {
            Text(stringResource(R.string.proj_takes_primary), color = MaterialTheme.colorScheme.primary)
        }
        Text(current.status, style = MaterialTheme.typography.labelSmall)
        Text(current.providerId.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
        Text(current.workflowPath.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.proj_take_compare_seed) + ": " + (current.seed ?: "—"),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.proj_take_compare_cost) + ": " +
            (current.costEstimate?.toString() ?: "?"), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.proj_take_compare_refs) + ": " +
            current.referenceAssetIds.size, style = MaterialTheme.typography.bodySmall)
        Text(current.prompt.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall, maxLines = 4)
        if (allowPromotion && current.status == "done" && current.id != selectedId) {
            TextButton(onClick = { onSelect(current.id) }) {
                Text(stringResource(R.string.proj_takes_select))
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TakeMediaPreviewDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val video = isVideoPath(path)
    val player = remember(path, video) {
        if (!video) null else ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path))))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.98f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                if (player != null) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
                        update = { it.player = player },
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    )
                } else {
                    MediaThumbnail(path, 300.dp, Modifier.fillMaxWidth().heightIn(min = 180.dp))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        }
    }
}

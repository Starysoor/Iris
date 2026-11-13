package com.comfyremote.app.feature.project

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.comfyremote.app.R
import com.comfyremote.app.data.project.Timeline
import com.comfyremote.app.data.project.ExportPlanBuilder
import com.comfyremote.app.data.project.VideoExporter
import com.comfyremote.app.data.project.TimelineClip
import com.comfyremote.app.data.project.TrackKind
import com.comfyremote.app.data.project.VideoProject
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.BannerTone
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.InfoBanner
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisPrimaryButton
import com.comfyremote.app.ui.components.IrisProgressBar
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisSwitch
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.SectionHeader
import com.comfyremote.app.ui.components.SegmentedControl
import com.comfyremote.app.ui.components.StatusChip
import com.comfyremote.app.ui.components.TextAction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val SPEED_STEPS = listOf(0.5f, 1f, 1.5f, 2f)

@Composable
fun TimelineScreen(
    viewModel: ProjectViewModel,
    projectId: String,
    onBack: () -> Unit,
) {
    val projects by viewModel.projects.collectAsState()
    val message by viewModel.message.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val snackbar = remember { SnackbarHostState() }
    var addingFor by remember { mutableStateOf<TrackKind?>(null) }
    var editing by remember { mutableStateOf<Pair<TrackKind, TimelineClip>?>(null) }
    var previewing by remember { mutableStateOf(false) }
    var exportSheet by remember { mutableStateOf(false) }
    val exportState by viewModel.exportState.collectAsState()

    LaunchedEffect(project?.id) {
        if (project != null && project.timeline == null) viewModel.ensureTimeline(projectId)
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.proj_timeline_title),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = { previewing = true },
                        enabled = project?.timeline?.videoTrack?.isNotEmpty() == true,
                    ) {
                        Icon(Icons.Outlined.PlayArrow, stringResource(R.string.proj_timeline_preview))
                    }
                    IconButton(
                        onClick = {
                            viewModel.startExport(projectId)
                            exportSheet = true
                        },
                        enabled = project?.timeline?.videoTrack?.isNotEmpty() == true,
                    ) {
                        Icon(Icons.Outlined.Movie, stringResource(R.string.proj_export_start))
                    }
                },
            )
        },
    ) { pad ->
        if (project == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.proj_not_found), icon = Icons.Outlined.PlayArrow)
            }
            return@IrisScaffold
        }
        val timeline = project.timeline ?: Timeline()
        // LazyListScope blocks are not composable: resolve strings up front.
        val videoTrackTitle = stringResource(R.string.proj_timeline_video_track)
        val videoTrackEmpty = stringResource(R.string.proj_timeline_video_empty)
        val audioTrackTitle = stringResource(R.string.proj_timeline_audio_track)
        val audioTrackEmpty = stringResource(R.string.proj_timeline_audio_empty)
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item("summary") {
                IrisCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(
                            stringResource(R.string.proj_timeline_duration, formatSeconds(timeline.totalDurationMs)),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(
                            stringResource(R.string.proj_timeline_video_clips, timeline.videoTrack.size),
                            tone = ChipTone.Neutral,
                        )
                        StatusChip(
                            stringResource(R.string.proj_timeline_audio_clips, timeline.audioTrack.size),
                            tone = ChipTone.Neutral,
                        )
                    }
                }
            }
            if (timeline.audioTrack.isNotEmpty()) {
                item("audio-note") {
                    InfoBanner(
                        text = stringResource(R.string.proj_timeline_audio_export_note),
                        tone = BannerTone.Info,
                    )
                }
            }
            item("video-add") {
                IrisPrimaryButton(
                    text = stringResource(R.string.proj_timeline_add_video),
                    icon = Icons.Outlined.Add,
                    onClick = { addingFor = TrackKind.VIDEO },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            trackSection(
                key = "video",
                title = videoTrackTitle,
                clips = timeline.videoTrack,
                emptyHint = videoTrackEmpty,
                onAdd = { addingFor = TrackKind.VIDEO },
                onEdit = { editing = TrackKind.VIDEO to it },
                onMove = { clipId, delta -> viewModel.moveTimelineClip(projectId, TrackKind.VIDEO, clipId, delta) },
                onRemove = { viewModel.removeTimelineClip(projectId, TrackKind.VIDEO, it) },
            )
            item("audio-add") {
                TextAction(stringResource(R.string.proj_timeline_add_audio), onClick = { addingFor = TrackKind.AUDIO })
            }
            trackSection(
                key = "audio",
                title = audioTrackTitle,
                clips = timeline.audioTrack,
                emptyHint = audioTrackEmpty,
                onAdd = { addingFor = TrackKind.AUDIO },
                onEdit = { editing = TrackKind.AUDIO to it },
                onMove = { clipId, delta -> viewModel.moveTimelineClip(projectId, TrackKind.AUDIO, clipId, delta) },
                onRemove = { viewModel.removeTimelineClip(projectId, TrackKind.AUDIO, it) },
            )
        }
    }

    addingFor?.let { kind ->
        if (project == null) {
            addingFor = null
        } else {
            ClipSourcePickerDialog(
                project = project,
                kind = kind,
                onDismiss = { addingFor = null },
                onPick = { path, label ->
                    viewModel.addTimelineClip(projectId, kind, path, label)
                    addingFor = null
                },
            )
        }
    }
    editing?.let { (kind, clip) ->
        EditClipDialog(
            clip = clip,
            onDismiss = { editing = null },
            onSave = { inMs, outMs, speed, volume, muted ->
                viewModel.updateTimelineClip(projectId, kind, clip.id, inMs, outMs, speed, volume, muted)
                editing = null
            },
            onDelete = {
                viewModel.removeTimelineClip(projectId, kind, clip.id)
                editing = null
            },
        )
    }
    if (previewing && project != null) {
        PreviewDialog(
            timeline = project.timeline ?: Timeline(),
            onDismiss = { previewing = false },
        )
    }
    if (exportSheet) {
        ExportDialog(
            state = exportState,
            onDismiss = {
                exportSheet = false
                viewModel.dismissExport()
            },
            onCancel = { viewModel.cancelExport() },
            onRetry = { viewModel.startExport(projectId) },
            onSaveToGallery = { viewModel.saveExportToGallery(it) },
        )
    }
}

/** Export progress / result dialog; cancellation and retry are explicit. */
@Composable
private fun ExportDialog(
    state: ProjectViewModel.ExportState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onSaveToGallery: (String) -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (state) {
                    is ProjectViewModel.ExportState.Running -> {
                        Text(stringResource(R.string.proj_export_running, (state.progress * 100).toInt()))
                        IrisProgressBar(progress = state.progress)
                        Text(
                            stringResource(R.string.proj_export_running_note),
                            style = IrisType.metaSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ProjectViewModel.ExportState.Succeeded -> {
                        Text(stringResource(R.string.proj_export_done))
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                            TextAction(stringResource(R.string.proj_export_share), onClick = {
                                com.comfyremote.app.feature.generate.shareMedia(context, state.outputPath)
                            })
                            TextAction(stringResource(R.string.proj_export_save_gallery), onClick = {
                                onSaveToGallery(state.outputPath)
                            })
                        }
                        Text(
                            stringResource(R.string.proj_export_done_note),
                            style = IrisType.metaSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ProjectViewModel.ExportState.Failed -> {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                        Text(
                            stringResource(R.string.proj_export_failed_note),
                            style = IrisType.metaSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ProjectViewModel.ExportState.Idle -> Text(stringResource(R.string.proj_export_start))
                }
            }
        },
        confirmButton = {
            when (state) {
                is ProjectViewModel.ExportState.Running -> TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
                is ProjectViewModel.ExportState.Failed -> TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.proj_export_retry))
                }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** Positive vertical drag moves a clip down, negative moves it up. */
internal fun timelineDragDirection(distancePx: Float, thresholdPx: Float): Int = when {
    thresholdPx <= 0f -> 0
    distancePx >= thresholdPx -> 1
    distancePx <= -thresholdPx -> -1
    else -> 0
}

private fun LazyListScope.trackSection(
    key: String,
    title: String,
    clips: List<TimelineClip>,
    emptyHint: String,
    onAdd: () -> Unit,
    onEdit: (TimelineClip) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    item("$key-heading") {
        SectionHeader(title) {
            TextAction(stringResource(R.string.proj_timeline_add), onClick = onAdd)
        }
    }
    if (clips.isEmpty()) {
        item("$key-empty") {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        items(clips.size, key = { "$key-" + clips[it].id }) { index ->
            val clip = clips[index]
            var menu by remember { mutableStateOf(false) }
            val dragThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }
            val currentClips by rememberUpdatedState(clips)
            val currentOnMove by rememberUpdatedState(onMove)
            val dragDescription = stringResource(R.string.proj_timeline_drag_to_reorder)
            // A long press on the card starts reordering. Each threshold crossing is
            // a single adjacent swap, so store ordering remains the source of truth.
            IrisCard(
                Modifier.fillMaxWidth().pointerInput(clip.id, dragThresholdPx) {
                    var accumulatedDrag = 0f
                    detectDragGesturesAfterLongPress(
                        onDragStart = { accumulatedDrag = 0f },
                        onDragEnd = { accumulatedDrag = 0f },
                        onDragCancel = { accumulatedDrag = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedDrag += dragAmount.y
                            val direction = timelineDragDirection(accumulatedDrag, dragThresholdPx)
                            if (direction != 0) {
                                val currentIndex = currentClips.indexOfFirst { it.id == clip.id }
                                val targetIndex = currentIndex + direction
                                if (currentIndex >= 0 && targetIndex in currentClips.indices) {
                                    currentOnMove(clip.id, direction)
                                }
                                accumulatedDrag = 0f
                            }
                        },
                    )
                },
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        "≡",
                        modifier = Modifier.semantics { contentDescription = dragDescription },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MediaThumbnail(clip.sourcePath, 56.dp, Modifier.clip(MaterialTheme.shapes.medium))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            clip.sourceLabel.ifBlank { clip.sourcePath.substringAfterLast('/') },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusChip(formatSeconds(clip.durationMs), tone = ChipTone.Neutral)
                            if (clip.speed != 1f) {
                                StatusChip("${clip.speed}x", tone = ChipTone.Accent)
                            }
                            if (clip.muted) {
                                StatusChip(stringResource(R.string.proj_timeline_muted), tone = ChipTone.Warning)
                            } else if (clip.volume != 1f) {
                                StatusChip("${(clip.volume * 100).toInt()}%", tone = ChipTone.Neutral)
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.proj_timeline_edit_clip)) },
                                onClick = { menu = false; onEdit(clip) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_move_up)) },
                                onClick = { menu = false; onMove(clip.id, -1) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.board_move_down)) },
                                onClick = { menu = false; onMove(clip.id, 1) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onRemove(clip.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Ordered list of media sources eligible for the requested track. */
internal fun clipSources(project: VideoProject, kind: TrackKind): List<Pair<String, String>> {
    val sources = LinkedHashMap<String, String>()
    if (kind == TrackKind.VIDEO) {
        project.shots.forEachIndexed { index, shot ->
            shot.resultPath?.takeIf { it.isNotBlank() }?.let { path ->
                sources[path] = "镜头 ${index + 1}"
            }
        }
    }
    project.assets
        .filter {
            if (kind == TrackKind.AUDIO) it.kind == AssetKind.AUDIO else it.kind == AssetKind.VIDEO
        }
        .forEach { asset ->
            sources.putIfAbsent(asset.path, asset.label.ifBlank { asset.path.substringAfterLast('/') })
        }
    return sources.map { it.key to it.value }
}

@Composable
private fun ClipSourcePickerDialog(
    project: VideoProject,
    kind: TrackKind,
    onDismiss: () -> Unit,
    onPick: (path: String, label: String) -> Unit,
) {
    val sources = clipSources(project, kind)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (kind == TrackKind.VIDEO) R.string.proj_timeline_add_video else R.string.proj_timeline_add_audio,
                ),
            )
        },
        text = {
            if (sources.isEmpty()) {
                Text(
                    stringResource(
                        if (kind == TrackKind.VIDEO) R.string.proj_timeline_no_video_sources else R.string.proj_timeline_no_audio_sources,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sources.forEach { (path, label) ->
                        TextButton(onClick = { onPick(path, label) }) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun EditClipDialog(
    clip: TimelineClip,
    onDismiss: () -> Unit,
    onSave: (inMs: Long, outMs: Long, speed: Float, volume: Float, muted: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var inText by remember(clip.id) { mutableStateOf(clip.sourceInMs.toString()) }
    var outText by remember(clip.id) { mutableStateOf(clip.sourceOutMs.toString()) }
    var speedIndex by remember(clip.id) { mutableStateOf(SPEED_STEPS.indexOfFirst { it == clip.speed }.takeIf { it >= 0 } ?: 1) }
    var volume by remember(clip.id) { mutableStateOf(if (clip.muted) 0f else clip.volume) }
    var muted by remember(clip.id) { mutableStateOf(clip.muted) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_timeline_edit_clip)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    IrisTextField(
                        value = inText,
                        onValueChange = { inText = it.filter(Char::isDigit).take(8) },
                        label = stringResource(R.string.proj_timeline_in_ms),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IrisTextField(
                        value = outText,
                        onValueChange = { outText = it.filter(Char::isDigit).take(8) },
                        label = stringResource(R.string.proj_timeline_out_ms),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    stringResource(R.string.proj_timeline_trim_hint, formatSeconds(clip.trimmedMs)),
                    style = IrisType.metaSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(stringResource(R.string.proj_timeline_speed), style = MaterialTheme.typography.titleSmall)
                SegmentedControl(
                    options = SPEED_STEPS.map { "${it}x" },
                    selectedIndex = speedIndex,
                    onSelect = { speedIndex = it },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.proj_timeline_volume), style = MaterialTheme.typography.titleSmall)
                        Slider(
                            value = volume,
                            onValueChange = { volume = it },
                            valueRange = 0f..2f,
                            enabled = !muted,
                        )
                    }
                    Text("${(volume * 100).toInt()}%", style = IrisType.metaSmall)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.proj_timeline_mute), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.proj_timeline_mute_hint),
                            style = IrisType.metaSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IrisSwitch(checked = muted, onCheckedChange = { muted = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val inMs = inText.toLongOrNull() ?: 0L
                val outMs = outText.toLongOrNull() ?: (inMs + 1)
                onSave(inMs, outMs.coerceAtLeast(inMs + 1), SPEED_STEPS[speedIndex], volume, muted)
            }) { Text(stringResource(R.string.board_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * Export-faithful preview: create a disposable MP4 using the SAME plan and
 * Transformer as the final export. Video speed, source volume/mute and the
 * parallel audio overlay cannot silently diverge from preview playback.
 * The temporary movie is never added to History or the user's media library.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PreviewDialog(timeline: Timeline, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exporter = remember(timeline) { VideoExporter(context.applicationContext) }
    val previewFile = remember(timeline) {
        File(context.cacheDir, "iris-preview-${UUID.randomUUID()}.mp4")
    }
    var progress by remember(timeline) { mutableStateOf(0f) }
    var ready by remember(timeline) { mutableStateOf(false) }
    var error by remember(timeline) { mutableStateOf<String?>(null) }

    LaunchedEffect(timeline, exporter) {
        try {
            val plan = withContext(Dispatchers.IO) {
                val sources = (timeline.videoTrack + timeline.audioTrack).map { it.sourcePath }.toSet()
                val missing = sources.filterNot { File(it).isFile }
                check(missing.isEmpty()) { "Missing media: ${missing.joinToString(", ") { it.substringAfterLast('/') }}" }
                val durations = sources.associateWith { path ->
                    val metadata = MediaMetadataRetriever()
                    try {
                        metadata.setDataSource(path)
                        metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    } finally {
                        metadata.release()
                    }
                }
                check(durations.values.all { it != null && it > 0L }) { "Unreadable video or audio source" }
                ExportPlanBuilder.build(timeline, sources, durations.mapValues { it.value!! })
            }
            check(plan.items.isNotEmpty()) { "No video clips to preview" }
            check(plan.skippedMissing.isEmpty() && plan.invalidTrims.isEmpty()) {
                "Missing clips or invalid trim: ${(plan.skippedMissing + plan.invalidTrims).joinToString(", ")}"
            }
            val result = exporter.export(plan, previewFile) { progress = it }
            ready = result is VideoExporter.Result.Success
            if (!ready) error = "Preview was cancelled"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: failure.javaClass.simpleName
        } finally {
            if (!ready) previewFile.delete()
        }
    }

    // Close or changing the timeline must cancel a render before reclaiming
    // only its own temp media. No app-owned source file is ever modified.
    DisposableEffect(exporter, previewFile) {
        onDispose {
            exporter.cancel()
            previewFile.delete()
        }
    }

    val previewPlayer = remember(previewFile, ready) {
        if (!ready) null else ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(previewFile)))
            prepare()
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer?.release() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_timeline_preview)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when {
                    previewPlayer != null -> AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { player = previewPlayer } },
                        update = { view -> view.player = previewPlayer },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                    error != null -> InfoBanner(error.orEmpty(), tone = BannerTone.Error)
                    else -> {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.proj_timeline_preview_preparing, (progress * 100).toInt()))
                        }
                    }
                }
                Text(
                    stringResource(R.string.proj_timeline_preview_note),
                    style = IrisType.metaSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

internal fun formatSeconds(ms: Long): String {
    val totalSeconds = ms / 1000.0
    return if (totalSeconds >= 60) {
        String.format(Locale.US, "%d:%04.1f", totalSeconds.toLong() / 60, totalSeconds % 60)
    } else {
        String.format(Locale.US, "%.1fs", totalSeconds)
    }
}

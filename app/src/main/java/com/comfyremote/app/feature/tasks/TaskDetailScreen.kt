package com.comfyremote.app.feature.tasks

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.data.generation.GenerationTask
import com.comfyremote.app.data.generation.ResultImage
import com.comfyremote.app.data.generation.uiLabel
import com.comfyremote.app.data.media.decodeSampled
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.feature.generate.MediaViewerDialog
import com.comfyremote.app.feature.generate.isNonImageMedia
import com.comfyremote.app.feature.generate.shareMedia
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.ErrorCard
import com.comfyremote.app.ui.components.IconAction
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.TextAction
import com.comfyremote.app.ui.components.IrisScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TaskDetailScreen(
    viewModel: TasksViewModel,
    taskId: String,
    onBack: () -> Unit,
    onReuse: (DraftTransfer) -> Unit,
    onOpenProject: (String, String?) -> Unit = { _, _ -> },
) {
    val tasks by viewModel.tasks.collectAsState()
    val message by viewModel.message.collectAsState()
    val task = tasks.firstOrNull { it.taskId == taskId }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedText = stringResource(R.string.tasks_diag_copied)
    var viewingPath by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmCloudCancel by remember { mutableStateOf(false) }
    var confirmLocalInterrupt by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.tasks_detail_title),
                subtitle = task?.request?.workflowName,
                onBack = onBack,
            )
        },
    ) { pad ->
        if (task == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.tasks_gone), stringResource(R.string.tasks_gone_detail))
            }
            return@IrisScaffold
        }
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (task.results.isNotEmpty()) {
                items(task.results, key = { it.id }) { result ->
                    ResultMedia(result) { viewingPath = result.localPath }
                }
            }
            item("status") { StatusBlock(task) }
            task.error?.let { error ->
                item("error") {
                    ErrorCard(
                        title = error.uiLabel(),
                        detail = listOfNotNull(error.nodeTitle, error.nodeType, error.nodeId?.let { "#$it" }, error.message).joinToString(" · "),
                    )
                }
            }
            item("actions") {
                TaskActions(
                    task = task,
                    onCancel = {
                        when {
                            task.request.provider != "comfyui" -> confirmCloudCancel = true
                            task.phase == GenerationPhase.RUNNING -> confirmLocalInterrupt = true
                            else -> viewModel.cancel(task.taskId)
                        }
                    },
                    onRetry = { viewModel.retry(task.taskId) },
                    onReuse = { viewModel.prepareReuse(task.taskId)?.let(onReuse) },
                    onRecover = { viewModel.recover(task.taskId) },
                    onCopyDiagnostics = {
                        clipboard.setText(AnnotatedString(viewModel.diagnostics(task.taskId)))
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                    onDelete = { confirmDelete = true },
                )
            }
            task.request.storyboardProjectId?.let { projectId ->
                item("storyboard") {
                    IrisCard {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                viewModel.storyboardLabel(task) ?: projectId,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextAction(
                                text = stringResource(R.string.tasks_open_storyboard),
                                onClick = { onOpenProject(projectId, task.request.storyboardShotId) },
                            )
                        }
                    }
                }
            }
            item("info") { TaskInfoCard(task, serverLabel = viewModel.serverLabel(task.request.serverUrl)) }
        }
    }

    if (confirmCloudCancel) {
        AlertDialog(
            onDismissRequest = { confirmCloudCancel = false },
            title = { Text(stringResource(R.string.tasks_cloud_cancel_title)) },
            text = { Text(stringResource(R.string.tasks_cloud_cancel_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCloudCancel = false
                    task?.let { viewModel.cancel(it.taskId) }
                }) { Text(stringResource(R.string.tasks_cancel_anyway), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCloudCancel = false }) { Text(stringResource(R.string.tasks_keep_waiting)) }
            },
        )
    }
    if (confirmLocalInterrupt) {
        AlertDialog(
            onDismissRequest = { confirmLocalInterrupt = false },
            title = { Text(stringResource(R.string.tasks_local_interrupt_title)) },
            text = { Text(stringResource(R.string.tasks_local_interrupt_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLocalInterrupt = false
                    task?.let { viewModel.cancel(it.taskId) }
                }) { Text(stringResource(R.string.tasks_cancel_anyway), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLocalInterrupt = false }) {
                    Text(stringResource(R.string.tasks_keep_waiting))
                }
            },
        )
    }

    if (confirmDelete && task != null && task.isTerminal) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tasks_delete_title)) },
            text = { Text(stringResource(R.string.tasks_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(task.taskId)
                    confirmDelete = false
                    onBack()
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    viewingPath?.let { path ->
        MediaViewerDialog(
            path = path,
            onClose = { viewingPath = null },
            onSave = { viewModel.saveMedia(path) },
            onShare = { shareMedia(context, path) },
        )
    }
}

/** Full-width result: decoded image, or the media thumbnail for video/audio. Tap opens the viewer. */
@Composable
private fun ResultMedia(result: ResultImage, onOpen: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    if (isNonImageMedia(result.localPath)) {
        MediaThumbnail(
            path = result.localPath,
            size = 220.dp,
            modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onOpen),
        )
    } else {
        val bitmap by produceState<Bitmap?>(null, result.localPath) {
            value = withContext(Dispatchers.IO) { decodeSampled(result.localPath, 1200) }
        }
        val loaded = bitmap
        if (loaded == null) {
            Box(
                Modifier.fillMaxWidth().height(180.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        } else {
            Image(
                loaded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onOpen),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

@Composable
private fun StatusBlock(task: GenerationTask) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(task.request.workflowName, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TaskChips(task, recoveredLabel = stringResource(R.string.tasks_recovered_detail))
        }
        RunningNodeProgress(task)
    }
}

@Composable
private fun TaskActions(
    task: GenerationTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onReuse: () -> Unit,
    onRecover: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (task.isActive) {
            IconAction(
                Icons.Outlined.Close,
                stringResource(R.string.action_cancel),
                onClick = onCancel,
                enabled = task.phase != GenerationPhase.CANCELLING,
            )
        } else {
            IconAction(
                Icons.Outlined.Replay,
                stringResource(if (task.phase == GenerationPhase.SUCCEEDED) R.string.tasks_run_again else R.string.tasks_retry),
                onClick = onRetry,
            )
        }
        IconAction(Icons.Outlined.Tune, stringResource(R.string.tasks_reuse), onClick = onReuse)
        if (!task.isActive && !task.promptId.isNullOrBlank()) {
            IconAction(Icons.Outlined.Restore, stringResource(R.string.tasks_recover), onClick = onRecover)
        }
        IconAction(Icons.Outlined.ContentCopy, stringResource(R.string.tasks_copy_diag), onClick = onCopyDiagnostics)
        if (task.isTerminal) {
            IconAction(Icons.Outlined.Delete, stringResource(R.string.tasks_delete_task), onClick = onDelete)
        }
    }
}

/** Technical facts in monospace: ids, server, queue, node, seed, timestamps. */
@Composable
private fun TaskInfoCard(task: GenerationTask, serverLabel: String) {
    IrisCard {
        InfoRow("SERVER", serverLabel)
        InfoRow("TASK", task.taskId)
        task.batchId?.let { InfoRow("BATCH", "${task.batchIndex ?: "?"}/${task.batchSize ?: "?"} · $it") }
        task.promptId?.let { InfoRow("PROMPT", it) }
        task.queuePosition?.let { InfoRow("QUEUE", "#$it") }
        if (task.localPending) InfoRow("QUEUE", "LOCAL")
        task.nodeTitle?.takeIf(String::isNotBlank)?.let { InfoRow("NODE", "${task.nodeId ?: "?"} · $it") }
        task.seedInfo.takeIf(String::isNotBlank)?.let { InfoRow("SEED", it) }
        task.recoveryNote?.takeIf(String::isNotBlank)?.let { InfoRow("RECOVERY", it) }
        InfoRow("CREATED", formatTimeFull(task.createdAt))
        task.startedAt?.let { InfoRow("STARTED", formatTimeFull(it)) }
        task.completedAt?.let { InfoRow("COMPLETED", formatTimeFull(it)) }
        Row {
            InfoLabel("ELAPSED")
            ElapsedText(task)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        InfoLabel(label)
        Text(value, style = IrisType.meta, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfoLabel(label: String) {
    Text(
        label,
        style = IrisType.metaSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(84.dp).padding(top = 1.dp),
    )
}

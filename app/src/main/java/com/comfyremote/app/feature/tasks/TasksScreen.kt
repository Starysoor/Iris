package com.comfyremote.app.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.comfyremote.app.R
import com.comfyremote.app.data.generation.GenerationPhase
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.IrisChip
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.LargeTopBar
import com.comfyremote.app.ui.components.SectionHeader

@Composable
fun TasksScreen(
    viewModel: TasksViewModel,
    onOpenTask: (String) -> Unit,
) {
    val ui by viewModel.uiState.collectAsState()
    val projects by viewModel.projects.collectAsState()
    val message by viewModel.message.collectAsState()
    val tasks = ui.visible
    // Confirmation counts and available bulk actions use the *same* selected
    // server/project scope as the actual mutation, never the global task list.
    val hasFinished = ui.finishedTargets.isNotEmpty()
    val hasQueued = ui.queuedTargets.isNotEmpty()
    val hasFailed = ui.failedTargets.isNotEmpty()
    val queuedTargets = ui.queuedTargets
    val remoteCloudQueued = queuedTargets.count {
        it.isRemoteQueued && it.request.provider != "comfyui"
    }
    var confirmClearFinished by remember { mutableStateOf(false) }
    var confirmCancelQueued by remember { mutableStateOf(false) }
    var serverMenu by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            LargeTopBar(
                title = stringResource(R.string.nav_tasks),
                subtitle = pluralStringResource(
                    R.plurals.tasks_active_count,
                    ui.tasks.count { it.isActive },
                    ui.tasks.count { it.isActive },
                ),
                actions = {
                    Box {
                        IconButton(onClick = { actionMenu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = actionMenu, onDismissRequest = { actionMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (ui.localQueuePaused) R.string.tasks_resume_local else R.string.tasks_pause_local)) },
                                onClick = { viewModel.toggleLocalQueuePaused(); actionMenu = false },
                            )
                            if (hasQueued) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tasks_cancel_queued)) },
                                    onClick = {
                                        actionMenu = false
                                        if (remoteCloudQueued > 0) confirmCancelQueued = true
                                        else viewModel.cancelQueued()
                                    },
                                )
                            }
                            if (hasFailed) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tasks_retry_failed)) },
                                    onClick = { viewModel.retryFailed(); actionMenu = false },
                                )
                            }
                            if (hasFinished) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.tasks_clear_finished)) },
                                    onClick = { confirmClearFinished = true; actionMenu = false },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Spacing.gutter, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(TaskFilter.values().toList(), key = { it.name }) { filter ->
                    IrisChip(
                        label = filterLabel(filter),
                        selected = ui.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        count = ui.copy(filter = filter).visible.size,
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f, fill = false)) {
                    IrisChip(
                        label = ui.serverUrl?.let(viewModel::serverLabel) ?: stringResource(R.string.tasks_all_servers),
                        selected = ui.serverUrl != null,
                        onClick = { serverMenu = true },
                        leadingIcon = Icons.Outlined.Dns,
                    )
                    DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tasks_all_servers)) },
                            onClick = { viewModel.setServerFilter(null); serverMenu = false },
                        )
                        ui.serverUrls.forEach { url ->
                            DropdownMenuItem(
                                text = { Text(viewModel.serverLabel(url)) },
                                onClick = { viewModel.setServerFilter(url); serverMenu = false },
                            )
                        }
                    }
                }
                if (ui.tasks.any { it.request.storyboardProjectId != null }) {
                    Box(Modifier.weight(1f, fill = false)) {
                        IrisChip(
                            label = when (val id = ui.projectFilter) {
                                null -> stringResource(R.string.studio_tasks_all)
                                "" -> stringResource(R.string.studio_tasks_independent)
                                else -> projects.firstOrNull { it.id == id }?.title ?: id
                            },
                            selected = ui.projectFilter != null,
                            onClick = { projectMenu = true },
                        )
                        DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.studio_tasks_all)) },
                                onClick = { viewModel.setProjectFilter(null); projectMenu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.studio_tasks_independent)) },
                                onClick = { viewModel.setProjectFilter(""); projectMenu = false },
                            )
                            ui.tasks.mapNotNull { it.request.storyboardProjectId }.distinct().forEach { id ->
                                DropdownMenuItem(
                                    text = { Text(projects.firstOrNull { it.id == id }?.title ?: id) },
                                    onClick = { viewModel.setProjectFilter(id); projectMenu = false },
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.tasks_local_count, ui.tasks.count { it.localPending }),
                    style = IrisType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(if (ui.tasks.isEmpty()) R.string.tasks_empty else R.string.tasks_empty_filter),
                        detail = stringResource(R.string.tasks_empty_detail),
                        icon = Icons.Outlined.HourglassEmpty,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    tasks.groupBy { it.request.serverUrl }.forEach { (server, serverTasks) ->
                        item("server:$server") {
                            ServerGroupHeader(
                                label = viewModel.serverLabel(server),
                                url = server,
                                active = serverTasks.count { it.isActive },
                                total = serverTasks.size,
                            )
                        }
                        items(serverTasks, key = { it.taskId }) { task ->
                            TaskRow(task = task, onClick = { onOpenTask(task.taskId) })
                        }
                    }
                }
            }
        }
    }

    if (confirmClearFinished) {
        AlertDialog(
            onDismissRequest = { confirmClearFinished = false },
            title = { Text(stringResource(R.string.tasks_clear_title)) },
            text = { Text(stringResource(R.string.tasks_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFinished()
                    confirmClearFinished = false
                }) { Text(stringResource(R.string.tasks_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFinished = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    if (confirmCancelQueued) {
        AlertDialog(
            onDismissRequest = { confirmCancelQueued = false },
            title = { Text(stringResource(R.string.tasks_bulk_cancel_cloud_title)) },
            text = { Text(stringResource(R.string.tasks_bulk_cancel_cloud_text, remoteCloudQueued)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelQueued = false
                    viewModel.cancelQueued()
                }) { Text(stringResource(R.string.tasks_cancel_anyway), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelQueued = false }) {
                    Text(stringResource(R.string.tasks_keep_waiting))
                }
            },
        )
    }
}

@Composable
private fun ServerGroupHeader(label: String, url: String, active: Int, total: Int) {
    Column {
        SectionHeader(label) {
            Text(
                pluralStringResource(R.plurals.tasks_group_meta, active, active, total),
                style = IrisType.metaSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (url.isNotBlank() && url != label) {
            Text(
                url,
                style = IrisType.metaSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

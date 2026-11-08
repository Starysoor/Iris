package com.comfyremote.app.feature.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.R
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.data.project.ProjectMediaLinks
import com.comfyremote.app.data.repository.DraftTransfer
import com.comfyremote.app.feature.generate.MediaViewerDialog
import com.comfyremote.app.feature.generate.shareMedia
import com.comfyremote.app.feature.project.referenceRoleLabel
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.InfoBanner
import com.comfyremote.app.ui.components.BannerTone
import com.comfyremote.app.ui.components.IrisChip
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.LargeTopBar
import com.comfyremote.app.ui.components.TextAction

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onReuse: (DraftTransfer) -> Unit,
    onOpenProject: (String) -> Unit,
) {
    val history by viewModel.items.collectAsState()
    val historyLoadError by viewModel.historyLoadError.collectAsState()
    val query by viewModel.query.collectAsState()
    val favoritesOnly by viewModel.favoritesOnly.collectAsState()
    val message by viewModel.message.collectAsState()
    val storyboardProjects by viewModel.storyboardProjects.collectAsState()
    val linkedResult by viewModel.linkedResult.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewingId by remember { mutableStateOf<String?>(null) }
    var viewingMediaPath by remember { mutableStateOf<String?>(null) }
    var projectPicker by remember { mutableStateOf<Pair<HistoryItem, String>?>(null) }
    var rolePicker by remember { mutableStateOf<Triple<HistoryItem, String, String>?>(null) }
    var actionPicker by remember { mutableStateOf<Triple<HistoryItem, String, String>?>(null) }
    var shotPicker by remember { mutableStateOf<Triple<HistoryItem, String, String>?>(null) }
    val context = LocalContext.current

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() } }
    LaunchedEffect(linkedResult) {
        linkedResult?.let { linked ->
            val action = snackbar.showSnackbar(
                message = linked.description,
                actionLabel = context.getString(R.string.studio_back_to_project),
                withDismissAction = true,
            )
            if (action == SnackbarResult.ActionPerformed) onOpenProject(linked.projectId)
            viewModel.clearLinkedResult()
        }
    }
    BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            if (selecting) {
                LargeTopBar(
                    title = stringResource(R.string.history_selected, selected.size),
                    actions = {
                        TextAction(stringResource(R.string.history_select_all), onClick = { selected = history.map { it.id }.toSet() })
                        IconButton(
                            onClick = { viewModel.deleteMany(selected); selecting = false; selected = emptySet() },
                            enabled = selected.isNotEmpty(),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                stringResource(R.string.action_delete),
                                tint = if (selected.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { selecting = false; selected = emptySet() }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.action_close))
                        }
                    },
                )
            } else {
                LargeTopBar(
                    title = stringResource(R.string.nav_results),
                    subtitle = pluralStringResource(R.plurals.history_count, history.size, history.size),
                    actions = {
                        if (history.isNotEmpty() || query.isNotBlank()) {
                            TextAction(stringResource(R.string.history_select), onClick = { selecting = true })
                        }
                    },
                )
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.xs, bottom = Spacing.xl),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            if (historyLoadError != null) {
                item(key = "storage-error", span = { GridItemSpan(maxLineSpan) }) {
                    InfoBanner(text = stringResource(R.string.history_storage_unreadable), tone = BannerTone.Error)
                }
            }
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                IrisTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.history_search),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    IrisChip(
                        label = stringResource(R.string.history_favorites),
                        selected = favoritesOnly,
                        onClick = viewModel::toggleFavoritesOnly,
                        leadingIcon = if (favoritesOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    )
                }
            }
            if (history.isEmpty() && historyLoadError == null) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        title = stringResource(if (query.isBlank() && !favoritesOnly) R.string.history_empty else R.string.history_no_match),
                        icon = Icons.Outlined.PhotoLibrary,
                    )
                }
            } else {
                items(history, key = { it.id }) { item ->
                    HistoryTile(
                        item = item,
                        selecting = selecting,
                        selected = item.id in selected,
                        onOpen = { viewingId = item.id },
                        onToggle = { selected = if (item.id in selected) selected - item.id else selected + item.id },
                        onStartSelect = { selecting = true; selected = setOf(item.id) },
                        onReuse = { viewModel.prepareReuse(item)?.let(onReuse) },
                        onFavorite = { viewModel.toggleFavorite(item.id) },
                        onDelete = { viewModel.delete(item.id) },
                    )
                }
            }
        }
    }

    val viewing = viewingId?.let { id -> history.firstOrNull { it.id == id } }
    viewing?.let { item ->
        HistoryDetailDialog(
            item = item,
            serverLabel = viewModel.serverLabel(item.serverUrl),
            onClose = { viewingId = null },
            onSave = viewModel::saveMedia,
            onShare = { shareMedia(context, it) },
            onContinue = { path ->
                viewModel.prepareReference(item, path)?.let(onReuse)
                viewingId = null
            },
            onGenerateVideo = { path ->
                viewModel.prepareVideo(item, path)?.let(onReuse)
                viewingId = null
            },
            onOpenMedia = { path -> viewingMediaPath = path },
            onBookmark = { viewModel.bookmark(item) },
            onReuse = { viewModel.prepareReuse(item)?.let(onReuse); viewingId = null },
            onFavorite = { viewModel.toggleFavorite(item.id) },
            onSetTags = { viewModel.setTags(item.id, it) },
            onAddToProject = { path ->
                projectPicker = item to path
                viewingId = null
            },
        )
    }

    projectPicker?.let { (item, path) ->
        AlertDialog(
            onDismissRequest = { projectPicker = null },
            title = { Text(stringResource(R.string.history_choose_project)) },
            text = {
                if (storyboardProjects.isEmpty()) {
                    Text(stringResource(R.string.history_no_projects))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        storyboardProjects.forEach { project ->
                            TextButton(onClick = {
                                actionPicker = Triple(item, path, project.id)
                                projectPicker = null
                            }) { Text(project.title) }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { projectPicker = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    actionPicker?.let { (item, path, projectId) ->
        val kind = ProjectMediaLinks.kind(path)
        AlertDialog(
            onDismissRequest = { actionPicker = null },
            title = { Text(stringResource(R.string.studio_history_pick_action)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        rolePicker = Triple(item, path, projectId)
                        actionPicker = null
                    }) { Text(stringResource(R.string.studio_history_as_asset)) }
                    if (kind == ProjectMediaLinks.Kind.VIDEO) {
                        TextButton(onClick = {
                            shotPicker = Triple(item, path, projectId)
                            actionPicker = null
                        }) { Text(stringResource(R.string.studio_history_as_take)) }
                    }
                    if (ProjectMediaLinks.track(path) != null) {
                        TextButton(onClick = {
                            viewModel.addToProjectTimeline(projectId, item, path)
                            actionPicker = null
                        }) { Text(stringResource(R.string.studio_history_as_timeline)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionPicker = null }) {
                Text(stringResource(R.string.action_cancel))
            } },
        )
    }

    shotPicker?.let { (item, path, projectId) ->
        val project = storyboardProjects.firstOrNull { it.id == projectId }
        AlertDialog(
            onDismissRequest = { shotPicker = null },
            title = { Text(stringResource(R.string.studio_history_pick_shot)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    project?.shots?.forEachIndexed { index, shot ->
                        TextButton(
                            onClick = {
                                viewModel.attachAsTake(projectId, shot.id, item, path)
                                shotPicker = null
                            },
                            enabled = shot.status != "running",
                        ) {
                            Text("${index + 1}. " + shot.prompt.take(55).ifBlank { "#${index + 1}" })
                        }
                    }
                    if (project?.shots.isNullOrEmpty()) {
                        Text(stringResource(R.string.studio_next_add_shots))
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { shotPicker = null }) {
                Text(stringResource(R.string.action_cancel))
            } },
        )
    }

    rolePicker?.let { (item, path, projectId) ->
        AlertDialog(
            onDismissRequest = { rolePicker = null },
            title = { Text(stringResource(R.string.history_choose_role)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReferenceRole.entries.forEach { role ->
                        TextButton(onClick = {
                            viewModel.addToProject(projectId, role, path, item.wfName)
                            rolePicker = null
                        }) { Text(referenceRoleLabel(role)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { rolePicker = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    viewingMediaPath?.let { path ->
        MediaViewerDialog(
            path = path,
            onClose = { viewingMediaPath = null },
            onSave = { viewModel.saveMedia(path) },
            onShare = { shareMedia(context, path) },
        )
    }
}

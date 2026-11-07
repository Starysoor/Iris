package com.comfyremote.app.feature.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.comfyremote.app.data.project.VideoProject
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisTonalButton
import com.comfyremote.app.ui.components.LargeTopBar
import com.comfyremote.app.ui.components.ListRow
import com.comfyremote.app.ui.components.StatusChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ProjectThumbSize = 56.dp

@Composable
fun ProjectListScreen(
    viewModel: ProjectViewModel,
    onOpenProject: (String) -> Unit,
) {
    val projects by viewModel.projects.collectAsState()
    val workflows by viewModel.workflows.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            LargeTopBar(
                title = stringResource(R.string.proj_title),
                subtitle = stringResource(R.string.proj_subtitle, projects.size),
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.proj_new))
                    }
                },
            )
        },
    ) { pad ->
        if (projects.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = stringResource(R.string.proj_empty_title),
                    detail = stringResource(R.string.proj_empty_detail),
                    icon = Icons.Outlined.Movie,
                    action = {
                        IrisTonalButton(
                            text = stringResource(R.string.proj_new),
                            onClick = { creating = true },
                            icon = Icons.Outlined.Add,
                        )
                    },
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        workflowName = workflows.firstOrNull {
                            it.path == project.workflowPath || it.workflowId == project.workflowId
                        }?.let { "${it.providerLabel} · ${it.name}" }
                            ?: project.workflowPath.ifBlank { project.workflowId },
                        onClick = { onOpenProject(project.id) },
                    )
                }
            }
        }
    }

    if (creating) {
        CreateProjectDialog(
            workflows = workflows,
            onDismiss = { creating = false },
            onCreate = { title, workflowPath, style, synopsis, chainFrames ->
                val project = viewModel.createProject(title, workflowPath, style, synopsis, chainFrames)
                creating = false
                project?.let { onOpenProject(it.id) }
            },
        )
    }
}

@Composable
private fun ProjectRow(project: VideoProject, workflowName: String, onClick: () -> Unit) {
    val done = project.shots.count { it.status == "done" }
    val total = project.shots.size
    val tone = when {
        project.shots.any { it.status == "running" } -> ChipTone.Accent
        project.shots.any { it.status == "failed" } -> ChipTone.Error
        total > 0 && done == total -> ChipTone.Success
        else -> ChipTone.Neutral
    }
    val cover = project.shots.lastOrNull { it.resultPath != null }?.resultPath
        ?: project.shots.lastOrNull { it.lastFramePath != null }?.lastFramePath
        ?: project.assets.firstOrNull { it.kind == com.comfyremote.app.data.reference.AssetKind.IMAGE }?.path
    ListRow(
        title = project.title,
        subtitle = workflowName.ifBlank { stringResource(R.string.proj_no_default_workflow) },
        onClick = onClick,
        leading = { ProjectCover(cover) },
        meta = {
            StatusChip("$done/$total", tone = tone)
            Text(
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(project.updatedAt)),
                style = IrisType.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun ProjectCover(path: String?) {
    if (path != null) {
        MediaThumbnail(path, ProjectThumbSize, Modifier.clip(MaterialTheme.shapes.medium))
    } else {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(ProjectThumbSize),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

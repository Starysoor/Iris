package com.comfyremote.app.feature.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.data.project.AssetProfile
import com.comfyremote.app.data.project.ProfileKind
import com.comfyremote.app.data.project.VideoProject
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.ChipTone
import com.comfyremote.app.ui.components.DetailTopBar
import com.comfyremote.app.ui.components.EmptyState
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisScaffold
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.SegmentedControl
import com.comfyremote.app.ui.components.StatusChip
import com.comfyremote.app.ui.components.TextAction

/**
 * Project asset library: every media kind, tags, and the consistency profiles
 * that bundle assets into one reusable character or scene identity.
 */
@Composable
fun AssetLibraryScreen(
    viewModel: ProjectViewModel,
    projectId: String,
    onBack: () -> Unit,
) {
    val projects by viewModel.projects.collectAsState()
    val message by viewModel.message.collectAsState()
    val importProgress by viewModel.assetImport.collectAsState()
    val project = projects.firstOrNull { it.id == projectId }
    val snackbar = remember { SnackbarHostState() }
    var kindFilter by remember { mutableStateOf(0) }
    var assetRoleDialog by remember { mutableStateOf(false) }
    var pendingAssetRole by remember { mutableStateOf<ReferenceRole?>(null) }
    var editingAsset by remember { mutableStateOf<ProjectAsset?>(null) }
    var editingProfile by remember { mutableStateOf<AssetProfile?>(null) }
    var creatingProfile by remember { mutableStateOf(false) }
    var applyingProfile by remember { mutableStateOf<AssetProfile?>(null) }
    val assetPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val role = pendingAssetRole
        if (uri != null && role != null) viewModel.importAsset(projectId, role, uri)
        pendingAssetRole = null
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }

    IrisScaffold(
        snackbarHostState = snackbar,
        topBar = {
            DetailTopBar(
                title = stringResource(R.string.proj_assets_title),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { assetRoleDialog = true }, enabled = !importProgress.running) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.board_add_asset))
                    }
                },
            )
        },
    ) { pad ->
        if (project == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.proj_not_found), icon = Icons.Outlined.Image)
            }
            return@IrisScaffold
        }
        val kindOptions = listOf(
            stringResource(R.string.proj_asset_kind_all),
            stringResource(R.string.proj_asset_kind_image),
            stringResource(R.string.proj_asset_kind_video),
            stringResource(R.string.proj_asset_kind_audio),
        )
        val selectedKind = when (kindFilter) {
            1 -> AssetKind.IMAGE
            2 -> AssetKind.VIDEO
            3 -> AssetKind.AUDIO
            else -> null
        }
        val assets = project.assets
            .filter { selectedKind == null || it.kind == selectedKind }
            .sortedBy { it.order }
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (importProgress.running) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    val receivedMiB = importProgress.copiedBytes / (1024 * 1024)
                    val totalMiB = importProgress.totalBytes?.let { (it + 1024 * 1024 - 1) / (1024 * 1024) }
                    Text(
                        if (totalMiB != null) stringResource(R.string.proj_asset_import_progress, receivedMiB, totalMiB)
                        else stringResource(R.string.proj_asset_import_progress_unknown, receivedMiB),
                        style = IrisType.metaSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextAction(stringResource(R.string.action_cancel), onClick = { viewModel.cancelAssetImport() })
                }
            }
            ProfilesSection(
                project = project,
                onCreate = { creatingProfile = true },
                onEdit = { editingProfile = it },
                onApply = { applyingProfile = it },
                onDelete = { viewModel.deleteProfile(projectId, it.id) },
            )
            SegmentedControl(
                options = kindOptions,
                selectedIndex = kindFilter,
                onSelect = { kindFilter = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.sm),
            )
            if (assets.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = stringResource(R.string.board_assets_empty),
                        detail = stringResource(R.string.proj_assets_empty_hint),
                        icon = Icons.Outlined.Image,
                        action = {
                            TextAction(stringResource(R.string.board_add_asset), onClick = { assetRoleDialog = true })
                        },
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    items(assets, key = { it.id }) { asset ->
                        AssetCard(
                            asset = asset,
                            label = assetDisplayLabel(project, asset),
                            onClick = { editingAsset = asset },
                            onDelete = { viewModel.removeAsset(projectId, asset.id) },
                        )
                    }
                }
            }
        }
    }

    if (assetRoleDialog) {
        AssetRoleDialog(
            onDismiss = { assetRoleDialog = false },
            onRole = { role ->
                pendingAssetRole = role
                assetRoleDialog = false
                assetPicker.launch("*/*")
            },
        )
    }
    editingAsset?.let { asset ->
        AssetEditDialog(
            asset = asset,
            onDismiss = { editingAsset = null },
            onSave = { label, tags ->
                viewModel.updateAsset(projectId, asset.copy(label = label, tags = tags))
                editingAsset = null
            },
            onDelete = {
                viewModel.removeAsset(projectId, asset.id)
                editingAsset = null
            },
        )
    }
    if (creatingProfile && project != null) {
        ProfileEditDialog(
            profile = null,
            project = project,
            onDismiss = { creatingProfile = false },
            onSave = { name, summary, kind, assetIds ->
                viewModel.createProfile(projectId, name, summary, kind, assetIds)
                creatingProfile = false
            },
        )
    }
    editingProfile?.let { profile ->
        if (project == null) return
        ProfileEditDialog(
            profile = profile,
            project = project,
            onDismiss = { editingProfile = null },
            onSave = { name, summary, kind, assetIds ->
                viewModel.updateProfile(
                    projectId,
                    profile.copy(name = name, summary = summary, kind = kind, assetIds = assetIds),
                )
                editingProfile = null
            },
            onDelete = {
                viewModel.deleteProfile(projectId, profile.id)
                editingProfile = null
            },
        )
    }
    applyingProfile?.let { profile ->
        if (project == null) return
        ShotPickerDialog(
            project = project,
            onDismiss = { applyingProfile = null },
            onPick = { shotId ->
                viewModel.applyProfileToShot(projectId, shotId, profile.id)
                applyingProfile = null
            },
        )
    }
}

@Composable
private fun ProfilesSection(
    project: VideoProject,
    onCreate: () -> Unit,
    onEdit: (AssetProfile) -> Unit,
    onApply: (AssetProfile) -> Unit,
    onDelete: (AssetProfile) -> Unit,
) {
    Column(Modifier.padding(horizontal = Spacing.gutter)) {
        com.comfyremote.app.ui.components.SectionHeader(stringResource(R.string.proj_profiles)) {
            TextAction(stringResource(R.string.proj_profile_new), onClick = onCreate)
        }
        project.profiles.forEach { profile ->
            var menu by remember { mutableStateOf(false) }
            IrisCard(Modifier.fillMaxWidth().padding(bottom = Spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Text(
                                profile.name.ifBlank { stringResource(R.string.proj_profile_unnamed) },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            StatusChip(
                                when (profile.kind) {
                                    ProfileKind.CHARACTER -> stringResource(R.string.proj_profile_character)
                                    ProfileKind.SCENE -> stringResource(R.string.proj_profile_scene)
                                },
                                tone = ChipTone.Neutral,
                            )
                            StatusChip("${profile.assetIds.size}", tone = ChipTone.Neutral)
                        }
                        if (profile.summary.isNotBlank()) {
                            Text(
                                profile.summary,
                                style = IrisType.metaSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Outlined.Edit, stringResource(R.string.action_more))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.proj_profile_edit)) },
                                onClick = { menu = false; onEdit(profile) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.proj_profile_apply)) },
                                enabled = profile.assetIds.isNotEmpty(),
                                onClick = { menu = false; onApply(profile) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; onDelete(profile) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Grid card with tap-to-edit; keeps the delete shortcut from the home strip. */
@Composable
private fun AssetCard(
    asset: ProjectAsset,
    label: String,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        modifier = Modifier.clip(MaterialTheme.shapes.medium).clickable(onClick = onClick),
    ) {
        Box {
            MediaThumbnail(asset.path, 96.dp, Modifier.clip(MaterialTheme.shapes.medium))
            IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(
                    Icons.Outlined.Delete,
                    stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.height(18.dp).padding(2.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (asset.tags.isNotEmpty()) {
            Text(
                asset.tags.joinToString(" · "),
                style = IrisType.metaSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssetEditDialog(
    asset: ProjectAsset,
    onDismiss: () -> Unit,
    onSave: (label: String, tags: List<String>) -> Unit,
    onDelete: () -> Unit,
) {
    var label by remember(asset.id) { mutableStateOf(asset.label) }
    var tagsText by remember(asset.id) {
        mutableStateOf(asset.tags.joinToString(", "))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_asset_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MediaThumbnail(asset.path, 120.dp, Modifier.clip(MaterialTheme.shapes.medium).fillMaxWidth())
                IrisTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = stringResource(R.string.proj_asset_label),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = tagsText,
                    onValueChange = { tagsText = it },
                    label = stringResource(R.string.proj_asset_tags),
                    supportingText = stringResource(R.string.proj_asset_tags_hint),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(label.trim(), parseTags(tagsText))
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

/** Create (profile == null) or edit (rename/re-summarize/re-select assets) a profile. */
@Composable
private fun ProfileEditDialog(
    profile: AssetProfile?,
    project: VideoProject,
    onDismiss: () -> Unit,
    onSave: (name: String, summary: String, kind: ProfileKind, assetIds: List<String>) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var summary by remember(profile?.id) { mutableStateOf(profile?.summary.orEmpty()) }
    var kindIndex by remember(profile?.id) { mutableStateOf(if (profile?.kind == ProfileKind.SCENE) 1 else 0) }
    var selected by remember(profile?.id) { mutableStateOf(profile?.assetIds?.toSet().orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (profile == null) {
                    stringResource(R.string.proj_profile_new)
                } else {
                    stringResource(R.string.proj_profile_edit)
                },
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                IrisTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.proj_profile_name),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                IrisTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = stringResource(R.string.proj_profile_summary),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                SegmentedControl(
                    options = listOf(
                        stringResource(R.string.proj_profile_character),
                        stringResource(R.string.proj_profile_scene),
                    ),
                    selectedIndex = kindIndex,
                    onSelect = { kindIndex = it },
                )
                Text(
                    stringResource(R.string.proj_profile_assets_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (project.assets.isEmpty()) {
                    Text(
                        stringResource(R.string.board_assets_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                project.assets.forEach { asset ->
                    val checked = asset.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (checked) selected - asset.id else selected + asset.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(
                            "${referenceRoleLabel(asset.role)} · " +
                                assetDisplayLabel(project, asset).ifBlank { asset.path.substringAfterLast('/') },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name.trim(),
                        summary.trim(),
                        if (kindIndex == 1) ProfileKind.SCENE else ProfileKind.CHARACTER,
                        project.assets.filter { it.id in selected }.map { it.id },
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.board_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Single-select shot list used when applying a profile to one shot. */
@Composable
private fun ShotPickerDialog(
    project: VideoProject,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_profile_apply)) },
        text = {
            if (project.shots.isEmpty()) {
                Text(stringResource(R.string.board_no_shots))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    project.shots.forEachIndexed { index, shot ->
                        TextButton(onClick = { onPick(shot.id) }) {
                            Text(
                                "${index + 1}. " + shot.prompt.ifBlank {
                                    stringResource(R.string.board_empty_shot)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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

internal fun parseTags(raw: String): List<String> =
    raw.split(',', '，', ';', '；', '#').map(String::trim).filter(String::isNotBlank).distinct()

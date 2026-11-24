package com.comfyremote.app.feature.generate

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.comfyremote.app.R
import com.comfyremote.app.FieldKind
import com.comfyremote.app.PromptField
import com.comfyremote.app.ui.LocalAppSettings
import com.comfyremote.app.ui.Sizes
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisTonalButton

/**
 * One reference-image input: image slot + camera tile, upload error, and the
 * "AI vision rewrite" action once an image is set.
 */
@Composable
internal fun ReferenceImageField(
    label: String,
    value: String,
    upload: ImageUploadStatus?,
    visionEnabled: Boolean,
    visionBusy: Boolean,
    loadBitmap: suspend (String) -> Bitmap?,
    onPick: () -> Unit,
    onCamera: () -> Unit,
    onClear: () -> Unit,
    onVision: () -> Unit,
) {
    val referenceName = value
    val preview by produceState<Pair<Boolean, Bitmap?>>(false to null, referenceName) {
        this.value = false to null
        if (referenceName.isNotBlank()) this.value = true to loadBitmap(referenceName)
    }
    val previewUnavailable = value.isNotBlank() && preview.first && preview.second == null
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ImageSlotTile(
                name = value,
                uploading = upload?.phase == ImageUploadPhase.UPLOADING,
                bitmap = preview.second,
                unavailable = previewUnavailable,
                onPick = onPick,
                onClear = onClear,
            )
            ActionTile(Icons.Outlined.PhotoCamera, stringResource(R.string.gen_camera), onCamera)
        }
        if (upload?.phase == ImageUploadPhase.FAILED) {
            Text(
                upload.message ?: stringResource(R.string.gen_upload_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        } else if (previewUnavailable) {
            Text(
                stringResource(R.string.gen_image_preview_unavailable, audioDisplayName(value.substringBefore('?')).take(80)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (value.isNotBlank()) {
            TextButton(
                onClick = onVision,
                enabled = visionEnabled,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(if (visionBusy) R.string.gen_vision_busy else R.string.gen_vision_rewrite),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun Modifier.tileModifier(onClick: () -> Unit): Modifier {
    val shape = MaterialTheme.shapes.medium
    return this
        .size(Sizes.mediaSlot)
        .clip(shape)
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(role = Role.Button, onClick = onClick)
}

@Composable
private fun ImageSlotTile(
    name: String,
    uploading: Boolean,
    bitmap: Bitmap?,
    unavailable: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Box(Modifier.tileModifier(onPick), contentAlignment = Alignment.Center) {
        if (name.isNotBlank()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else if (unavailable) {
                    TileLabel(Icons.Outlined.BrokenImage, stringResource(R.string.gen_repick_image))
                } else {
                    Icon(Icons.Outlined.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.scrim)
                    .clickable(role = Role.Button, onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                // White reads on the scrim in both themes.
                Icon(Icons.Outlined.Close, stringResource(R.string.action_clear), tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            TileLabel(Icons.Outlined.AddPhotoAlternate, stringResource(R.string.gen_add))
        }
        if (uploading) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp) }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, caption: String, onClick: () -> Unit) {
    Box(Modifier.tileModifier(onClick), contentAlignment = Alignment.Center) { TileLabel(icon, caption) }
}

@Composable
private fun TileLabel(icon: ImageVector, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One audio input: current file (tap to preview), clear, and the picker. */
@Composable
internal fun TimedMediaReferenceField(
    field: PromptField,
    value: String,
    cloud: Boolean,
    formats: List<String>,
    upload: ImageUploadStatus?,
    running: Boolean,
    onPreview: (String) -> Unit,
    onChange: (String) -> Unit,
    onPick: () -> Unit,
) {
    val compact = LocalAppSettings.current.compactUi
    val video = field.kind == FieldKind.VIDEO
    val uploading = upload?.phase == ImageUploadPhase.UPLOADING
    var showUrl by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var showServerFiles by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (formats.isNotEmpty()) {
            Text(formats.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        IrisCard(contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (value.isNotBlank()) {
                    val path = value.removePrefix("local:")
                    if (value.startsWith("local:")) {
                        MediaThumbnail(
                            path = path,
                            size = Sizes.thumbSm,
                            modifier = Modifier.clip(MaterialTheme.shapes.small).clickable { onPreview(path) },
                        )
                    }
                    Text(
                        if (value.startsWith("data:")) stringResource(R.string.gen_inline_media)
                        else audioDisplayName(value.substringBefore('?')),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onChange("") }, enabled = !running && !uploading) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.action_clear), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(
                        stringResource(if (video) R.string.gen_no_video else R.string.gen_no_audio),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                IrisTonalButton(
                    text = stringResource(if (video) R.string.gen_pick_video else R.string.gen_pick_audio),
                    onClick = onPick,
                    enabled = !running && !uploading,
                    icon = if (video) Icons.Outlined.VideoFile else Icons.Outlined.AudioFile,
                )
            }
            if (uploading) Text(stringResource(R.string.gen_media_uploading), style = MaterialTheme.typography.bodySmall)
            if (cloud) {
                TextButton(enabled = !running && !uploading, onClick = {
                    url = value.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
                    showUrl = true
                }) { Text(stringResource(R.string.gen_media_url)) }
            } else if (field.options.isNotEmpty()) {
                Box {
                    TextButton(
                        enabled = !running && !uploading,
                        onClick = { showServerFiles = true },
                    ) { Text(stringResource(R.string.gen_server_media)) }
                    DropdownMenu(expanded = showServerFiles, onDismissRequest = { showServerFiles = false }) {
                        field.options.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                onChange(option)
                                showServerFiles = false
                            })
                        }
                    }
                }
            }
        }
        if (upload?.phase == ImageUploadPhase.FAILED) {
            Text(
                upload.message ?: stringResource(R.string.gen_media_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (showUrl) {
        AlertDialog(
            onDismissRequest = { showUrl = false },
            title = { Text(stringResource(R.string.gen_media_url)) },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(enabled = url.isNotBlank(), onClick = {
                    onChange(url.trim())
                    showUrl = false
                }) { Text(stringResource(R.string.board_save)) }
            },
            dismissButton = { TextButton(onClick = { showUrl = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

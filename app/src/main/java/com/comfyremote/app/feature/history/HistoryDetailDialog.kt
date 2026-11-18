package com.comfyremote.app.feature.history

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.data.media.GallerySaveFeedback
import com.comfyremote.app.data.media.GallerySavePhase
import com.comfyremote.app.R
import com.comfyremote.app.data.media.decodeSampled
import com.comfyremote.app.feature.generate.MediaThumbnail
import com.comfyremote.app.feature.generate.isNonImageMedia
import com.comfyremote.app.ui.IrisType
import com.comfyremote.app.ui.Spacing
import com.comfyremote.app.ui.components.IconAction
import com.comfyremote.app.ui.components.IrisCard
import com.comfyremote.app.ui.components.IrisTextField
import com.comfyremote.app.ui.components.IrisTonalButton
import com.comfyremote.app.ui.components.TextAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full-screen result detail: every image with its actions, then prompts, seed, tags and reuse. */
@Composable
fun HistoryDetailDialog(
    item: HistoryItem,
    serverLabel: String,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
    saveFeedback: GallerySaveFeedback? = null,
    onShare: (String) -> Unit,
    onContinue: (String) -> Unit,
    onGenerateVideo: (String) -> Unit,
    onOpenMedia: (String) -> Unit,
    onBookmark: () -> Unit,
    onReuse: () -> Unit,
    onFavorite: () -> Unit,
    onSetTags: (String) -> Unit,
    onAddToProject: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedText = stringResource(R.string.history_copied)
    var tagsText by remember(item.id, item.tags) { mutableStateOf(item.tags.joinToString(", ")) }
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Row(
                Modifier.fillMaxWidth().padding(start = Spacing.gutter, end = 4.dp, top = Spacing.sm, bottom = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.wfName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onFavorite) {
                    Icon(
                        if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(if (item.favorite) R.string.history_unfavorite else R.string.history_favorite),
                        tint = if (item.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.action_close)) }
            }
            val visibleSave = saveFeedback?.takeIf { it.path in item.images }
            visibleSave?.let { feedback ->
                val label = when (feedback.phase) {
                    GallerySavePhase.SAVING -> stringResource(
                        R.string.gallery_saving_progress,
                        ((feedback.progress ?: 0f) * 100f).toInt().coerceIn(0, 100),
                    )
                    GallerySavePhase.SUCCEEDED -> stringResource(R.string.gallery_saved)
                    GallerySavePhase.FAILED -> stringResource(
                        R.string.gallery_save_failed, feedback.error.orEmpty().take(160))
                }
                Text(
                    label,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = Spacing.sm, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                items(item.images, key = { it }) { path ->
                    val nonImage = isNonImageMedia(path)
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        DetailMedia(path, nonImage) { onOpenMedia(path) }
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            IconAction(Icons.Outlined.SaveAlt, stringResource(R.string.action_save), onClick = { onSave(path) })
                            IconAction(Icons.Outlined.Share, stringResource(R.string.action_share), onClick = { onShare(path) })
                            if (!nonImage) {
                                IconAction(Icons.Outlined.AutoAwesome, stringResource(R.string.history_continue_ref), onClick = { onContinue(path) })
                                IconAction(Icons.Outlined.Movie, stringResource(R.string.history_make_video), onClick = { onGenerateVideo(path) })
                                IconAction(Icons.Outlined.PhotoLibrary, stringResource(R.string.history_add_to_project), onClick = { onAddToProject(path) })
                            }
                        }
                    }
                }
                item("metadata") {
                    IrisCard {
                        if (serverLabel.isNotBlank()) {
                            Text("SERVER · $serverLabel", style = IrisType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.history_prompts), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (item.prompts.isNotEmpty()) {
                                TextAction(stringResource(R.string.history_copy_all), onClick = { copy(item.prompts.joinToString("\n") { it.value }) })
                            }
                        }
                        item.prompts.forEach { prompt ->
                            Text(prompt.label, style = IrisType.overline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(prompt.value, style = MaterialTheme.typography.bodySmall)
                        }
                        item.seeds.takeIf(String::isNotBlank)?.let { seeds ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("SEED · $seeds", style = IrisType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                TextAction(stringResource(R.string.history_copy_seed), onClick = { copy(seeds) })
                            }
                        }
                        IrisTextField(
                            value = tagsText,
                            onValueChange = { tagsText = it },
                            label = stringResource(R.string.history_tags_hint),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IrisTonalButton(stringResource(R.string.history_reuse_params), onClick = onReuse, icon = Icons.Outlined.Tune)
                            TextAction(stringResource(R.string.history_save_prompt), onClick = onBookmark)
                            TextAction(stringResource(R.string.history_save_tags), onClick = { onSetTags(tagsText) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMedia(path: String, nonImage: Boolean, onOpen: () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    if (nonImage) {
        MediaThumbnail(path, 220.dp, Modifier.fillMaxWidth().clip(shape).clickable(onClick = onOpen))
    } else {
        val bitmap by produceState<Bitmap?>(null, path) {
            value = withContext(Dispatchers.IO) { decodeSampled(path, 1600) }
        }
        val loaded = bitmap
        if (loaded != null) {
            Image(
                loaded.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClick = onOpen),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(Modifier.fillMaxWidth().height(220.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        }
    }
}

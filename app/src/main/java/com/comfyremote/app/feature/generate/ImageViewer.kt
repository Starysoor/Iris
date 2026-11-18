package com.comfyremote.app.feature.generate

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.comfyremote.app.R
import com.comfyremote.app.data.media.MediaTypeResolver
import com.comfyremote.app.data.media.GallerySaveFeedback
import com.comfyremote.app.data.media.GallerySavePhase
import com.comfyremote.app.data.media.decodeSampled
import com.comfyremote.app.ui.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun stripMarkdown(markdown: String): String = markdown
    .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
    .replace("**", "")
    .replace(Regex("`(.*?)`"), "$1")
    .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
    .trim()

/*
 * Full-screen viewers sit on pure black regardless of theme: imagery reads
 * truest there, and controls are white glyphs on a translucent disc.
 */
private val ViewerBackdrop = Color.Black
private val ViewerForeground = Color.White

@Composable
private fun ViewerButton(icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick = onClick, enabled = enabled) {
        Surface(shape = CircleShape, color = ViewerBackdrop.copy(alpha = 0.45f)) {
            Icon(icon, description, tint = ViewerForeground, modifier = Modifier.padding(8.dp).size(20.dp))
        }
    }
}

@Composable
private fun ViewerActions(
    onClose: () -> Unit,
    onSave: (() -> Unit)?,
    onShare: (() -> Unit)?,
    modifier: Modifier = Modifier,
    saving: Boolean = false,
) {
    Row(modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        onShare?.let { ViewerButton(Icons.Outlined.Share, stringResource(R.string.action_share), it) }
        onSave?.let { ViewerButton(Icons.Outlined.SaveAlt, stringResource(R.string.action_save), it, enabled = !saving) }
        ViewerButton(Icons.Outlined.Close, stringResource(R.string.action_close), onClose)
    }
}

/** Render inside the Dialog window; screen-level Snackbars sit underneath it. */
@Composable
private fun ViewerSaveFeedback(feedback: GallerySaveFeedback?, modifier: Modifier = Modifier) {
    if (feedback == null) return
    Surface(
        modifier = modifier.padding(horizontal = 20.dp),
        shape = MaterialTheme.shapes.medium,
        color = ViewerBackdrop.copy(alpha = 0.84f),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val label = when (feedback.phase) {
                GallerySavePhase.SAVING -> stringResource(
                    R.string.gallery_saving_progress,
                    ((feedback.progress ?: 0f) * 100f).toInt().coerceIn(0, 100),
                )
                GallerySavePhase.SUCCEEDED -> stringResource(R.string.gallery_saved)
                GallerySavePhase.FAILED -> stringResource(
                    R.string.gallery_save_failed,
                    feedback.error.orEmpty().take(160),
                )
            }
            Text(label, color = ViewerForeground, style = MaterialTheme.typography.bodyMedium)
            if (feedback.isSaving) {
                LinearProgressIndicator(
                    progress = { (feedback.progress ?: 0f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun ImageViewerDialog(
    bitmap: Bitmap,
    onClose: () -> Unit,
    onSave: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    saveFeedback: GallerySaveFeedback? = null,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(ViewerBackdrop)) {
            var scale by remember { mutableStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            Image(
                bitmap.asImageBitmap(), null,
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (scale > 1f) offset + pan else Offset.Zero
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero }) }
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
            ViewerActions(onClose, onSave, onShare, Modifier.align(Alignment.TopEnd), saving = feedback?.isSaving == true)
            ViewerSaveFeedback(feedback, Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp))
            Text(
                stringResource(R.string.gen_viewer_hint),
                color = ViewerForeground.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

fun shareImage(context: Context, path: String) {
    shareMedia(context, path)
}

fun shareMedia(context: Context, path: String) {
    val file = File(path)
    if (!file.isFile) return
    runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mediaMimeType(path)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, AppStrings.get(R.string.action_share)))
    }
}

fun isVideoPath(path: String): Boolean = MediaTypeResolver.isVideo(path)

fun isAudioPath(path: String): Boolean = MediaTypeResolver.isAudio(path)

fun isNonImageMedia(path: String): Boolean = isVideoPath(path) || isAudioPath(path)

fun mediaMimeType(path: String): String = MediaTypeResolver.mimeType(path)

/**
 * Square media thumbnail for images, videos (first frame + play badge) and
 * audio (waveform glyph). Rounded to `shapes.small`; callers may clip further.
 */
@Composable
fun MediaThumbnail(
    path: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
    if (isAudioPath(path)) {
        Box(modifier.size(size).clip(shape).background(placeholder), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Outlined.GraphicEq,
                stringResource(R.string.gen_audio),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(minOf(size / 2, 48.dp)),
            )
        }
    } else if (isVideoPath(path)) {
        val frame by produceState<Bitmap?>(null, path) {
            value = withContext(Dispatchers.IO) { videoFrame(path) }
        }
        Box(modifier.size(size).clip(shape).background(placeholder), contentAlignment = Alignment.Center) {
            frame?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim) {
                Icon(Icons.Filled.PlayArrow, null, tint = ViewerForeground, modifier = Modifier.padding(4.dp).size(22.dp))
            }
        }
    } else {
        val bitmap by produceState<Bitmap?>(null, path) {
            value = withContext(Dispatchers.IO) { decodeSampled(path, (size.value * 3).toInt().coerceAtLeast(96)) }
        }
        val loaded = bitmap
        if (loaded != null) {
            Image(loaded.asImageBitmap(), null, modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
        } else {
            Box(modifier.size(size).clip(shape).background(placeholder))
        }
    }
}

/** Full-screen viewer that handles images and videos behind one entry point. */
@Composable
fun MediaViewerDialog(
    path: String,
    onClose: () -> Unit,
    onSave: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    saveFeedback: GallerySaveFeedback? = null,
) {
    // Never show a previous save's status for a different opened result.
    val feedback = saveFeedback?.takeIf { it.path == path }
    if (!isNonImageMedia(path)) {
        val bitmap by produceState<Bitmap?>(null, path) {
            value = withContext(Dispatchers.IO) { decodeSampled(path, 1800) }
        }
        bitmap?.let {
            ImageViewerDialog(bitmap = it, onClose = onClose, onSave = onSave, onShare = onShare, saveFeedback = feedback)
        }
        return
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(ViewerBackdrop)) {
            AndroidView(
                factory = { context ->
                    VideoView(context).apply {
                        setVideoPath(path)
                        val controller = MediaController(context)
                        controller.setMediaPlayer(this)
                        setMediaController(controller)
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            start()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            ViewerActions(onClose, onSave, onShare, Modifier.align(Alignment.TopEnd))
        }
    }
}

private fun videoFrame(path: String): Bitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(path)
        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } finally {
        retriever.release()
    }
}.getOrNull()

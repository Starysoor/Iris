package com.comfyremote.app.feature.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.comfyremote.app.R
import com.comfyremote.app.data.project.ProjectBackupSnapshot
import com.comfyremote.app.ui.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recovery is always explicitly confirmed; invalid snapshot files never display. */
@Composable
internal fun ProjectBackupRestoreDialog(
    backups: List<ProjectBackupSnapshot>,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
) {
    var selected by remember(backups) { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.proj_backup_restore)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    stringResource(R.string.proj_backup_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                if (backups.isEmpty()) {
                    Text(stringResource(R.string.proj_backup_empty))
                }
                backups.forEach { snapshot ->
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(Date(snapshot.timestampMs))
                    TextButton(onClick = { selected = snapshot.filename }) {
                        Text(
                            (if (selected == snapshot.filename) "✓ " else "") +
                                "$date · " +
                                stringResource(R.string.proj_backup_count, snapshot.projectCount),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { selected?.let(onRestore) },
            ) { Text(stringResource(R.string.proj_backup_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

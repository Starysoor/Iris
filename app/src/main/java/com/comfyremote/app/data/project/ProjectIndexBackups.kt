package com.comfyremote.app.data.project

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** App-private, rotating snapshots of the complete Studio index, never media deletion. */
data class ProjectBackupSnapshot(val filename: String, val timestampMs: Long, val projectCount: Int)

internal class ProjectIndexBackups(
    private val index: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val parent: File get() = index.parentFile ?: throw IOException("Missing index parent")
    private val prefix: String get() = index.nameWithoutExtension + ".backup-"
    private val pattern: Regex get() = Regex("^" + Regex.escape(prefix) + "([0-9]{13})-([0-9]{3})\\.json$")
    private val maxSnapshots = 3

    private fun validated(raw: String): List<VideoProject> {
        val rows = ProjectCodec.rawProjectCount(raw)
            ?: throw IOException("Backup contains invalid project index JSON")
        val decoded = ProjectCodec.decode(raw)
        if (decoded.size != rows) throw IOException("Backup has unreadable project rows")
        return decoded
    }

    private fun candidates(): List<File> = parent.listFiles().orEmpty()
        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && pattern.matches(it.name) }
        .sortedByDescending { it.name }

    fun list(): List<ProjectBackupSnapshot> = candidates().mapNotNull { candidate ->
        runCatching {
            val count = validated(candidate.readText()).size
            val timestamp = pattern.matchEntire(candidate.name)!!.groupValues[1].toLong()
            ProjectBackupSnapshot(candidate.name, timestamp, count)
        }.getOrNull()
    }

    /** Fails the mutation when an existing valid index cannot be snapshotted. */
    fun snapshot(raw: String) {
        validated(raw)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create backup directory")
        val whenMs = clock()
        val destination = (0..999).asSequence()
            .map { File(parent, prefix + whenMs.toString().padStart(13, '0') + "-" +
                it.toString().padStart(3, '0') + ".json") }
            .firstOrNull { !it.exists() }
            ?: throw IOException("Project backup filename collision")
        writeAtomically(destination, raw.toByteArray(Charsets.UTF_8), overwrite = false)
        candidates().drop(maxSnapshots).forEach { stale ->
            // A failed deletion leaves extra recovery copies, never a failed index write.
            runCatching { stale.delete() }
        }
    }

    /**
     * Restore only a validated snapshot from the app-owned directory. Save even
     * a corrupt current index under a separate "before-restore" name first so
     * manual recovery can inspect it. The live index is replaced atomically.
     */
    fun restore(filename: String): List<VideoProject> {
        val source = candidates().firstOrNull { it.name == filename }
            ?: throw IOException("No matching project backup")
        val raw = source.readText()
        val decoded = validated(raw)
        if (index.exists()) {
            if (!index.isFile) throw IOException("Current index is not a file")
            val safeguard = (0..999).asSequence().map {
                File(parent, index.nameWithoutExtension + ".before-restore-" +
                    clock() + "-" + it.toString().padStart(3, '0') + ".json")
            }.firstOrNull { !it.exists() } ?: throw IOException("Cannot preserve current index")
            writeAtomically(safeguard, index.readBytes(), overwrite = false)
        }
        writeAtomically(index, raw.toByteArray(Charsets.UTF_8), overwrite = true)
        return decoded
    }

    private fun writeAtomically(destination: File, bytes: ByteArray, overwrite: Boolean) {
        val temporary = File(parent, destination.name + ".tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            val options = if (overwrite) {
                arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                arrayOf(StandardCopyOption.ATOMIC_MOVE)
            }
            try {
                Files.move(temporary.toPath(), destination.toPath(), *options)
            } catch (_: AtomicMoveNotSupportedException) {
                if (overwrite) {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } else {
                    Files.move(temporary.toPath(), destination.toPath())
                }
            }
        } finally {
            temporary.delete()
        }
    }
}

package com.comfyremote.app.data.project

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable single-project backups. Everything is streamed and bounded.
 * Imported snapshots are detached from paid/remote tasks and receive fresh IDs.
 */
class ProjectArchive(
    private val store: ProjectStore,
    private val mediaDir: File,
    private val readableRoots: List<File>,
) {
    data class ExportResult(val mediaFiles: Int, val bytes: Long)
    private data class MediaRow(val path: String, val entry: String)

    fun export(projectId: String, output: OutputStream): ExportResult {
        val project = store.project(projectId) ?: throw IOException("Project not found")
        val paths = referencedPaths(project).sorted()
        if (paths.size > MAX_FILES) throw IOException("Too many media files")
        val rows = paths.mapIndexed { index, path ->
            val file = File(path).canonicalFile
            if (!file.isFile || !readableRoots.any { root ->
                file.path.startsWith(root.canonicalPath.trimEnd(File.separatorChar) + File.separator)
            }) throw IOException("Missing or non-owned media: " + file.name)
            MediaRow(path, "media/" + index + "." + extension(file.extension))
        }
        val bytes = rows.sumOf { row ->
            val size = File(row.path).length()
            if (size > MAX_FILE_BYTES) throw IOException("Media file too large")
            size
        }
        if (bytes > MAX_TOTAL_BYTES) throw IOException("Project media exceeds 2 GiB")
        val json = ProjectCodec.encode(listOf(project))
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) throw IOException("Project index too large")
        val manifest = JSONObject().put("format", "iris-project").put("version", VERSION)
            .put("media", JSONArray().apply {
                rows.forEach { put(JSONObject().put("path", it.path).put("entry", it.entry)) }
            }).toString()
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            fun writeText(name: String, value: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            writeText("manifest.json", manifest)
            writeText("project.json", json)
            rows.forEach { row ->
                val file = File(row.path)
                zip.putNextEntry(ZipEntry(row.entry))
                var copied = 0L
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        if (copied > MAX_FILE_BYTES) throw IOException("Media exceeded limit")
                        zip.write(buffer, 0, n)
                    }
                }
                if (copied != file.length()) throw IOException("Media changed during export")
                zip.closeEntry()
            }
        }
        return ExportResult(rows.size, bytes)
    }

    /** Reject unknown ZIP entries, duplicate paths, damaged data and ZIP bombs. */
    fun importSnapshot(input: InputStream): VideoProject {
        if (!mediaDir.isDirectory && !mediaDir.mkdirs()) throw IOException("Cannot create media directory")
        val staging = File(mediaDir, ".import-" + UUID.randomUUID())
        if (!staging.mkdir()) throw IOException("Cannot create staging directory")
        val moved = mutableListOf<File>()
        var indexCommitted = false
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                val manifestEntry = zip.nextEntry ?: throw IOException("Missing manifest")
                if (manifestEntry.name != "manifest.json" || manifestEntry.isDirectory) {
                    throw IOException("Invalid archive manifest")
                }
                val manifest = JSONObject(readSmall(zip))
                zip.closeEntry()
                if (manifest.optString("format") != "iris-project" ||
                    manifest.optInt("version") != VERSION) throw IOException("Unsupported archive")
                val media = manifest.optJSONArray("media") ?: throw IOException("Missing media manifest")
                if (media.length() > MAX_FILES) throw IOException("Too many files")
                val rows = (0 until media.length()).map { index ->
                    val row = media.optJSONObject(index) ?: throw IOException("Invalid media row")
                    MediaRow(row.optString("path"), row.optString("entry")).also {
                        if (it.path.isBlank() || !ENTRY_PATTERN.matches(it.entry)) {
                            throw IOException("Invalid media entry")
                        }
                    }
                }
                if (rows.map { it.path }.distinct().size != rows.size ||
                    rows.map { it.entry }.distinct().size != rows.size) throw IOException("Duplicate paths")
                val projectEntry = zip.nextEntry ?: throw IOException("Missing project index")
                if (projectEntry.name != "project.json" || projectEntry.isDirectory) {
                    throw IOException("Invalid project index entry")
                }
                val raw = readSmall(zip)
                zip.closeEntry()
                if (ProjectCodec.rawProjectCount(raw) != 1) throw IOException("Expected one project")
                val project = ProjectCodec.decode(raw).singleOrNull()
                    ?: throw IOException("Damaged project index")
                if (referencedPaths(project) != rows.map { it.path }.toSet()) {
                    throw IOException("Referenced media not present in manifest")
                }
                var total = 0L
                val staged = LinkedHashMap<String, File>()
                rows.forEach { row ->
                    val entry = zip.nextEntry ?: throw IOException("Missing media file")
                    if (entry.isDirectory || entry.name != row.entry) {
                        throw IOException("Unrecognized media entry")
                    }
                    val file = File(staging, "media_" + staged.size + "." + row.entry.substringAfterLast('.'))
                    FileOutputStream(file).use { sink ->
                        val buffer = ByteArray(BUFFER)
                        var count = 0L
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            count += n
                            total += n
                            if (count > MAX_FILE_BYTES || total > MAX_TOTAL_BYTES) {
                                throw IOException("Archive media too large")
                            }
                            sink.write(buffer, 0, n)
                        }
                        sink.fd.sync()
                    }
                    zip.closeEntry()
                    staged[row.path] = file
                }
                if (zip.nextEntry != null) throw IOException("Unexpected trailing archive entry")
                val destinations = LinkedHashMap<String, String>()
                staged.forEach { (path, source) ->
                    val target = File(mediaDir, "asset_" + UUID.randomUUID() + "." + extension(source.extension))
                    if (!source.renameTo(target)) throw IOException("Could not install media")
                    moved.add(target)
                    destinations[path] = target.absolutePath
                }
                val snapshot = detachedSnapshot(project, destinations)
                if (!store.insertImported(snapshot)) throw IOException("Could not save imported project")
                indexCommitted = true
                return snapshot
            }
        } finally {
            staging.deleteRecursively()
            if (!indexCommitted) moved.forEach { it.delete() }
        }
    }

    companion object {
        private const val VERSION = 1
        private const val BUFFER = 64 * 1024
        private const val MAX_JSON_BYTES = 4 * 1024 * 1024
        private const val MAX_FILES = 256
        private const val MAX_FILE_BYTES = 1024L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        private val ENTRY_PATTERN = Regex("media/[0-9]{1,4}\\.[a-z0-9]{1,8}")

        private fun extension(raw: String) =
            raw.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"

        private fun readSmall(input: InputStream): String {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_JSON_BYTES) throw IOException("Archive metadata too large")
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8")
        }

        internal fun referencedPaths(project: VideoProject): Set<String> = buildSet {
            fun addPath(path: String?) { if (!path.isNullOrBlank()) add(path) }
            project.assets.forEach { addPath(it.path) }
            project.shots.forEach { shot ->
                addPath(shot.referencePath)
                addPath(shot.resultPath)
                addPath(shot.lastFramePath)
                shot.takes.forEach { addPath(it.resultPath) }
            }
            project.timeline?.let { timeline ->
                (timeline.videoTrack + timeline.audioTrack).forEach { addPath(it.sourcePath) }
            }
        }

        /** Incoming snapshots are editable but cannot resume any remote paid operation. */
        internal fun detachedSnapshot(source: VideoProject, paths: Map<String, String>): VideoProject {
            fun mapped(path: String?): String? = path?.let {
                paths[it] ?: throw IOException("Unmapped media path")
            }
            val shotIds = source.shots.associate { it.id to UUID.randomUUID().toString() }
            val assetIds = source.assets.associate { asset ->
                asset.id to if (asset.id.startsWith("tail:")) {
                    "tail:" + (shotIds[asset.id.removePrefix("tail:")] ?: UUID.randomUUID().toString())
                } else UUID.randomUUID().toString()
            }
            fun remapAsset(id: String): String =
                assetIds[id] ?: throw IOException("Missing asset reference")
            val shots = source.shots.map { shot ->
                val takes = shot.takes.map { take ->
                    take.copy(
                        id = UUID.randomUUID().toString(),
                        taskId = null,
                        status = if (take.status == "running") "failed" else take.status,
                        error = if (take.status == "running") "Imported snapshot: task detached" else take.error,
                        referenceAssetIds = take.referenceAssetIds.map(::remapAsset),
                        resultPath = mapped(take.resultPath),
                    )
                }
                shot.copy(
                    id = shotIds.getValue(shot.id),
                    taskId = null,
                    status = if (shot.status == "running") "failed" else shot.status,
                    error = if (shot.status == "running") "Imported snapshot: task detached" else shot.error,
                    referencePath = mapped(shot.referencePath),
                    resultPath = mapped(shot.resultPath),
                    lastFramePath = mapped(shot.lastFramePath),
                    referenceAssetIds = shot.referenceAssetIds.map(::remapAsset),
                    firstFrameAssetId = shot.firstFrameAssetId?.let(::remapAsset),
                    lastFrameAssetId = shot.lastFrameAssetId?.let(::remapAsset),
                    selectedTakeId = shot.takes.indexOfFirst { it.id == shot.selectedTakeId }
                        .takeIf { it >= 0 }?.let { takes[it].id },
                    takes = takes,
                )
            }
            return source.copy(
                id = UUID.randomUUID().toString(),
                title = source.title + " (imported)",
                archived = false,
                shots = shots,
                assets = source.assets.map { asset ->
                    asset.copy(id = remapAsset(asset.id), path = mapped(asset.path)!!)
                },
                profiles = source.profiles.map { profile ->
                    profile.copy(
                        id = UUID.randomUUID().toString(),
                        assetIds = profile.assetIds.map(::remapAsset),
                    )
                },
                stageMediaBindings = source.stageMediaBindings.map {
                    it.copy(assetId = remapAsset(it.assetId))
                },
                timeline = source.timeline?.let { timeline ->
                    timeline.copy(
                        id = UUID.randomUUID().toString(),
                        videoTrack = timeline.videoTrack.map {
                            it.copy(id = "tlc:" + UUID.randomUUID(), sourcePath = mapped(it.sourcePath)!!)
                        },
                        audioTrack = timeline.audioTrack.map {
                            it.copy(id = "tlc:" + UUID.randomUUID(), sourcePath = mapped(it.sourcePath)!!)
                        },
                    )
                },
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        }
    }
}

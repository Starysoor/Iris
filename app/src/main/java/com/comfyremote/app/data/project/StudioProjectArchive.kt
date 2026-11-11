package com.comfyremote.app.data.project

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single-project, portable, user-invoked ZIP. Media are streamed and bounded,
 * never readBytes(), and every item is validated before indexing an import.
 * Independent generation task IDs are intentionally not portable.
 */
object StudioProjectArchive {
    data class Imported(val project: VideoProject, val mediaDirectory: File)

    private const val FORMAT = "iris-studio-project"
    private const val VERSION = 1
    private const val MAX_FILES = 512
    private const val MAX_SINGLE_BYTES = 1L shl 30
    private const val MAX_TOTAL_BYTES = 2L shl 30
    private const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
    private val filenamePattern = Regex("media/[0-9]{6}\\.[a-z0-9]{1,8}")
    private val hex = "0123456789abcdef".toCharArray()

    private fun digestHex(digest: ByteArray): String = buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }

    fun mediaPaths(project: VideoProject): List<String> = buildList {
        project.assets.forEach { add(it.path) }
        project.shots.forEach { shot ->
            shot.referencePath?.let(::add)
            shot.lastFramePath?.let(::add)
            shot.resultPath?.let(::add)
            shot.takes.forEach { take -> take.resultPath?.let(::add) }
        }
        project.timeline?.let { timeline ->
            timeline.videoTrack.forEach { add(it.sourcePath) }
            timeline.audioTrack.forEach { add(it.sourcePath) }
        }
    }.filter { it.isNotBlank() }.distinct()

    private fun remapPaths(project: VideoProject, paths: Map<String, String>): VideoProject {
        fun map(raw: String): String =
            paths[raw] ?: throw IOException("Project refers to absent archive media")
        fun nullable(raw: String?): String? = raw?.let(::map)
        return project.copy(
            assets = project.assets.map { it.copy(path = map(it.path)) },
            shots = project.shots.map { shot ->
                shot.copy(
                    referencePath = nullable(shot.referencePath),
                    lastFramePath = nullable(shot.lastFramePath),
                    resultPath = nullable(shot.resultPath),
                    takes = shot.takes.map { take ->
                        take.copy(resultPath = nullable(take.resultPath))
                    },
                )
            },
            timeline = project.timeline?.let { tl ->
                tl.copy(
                    videoTrack = tl.videoTrack.map { it.copy(sourcePath = map(it.sourcePath)) },
                    audioTrack = tl.audioTrack.map { it.copy(sourcePath = map(it.sourcePath)) },
                )
            },
        )
    }

    private fun detachedIdentity(project: VideoProject): VideoProject {
        val now = System.currentTimeMillis()
        val shotIds = project.shots.associate { it.id to UUID.randomUUID().toString() }
        val assetIds = project.assets.associate { asset ->
            asset.id to (if (asset.id.startsWith("tail:")) {
                val shotId = asset.id.removePrefix("tail:")
                shotIds[shotId]?.let { "tail:$it" } ?: UUID.randomUUID().toString()
            } else UUID.randomUUID().toString())
        }
        fun assets(ids: List<String>) = ids.mapNotNull(assetIds::get)
        return project.copy(
            id = UUID.randomUUID().toString(),
            title = project.title + " (imported)",
            shots = project.shots.map { shot ->
                val takeIds = shot.takes.associate { it.id to UUID.randomUUID().toString() }
                shot.copy(
                    id = shotIds.getValue(shot.id),
                    taskId = null,
                    status = if (shot.status == "running") "failed" else shot.status,
                    error = if (shot.status == "running")
                        "Imported snapshot; verify original remote job before retrying" else shot.error,
                    takes = shot.takes.map { take ->
                        take.copy(
                            id = takeIds.getValue(take.id),
                            taskId = null,
                            referenceAssetIds = assets(take.referenceAssetIds),
                            status = if (take.status == "running") "failed" else take.status,
                            error = if (take.status == "running")
                                "Remote job not transferred; verify before retry" else take.error,
                        )
                    },
                    selectedTakeId = shot.selectedTakeId?.let(takeIds::get),
                    referenceAssetIds = assets(shot.referenceAssetIds),
                    firstFrameAssetId = shot.firstFrameAssetId?.let(assetIds::get),
                    lastFrameAssetId = shot.lastFrameAssetId?.let(assetIds::get),
                    createdAt = now, updatedAt = now,
                )
            },
            assets = project.assets.map { it.copy(id = assetIds.getValue(it.id), createdAt = now) },
            profiles = project.profiles.map { profile ->
                profile.copy(id = UUID.randomUUID().toString(),
                    assetIds = assets(profile.assetIds), createdAt = now)
            },
            stageMediaBindings = project.stageMediaBindings.mapNotNull { binding ->
                assetIds[binding.assetId]?.let { binding.copy(assetId = it) }
            },
            timeline = project.timeline?.let { timeline ->
                timeline.copy(
                    id = UUID.randomUUID().toString(),
                    videoTrack = timeline.videoTrack.map { it.copy(id = "tlc:" + UUID.randomUUID()) },
                    audioTrack = timeline.audioTrack.map { it.copy(id = "tlc:" + UUID.randomUUID()) },
                    createdAt = now, updatedAt = now,
                )
            },
            createdAt = now, updatedAt = now,
        )
    }

    /** Export fails rather than producing an apparently complete archive with missing media. */
    fun write(project: VideoProject, output: OutputStream) {
        val paths = mediaPaths(project)
        if (paths.size > MAX_FILES) throw IOException("Too many project media files")
        val entries = paths.mapIndexed { index, path ->
            val source = File(path)
            if (!source.isFile || !source.canRead()) {
                throw IOException("Missing or unreadable project media: " + source.name)
            }
            val length = source.length()
            if (length < 0L || length > MAX_SINGLE_BYTES) throw IOException("Project media exceeds per-file cap")
            val ext = source.extension.lowercase(Locale.US).takeIf {
                it.matches(Regex("[a-z0-9]{1,8}"))
            } ?: "bin"
            Triple(source, String.format(Locale.US, "media/%06d.%s", index + 1, ext), length)
        }
        if (entries.sumOf { it.third } > MAX_TOTAL_BYTES) {
            throw IOException("Project archive exceeds 2 GiB limit")
        }
        val portable = remapPaths(project, entries.associate { it.first.path to it.second })
        val manifestRecords = JSONArray()
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            entries.forEach { (source, name, expectedBytes) ->
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                zip.putNextEntry(ZipEntry(name))
                FileInputStream(source).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        if (n == 0) continue
                        copied += n
                        if (copied > MAX_SINGLE_BYTES || copied > expectedBytes) {
                            throw IOException("Project media changed while exporting")
                        }
                        zip.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                    }
                }
                zip.closeEntry()
                if (copied != expectedBytes) throw IOException("Project media changed while exporting")
                manifestRecords.put(
                    JSONObject().put("name", name).put("length", copied)
                        .put("sha256", digestHex(digest.digest())),
                )
            }
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("index", ProjectCodec.encode(listOf(portable)))
                .put("media", manifestRecords)
                .toString().toByteArray(Charsets.UTF_8)
            if (manifest.size > MAX_MANIFEST_BYTES) throw IOException("Project index is too large for archive")
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
        }
    }

    /**
     * Only fixed-schema entry names are accepted. Files are written to a new,
     * private staging directory; the caller commits the index *after* validation.
     */
    fun read(input: InputStream, storageRoot: File): Imported {
        if (!storageRoot.isDirectory && !storageRoot.mkdirs()) {
            throw IOException("Cannot create project archive media directory")
        }
        val dir = File(storageRoot, UUID.randomUUID().toString())
        if (!dir.mkdir()) throw IOException("Cannot create isolated media staging directory")
        try {
            val received = linkedMapOf<String, Pair<Long, String>>()
            var manifestText: String? = null
            var totalBytes = 0L
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entryCount = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_FILES + 1 || entry.isDirectory) {
                        throw IOException("Too many or invalid ZIP entries")
                    }
                    when (val name = entry.name) {
                        "manifest.json" -> {
                            if (manifestText != null) throw IOException("Duplicate archive manifest")
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(32 * 1024)
                            var size = 0
                            while (true) {
                                val n = zip.read(buffer)
                                if (n == -1) break
                                if (n == 0) continue
                                size += n
                                if (size > MAX_MANIFEST_BYTES) throw IOException("Oversized ZIP manifest")
                                output.write(buffer, 0, n)
                            }
                            manifestText = output.toString(Charsets.UTF_8.name())
                        }
                        else -> {
                            if (!filenamePattern.matches(name) || received.containsKey(name)) {
                                throw IOException("Unexpected or repeated archive path")
                            }
                            val file = File(dir, name.substringAfter('/'))
                            val digest = MessageDigest.getInstance("SHA-256")
                            var copied = 0L
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n == -1) break
                                    if (n == 0) continue
                                    copied += n
                                    totalBytes += n
                                    if (copied > MAX_SINGLE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                                        throw IOException("Project ZIP media exceeds safety cap")
                                    }
                                    output.write(buffer, 0, n)
                                    digest.update(buffer, 0, n)
                                }
                                output.fd.sync()
                            }
                            received[name] = copied to digestHex(digest.digest())
                        }
                    }
                    zip.closeEntry()
                }
            }
            val manifest = JSONObject(manifestText ?: throw IOException("Missing project manifest"))
            if (manifest.optString("format") != FORMAT || manifest.optInt("version", -1) != VERSION) {
                throw IOException("Unsupported Iris project archive format")
            }
            val records = manifest.optJSONArray("media") ?: throw IOException("Missing archive media listing")
            if (records.length() != received.size) throw IOException("Archive media count mismatch")
            val expected = linkedSetOf<String>()
            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: throw IOException("Invalid archive media listing")
                val name = record.optString("name")
                if (!expected.add(name) || !filenamePattern.matches(name)) {
                    throw IOException("Invalid or duplicated archive media name")
                }
                val actual = received[name] ?: throw IOException("Missing archive file")
                if (record.optLong("length", -1) != actual.first ||
                    !record.optString("sha256").equals(actual.second, ignoreCase = true)) {
                    throw IOException("Media checksum or size mismatch")
                }
            }
            if (received.keys != expected) throw IOException("Unexpected ZIP payload")
            val index = manifest.optString("index")
            if (ProjectCodec.rawProjectCount(index) != 1) throw IOException("ZIP must contain exactly one project")
            val portable = ProjectCodec.decode(index).singleOrNull()
                ?: throw IOException("Project index could not be decoded")
            if (mediaPaths(portable).toSet() != expected) {
                throw IOException("Project media paths do not match the ZIP manifest")
            }
            val relocated = remapPaths(portable, received.keys.associateWith {
                File(dir, it.substringAfter('/')).absolutePath
            })
            return Imported(detachedIdentity(relocated), dir)
        } catch (error: Exception) {
            dir.deleteRecursively()
            throw error
        }
    }
}

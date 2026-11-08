package com.comfyremote.app

import android.content.Context
import com.comfyremote.app.data.reference.ReferenceRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One past generation stored locally on the phone. */
data class HistoryItem(
    val id: String,          // ComfyUI prompt_id
    val wfPath: String,      // workflow file path on the server (for re-run)
    val wfName: String,
    val time: Long,
    val values: Map<String, String>, // field edits used for this run
    /** Source field metadata, used to map values onto a different workflow. */
    val fields: List<PromptField> = emptyList(),
    val randomSeed: Boolean,
    val images: List<String>,        // local file paths
    val summary: String,             // first prompt text, truncated
    val prompts: List<LabeledText> = emptyList(), // full text prompts, copyable
    val seeds: String = "",          // seeds actually used
    val serverUrl: String = "",      // submit-time server for multi-server history
    val favorite: Boolean = false,
    val tags: List<String> = emptyList(),
)

/** JSON-file-backed result library; result PNGs saved under app-external files dir. */
class HistoryStore(
    private val dir: File,
    /** Files still referenced by storyboards (or other stores) must survive delete/prune. */
    /** Injected in tests to simulate an index commit failure without risking user media. */
    private val replaceIndex: (File, File) -> Unit = { source, target ->
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        Unit
    },
    private val protectedMedia: () -> Set<String> = { emptySet() },
) {

    constructor(
        context: Context,
        protectedMedia: () -> Set<String> = { emptySet() },
    ) : this(File(context.getExternalFilesDir(null), "history").apply { mkdirs() }, protectedMedia = protectedMedia)

    private val indexFile = File(dir, "index.json")
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    init {
        // A killed process cannot run the export coroutine's finally block.
        // Reclaim only old, unindexed Studio export tmp files at process startup.
        cleanupAbandonedStudioExports()
    }

    /**
     * Strictly scoped startup scavenger: never delete active/fresh exports, user
     * media, imported files, other workers' tmp files, or completed MP4s.
     */
    internal fun cleanupAbandonedStudioExports(now: Long = System.currentTimeMillis()): Int {
        val threshold = now - STUDIO_TMP_GRACE_MS
        val pattern = Regex("""project-export-\d{10,20}_\d+\.mp4\.tmp""")
        return dir.listFiles()?.count { candidate ->
            candidate.isFile && !Files.isSymbolicLink(candidate.toPath()) &&
                pattern.matches(candidate.name) && candidate.lastModified() < threshold &&
                candidate.delete()
        } ?: 0
    }

    fun load(): List<HistoryItem> = runCatching {
        val arr = JSONArray(indexFile.takeIf { it.isFile }?.readText() ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i)
                ?: throw IOException("History index contains an unreadable entry at $i")
            if (o.optString("id").isBlank()) throw IOException("History entry $i has no id")
            HistoryItem(
                id = o.optString("id"),
                wfPath = o.optString("wfPath"),
                wfName = o.optString("wfName"),
                time = o.optLong("time"),
                values = strMap(o.optJSONObject("values")),
                fields = promptFields(o.optJSONArray("fields")),
                randomSeed = o.optBoolean("randomSeed", true),
                images = strList(o.optJSONArray("images")).filter { p -> File(p).isFile },
                summary = o.optString("summary"),
                prompts = labeledList(o.optJSONArray("prompts")),
                seeds = o.optString("seeds"),
                serverUrl = o.optString("serverUrl"),
                favorite = o.optBoolean("favorite", false),
                tags = strList(o.optJSONArray("tags")),
            )
        }
    }.onSuccess {
        _loadError.value = null
    }.getOrElse { error ->
        // Do not pretend a corrupt index is an empty Results library.
        _loadError.value = error.message ?: error.javaClass.simpleName
        emptyList()
    }.sortedWith(
        compareByDescending<HistoryItem> { it.favorite }.thenByDescending { it.time }
    )

    fun add(item: HistoryItem) {
        // Recovery can finish the same prompt twice around a process crash; prompt_id is the identity.
        val old = load().firstOrNull { it.id == item.id }
        val merged = if (old == null) item else item.copy(
            favorite = old.favorite,
            tags = old.tags,
        )
        val kept = (load().filter { it.id != item.id } + merged)
            .sortedWith(compareByDescending<HistoryItem> { it.favorite }.thenByDescending { it.time })
            .take(MAX_ITEMS)
        writeItems(kept)
        pruneImages(kept)
    }

    fun updateMetadata(id: String, favorite: Boolean? = null, tags: List<String>? = null) {
        val normalizedTags = tags?.map(String::trim)?.filter(String::isNotBlank)?.distinct()?.take(16)
        val list = load().map { item ->
            if (item.id != id) item else item.copy(
                favorite = favorite ?: item.favorite,
                tags = normalizedTags ?: item.tags,
            )
        }.sortedWith(compareByDescending<HistoryItem> { it.favorite }.thenByDescending { it.time })
        writeItems(list)
    }

    fun deleteMany(ids: Collection<String>) {
        val idSet = ids.toSet()
        if (idSet.isEmpty()) return
        val existing = load()
        val victims = existing.filter { it.id in idSet }
        if (victims.isEmpty()) return
        val kept = existing.filterNot { it.id in idSet }
        val stillReferenced = protectedMedia() + kept.flatMap { it.images }
        // Commit the new index first; failed I/O must not destroy existing results.
        writeItems(kept)
        val ownedDir = dir.canonicalFile.toPath()
        victims.flatMap { it.images }.distinct()
            .filterNot { it in stillReferenced }
            .forEach { path ->
                runCatching {
                    val media = File(path).canonicalFile
                    // Only delete app-owned results, never arbitrary imported source files.
                    if (media.isFile && media.toPath().startsWith(ownedDir)) media.delete()
                }
            }
    }

    fun delete(id: String) = deleteMany(listOf(id))

    fun newImageFile(id: String, idx: Int): File = newMediaFile(id, idx, "png")

    fun newMediaFile(id: String, idx: Int, extension: String): File {
        // IDs come from external APIs in legacy flows: never treat them as paths.
        val safeId = id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,96}")) }
            ?: java.security.MessageDigest.getInstance("SHA-256")
                .digest(id.toByteArray(Charsets.UTF_8))
                .take(20)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val safeExt = extension.trim().removePrefix(".").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
        return File(dir, "${safeId}_${idx.coerceAtLeast(0)}.$safeExt")
    }

    private fun writeItems(items: List<HistoryItem>) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create history directory: $dir")
        // A corrupted index must not be silently overwritten with an empty library.
        if (indexFile.isFile) {
            try {
                val existing = JSONArray(indexFile.readText())
                for (i in 0 until existing.length()) {
                    val row = existing.optJSONObject(i)
                    if (row == null || row.optString("id").isBlank()) {
                        throw IOException("History index contains an unreadable entry at $i")
                    }
                }
            } catch (error: Exception) {
                throw IOException("History index is unreadable; refusing to overwrite it", error)
            }
        }
        val raw = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
        val tmp = File.createTempFile("index.json.", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { stream ->
                stream.write(raw.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            replaceIndex(tmp, indexFile)
        } finally {
            tmp.delete()
        }
    }

    private fun pruneImages(kept: List<HistoryItem>) {
        val liveFiles = kept.flatMap { it.images }.toSet()
        val protected = protectedMedia()
        val cutoff = System.currentTimeMillis() - PRUNE_GRACE_MS
        dir.listFiles { f -> f.isFile && f.name != "index.json" && !f.name.endsWith(".tmp") }?.forEach { f ->
            // A runner may still be writing a file before its History entry exists.
            if (f.absolutePath !in liveFiles && f.absolutePath !in protected && f.lastModified() < cutoff) {
                f.delete()
            }
        }
    }

    private fun HistoryItem.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("wfPath", wfPath)
        .put("wfName", wfName)
        .put("time", time)
        .put("values", JSONObject(values))
        .put("fields", JSONArray().apply { fields.forEach { put(it.toJson()) } })
        .put("randomSeed", randomSeed)
        .put("images", JSONArray().apply { images.forEach { put(it) } })
        .put("summary", summary)
        .put("prompts", JSONArray().apply { prompts.forEach { put(JSONObject().put("label", it.label).put("value", it.value)) } })
        .put("seeds", seeds)
        .put("serverUrl", serverUrl)
        .put("favorite", favorite)
        .put("tags", JSONArray().apply { tags.forEach { put(it) } })

    private fun strMap(o: JSONObject?): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        o?.keys()?.forEach { k -> m[k] = o.optString(k) }
        return m
    }

    private fun strList(a: JSONArray?): List<String> {
        val out = mutableListOf<String>()
        if (a != null) for (i in 0 until a.length()) out.add(a.optString(i))
        return out.filter { it.isNotBlank() }
    }

    private fun PromptField.toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("nodeId", nodeId)
        .put("input", input)
        .put("label", label)
        .put("kind", kind.name)
        .put("value", value)
        .put("multiline", multiline)
        .put("ptype", ptype)
        .put("options", JSONArray().apply { options.forEach { put(it) } })
        .put("min", min ?: JSONObject.NULL)
        .put("max", max ?: JSONObject.NULL)
        .put("required", required)
        .put("referenceRole", referenceRole.name)

    private fun promptFields(a: JSONArray?): List<PromptField> {
        val out = mutableListOf<PromptField>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val key = o.optString("key").takeIf { it.isNotBlank() } ?: continue
            out += PromptField(
                key = key,
                nodeId = o.optString("nodeId"),
                input = o.optString("input"),
                label = o.optString("label"),
                kind = runCatching { FieldKind.valueOf(o.optString("kind")) }.getOrDefault(FieldKind.PARAM),
                value = o.optString("value"),
                multiline = o.optBoolean("multiline", false),
                ptype = o.optString("ptype"),
                options = strList(o.optJSONArray("options")),
                min = if (o.isNull("min")) null else o.optString("min"),
                max = if (o.isNull("max")) null else o.optString("max"),
                required = o.optBoolean("required", false),
                referenceRole = ReferenceRole.fromStorage(o.optString("referenceRole")),
            )
        }
        return out
    }

    private fun labeledList(a: JSONArray?): List<LabeledText> {
        val out = mutableListOf<LabeledText>()
        if (a != null) for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(LabeledText(o.optString("label"), o.optString("value")))
        }
        return out
    }

    companion object {
        private const val MAX_ITEMS = 200
        private const val PRUNE_GRACE_MS = 60 * 60_000L
        private const val STUDIO_TMP_GRACE_MS = 10 * 60_000L
    }
}

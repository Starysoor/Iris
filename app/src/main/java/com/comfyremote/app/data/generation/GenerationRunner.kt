package com.comfyremote.app.data.generation

import com.comfyremote.app.FieldKind
import com.comfyremote.app.HistoryItem
import com.comfyremote.app.LabeledText
import com.comfyremote.app.OutputImage
import com.comfyremote.app.WsEvent
import com.comfyremote.app.historyCancelled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Executes exactly one immutable generation request. WebSocket is best-effort
 * realtime feedback; /history remains the authoritative completion source.
 */
class GenerationRunner(
    private val backend: GenerationBackend,
    private val history: GenerationHistorySink,
    private val pollDelayMs: Long = 4_000L,
    private val timeoutMs: Long = 30 * 60_000L,
    /** Resolves `local:` image values to data URLs for hosted workflows. */
    private val resolveCloudImage: (String) -> String? = { null },
) {
    suspend fun run(
        task: GenerationTask,
        onUpdate: (GenerationTask) -> Unit,
    ): GenerationTask {
        var current = task
        fun update(next: GenerationTask) {
            if (current.isTerminal) return
            current = next
            onUpdate(next)
        }

        val request = task.request
        update(current.copy(phase = GenerationPhase.QUEUEING, startedAt = System.currentTimeMillis()))
        val (prompt, seedsUsed) = GenerationPromptBuilder.build(request, resolveCloudImage)
        val clientId = task.clientId ?: error("missing clientId")
        val promptId = backend.queuePrompt(prompt, clientId)
        val seedInfo = seedsUsed.values.joinToString(", ")
        // Persist actual random seeds immediately after queueing so process recovery can reproduce metadata.
        update(
            current.copy(
                phase = GenerationPhase.QUEUED,
                promptId = promptId,
                seedValues = seedsUsed,
                seedInfo = seedInfo,
                queuePosition = null,
            )
        )
        return follow(current, promptId, seedsUsed, onUpdate, useWebSocket = true)
    }

    /**
     * Re-attaches to a prompt that was already submitted before process death.
     * History is checked first, then /queue. A missing prompt is never guessed to be running.
     */
    suspend fun recover(
        task: GenerationTask,
        onUpdate: (GenerationTask) -> Unit,
    ): GenerationTask {
        var current = task.copy(preview = null, recovered = true, recoveryNote = "Checking ComfyUI state")
        fun update(next: GenerationTask) {
            if (current.isTerminal) return
            current = next
            onUpdate(next)
        }
        onUpdate(current)

        val promptId = task.promptId
        if (promptId.isNullOrBlank()) {
            val failed = current.copy(
                phase = GenerationPhase.FAILED,
                error = GenerationError(ErrorKind.CONNECTION, "Recovery failed: task ended before a prompt_id was persisted"),
                completedAt = System.currentTimeMillis(),
                recoveryNote = "No prompt_id",
            )
            onUpdate(failed)
            return failed
        }

        val seedsUsed = effectiveSeeds(current)
        repeat(RECOVERY_PROBES) {
            backend.historyEntry(promptId)?.let { entry ->
                return finish(current, entry, promptId, seedsUsed, onUpdate)
            }
            val queue = backend.queueSnapshot()
            if (queue == null) {
                update(current.copy(phase = GenerationPhase.RUNNING, progress = null, recoveryNote = "Recovered by history polling"))
                return follow(current, promptId, seedsUsed, onUpdate, useWebSocket = false)
            }
            queue.pendingPosition(promptId)?.let { position ->
                update(
                    current.copy(
                        phase = GenerationPhase.QUEUED,
                        queuePosition = position,
                        progress = null,
                        recoveryNote = "Recovered queued task",
                    )
                )
                return follow(current, promptId, seedsUsed, onUpdate, useWebSocket = false)
            }
            if (queue.isRunning(promptId)) {
                update(
                    current.copy(
                        phase = GenerationPhase.RUNNING,
                        queuePosition = null,
                        progress = current.progress ?: 0f,
                        recoveryNote = "Recovered running task",
                    )
                )
                return follow(current, promptId, seedsUsed, onUpdate, useWebSocket = false)
            }
            delay(RECOVERY_PROBE_DELAY_MS)
        }

        val failed = current.copy(
            phase = GenerationPhase.FAILED,
            error = GenerationError(
                ErrorKind.CONNECTION,
                "Recovery failed: prompt is no longer present in ComfyUI history or queue",
            ),
            completedAt = System.currentTimeMillis(),
            preview = null,
            recoveryNote = "Prompt missing on server",
        )
        onUpdate(failed)
        return failed
    }

    private suspend fun follow(
        task: GenerationTask,
        promptId: String,
        seedsUsed: Map<String, String>,
        onUpdate: (GenerationTask) -> Unit,
        useWebSocket: Boolean,
    ): GenerationTask {
        var current = task
        fun update(next: GenerationTask) {
            if (current.isTerminal) return
            current = next
            onUpdate(next)
        }

        val clientId = task.clientId
        val entry: JSONObject = coroutineScope {
            val wsJob: Job? = if (useWebSocket && !clientId.isNullOrBlank()) {
                launch {
                    runCatching {
                        backend.wsEvents(clientId).collect { event ->
                            when (event) {
                                is WsEvent.Preview -> update(current.copy(preview = event.bitmap))
                                is WsEvent.Json -> {
                                    val data = event.obj.optJSONObject("data")
                                    when (event.obj.optString("type")) {
                                        "status" -> {
                                            val remaining = data?.optJSONObject("status")?.optJSONObject("exec_info")
                                                ?.optInt("queue_remaining", -1) ?: -1
                                            if (remaining >= 0) update(current.copy(queueRemaining = remaining))
                                        }
                                        "execution_start" -> if (data?.optString("prompt_id") == promptId) {
                                            update(
                                                current.copy(
                                                    phase = GenerationPhase.RUNNING,
                                                    queuePosition = null,
                                                    progress = 0f,
                                                )
                                            )
                                        }
                                        "executing" -> if (data?.optString("prompt_id") == promptId) {
                                            val node = data.optString("node")
                                            if (node.isNotEmpty() && node != "null") {
                                                update(
                                                    current.copy(
                                                        phase = GenerationPhase.RUNNING,
                                                        queuePosition = null,
                                                        nodeId = node,
                                                        nodeTitle = task.request.nodeTitles[node].orEmpty(),
                                                        progress = null,
                                                    )
                                                )
                                            }
                                        }
                                        "progress" -> {
                                            val eventPrompt = data?.optString("prompt_id")
                                            if (data != null && data.has("value") && (eventPrompt.isNullOrEmpty() || eventPrompt == promptId)) {
                                                val value = data.optDouble("value", 0.0)
                                                val max = data.optDouble("max", 1.0).coerceAtLeast(1e-6)
                                                update(
                                                    current.copy(
                                                        phase = GenerationPhase.RUNNING,
                                                        queuePosition = null,
                                                        progress = (value / max).toFloat().coerceIn(0f, 1f),
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else null
            try {
                pollHistory(promptId) { phase, position ->
                    when (phase) {
                        GenerationPhase.QUEUED -> update(
                            current.copy(
                                phase = GenerationPhase.QUEUED,
                                queuePosition = position,
                                progress = null,
                            )
                        )
                        GenerationPhase.RUNNING -> update(
                            current.copy(
                                phase = GenerationPhase.RUNNING,
                                queuePosition = null,
                                progress = current.progress ?: 0f,
                            )
                        )
                        else -> Unit
                    }
                }
            } finally {
                wsJob?.cancel()
            }
        }

        return finish(current, entry, promptId, seedsUsed, onUpdate)
    }

    private suspend fun pollHistory(
        promptId: String,
        onServerState: (GenerationPhase, Int?) -> Unit,
    ): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var first = true
        while (System.currentTimeMillis() < deadline) {
            backend.historyEntry(promptId)?.let { return it }
            val queue = runCatching { backend.queueSnapshot() }.getOrNull()
            when {
                queue?.isRunning(promptId) == true -> onServerState(GenerationPhase.RUNNING, null)
                queue?.pendingPosition(promptId) != null -> onServerState(
                    GenerationPhase.QUEUED,
                    queue.pendingPosition(promptId),
                )
                queue == null && first -> onServerState(GenerationPhase.RUNNING, null)
            }
            first = false
            delay(pollDelayMs)
        }
        throw GenerationTimeoutException("Timed out waiting for ComfyUI history")
    }

    private suspend fun finish(
        task: GenerationTask,
        entry: JSONObject,
        promptId: String,
        seedsUsed: Map<String, String>,
        onUpdate: (GenerationTask) -> Unit,
    ): GenerationTask {
        if (historyCancelled(entry)) {
            return task.copy(
                phase = GenerationPhase.CANCELLED,
                progress = null,
                completedAt = System.currentTimeMillis(),
                preview = null,
                queuePosition = null,
            ).also(onUpdate)
        }

        extractError(entry, task.request.nodeTitles)?.let { error ->
            return task.copy(
                phase = GenerationPhase.FAILED,
                progress = null,
                error = error,
                completedAt = System.currentTimeMillis(),
                preview = null,
                queuePosition = null,
            ).also(onUpdate)
        }

        val outputImages = parseImages(entry)
        val results = mutableListOf<ResultImage>()
        var failedDownloads = 0
        outputImages.forEachIndexed { index, image ->
            runCatching {
                val file = history.newMediaFile(task.taskId, index, extensionFor(image))
                if (!file.isFile || file.length() == 0L) {
                    // Write to .tmp and rename so a crash cannot leave a half file as a result.
                    val temp = java.io.File(file.parentFile, file.name + ".tmp")
                    try {
                        backend.downloadTo(image, temp)
                        if (!temp.renameTo(file)) {
                            temp.copyTo(file, overwrite = true)
                            temp.delete()
                        }
                    } catch (t: Throwable) {
                        temp.delete()
                        throw t
                    }
                }
                results += ResultImage(id = "${task.taskId}:$index", localPath = file.absolutePath)
            }.onFailure { failedDownloads++ }
        }
        if (outputImages.isNotEmpty() && results.isEmpty()) {
            return task.copy(
                phase = GenerationPhase.FAILED,
                progress = null,
                error = GenerationError(ErrorKind.DOWNLOAD, "All result image downloads failed"),
                completedAt = System.currentTimeMillis(),
                preview = null,
                queuePosition = null,
            ).also(onUpdate)
        }

        val valuesUsed = task.request.fieldValues + seedsUsed
        val summary = task.request.fields.firstOrNull { it.kind == FieldKind.TEXT }
            ?.let { valuesUsed[it.key] ?: it.value }?.take(50) ?: task.request.workflowName
        val prompts = task.request.fields.filter { it.kind == FieldKind.TEXT }
            .map { LabeledText(it.label, valuesUsed[it.key] ?: it.value) }
            .filter { it.value.isNotBlank() }
        val seedInfo = seedsUsed.values.joinToString(", ").ifBlank { task.seedInfo }

        history.add(
            HistoryItem(
                id = task.taskId,
                wfPath = task.request.workflowPath,
                wfName = task.request.workflowName,
                time = System.currentTimeMillis(),
                values = valuesUsed,
                fields = task.request.fields,
                randomSeed = task.request.randomSeed,
                images = results.map { it.localPath },
                summary = summary,
                prompts = prompts,
                seeds = seedInfo,
            )
        )

        return task.copy(
            phase = GenerationPhase.SUCCEEDED,
            progress = 1f,
            results = results,
            completedAt = System.currentTimeMillis(),
            preview = null,
            seedInfo = seedInfo,
            seedValues = seedsUsed,
            queuePosition = null,
            recoveryNote = when {
                failedDownloads > 0 -> "Partial result: $failedDownloads file(s) failed to download"
                task.recovered -> "Recovered from ComfyUI"
                else -> task.recoveryNote
            },
        ).also(onUpdate)
    }

    private fun effectiveSeeds(task: GenerationTask): Map<String, String> {
        if (task.seedValues.isNotEmpty()) return task.seedValues
        if (task.request.randomSeed) return emptyMap()
        return task.request.fields.filter { it.kind == FieldKind.SEED }.associate { field ->
            field.key to (task.request.fieldValues[field.key] ?: field.value)
        }
    }

    private fun extractError(entry: JSONObject, nodeTitles: Map<String, String>): GenerationError? {
        val status = entry.optJSONObject("status") ?: return null
        if (status.optString("status_str") != "error") return null
        val messages = status.optJSONArray("messages")
        if (messages != null) for (i in 0 until messages.length()) {
            val message = messages.optJSONArray(i) ?: continue
            if (message.optString(0) == "execution_error") {
                val data = message.optJSONObject(1) ?: continue
                val nodeId = data.optString("node_id").ifEmpty { null }
                return GenerationError(
                    kind = ErrorKind.EXECUTION,
                    message = data.optString("exception_message").ifEmpty { "Execution error" },
                    nodeId = nodeId,
                    nodeType = data.optString("node_type").ifEmpty { null },
                    nodeTitle = nodeId?.let(nodeTitles::get),
                )
            }
        }
        return GenerationError(ErrorKind.EXECUTION, "Execution error")
    }

    private fun parseImages(entry: JSONObject): List<OutputImage> {
        val result = mutableListOf<OutputImage>()
        val outputs = entry.optJSONObject("outputs") ?: return result
        outputs.keys().forEach { key ->
            val node = outputs.optJSONObject(key) ?: return@forEach
            // ComfyUI uses `images` and `gifs`; AutoDL results are normalised into
            // `images`/`videos`. Audio is persisted too so cloud TTS can share the
            // same history pipeline.
            listOf("images" to "image", "gifs" to "image", "videos" to "video", "audio" to "audio", "audios" to "audio")
                .forEach { (arrayName, defaultKind) ->
                    val array = node.optJSONArray(arrayName) ?: return@forEach
                    for (i in 0 until array.length()) {
                        val image = array.optJSONObject(i) ?: continue
                        val url = image.optString("url").takeIf { it.isNotBlank() }
                        result += OutputImage(
                            filename = image.optString("filename").ifBlank { url.orEmpty() },
                            subfolder = image.optString("subfolder"),
                            type = image.optString("type", "output"),
                            url = url,
                            mediaKind = image.optString("media_kind").ifBlank {
                                if (image.optString("format").startsWith("video/")) "video" else defaultKind
                            },
                            fileType = image.optString("file_type"),
                        )
                    }
                }
        }
        return result
    }

    private fun extensionFor(image: OutputImage): String {
        val reported = image.fileType.trim().removePrefix(".").lowercase()
        if (reported.matches(Regex("[a-z0-9]{2,5}"))) return reported
        val fromName = (image.url ?: image.filename).substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "").lowercase()
        if (fromName.matches(Regex("[a-z0-9]{2,5}"))) return fromName
        return when (image.mediaKind) {
            "video" -> "mp4"
            "audio" -> "wav"
            else -> "png"
        }
    }

    companion object {
        private const val RECOVERY_PROBES = 3
        private const val RECOVERY_PROBE_DELAY_MS = 2_000L
    }
}

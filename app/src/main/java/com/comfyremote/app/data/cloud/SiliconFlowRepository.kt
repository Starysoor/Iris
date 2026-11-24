package com.comfyremote.app.data.cloud

import com.comfyremote.app.ComfyException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** SiliconFlow image and video models (OpenAI-compatible image API + async video API). */
class SiliconFlowRepository(
    private val settings: CloudProviderSettingsStore,
    private val taskStore: CloudProviderTaskStore,
) : CloudProvider {
    override val id: String = PROVIDER
    override val label: String = "SiliconFlow"
    override val settingsSummary: String = "Media models · API key"
    override val pathPrefix: String = "siliconflow:"
    override val baseUrl: String = DEFAULT_BASE
    override val supportsModelDiscovery: Boolean = true
    override val supportsCustomHeaders: Boolean = true

    private data class Pending(val model: String, val kind: String, val base: String)
    private val imageResults = ConcurrentHashMap<String, CloudTaskResult>()
    private val pending = ConcurrentHashMap<String, Pending>()

    private fun account() = settings.account(id, DEFAULT_BASE)

    override fun configured(): Boolean = account().token.isNotBlank()

    override fun endpoint(): String = account().baseUrl

    override suspend fun discoverModels(): List<String> {
        val account = account()
        return discoverModelIds(settings, account) { discoverMetadata(account) }
    }

    private suspend fun discoverMetadata(account: CloudProviderAccount): List<CloudModelMetadata> {
        if (account.token.isBlank()) throw ComfyException("Configure a SiliconFlow API key first")
        val root = CloudHttp.getJson("${account.baseUrl.trimEnd('/')}/models", auth(account))
        return providerModelDirectory(root.optJSONArray("data")).filter {
            it.kind in setOf("image", "video", "audio") ||
                (it.kind.isBlank() && CloudModelParameters.looksLikeMediaModel(it.id))
        }
    }

    override suspend fun catalog(force: Boolean): List<CloudWorkflowSummary> {
        val account = account()
        if (account.token.isBlank()) return emptyList()
        return modelDirectory(settings, account, force) { discoverMetadata(account) }.map { model ->
            val kind = kindOf(model)
            model.summary(kind, fallbackName = model.id.substringAfterLast('/'),
                fallbackDescription = "SiliconFlow $kind generation",
                unavailableReason = unavailableReason(kind))
        }
    }

    override suspend fun detail(workflowId: String): CloudWorkflowSpec {
        val model = settings.modelMetadata(account(), workflowId)
        val kind = kindOf(model)
        val unavailable = unavailableReason(kind)
        return CloudWorkflowSpec(
            id = workflowId,
            name = workflowId.substringAfterLast('/'),
            kind = kind,
            description = "SiliconFlow $kind generation",
            rules = if (unavailable.isBlank()) CloudModelParameters.siliconFlowRules(workflowId, kind) else emptyList(),
            unavailableReason = unavailable,
        ).withMetadata(model)
    }

    override suspend fun submit(workflowId: String, body: JSONObject): String {
        val account = account()
        if (account.token.isBlank()) throw ComfyException("Configure a SiliconFlow API key first")
        val headers = auth(account)
        val base = account.baseUrl.trimEnd('/')
        val kind = kindOf(settings.modelMetadata(account, workflowId))
        unavailableReason(kind).takeIf(String::isNotBlank)?.let { throw ComfyException(it) }
        return if (kind == "video") {
            val payload = buildSiliconFlowVideoPayload(workflowId, body)
            val response = CloudHttp.postJson("$base/video/submit", payload, headers)
            val requestId = response.optString("requestId")
                .takeIf { it.isNotBlank() }
                ?: response.optJSONObject("data")?.optString("requestId")?.takeIf { it.isNotBlank() }
                ?: throw ComfyException(CloudHttp.errorMessage(response, "SiliconFlow returned no requestId"))
            taskStore.saveAcceptedTask(id, requestId, JSONObject().put("base", base), label)
            pending[requestId] = Pending(workflowId, "video", base)
            requestId
        } else {
            val payload = buildSiliconFlowImagePayload(workflowId, body)
            val response = CloudHttp.postJson("$base/images/generations", payload, headers)
            val items = response.optJSONArray("images") ?: response.optJSONArray("data")
                ?: throw ComfyException(CloudHttp.errorMessage(response, "SiliconFlow returned no images"))
            val results = (0 until items.length()).mapNotNull { index ->
                val item = items.optJSONObject(index) ?: return@mapNotNull null
                val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                CloudResult(
                    url = url,
                    type = "image",
                    fileType = extensionOf(url).ifBlank { "png" },
                    outputType = "output",
                )
            }
            if (results.isEmpty()) throw ComfyException("SiliconFlow returned no images")
            val taskId = UUID.randomUUID().toString()
            val result = CloudTaskResult(taskId, "SUCCESS", results, "", 0)
            imageResults[taskId] = result
            taskStore.saveResult(id, taskId, result)
            taskId
        }
    }

    override suspend fun result(taskId: String): CloudTaskResult {
        imageResults[taskId]?.let { return it }
        taskStore.result(id, taskId)?.let { return it }
        val account = account()
        val base = pending[taskId]?.base
            ?: taskStore.pending(id, taskId)?.optString("base")?.takeIf { it.isNotBlank() }
            ?: account.baseUrl.trimEnd('/')
        val response = CloudHttp.postJson(
            "${base.trimEnd('/')}/video/status",
            JSONObject().put("requestId", taskId),
            auth(account),
        )
        val data = response.optJSONObject("data") ?: response
        val status = data.optString("status", response.optString("status"))
        return when (status.lowercase()) {
            "succeed", "success", "succeeded" -> {
                val videos = data.optJSONObject("results")?.optJSONArray("videos")
                    ?: data.optJSONArray("videos")
                    ?: JSONArray()
                val results = (0 until videos.length()).mapNotNull { index ->
                    val item = videos.optJSONObject(index) ?: return@mapNotNull null
                    val url = item.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    CloudResult(
                        url = url,
                        type = "video",
                        fileType = extensionOf(url).ifBlank { "mp4" },
                        outputType = "output",
                    )
                }
                CloudTaskResult(taskId, "SUCCESS", results, "", 0).also {
                    taskStore.saveResult(id, taskId, it)
                }
            }
            "cancelled", "canceled" -> CloudTaskResult(
                taskId, "CANCELED", emptyList(),
                data.optString("message").ifBlank { "SiliconFlow video generation cancelled" }, 0,
            )
            "failed", "failure", "error" -> CloudTaskResult(
                taskId, "FAILED", emptyList(),
                data.optString("message").ifBlank { "SiliconFlow video generation failed" }, 0,
            )
            else -> CloudTaskResult(taskId, "RUNNING", emptyList(), status, 0)
        }
    }

    override fun forgetTask(taskId: String) {
        imageResults.remove(taskId)
        taskStore.clear(id, taskId)
    }

    override suspend fun download(url: String): ByteArray = CloudHttp.download(url)

    override suspend fun downloadTo(url: String, target: java.io.File) {
        CloudHttp.downloadTo(url, target)
    }

    private fun kindOf(model: CloudModelMetadata): String =
        model.kind.ifBlank { CloudModelParameters.mediaKindHint(model.id) }

    private fun unavailableReason(kind: String): String =
        if (kind in setOf("image", "video")) "" else "The SiliconFlow generation contract is unavailable for this model."

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }.orEmpty()

    private fun auth(account: CloudProviderAccount) =
        settings.mergeHeaders(id, mapOf("Authorization" to "Bearer ${account.token}"))

    companion object {
        const val PROVIDER = "siliconflow"
        const val DEFAULT_BASE = "https://api.siliconflow.cn/v1"
    }
}

/** Strict JSON types matter: SiliconFlow rejects stringified booleans and oversized seeds. */
internal fun buildSiliconFlowImagePayload(workflowId: String, body: JSONObject): JSONObject {
    val payload = JSONObject()
        .put("model", workflowId)
        .put("prompt", body.optString("prompt"))
    listOf("negative_prompt", "image_size").forEach { key ->
        body.optString(key).takeIf { it.isNotBlank() }?.let { payload.put(key, it) }
    }
    if (body.has("prompt_enhancement")) {
        payload.put("prompt_enhancement", body.optBoolean("prompt_enhancement", false))
    }
    // Re-number non-blank slots so image, image3 still becomes image, image2.
    CloudModelParameters.referenceImageInputs(body).forEachIndexed { index, (_, value) ->
        payload.put(if (index == 0) "image" else "image${index + 1}", value)
    }
    body.optInt("num_inference_steps", 20).takeIf { it > 0 }?.let { payload.put("num_inference_steps", it) }
    body.opt("guidance_scale")?.let {
        it.toString().toDoubleOrNull()?.let { value -> payload.put("guidance_scale", value) }
    }
    body.opt("seed")?.let { payload.put("seed", it) }
    return payload
}

/** MiniMax-H3 on SiliconFlow uses the newer duration/resolution/ratio/images contract. */
internal fun buildSiliconFlowVideoPayload(workflowId: String, body: JSONObject): JSONObject {
    val payload = JSONObject()
        .put("model", workflowId)
        .put("prompt", body.optString("prompt"))
    if (workflowId.contains("MiniMax-H3", ignoreCase = true)) {
        body.opt("duration")?.toString()?.toIntOrNull()
            ?.let { payload.put("duration", it.coerceIn(5, 15)) }
        body.optString("resolution").takeIf { it.isNotBlank() }?.let { payload.put("resolution", it) }
        body.optString("ratio").takeIf { it.isNotBlank() }?.let { payload.put("ratio", it) }
        val images = JSONArray()
        body.optString("first_frame_image").takeIf { it.isNotBlank() }?.let { url ->
            images.put(JSONObject().put("url", url).put("role", "first_frame"))
        }
        body.optString("last_frame_image").takeIf { it.isNotBlank() }?.let { url ->
            images.put(JSONObject().put("url", url).put("role", "last_frame"))
        }
        if (images.length() > 0) payload.put("images", images)
        return payload
    }
    body.optString("image_size").takeIf { it.isNotBlank() }?.let { payload.put("image_size", it) }
    body.optString("negative_prompt").takeIf { it.isNotBlank() }?.let { payload.put("negative_prompt", it) }
    body.opt("seed")?.let { payload.put("seed", it) }
    if (workflowId.contains("I2V", ignoreCase = true)) {
        val image = body.optString("image").takeIf { it.isNotBlank() }
            ?: throw ComfyException("This video model requires a first-frame image")
        payload.put("image", image)
    }
    return payload
}

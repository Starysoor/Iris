package com.comfyremote.app.data.cloud

import com.comfyremote.app.ComfyException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** OpenAI-compatible Images API (official OpenAI and AutoDL model gateways). */
class OpenAiCompatibleImageRepository(
    private val settings: CloudProviderSettingsStore,
    private val taskStore: CloudProviderTaskStore,
    override val id: String = PROVIDER,
    override val label: String = "OpenAI",
    override val settingsSummary: String = "Official image models · API key",
    override val pathPrefix: String = "openai:",
    override val baseUrl: String = DEFAULT_BASE,
) : CloudProvider {
    override val supportsModelDiscovery: Boolean = true
    override val supportsCustomHeaders: Boolean = true

    private val results = ConcurrentHashMap<String, CloudTaskResult>()

    private fun account() = settings.account(id, baseUrl)

    override fun configured(): Boolean = account().token.isNotBlank()

    override fun endpoint(): String = account().baseUrl

    override suspend fun discoverModels(): List<String> {
        val account = account()
        return discoverModelIds(settings, account) { discoverMetadata(account) }
    }

    internal suspend fun discoverMetadata(account: CloudProviderAccount): List<CloudModelMetadata> {
        if (account.token.isBlank()) throw ComfyException("Configure an API key first")
        val root = CloudHttp.getJson(
            "${account.baseUrl.trimEnd('/')}/models",
            settings.mergeHeaders(id, mapOf("Authorization" to "Bearer ${account.token}")),
        )
        val models = providerModelDirectory(root.optJSONArray("data"))
        return if (id == PROVIDER) models.filter {
            it.kind == "image" || (it.kind.isBlank() && looksLikeImageModel(it.id))
        } else models
    }

    override suspend fun catalog(force: Boolean): List<CloudWorkflowSummary> {
        val account = account()
        if (account.token.isBlank()) return emptyList()
        return modelDirectory(settings, account, force) { discoverMetadata(account) }.map { model ->
            model.summary("image", fallbackName = displayName(model.id),
                fallbackDescription = "Generate images with ${label}. Available controls depend on the model.")
        }
    }

    override suspend fun detail(workflowId: String): CloudWorkflowSpec = CloudWorkflowSpec(
        id = workflowId,
        name = displayName(workflowId),
        kind = "image",
        description = "OpenAI Images API",
        rules = rulesFor(workflowId),
    ).withMetadata(settings.modelMetadata(account(), workflowId))

    override suspend fun submit(workflowId: String, body: JSONObject): String {
        val account = account()
        if (account.token.isBlank()) throw ComfyException("Configure an ${label} API key first")
        val headers = settings.mergeHeaders(id, mapOf("Authorization" to "Bearer ${account.token}"))
        val base = account.baseUrl.trimEnd('/')
        val references = CloudModelParameters.referenceImageInputs(body)
            .filter { CloudModelParameters.supportsReferenceImage(workflowId) }
        val response = if (references.isNotEmpty()) {
            val files = references.mapIndexed { index, (_, value) ->
                val payload = CloudHttp.imagePayload(value)
                    ?: throw ComfyException("The reference image could not be read")
                val extension = when (payload.first) {
                    "image/jpeg" -> "jpg"
                    "image/webp" -> "webp"
                    else -> "png"
                }
                CloudFilePart(
                    // OpenAI accepts repeated image[] parts for multi-reference edits.
                    fieldName = if (references.size == 1) "image" else "image[]",
                    fileName = "reference${index + 1}.$extension",
                    mime = payload.first,
                    bytes = payload.second,
                )
            }
            val fields = linkedMapOf(
                "model" to workflowId,
                "prompt" to body.optString("prompt"),
            )
            listOf("size", "quality", "background", "output_format").forEach { key ->
                body.opt(key)?.toString()?.takeIf { it.isNotBlank() }?.let { fields[key] = it }
            }
            body.optInt("n", 1).takeIf { it > 0 }?.let { fields["n"] = it.toString() }
            CloudHttp.postMultipart(
                url = "$base/images/edits",
                fields = fields,
                files = files,
                headers = headers,
            )
        } else {
            val payload = JSONObject()
                .put("model", workflowId)
                .put("prompt", body.optString("prompt"))
            body.opt("size")?.toString()?.takeIf { it.isNotBlank() }?.let { payload.put("size", it) }
            body.opt("quality")?.toString()?.takeIf { it.isNotBlank() }?.let { payload.put("quality", it) }
            body.opt("style")?.toString()?.takeIf { it.isNotBlank() }?.let { payload.put("style", it) }
            body.opt("background")?.toString()?.takeIf { it.isNotBlank() }?.let { payload.put("background", it) }
            body.opt("output_format")?.toString()?.takeIf { it.isNotBlank() }?.let { payload.put("output_format", it) }
            body.optInt("n", 1).takeIf { it > 0 }?.let { payload.put("n", it) }
            CloudHttp.postJson(
                url = "$base/images/generations",
                body = payload,
                headers = headers,
            )
        }
        val data = response.optJSONArray("data")
            ?: throw ComfyException(CloudHttp.errorMessage(response, "${label} returned no image data"))
        val results = (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val b64 = item.optString("b64_json").takeIf { it.isNotBlank() }
            val url = item.optString("url").takeIf { it.isNotBlank() }
            val format = body.optString("output_format").ifBlank { "png" }
            val resolvedUrl = when {
                !url.isNullOrBlank() -> url
                !b64.isNullOrBlank() -> "data:image/$format;base64,$b64"
                else -> null
            } ?: return@mapNotNull null
            CloudResult(
                url = resolvedUrl,
                type = "image",
                fileType = if (!url.isNullOrBlank()) extensionOf(url).ifBlank { format } else format,
                outputType = "output",
            )
        }
        if (results.isEmpty()) throw ComfyException("${label} returned no images")
        val taskId = UUID.randomUUID().toString()
        val result = CloudTaskResult(
            taskId = taskId,
            status = "SUCCESS",
            results = results,
            message = "",
            durationSeconds = 0,
        )
        this.results[taskId] = result
        taskStore.saveResult(id, taskId, result)
        return taskId
    }

    override suspend fun result(taskId: String): CloudTaskResult =
        results[taskId]
            ?: taskStore.result(id, taskId)
            ?: CloudTaskResult(taskId, "FAILED", emptyList(), "Image result expired", 0)

    override fun forgetTask(taskId: String) {
        results.remove(taskId)
        taskStore.clear(id, taskId)
    }

    override suspend fun download(url: String): ByteArray = CloudHttp.download(url)

    override suspend fun downloadTo(url: String, target: java.io.File) {
        CloudHttp.downloadTo(url, target)
    }

    private fun rulesFor(model: String): List<CloudInputRule> =
        CloudModelParameters.openAiRules(model)

    private fun displayName(model: String): String = when (model) {
        "gpt-image-2.5-flare" -> "GPT Image 2.5 Flare"
        "gpt-image-2.5-sunburst" -> "GPT Image 2.5 Sunburst"
        "gpt-image-2.5" -> "GPT Image 2.5"
        "gpt-image-2" -> "GPT Image 2"
        "gpt-image-1.5" -> "GPT Image 1.5"
        "dall-e-3" -> "DALL·E 3"
        else -> model
    }

    private fun looksLikeImageModel(model: String): Boolean {
        val lower = model.lowercase()
        return "image" in lower || lower.startsWith("dall-e")
    }

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }.orEmpty()

    companion object {
        const val PROVIDER = "openai"
        const val DEFAULT_BASE = "https://api.openai.com/v1"
    }
}

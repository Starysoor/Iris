package com.comfyremote.app.data.cloud

import com.comfyremote.app.ComfyException
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Google Gemini / Imagen official image APIs. */
class GeminiImageRepository(
    private val settings: CloudProviderSettingsStore,
    private val taskStore: CloudProviderTaskStore,
) : CloudProvider {
    override val id: String = PROVIDER
    override val label: String = "Google Gemini"
    override val settingsSummary: String = "Gemini image models · API key"
    override val pathPrefix: String = "gemini:"
    override val baseUrl: String = DEFAULT_BASE
    override val supportsModelDiscovery: Boolean = true
    override val supportsCustomHeaders: Boolean = true

    private val results = ConcurrentHashMap<String, CloudTaskResult>()

    private fun account() = settings.account(id, DEFAULT_BASE)

    override fun configured(): Boolean = account().token.isNotBlank()

    override fun endpoint(): String = account().baseUrl

    override suspend fun discoverModels(): List<String> {
        val account = account()
        return discoverModelIds(settings, account) { discoverMetadata(account) }
    }

    private suspend fun discoverMetadata(account: CloudProviderAccount): List<CloudModelMetadata> {
        if (account.token.isBlank()) throw ComfyException("Configure a Gemini API key first")
        val headers = auth(account)
        val endpoint = "${account.baseUrl.trimEnd('/')}/models"
        val seen = mutableSetOf<String>()
        val models = mutableListOf<CloudModelMetadata>()
        var page = ""
        do {
            val url = if (page.isBlank()) endpoint else "$endpoint?pageToken=" +
                java.net.URLEncoder.encode(page, "UTF-8")
            val root = CloudHttp.getJson(url, headers)
            models += providerModelDirectory(root.optJSONArray("models"), idKey = "name").mapNotNull { entry ->
                val modelId = entry.id.removePrefix("models/")
                val leaf = modelId.lowercase()
                val imageCapable = entry.kind == "image" ||
                    (entry.kind.isBlank() && leaf.startsWith("gemini") && "image" in leaf)
                entry.copy(id = modelId, kind = "image").takeIf { imageCapable || leaf.startsWith("imagen-") }
            }
            page = root.optString("nextPageToken")
        } while (page.isNotBlank() && seen.add(page))
        return models
    }

    override suspend fun catalog(force: Boolean): List<CloudWorkflowSummary> {
        val account = account()
        if (account.token.isBlank()) return emptyList()
        return modelDirectory(settings, account, force) { discoverMetadata(account) }.map { model ->
            model.summary("image", fallbackName = displayName(model.id),
                fallbackDescription = "Google image generation")
        }
    }

    override suspend fun detail(workflowId: String): CloudWorkflowSpec = CloudWorkflowSpec(
        id = workflowId,
        name = displayName(workflowId),
        kind = "image",
        description = if (isImagen(workflowId)) "Text-to-image generation with Google Imagen."
            else "Image generation and reference-based editing with Gemini.",
        rules = rulesFor(workflowId),
    ).withMetadata(settings.modelMetadata(account(), workflowId))

    override suspend fun submit(workflowId: String, body: JSONObject): String {
        val account = account()
        if (account.token.isBlank()) throw ComfyException("Configure a Gemini API key first")
        val base = account.baseUrl.trimEnd('/')
        val headers = auth(account)
        val prompt = body.optString("prompt").trim()
        if (prompt.isBlank()) throw ComfyException("Prompt is required")
        val aspect = body.optString("aspect_ratio").ifBlank { "1:1" }
        val items = if (isImagen(workflowId)) {
            submitImagen(base, workflowId, prompt, aspect, body, headers)
        } else submitGemini(base, workflowId, prompt, aspect, body, headers)
        if (items.isEmpty()) throw ComfyException("Provider returned no images")
        val taskId = UUID.randomUUID().toString()
        val result = CloudTaskResult(taskId, "SUCCESS", items, "", 0)
        results[taskId] = result
        taskStore.saveResult(id, taskId, result)
        return taskId
    }

    private fun submitImagen(
        base: String,
        model: String,
        prompt: String,
        aspect: String,
        body: JSONObject,
        headers: Map<String, String>,
    ): List<CloudResult> {
        val payload = JSONObject()
            .put("instances", JSONArray().put(JSONObject().put("prompt", prompt)))
            .put("parameters", JSONObject()
                .put("sampleCount", body.optInt("sample_count", 1))
                .put("aspectRatio", aspect))
        val response = CloudHttp.postJson("$base/models/${model.removePrefix("models/")}:predict", payload, headers)
        val predictions = response.optJSONArray("predictions")
            ?: throw ComfyException(CloudHttp.errorMessage(response, "Imagen returned no predictions"))
        return (0 until predictions.length()).mapNotNull { index ->
            val prediction = predictions.optJSONObject(index) ?: return@mapNotNull null
            val bytes = prediction.optString("bytesBase64Encoded").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val mime = prediction.optString("mimeType").ifBlank { "image/png" }
            CloudResult("data:$mime;base64,$bytes", "image", mime.substringAfter('/'), "output")
        }
    }

    private fun submitGemini(
        base: String,
        model: String,
        prompt: String,
        aspect: String,
        body: JSONObject,
        headers: Map<String, String>,
    ): List<CloudResult> {
        val imageConfig = JSONObject().put("aspectRatio", aspect)
        body.optString("image_size").takeIf { it.isNotBlank() }?.let {
            imageConfig.put("imageSize", it)
        }
        val parts = buildGeminiImageParts(model, prompt, body) { value ->
            CloudHttp.imagePayload(value)
        }
        val generationConfig = JSONObject()
            .put("responseModalities", JSONArray().put("IMAGE"))
            .put("imageConfig", imageConfig)
        body.opt("seed")?.toString()?.toLongOrNull()?.let { generationConfig.put("seed", it) }
        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", parts),
                ),
            )
            .put("generationConfig", generationConfig)
        val response = CloudHttp.postJson("${base}/models/${model.removePrefix("models/")}:generateContent", payload, headers)
        val responseParts = response.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw ComfyException(CloudHttp.errorMessage(response, "Gemini returned no image parts"))
        return (0 until responseParts.length()).mapNotNull { index ->
            val part = responseParts.optJSONObject(index) ?: return@mapNotNull null
            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                ?: return@mapNotNull null
            val data = inline.optString("data").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val mime = inline.optString("mimeType").ifBlank { "image/png" }
            CloudResult(
                url = "data:$mime;base64,$data",
                type = "image",
                fileType = mime.substringAfter('/', "png"),
                outputType = "output",
            )
        }
    }

    override suspend fun result(taskId: String): CloudTaskResult =
        results[taskId]
            ?: taskStore.result(id, taskId)
            ?: CloudTaskResult(taskId, "FAILED", emptyList(), "Google image result expired", 0)

    override fun forgetTask(taskId: String) {
        results.remove(taskId)
        taskStore.clear(id, taskId)
    }

    private fun auth(account: CloudProviderAccount) =
        settings.mergeHeaders(id, mapOf("x-goog-api-key" to account.token))

    override suspend fun download(url: String): ByteArray = CloudHttp.download(url)

    override suspend fun downloadTo(url: String, target: java.io.File) {
        CloudHttp.downloadTo(url, target)
    }

    private fun isImagen(model: String): Boolean = model.removePrefix("models/").startsWith("imagen-", ignoreCase = true)

    private fun rulesFor(model: String): List<CloudInputRule> = if (isImagen(model)) {
        listOf(
            CloudInputRule("prompt", "prompt", required = true),
            CloudInputRule("aspect_ratio", "enum", required = false, defaultValue = "1:1",
                options = listOf("1:1", "3:4", "4:3", "9:16", "16:9")),
            CloudInputRule("sample_count", "integer", required = false, defaultValue = "1", min = 1.0, max = 4.0),
        )
    } else CloudModelParameters.geminiRules(model)

    private fun displayName(model: String): String = when {
        model == "gemini-3-pro-image" -> "Gemini 3 Pro Image (Nano Banana Pro)"
        model == "gemini-3.1-flash-lite-image" -> "Gemini 3.1 Flash Lite Image"
        model == "gemini-3.1-flash-image" -> "Gemini 3.1 Flash Image"
        else -> model
    }

    companion object {
        const val PROVIDER = "gemini"
        const val DEFAULT_BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/** Pure part builder so multi-image Gemini requests can be regression-tested. */
internal fun buildGeminiImageParts(
    model: String,
    prompt: String,
    body: JSONObject,
    resolveImage: (String) -> Pair<String, ByteArray>?,
): JSONArray {
    val parts = JSONArray()
    if (CloudModelParameters.supportsReferenceImage(model)) {
        CloudModelParameters.referenceImageInputs(body).forEach { (_, value) ->
            val payload = resolveImage(value)
                ?: throw ComfyException("The reference image could not be read")
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", payload.first)
                        .put("data", java.util.Base64.getEncoder().encodeToString(payload.second)),
                )
            )
        }
    }
    parts.put(JSONObject().put("text", prompt))
    return parts
}

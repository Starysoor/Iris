package com.comfyremote.app.data.cloud

import com.comfyremote.app.ComfyException
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * User-configured OpenAI-compatible provider. The base URL and model list are editable;
 * image parameters are inferred from the OpenAI Images contract.
 */
class CustomOpenAiRepository(
    private val settings: CloudProviderSettingsStore,
    private val taskStore: CloudProviderTaskStore,
) : CloudProvider {
    override val id: String = PROVIDER
    override val label: String = "Custom OpenAI API"
    override val settingsSummary: String = "Bring your own OpenAI-compatible endpoint"
    override val pathPrefix: String = "custom:"
    override val baseUrl: String = ""
    override val supportsModelDiscovery: Boolean = true
    override val supportsCustomHeaders: Boolean = true

    private val results = ConcurrentHashMap<String, CloudTaskResult>()

    private fun account() = settings.account(id, "")

    override fun configured(): Boolean = account().baseUrl.isNotBlank()

    override fun endpoint(): String = account().baseUrl

    override suspend fun discoverModels(): List<String> {
        val account = account()
        return discoverModelIds(settings, account) { discoverMetadata(account) }
    }

    private suspend fun discoverMetadata(account: CloudProviderAccount): List<CloudModelMetadata> {
        if (account.baseUrl.isBlank()) return emptyList()
        val root = CloudHttp.getJson(
            "${account.baseUrl.trimEnd('/')}/models",
            auth(account),
        )
        val metadata = providerModelDirectory(root.optJSONArray("data"))
        val picked = mergeCustomModels(account.models, metadata.map { it.id }) +
            metadata.filter { it.kind == "image" }.map { it.id }
        val byId = metadata.associateBy { it.id }
        return picked.distinct().map { byId[it] ?: CloudModelMetadata(it) }
    }

    override suspend fun catalog(force: Boolean): List<CloudWorkflowSummary> {
        val account = account()
        if (account.baseUrl.isBlank()) return emptyList()
        // Auto-discovery keeps a media-name hint, but a manually saved model list
        // is user intent and must not be filtered by Iris keywords.
        return modelDirectory(settings, account, force) { discoverMetadata(account) }.map { model ->
            model.summary(
                kind = model.kind.ifBlank { "image" },
                fallbackDescription = "Custom OpenAI-compatible Images API",
                unavailableReason = unavailableReason(model),
            ).copy(priceType = "custom_api")
        }
    }

    override suspend fun detail(workflowId: String): CloudWorkflowSpec {
        val metadata = settings.modelMetadata(account(), workflowId)
        return CloudWorkflowSpec(
            id = workflowId, name = workflowId, kind = metadata.kind.ifBlank { "image" },
            description = "Custom OpenAI-compatible Images API",
            rules = CloudModelParameters.openAiRules(workflowId),
            unavailableReason = unavailableReason(metadata),
        ).withMetadata(metadata)
    }

    override suspend fun submit(workflowId: String, body: JSONObject): String {
        val account = account()
        if (account.baseUrl.isBlank()) throw ComfyException("Configure the custom API base URL first")
        unavailableReason(settings.modelMetadata(account, workflowId)).takeIf(String::isNotBlank)?.let {
            throw ComfyException(it)
        }
        val headers = auth(account)
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
            listOf("size", "quality", "style", "response_format").forEach { key ->
                body.optString(key).takeIf { it.isNotBlank() }?.let { fields[key] = it }
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
            listOf("size", "quality", "style", "response_format").forEach { key ->
                body.optString(key).takeIf { it.isNotBlank() }?.let { payload.put(key, it) }
            }
            body.optInt("n", 1).takeIf { it > 0 }?.let { payload.put("n", it) }
            CloudHttp.postJson("$base/images/generations", payload, headers)
        }
        val data = response.optJSONArray("data")
            ?: throw ComfyException(CloudHttp.errorMessage(response, "Custom API returned no image data"))
        val results = (0 until data.length()).mapNotNull { index ->
            val item = data.optJSONObject(index) ?: return@mapNotNull null
            val b64 = item.optString("b64_json").takeIf { it.isNotBlank() }
            val url = item.optString("url").takeIf { it.isNotBlank() }
            val resolved = when {
                !url.isNullOrBlank() -> url
                !b64.isNullOrBlank() -> "data:image/png;base64,$b64"
                else -> null
            } ?: return@mapNotNull null
            CloudResult(
                url = resolved,
                type = "image",
                fileType = if (!url.isNullOrBlank()) extensionOf(url).ifBlank { "png" } else "png",
                outputType = "output",
            )
        }
        if (results.isEmpty()) throw ComfyException("Custom API returned no images")
        val taskId = UUID.randomUUID().toString()
        val result = CloudTaskResult(taskId, "SUCCESS", results, "", 0)
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

    private fun auth(account: CloudProviderAccount): Map<String, String> =
        settings.mergeHeaders(
            id,
            if (account.token.isBlank()) emptyMap()
            else mapOf("Authorization" to "Bearer ${account.token}"),
        )

    override suspend fun download(url: String): ByteArray = CloudHttp.download(url)

    override suspend fun downloadTo(url: String, target: java.io.File) {
        CloudHttp.downloadTo(url, target)
    }

    private fun unavailableReason(model: CloudModelMetadata): String =
        if (model.kind.isBlank() || model.kind == "image") "" else
            "This model uses a different API than OpenAI Images."

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }.orEmpty()

    companion object {
        const val PROVIDER = "custom"
    }
}

/**
 * A custom image endpoint may use opaque IDs without "image" in their names.
 * The user-saved list always wins over heuristic discovery filtering, even
 * when "Refresh models" is pressed in settings.
 */
internal fun mergeCustomModels(saved: List<String>, discovered: List<String>): List<String> =
    (saved + discovered.filter(CloudModelParameters::looksLikeMediaModel))
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

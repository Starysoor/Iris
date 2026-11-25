package com.comfyremote.app.data.cloud

import com.comfyremote.app.ComfyException
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Replicate models. Inputs are discovered from the model's OpenAPI input schema. */
class ReplicateRepository(
    private val settings: CloudProviderSettingsStore,
    private val taskStore: CloudProviderTaskStore,
) : CloudProvider {
    override val id: String = PROVIDER
    override val label: String = "Replicate"
    override val settingsSummary: String = "Image & video models · API token"
    override val pathPrefix: String = "replicate:"
    override val baseUrl: String = DEFAULT_BASE
    override val supportsModelDiscovery: Boolean = true
    override val supportsCustomHeaders: Boolean = true

    private data class Pending(val model: String, val kind: String, val endpoint: String)
    private val pending = ConcurrentHashMap<String, Pending>()

    private fun account() = settings.account(id, DEFAULT_BASE)

    override fun configured(): Boolean = account().token.isNotBlank()

    override fun endpoint(): String = account().baseUrl

    /** Replicate exposes a public model catalogue; persist only what this account pulled. */
    override suspend fun discoverModels(): List<String> {
        val account = account()
        return discoverModelIds(settings, account) { discoverMetadata(account) }
    }

    private suspend fun discoverMetadata(account: CloudProviderAccount): List<CloudModelMetadata> {
        if (account.token.isBlank()) throw ComfyException("Configure a Replicate token first")
        val endpoint = "${account.baseUrl.trimEnd('/')}/models".toHttpUrl()
        val headers = auth(account)
        val seen = mutableSetOf<String>()
        val models = mutableListOf<CloudModelMetadata>()
        var page = endpoint
        while (seen.add(page.toString())) {
            coroutineContext.ensureActive()
            val root = CloudHttp.getJson(page.toString(), headers)
            val results = root.optJSONArray("results") ?: break
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val owner = item.optString("owner").takeIf(String::isNotBlank) ?: continue
                val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
                val model = providerModelMetadata("$owner/$name", item)
                if (model.kind in setOf("image", "video", "audio") ||
                    (model.kind.isBlank() && CloudModelParameters.looksLikeMediaModel(model.id))) {
                    models += model
                }
            }
            val next = root.optString("next").takeUnless { it.isBlank() || it == "null" } ?: break
            val url = endpoint.resolve(next) ?: break
            if (url.scheme != endpoint.scheme || url.host != endpoint.host || url.port != endpoint.port) {
                throw ComfyException("Replicate catalogue pagination returned another endpoint")
            }
            page = url
        }
        return models
    }

    /** Replicate has no account-specific catalogue; validate the token against the account API. */
    override suspend fun check(): CloudHealth {
        if (!configured()) {
            return CloudHealth(CloudHealthStatus.NOT_CONFIGURED, checkedAt = System.currentTimeMillis())
        }
        return try {
            val account = account()
            CloudHttp.getJson("${account.baseUrl.trimEnd('/')}/account", auth(account))
            CloudHealth(
                status = CloudHealthStatus.READY,
                modelCount = account.models.size,
                checkedAt = System.currentTimeMillis(),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            classifyCloudFailure(error).copy(checkedAt = System.currentTimeMillis())
        }
    }

    override suspend fun catalog(force: Boolean): List<CloudWorkflowSummary> {
        val account = account()
        if (account.token.isBlank()) return emptyList()
        return modelDirectory(settings, account, force) { discoverMetadata(account) }.map { model ->
            model.summary(kindOf(model), fallbackName = model.id.substringAfterLast('/'))
        }
    }

    override suspend fun detail(workflowId: String): CloudWorkflowSpec {
        val account = account()
        val cached = settings.modelMetadata(account, workflowId)
        val fallback = CloudWorkflowSpec(
            id = workflowId,
            name = workflowId.substringAfterLast('/'),
            kind = kindOf(cached),
            description = "Replicate model",
            rules = fallbackRules(),
        ).withMetadata(cached)
        if (account.token.isBlank()) return fallback
        return try {
            val model = CloudHttp.getJson(
                "${account.baseUrl.trimEnd('/')}/models/$workflowId",
                auth(account),
            )
            val metadata = providerModelMetadata(workflowId, model)
            val detail = fallback.withMetadata(metadata).copy(kind = kindOf(metadata))
            val inputSchema = model.optJSONObject("latest_version")
                ?.optJSONObject("openapi_schema")
                ?.optJSONObject("components")
                ?.optJSONObject("schemas")
                ?.optJSONObject("Input")
                ?: return detail
            val rules = ReplicateSchema.rules(inputSchema)
            if (rules.isEmpty()) detail else detail.copy(rules = rules)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fallback.copy(unavailableReason = "Could not load Replicate's input schema for $workflowId.")
        }
    }

    override suspend fun submit(workflowId: String, body: JSONObject): String {
        val account = account()
        if (account.token.isBlank()) throw ComfyException("Configure a Replicate token first")
        val payload = JSONObject().put("input", body)
        val response = CloudHttp.postJson(
            "${account.baseUrl.trimEnd('/')}/models/$workflowId/predictions",
            payload,
            auth(account),
        )
        val taskId = response.optString("id").takeIf { it.isNotBlank() }
            ?: throw ComfyException(CloudHttp.errorMessage(response, "Replicate returned no prediction id"))
        val snapshot = Pending(workflowId, kindOf(settings.modelMetadata(account, workflowId)), account.baseUrl.trimEnd('/'))
        try {
            taskStore.savePending(
                id,
                taskId,
                JSONObject()
                    .put("model", snapshot.model)
                    .put("kind", snapshot.kind)
                    .put("endpoint", snapshot.endpoint),
            )
        } catch (error: Exception) {
            // The remote task may already be billable; expose its id and never silently retry.
            throw java.io.IOException(
                "Replicate task $taskId was submitted but recovery data could not be saved; do not resubmit automatically",
                error,
            )
        }
        pending[taskId] = snapshot
        return taskId
    }

    override suspend fun result(taskId: String): CloudTaskResult {
        taskStore.result(id, taskId)?.let { return it }
        val account = account()
        val snapshot = pending[taskId] ?: taskStore.pending(id, taskId)?.let { stored ->
            Pending(
                model = stored.optString("model"),
                kind = stored.optString("kind"),
                endpoint = stored.optString("endpoint"),
            )
        }?.also { pending.putIfAbsent(taskId, it) }
        val response = CloudHttp.getJson(
            replicatePredictionUrl(snapshot?.endpoint, account.baseUrl, taskId),
            auth(account),
        )
        val status = response.optString("status")
        val result = when (status) {
            "succeeded" -> {
                val kind = snapshot?.kind?.takeIf { it.isNotBlank() }
                    ?: kindOf(settings.modelMetadata(account, response.optString("model")))
                val results = outputs(response.opt("output"), kind)
                CloudTaskResult(taskId, "SUCCESS", results, "", 0)
            }
            "canceled", "cancelled" -> CloudTaskResult(
                taskId,
                "CANCELED",
                emptyList(),
                response.optString("error").ifBlank { "Replicate prediction $status" },
                0,
            )
            "failed" -> CloudTaskResult(
                taskId,
                "FAILED",
                emptyList(),
                response.optString("error").ifBlank { "Replicate prediction $status" },
                0,
            )
            else -> CloudTaskResult(taskId, "RUNNING", emptyList(), status, 0)
        }
        if (result.status == "SUCCESS") taskStore.saveResult(id, taskId, result)
        return result
    }

    override fun forgetTask(taskId: String) {
        pending.remove(taskId)
        taskStore.clear(id, taskId)
    }

    override suspend fun download(url: String): ByteArray = CloudHttp.download(url)

    override suspend fun downloadTo(url: String, target: java.io.File) {
        CloudHttp.downloadTo(url, target)
    }

    private fun outputs(output: Any?, kind: String): List<CloudResult> {
        val urls = when (output) {
            is String -> listOf(output)
            is JSONArray -> (0 until output.length()).mapNotNull { output.optString(it).takeIf(String::isNotBlank) }
            is JSONObject -> listOfNotNull(
                output.optString("url").takeIf(String::isNotBlank),
                output.optString("video").takeIf(String::isNotBlank),
                output.optString("image").takeIf(String::isNotBlank),
            )
            else -> emptyList()
        }
        return urls.map { url ->
            CloudResult(
                url = url,
                type = kind,
                fileType = extensionOf(url).ifBlank { if (kind == "video") "mp4" else "png" },
                outputType = "output",
            )
        }
    }

    private fun fallbackRules(): List<CloudInputRule> = listOf(
        CloudInputRule("prompt", "prompt", required = true),
        CloudInputRule("aspect_ratio", "enum", required = false, defaultValue = "1:1", options = listOf("1:1", "16:9", "9:16", "4:3", "3:4")),
        CloudInputRule("num_outputs", "integer", required = false, defaultValue = "1", min = 1.0, max = 4.0),
        CloudInputRule("seed", "integer", required = false),
    )

    private fun auth(account: CloudProviderAccount): Map<String, String> =
        settings.mergeHeaders(id, mapOf("Authorization" to "Bearer ${account.token}"))

    private fun kindOf(model: CloudModelMetadata): String =
        model.kind.ifBlank { CloudModelParameters.mediaKindHint(model.id) }.ifBlank { "image" }

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }.orEmpty()

    companion object {
        const val PROVIDER = "replicate"
        const val DEFAULT_BASE = "https://api.replicate.com/v1"
    }
}

/** Keep polling an in-flight prediction on the endpoint used at submit time. */
internal fun replicatePredictionUrl(
    submittedEndpoint: String?,
    currentEndpoint: String,
    taskId: String,
): String {
    val base = submittedEndpoint?.takeIf { it.isNotBlank() } ?: currentEndpoint
    return "${base.trimEnd('/')}/predictions/$taskId"
}

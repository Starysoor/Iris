package com.comfyremote.app.data.cloud

import com.comfyremote.app.ComfyException
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AutoDL.Art model API (separate from ComfyUI workflow tokens).
 * Image models use OpenAI Images; video models use the vendor protocol proxied by AutoDL.
 */
class AutoDlModelRepository(
    private val settings: CloudProviderSettingsStore,
    private val taskStore: CloudProviderTaskStore,
) : CloudProvider {
    override val id: String = PROVIDER
    override val label: String = "AutoDL Models"
    override val settingsSummary: String = "AutoDL model API · image and video models"
    override val pathPrefix: String = PREFIX
    override val baseUrl: String = BASE
    override val supportsModelDiscovery: Boolean = true
    override val supportsCustomHeaders: Boolean = true

    private data class Pending(val family: String, val submitUrl: String, val base: String)
    private val pending = ConcurrentHashMap<String, Pending>()

    private val imageApi = OpenAiCompatibleImageRepository(
        settings = settings,
        taskStore = taskStore,
        id = PROVIDER,
        label = label,
        settingsSummary = settingsSummary,
        pathPrefix = PREFIX,
        baseUrl = BASE,
    )

    private fun account() = settings.account(id, BASE)

    override fun configured(): Boolean = account().token.isNotBlank()

    override fun endpoint(): String = account().baseUrl

    override suspend fun discoverModels(): List<String> {
        val account = account()
        return discoverModelIds(settings, account) { discoverMedia(account) }
    }

    private suspend fun discoverMedia(account: CloudProviderAccount): List<CloudModelMetadata> =
        imageApi.discoverMetadata(account).filter {
            it.kind in setOf("image", "video", "audio") ||
                (it.kind.isBlank() && CloudModelParameters.looksLikeMediaModel(it.id))
        }

    override suspend fun catalog(force: Boolean): List<CloudWorkflowSummary> {
        val account = account()
        if (account.token.isBlank()) return emptyList()
        return modelDirectory(settings, account, force) { discoverMedia(account) }.map { model ->
            val family = autoDlModelFamily(model.id, model.kind)
            model.summary(
                kind = modelKind(model, family),
                fallbackDescription = modelDescription(model.id, family),
                unavailableReason = unavailableReason(model.id, family),
            )
        }
    }

    override suspend fun detail(workflowId: String): CloudWorkflowSpec {
        val model = settings.modelMetadata(account(), workflowId)
        val family = autoDlModelFamily(workflowId, model.kind)
        if (family == "image") {
            val imageSpec = imageApi.detail(workflowId)
            val documentedSizes = autoDlImageSizes(workflowId)
            return imageSpec.copy(
                description = model.description.ifBlank { modelDescription(workflowId, family) },
                rules = if (documentedSizes.isEmpty()) imageSpec.rules else listOf(
                    CloudInputRule("prompt", "prompt", required = true),
                    CloudInputRule("size", "enum", required = false, defaultValue = documentedSizes.first(),
                        options = documentedSizes),
                ),
                referenceUrl = when (workflowId.substringAfterLast('/').lowercase()) {
                    "qwen-image" -> "https://autodl.art/docs/Qwen-Image/"
                    "z-image-turbo" -> "https://autodl.art/docs/Z-Image-Turbo/"
                    else -> imageSpec.referenceUrl
                },
            )
        }
        return CloudWorkflowSpec(
            id = workflowId,
            name = workflowId,
            kind = modelKind(model, family),
            description = modelDescription(workflowId, family),
            rules = if (family.isBlank()) emptyList() else videoRules(family, workflowId),
            unavailableReason = unavailableReason(workflowId, family),
        ).withMetadata(model)
    }

    override suspend fun submit(workflowId: String, body: JSONObject): String {
        val account = account()
        val family = autoDlModelFamily(workflowId, settings.modelMetadata(account, workflowId).kind)
        if (family == "image") return imageApi.submit(workflowId, body)
        if (family.isBlank()) throw ComfyException(unavailableReason(workflowId, family))
        if (account.token.isBlank()) throw ComfyException("Configure an AutoDL model API token first")
        val headers = settings.mergeHeaders(id, mapOf("Authorization" to "Bearer ${account.token}"))
        val base = account.baseUrl.trimEnd('/')
        val (submitUrl, payload) = when (family) {
            "wan" -> "$base/ali/api/v1/services/aigc/video-generation/video-synthesis" to
                buildAutoDlWanPayload(workflowId, body)
            "h3" -> "$base/minimax/v2/video_generation" to miniMaxPayload(workflowId, body)
            "minimax" -> "$base/minimax/v1/video_generation" to miniMaxPayload(workflowId, body)
            "seedance" -> "$base/ark/v3/contents/generations/tasks" to seedancePayload(workflowId, body)
            "kling" -> klingSubmit(workflowId, body, base)
            else -> throw ComfyException("Unsupported AutoDL model $workflowId")
        }
        val response = CloudHttp.requestJson(
            method = "POST",
            url = submitUrl,
            body = payload,
            headers = if (family == "wan") headers + mapOf("X-DashScope-Async" to "enable") else headers,
        )
        val taskId = when (family) {
            "wan" -> response.optJSONObject("output")?.optString("task_id")
            "h3", "minimax" -> response.optString("task_id")
            "seedance" -> response.optString("id")
            "kling" -> response.optJSONObject("data")?.optString("task_id")
            else -> null
        }?.takeIf { it.isNotBlank() }
            ?: throw ComfyException(CloudHttp.errorMessage(response, "AutoDL returned no task id"))
        taskStore.saveAcceptedTask(
            id,
            taskId,
            JSONObject().put("family", family).put("submitUrl", submitUrl).put("base", base),
            label,
        )
        pending[taskId] = Pending(family, submitUrl, base)
        return taskId
    }

    override suspend fun result(taskId: String): CloudTaskResult {
        taskStore.result(id, taskId)?.let { return it }
        val task = pending[taskId] ?: taskStore.pending(id, taskId)?.let { stored ->
            val family = stored.optString("family").takeIf { it.isNotBlank() } ?: return@let null
            Pending(
                family = family,
                submitUrl = stored.optString("submitUrl"),
                base = stored.optString("base"),
            )
        } ?: return imageApi.result(taskId)
        val account = account()
        val headers = settings.mergeHeaders(id, mapOf("Authorization" to "Bearer ${account.token}"))
        val base = task.base.ifBlank { account.baseUrl.trimEnd('/') }
        val result = when (task.family) {
            "wan" -> wanResult(base, taskId, headers)
            "h3", "minimax" -> miniMaxResult(base, taskId, headers)
            "seedance" -> seedanceResult(base, taskId, headers)
            "kling" -> klingResult(task.submitUrl, taskId, headers)
            else -> CloudTaskResult(taskId, "FAILED", emptyList(), "Unsupported task", 0)
        }
        if (result.status == "SUCCESS") taskStore.saveResult(id, taskId, result)
        return result
    }

    override fun forgetTask(taskId: String) {
        pending.remove(taskId)
        // Image results (including base64 data) live in the delegated OpenAI repo.
        imageApi.forgetTask(taskId)
        taskStore.clear(id, taskId)
    }

    override suspend fun download(url: String): ByteArray = CloudHttp.download(url)

    override suspend fun downloadTo(url: String, target: java.io.File) {
        CloudHttp.downloadTo(url, target)
    }

    private fun miniMaxPayload(model: String, body: JSONObject): JSONObject {
        val payload = JSONObject().put("model", model).put("prompt", body.optString("prompt"))
        body.optString("resolution").takeIf { it.isNotBlank() }?.let { payload.put("resolution", it) }
        body.opt("duration")?.toString()?.toIntOrNull()?.let { payload.put("duration", it) }
        body.optString("first_frame_image").takeIf { it.isNotBlank() }?.let {
            payload.put("first_frame_image", it)
        }
        return payload
    }

    private fun seedancePayload(model: String, body: JSONObject): JSONObject {
        val content = JSONArray()
        body.optString("prompt").takeIf { it.isNotBlank() }?.let {
            content.put(JSONObject().put("type", "text").put("text", it))
        }
        body.optString("first_frame_image").takeIf { it.isNotBlank() }?.let {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", it))
                    .put("role", "first_frame")
            )
        }
        val payload = JSONObject().put("model", model).put("content", content)
        body.optString("ratio").takeIf { it.isNotBlank() }?.let { payload.put("ratio", it) }
        body.optString("resolution").takeIf { it.isNotBlank() }?.let { payload.put("resolution", it) }
        body.opt("duration")?.toString()?.toIntOrNull()?.let { payload.put("duration", it) }
        if (body.has("generate_audio")) payload.put("generate_audio", body.optBoolean("generate_audio"))
        if (body.has("watermark")) payload.put("watermark", body.optBoolean("watermark"))
        body.opt("seed")?.let { payload.put("seed", it) }
        return payload
    }

    private fun klingSubmit(model: String, body: JSONObject, base: String): Pair<String, JSONObject> {
        val omni = model.contains("omni") || model.contains("o1")
        val hasImage = body.optString("first_frame_image").isNotBlank()
        val path = when {
            omni -> "/kling/v1/videos/omni-video"
            hasImage -> "/kling/v1/videos/image2video"
            else -> "/kling/v1/videos/text2video"
        }
        val payload = JSONObject()
            .put("model_name", model)
            .put("prompt", body.optString("prompt"))
        body.optString("mode").takeIf { it.isNotBlank() }?.let { payload.put("mode", it) }
        body.optString("aspect_ratio").takeIf { it.isNotBlank() }?.let { payload.put("aspect_ratio", it) }
        body.opt("duration")?.toString()?.takeIf { it.isNotBlank() }?.let { payload.put("duration", it) }
        body.optString("first_frame_image").takeIf { it.isNotBlank() }?.let {
            payload.put("image", it)
        }
        return "$base$path" to payload
    }

    private suspend fun wanResult(base: String, taskId: String, headers: Map<String, String>): CloudTaskResult {
        val response = CloudHttp.getJson("$base/ali/api/v1/tasks/$taskId", headers)
        val output = response.optJSONObject("output") ?: response
        val status = output.optString("task_status")
        return when (status.uppercase()) {
            "SUCCEEDED", "SUCCESS" -> {
                val url = output.optString("video_url")
                CloudTaskResult(taskId, "SUCCESS", videoResults(url), "", 0)
            }
            "CANCELED", "CANCELLED" -> CloudTaskResult(
                taskId, "CANCELED", emptyList(),
                output.optString("message").ifBlank { "Wan video generation cancelled" }, 0,
            )
            "FAILED" -> CloudTaskResult(
                taskId, "FAILED", emptyList(),
                output.optString("message").ifBlank { "Wan video generation failed" }, 0,
            )
            else -> CloudTaskResult(taskId, "RUNNING", emptyList(), status, 0)
        }
    }

    private suspend fun miniMaxResult(base: String, taskId: String, headers: Map<String, String>): CloudTaskResult {
        val response = CloudHttp.getJson("$base/minimax/v1/query/video_generation/$taskId", headers)
        val status = response.optString("status").ifBlank { response.optString("task_status") }
        return when (status.lowercase()) {
            "success", "succeeded" -> {
                val fileId = response.optString("file_id")
                val file = if (fileId.isNotBlank()) {
                    CloudHttp.getJson("$base/minimax/v1/files/retrieve?file_id=$fileId", headers)
                } else response
                val url = file.optJSONObject("file")?.optString("download_url")
                    ?: file.optString("download_url")
                CloudTaskResult(taskId, "SUCCESS", videoResults(url), "", 0)
            }
            "cancel", "cancelled", "canceled" -> CloudTaskResult(
                taskId, "CANCELED", emptyList(),
                response.optString("status_msg").ifBlank { "MiniMax video generation cancelled" }, 0,
            )
            "fail", "failed" -> CloudTaskResult(
                taskId, "FAILED", emptyList(),
                response.optString("status_msg").ifBlank { "MiniMax video generation failed" }, 0,
            )
            else -> CloudTaskResult(taskId, "RUNNING", emptyList(), status, 0)
        }
    }

    private suspend fun seedanceResult(base: String, taskId: String, headers: Map<String, String>): CloudTaskResult {
        val response = CloudHttp.getJson("$base/ark/v3/contents/generations/tasks/$taskId", headers)
        return when (response.optString("status").lowercase()) {
            "succeeded", "success" -> {
                val url = response.optJSONObject("content")?.optString("video_url")
                CloudTaskResult(taskId, "SUCCESS", videoResults(url), "", 0)
            }
            "cancelled", "canceled" -> CloudTaskResult(
                taskId, "CANCELED", emptyList(),
                response.optJSONObject("error")?.optString("message").orEmpty().ifBlank {
                    "Seedance video generation cancelled"
                }, 0,
            )
            "failed", "expired" -> CloudTaskResult(
                taskId, "FAILED", emptyList(),
                response.optJSONObject("error")?.optString("message").orEmpty().ifBlank {
                    "Seedance video generation failed"
                }, 0,
            )
            else -> CloudTaskResult(taskId, "RUNNING", emptyList(), response.optString("status"), 0)
        }
    }

    private suspend fun klingResult(submitUrl: String, taskId: String, headers: Map<String, String>): CloudTaskResult {
        val response = CloudHttp.getJson("$submitUrl/$taskId", headers)
        val data = response.optJSONObject("data") ?: response
        return when (data.optString("task_status").lowercase()) {
            "succeed", "success", "succeeded" -> {
                val videos = data.optJSONObject("task_result")?.optJSONArray("videos") ?: JSONArray()
                val results = (0 until videos.length()).mapNotNull { index ->
                    val url = videos.optJSONObject(index)?.optString("url")?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    CloudResult(url, "video", extensionOf(url).ifBlank { "mp4" }, "output")
                }
                CloudTaskResult(taskId, "SUCCESS", results, "", 0)
            }
            "cancelled", "canceled" -> CloudTaskResult(
                taskId, "CANCELED", emptyList(),
                data.optString("task_status_msg").ifBlank { "Kling video generation cancelled" }, 0,
            )
            "failed" -> CloudTaskResult(
                taskId, "FAILED", emptyList(),
                data.optString("task_status_msg").ifBlank { "Kling video generation failed" }, 0,
            )
            else -> CloudTaskResult(taskId, "RUNNING", emptyList(), data.optString("task_status"), 0)
        }
    }

    private fun videoResults(url: String?): List<CloudResult> {
        if (url.isNullOrBlank()) return emptyList()
        return listOf(
            CloudResult(
                url = url,
                type = "video",
                fileType = extensionOf(url).ifBlank { "mp4" },
                outputType = "output",
            )
        )
    }

    private fun extensionOf(url: String): String =
        url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..5 }.orEmpty()

    private fun modelKind(model: CloudModelMetadata, family: String): String = when (family) {
        "image" -> "image"
        "" -> model.kind.ifBlank { CloudModelParameters.mediaKindHint(model.id) }
        else -> "video"
    }

    private fun modelDescription(model: String, family: String): String = when (family) {
        "wan" -> if (wan27ImageToVideo(model)) {
            "Wan 2.7 image-to-video via AutoDL. Choose a first frame and, optionally, an ending frame."
        } else "Wan video generation via AutoDL. The input mode depends on the model variant."
        "h3" -> "MiniMax H3 video generation via AutoDL. Use a prompt or a first-frame image."
        "minimax" -> "MiniMax Hailuo video generation via AutoDL. Duration and resolution are model-specific."
        "seedance" -> "Seedance video generation via AutoDL. Configure the frame, aspect ratio, duration and audio."
        "kling" -> "Kling video generation via AutoDL. Supports the documented text and image submission modes."
        "image" -> if (CloudModelParameters.supportsReferenceImage(model)) {
            "Image generation and reference-based editing via the AutoDL Images API."
        } else "Text-to-image generation via the AutoDL Images API."
        else -> "Model from the AutoDL catalogue."
    }

    private fun unavailableReason(model: String, family: String): String =
        if (family.isBlank()) "AutoDL lists $model, but its generation endpoint is not identified." else ""

    private fun videoRules(family: String, model: String): List<CloudInputRule> {
        val wanKeyframes = family == "wan" && wan27ImageToVideo(model)
        val rules = mutableListOf(CloudInputRule("prompt", "prompt", required = !wanKeyframes))
        when (family) {
            "wan" -> {
                rules += CloudInputRule(
                    "resolution", "enum", required = false, defaultValue = "720P",
                    options = if (wanKeyframes) listOf("720P", "1080P") else listOf("480P", "720P", "1080P"),
                )
                rules += CloudInputRule(
                    "duration", "integer", required = false, defaultValue = "5",
                    min = if (wanKeyframes) 2.0 else 1.0,
                    max = if (wanKeyframes) 15.0 else 30.0,
                )
                rules += CloudInputRule("first_frame_image", "image", required = wanKeyframes)
                if (wanKeyframes) rules += CloudInputRule("last_frame_image", "image", required = false)
                rules += CloudInputRule("negative_prompt", "string", required = false)
            }
            "h3" -> {
                rules += CloudInputRule(
                    "resolution", "enum", required = false, defaultValue = "768P",
                    options = listOf("768P", "2K"),
                )
                rules += CloudInputRule(
                    "duration", "integer", required = false, defaultValue = "6",
                    min = 6.0, max = 10.0,
                )
                rules += CloudInputRule("first_frame_image", "image", required = false)
            }
            "minimax" -> {
                val durations = if (model.contains("2.3-Fast", ignoreCase = true)) listOf("6") else listOf("6", "10")
                rules += CloudInputRule(
                    "resolution", "enum", required = false, defaultValue = "768P",
                    options = if (durations == listOf("6")) listOf("768P") else listOf("768P", "1080P"),
                )
                rules += CloudInputRule(
                    "duration", "enum", required = false, defaultValue = durations.first(), options = durations,
                )
                rules += CloudInputRule(
                    "first_frame_image", "image",
                    required = model.contains("2.3-Fast", ignoreCase = true),
                )
            }
            "seedance" -> {
                rules += CloudInputRule(
                    "resolution", "enum", required = false, defaultValue = "720p",
                    options = listOf("480p", "720p", "1080p"),
                )
                rules += CloudInputRule(
                    "ratio", "enum", required = false, defaultValue = "adaptive",
                    options = listOf("adaptive", "16:9", "9:16", "1:1", "4:3", "3:4", "21:9"),
                )
                rules += CloudInputRule(
                    "duration", "integer", required = false, defaultValue = "5",
                    min = 1.0, max = 15.0,
                )
                rules += CloudInputRule("first_frame_image", "image", required = false)
                rules += CloudInputRule("generate_audio", "boolean", required = false, defaultValue = "true")
                rules += CloudInputRule("watermark", "boolean", required = false, defaultValue = "false")
                rules += CloudInputRule("seed", "integer", required = false)
            }
            "kling" -> {
                rules += CloudInputRule(
                    "mode", "enum", required = false, defaultValue = "std", options = listOf("std", "pro"),
                )
                rules += CloudInputRule(
                    "duration", "enum", required = false, defaultValue = "5", options = listOf("5", "10"),
                )
                rules += CloudInputRule(
                    "aspect_ratio", "enum", required = false, defaultValue = "16:9",
                    options = listOf("16:9", "9:16", "1:1"),
                )
                rules += CloudInputRule("first_frame_image", "image", required = false)
            }
        }
        return rules
    }

    companion object {
        const val PROVIDER = "autodl_model"
        const val BASE = "https://www.autodl.art/api/v1"
        const val PREFIX = "autodl-model:"
    }
}

private fun wan27ImageToVideo(model: String): Boolean =
    model.substringAfterLast('/').lowercase().replace('_', '-').startsWith("wan2.7-i2v")

internal fun autoDlImageSizes(model: String): List<String> = when (model.substringAfterLast('/').lowercase()) {
    "qwen-image" -> listOf("1328x1328", "1664x928")
    "z-image-turbo" -> listOf("1024x1024")
    else -> emptyList()
}

internal fun buildAutoDlWanPayload(model: String, body: JSONObject): JSONObject {
    val input = JSONObject()
    for (name in listOf("prompt", "negative_prompt")) {
        body.optString(name).takeIf { it.isNotBlank() }?.let { input.put(name, it) }
    }
    val media = JSONArray()
    body.optString("first_frame_image").takeIf { it.isNotBlank() }?.let {
        media.put(JSONObject().put("type", "first_frame").put("url", it))
    }
    if (wan27ImageToVideo(model)) {
        body.optString("last_frame_image").takeIf { it.isNotBlank() }?.let {
            media.put(JSONObject().put("type", "last_frame").put("url", it))
        }
    }
    if (media.length() > 0) input.put("media", media)
    val params = JSONObject()
    body.optString("resolution").takeIf { it.isNotBlank() }?.let { params.put("resolution", it) }
    body.opt("duration")?.toString()?.toIntOrNull()?.let { params.put("duration", it) }
    return JSONObject().put("model", model).put("input", input).put("parameters", params)
}

internal fun autoDlModelFamily(model: String, declaredKind: String = ""): String {
    val leaf = model.substringAfterLast('/').lowercase().replace('_', '-')
    if (declaredKind == "image") return "image"
    if (declaredKind in setOf("text", "audio")) return ""
    val hint = CloudModelParameters.mediaKindHint(model)
    if (declaredKind.isBlank() && hint == "image") return "image"
    return when {
        leaf.startsWith("wan2") || leaf.startsWith("wan3") -> "wan"
        leaf.startsWith("minimax-h3") -> "h3"
        leaf.startsWith("minimax-hailuo") -> "minimax"
        "seedance" in leaf -> "seedance"
        leaf.startsWith("kling") -> "kling"
        else -> ""
    }
}

package com.comfyremote.app.data.cloud

import com.comfyremote.app.WorkflowEntry
import com.comfyremote.app.R
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.ui.AppStrings
import org.json.JSONArray
import org.json.JSONObject

/** AutoDL.Art hosted ComfyUI workflow API constants. */
object AutoDlConfig {
    const val PROVIDER = "autodl"
    const val BASE_URL = "https://autodl.art"
    const val PATH_PREFIX = "autodl:"
}

/** One price tier, usually selected by output resolution. */
data class CloudPriceTier(
    val resolution: String,
    val price: Int,
    val offPeakPrice: Int? = null,
)

/**
 * AutoDL billing metadata. `price` values are platform billing units, not a
 * currency; the UI always labels them as platform-provided estimates.
 */
data class CloudPricing(
    val mode: String, // per_call | per_second
    val tiers: List<CloudPriceTier> = emptyList(),
    val price: Int? = null,
    val offPeakPrice: Int? = null,
    val minimumPrice: Int? = null,
) {
    val label: String
        get() = when {
            mode == "per_second" -> buildString {
                append(AppStrings.get(R.string.price_per_second, (price ?: 0).toString()))
                minimumPrice?.let { append(AppStrings.get(R.string.price_minimum, it.toString())) }
            }
            tiers.isNotEmpty() -> {
                val min = tiers.minOf { it.price }
                val max = tiers.maxOf { it.price }
                if (min == max) AppStrings.get(R.string.price_per_run, min.toString())
                else AppStrings.get(R.string.price_range_per_run, min.toString(), max.toString())
            }
            price != null -> AppStrings.get(R.string.price_per_run, price.toString())
            else -> AppStrings.get(R.string.price_platform)
        }

    fun tierFor(resolution: String?): CloudPriceTier? {
        if (tiers.isEmpty()) return null
        val clean = resolution.orEmpty()
            .replace("竖", "")
            .replace("横", "")
            .replace(Regex("\\(.*\\)"), "")
            .trim()
        return tiers.firstOrNull { it.resolution.equals(clean, ignoreCase = true) }
            ?: tiers.firstOrNull { clean.startsWith(it.resolution, ignoreCase = true) }
            ?: tiers.firstOrNull { it.resolution.startsWith(clean, ignoreCase = true) && clean.isNotBlank() }
    }

    fun estimate(resolution: String?, durationSeconds: Int?): Int? = when (mode) {
        "per_second" -> {
            val unit = price ?: return null
            val seconds = (durationSeconds ?: 1).coerceAtLeast(1)
            (unit * seconds).coerceAtLeast(minimumPrice ?: 0)
        }
        else -> tierFor(resolution)?.price ?: tiers.minOfOrNull { it.price } ?: price
    }

    fun hasOffPeak(): Boolean = offPeakPrice != null || tiers.any { it.offPeakPrice != null }
}

/** One workflow in AutoDL's public catalogue. */
data class CloudWorkflowSummary(
    val id: String,
    val name: String,
    val description: String,
    val kind: String,
    val priceType: String,
    val coverUrl: String = "",
    val author: String = "",
    val usageCount: Int = 0,
    val pricing: CloudPricing? = null,
    val version: String = "",
    val unavailableReason: String = "",
) {
    fun toEntry(
        pathPrefix: String = AutoDlConfig.PATH_PREFIX,
        providerLabel: String = "AutoDL.Art",
        providerId: String = AutoDlConfig.PROVIDER,
    ): WorkflowEntry = WorkflowEntry(
        path = pathPrefix + id,
        name = name,
        sizeKb = "",
        cloud = true,
        kind = kind,
        description = description,
        priceType = priceType,
        priceLabel = pricing?.label.orEmpty(),
        providerLabel = providerLabel,
        providerId = providerId,
        version = version,
        unavailableReason = unavailableReason,
    )
}

/** One input rule exposed by a hosted workflow. */
data class CloudInputRule(
    val name: String,
    val type: String,
    val required: Boolean,
    val defaultValue: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val options: List<String> = emptyList(),
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val referenceRole: ReferenceRole = ReferenceRole.AUTO,
    val acceptedMediaTypes: List<String> = emptyList(),
    val label: String = "",
    val description: String = "",
) {
    val isImage: Boolean get() = type == "image"
    val isAudio: Boolean get() = type == "audio"
    val isVideo: Boolean get() = type == "video"
    val isMedia: Boolean get() = isImage || isAudio || isVideo
    val isText: Boolean get() = type == "prompt" || type == "string"
    val isSeed: Boolean get() = name == "seed" || name.endsWith("_seed")

    /** AutoDL uses a real blank asset URL as the default for optional media slots. */
    val effectiveDefault: String?
        get() = defaultValue?.takeUnless { it.startsWith("default_local_path:", ignoreCase = true) }
}

/** Full hosted workflow definition, persisted inside GenerationRequest.convertedPrompt. */
data class CloudWorkflowSpec(
    val id: String,
    val name: String,
    val kind: String,
    val description: String,
    val content: String = "",
    val rules: List<CloudInputRule>,
    val pricing: CloudPricing? = null,
    val version: String = "",
    val referenceUrl: String = "",
    val unavailableReason: String = "",
) {
    fun rule(name: String): CloudInputRule? = rules.firstOrNull { it.name == name }

    fun toJson(): JSONObject = JSONObject()
        .put("__cloud", AutoDlConfig.PROVIDER)
        .put("workflowId", id)
        .put("name", name)
        .put("kind", kind)
        .put("description", description)
        .put("content", content)
        .put("version", version)
        .put("referenceUrl", referenceUrl)
        .put("unavailableReason", unavailableReason)
        .put("pricing", pricing?.let(::pricingToJson) ?: JSONObject.NULL)
        .put("rules", JSONArray().apply { rules.forEach { put(ruleToJson(it)) } })

    companion object {
        fun fromJson(json: JSONObject): CloudWorkflowSpec? {
            val provider = json.opt("__cloud")
            if (provider != true && provider?.toString() != AutoDlConfig.PROVIDER) return null
            val id = json.optString("workflowId").takeIf { it.isNotBlank() } ?: return null
            val rules = mutableListOf<CloudInputRule>()
            val array = json.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { rules += ruleFromJson(it) }
            }
            return CloudWorkflowSpec(
                id = id,
                name = json.optString("name", id),
                kind = json.optString("kind", "video"),
                description = json.optString("description"),
                content = json.optString("content"),
                rules = rules,
                pricing = json.optJSONObject("pricing")?.let(::pricingFromJson),
                version = json.optString("version"),
                referenceUrl = json.optString("referenceUrl"),
                unavailableReason = json.optString("unavailableReason"),
            )
        }

        fun pricingToJson(pricing: CloudPricing): JSONObject = JSONObject()
            .put("mode", pricing.mode)
            .put("price", pricing.price ?: JSONObject.NULL)
            .put("offPeakPrice", pricing.offPeakPrice ?: JSONObject.NULL)
            .put("minimumPrice", pricing.minimumPrice ?: JSONObject.NULL)
            .put("tiers", JSONArray().apply {
                pricing.tiers.forEach { tier ->
                    put(
                        JSONObject()
                            .put("resolution", tier.resolution)
                            .put("price", tier.price)
                            .put("offPeakPrice", tier.offPeakPrice ?: JSONObject.NULL)
                    )
                }
            })

        fun pricingFromJson(json: JSONObject): CloudPricing = CloudPricing(
            mode = json.optString("mode", "per_call"),
            price = if (json.has("price") && !json.isNull("price")) json.optInt("price") else null,
            offPeakPrice = if (json.has("offPeakPrice") && !json.isNull("offPeakPrice")) json.optInt("offPeakPrice") else null,
            minimumPrice = if (json.has("minimumPrice") && !json.isNull("minimumPrice")) json.optInt("minimumPrice") else null,
            tiers = json.optJSONArray("tiers")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { tier ->
                        CloudPriceTier(
                            resolution = tier.optString("resolution"),
                            price = tier.optInt("price"),
                            offPeakPrice = if (tier.has("offPeakPrice") && !tier.isNull("offPeakPrice")) tier.optInt("offPeakPrice") else null,
                        )
                    }
                }
            }.orEmpty(),
        )

        fun ruleToJson(rule: CloudInputRule): JSONObject = JSONObject()
            .put("name", rule.name)
            .put("type", rule.type)
            .put("required", rule.required)
            .put("default", rule.defaultValue ?: JSONObject.NULL)
            .put("min", rule.min ?: JSONObject.NULL)
            .put("max", rule.max ?: JSONObject.NULL)
            .put("options", JSONArray().apply { rule.options.forEach { put(it) } })
            .put("minLength", rule.minLength ?: JSONObject.NULL)
            .put("maxLength", rule.maxLength ?: JSONObject.NULL)
            .put("referenceRole", rule.referenceRole.name)
            .put("acceptedMediaTypes", JSONArray(rule.acceptedMediaTypes))
            .put("label", rule.label)
            .put("description", rule.description)

        fun ruleFromJson(json: JSONObject): CloudInputRule = CloudInputRule(
            name = json.optString("name"),
            type = json.optString("type"),
            required = json.optBoolean("required", false),
            defaultValue = if (json.isNull("default")) null else json.optString("default"),
            min = if (json.isNull("min")) null else json.optDouble("min"),
            max = if (json.isNull("max")) null else json.optDouble("max"),
            options = json.optJSONArray("options")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty(),
            minLength = if (json.isNull("minLength")) null else json.optInt("minLength"),
            referenceRole = ReferenceRole.fromStorage(json.optString("referenceRole")),
            maxLength = if (json.isNull("maxLength")) null else json.optInt("maxLength"),
            acceptedMediaTypes = json.optJSONArray("acceptedMediaTypes").strings(),
            label = json.optString("label"),
            description = json.optString("description"),
        )
    }
}

/** One generated media item returned by AutoDL. */
data class CloudResult(
    val url: String,
    val type: String,       // image / video / audio
    val fileType: String,   // png / mp4 / wav ...
    val outputType: String,
)

data class CloudTaskResult(
    val taskId: String,
    val status: String,
    val results: List<CloudResult>,
    val message: String,
    val durationSeconds: Long,
)

/** Pure parsing helpers, kept outside the HTTP client so they are unit-testable. */
object CloudWorkflowParser {
    fun list(body: JSONObject): Pair<List<CloudWorkflowSummary>, Int> {
        val data = body.optJSONObject("data") ?: JSONObject()
        val list = data.optJSONArray("list") ?: JSONArray()
        val out = mutableListOf<CloudWorkflowSummary>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("uuid").takeIf { it.isNotBlank() } ?: continue
            val priceType = o.optString("price_type")
            out += CloudWorkflowSummary(
                id = id,
                name = o.optString("name").ifBlank { id },
                description = o.optString("description"),
                kind = kindOf(o),
                priceType = priceType,
                coverUrl = o.optString("cover_image_url"),
                author = o.optString("author_nickname").ifBlank { o.optString("author_name") },
                usageCount = o.optInt("usage_count_7d"),
                pricing = parsePricing(o.optJSONObject("billing_config"), priceType),
                version = o.optString("version"),
            )
        }
        return out to data.optInt("max_page", 1).coerceAtLeast(1)
    }

    fun detail(body: JSONObject): CloudWorkflowSpec {
        val data = body.optJSONObject("data") ?: JSONObject()
        val id = data.optString("uuid")
        val rulesObj = data.optJSONObject("input_rules") ?: JSONObject()
        val rules = mutableListOf<CloudInputRule>()
        rulesObj.keys().forEach { name ->
            rulesObj.optJSONObject(name)?.let { rule ->
                val rawDefault = rule.opt("default")
                rules += CloudInputRule(
                    name = name,
                    type = rule.optString("type", "string").trim().lowercase(),
                    required = rule.optBoolean("required", false),
                    defaultValue = when (rawDefault) {
                        null, JSONObject.NULL -> null
                        is Boolean -> rawDefault.toString()
                        is Number -> rawDefault.toString()
                        else -> rawDefault.toString()
                    },
                    min = rule.opt("min")?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite),
                    max = rule.opt("max")?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite),
                    options = rule.optJSONArray("options")?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            val option = array.optJSONObject(index)
                            option?.optString("label")?.takeIf { it.isNotBlank() }
                                ?: array.optString(index).takeIf { it.isNotBlank() }
                        }
                    }.orEmpty(),
                    minLength = if (rule.has("min_length")) rule.optInt("min_length") else null,
                    maxLength = if (rule.has("max_length")) rule.optInt("max_length") else null,
                    referenceRole = ReferenceRole.fromStorage(
                        rule.optString("reference_role", rule.optString("referenceRole", ""))
                    ),
                    acceptedMediaTypes = rule.optJSONArray("accept_types").strings(),
                    label = rule.optString("display_name").ifBlank { rule.optString("label") },
                    description = rule.optString("description").ifBlank { rule.optString("tooltip") },
                )
            }
        }
        val priceType = data.optString("price_type")
        return CloudWorkflowSpec(
            id = id,
            name = data.optString("name").ifBlank { id },
            kind = kindOf(data),
            description = data.optString("description"),
            content = data.optString("content"),
            rules = rules,
            pricing = parsePricing(data.optJSONObject("billing_config"), priceType),
            version = data.optString("version"),
            referenceUrl = data.optString("api_reference_url"),
        )
    }

    private fun parsePricing(billingConfig: JSONObject?, priceType: String): CloudPricing? {
        val root = billingConfig ?: return null
        val key = root.keys().asSequence().firstOrNull() ?: return null
        val value = root.opt(key)
        if (value is JSONArray) {
            val tiers = mutableListOf<CloudPriceTier>()
            for (i in 0 until value.length()) {
                val tier = value.optJSONObject(i) ?: continue
                val price = tier.optInt("price", -1)
                if (price < 0) continue
                tiers += CloudPriceTier(
                    resolution = tier.optString("resolution"),
                    price = price,
                    offPeakPrice = if (tier.has("off_peak_price")) tier.optInt("off_peak_price") else null,
                )
            }
            return CloudPricing(mode = "per_call", tiers = tiers)
        }
        if (value is JSONObject) {
            val perSecond = key.contains("sec", ignoreCase = true) ||
                priceType.contains("sec", ignoreCase = true) ||
                value.has("minimum_price")
            return CloudPricing(
                mode = if (perSecond) "per_second" else "per_call",
                price = if (value.has("price")) value.optInt("price") else null,
                offPeakPrice = if (value.has("off_peak_price")) value.optInt("off_peak_price") else null,
                minimumPrice = if (value.has("minimum_price")) value.optInt("minimum_price") else null,
            )
        }
        return null
    }

    fun taskResult(body: JSONObject): CloudTaskResult {
        val data = body.optJSONObject("data") ?: JSONObject()
        val results = mutableListOf<CloudResult>()
        val array = data.optJSONArray("results") ?: JSONArray()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            results += CloudResult(
                url = url,
                type = o.optString("type", "video"),
                fileType = o.optString("file_type"),
                outputType = o.optString("output_type", "output"),
            )
        }
        return CloudTaskResult(
            taskId = data.optString("task_id"),
            status = data.optString("status").uppercase(),
            results = results,
            message = data.optString("message").ifBlank { body.optString("msg") },
            durationSeconds = data.optLong("duration"),
        )
    }

    private fun kindOf(data: JSONObject): String {
        val declared = data.optString("output_type").lowercase()
        if (declared in setOf("image", "audio", "video")) return declared
        val name = data.optString("name")
        return when (data.optString("price_type")) {
            "audio_sec_cost" -> "audio"
            "video_sec_cost", "minimax_h3" -> "video"
            else -> when {
                name.contains("视频", true) || name.contains("video", true) -> "video"
                name.contains("语音", true) || name.contains("tts", true) -> "audio"
                else -> "image"
            }
        }
    }
}

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
        optString(index).trim().takeIf(String::isNotEmpty)
    }

package com.comfyremote.app.data.cloud

import org.json.JSONObject

/**
 * The editable parameters a specific model really exposes. Model capabilities
 * differ inside one vendor family, so rules are derived from the model id rather
 * than from a provider-wide template.
 */
object CloudModelParameters {

    private val referenceImagePatterns = listOf(
        "gpt-image",
        "chatgpt-image",
        "nano-banana",
        "qwen-image-edit",
        "flux-kontext",
        "seededit",
        "seedream",
        "hunyuan-image",
        "image-edit",
        "edit-image",
        "image-to-image",
        "img2img",
        "i2i",
    )

    /** True when the model accepts a reference/input image in addition to text. */
    fun supportsReferenceImage(model: String): Boolean {
        val lower = model.lowercase()
        if (lower.startsWith("gemini") && "image" in lower) return true
        return referenceImagePatterns.any { it in lower }
    }

    /**
     * Number of reference-image slots exposed for a model. This is an app-side
     * practical cap; vendor hard limits are higher (GPT Image 16, Gemini Pro 14).
     */
    fun referenceImageLimit(model: String): Int {
        val lower = model.substringAfterLast('/').lowercase()
        if (!supportsReferenceImage(model)) return 0
        if (lower.contains("qwen-image-edit-2509")) return 3
        if (lower.startsWith("gpt-image") || lower.startsWith("chatgpt-image")) return 4
        if (lower.startsWith("gemini") && "image" in lower) return 4
        if (lower.contains("nano-banana") || lower.contains("seedream")) return 4
        return 1
    }

    /** Ordered reference image values from a built request body (`image`, `image2`, ...). */
    fun referenceImageInputs(body: JSONObject): List<Pair<String, String>> =
        body.keys().asSequence()
            .filter { key -> key == "image" || Regex("image\\d+").matches(key) }
            .sortedBy { key ->
                if (key == "image") 0 else key.removePrefix("image").toIntOrNull() ?: Int.MAX_VALUE
            }
            .mapNotNull { key ->
                body.optString(key).takeIf(String::isNotBlank)?.let { key to it }
            }
            .toList()

    fun kindOf(model: String): String {
        return mediaKindHint(model).ifBlank { "image" }
    }

    /** Fallback for ID-only catalogues. A provider-declared modality takes precedence. */
    internal fun mediaKindHint(model: String): String {
        val leaf = model.substringAfterLast('/').lowercase().replace('_', '-')
        return when {
            listOf("tts", "speech", "stable-audio", "voice-clone", "musicgen", "text-to-audio")
                .any { it in leaf } -> "audio"
            "image-to-video" in leaf || listOf("t2v", "i2v", "v2v", "flf2v", "video", "wan2", "wan3", "wan-", "seedance",
                "kling", "hailuo", "minimax-h3", "veo", "runway", "sora", "luma")
                .any { it in leaf } && "image" !in leaf -> "video"
            listOf("image", "dall-e", "seedream", "seededit", "flux", "banana", "kolors",
                "stable-diffusion", "sdxl", "sd3", "recraft", "ideogram", "imagen")
                .any { it in leaf } -> "image"
            else -> ""
        }
    }

    fun looksLikeMediaModel(model: String): Boolean = mediaKindHint(model).isNotEmpty()

    /**
     * OpenAI-compatible Images parameters. GPT Image models accept an input
     * image through `/images/edits`; DALL·E 3 explicitly does not.
     */
    fun openAiRules(model: String): List<CloudInputRule> {
        val leaf = model.substringAfterLast('/').lowercase()
        val gptImage = leaf.startsWith("gpt-image") || leaf.startsWith("chatgpt-image")
        val dallE = leaf.startsWith("dall-e")
        val rules = mutableListOf(CloudInputRule("prompt", "prompt", required = true))
        repeat(referenceImageLimit(model)) { index ->
            rules += CloudInputRule(
                name = if (index == 0) "image" else "image${index + 1}",
                type = "image",
                required = false,
            )
        }
        if (!gptImage && !dallE) {
            rules += CloudInputRule(
                "size", "enum", required = false, defaultValue = "1024x1024",
                options = listOf("1024x1024", "768x1024", "1024x768", "auto"),
            )
            rules += CloudInputRule("n", "integer", required = false, defaultValue = "1", min = 1.0, max = 4.0)
            return rules
        }
        val sizes = if (dallE) {
            listOf("1024x1024", "1792x1024", "1024x1792")
        } else if (model.contains("2.5")) {
            listOf(
                "1024x1024", "1536x1024", "1024x1536",
                "2048x2048", "2048x1152", "1152x2048", "auto",
            )
        } else {
            listOf("1024x1024", "1536x1024", "1024x1536", "auto")
        }
        rules += CloudInputRule("size", "enum", required = false, defaultValue = sizes.first(), options = sizes)
        val qualities = if (dallE) {
            listOf("standard", "hd")
        } else if (model.contains("2.5")) {
            listOf("auto", "low", "medium", "high", "xhigh", "max")
        } else {
            listOf("auto", "low", "medium", "high")
        }
        rules += CloudInputRule(
            "quality", "enum", required = false,
            defaultValue = if (dallE) "standard" else "auto",
            options = qualities,
        )
        rules += CloudInputRule(
            "n", "integer", required = false, defaultValue = "1",
            min = 1.0, max = if (dallE) 1.0 else 4.0,
        )
        if (dallE) {
            rules += CloudInputRule(
                "style", "enum", required = false, defaultValue = "vivid",
                options = listOf("vivid", "natural"),
            )
        } else {
            rules += CloudInputRule(
                "background", "enum", required = false, defaultValue = "opaque",
                options = listOf("transparent", "opaque", "auto"),
            )
            rules += CloudInputRule(
                "output_format", "enum", required = false, defaultValue = "png",
                options = listOf("png", "jpeg", "webp"),
            )
        }
        return rules
    }

    /**
     * Gemini image models support reference images as inline parts. `imageSize`
     * is only exposed by the Pro model; the flash/lite endpoints use their
     * documented default instead of showing an option the API would ignore.
     */
    fun geminiRules(model: String): List<CloudInputRule> {
        val isFlash = model.contains("flash-image") && !model.contains("flash-lite-image")
        val ratios = if (isFlash) {
            // Gemini 3.1 Flash Image adds the extreme wide/tall ratios.
            listOf(
                "1:1", "3:4", "4:3", "9:16", "16:9", "3:2", "2:3", "21:9",
                "1:4", "4:1", "1:8", "8:1",
            )
        } else {
            listOf("1:1", "3:4", "4:3", "9:16", "16:9", "3:2", "2:3", "21:9")
        }
        val rules = mutableListOf(
            CloudInputRule("prompt", "prompt", required = true),
            CloudInputRule(
                "aspect_ratio", "enum", required = false, defaultValue = "1:1",
                options = ratios,
            ),
        )
        repeat(referenceImageLimit(model)) { index ->
            rules += CloudInputRule(
                name = if (index == 0) "image" else "image${index + 1}",
                type = "image",
                required = false,
            )
        }
        val imageSizes = when {
            model.contains("3-pro-image") -> listOf("1K", "2K", "4K")
            isFlash -> listOf("0.5K", "1K", "2K", "4K")
            model.contains("flash-lite-image") -> listOf("0.5K", "1K")
            else -> emptyList()
        }
        if (imageSizes.isNotEmpty()) {
            rules += CloudInputRule(
                "image_size", "enum", required = false, defaultValue = "1K",
                options = imageSizes,
            )
        }
        rules += CloudInputRule("seed", "integer", required = false, max = 2_147_483_647.0)
        return rules
    }

    /** SiliconFlow image/video parameters, including edit and I2V inputs. */
    fun siliconFlowRules(model: String, kind: String = kindOf(model)): List<CloudInputRule> {
        val lower = model.lowercase()
        val editModel = supportsReferenceImage(model)
        val rules = mutableListOf(CloudInputRule("prompt", "prompt", required = kind != "image" || !editModel))
        if (kind == "video") {
            if (lower.contains("minimax-h3")) {
                rules += CloudInputRule(
                    "duration", "integer", required = false, defaultValue = "5",
                    min = 5.0, max = 15.0,
                )
                rules += CloudInputRule(
                    "resolution", "enum", required = false, defaultValue = "768p",
                    options = listOf("480p", "768p"),
                )
                rules += CloudInputRule(
                    "ratio", "enum", required = false, defaultValue = "16:9",
                    options = listOf("1:1", "3:4", "4:3", "9:16", "16:9", "21:9"),
                )
                rules += CloudInputRule("first_frame_image", "image", required = false)
                rules += CloudInputRule("last_frame_image", "image", required = false)
                return rules
            }
            rules += CloudInputRule(
                "image_size", "enum", required = false, defaultValue = "1280x720",
                options = listOf("1280x720", "720x1280", "960x960"),
            )
            if (lower.contains("i2v")) {
                rules += CloudInputRule("image", "image", required = true)
            }
            rules += CloudInputRule("negative_prompt", "string", required = false)
            rules += CloudInputRule(
                "seed", "integer", required = false, max = SILICON_FLOW_SEED_MAX,
            )
            return rules
        }
        if (editModel) {
            // Qwen-Image-Edit-2509 accepts image, image2 and image3; older edit
            // endpoints take a single source image. Edit models do not accept
            // image_size, so it must not be offered.
            repeat(referenceImageLimit(model)) { index ->
                rules += CloudInputRule(
                    name = if (index == 0) "image" else "image${index + 1}",
                    type = "image",
                    required = index == 0,
                )
            }
        } else {
            rules += CloudInputRule(
                "image_size", "enum", required = false, defaultValue = "1024x1024",
                options = listOf("1024x1024", "768x1024", "1024x768", "512x512", "1280x720", "720x1280"),
            )
        }
        rules += CloudInputRule("negative_prompt", "string", required = false)
        // SiliconFlow removed image batch_size; multi-image runs use Iris' own batch queue.
        rules += CloudInputRule(
            "num_inference_steps", "integer", required = false, defaultValue = "20",
            min = 1.0, max = if (editModel) 20.0 else 50.0,
        )
        if (!editModel) {
            rules += CloudInputRule(
                "guidance_scale", "number", required = false, defaultValue = "7.5", min = 1.0, max = 20.0,
            )
        }
        rules += CloudInputRule(
            "seed", "integer", required = false, max = SILICON_FLOW_SEED_MAX,
        )
        rules += CloudInputRule("prompt_enhancement", "boolean", required = false, defaultValue = "false")
        return rules
    }

    /** SiliconFlow rejects seeds above 9.999999999e9. */
    private const val SILICON_FLOW_SEED_MAX = 9_999_999_999.0
}

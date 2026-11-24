package com.comfyremote.app.data.cloud

import com.comfyremote.app.FieldKind
import com.comfyremote.app.PromptField
import com.comfyremote.app.PromptValidationException
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.data.reference.inferReferenceRole
import com.comfyremote.app.data.repository.WorkflowCapability
import com.comfyremote.app.data.repository.WorkflowCompatibilityIssue
import com.comfyremote.app.data.repository.WorkflowInsight
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import kotlin.random.Random

/** Maps AutoDL `input_rules` onto the same editable fields the local UI uses. */
object CloudWorkflowMapping {

    internal fun supports(rule: CloudInputRule): Boolean =
        rule.isMedia || rule.isText || rule.isSeed || rule.type in setOf("integer", "number", "boolean", "enum")

    fun fields(spec: CloudWorkflowSpec): List<PromptField> = spec.rules.mapNotNull { rule ->
        val label = rule.label.ifBlank { labelFor(rule.name) }
        val value = rule.effectiveDefault.orEmpty()
        when {
            rule.isText -> PromptField(
                key = rule.name,
                nodeId = "cloud",
                input = rule.name,
                label = label,
                kind = FieldKind.TEXT,
                value = value,
                multiline = rule.type == "prompt",
                required = rule.required,
            )
            rule.isSeed -> PromptField(
                key = rule.name,
                nodeId = "cloud",
                input = rule.name,
                label = label,
                kind = FieldKind.SEED,
                value = value,
                required = rule.required,
            )
            rule.isImage -> PromptField(
                key = rule.name,
                nodeId = "cloud",
                input = rule.name,
                label = label,
                kind = FieldKind.IMAGE,
                value = value,
                required = rule.required,
                referenceRole = if (rule.referenceRole != ReferenceRole.AUTO) {
                    rule.referenceRole
                } else {
                    inferReferenceRole(rule.name, label)
                },
            )
            rule.isAudio || rule.isVideo -> PromptField(
                key = rule.name,
                nodeId = "cloud",
                input = rule.name,
                label = label,
                kind = if (rule.isVideo) FieldKind.VIDEO else FieldKind.AUDIO,
                value = value,
                required = rule.required,
            )
            rule.type == "integer" -> PromptField(
                key = rule.name,
                nodeId = "cloud",
                input = rule.name,
                label = label,
                kind = FieldKind.PARAM,
                value = value,
                ptype = "int",
                min = rule.min?.toInt()?.toString(),
                max = rule.max?.toInt()?.toString(),
                required = rule.required,
            )
            rule.type == "number" -> PromptField(
                key = rule.name,
                nodeId = "cloud",
                input = rule.name,
                label = label,
                kind = FieldKind.PARAM,
                value = value,
                ptype = "float",
                min = rule.min?.toString(),
                max = rule.max?.toString(),
                required = rule.required,
            )
            rule.type == "boolean" -> PromptField(
                key = rule.name,
                nodeId = "cloud",
                input = rule.name,
                label = label,
                kind = FieldKind.PARAM,
                value = value.ifBlank { "false" },
                ptype = "bool",
                required = rule.required,
            )
            rule.type == "enum" -> PromptField(
                key = rule.name,
                nodeId = "cloud",
                input = rule.name,
                label = label,
                kind = FieldKind.PARAM,
                value = value.ifBlank { rule.options.firstOrNull().orEmpty() },
                ptype = "combo",
                options = rule.options,
                required = rule.required,
            )
            else -> null
        }
    }

    fun warnings(spec: CloudWorkflowSpec): List<String> {
        return listOfNotNull(spec.unavailableReason.takeIf(String::isNotBlank)) + spec.rules.filterNot(::supports).map { rule ->
            val type = "${rule.name} (${rule.type})"
            if (rule.required) "尚未支持的必填参数 $type，无法提交"
            else "尚未支持的可选参数 $type"
        }
    }

    fun analyze(
        spec: CloudWorkflowSpec,
        fields: List<PromptField>,
        values: Map<String, String>,
    ): WorkflowInsight {
        val capabilities = linkedSetOf<WorkflowCapability>()
        val imageFields = fields.filter { it.kind == FieldKind.IMAGE }
        when (spec.kind) {
            "video" -> {
                capabilities += WorkflowCapability.VIDEO
                if (fields.any { it.kind == FieldKind.VIDEO }) capabilities += WorkflowCapability.VIDEO_REFERENCE
                if (imageFields.isNotEmpty()) capabilities += WorkflowCapability.IMG2VIDEO
                else if (WorkflowCapability.VIDEO_REFERENCE !in capabilities) capabilities += WorkflowCapability.TXT2VIDEO
            }
            "audio" -> capabilities += WorkflowCapability.AUDIO
            "image" -> if (imageFields.isNotEmpty()) capabilities += WorkflowCapability.IMG2IMG
                else capabilities += WorkflowCapability.TXT2IMG
        }
        if (fields.any { it.kind == FieldKind.AUDIO }) capabilities += WorkflowCapability.AUDIO
        if (imageFields.size > 1) capabilities += WorkflowCapability.MULTI_REFERENCE

        val issues = mutableListOf<WorkflowCompatibilityIssue>()
        warnings(spec).forEach { issue ->
            issues += WorkflowCompatibilityIssue(issue, blocking = issue == spec.unavailableReason || issue.contains("无法提交"))
        }
        fields.forEach { field ->
            val current = values[field.key] ?: field.value
            if (field.required && current.isBlank() && field.kind != FieldKind.SEED) {
                val kind = when (field.kind) {
                    FieldKind.IMAGE -> "参考图片"
                    FieldKind.AUDIO -> "参考音频"
                    FieldKind.VIDEO -> "参考视频"
                    FieldKind.TEXT -> "提示词"
                    else -> "参数"
                }
                issues += WorkflowCompatibilityIssue("${field.label}: $kind 不能为空", blocking = true)
            } else if (current.isNotBlank() && field.kind != FieldKind.SEED) {
                CloudPromptBuilder.valueProblem(spec.rule(field.input), field, current)?.let { message ->
                    issues += WorkflowCompatibilityIssue(message, blocking = true)
                }
            }
        }
        return WorkflowInsight(capabilities, issues.distinctBy { it.message })
    }

    private fun labelFor(name: String): String = when {
        name == "prompt" -> "提示词 · Prompt"
        name == "prompt_text" -> "文本 · Text"
        name == "duration" -> "时长 · Duration (s)"
        name == "audio_duration" -> "音频时长 · Audio duration (s)"
        name == "resolution" -> "分辨率 · Resolution"
        name == "seed" -> "随机种子 · Seed"
        name == "first_frame" || name == "first_frame_image" -> "首帧 · First frame"
        name == "last_frame" || name == "last_frame_image" -> "尾帧 · Last frame"
        name == "image" -> "参考图 · Reference image"
        name.matches(Regex("image\\d+")) ->
            "参考图 ${name.removePrefix("image").toIntOrNull() ?: ""} · Reference"
        name == "mask" -> "蒙版 · Mask"
        name == "aspect_ratio" || name == "ratio" -> "画幅 · Aspect ratio"
        name == "image_size" || name == "size" -> "尺寸 · Size"
        name == "quality" -> "质量 · Quality"
        name == "background" -> "背景 · Background"
        name == "output_format" -> "输出格式 · Format"
        name == "n" || name == "batch_size" || name == "num_outputs" -> "数量 · Count"
        name == "negative_prompt" -> "负面提示词 · Negative prompt"
        name == "mode" -> "模式 · Mode"
        name == "generate_audio" -> "生成音频 · Audio"
        name == "watermark" -> "水印 · Watermark"
        name == "prompt_enhancement" -> "提示词增强 · Enhance"
        name == "num_inference_steps" -> "步数 · Steps"
        name == "guidance_scale" -> "引导强度 · Guidance"
        name == "style" -> "风格 · Style"
        name.startsWith("ref_image_") -> "参考图 ${name.removePrefix("ref_image_").toIntOrNull()?.plus(1) ?: ""} · Reference"
        name.startsWith("ref_audio_") -> "参考音频 ${name.removePrefix("ref_audio_").toIntOrNull()?.plus(1) ?: ""} · Audio"
        name == "ref_video" || name == "video" -> "参考视频 · Reference video"
        name.startsWith("ref_video_") -> "参考视频 ${name.removePrefix("ref_video_").toIntOrNull()?.plus(1) ?: ""} · Video"
        else -> name
    }
}

/** Builds the AutoDL request body from the editable field snapshot. */
object CloudPromptBuilder {

    suspend fun build(
        spec: CloudWorkflowSpec,
        fields: List<PromptField>,
        values: Map<String, String>,
        randomSeed: Boolean,
        resolveImage: (String) -> String?,
    ): Pair<JSONObject, Map<String, String>> {
        if (spec.unavailableReason.isNotBlank()) throw PromptValidationException(spec.unavailableReason)
        val body = JSONObject()
        val seeds = HashMap<String, String>()
        val fieldInputs = fields.mapTo(hashSetOf(), PromptField::input)
        val unsupported = spec.rules.filter { it.required &&
            (!CloudWorkflowMapping.supports(it) || it.name !in fieldInputs) }
        if (unsupported.isNotEmpty()) {
            throw PromptValidationException("缺少必填参数 ${unsupported.joinToString { it.name }}，请重新打开工作流")
        }
        for (field in fields) {
            val rule = spec.rule(field.input)
            val supplied = values[field.key] ?: field.value
            val raw = if (field.kind == FieldKind.TEXT) supplied else supplied.trim()
            if (raw.isNotBlank() && field.kind != FieldKind.SEED) {
                valueProblem(rule, field, raw)?.let { throw PromptValidationException(it) }
            }
            when (field.kind) {
                FieldKind.TEXT -> {
                    if (raw.isNotBlank()) body.put(field.input, raw)
                    else if (field.required) throw PromptValidationException("${field.label} 不能为空")
                }
                FieldKind.IMAGE, FieldKind.AUDIO, FieldKind.VIDEO -> {
                    if (raw.isNotBlank()) {
                        // A filled slot is a user intent. Never silently drop it and
                        // submit a cheaper request with missing references.
                        val resolved = raw.takeIf { it.toHttpUrlOrNull() != null || it.startsWith("data:") }
                            ?: resolveImage(raw)
                            ?: throw PromptValidationException("${field.label} 读取失败，请重新选择素材")
                        mediaProblem(rule, field, resolved, allowLocal = false)?.let {
                            throw PromptValidationException(it)
                        }
                        body.put(field.input, resolved)
                    } else if (field.required) {
                        throw PromptValidationException("${field.label} 不能为空")
                    }
                }
                FieldKind.SEED -> {
                    val max = spec.rule(field.input)?.max
                        ?.toLong()
                        ?.coerceIn(1L, SEED_LIMIT)
                        ?: SEED_LIMIT
                    val used = if (randomSeed) {
                        Random.nextLong(1L, max + 1L)
                    } else {
                        (raw.toLongOrNull() ?: 1L).coerceIn(1L, max)
                    }
                    body.put(field.input, used)
                    seeds[field.key] = used.toString()
                }
                FieldKind.PARAM -> when (field.ptype) {
                    "int" -> {
                        val value = raw.toLongOrNull()
                        if (value != null) body.put(field.input, value)
                        else if (field.required) {
                            throw PromptValidationException("${field.label} 需要整数")
                        }
                    }
                    "float" -> {
                        val value = raw.toDoubleOrNull()
                        if (value != null) body.put(field.input, value)
                        else if (field.required) {
                            throw PromptValidationException("${field.label} 需要数字")
                        }
                    }
                    "bool" -> if (raw.isNotBlank()) {
                        body.put(field.input, raw.equals("true", ignoreCase = true))
                    } else if (field.required) {
                        throw PromptValidationException("${field.label} 不能为空")
                    }
                    "combo" -> if (raw.isNotBlank()) body.put(field.input, raw)
                    else if (field.required) throw PromptValidationException("${field.label} 不能为空")
                }
            }
        }
        return body to seeds
    }

    internal fun valueProblem(rule: CloudInputRule?, field: PromptField, value: String): String? {
        if (field.kind.isMedia) return mediaProblem(rule, field, value.trim(), allowLocal = true)
        if (field.kind == FieldKind.TEXT) {
            val length = value.codePointCount(0, value.length)
            rule?.minLength?.takeIf { length < it }?.let { return "${field.label} 至少需要 $it 个字符" }
            rule?.maxLength?.takeIf { length > it }?.let { return "${field.label} 最多支持 $it 个字符" }
            return null
        }
        val numeric = when (field.ptype) {
            "int" -> value.trim().toLongOrNull()?.toDouble() ?: return "${field.label} 需要整数"
            "float" -> value.trim().toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: return "${field.label} 需要数字"
            "bool" -> return if (value.trim().lowercase() in setOf("true", "false")) null
                else "${field.label} 需要布尔值"
            "combo" -> return if (field.options.isEmpty() || value.trim() in field.options) null
                else "${field.label} 的当前选项不在服务商支持的范围内"
            else -> return null
        }
        rule?.min?.takeIf { numeric < it }?.let { return "${field.label} 不能小于 ${formatLimit(it)}" }
        rule?.max?.takeIf { numeric > it }?.let { return "${field.label} 不能大于 ${formatLimit(it)}" }
        return null
    }

    private fun mediaProblem(
        rule: CloudInputRule?,
        field: PromptField,
        value: String,
        allowLocal: Boolean,
    ): String? {
        if (value.toHttpUrlOrNull() != null || (allowLocal && value.startsWith(CloudInputStore.PREFIX))) return null
        val mime = value.takeIf { it.startsWith("data:") && ',' in it }
            ?.substringAfter("data:")?.substringBefore(';')?.substringBefore(',')?.lowercase()
        val type = field.kind.mediaType ?: return null
        val accepted = rule?.acceptedMediaTypes.orEmpty()
        if (mime?.startsWith("$type/") == true &&
            (accepted.isEmpty() || accepted.any { it.equals(mime, true) || it == "$type/*" })) return null
        return "${field.label} 需要可用的媒体文件或 HTTP(S) 链接" +
            accepted.takeIf { it.isNotEmpty() }?.joinToString(", ", "（", "）").orEmpty()
    }

    private fun formatLimit(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    /** AutoDL seed max is 999999999999999; stay strictly below it. */
    private const val SEED_LIMIT = 1_000_000_000_000_000L
}

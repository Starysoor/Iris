package com.comfyremote.app.data.repository

import com.comfyremote.app.FieldKind
import com.comfyremote.app.PromptField
import org.json.JSONObject

enum class WorkflowCapability(val label: String) {
    TXT2IMG("TXT2IMG"),
    IMG2IMG("IMG2IMG"),
    INPAINT("INPAINT"),
    UPSCALE("UPSCALE"),
    VIDEO("VIDEO"),
    TXT2VIDEO("TXT2VIDEO"),
    IMG2VIDEO("IMG2VIDEO"),
    VIDEO_REFERENCE("VIDEO REF"),
    CONTROL("CONTROL"),
    MULTI_REFERENCE("MULTI-REF"),
    LORA("LORA"),
    AUDIO("AUDIO"),
}

data class WorkflowCompatibilityIssue(
    val message: String,
    val blocking: Boolean,
)

data class WorkflowInsight(
    val capabilities: Set<WorkflowCapability>,
    val issues: List<WorkflowCompatibilityIssue>,
) {
    val blockingIssues: List<WorkflowCompatibilityIssue> get() = issues.filter { it.blocking }
    val compatible: Boolean get() = blockingIssues.isEmpty()
}

object WorkflowInsights {
    fun isModelSelection(field: PromptField): Boolean =
        field.kind == FieldKind.PARAM && field.ptype == "combo" &&
            listOf("ckpt", "checkpoint", "model", "unet", "vae", "lora", "control_net", "controlnet", "clip_name")
                .any { it in field.input.lowercase() }

    fun analyze(
        prompt: JSONObject,
        fields: List<PromptField>,
        values: Map<String, String>,
        converterWarnings: List<String>,
        blockingProblems: List<String> = emptyList(),
    ): WorkflowInsight {
        val types = buildList {
            prompt.keys().forEach { id ->
                prompt.optJSONObject(id)?.optString("class_type")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val lowerTypes = types.map(String::lowercase)
        val imageFields = fields.filter { it.kind == FieldKind.IMAGE }
        val capabilities = linkedSetOf<WorkflowCapability>()

        if (lowerTypes.any { it.contains("video") || it.contains("wan") || it.contains("animatediff") }) {
            capabilities += WorkflowCapability.VIDEO
        }
        if (lowerTypes.any { it.contains("upscale") || it.contains("esrgan") || it.contains("supir") }) {
            capabilities += WorkflowCapability.UPSCALE
        }
        if (lowerTypes.any { it.contains("inpaint") || it.contains("mask") }) {
            capabilities += WorkflowCapability.INPAINT
        }
        if (lowerTypes.any { it.contains("controlnet") || it.contains("control_net") }) {
            capabilities += WorkflowCapability.CONTROL
        }
        if (lowerTypes.any { it.contains("lora") }) capabilities += WorkflowCapability.LORA
        if (imageFields.size > 1) capabilities += WorkflowCapability.MULTI_REFERENCE
        if (imageFields.isNotEmpty()) capabilities += WorkflowCapability.IMG2IMG
        else if (WorkflowCapability.VIDEO !in capabilities) capabilities += WorkflowCapability.TXT2IMG

        val issues = blockingProblems.map { WorkflowCompatibilityIssue(it, true) }.toMutableList()
        converterWarnings.forEach { warning ->
            val blocking = warning.contains("未知节点") || warning.contains("无法完整转换") || warning.contains("Subgraph", true)
            issues += WorkflowCompatibilityIssue(warning, blocking)
        }

        fields.filter { it.kind == FieldKind.PARAM && it.ptype == "combo" && it.options.isNotEmpty() }
            .forEach { field ->
                val current = values[field.key] ?: field.value
                if (isModelSelection(field) && current.isNotBlank() && current !in field.options) {
                    issues += WorkflowCompatibilityIssue(
                        "${field.label}: '$current' is not available on this server",
                        blocking = true,
                    )
                }
            }

        fields.filter { it.kind.isMedia }.forEach { field ->
            val current = values[field.key] ?: field.value
            if (current.isBlank()) {
                // Required image inputs must be visible before submit; optional reference
                // slots stay a warning because ComfyUI is still the authority for them.
                issues += WorkflowCompatibilityIssue(
                    "${field.label}: reference media is empty",
                    blocking = field.required,
                )
            }
        }

        return WorkflowInsight(capabilities, issues.distinctBy { it.message })
    }
}

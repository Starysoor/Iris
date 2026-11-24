package com.comfyremote.app.feature.generate

import com.comfyremote.app.FieldKind
import com.comfyremote.app.PromptField
import com.comfyremote.app.data.repository.DraftTransfer

/**
 * Maps saved values from one workflow onto another by field role/input instead of
 * raw node keys. Ambiguous matches are dropped rather than silently written into a
 * same-named but semantically different input (for example a negative prompt).
 */
object ReuseValueMapper {

    data class Result(val values: Map<String, String>, val unmapped: Int)

    private enum class Role { TEXT_POSITIVE, TEXT_NEGATIVE, SEED, IMAGE, AUDIO, VIDEO, PARAM }

    private data class SourceValue(
        val key: String,
        val value: String,
        val input: String,
        val role: Role,
        val kind: FieldKind?,
    )

    fun map(transfer: DraftTransfer, target: WorkflowDraft): Result {
        if (transfer.workflowPath == target.workflowPath) return Result(transfer.values, 0)
        if (transfer.values.isEmpty()) return Result(emptyMap(), 0)

        val sourceFields = transfer.sourceFields.associateBy { it.key }
        val sources = transfer.values.map { (key, value) ->
            val field = sourceFields[key]
            val input = field?.input?.takeIf { it.isNotBlank() } ?: key.substringAfter(':', key)
            SourceValue(
                key = key,
                value = value,
                input = input,
                role = roleOf(input, field?.label.orEmpty(), field?.kind, value),
                kind = field?.kind,
            )
        }
        val used = mutableSetOf<String>()
        val mapped = linkedMapOf<String, String>()

        // Cloud rule keys (prompt, duration, ...) are semantic and stable across cloud workflows.
        if (target.isCloud) {
            target.fields.forEach { field ->
                val source = sources.firstOrNull { it.key == field.key && it.key !in used } ?: return@forEach
                if (!compatible(source, field, target)) return@forEach
                mapped[field.key] = source.value
                used += source.key
            }
        }

        target.fields.filter { it.key !in mapped }.forEach { field ->
            val role = roleOf(field.input, field.label, field.kind, field.value)
            var candidates = sources.filter {
                it.key !in used && it.role == role && compatible(it, field, target)
            }
            if (candidates.size > 1) {
                candidates = candidates.filter { normalize(it.input) == normalize(field.input) }
            }
            // Still ambiguous: leave it to the user rather than guessing.
            val chosen = candidates.singleOrNull() ?: return@forEach
            mapped[field.key] = chosen.value
            used += chosen.key
        }
        return Result(mapped, sources.count { it.key !in used })
    }

    private fun compatible(source: SourceValue, target: PromptField, draft: WorkflowDraft): Boolean {
        if (target.kind == FieldKind.PARAM) {
            return normalize(source.input) == normalize(target.input)
        }
        if (source.kind != null && source.kind != target.kind) return false
        if (target.kind.isMedia) {
            val cloudValue = source.value.startsWith("local:") ||
                source.value.startsWith("http://") ||
                source.value.startsWith("https://") ||
                source.value.startsWith("data:")
            // Cloud inputs and ComfyUI input names live in different namespaces.
            return if (draft.isCloud) cloudValue else !cloudValue
        }
        return true
    }

    private fun roleOf(input: String, label: String, kind: FieldKind?, value: String): Role {
        return when (kind) {
            FieldKind.TEXT -> if (looksNegative(input, label)) Role.TEXT_NEGATIVE else Role.TEXT_POSITIVE
            FieldKind.SEED -> Role.SEED
            FieldKind.IMAGE -> Role.IMAGE
            FieldKind.AUDIO -> Role.AUDIO
            FieldKind.VIDEO -> Role.VIDEO
            FieldKind.PARAM -> Role.PARAM
            null -> {
                val text = normalize("$input $label")
                when {
                    looksNegative(input, label) -> Role.TEXT_NEGATIVE
                    text.contains("seed") -> Role.SEED
                    text.contains("video") -> Role.VIDEO
                    text.contains("image") || text.contains("img") ||
                        text.contains("frame") || text.contains("reference") -> Role.IMAGE
                    text.contains("audio") || text.contains("sound") || text.contains("voice") -> Role.AUDIO
                    text.contains("prompt") || text.contains("text") -> Role.TEXT_POSITIVE
                    else -> Role.PARAM
                }
            }
        }
    }

    private fun looksNegative(input: String, label: String): Boolean {
        val text = "$input $label".lowercase()
        return listOf("negative", "neg_", "neg ", "负面", "负向", "反向").any(text::contains)
    }

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}

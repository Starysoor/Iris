package com.comfyremote.app

import org.json.JSONArray
import org.json.JSONObject

/** The rgthree loader takes LoRA slots as object-valued optional inputs. */
internal object PowerLoraWidgets {
    const val NODE_TYPE = "Power Lora Loader (rgthree)"

    private val slotName = Regex("lora_[0-9]+")
    private val members = setOf("lora", "on", "strength", "strengthTwo")

    fun restoreInputs(
        widgets: JSONArray?,
        namedWidgets: JSONObject?,
        overrides: Map<String, Any>,
        inputs: JSONObject,
    ) {
        val slots = linkedMapOf<String, JSONObject>()
        if (widgets != null) {
            var nextSlot = 1
            for (index in 0 until widgets.length()) {
                val value = widgets.optJSONObject(index) ?: continue
                // Header, spacer and button widgets do not carry a LoRA value.
                if (value.has("lora")) slots["lora_${nextSlot++}"] = value
            }
        }
        if (namedWidgets != null) {
            for (name in namedWidgets.keys()) {
                if (!slotName.matches(name)) continue
                namedWidgets.optJSONObject(name)?.takeIf { it.has("lora") }?.let { slots[name] = it }
            }
        }
        for ((name, value) in overrides) {
            if (slotName.matches(name) && value is JSONObject && value.has("lora")) slots[name] = value
        }
        for ((name, value) in slots) {
            if (!inputs.has(name)) {
                val slot = JSONObject(value.toString())
                if (slot.isNull("strengthTwo")) slot.remove("strengthTwo")
                inputs.put(name, slot)
            }
        }
    }

    fun fields(
        nodeId: String,
        title: String,
        inputs: JSONObject,
        availableLoras: List<String>,
    ): List<PromptField> = buildList {
        for (name in inputs.keys()) {
            if (!slotName.matches(name)) continue
            val slot = inputs.optJSONObject(name)?.takeIf { it.has("lora") } ?: continue
            val prefix = "$title · LoRA ${name.substringAfter('_')}"
            val selectedLora = (slot.opt("lora") as? String).orEmpty()
            // rgthree can resolve saved filename aliases as well as full catalogue paths.
            val options = if (availableLoras.isEmpty()) emptyList()
                else (availableLoras + selectedLora).filter(String::isNotBlank).distinct()

            fun addField(member: String, label: String, value: String, type: String, choices: List<String> = emptyList()) {
                val input = "$name.$member"
                add(PromptField("$nodeId:$input", nodeId, input, "$prefix · $label",
                    FieldKind.PARAM, value, ptype = type, options = choices))
            }

            addField("lora", "lora_name", selectedLora, if (options.isEmpty()) "string" else "combo", options)
            addField("on", "on", slot.optBoolean("on", true).toString(), "bool")
            addField("strength", "strength_model", (slot.opt("strength") as? Number)?.toString().orEmpty(), "float")
            val clipStrength = slot.opt("strengthTwo") as? Number
            if (clipStrength != null) addField("strengthTwo", "strength_clip", clipStrength.toString(), "float")
        }
    }

    fun fieldTarget(inputs: JSONObject, field: String): Pair<JSONObject, String>? {
        val name = field.substringBefore('.')
        val member = field.substringAfter('.', "")
        if (member !in members || !slotName.matches(name)) return null
        return inputs.optJSONObject(name)?.let { it to member }
    }
}

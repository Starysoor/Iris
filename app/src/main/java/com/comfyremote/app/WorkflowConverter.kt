package com.comfyremote.app

import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.data.reference.inferReferenceRole
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

enum class FieldKind(val mediaType: String? = null) {
    TEXT, SEED, IMAGE("image"), AUDIO("audio"), VIDEO("video"), PARAM;

    val isMedia: Boolean get() = mediaType != null
}

/**
 * A user-editable parameter discovered in the workflow: a text prompt, a seed,
 * an input image, or an "advanced" numeric/boolean/combo parameter.
 */
data class PromptField(
    val key: String,
    val nodeId: String,
    val input: String,
    val label: String,
    val kind: FieldKind,
    val value: String,
    val multiline: Boolean = false,
    /** PARAM only: "int" / "float" / "bool" / "combo" */
    val ptype: String = "",
    /** PARAM combo choices */
    val options: List<String> = emptyList(),
    val min: String? = null,
    val max: String? = null,
    /** True when the workflow schema marks this input as required. */
    val required: Boolean = false,
    /** Semantic role for reference images; AUTO lets the adapter/user decide. */
    val referenceRole: ReferenceRole = ReferenceRole.AUTO,
)

class ConvertedWorkflow(
    val prompt: JSONObject,
    val fields: List<PromptField>,
    val warnings: List<String>,
    val notes: List<String> = emptyList(),
    val blockingProblems: List<String> = emptyList(),
    val outputKinds: Set<String> = emptySet(),
)

/**
 * Converts ComfyUI UI-format workflows (nodes/links canvas JSON) into API-format
 * prompt dicts, using /object_info schemas to map widgets_values positions to
 * input names. A widget that was "converted to input" keeps a stale placeholder
 * in widgets_values, so widget values are consumed in schema order even when the
 * input is linked — the placeholder is discarded and the link is resolved instead.
 */
object WorkflowConverter {

    const val SEED_MAX = 281474976710656L // 2^48

    private val noteTypes = setOf("MarkdownNote", "Note")
    private const val DYNAMIC_COMBO = "COMFY_DYNAMICCOMBO_V3"

    fun convert(wfJson: String, info: JSONObject): ConvertedWorkflow {
        val wf = JSONObject(wfJson)
        if (wf.optJSONArray("nodes") == null) return apiFormatFields(wf.optJSONObject("prompt") ?: wf, info)

        val canvas = ComfyCanvas(wf)
        val api = JSONObject()
        val fields = mutableListOf<PromptField>()
        val notes = mutableListOf<String>()
        val availableLoras by lazy { loraOptions(info) }

        for (node in canvas.executableNodes()) {
            val n = node.raw
            val type = node.type
            val id = node.id

            if (type in noteTypes) {
                n.optJSONArray("widgets_values")?.opt(0)?.let { notes.add(it.toString()) }
                continue
            }

            val def = info.optJSONObject(type)
            if (def == null) {
                if (type != "PrimitiveNode" && type != "Reroute") {
                    canvas.problems += "服务器缺少节点 $type #$id"
                }
                continue
            }
            val title = nodeTitle(n, "title", def, type)
            val inputs = JSONObject()
            val sockets = node.inputs.associateBy { it.optString("name") }
            val widgets = n.optJSONArray("widgets_values")
            val namedWidgets = n.optJSONObject("widgets_values")
            val overrides = node.widgetOverrides()
            val handled = mutableSetOf<String>()
            var widgetIndex = 0

            fun emit(name: String, argument: Argument) {
                if (!handled.add(name)) return
                val socket = sockets[name]
                val linked = socket?.let { node.resolve(it) }
                val config = argument.config
                val field = promptField(id, type, name, title, argument, null)
                val widget = !config.optBoolean("forceInput") &&
                    (field != null || argument.type == DYNAMIC_COMBO || socket?.has("widget") == true ||
                        (config.has("socketless") && !config.optBoolean("socketless", true)))
                val saved = if (widget) widgets?.opt(widgetIndex++) else null
                if (widget && config.has("control_after_generate")) widgetIndex++

                when (linked) {
                    is ComfyCanvas.Input.Link -> {
                        inputs.put(name, JSONArray().put(linked.nodeId).put(linked.output))
                        return
                    }
                    is ComfyCanvas.Input.Unresolved -> {
                        canvas.problems += "$title #$id · $name：${linked.reason}"
                        return
                    }
                    else -> Unit
                }
                if (!widget && linked == null) return
                val value = (linked as? ComfyCanvas.Input.Value)?.value
                    ?: overrides[name] ?: namedWidgets?.opt(name) ?: saved ?: config.opt("default")
                inputs.put(name, wrapLiteral(value))
                if (linked == null || socket?.has("widget") == true) {
                    promptField(id, type, name, title, argument, value)?.let(fields::add)
                }
                if (argument.type == DYNAMIC_COMBO) {
                    dynamicArguments(config, value).forEach { (child, rule) -> emit("$name.$child", rule) }
                }
            }

            arguments(def.optJSONObject("input"), def.optJSONObject("input_order")).forEach { (name, spec) -> emit(name, spec) }
            for ((name, socket) in sockets) {
                if (name.isBlank() || name in handled) continue
                when (val link = node.resolve(socket)) {
                    is ComfyCanvas.Input.Link -> inputs.put(name, JSONArray().put(link.nodeId).put(link.output))
                    is ComfyCanvas.Input.Value -> inputs.put(name, wrapLiteral(link.value))
                    is ComfyCanvas.Input.Unresolved -> canvas.problems += "$title #$id · $name：${link.reason}"
                    null -> (overrides[name] ?: namedWidgets?.opt(name))?.let { inputs.put(name, wrapLiteral(it)) }
                }
            }
            if (type == PowerLoraWidgets.NODE_TYPE) {
                PowerLoraWidgets.restoreInputs(widgets, namedWidgets, overrides, inputs)
                fields += PowerLoraWidgets.fields(id, title, inputs, availableLoras)
            }
            api.put(id, JSONObject()
                .put("class_type", type)
                .put("inputs", inputs)
                .put("_meta", JSONObject().put("title", title)))
        }
        val broken = canvas.problems + promptProblems(api, info)
        return ConvertedWorkflow(api, distinctLabels(fields), emptyList(), notes, broken.distinct(), outputKinds(api, info))
    }

    private data class Argument(val type: Any?, val config: JSONObject, val required: Boolean)

    private fun arguments(input: JSONObject?, order: JSONObject? = null): Map<String, Argument> = buildMap {
        for (group in listOf("required", "optional")) {
            val definitions = input?.optJSONObject(group) ?: continue
            val names = order?.optJSONArray(group)?.let { keys ->
                (0 until keys.length()).map { keys.optString(it) }
            }.orEmpty()
            for (name in (names + definitions.keys().asSequence().toList()).distinct()) {
                val rule = definitions.optJSONArray(name) ?: continue
                put(name, Argument(rule.opt(0), rule.optJSONObject(1) ?: JSONObject(), group == "required"))
            }
        }
    }

    private fun dynamicArguments(config: JSONObject, selection: Any?): Map<String, Argument> {
        val options = config.optJSONArray("options") ?: return emptyMap()
        val option = (0 until options.length()).firstNotNullOfOrNull { index ->
            options.optJSONObject(index)?.takeIf { it.optString("key") == selection?.toString() }
        } ?: return emptyMap()
        return arguments(option.optJSONObject("inputs"), option.optJSONObject("input_order"))
    }

    private fun promptField(
        id: String,
        classType: String,
        input: String,
        title: String,
        argument: Argument,
        value: Any?,
    ): PromptField? {
        val config = argument.config
        val type = argument.type
        val combo = type is JSONArray || type == "COMBO"
        val kind = when {
            config.has("control_after_generate") -> FieldKind.SEED
            config.optBoolean("video_upload") ||
                (combo && input == "video" && classType in setOf("LoadVideo", "VHS_LoadVideo")) -> FieldKind.VIDEO
            config.optBoolean("audio_upload") ||
                (combo && input == "audio" && classType in setOf("LoadAudio", "VHS_LoadAudioUpload")) -> FieldKind.AUDIO
            config.optBoolean("image_upload") -> FieldKind.IMAGE
            type == "STRING" -> FieldKind.TEXT
            combo || type in setOf("INT", "FLOAT", "BOOLEAN", "BOOL") -> FieldKind.PARAM
            else -> return null
        }
        val paramType = when {
            combo -> "combo"
            type == "INT" -> "int"
            type == "FLOAT" -> "float"
            type == "BOOLEAN" || type == "BOOL" -> "bool"
            else -> ""
        }
        val label = config.optString("display_name").ifBlank { input }
        return PromptField(
            key = "$id:$input",
            nodeId = id,
            input = input,
            label = "$title · $label",
            kind = kind,
            value = value?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty(),
            multiline = config.optBoolean("multiline"),
            ptype = if (kind == FieldKind.PARAM) paramType else "",
            options = comboOptions(type, config),
            min = config.opt("min")?.toString(),
            max = config.opt("max")?.toString(),
            required = argument.required,
            referenceRole = if (kind == FieldKind.IMAGE) inferReferenceRole(input, title) else ReferenceRole.AUTO,
        )
    }

    private fun comboOptions(t: Any?, cfg: JSONObject): List<String> {
        val out = mutableListOf<String>()
        if (t is JSONArray) for (i in 0 until t.length()) out.add(t.opt(i).toString())
        cfg.optJSONArray("options")?.let { a -> for (i in 0 until a.length()) out.add(a.optString(i)) }
        return out.filter { it.isNotEmpty() }.distinct()
    }

    private fun wrapLiteral(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is JSONArray, is JSONObject, is Boolean, is Int, is Long, is Double -> v
        is Float -> v.toDouble()
        else -> v.toString()
    }

    private fun apiFormatFields(api: JSONObject, info: JSONObject): ConvertedWorkflow {
        val fields = mutableListOf<PromptField>()
        val availableLoras by lazy { loraOptions(info) }
        api.keys().forEach { id ->
            val node = api.optJSONObject(id) ?: return@forEach
            val type = node.optString("class_type")
            val def = info.optJSONObject(type) ?: return@forEach
            val title = nodeTitle(node.optJSONObject("_meta"), "title", def, type)
            val ins = node.optJSONObject("inputs") ?: return@forEach
            fun addFields(name: String, argument: Argument) {
                val value = ins.opt(name)
                if (isLink(value) || argument.config.optBoolean("forceInput")) return
                if (argument.type == DYNAMIC_COMBO) {
                    dynamicArguments(argument.config, value).forEach { (child, rule) -> addFields("$name.$child", rule) }
                } else {
                    promptField(id, type, name, title, argument, value ?: argument.config.opt("default"))
                        ?.takeIf { value != null || it.required }
                        ?.let(fields::add)
                }
            }
            arguments(def.optJSONObject("input"), def.optJSONObject("input_order")).forEach { (name, argument) ->
                addFields(name, argument)
            }
            if (type == PowerLoraWidgets.NODE_TYPE) {
                fields += PowerLoraWidgets.fields(id, title, ins, availableLoras)
            }
        }
        return ConvertedWorkflow(api, distinctLabels(fields), emptyList(),
            blockingProblems = promptProblems(api, info), outputKinds = outputKinds(api, info))
    }

    private fun loraOptions(info: JSONObject): List<String> =
        listOf("LoraLoader", "LoraLoaderModelOnly").flatMap { type ->
            val rule = arguments(info.optJSONObject(type)?.optJSONObject("input"))["lora_name"]
            if (rule == null) emptyList() else comboOptions(rule.type, rule.config)
        }.distinct()

    private fun nodeTitle(node: JSONObject?, key: String, schema: JSONObject, classType: String): String =
        sequenceOf(node?.opt(key), schema.opt("display_name"))
            .filterIsInstance<String>().firstOrNull { it.isNotBlank() && it != "null" } ?: classType

    private fun distinctLabels(fields: List<PromptField>): List<PromptField> {
        val count = fields.groupingBy(PromptField::label).eachCount()
        return fields.map { field ->
            if (count.getValue(field.label) == 1) field else field.copy(label = "${field.label} · #${field.nodeId}")
        }
    }

    private fun isLink(value: Any?): Boolean =
        value is JSONArray && value.length() == 2 && value.opt(1) is Number &&
            (value.opt(0) is String || value.opt(0) is Number)

    private fun outputKinds(api: JSONObject, info: JSONObject): Set<String> = buildSet {
        for (id in api.keys()) {
            val node = api.optJSONObject(id) ?: continue
            val type = node.optString("class_type")
            val schema = info.optJSONObject(type) ?: continue
            when {
                type == "VHS_VideoCombine" -> {
                    val format = node.optJSONObject("inputs")?.optString("format").orEmpty()
                    if (format.startsWith("video/")) add("video")
                    else if (format.startsWith("image/")) add("image")
                }
                type == "SaveVideo" -> add("video")
                type in setOf("SaveAudio", "SaveAudioMP3", "SaveAudioOpus", "SaveAudioWAV", "PreviewAudio") ->
                    add("audio")
                type in setOf("SaveImage", "PreviewImage", "SaveAnimatedWEBP", "SaveAnimatedPNG") -> add("image")
                schema.optBoolean("output_node") -> {
                    val sockets = arguments(schema.optJSONObject("input")).values.map { it.type }
                    when {
                        "VIDEO" in sockets -> add("video")
                        "IMAGE" in sockets -> add("image")
                        "AUDIO" in sockets -> add("audio")
                    }
                }
            }
        }
    }

    private fun promptProblems(api: JSONObject, info: JSONObject): List<String> = buildList {
        if (api.length() == 0) add("工作流没有可执行节点")
        for (id in api.keys()) {
            val node = api.optJSONObject(id)
            val type = node?.optString("class_type").orEmpty()
            val inputs = node?.optJSONObject("inputs")
            if (type.isBlank() || inputs == null) {
                add("节点 #$id 缺少 class_type 或 inputs")
                continue
            }
            if (!info.has(type)) add("服务器缺少节点 $type #$id")
            for (name in inputs.keys()) {
                val value = inputs.opt(name)
                if (isLink(value)) {
                    val link = value as JSONArray
                    if (!api.has(link.optString(0))) add("$type #$id · $name：来源节点 #${link.optString(0)} 不可执行")
                }
            }
        }
    }

    /**
     * Applies user edits on top of the converted prompt (deep copy).
     * Returns the prompt plus the seed values actually used (key -> seed string),
     * so the UI can show what to reuse for reproduction.
     */
    fun buildPrompt(
        converted: ConvertedWorkflow,
        values: Map<String, String>,
        randomSeed: Boolean
    ): Pair<JSONObject, Map<String, String>> {
        converted.blockingProblems.firstOrNull()?.let { throw PromptValidationException(it) }
        val out = JSONObject(converted.prompt.toString())
        val seeds = HashMap<String, String>()
        for (f in converted.fields) {
            val node = out.optJSONObject(f.nodeId) ?: continue
            val ins = node.optJSONObject("inputs") ?: continue
            when (f.kind) {
                FieldKind.TEXT -> values[f.key]?.let { ins.put(f.input, it) }
                FieldKind.IMAGE, FieldKind.AUDIO, FieldKind.VIDEO -> values[f.key]?.let {
                    if (it.isBlank()) ins.remove(f.input) else ins.put(f.input, it)
                }
                FieldKind.SEED -> {
                    val used = if (randomSeed) Random.nextLong(SEED_MAX)
                    else values[f.key]?.toLongOrNull() ?: ins.optLong(f.input, 0L)
                    ins.put(f.input, used)
                    seeds[f.key] = used.toString()
                }
                FieldKind.PARAM -> {
                    val raw = values[f.key] ?: continue
                    val nested = if (node.optString("class_type") == PowerLoraWidgets.NODE_TYPE)
                        PowerLoraWidgets.fieldTarget(ins, f.input) else null
                    val target = nested?.first ?: ins
                    val input = nested?.second ?: f.input
                    when (f.ptype) {
                        "int" -> raw.toLongOrNull()?.let { target.put(input, it) }
                        "float" -> raw.toDoubleOrNull()?.let { target.put(input, it) }
                        "bool" -> when (raw.lowercase()) {
                            "true" -> target.put(input, true)
                            "false" -> target.put(input, false)
                        }
                        "combo", "string" -> if (raw.isNotEmpty()) target.put(input, raw)
                    }
                }
            }
        }
        return Pair(out, seeds)
    }
}

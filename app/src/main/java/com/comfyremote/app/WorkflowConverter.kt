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
    /** Row groups the user can extend on the phone, keyed by `nodeId:group`. */
    val dynamicGroups: Map<String, DynamicGroup> = emptyMap(),
)

/**
 * One COMFY_DYNAMICGROUP_V3 input: a repeatable set of rows such as ComfyUI's
 * LoRA stack. The workflow file fixes the first [savedRows] rows; the rest were
 * added on the phone and only exist in the converted prompt.
 */
class DynamicGroup(
    val nodeId: String,
    val name: String,
    /** Row heading shown in field labels, e.g. "LoRA". */
    val heading: String,
    val min: Int,
    val max: Int,
    /** Rows present in the saved workflow. */
    val savedRows: Int,
    /** Rows present in the converted prompt. */
    val rows: Int,
    internal val template: Map<String, WorkflowConverter.Argument>,
    internal val title: String,
) {
    val key: String get() = "$nodeId:$name"

    /** Rows beyond the ones the saved workflow contains; they live only in the converted prompt. */
    val addedRows: Int get() = (rows - savedRows).coerceAtLeast(0)

    /** False at the row limit or when the server offers nothing to pick for a new row. */
    val canAddRows: Boolean = rows < max && template.values.all { rule ->
        val combo = rule.type is JSONArray || rule.type == "COMBO"
        !combo || WorkflowConverter.comboOptions(rule.type, rule.config).isNotEmpty()
    }
}

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
    /** A repeatable set of rows; the API prompt carries them as `<group>.<index>.<field>`. */
    internal const val DYNAMIC_GROUP = "COMFY_DYNAMICGROUP_V3"
    /** The server refuses any group with more rows than this. */
    internal const val DYNAMIC_GROUP_MAX_ROWS = 20

    fun convert(wfJson: String, info: JSONObject): ConvertedWorkflow {
        val wf = JSONObject(wfJson)
        if (wf.optJSONArray("nodes") == null) return apiFormatFields(wf.optJSONObject("prompt") ?: wf, info)

        val canvas = ComfyCanvas(wf)
        val api = JSONObject()
        val fields = mutableListOf<PromptField>()
        val notes = mutableListOf<String>()
        val groups = linkedMapOf<String, DynamicGroup>()
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
            val rowHeadings = HashMap<String, String>()
            var widgetIndex = 0

            fun emit(name: String, argument: Argument) {
                if (!handled.add(name)) return
                val socket = sockets[name]
                val linked = socket?.let { node.resolve(it) }
                val config = argument.config
                val displayName = rowHeadings[name]?.let { "$it · ${inputLabel(config, name.substringAfterLast('.'))}" }
                val field = promptField(id, type, name, title, argument, null, displayName)
                val group = argument.type == DYNAMIC_GROUP
                val widget = !config.optBoolean("forceInput") &&
                    (field != null || group || argument.type == DYNAMIC_COMBO || socket?.has("widget") == true ||
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
                if (group) {
                    // The counter itself is never sent: each row is flattened into
                    // `<group>.<index>.<field>` inputs, exactly as the frontend does.
                    val template = groupTemplate(config, sockets.keys.toList(), name)
                    val heading = groupHeading(config, name)
                    val max = groupMax(config)
                    val savedRows = groupRowCount(overrides[name] ?: namedWidgets?.opt(name) ?: saved, sockets.keys, name)
                    val count = minOf(savedRows, max)
                    if (savedRows > max) {
                        // Never expand a damaged counter; the workflow is blocked below anyway.
                        canvas.problems += "$title #$id · $name：行数 $savedRows 超出服务器上限 $max"
                    }
                    groups["$id:$name"] = DynamicGroup(
                        id, name, heading, config.optInt("min", 0), max, count, count, template, title,
                    )
                    for (row in 0 until count) template.forEach { (child, rule) ->
                        val key = "$name.$row.$child"
                        rowHeadings[key] = "$heading ${row + 1}"
                        emit(key, rule)
                    }
                    return
                }
                val value = (linked as? ComfyCanvas.Input.Value)?.value
                    ?: overrides[name] ?: namedWidgets?.opt(name) ?: saved ?: config.opt("default")
                // Row values are read by position, so a value of the wrong kind means
                // the rows were laid out differently than this conversion assumes.
                if (rowHeadings.containsKey(name) && linked == null && !matchesInputKind(value, argument.type)) {
                    canvas.problems += "$title #$id · $name：保存的行数据与服务器模板不一致，请在电脑端重新打开并保存该工作流"
                }
                inputs.put(name, wrapLiteral(value))
                if (linked == null || socket?.has("widget") == true) {
                    promptField(id, type, name, title, argument, value, displayName)?.let(fields::add)
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
        return ConvertedWorkflow(api, distinctLabels(fields), emptyList(), notes, broken.distinct(), outputKinds(api, info), groups)
    }

    /** One input definition: its type, options and whether the schema requires it. */
    data class Argument(val type: Any?, val config: JSONObject, val required: Boolean)

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
        displayName: String? = null,
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
        val label = displayName ?: inputLabel(config, input)
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

    internal fun comboOptions(t: Any?, cfg: JSONObject): List<String> {
        val out = mutableListOf<String>()
        if (t is JSONArray) for (i in 0 until t.length()) out.add(t.opt(i).toString())
        cfg.optJSONArray("options")?.let { a -> for (i in 0 until a.length()) out.add(a.optString(i)) }
        return out.filter { it.isNotEmpty() }.distinct()
    }

    private fun wrapLiteral(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is JSONArray, is JSONObject, is Boolean, is Int, is Long, is Double -> v
        // Decimal parsers other than Android's hand back BigDecimal or Float;
        // the server expects a number, never its text.
        is Number -> v.toDouble()
        else -> v.toString()
    }

    private fun apiFormatFields(api: JSONObject, info: JSONObject): ConvertedWorkflow {
        val fields = mutableListOf<PromptField>()
        val groups = linkedMapOf<String, DynamicGroup>()
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
            fun addGroup(name: String, argument: Argument) {
                val template = arguments(argument.config.optJSONObject("template"))
                val heading = groupHeading(argument.config, name)
                val rows = groupRows(ins, name, template)
                groups["$id:$name"] = DynamicGroup(
                    id, name, heading, argument.config.optInt("min", 0), groupMax(argument.config),
                    rows.size, rows.size, template, title,
                )
                rows.forEach { row ->
                    val rule = template.getValue(row.field)
                    val value = ins.opt(row.key)
                    if (isLink(value) || rule.config.optBoolean("forceInput")) return@forEach
                    promptField(
                        id, type, row.key, title, rule, value,
                        "$heading ${row.index + 1} · ${inputLabel(rule.config, row.field)}",
                    )?.let(fields::add)
                }
            }
            arguments(def.optJSONObject("input"), def.optJSONObject("input_order")).forEach { (name, argument) ->
                if (argument.type == DYNAMIC_GROUP) addGroup(name, argument) else addFields(name, argument)
            }
            if (type == PowerLoraWidgets.NODE_TYPE) {
                fields += PowerLoraWidgets.fields(id, title, ins, availableLoras)
            }
        }
        return ConvertedWorkflow(api, distinctLabels(fields), emptyList(),
            blockingProblems = promptProblems(api, info), outputKinds = outputKinds(api, info), dynamicGroups = groups)
    }

    private fun inputLabel(config: JSONObject, input: String): String =
        (config.opt("display_name") as? String)?.takeIf { it.isNotBlank() && it != "null" } ?: input

    /** Splits `<group>.<index>.<field>` into its zero-based row index and template field. */
    private fun rowOf(input: String, group: String): Pair<Int, String>? {
        if (!input.startsWith("$group.")) return null
        val separator = input.indexOf('.', group.length + 1)
        if (separator < 0) return null
        val index = input.substring(group.length + 1, separator)
        // The server only accepts plain decimal indices without leading zeros.
        if (index.isEmpty() || (index.length > 1 && index[0] == '0') || index.any { it !in '0'..'9' }) return null
        val field = input.substring(separator + 1)
        return if (field.isEmpty() || field.contains('.')) null else index.toInt() to field
    }

    private fun groupHeading(config: JSONObject, group: String): String =
        (config.opt("group_name") as? String)?.takeIf(String::isNotBlank) ?: group

    /** Whether a saved widget value has the kind of value its input definition takes. */
    private fun matchesInputKind(value: Any?, type: Any?): Boolean {
        if (value == null || value == JSONObject.NULL) return true
        val name = type as? String
        return when {
            type is JSONArray || name == "COMBO" -> value is String || value is Number
            name == "STRING" -> value is String
            name == "INT" || name == "LONG" ->
                (value as? Number)?.toDouble()?.let { it == Math.floor(it) && !it.isInfinite() } == true
            name == "FLOAT" || name == "NUMBER" -> value is Number
            name == "BOOLEAN" || name == "BOOL" -> value is Boolean
            else -> true
        }
    }

    private fun groupMax(config: JSONObject): Int =
        config.optInt("max", DYNAMIC_GROUP_MAX_ROWS).takeIf { it >= 1 } ?: DYNAMIC_GROUP_MAX_ROWS

    private fun groupRowCount(counter: Any?, listed: Set<String>, group: String): Int {
        val value = counter?.takeUnless { it == JSONObject.NULL }
        val number = (value as? Number)?.toInt() ?: value?.toString()?.toDoubleOrNull()?.toInt()
        if (number != null) return number.coerceAtLeast(0)
        // Without a usable counter the rows are whatever the canvas still lists.
        return (listed.mapNotNull { rowOf(it, group)?.first }.maxOrNull() ?: -1) + 1
    }

    /**
     * Row fields in widget order. The server lists them in template order; when the
     * canvas still lists every field the saved slot order is used instead, because
     * that is the order the frontend wrote the row values in.
     */
    private fun groupTemplate(config: JSONObject, listed: List<String>, group: String): Map<String, Argument> {
        val template = arguments(config.optJSONObject("template"))
        val listedFields = listed.mapNotNull { rowOf(it, group)?.second }.distinct()
        if (listedFields.size != template.size) return template
        return listedFields.mapNotNull { field -> template[field]?.let { field to it } }.toMap(linkedMapOf())
    }

    private class GroupRow(val key: String, val index: Int, val field: String)

    /** Every well-formed row key of [group] present in [inputs], in row and template order. */
    private fun groupRows(inputs: JSONObject, group: String, template: Map<String, Argument>): List<GroupRow> {
        val order = template.keys.withIndex().associate { it.value to it.index }
        return inputs.keys().asSequence()
            .mapNotNull { key -> rowOf(key, group)?.let { key to it } }
            .filter { it.second.second in template }
            .map { GroupRow(it.first, it.second.first, it.second.second) }
            .sortedWith(compareBy<GroupRow> { it.index }.thenBy { order.getValue(it.field) })
            .toList()
    }

    /** Draft key that remembers how many rows a group has after edits made on the phone. */
    fun groupRowsKey(groupKey: String): String = "$groupKey#rows"

    /**
     * Rebuilds rows the user added on the phone. They exist only in the converted
     * prompt, so the saved workflow on its own comes back without them.
     */
    fun restoreGroupRows(converted: ConvertedWorkflow, values: Map<String, String>): ConvertedWorkflow {
        var restored = converted
        converted.dynamicGroups.values.forEach { group ->
            val rows = values[groupRowsKey(group.key)]?.toIntOrNull() ?: return@forEach
            if (rows > group.rows) restored = withGroupRows(restored, group.key, rows)
        }
        return restored
    }

    /**
     * Resizes a row group inside [converted]. Existing rows keep their values, new
     * rows start from the server template defaults and rows are removed from the
     * end. Returns [converted] as is when the request cannot be honoured.
     */
    fun withGroupRows(converted: ConvertedWorkflow, groupKey: String, rows: Int): ConvertedWorkflow {
        val group = converted.dynamicGroups[groupKey] ?: return converted
        if (rows !in group.min..group.max || rows == group.rows) return converted
        // A group that already breaks its limits needs a repaired workflow, not more edits.
        if (group.rows < group.min) return converted
        if (rows > group.rows && !group.canAddRows) return converted

        val prompt = JSONObject(converted.prompt.toString())
        val node = prompt.getJSONObject(group.nodeId)
        val inputs = node.getJSONObject("inputs")
        val classType = node.optString("class_type")
        val indexes = groupRows(inputs, group.name, group.template).map { it.index }.distinct().sorted()
        val keptCount = minOf(rows, indexes.size)
        indexes.drop(keptCount).forEach { index ->
            inputs.keys().asSequence().filter { it.startsWith("${group.name}.$index.") }.toList().forEach(inputs::remove)
        }

        val isRow = { field: PromptField -> field.nodeId == group.nodeId && rowOf(field.input, group.name) != null }
        val firstRowField = converted.fields.indexOfFirst(isRow)
        val kept = indexes.take(keptCount).toSet()
        val rowFields = converted.fields.filter(isRow)
            .filter { rowOf(it.input, group.name)!!.first in kept }
            .toMutableList()
        var next = (indexes.lastOrNull() ?: -1) + 1
        repeat(rows - keptCount) {
            val index = next++
            group.template.forEach { (field, rule) ->
                val key = "${group.name}.$index.$field"
                val value = templateDefault(rule)
                inputs.put(key, wrapLiteral(value))
                promptField(
                    group.nodeId, classType, key, group.title, rule, value,
                    "${group.heading} ${index + 1} · ${inputLabel(rule.config, field)}",
                )?.let(rowFields::add)
            }
        }
        val fields = converted.fields.filterNot(isRow).toMutableList().apply {
            addAll(firstRowField.coerceIn(0, size), rowFields)
        }
        return ConvertedWorkflow(
            prompt, fields, converted.warnings, converted.notes, converted.blockingProblems, converted.outputKinds,
            converted.dynamicGroups + (groupKey to DynamicGroup(
                group.nodeId, group.name, group.heading, group.min, group.max, group.savedRows, rows,
                group.template, group.title,
            )),
        )
    }

    private fun templateDefault(rule: Argument): Any? =
        rule.config.opt("default")?.takeUnless { it == JSONObject.NULL }
            ?: if (rule.type is JSONArray || rule.type == "COMBO") comboOptions(rule.type, rule.config).firstOrNull() else null

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
            // A row group outside its limits is refused by the server; report it up front.
            arguments(info.optJSONObject(type)?.optJSONObject("input")).forEach { (name, argument) ->
                if (argument.type != DYNAMIC_GROUP) return@forEach
                val rows = groupRows(inputs, name, arguments(argument.config.optJSONObject("template"))).distinctBy { it.index }.size
                val min = argument.config.optInt("min", 0)
                val max = groupMax(argument.config)
                if (rows !in min..max) add("$type #$id · $name：需要 $min–$max 行，当前 $rows 行")
            }
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

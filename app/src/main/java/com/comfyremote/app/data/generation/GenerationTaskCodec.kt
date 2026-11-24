package com.comfyremote.app.data.generation

import com.comfyremote.app.FieldKind
import com.comfyremote.app.PromptField
import com.comfyremote.app.data.reference.ReferenceRole
import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON codec so persisted runtime tasks can be regression-tested on the JVM. */
object GenerationTaskCodec {
    fun encode(tasks: List<GenerationTask>): String = JSONArray().apply {
        tasks.forEach { put(taskToJson(it)) }
    }.toString()

    fun decode(raw: String): List<GenerationTask> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::taskFromJson)
        }
    }.getOrDefault(emptyList())

    private fun taskToJson(task: GenerationTask): JSONObject = JSONObject()
        .put("taskId", task.taskId)
        .put("request", requestToJson(task.request))
        .put("phase", task.phase.name)
        .put("promptId", task.promptId ?: JSONObject.NULL)
        .put("clientId", task.clientId ?: JSONObject.NULL)
        .put("queueRemaining", task.queueRemaining ?: JSONObject.NULL)
        .put("queuePosition", task.queuePosition ?: JSONObject.NULL)
        .put("nodeId", task.nodeId ?: JSONObject.NULL)
        .put("nodeTitle", task.nodeTitle ?: JSONObject.NULL)
        .put("progress", task.progress ?: JSONObject.NULL)
        .put("createdAt", task.createdAt)
        .put("startedAt", task.startedAt ?: JSONObject.NULL)
        .put("completedAt", task.completedAt ?: JSONObject.NULL)
        .put("results", JSONArray().apply { task.results.forEach { put(resultToJson(it)) } })
        .put("error", task.error?.let(::errorToJson) ?: JSONObject.NULL)
        .put("seedInfo", task.seedInfo)
        .put("seedValues", JSONObject(task.seedValues))
        .put("recovered", task.recovered)
        .put("recoveryNote", task.recoveryNote ?: JSONObject.NULL)
        .put("localPending", task.localPending)
        .put("spendRecorded", task.spendRecorded)
        .put("batchId", task.batchId ?: JSONObject.NULL)
        .put("batchIndex", task.batchIndex ?: JSONObject.NULL)
        .put("batchSize", task.batchSize ?: JSONObject.NULL)

    private fun taskFromJson(o: JSONObject): GenerationTask? {
        val request = o.optJSONObject("request")?.let(::requestFromJson) ?: return null
        val phase = runCatching { GenerationPhase.valueOf(o.optString("phase")) }
            .getOrDefault(GenerationPhase.FAILED)
        return GenerationTask(
            taskId = o.optString("taskId", request.id),
            request = request,
            phase = phase,
            promptId = nullableString(o, "promptId"),
            clientId = nullableString(o, "clientId"),
            queueRemaining = nullableInt(o, "queueRemaining"),
            queuePosition = nullableInt(o, "queuePosition"),
            nodeId = nullableString(o, "nodeId"),
            nodeTitle = nullableString(o, "nodeTitle"),
            progress = nullableDouble(o, "progress")?.toFloat(),
            preview = null,
            createdAt = o.optLong("createdAt", request.createdAt),
            startedAt = nullableLong(o, "startedAt"),
            completedAt = nullableLong(o, "completedAt"),
            results = resultList(o.optJSONArray("results")),
            error = o.optJSONObject("error")?.let(::errorFromJson),
            seedInfo = o.optString("seedInfo"),
            seedValues = stringMap(o.optJSONObject("seedValues")),
            recovered = o.optBoolean("recovered", false),
            recoveryNote = nullableString(o, "recoveryNote"),
            localPending = o.optBoolean("localPending", false),
            spendRecorded = o.optBoolean("spendRecorded", false),
            batchId = nullableString(o, "batchId"),
            batchIndex = nullableInt(o, "batchIndex"),
            batchSize = nullableInt(o, "batchSize"),
        )
    }

    private fun requestToJson(request: GenerationRequest): JSONObject = JSONObject()
        .put("id", request.id)
        .put("provider", request.provider)
        .put("providerLabel", request.providerLabel)
        .put("serverUrl", request.serverUrl)
        .put("workflowPath", request.workflowPath)
        .put("workflowName", request.workflowName)
        .put("fieldValues", JSONObject(request.fieldValues))
        .put("randomSeed", request.randomSeed)
        .put("nodeTitles", JSONObject(request.nodeTitles))
        .put("fields", JSONArray().apply { request.fields.forEach { put(fieldToJson(it)) } })
        .put("convertedPrompt", JSONObject(request.convertedPrompt.toString()))
        .put("createdAt", request.createdAt)
        .put("estimatedCloudCost", request.estimatedCloudCost ?: JSONObject.NULL)
        .put("storyboardProjectId", request.storyboardProjectId ?: JSONObject.NULL)
        .put("storyboardShotId", request.storyboardShotId ?: JSONObject.NULL)
        .put("studioStageKind", request.studioStageKind ?: JSONObject.NULL)

    private fun requestFromJson(o: JSONObject): GenerationRequest? = runCatching {
        GenerationRequest(
            id = o.optString("id"),
            provider = o.optString("provider", "comfyui"),
            providerLabel = o.optString("providerLabel"),
            serverUrl = o.getString("serverUrl"),
            workflowPath = o.getString("workflowPath"),
            workflowName = o.optString("workflowName"),
            fieldValues = stringMap(o.optJSONObject("fieldValues")),
            randomSeed = o.optBoolean("randomSeed", true),
            nodeTitles = stringMap(o.optJSONObject("nodeTitles")),
            fields = fieldList(o.optJSONArray("fields")),
            convertedPrompt = o.optJSONObject("convertedPrompt") ?: JSONObject(),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            estimatedCloudCost = nullableInt(o, "estimatedCloudCost"),
            storyboardProjectId = nullableString(o, "storyboardProjectId"),
            storyboardShotId = nullableString(o, "storyboardShotId"),
            studioStageKind = nullableString(o, "studioStageKind"),
        )
    }.getOrNull()

    private fun fieldToJson(field: PromptField): JSONObject = JSONObject()
        .put("key", field.key)
        .put("nodeId", field.nodeId)
        .put("input", field.input)
        .put("label", field.label)
        .put("kind", field.kind.name)
        .put("value", field.value)
        .put("multiline", field.multiline)
        .put("ptype", field.ptype)
        .put("options", JSONArray().apply { field.options.forEach { put(it) } })
        .put("min", field.min ?: JSONObject.NULL)
        .put("max", field.max ?: JSONObject.NULL)
        .put("required", field.required)
        .put("referenceRole", field.referenceRole.name)

    private fun fieldList(array: JSONArray?): List<PromptField> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            val kind = runCatching { FieldKind.valueOf(o.optString("kind")) }.getOrDefault(FieldKind.PARAM)
            PromptField(
                key = o.optString("key"),
                nodeId = o.optString("nodeId"),
                input = o.optString("input"),
                label = o.optString("label"),
                kind = kind,
                value = o.optString("value"),
                multiline = o.optBoolean("multiline", false),
                ptype = o.optString("ptype"),
                options = stringList(o.optJSONArray("options")),
                min = nullableString(o, "min"),
                max = nullableString(o, "max"),
                required = o.optBoolean("required", false),
                referenceRole = ReferenceRole.fromStorage(o.optString("referenceRole")),
            )
        }
    }

    private fun resultToJson(result: ResultImage): JSONObject = JSONObject()
        .put("id", result.id)
        .put("localPath", result.localPath)
        .put("width", result.width ?: JSONObject.NULL)
        .put("height", result.height ?: JSONObject.NULL)

    private fun resultList(array: JSONArray?): List<ResultImage> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val o = array.optJSONObject(index) ?: return@mapNotNull null
            ResultImage(
                id = o.optString("id"),
                localPath = o.optString("localPath"),
                width = nullableInt(o, "width"),
                height = nullableInt(o, "height"),
            )
        }
    }

    private fun errorToJson(error: GenerationError): JSONObject = JSONObject()
        .put("kind", error.kind.name)
        .put("message", error.message)
        .put("nodeId", error.nodeId ?: JSONObject.NULL)
        .put("nodeType", error.nodeType ?: JSONObject.NULL)
        .put("nodeTitle", error.nodeTitle ?: JSONObject.NULL)

    private fun errorFromJson(o: JSONObject): GenerationError {
        val kind = runCatching { ErrorKind.valueOf(o.optString("kind")) }.getOrDefault(ErrorKind.UNKNOWN)
        return GenerationError(
            kind = kind,
            message = o.optString("message"),
            nodeId = nullableString(o, "nodeId"),
            nodeType = nullableString(o, "nodeType"),
            nodeTitle = nullableString(o, "nodeTitle"),
        )
    }

    private fun stringMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        o.keys().forEach { key -> result[key] = o.optString(key) }
        return result
    }

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        return (0 until a.length()).map { a.optString(it) }
    }

    private fun nullableString(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

    private fun nullableInt(o: JSONObject, key: String): Int? =
        if (!o.has(key) || o.isNull(key)) null else o.optInt(key)

    private fun nullableLong(o: JSONObject, key: String): Long? =
        if (!o.has(key) || o.isNull(key)) null else o.optLong(key)

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key)
}

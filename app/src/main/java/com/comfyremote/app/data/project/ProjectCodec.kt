package com.comfyremote.app.data.project

import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ProjectAsset
import com.comfyremote.app.data.reference.ReferenceRole
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Pure JSON codec for the Studio project index. Unlike the legacy storyboard
 * index the file carries an explicit schema [VERSION] envelope so future
 * migrations can branch on it; fields are still read tolerantly so older
 * files always decode.
 */
object ProjectCodec {
    private const val VERSION = 2

    fun encode(projects: List<VideoProject>): String = JSONObject()
        .put("version", VERSION)
        .put("projects", JSONArray().apply { projects.forEach { put(projectToJson(it)) } })
        .toString()

    fun decode(raw: String): List<VideoProject> = runCatching {
        val root = JSONObject(raw)
        val array = root.optJSONArray("projects") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::projectFromJson)
        }
    }.getOrDefault(emptyList())

    /**
     * Number of project rows actually present in the raw file, or null when the
     * file is not parseable JSON at all. Stores use this as the corruption
     * guard: rows that decode() had to drop must never be overwritten silently.
     */
    fun rawProjectCount(raw: String): Int? = runCatching {
        // A missing or wrong-type projects field is damage, not an empty index.
        JSONObject(raw).optJSONArray("projects")?.length()
    }.getOrNull()

    private fun projectToJson(project: VideoProject): JSONObject = JSONObject()
        .put("id", project.id)
        .put("title", project.title)
        .put("synopsis", project.synopsis)
        .put("aspectRatio", project.aspectRatio)
        .put("targetDurationSeconds", project.targetDurationSeconds ?: JSONObject.NULL)
        .put("stylePrompt", project.stylePrompt)
        .put("workflowId", project.workflowId)
        .put("workflowPath", project.workflowPath)
        .put("providerId", project.providerId)
        .put("defaultValues", JSONObject(project.defaultValues))
        .put("workflowRecipeId", project.workflowRecipeId)
        .put("stageWorkflowPaths", JSONObject(project.stageWorkflowPaths))
        .put("stageMediaBindings", JSONObject(project.stageMediaBindings))
        .put("chainFrames", project.chainFrames)
        .put("budget", project.budget ?: JSONObject.NULL)
        .put("createdAt", project.createdAt)
        .put("updatedAt", project.updatedAt)
        .put("shots", JSONArray().apply { project.shots.forEach { put(shotToJson(it)) } })
        .put("assets", JSONArray().apply { project.assets.forEach { put(assetToJson(it)) } })
        .put("profiles", JSONArray().apply { project.profiles.forEach { put(profileToJson(it)) } })
        .put("timeline", project.timeline?.let(::timelineToJson) ?: JSONObject.NULL)

    private fun projectFromJson(o: JSONObject): VideoProject? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val shots = mutableListOf<ProjectShot>()
        val shotArray = o.optJSONArray("shots") ?: JSONArray()
        for (i in 0 until shotArray.length()) {
            shotArray.optJSONObject(i)?.let { shots += shotFromJson(it) }
        }
        val assets = mutableListOf<ProjectAsset>()
        o.optJSONArray("assets")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { assets += assetFromJson(it) }
            }
        }
        val profiles = mutableListOf<AssetProfile>()
        o.optJSONArray("profiles")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { json -> profileFromJson(json)?.let { profiles += it } }
            }
        }
        return VideoProject(
            id = id,
            title = o.optString("title", "Project"),
            synopsis = o.optString("synopsis"),
            aspectRatio = o.optString("aspectRatio"),
            targetDurationSeconds = nullableInt(o, "targetDurationSeconds"),
            stylePrompt = o.optString("stylePrompt"),
            workflowId = o.optString("workflowId"),
            workflowPath = o.optString("workflowPath"),
            providerId = o.optString("providerId"),
            defaultValues = stringMap(o.optJSONObject("defaultValues")),
            workflowRecipeId = o.optString("workflowRecipeId")
                .takeIf(StudioWorkflowRecipes::supports) ?: StudioWorkflowRecipes.TEXT_TO_VIDEO,
            stageWorkflowPaths = stringMap(o.optJSONObject("stageWorkflowPaths"))
                .filterKeys(StudioWorkflowRecipes::isModelKind),
            stageMediaBindings = stringMap(o.optJSONObject("stageMediaBindings"))
                .filterKeys { it.length <= 512 && "::" in it }
                .filterValues { it.isNotBlank() && it.length <= 128 }
                .entries.take(128).associate { it.key to it.value },
            chainFrames = o.optBoolean("chainFrames", true),
            budget = nullableDouble(o, "budget"),
            shots = shots,
            assets = assets,
            profiles = profiles,
            timeline = o.optJSONObject("timeline")?.let(::timelineFromJson),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }

    private fun shotToJson(shot: ProjectShot): JSONObject = JSONObject()
        .put("id", shot.id)
        .put("prompt", shot.prompt)
        .put("cameraNotes", shot.cameraNotes ?: JSONObject.NULL)
        .put("plannedDurationSeconds", shot.plannedDurationSeconds ?: JSONObject.NULL)
        .put("referencePath", shot.referencePath ?: JSONObject.NULL)
        .put("referenceManual", shot.referenceManual)
        .put("referenceRole", shot.referenceRole.name)
        .put("referenceAssetIds", JSONArray().apply { shot.referenceAssetIds.forEach { put(it) } })
        .put("firstFrameAssetId", shot.firstFrameAssetId ?: JSONObject.NULL)
        .put("lastFrameAssetId", shot.lastFrameAssetId ?: JSONObject.NULL)
        .put("workflowPath", shot.workflowPath ?: JSONObject.NULL)
        .put("providerId", shot.providerId ?: JSONObject.NULL)
        .put("workflowId", shot.workflowId ?: JSONObject.NULL)
        .put("values", JSONObject(shot.values))
        .put("lastFramePath", shot.lastFramePath ?: JSONObject.NULL)
        .put("tailFrameStored", shot.tailFrameStored)
        .put("status", shot.status)
        .put("taskId", shot.taskId ?: JSONObject.NULL)
        .put("resultPath", shot.resultPath ?: JSONObject.NULL)
        .put("error", shot.error ?: JSONObject.NULL)
        .put("takes", JSONArray().apply { shot.takes.forEach { put(takeToJson(it)) } })
        .put("selectedTakeId", shot.selectedTakeId ?: JSONObject.NULL)
        .put("createdAt", shot.createdAt)
        .put("updatedAt", shot.updatedAt)

    private fun shotFromJson(o: JSONObject): ProjectShot {
        val takes = mutableListOf<ShotTake>()
        o.optJSONArray("takes")?.let { array ->
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { takes += takeFromJson(it) }
            }
        }
        return ProjectShot(
            id = o.optString("id", UUID.randomUUID().toString()),
            prompt = o.optString("prompt"),
            cameraNotes = nullableString(o, "cameraNotes"),
            plannedDurationSeconds = nullableInt(o, "plannedDurationSeconds")?.takeIf { it in 1..3600 },
            referencePath = nullableString(o, "referencePath"),
            referenceManual = o.optBoolean("referenceManual", false),
            referenceRole = ReferenceRole.fromStorage(o.optString("referenceRole")),
            referenceAssetIds = stringList(o.optJSONArray("referenceAssetIds")),
            firstFrameAssetId = nullableString(o, "firstFrameAssetId"),
            lastFrameAssetId = nullableString(o, "lastFrameAssetId"),
            workflowPath = nullableString(o, "workflowPath"),
            providerId = nullableString(o, "providerId"),
            workflowId = nullableString(o, "workflowId"),
            values = stringMap(o.optJSONObject("values")),
            lastFramePath = nullableString(o, "lastFramePath"),
            tailFrameStored = o.optBoolean("tailFrameStored", false),
            status = o.optString("status", "draft"),
            taskId = nullableString(o, "taskId"),
            resultPath = nullableString(o, "resultPath"),
            error = nullableString(o, "error"),
            takes = takes,
            selectedTakeId = nullableString(o, "selectedTakeId"),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }

    private fun takeToJson(take: ShotTake): JSONObject = JSONObject()
        .put("id", take.id)
        .put("providerId", take.providerId)
        .put("workflowPath", take.workflowPath)
        .put("prompt", take.prompt)
        .put("seed", take.seed ?: JSONObject.NULL)
        .put("values", JSONObject(take.values))
        .put("referenceAssetIds", JSONArray().apply { take.referenceAssetIds.forEach { put(it) } })
        .put("status", take.status)
        .put("taskId", take.taskId ?: JSONObject.NULL)
        .put("resultPath", take.resultPath ?: JSONObject.NULL)
        .put("error", take.error ?: JSONObject.NULL)
        .put("costEstimate", take.costEstimate ?: JSONObject.NULL)
        .put("favorite", take.favorite)
        .put("createdAt", take.createdAt)

    private fun takeFromJson(o: JSONObject): ShotTake = ShotTake(
        id = o.optString("id", UUID.randomUUID().toString()),
        providerId = o.optString("providerId"),
        workflowPath = o.optString("workflowPath"),
        prompt = o.optString("prompt"),
        seed = nullableString(o, "seed"),
        values = stringMap(o.optJSONObject("values")),
        referenceAssetIds = stringList(o.optJSONArray("referenceAssetIds")),
        status = o.optString("status", "running"),
        taskId = nullableString(o, "taskId"),
        resultPath = nullableString(o, "resultPath"),
        error = nullableString(o, "error"),
        costEstimate = nullableDouble(o, "costEstimate"),
        favorite = o.optBoolean("favorite", false),
        createdAt = o.optLong("createdAt"),
    )

    private fun assetToJson(asset: ProjectAsset): JSONObject = JSONObject()
        .put("id", asset.id)
        .put("role", asset.role.name)
        .put("path", asset.path)
        .put("label", asset.label)
        .put("order", asset.order)
        .put("createdAt", asset.createdAt)
        .put("kind", asset.kind.name)
        .put("tags", JSONArray().apply { asset.tags.forEach { put(it) } })

    private fun assetFromJson(o: JSONObject): ProjectAsset {
        val path = o.optString("path")
        return ProjectAsset(
            id = o.optString("id", UUID.randomUUID().toString()),
            role = ReferenceRole.fromStorage(o.optString("role")),
            path = path,
            label = o.optString("label"),
            order = o.optInt("order", 0),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            kind = if (o.has("kind")) {
                AssetKind.fromStorage(o.optString("kind"))
            } else {
                // Rows written before the kind field existed: infer from the file.
                AssetKind.fromPath(path)
            },
            tags = stringList(o.optJSONArray("tags")),
        )
    }

    private fun timelineToJson(timeline: Timeline): JSONObject = JSONObject()
        .put("id", timeline.id)
        .put("createdAt", timeline.createdAt)
        .put("updatedAt", timeline.updatedAt)
        .put("videoTrack", JSONArray().apply { timeline.videoTrack.forEach { put(clipToJson(it)) } })
        .put("audioTrack", JSONArray().apply { timeline.audioTrack.forEach { put(clipToJson(it)) } })

    private fun timelineFromJson(o: JSONObject): Timeline? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        return Timeline(
            id = id,
            videoTrack = clipList(o.optJSONArray("videoTrack")),
            audioTrack = clipList(o.optJSONArray("audioTrack")),
            createdAt = o.optLong("createdAt"),
            updatedAt = o.optLong("updatedAt"),
        )
    }

    private fun clipList(array: JSONArray?): List<TimelineClip> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::clipFromJson)
        }
    }

    private fun clipToJson(clip: TimelineClip): JSONObject = JSONObject()
        .put("id", clip.id)
        .put("sourcePath", clip.sourcePath)
        .put("sourceLabel", clip.sourceLabel)
        .put("sourceInMs", clip.sourceInMs)
        .put("sourceOutMs", clip.sourceOutMs)
        .put("speed", clip.speed.toDouble())
        .put("volume", clip.volume.toDouble())
        .put("muted", clip.muted)

    private fun clipFromJson(o: JSONObject): TimelineClip? {
        val path = o.optString("sourcePath").takeIf { it.isNotBlank() } ?: return null
        val sourceIn = o.optLong("sourceInMs", 0L).coerceAtLeast(0L)
        return TimelineClip(
            id = o.optString("id").takeIf { it.isNotBlank() } ?: "tlc:" + UUID.randomUUID(),
            sourcePath = path,
            sourceLabel = o.optString("sourceLabel"),
            sourceInMs = sourceIn,
            // A zero/absent out point falls back to just past the in point so
            // damaged rows still decode to a non-negative trim window.
            sourceOutMs = o.optLong("sourceOutMs", sourceIn + 1).coerceAtLeast(sourceIn + 1),
            speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 4f),
            volume = o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 2f),
            muted = o.optBoolean("muted", false),
        )
    }

    private fun profileToJson(profile: AssetProfile): JSONObject = JSONObject()
        .put("id", profile.id)
        .put("name", profile.name)
        .put("summary", profile.summary)
        .put("kind", profile.kind.name)
        .put("assetIds", JSONArray().apply { profile.assetIds.forEach { put(it) } })
        .put("createdAt", profile.createdAt)

    private fun profileFromJson(o: JSONObject): AssetProfile? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        return AssetProfile(
            id = id,
            name = o.optString("name"),
            summary = o.optString("summary"),
            kind = ProfileKind.fromStorage(o.optString("kind")),
            assetIds = stringList(o.optJSONArray("assetIds")),
            createdAt = o.optLong("createdAt"),
        )
    }

    private fun stringMap(o: JSONObject?): Map<String, String> {
        if (o == null) return emptyMap()
        val out = linkedMapOf<String, String>()
        o.keys().forEach { key -> out[key] = o.optString(key) }
        return out
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
    }

    private fun nullableString(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.optString(key).takeIf { it.isNotBlank() }

    private fun nullableInt(o: JSONObject, key: String): Int? =
        if (!o.has(key) || o.isNull(key)) null else o.optInt(key).takeIf { it > 0 }

    private fun nullableDouble(o: JSONObject, key: String): Double? =
        if (!o.has(key) || o.isNull(key)) null else o.optDouble(key).takeIf { !it.isNaN() }
}

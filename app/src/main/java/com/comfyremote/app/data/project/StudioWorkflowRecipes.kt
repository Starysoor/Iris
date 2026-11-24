package com.comfyremote.app.data.project

import com.comfyremote.app.data.cloud.AutoDlConfig
import com.comfyremote.app.data.reference.AssetKind
import com.comfyremote.app.data.reference.ReferenceRole
import com.comfyremote.app.data.repository.DraftTransfer
/**
 * Human-operated, saveable pipeline guides. A stage points to a validated provider
 * workflow; merely picking a recipe never uploads media or launches paid generation.
 */
object StudioWorkflowRecipes {
    const val TEXT_TO_VIDEO = "text-video"
    const val REFERENCES_TO_VIDEO = "references-video"
    const val KEYFRAMES_AND_AUDIO = "keyframes-audio"

    enum class Step(val providerKind: String? = null) {
        ASSETS, IMAGE("image"), SHOTS, VIDEO("video"), AUDIO("audio"), TIMELINE
    }

    data class Recipe(val id: String, val steps: List<Step>)

    val options = listOf(
        Recipe(TEXT_TO_VIDEO, listOf(Step.SHOTS, Step.VIDEO, Step.TIMELINE)),
        Recipe(REFERENCES_TO_VIDEO, listOf(Step.ASSETS, Step.SHOTS, Step.VIDEO, Step.TIMELINE)),
        Recipe(KEYFRAMES_AND_AUDIO, listOf(
            Step.ASSETS, Step.IMAGE, Step.SHOTS, Step.VIDEO, Step.AUDIO, Step.TIMELINE,
        )),
    )

    fun resolve(id: String?): Recipe = options.firstOrNull { it.id == id } ?: options.first()
    fun supports(id: String): Boolean = options.any { it.id == id }
    fun isModelKind(kind: String): Boolean = kind in setOf("image", "video", "audio")

    fun workflowPath(project: VideoProject, kind: String, shot: ProjectShot? = null): String? {
        if (kind != "video") return shot?.stageWorkflowPaths?.get(kind)?.takeIf(String::isNotBlank)
            ?: project.stageWorkflowPaths[kind]?.takeIf(String::isNotBlank)
        return shot?.workflowPath?.takeIf(String::isNotBlank)
            ?: shot?.workflowId?.takeIf(String::isNotBlank)?.let { AutoDlConfig.PATH_PREFIX + it }
            ?: project.stageWorkflowPaths[kind]?.takeIf(String::isNotBlank)
            ?: project.workflowPath.takeIf(String::isNotBlank)
            ?: project.workflowId.takeIf(String::isNotBlank)?.let { AutoDlConfig.PATH_PREFIX + it }
    }

    fun shotTransfer(
        project: VideoProject,
        shot: ProjectShot,
        kind: String,
        path: String,
        name: String,
        fieldPaths: Map<String, String>,
    ): DraftTransfer {
        val assets = project.assets.filter { it.kind == AssetKind.IMAGE }.associateBy { it.id }
        val references = if (kind != "audio") shot.referenceAssetIds.mapNotNull { assets[it]?.path } else emptyList()
        val previous = project.shots.getOrNull(project.shots.indexOfFirst { it.id == shot.id } - 1)
        val image = if (kind == "video" && references.isEmpty()) {
            shot.referencePath ?: previous?.lastFramePath?.takeIf { !shot.referenceManual && project.chainFrames }
        } else null
        val frames = if (kind == "video") buildMap {
            assets[shot.firstFrameAssetId]?.path?.let { put(ReferenceRole.START_FRAME, it) }
            assets[shot.lastFrameAssetId]?.path?.let { put(ReferenceRole.END_FRAME, it) }
        } else emptyMap()
        val saved = shot.stageDraft(kind, path)
        val legacy = if (kind == "video" && workflowPath(project, kind, shot) == path) shot.values else emptyMap()
        val defaults = if (kind == "video" && project.workflowPath == path) project.defaultValues else emptyMap()
        val prompt = when (kind) {
            "audio" -> shot.prompt
            "image" -> listOf(project.stylePrompt, shot.prompt).filter(String::isNotBlank).joinToString(", ")
            else -> listOf(project.stylePrompt, shot.prompt, shot.cameraNotes.orEmpty())
                .filter(String::isNotBlank).joinToString(", ")
        }
        return DraftTransfer(
            workflowPath = path, workflowName = name, values = defaults + (saved?.values ?: legacy),
            randomSeed = saved?.randomSeed ?: true,
            storyboardProjectId = project.id, storyboardShotId = shot.id, studioStageKind = kind,
            restoreStudioPrompt = saved != null || legacy.isNotEmpty(),
            studioPrompt = prompt.takeIf(String::isNotBlank),
            referenceLocalPath = image, referenceLocalPaths = references,
            referenceRolePaths = frames, referenceFieldPaths = fieldPaths,
        )
    }
}

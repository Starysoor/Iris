package com.comfyremote.app.data.project

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
}

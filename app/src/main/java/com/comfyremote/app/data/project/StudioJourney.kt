package com.comfyremote.app.data.project

/**
 * Computes the next useful action from persisted project artifacts, without requiring an
 * online provider or making reference images mandatory for text-only generation.
 */
object StudioJourney {
    enum class NextStep { ADD_SHOTS, GENERATE, ARRANGE, REVIEW }
    data class Progress(val next: NextStep, val totalShots: Int, val completedShots: Int, val timelineClips: Int)

    fun inspect(project: VideoProject): Progress {
        val completed = project.shots.count { it.status == "done" && !it.resultPath.isNullOrBlank() }
        val clips = project.timeline?.videoTrack?.size ?: 0
        val next = when {
            project.shots.isEmpty() -> NextStep.ADD_SHOTS
            completed < project.shots.size -> NextStep.GENERATE
            clips == 0 -> NextStep.ARRANGE
            else -> NextStep.REVIEW
        }
        return Progress(next, project.shots.size, completed, clips)
    }
}

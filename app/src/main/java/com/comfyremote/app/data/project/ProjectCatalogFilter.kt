package com.comfyremote.app.data.project

/** UI-only filtering; it cannot mutate, delete or conceal stored media. */
object ProjectCatalogFilter {
    enum class Mode { CURRENT, COMPLETED, ARCHIVED, ALL }
    fun filter(projects: List<VideoProject>, mode: Mode): List<VideoProject> =
        projects.filter { project ->
            when (mode) {
                Mode.CURRENT -> !project.archived
                Mode.COMPLETED -> !project.archived && project.shots.isNotEmpty() &&
                    project.shots.all { it.status == "done" }
                Mode.ARCHIVED -> project.archived
                Mode.ALL -> true
            }
        }
}

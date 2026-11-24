package com.comfyremote.app.navigation

import android.net.Uri

object Routes {
    const val CONNECT = "connect"
    const val CREATE = "create"
    const val TASKS = "tasks"
    const val HISTORY = "history"
    const val PROJECTS = "projects"
    const val SETTINGS = "settings"
    const val APP_SETTINGS = "app-settings"
    const val CLOUD_SETTINGS = "cloud-settings"
    const val CLOUD_SERVICES = "cloud-services"
    const val CLOUD_PROVIDER_PATTERN = "cloud-provider/{providerId}"
    const val SERVERS = "servers"
    const val AI_SETTINGS = "ai-settings"
    // Reuses the old update route to preserve existing navigation state.
    const val VERSION = "update"

    const val GENERATE_PATTERN = "generate?path={path}&name={name}"
    const val TASK_PATTERN = "task/{taskId}"
    const val PROJECT_PATTERN = "project/{projectId}"
    const val PROJECT_SHOTS_PATTERN = "project/{projectId}/shots?shot={shotId}"
    const val PROJECT_ASSETS_PATTERN = "project/{projectId}/assets"
    const val PROJECT_TIMELINE_PATTERN = "project/{projectId}/timeline"
    const val PROJECT_DIRECTOR_PATTERN = "project/{projectId}/director"

    fun generate(path: String, name: String): String =
        "generate?path=${Uri.encode(path)}&name=${Uri.encode(name)}"

    fun task(taskId: String): String = "task/${Uri.encode(taskId)}"

    fun project(projectId: String): String = "project/${Uri.encode(projectId)}"

    fun projectShots(projectId: String, shotId: String? = null): String =
        "project/${Uri.encode(projectId)}/shots" + shotId?.let { "?shot=${Uri.encode(it)}" }.orEmpty()

    fun projectAssets(projectId: String): String = "project/${Uri.encode(projectId)}/assets"

    fun projectTimeline(projectId: String): String = "project/${Uri.encode(projectId)}/timeline"

    fun projectDirector(projectId: String): String = "project/${Uri.encode(projectId)}/director"

    fun cloudProvider(providerId: String): String = "cloud-provider/${Uri.encode(providerId)}"
}

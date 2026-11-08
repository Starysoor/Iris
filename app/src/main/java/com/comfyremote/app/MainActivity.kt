package com.comfyremote.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.comfyremote.app.data.generation.GenerationNotificationController
import com.comfyremote.app.data.generation.GenerationRequest
import com.comfyremote.app.navigation.ComfyRemoteRoot
import com.comfyremote.app.navigation.Routes
import com.comfyremote.app.ui.IrisTheme
import com.comfyremote.app.ui.LocalAppSettings
import com.comfyremote.app.ui.intro.IrisIntro
import com.comfyremote.app.ui.intro.IrisIntroHost
import com.comfyremote.app.ui.isIrisDark

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as ComfyRemoteApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated Activity still carries the launch Intent; consume it only once.
        if (savedInstanceState == null) handleEntryIntent(intent)
        // Decided once per cold start; recreation (rotation, theme change) never replays it.
        val playIntro = savedInstanceState == null &&
            IrisIntro.shouldPlay(this, intent, container.appSettingsStore.settings.value.playIntro)
        setContent {
            val settings by container.appSettingsStore.settings.collectAsState()
            val dark = isIrisDark(settings.themeMode)
            // Keep system bar icon contrast in sync with the in-app theme choice,
            // which may differ from the system night mode.
            DisposableEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            IrisTheme(settings.themeMode) {
                IrisIntroHost(play = playIntro, dark = dark) {
                    CompositionLocalProvider(LocalAppSettings provides settings) {
                        ComfyRemoteRoot(container)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleEntryIntent(intent)
    }

    private fun handleEntryIntent(intent: Intent?) {
        intent ?: return
        if (BuildConfig.DEBUG && intent.action == ACTION_QA_GALLERY) {
            // Screenshots are captured from the real Compose UI using deliberately
            // offline, clearly labeled demo content. No paid generation occurs.
            val projectId = ShowcaseCaptureSeed.seed(container) ?: return
            val route = when (intent.getStringExtra("qa_screen")) {
                "overview" -> Routes.project(projectId)
                "shots" -> Routes.projectShots(projectId)
                "assets" -> Routes.projectAssets(projectId)
                "timeline" -> Routes.projectTimeline(projectId)
                "director" -> Routes.projectDirector(projectId)
                "settings" -> Routes.SETTINGS
                else -> Routes.PROJECTS
            }
            container.navigationRequests.request(route)
            return
        }
        if (BuildConfig.DEBUG && intent.action == ACTION_QA_SMOKE) {
            // Debug-only CI smoke task: exercises persistence + foreground service
            // against an unreachable local port, never a paid cloud provider.
            runCatching {
                container.generationManager.submit(
                    GenerationRequest(
                        provider = "comfyui",
                        serverUrl = "http://127.0.0.1:9",
                        workflowPath = "qa-smoke.json",
                        workflowName = "QA smoke",
                        fieldValues = emptyMap(),
                        randomSeed = false,
                        nodeTitles = emptyMap(),
                        fields = emptyList(),
                        convertedPrompt = org.json.JSONObject(),
                    )
                )
            }
            return
        }
        intent.getStringExtra(GenerationNotificationController.EXTRA_TASK_ID)?.let { taskId ->
            container.navigationRequests.request(Routes.task(taskId))
        }
        if (container.incomingMedia.handleIntent(intent)) {
            // Cloud workflows can consume shared media without a local ComfyUI session.
            container.navigationRequests.request(Routes.CREATE)
        }
    }

    companion object {
        const val ACTION_QA_SMOKE = "com.comfyremote.app.QA_SMOKE"
        const val ACTION_QA_GALLERY = "com.comfyremote.app.QA_GALLERY"
    }
}

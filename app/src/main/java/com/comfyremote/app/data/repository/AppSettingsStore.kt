package com.comfyremote.app.data.repository

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing app preferences. Every behaviour that used to be hard-coded in a
 * screen or ViewModel belongs here so the Settings screen is the single place
 * to customise the app.
 */
data class AppSettings(
    val themeMode: String = "system",              // system | light | dark
    val compactUi: Boolean = true,
    /** Play the Iris intro on a launcher cold start. */
    val playIntro: Boolean = true,
    val recentResultsCount: Int = 2,               // 0..4
    val recentPresetsCount: Int = 2,               // 0..4
    val showServerWorkspace: Boolean = true,
    val showProjectLinkPrompt: Boolean = true,
    val defaultBatchCount: Int = 1,                // 1..16
    val defaultLocalQueue: Boolean = true,
    val defaultRandomSeed: Boolean = true,
    val autoSaveDrafts: Boolean = true,
    val autoRestoreDrafts: Boolean = true,
    val notifyOnFinish: Boolean = true,
    val maxImageDimension: Int = 1536,             // 1024 | 1536 | 2048
    val notesExpandedByDefault: Boolean = false,
    val advancedExpandedByDefault: Boolean = false,
    val defaultVideoWorkflowId: String = "minimax_h3_lightx2v_v5",
    val confirmCloudSubmit: Boolean = true,
    /** Off: reuse returns straight to the original platform/workflow. */
    val reuseChooseWorkflow: Boolean = false,
)

/** Persists [AppSettings] in the shared `comfyremote` preferences file. */
class AppSettingsStore(private val prefs: SharedPreferences) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun load(): AppSettings = AppSettings(
        themeMode = prefs.getString(KEY_THEME, "system") ?: "system",
        compactUi = prefs.getBoolean(KEY_COMPACT, true),
        playIntro = prefs.getBoolean(KEY_PLAY_INTRO, true),
        recentResultsCount = prefs.getInt(KEY_RECENT_RESULTS, 2).coerceIn(0, 4),
        recentPresetsCount = prefs.getInt(KEY_RECENT_PRESETS, 2).coerceIn(0, 4),
        showServerWorkspace = prefs.getBoolean(KEY_SERVER_WORKSPACE, true),
        showProjectLinkPrompt = prefs.getBoolean(KEY_PROJECT_LINK_PROMPT, true),
        defaultBatchCount = prefs.getInt(KEY_BATCH, 1).coerceIn(1, 16),
        defaultLocalQueue = prefs.getBoolean(KEY_LOCAL_QUEUE, true),
        defaultRandomSeed = prefs.getBoolean(KEY_RANDOM_SEED, true),
        autoSaveDrafts = prefs.getBoolean(KEY_AUTO_SAVE, true),
        autoRestoreDrafts = prefs.getBoolean(KEY_AUTO_RESTORE, true),
        notifyOnFinish = prefs.getBoolean(KEY_NOTIFY, true),
        maxImageDimension = prefs.getInt(KEY_IMAGE_DIM, 1536).coerceIn(1024, 2048),
        notesExpandedByDefault = prefs.getBoolean(KEY_NOTES_OPEN, false),
        advancedExpandedByDefault = prefs.getBoolean(KEY_ADVANCED_OPEN, false),
        defaultVideoWorkflowId = prefs.getString(KEY_VIDEO_WORKFLOW, "minimax_h3_lightx2v_v5")
            ?.trim().orEmpty(),
        confirmCloudSubmit = prefs.getBoolean(KEY_CONFIRM_CLOUD, true),
        reuseChooseWorkflow = prefs.getBoolean(KEY_REUSE_CHOOSE_WORKFLOW, false),
    )

    fun save(settings: AppSettings) {
        _settings.value = settings
        prefs.edit()
            .putString(KEY_THEME, settings.themeMode)
            .putBoolean(KEY_COMPACT, settings.compactUi)
            .putBoolean(KEY_PLAY_INTRO, settings.playIntro)
            .putInt(KEY_RECENT_RESULTS, settings.recentResultsCount.coerceIn(0, 4))
            .putInt(KEY_RECENT_PRESETS, settings.recentPresetsCount.coerceIn(0, 4))
            .putBoolean(KEY_SERVER_WORKSPACE, settings.showServerWorkspace)
            .putBoolean(KEY_PROJECT_LINK_PROMPT, settings.showProjectLinkPrompt)
            .putInt(KEY_BATCH, settings.defaultBatchCount.coerceIn(1, 16))
            .putBoolean(KEY_LOCAL_QUEUE, settings.defaultLocalQueue)
            .putBoolean(KEY_RANDOM_SEED, settings.defaultRandomSeed)
            .putBoolean(KEY_AUTO_SAVE, settings.autoSaveDrafts)
            .putBoolean(KEY_AUTO_RESTORE, settings.autoRestoreDrafts)
            .putBoolean(KEY_NOTIFY, settings.notifyOnFinish)
            .putInt(KEY_IMAGE_DIM, settings.maxImageDimension.coerceIn(1024, 2048))
            .putBoolean(KEY_NOTES_OPEN, settings.notesExpandedByDefault)
            .putBoolean(KEY_ADVANCED_OPEN, settings.advancedExpandedByDefault)
            .putString(KEY_VIDEO_WORKFLOW, settings.defaultVideoWorkflowId.trim())
            .putBoolean(KEY_CONFIRM_CLOUD, settings.confirmCloudSubmit)
            .putBoolean(KEY_REUSE_CHOOSE_WORKFLOW, settings.reuseChooseWorkflow)
            .apply()
    }

    fun update(transform: (AppSettings) -> AppSettings) = save(transform(_settings.value))

    companion object {
        private const val KEY_THEME = "appThemeMode"
        private const val KEY_COMPACT = "appCompactUi"
        private const val KEY_PLAY_INTRO = "appPlayIntro"
        private const val KEY_RECENT_RESULTS = "appRecentResults"
        private const val KEY_RECENT_PRESETS = "appRecentPresets"
        private const val KEY_SERVER_WORKSPACE = "appServerWorkspace"
        private const val KEY_PROJECT_LINK_PROMPT = "appProjectLinkPrompt"
        private const val KEY_BATCH = "appDefaultBatch"
        private const val KEY_LOCAL_QUEUE = "appDefaultLocalQueue"
        private const val KEY_RANDOM_SEED = "appDefaultRandomSeed"
        private const val KEY_AUTO_SAVE = "appAutoSaveDrafts"
        private const val KEY_AUTO_RESTORE = "appAutoRestoreDrafts"
        private const val KEY_NOTIFY = "appNotifyOnFinish"
        private const val KEY_IMAGE_DIM = "appMaxImageDimension"
        private const val KEY_NOTES_OPEN = "appNotesExpanded"
        private const val KEY_ADVANCED_OPEN = "appAdvancedExpanded"
        private const val KEY_VIDEO_WORKFLOW = "appDefaultVideoWorkflow"
        private const val KEY_CONFIRM_CLOUD = "appConfirmCloudSubmit"
        private const val KEY_REUSE_CHOOSE_WORKFLOW = "appReuseChooseWorkflow"
    }
}

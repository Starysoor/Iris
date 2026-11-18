package com.comfyremote.app.data.repository

import android.content.SharedPreferences

/** Public GitHub update-check throttle timestamp. Never stores a GitHub token. */
class UpdateSettingsStore(private val prefs: SharedPreferences) {

    fun lastUpdateCheck(): Long = prefs.getLong("lastUpdateCheck", 0L)

    fun markUpdateChecked() {
        prefs.edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply()
    }
}

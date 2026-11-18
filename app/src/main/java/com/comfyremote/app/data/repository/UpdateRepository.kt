package com.comfyremote.app.data.repository

import android.app.Application
import com.comfyremote.app.GhRelease
import com.comfyremote.app.Updater
import java.io.File

/** Release checking/downloading boundary used by the update feature and app bootstrap. */
class UpdateRepository(private val app: Application) {
    fun currentVersion(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
    }.getOrDefault("0")

    suspend fun latest(): GhRelease = Updater.latest(currentVersion())

    fun isNewer(release: GhRelease): Boolean =
        Updater.compareVersions(release.tag, currentVersion()) > 0

    suspend fun download(
        release: GhRelease,
        onProgress: (Float) -> Unit,
    ): File {
        val dest = File(app.cacheDir, "update/comfyremote_${release.tag}.apk")
        Updater.download(release, dest, onProgress)
        return dest
    }

    fun install(apk: File) = Updater.installApk(app, apk)
}

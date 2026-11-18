package com.comfyremote.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Latest release info pulled from GitHub. */
data class GhRelease(
    val tag: String,        // e.g. "v1.3"
    val name: String,
    val notes: String,
    val apkUrl: String,     // release asset URL
    val browserUrl: String, // public browser download link (shareable)
    val apkSize: Long,
)

object Updater {

    private const val REPO = "Starysoor/Iris"
    // HTTP/1.1 only: HTTP/2 connection reuse breaks on some networks between
    // api.github.com and the S3 redirect target ("SETTINGS preface" errors).
    private val http = OkHttpClient.Builder()
        .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * SemVer comparison with prerelease precedence: beta.10 > beta.2,
     * and v2.4.0 stable > v2.4.0-beta.9.
     */
    fun compareVersions(a: String, b: String): Int {
        data class Version(val core: List<Int>, val suffix: List<String>?)
        fun parse(raw: String): Version {
            val cleaned = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val core = cleaned.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val suffix = cleaned.substringAfter('-', "").takeIf { it.isNotBlank() }?.split('.')
            return Version(core, suffix)
        }
        val left = parse(a)
        val right = parse(b)
        for (i in 0 until maxOf(left.core.size, right.core.size)) {
            val compared = left.core.getOrElse(i) { 0 }.compareTo(right.core.getOrElse(i) { 0 })
            if (compared != 0) return compared
        }
        if (left.suffix == null && right.suffix != null) return 1
        if (left.suffix != null && right.suffix == null) return -1
        if (left.suffix == null || right.suffix == null) return 0
        for (i in 0 until minOf(left.suffix.size, right.suffix.size)) {
            val l = left.suffix[i]
            val r = right.suffix[i]
            val li = l.toIntOrNull()
            val ri = r.toIntOrNull()
            val compared = when {
                li != null && ri != null -> li.compareTo(ri)
                li != null -> -1
                ri != null -> 1
                else -> l.compareTo(r, ignoreCase = true)
            }
            if (compared != 0) return compared
        }
        return left.suffix.size.compareTo(right.suffix.size)
    }

    /**
     * Installed betas can see newer beta and stable releases; stable installs
     * only receive stable updates to avoid unwanted prerelease enrollment.
     */
    internal fun selectRelease(releases: JSONArray, installedVersion: String): GhRelease {
        val includePrerelease = installedVersion.contains('-')
        val candidates = (0 until releases.length()).mapNotNull { index ->
            val release = releases.optJSONObject(index) ?: return@mapNotNull null
            if (release.optBoolean("draft") ||
                (!includePrerelease && release.optBoolean("prerelease"))
            ) return@mapNotNull null
            val tag = release.optString("tag_name")
            if (tag.isBlank()) return@mapNotNull null
            val assets = release.optJSONArray("assets") ?: return@mapNotNull null
            val apk = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { asset ->
                    asset.optString("name").endsWith(".apk", ignoreCase = true) &&
                        asset.optString("browser_download_url").startsWith(
                            "https://github.com/$REPO/releases/download/",
                        )
                } ?: return@mapNotNull null
            GhRelease(
                tag = tag,
                name = release.optString("name").ifBlank { tag },
                notes = release.optString("body").orEmpty(),
                apkUrl = apk.optString("url"),
                browserUrl = apk.optString("browser_download_url"),
                apkSize = apk.optLong("size"),
            )
        }
        return candidates.maxWithOrNull { a, b -> compareVersions(a.tag, b.tag) }
            ?: throw ComfyException("没有可下载的公开 APK Release")
    }

    suspend fun latest(installedVersion: String): GhRelease = withContext(Dispatchers.IO) {
        // The /releases/latest endpoint intentionally excludes prereleases.
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases?per_page=30")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                throw ComfyException("GitHub Releases HTTP " + r.code + ": " + body.take(160))
            }
            selectRelease(JSONArray(body), installedVersion)
        }
    }

    /** Streams the release asset to dest, reporting 0f..1f progress. */
    suspend fun download(release: GhRelease, dest: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.absolutePath + ".part")
            val builder = Request.Builder()
                .url(release.browserUrl.ifBlank { release.apkUrl })
                .header("Accept", "application/octet-stream")
            http.newCall(builder.build()).execute().use { r ->
                if (!r.isSuccessful) {
                    val errBody = r.body?.string()?.take(200)
                    android.util.Log.e("Updater", "download failed code=${r.code} url=${release.apkUrl} body=$errBody")
                    throw ComfyException("下载失败 HTTP ${r.code}: ${errBody ?: "(无响应体)"}")
                }
                val total = r.body?.contentLength() ?: -1L
                val input = r.body?.byteStream() ?: throw ComfyException("下载失败：空响应")
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int; var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read); done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                dest.delete(); tmp.copyTo(dest, overwrite = true); tmp.delete()
            }
        }

    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

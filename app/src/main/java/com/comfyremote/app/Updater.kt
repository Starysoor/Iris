package com.comfyremote.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    /** Positive when a is newer than b. Tolerates a leading "v" and missing segments. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").removePrefix("V").split('.').map { it.trim().toIntOrNull() ?: 0 }
        val pa = parts(a); val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    suspend fun latest(): GhRelease = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        http.newCall(builder.build()).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (r.code == 404) throw ComfyException("暂无公开发布的正式版本")
            if (!r.isSuccessful) throw ComfyException("HTTP ${r.code}: ${body.take(200)}")
            val o = JSONObject(body)
            val assets = o.optJSONArray("assets") ?: org.json.JSONArray()
            var apkUrl = ""; var browserUrl = ""; var apkSize = 0L
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) {
                    // API asset URL (not browser_download_url): with Accept octet-stream it
                    // 302-redirects to a pre-signed S3 URL, which works for private repos.
                    apkUrl = a.optString("url")
                    browserUrl = a.optString("browser_download_url")
                    apkSize = a.optLong("size")
                    break
                }
            }
            if (apkUrl.isEmpty()) throw ComfyException("该 Release 里没有 .apk 附件")
            GhRelease(
                tag = o.optString("tag_name"),
                name = o.optString("name").ifEmpty { o.optString("tag_name") },
                notes = o.optString("body").orEmpty(),
                apkUrl = apkUrl,
                browserUrl = browserUrl,
                apkSize = apkSize,
            )
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

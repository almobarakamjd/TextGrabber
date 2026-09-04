package com.oqod.textgrabber.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** معلومات آخر إصدار منشور على GitHub Releases. */
data class LatestRelease(
    val versionName: String,
    val apkDownloadUrl: String,
    val releaseNotes: String
)

private const val GITHUB_LATEST_RELEASE_API_URL =
    "https://api.github.com/repos/almobarakamjd/TextGrabber/releases/latest"

/**
 * يجلب معلومات آخر إصدار من واجهة GitHub Releases API (طلب شبكة متزامن؛
 * يجب استدعاؤها من خيط خلفية / Dispatchers.IO فقط).
 *
 * تعيد null بصمت عند أي خطأ (لا اتصال، Rate limit، تنسيق غير متوقع...)
 * حتى لا يتعطل التطبيق بسبب فشل التحقق من التحديث.
 */
fun fetchLatestReleaseBlocking(): LatestRelease? {
    return try {
        val connection = URL(GITHUB_LATEST_RELEASE_API_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.connectTimeout = 8000
        connection.readTimeout = 8000

        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            return null
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONObject(body)
        val versionName = json.optString("tag_name").removePrefix("v").trim()
        val releaseNotes = json.optString("body")

        val assets = json.optJSONArray("assets") ?: return null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                apkUrl = asset.optString("browser_download_url")
                break
            }
        }

        if (versionName.isBlank() || apkUrl.isNullOrBlank()) {
            null
        } else {
            LatestRelease(versionName = versionName, apkDownloadUrl = apkUrl, releaseNotes = releaseNotes)
        }
    } catch (e: Exception) {
        null
    }
}

/** مقارنة إصدارين بصيغة "1.2.3" رقما برقم؛ تعيد true إن كان remote أحدث من local. */
fun isNewerVersion(remote: String, local: String): Boolean {
    val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
    val localParts = local.split(".").mapNotNull { it.toIntOrNull() }
    val length = maxOf(remoteParts.size, localParts.size)
    for (i in 0 until length) {
        val r = remoteParts.getOrElse(i) { 0 }
        val l = localParts.getOrElse(i) { 0 }
        if (r != l) return r > l
    }
    return false
}

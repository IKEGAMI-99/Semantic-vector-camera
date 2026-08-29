package com.ikegami.svcam.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.ikegami.svcam.BuildConfig
import com.ikegami.svcam.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val checksumUrl: String,
    val available: Boolean,
)

class UpdateManager(private val context: Context) {
    suspend fun check(): UpdateInfo = withContext(Dispatchers.IO) {
        AppLogger.info("UPDATE", "check_start")
        val root = JSONObject(readUrl("https://api.github.com/repos/IKEGAMI-99/Semantic-vector-camera/releases/latest", "application/vnd.github+json"))
        val version = root.optString("tag_name").removePrefix("v")
        val assets = root.optJSONArray("assets")
        var apkUrl = ""
        var checksumUrl = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                when {
                    name.endsWith(".apk", ignoreCase = true) -> apkUrl = url
                    name.endsWith(".apk.sha256", ignoreCase = true) -> checksumUrl = url
                }
            }
        }
        val info = UpdateInfo(
            version = version,
            notes = root.optString("body"),
            apkUrl = apkUrl,
            checksumUrl = checksumUrl,
            available = apkUrl.isNotBlank() && checksumUrl.isNotBlank() && isNewer(version, BuildConfig.VERSION_NAME),
        )
        AppLogger.info(
            "UPDATE",
            "check_complete",
            mapOf("latest" to version, "available" to info.available, "checksum" to checksumUrl.isNotBlank()),
        )
        info
    }

    suspend fun downloadAndInstall(info: UpdateInfo) {
        require(info.available && info.apkUrl.isNotBlank() && info.checksumUrl.isNotBlank())
        val target = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apk = File(dir, "SemanticVectorCamera-${info.version}.apk")
            AppLogger.info("UPDATE", "download_start", mapOf("version" to info.version))
            download(info.apkUrl, apk)
            require(apk.length() > 100_000) { "Downloaded APK is unexpectedly small" }

            val expected = readUrl(info.checksumUrl, "text/plain")
                .trim()
                .substringBefore(' ')
                .lowercase()
            val actual = sha256(apk)
            require(expected.matches(Regex("[0-9a-f]{64}"))) { "Release checksum is malformed" }
            require(actual == expected) { "APK SHA-256 verification failed" }
            AppLogger.info("UPDATE", "checksum_valid", mapOf("sha256" to actual))
            apk
        }

        withContext(Dispatchers.Main) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            AppLogger.info("UPDATE", "installer_launch", mapOf("version" to info.version))
            context.startActivity(intent)
        }
    }

    private fun readUrl(url: String, accept: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "SemanticVectorCamera/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "GitHub request failed: HTTP $code" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "SemanticVectorCamera/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "APK download failed: HTTP $code" }
            connection.inputStream.use { input -> target.outputStream().buffered().use { input.copyTo(it) } }
            AppLogger.info("UPDATE", "download_complete", mapOf("bytes" to target.length()))
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }
}

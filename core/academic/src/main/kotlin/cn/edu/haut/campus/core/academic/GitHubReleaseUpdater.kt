package cn.edu.haut.campus.core.academic

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val versionNormalized: String,
    val releaseTitle: String,
    val changelog: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkFileName: String,
    val apkSizeBytes: Long,
    val hasNewerVersion: Boolean,
    val currentVersionNormalized: String
)

/**
 * GitHub Release 自动版本检测与 APK 在线直装更新引擎：
 * - 自动查询 `https://api.github.com/repos/yellowpeachxgp/day-in-school/releases/latest`
 * - 语义化版本号比对（支持识别 `v0.9.0` vs 本地 `0.8.0` / `0.8.0-debug`）
 * - 支持应用内直接流式拉取最新 Release APK（自动跟随 GitHub 302 CDN 重定向并回调实时下载进度）
 * - 下载完成后通过 FileProvider 直接唤起 Android 系统软件包安装程序完成覆盖升级
 */
object GitHubReleaseUpdater {

    const val REPO_OWNER = "yellowpeachxgp"
    const val REPO_NAME = "day-in-school"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"

    private const val USER_AGENT = "HAUT-DayInSchool-Android-Updater/1.0"

    fun normalizeVersion(rawVersion: String): String {
        return rawVersion
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .trim()
    }

    /**
     * 语义化版本比对：当远端版本 > 当前本地安装版本时返回 true。
     */
    fun isRemoteVersionNewer(currentVersionName: String, remoteTagName: String): Boolean {
        val currentParts = normalizeVersion(currentVersionName)
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
        val remoteParts = normalizeVersion(remoteTagName)
            .split(".")
            .map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val r = remoteParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    /**
     * 从 GitHub Releases API 检测最新发布版本与 APK 资产。
     */
    suspend fun checkLatestRelease(currentVersionName: String): Result<GitHubReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL(LATEST_RELEASE_API)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 12_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", USER_AGENT)
                }

                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        throw IllegalStateException("GitHub API 响应异常 (HTTP $code)")
                    }
                    val bodyText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    parseReleaseJson(bodyText, currentVersionName)
                } finally {
                    conn.disconnect()
                }
            }
        }

    internal fun parseReleaseJson(jsonText: String, currentVersionName: String): GitHubReleaseInfo {
        val obj = JSONObject(jsonText)
        val tagName = obj.optString("tag_name", "").ifBlank { "v0.0.0" }
        val releaseTitle = obj.optString("name", tagName).ifBlank { tagName }
        val changelog = obj.optString("body", "").trim().ifBlank { "常规性能优化与体验改进。" }
        val publishedAt = obj.optString("published_at", "").substringBefore("T")
        val htmlUrl = obj.optString("html_url", RELEASES_PAGE_URL)

        var selectedUrl: String? = null
        var selectedName = "day-in-school-$tagName-release.apk"
        var selectedSize = 0L

        val assets = obj.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            // 优先匹配签名的 -release.apk，其次任意 .apk
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val dlUrl = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)
                if (name.endsWith(".apk", ignoreCase = true) && dlUrl.isNotBlank()) {
                    if (selectedUrl == null || name.contains("release", ignoreCase = true)) {
                        selectedUrl = dlUrl
                        selectedName = name
                        selectedSize = size
                    }
                }
            }
        }

        // 若 assets 列表尚未挂载完成，回退拼接标准 release asset 下载地址
        if (selectedUrl == null) {
            selectedUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$tagName/day-in-school-$tagName-release.apk"
        }

        val currentNorm = normalizeVersion(currentVersionName)
        val remoteNorm = normalizeVersion(tagName)

        return GitHubReleaseInfo(
            tagName = tagName,
            versionNormalized = remoteNorm,
            releaseTitle = releaseTitle,
            changelog = changelog,
            publishedAt = publishedAt,
            htmlUrl = htmlUrl,
            apkDownloadUrl = selectedUrl,
            apkFileName = selectedName,
            apkSizeBytes = selectedSize,
            hasNewerVersion = isRemoteVersionNewer(currentVersionName, tagName),
            currentVersionNormalized = currentNorm
        )
    }

    /**
     * 直接拉取 GitHub Release APK 到本地缓存目录，并实时回调下载进度。
     */
    suspend fun downloadReleaseApk(
        context: Context,
        releaseInfo: GitHubReleaseInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val rawUrl = releaseInfo.apkDownloadUrl
                ?: throw IllegalArgumentException("未找到可用的 APK 下载链接")

            val baseCacheDir = context.externalCacheDir ?: context.cacheDir
            val updatesDir = File(baseCacheDir, "updates").apply { mkdirs() }
            val safeFileName = releaseInfo.apkFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(updatesDir, safeFileName)
            val tempFile = File(updatesDir, "$safeFileName.part")

            if (tempFile.exists()) {
                tempFile.delete()
            }

            val conn = openConnectionFollowingRedirects(rawUrl)
            try {
                val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: releaseInfo.apkSizeBytes
                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloaded = 0L
                        var lastCallbackTime = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastCallbackTime >= 80L || downloaded == totalBytes) {
                                lastCallbackTime = now
                                onProgress(downloaded, totalBytes)
                            }
                        }
                        output.flush()
                        onProgress(downloaded, maxOf(totalBytes, downloaded))
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            targetFile
        }
    }

    private fun openConnectionFollowingRedirects(initialUrl: String): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream, */*")
            }
            val code = conn.responseCode
            if (code in listOf(
                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308
                )
            ) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("下载重定向缺少 Location 头")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
                redirects++
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IllegalStateException("下载 APK 失败 (HTTP $code)")
            }
            return conn
        }
        throw IllegalStateException("下载重定向次数过多")
    }

    /**
     * 唤起系统软件包安装程序直接安装已下载的 APK：
     * - 若 Android 8.0+ 尚未授予「安装未知应用」权限，自动跳转系统授权设置页并返回 false。
     * - 若已具备权限，直接调起系统安装界面并返回 true。
     */
    fun triggerInstallApk(context: Context, apkFile: File): Result<Boolean> {
        return runCatching {
            if (!apkFile.exists() || apkFile.length() <= 0L) {
                throw IllegalStateException("安装包文件不存在或已损坏，请重新拉取")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val permIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permIntent)
                return@runCatching false
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            true
        }
    }
}

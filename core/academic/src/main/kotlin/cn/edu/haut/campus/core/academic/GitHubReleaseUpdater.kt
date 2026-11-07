package cn.edu.haut.campus.core.academic

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.PresetMirrorNode
import cn.edu.haut.campus.core.model.ProxyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
    val currentVersionNormalized: String,
    val resolvedViaRoute: String = "GitHub API",
    val expectedSha256Hex: String? = null
)

data class ProxyConnectivityReport(
    val reachable: Boolean,
    val latencyMillis: Long,
    val routeSummary: String,
    val detailMessage: String
)

/**
 * GitHub Release 自动版本检测、多路代理加速更新与云服务统一网络引擎：
 * - 支持 5 种代理模式：预置加速镜像 (`GHFast` / `GH-Proxy` / `GHProxy.net` / `Moeyy`)、自定义反向代理前缀、HTTP 代理、SOCKS5 代理、直连模式
 * - 自动查询 `https://api.github.com/repos/yellowpeachxgp/day-in-school/releases/latest`，当 GitHub API 受阻或限流时自动回退解析 `/releases/latest` 302 标签重定向
 * - 下载 Release APK 时支持按用户首选代理线路拉取，并在开启自动回退时无缝切换备选镜像节点
 * - 为后续云服务（云端课表同步、社区云服务等）提供统一的代理连接工厂 `openCloudServiceConnection`
 */
object GitHubReleaseUpdater {

    const val REPO_OWNER = "yellowpeachxgp"
    const val REPO_NAME = "day-in-school"
    const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val LATEST_RELEASE_WEB_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"

    private const val USER_AGENT = "HAUT-DayInSchool-Android-Updater/1.1"

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
     * 打开统一代理配置的 HttpURLConnection（同时支持 HTTP/SOCKS 底层代理与超时配置）。
     */
    fun openProxiedConnection(
        targetUrl: String,
        proxyConfig: NetworkProxyConfig,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): HttpURLConnection {
        val url = URL(targetUrl)
        val javaProxy = proxyConfig.resolveJavaNetProxy()
        val conn = if (javaProxy != null) {
            url.openConnection(javaProxy) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    /**
     * 面向后续云服务模块的统一连接入口：
     * 自动根据 `proxyConfig.customCloudApiBaseUrl` 拼接端点路径并挂载当前代理配置。
     */
    fun openCloudServiceConnection(
        endpointPath: String,
        proxyConfig: NetworkProxyConfig,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000
    ): HttpURLConnection {
        val resolvedUrl = proxyConfig.resolveCloudServiceUrl(endpointPath)
        return openProxiedConnection(
            targetUrl = resolvedUrl,
            proxyConfig = proxyConfig,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs
        )
    }

    /**
     * 从 GitHub Releases API 或镜像重定向通道检测最新发布版本与 APK 资产。
     */
    suspend fun checkLatestRelease(
        currentVersionName: String,
        proxyConfig: NetworkProxyConfig = NetworkProxyConfig()
    ): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val apiCandidates = buildApiCandidateUrls(proxyConfig)
            var lastError: Throwable? = null

            for ((candidateUrl, routeLabel) in apiCandidates) {
                val attempt = runCatching {
                    val conn = openProxiedConnection(
                        targetUrl = candidateUrl,
                        proxyConfig = proxyConfig,
                        connectTimeoutMs = 8_000,
                        readTimeoutMs = 10_000
                    ).apply {
                        requestMethod = "GET"
                        instanceFollowRedirects = false
                        setRequestProperty("Accept", "application/vnd.github+json")
                    }
                    try {
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            throw IllegalStateException("GitHub API 响应 HTTP $code")
                        }
                        val bodyText = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        parseReleaseJson(bodyText, currentVersionName).copy(resolvedViaRoute = routeLabel)
                    } finally {
                        conn.disconnect()
                    }
                }
                if (attempt.isSuccess) {
                    return@runCatching attempt.getOrThrow()
                } else {
                    lastError = attempt.exceptionOrNull()
                }
            }

            // 当 api.github.com 在当前网络环境下超时或受限时，自动回退通过 /releases/latest 302 Location 提取最新版本号
            val fallbackAttempt = runCatching {
                resolveReleaseViaWebRedirectFallback(currentVersionName, proxyConfig)
            }
            if (fallbackAttempt.isSuccess) {
                return@runCatching fallbackAttempt.getOrThrow()
            }

            throw (lastError ?: fallbackAttempt.exceptionOrNull() ?: IllegalStateException("无法连接更新服务器"))
        }
    }

    private fun buildApiCandidateUrls(proxyConfig: NetworkProxyConfig): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        when (proxyConfig.mode) {
            ProxyMode.CUSTOM_REVERSE_PROXY -> {
                val prefix = proxyConfig.activeReverseProxyPrefix()
                if (!prefix.isNullOrBlank()) {
                    list.add(proxyConfig.applyReverseProxyPrefix(LATEST_RELEASE_API, prefix) to "自定义反代")
                }
                list.add(LATEST_RELEASE_API to "GitHub API")
            }
            ProxyMode.HTTP_PROXY -> {
                list.add(LATEST_RELEASE_API to "HTTP 代理 (${proxyConfig.proxyHost}:${proxyConfig.proxyPort})")
            }
            ProxyMode.SOCKS_PROXY -> {
                list.add(LATEST_RELEASE_API to "SOCKS5 代理 (${proxyConfig.proxyHost}:${proxyConfig.proxyPort})")
            }
            ProxyMode.PRESET_MIRROR, ProxyMode.DIRECT -> {
                list.add(LATEST_RELEASE_API to "GitHub API")
            }
        }
        return list.distinctBy { it.first }
    }

    private fun resolveReleaseViaWebRedirectFallback(
        currentVersionName: String,
        proxyConfig: NetworkProxyConfig
    ): GitHubReleaseInfo {
        val probeUrls = mutableListOf<Pair<String, String>>()
        val activePrefix = proxyConfig.activeReverseProxyPrefix()
        if (!activePrefix.isNullOrBlank()) {
            probeUrls.add(proxyConfig.applyReverseProxyPrefix(LATEST_RELEASE_WEB_URL, activePrefix) to proxyConfig.statusSummaryLabel())
        }
        probeUrls.add(LATEST_RELEASE_WEB_URL to "GitHub Releases 重定向")

        for ((probeUrl, label) in probeUrls.distinctBy { it.first }) {
            val conn = openProxiedConnection(
                targetUrl = probeUrl,
                proxyConfig = proxyConfig,
                connectTimeoutMs = 8_000,
                readTimeoutMs = 8_000
            ).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            try {
                val code = conn.responseCode
                val location = conn.getHeaderField("Location").orEmpty()
                val extractedTag = extractTagFromReleaseUrl(location)
                    ?: if (code in 200..299) extractTagFromReleaseUrl(conn.url?.toString().orEmpty()) else null
                if (!extractedTag.isNullOrBlank()) {
                    val apkName = "day-in-school-$extractedTag-release.apk"
                    val rawApkUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$extractedTag/$apkName"
                    val notesAssetUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/download/$extractedTag/RELEASE_NOTES.txt"
                    val fetchedNotes = fetchReleaseNotesAssetOrNull(notesAssetUrl, proxyConfig)
                    val effectiveChangelog = fetchedNotes?.let { formatCleanChangelog(it) }
                        ?.takeIf { it.isNotBlank() }
                        ?: "检测到新版本 $extractedTag（通过 $label 通道发版解析）。"
                    return GitHubReleaseInfo(
                        tagName = extractedTag,
                        versionNormalized = normalizeVersion(extractedTag),
                        releaseTitle = "日在校园 $extractedTag",
                        changelog = effectiveChangelog,
                        publishedAt = "",
                        htmlUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/tag/$extractedTag",
                        apkDownloadUrl = rawApkUrl,
                        apkFileName = apkName,
                        apkSizeBytes = 0L,
                        hasNewerVersion = isRemoteVersionNewer(currentVersionName, extractedTag),
                        currentVersionNormalized = normalizeVersion(currentVersionName),
                        resolvedViaRoute = label
                    )
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("未能从镜像或重定向通道解析最新版本号")
    }

    private fun fetchReleaseNotesAssetOrNull(
        rawNotesUrl: String,
        proxyConfig: NetworkProxyConfig
    ): String? {
        val candidateUrls = proxyConfig.buildGitHubDownloadCandidateUrls(rawNotesUrl)
        for (url in candidateUrls.take(3)) {
            val text = runCatching {
                val conn = openConnectionFollowingRedirects(url, proxyConfig, maxHops = 4)
                try {
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                    } else null
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    internal fun formatCleanChangelog(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed
            .lines()
            .filterNot { line ->
                val l = line.trim()
                l.startsWith("**Full Changelog**", ignoreCase = true) ||
                    l.startsWith("Full Changelog:", ignoreCase = true) ||
                    l.equals("## What's Changed", ignoreCase = true)
            }
            .joinToString("\n") { line ->
                line.trimEnd()
                    .replace(Regex("^#{1,4}\\s+"), "【")
                    .let { if (it.startsWith("【") && !it.endsWith("】")) "$it】" else it }
                    .replace("**", "")
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    internal fun extractTagFromReleaseUrl(urlOrLocation: String): String? {
        val marker = "/releases/tag/"
        val idx = urlOrLocation.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val tail = urlOrLocation.substring(idx + marker.length)
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore("/")
            .trim()
        return tail.takeIf { it.isNotBlank() }
    }

    /**
     * 测试当前代理配置连通性与响应延迟（毫秒）。
     */
    suspend fun testProxyConnectivity(proxyConfig: NetworkProxyConfig): ProxyConnectivityReport =
        withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            val summary = proxyConfig.statusSummaryLabel()
            val sampleGitHubAssetUrl = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"
            val testTargetUrl = when (proxyConfig.mode) {
                ProxyMode.PRESET_MIRROR, ProxyMode.CUSTOM_REVERSE_PROXY -> {
                    val prefix = proxyConfig.activeReverseProxyPrefix()
                    if (prefix.isNullOrBlank()) {
                        return@withContext ProxyConnectivityReport(
                            reachable = false,
                            latencyMillis = 0L,
                            routeSummary = summary,
                            detailMessage = "请先填写有效的自定义反向代理 URL 前缀"
                        )
                    }
                    proxyConfig.applyReverseProxyPrefix(sampleGitHubAssetUrl, prefix)
                }
                ProxyMode.HTTP_PROXY, ProxyMode.SOCKS_PROXY, ProxyMode.DIRECT -> LATEST_RELEASE_API
            }

            runCatching {
                val conn = openProxiedConnection(
                    targetUrl = testTargetUrl,
                    proxyConfig = proxyConfig,
                    connectTimeoutMs = 6_000,
                    readTimeoutMs = 6_000
                ).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = false
                }
                try {
                    val code = conn.responseCode
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    val ok = code in 200..399
                    ProxyConnectivityReport(
                        reachable = ok,
                        latencyMillis = elapsed,
                        routeSummary = summary,
                        detailMessage = if (ok) {
                            "连通正常 · 响应耗时 ${elapsed}ms ($summary)"
                        } else {
                            "连接返回 HTTP $code · 耗时 ${elapsed}ms ($summary)"
                        }
                    )
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { err ->
                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                ProxyConnectivityReport(
                    reachable = false,
                    latencyMillis = elapsed,
                    routeSummary = summary,
                    detailMessage = "连接失败 (${err.javaClass.simpleName}: ${err.message ?: "超时"})"
                )
            }
        }

    internal fun parseReleaseJson(jsonText: String, currentVersionName: String): GitHubReleaseInfo {
        val obj = JSONObject(jsonText)
        val tagName = obj.optString("tag_name", "").ifBlank { "v0.0.0" }
        val releaseTitle = obj.optString("name", tagName).ifBlank { tagName }
        val rawBody = obj.optString("body", "").trim()
        val changelog = formatCleanChangelog(rawBody).ifBlank { "常规性能优化与体验改进。" }
        val publishedAt = obj.optString("published_at", "").substringBefore("T")
        val htmlUrl = obj.optString("html_url", RELEASES_PAGE_URL)

        var selectedUrl: String? = null
        var selectedName = "day-in-school-$tagName-release.apk"
        var selectedSize = 0L
        var selectedSha256Hex: String? = null

        val assets = obj.optJSONArray("assets")
        if (assets != null && assets.length() > 0) {
            // 优先匹配签名的 -release.apk，其次任意 .apk
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val dlUrl = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)
                val rawDigest = asset.optString("digest", "").trim()
                val sha256 = rawDigest
                    .removePrefix("sha256:")
                    .removePrefix("SHA256:")
                    .trim()
                    .lowercase()
                    .takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
                if (name.endsWith(".apk", ignoreCase = true) && dlUrl.isNotBlank()) {
                    if (selectedUrl == null || name.contains("release", ignoreCase = true)) {
                        selectedUrl = dlUrl
                        selectedName = name
                        selectedSize = size
                        selectedSha256Hex = sha256
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
            currentVersionNormalized = currentNorm,
            expectedSha256Hex = selectedSha256Hex
        )
    }

    /**
     * 直接拉取 GitHub Release APK 到本地缓存目录（支持多路镜像代理与自动回退），并实时回调下载进度。
     */
    suspend fun downloadReleaseApk(
        context: Context,
        releaseInfo: GitHubReleaseInfo,
        proxyConfig: NetworkProxyConfig = NetworkProxyConfig(),
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val rawUrl = releaseInfo.apkDownloadUrl
                ?: throw IllegalArgumentException("未找到可用的 APK 下载链接")

            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeFileName = releaseInfo.apkFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(updatesDir, safeFileName)
            val tempFile = File(updatesDir, "$safeFileName.part")

            val candidateUrls = proxyConfig.buildGitHubDownloadCandidateUrls(rawUrl)
            var lastDownloadError: Throwable? = null

            for (candidateUrl in candidateUrls) {
                val routeAttempt = runCatching {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val conn = openConnectionFollowingRedirects(candidateUrl, proxyConfig)
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
                                    digest.update(buffer, 0, read)
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

                    if (!tempFile.exists() || tempFile.length() < 64 * 1024L) {
                        tempFile.delete()
                        throw IllegalStateException("下载内容不完整，自动切换备用线路")
                    }

                    val expectedSha256 = releaseInfo.expectedSha256Hex
                    if (!expectedSha256.isNullOrBlank()) {
                        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
                        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                            tempFile.delete()
                            throw SecurityException("APK SHA-256 摘要校验未通过，已阻断可疑镜像包并切换备用线路")
                        }
                    }

                    verifyDownloadedApkArchive(context, tempFile)
                }

                if (routeAttempt.isSuccess) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    if (!tempFile.renameTo(targetFile)) {
                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()
                    }
                    return@runCatching targetFile
                } else {
                    lastDownloadError = routeAttempt.exceptionOrNull()
                }
            }

            throw (lastDownloadError ?: IllegalStateException("所有代理与镜像线路均下载失败"))
        }
    }

    private fun openConnectionFollowingRedirects(
        initialUrl: String,
        proxyConfig: NetworkProxyConfig
    ): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val parsedCurrent = URL(currentUrl)
            if (!parsedCurrent.protocol.equals("https", ignoreCase = true) || parsedCurrent.userInfo != null) {
                throw SecurityException("拒绝非 HTTPS 下载重定向地址: $currentUrl")
            }
            val conn = openProxiedConnection(
                targetUrl = currentUrl,
                proxyConfig = proxyConfig,
                connectTimeoutMs = 12_000,
                readTimeoutMs = 25_000
            ).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
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
                val resolvedUrl = URL(parsedCurrent, location)
                if (!resolvedUrl.protocol.equals("https", ignoreCase = true) || resolvedUrl.userInfo != null) {
                    throw SecurityException("下载重定向尝试降级至非 HTTPS 协议，已阻断")
                }
                currentUrl = resolvedUrl.toString()
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

    private fun extractSigningCertDigests(pkgInfo: android.content.pm.PackageInfo?): Set<String> {
        if (pkgInfo == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures
        } ?: return emptySet()
        return sigs.mapNotNull { sig ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(sig.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    private fun verifyDownloadedApkArchive(context: Context, apkFile: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archiveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } ?: throw SecurityException("下载的文件不是有效的 Android APK 安装包")

        if (archiveInfo.packageName != context.packageName) {
            apkFile.delete()
            throw SecurityException("APK 包名不匹配 (${archiveInfo.packageName})，已拒绝安装")
        }

        val installedInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, flags)
        }
        val installedDigests = extractSigningCertDigests(installedInfo)
        val archiveDigests = extractSigningCertDigests(archiveInfo)
        if (installedDigests.isNotEmpty() && archiveDigests.isNotEmpty() &&
            installedDigests.intersect(archiveDigests).isEmpty()
        ) {
            apkFile.delete()
            throw SecurityException("下载的 APK 签名证书与当前应用不一致，已拦截可疑篡改包")
        }
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
            verifyDownloadedApkArchive(context, apkFile)
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

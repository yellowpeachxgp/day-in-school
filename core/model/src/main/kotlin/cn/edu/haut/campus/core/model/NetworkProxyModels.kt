package cn.edu.haut.campus.core.model

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * 网络与云服务代理模式：
 * 同时服务于 GitHub Release 在线更新检测/APK 下载加速，以及后续云服务 API 网关调用。
 */
enum class ProxyMode(
    val key: String,
    val label: String,
    val subtitle: String
) {
    PRESET_MIRROR(
        key = "PRESET_MIRROR",
        label = "预置镜像加速",
        subtitle = "通过内置高速镜像节点加速 GitHub 更新与资源拉取"
    ),
    CUSTOM_REVERSE_PROXY(
        key = "CUSTOM_REVERSE_PROXY",
        label = "自定义反代前缀",
        subtitle = "使用自建 Worker / Nginx 反代前缀转发 GitHub 与云服务请求"
    ),
    HTTP_PROXY(
        key = "HTTP_PROXY",
        label = "HTTP 代理",
        subtitle = "通过自定义 HTTP 代理服务器 (Host:Port) 转发网络流量"
    ),
    SOCKS_PROXY(
        key = "SOCKS_PROXY",
        label = "SOCKS5 代理",
        subtitle = "通过自定义 SOCKS5 代理服务器 (Host:Port) 转发网络流量"
    ),
    DIRECT(
        key = "DIRECT",
        label = "直连模式",
        subtitle = "直接连接目标服务器，不经过额外代理中转"
    );

    companion object {
        fun fromKey(key: String?): ProxyMode {
            return entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
                ?: PRESET_MIRROR
        }
    }
}

/**
 * 预置加速镜像代理线路：
 * 面向无代理客户端环境下的 GitHub Release 安装包与版本检测加速。
 */
enum class PresetMirrorNode(
    val key: String,
    val label: String,
    val prefixUrl: String,
    val description: String
) {
    GHFAST_TOP(
        key = "GHFAST_TOP",
        label = "GHFast 高速线路",
        prefixUrl = "https://ghfast.top/",
        description = "国内 CDN 节点加速，推荐默认使用"
    ),
    GH_PROXY_COM(
        key = "GH_PROXY_COM",
        label = "GH-Proxy 主线路",
        prefixUrl = "https://gh-proxy.com/",
        description = "通用 GitHub Release 资产镜像代理"
    ),
    GHPROXY_NET(
        key = "GHPROXY_NET",
        label = "GHProxy.net 镜像",
        prefixUrl = "https://ghproxy.net/",
        description = "海外优选边缘节点反向代理"
    ),
    MOEYY_CN(
        key = "MOEYY_CN",
        label = "Moeyy 公益镜像",
        prefixUrl = "https://github.moeyy.cn/",
        description = "国内公益 GitHub Release 下载加速节点"
    );

    companion object {
        fun fromKey(key: String?): PresetMirrorNode {
            return entries.firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
                ?: GHFAST_TOP
        }
    }
}

/**
 * 预置常见本地/局域网代理客户端快捷配置模板：
 * 方便使用 Clash / v2rayNG / NekoBox / Sing-box 的用户一键填入端口。
 */
enum class PresetLocalProxyClient(
    val key: String,
    val label: String,
    val mode: ProxyMode,
    val host: String,
    val port: Int
) {
    CLASH_HTTP("CLASH_HTTP", "Clash HTTP (7890)", ProxyMode.HTTP_PROXY, "127.0.0.1", 7890),
    CLASH_SOCKS("CLASH_SOCKS", "Clash SOCKS5 (7891)", ProxyMode.SOCKS_PROXY, "127.0.0.1", 7891),
    V2RAY_HTTP("V2RAY_HTTP", "v2rayNG HTTP (10809)", ProxyMode.HTTP_PROXY, "127.0.0.1", 10809),
    V2RAY_SOCKS("V2RAY_SOCKS", "v2rayNG SOCKS5 (10808)", ProxyMode.SOCKS_PROXY, "127.0.0.1", 10808),
    SINGBOX_MIXED("SINGBOX_MIXED", "Sing-box / NekoBox (2080)", ProxyMode.SOCKS_PROXY, "127.0.0.1", 2080)
}

/**
 * 统一网络代理与云服务网关配置模型。
 */
data class NetworkProxyConfig(
    val mode: ProxyMode = ProxyMode.PRESET_MIRROR,
    val presetMirrorKey: String = PresetMirrorNode.GHFAST_TOP.key,
    val customReverseProxyPrefix: String = "",
    val proxyHost: String = "127.0.0.1",
    val proxyPort: Int = 7890,
    val autoFallbackMirror: Boolean = true,
    val customCloudApiBaseUrl: String = DEFAULT_CLOUD_API_BASE_URL
) {
    companion object {
        const val DEFAULT_CLOUD_API_BASE_URL = "https://cloud.dayinschool.haut.edu.cn"

        fun normalizePrefixUrl(rawPrefix: String): String {
            val trimmed = rawPrefix.trim()
            if (trimmed.isEmpty()) return ""
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }
    }

    /**
     * 当前生效的反向代理 URL 前缀（仅在 PRESET_MIRROR 或 CUSTOM_REVERSE_PROXY 模式下返回非空）。
     */
    fun activeReverseProxyPrefix(): String? {
        return when (mode) {
            ProxyMode.PRESET_MIRROR -> PresetMirrorNode.fromKey(presetMirrorKey).prefixUrl
            ProxyMode.CUSTOM_REVERSE_PROXY -> normalizePrefixUrl(customReverseProxyPrefix).takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /**
     * 解析底层 Socket 级 `java.net.Proxy`：
     * - HTTP_PROXY -> `Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))`
     * - SOCKS_PROXY -> `Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))`
     * - 其余模式 -> `Proxy.NO_PROXY` 或系统默认连接
     */
    fun resolveJavaNetProxy(): Proxy? {
        val cleanHost = proxyHost.trim()
        val validPort = proxyPort.coerceIn(1, 65535)
        if (cleanHost.isBlank()) return null
        return when (mode) {
            ProxyMode.HTTP_PROXY -> Proxy(Proxy.Type.HTTP, InetSocketAddress(cleanHost, validPort))
            ProxyMode.SOCKS_PROXY -> Proxy(Proxy.Type.SOCKS, InetSocketAddress(cleanHost, validPort))
            else -> null
        }
    }

    /**
     * 将原始 GitHub 下载或 API 地址转换为带加速前缀的代理地址。
     */
    fun applyReverseProxyPrefix(rawUrl: String, prefix: String? = activeReverseProxyPrefix()): String {
        val cleanUrl = rawUrl.trim()
        val normalizedPrefix = prefix?.let { normalizePrefixUrl(it) }.orEmpty()
        if (normalizedPrefix.isBlank() || cleanUrl.isBlank()) return cleanUrl
        if (cleanUrl.startsWith(normalizedPrefix, ignoreCase = true)) return cleanUrl
        return "$normalizedPrefix$cleanUrl"
    }

    /**
     * 生成 GitHub 资源下载（如 Release APK）的多路候选 URL 列表：
     * 1. 首选当前用户配置的模式（如指定镜像节点、自定义反代前缀或直连）
     * 2. 若开启了 `autoFallbackMirror`，自动追加其余备选镜像节点与原始直连地址，确保高可用下载。
     */
    fun buildGitHubDownloadCandidateUrls(rawGitHubUrl: String): List<String> {
        val cleanUrl = rawGitHubUrl.trim()
        if (cleanUrl.isBlank()) return emptyList()

        val candidates = LinkedHashSet<String>()
        val primaryPrefix = activeReverseProxyPrefix()
        if (primaryPrefix != null) {
            candidates.add(applyReverseProxyPrefix(cleanUrl, primaryPrefix))
        } else {
            candidates.add(cleanUrl)
        }

        if (autoFallbackMirror) {
            val customPrefix = normalizePrefixUrl(customReverseProxyPrefix)
            if (customPrefix.isNotBlank()) {
                candidates.add(applyReverseProxyPrefix(cleanUrl, customPrefix))
            }
            for (mirror in PresetMirrorNode.entries) {
                candidates.add(applyReverseProxyPrefix(cleanUrl, mirror.prefixUrl))
            }
            candidates.add(cleanUrl)
        } else {
            candidates.add(cleanUrl)
        }

        return candidates.toList()
    }

    /**
     * 解析云服务 API 完整请求地址（支持后续云同步、云备份等模块直接调用）：
     * - 自动拼接 `customCloudApiBaseUrl` 与相对路径 `endpointPath`
     * - 若处于 `CUSTOM_REVERSE_PROXY` 且目标为外部云网关，也支持按需使用自定义网关
     */
    fun resolveCloudServiceUrl(endpointPath: String): String {
        val base = customCloudApiBaseUrl.trim().ifBlank { DEFAULT_CLOUD_API_BASE_URL }.trimEnd('/')
        val cleanPath = endpointPath.trim().trimStart('/')
        return if (cleanPath.isEmpty()) base else "$base/$cleanPath"
    }

    /**
     * 用于 UI 状态徽章展示的简短描述。
     */
    fun statusSummaryLabel(): String {
        return when (mode) {
            ProxyMode.PRESET_MIRROR -> PresetMirrorNode.fromKey(presetMirrorKey).label
            ProxyMode.CUSTOM_REVERSE_PROXY -> {
                val p = normalizePrefixUrl(customReverseProxyPrefix)
                if (p.isBlank()) "自定义反代 (未填写)" else p.removePrefix("https://").removePrefix("http://").trimEnd('/')
            }
            ProxyMode.HTTP_PROXY -> "HTTP $proxyHost:$proxyPort"
            ProxyMode.SOCKS_PROXY -> "SOCKS5 $proxyHost:$proxyPort"
            ProxyMode.DIRECT -> if (autoFallbackMirror) "直连 (含镜像回退)" else "纯直连"
        }
    }
}

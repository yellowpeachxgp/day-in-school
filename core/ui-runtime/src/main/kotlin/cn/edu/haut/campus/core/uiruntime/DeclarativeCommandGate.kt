package cn.edu.haut.campus.core.uiruntime

import cn.edu.haut.campus.core.packages.v2.PluginCommandDeclaration
import java.net.URI

/** 已通过权限门控、可被宿主以声明式语义执行的命令。 */
data class ResolvedDeclarativeCommand(
    val pluginId: String,
    val declaration: PluginCommandDeclaration,
    val type: String,
    val target: String
)

sealed interface DeclarativeCommandDecision {
    data class Allowed(val command: ResolvedDeclarativeCommand) : DeclarativeCommandDecision
    data class Blocked(val errorCode: String, val message: String) : DeclarativeCommandDecision
}

/**
 * 声明式命令唯一门禁。它只做静态判定，不执行插件代码、不回显请求 payload、不打开任意路由或 URL。
 */
object DeclarativeCommandGate {
    const val COMMAND_UNKNOWN = "PLUGIN_COMMAND_UNKNOWN"
    const val COMMAND_UNSUPPORTED = "PLUGIN_COMMAND_UNSUPPORTED"
    const val PERMISSION_DENIED = "PLUGIN_PERMISSION_DENIED"
    const val ORIGIN_DENIED = "PLUGIN_ORIGIN_DENIED"
    const val ROUTE_DENIED = "PLUGIN_ROUTE_DENIED"
    const val TARGET_INVALID = "PLUGIN_COMMAND_TARGET_INVALID"

    fun resolve(
        pluginId: String,
        commandName: String,
        declarations: List<PluginCommandDeclaration>,
        grantedPermissions: Set<String>,
        allowedOrigins: Set<String>,
        allowedHostRoutes: Set<String>
    ): DeclarativeCommandDecision {
        val declaration = declarations.firstOrNull { it.name == commandName }
            ?: return DeclarativeCommandDecision.Blocked(COMMAND_UNKNOWN, "插件未声明命令：" + commandName)
        val requiredPermission = declaration.requiredPermission
        if (!requiredPermission.isNullOrBlank() && requiredPermission !in grantedPermissions) {
            return DeclarativeCommandDecision.Blocked(PERMISSION_DENIED, "命令缺少已授予权限：" + requiredPermission)
        }
        return when (declaration.type) {
            "open_url" -> resolveOpenUrl(pluginId, declaration, allowedOrigins)
            "open_host_route" -> resolveHostRoute(pluginId, declaration, allowedHostRoutes)
            "show_data" -> resolveShowData(pluginId, declaration)
            else -> DeclarativeCommandDecision.Blocked(
                COMMAND_UNSUPPORTED,
                "命令类型不受支持：" + (declaration.type ?: "<missing>")
            )
        }
    }

    private fun resolveOpenUrl(
        pluginId: String,
        declaration: PluginCommandDeclaration,
        allowedOrigins: Set<String>
    ): DeclarativeCommandDecision {
        val raw = declaration.url ?: return DeclarativeCommandDecision.Blocked(TARGET_INVALID, "open_url 缺少 url")
        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return DeclarativeCommandDecision.Blocked(TARGET_INVALID, "open_url 不是合法 URI")
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null) {
            return DeclarativeCommandDecision.Blocked(TARGET_INVALID, "open_url 只允许无用户信息、无 fragment 的 HTTPS 地址")
        }
        val origin = originOf(uri) ?: return DeclarativeCommandDecision.Blocked(TARGET_INVALID, "open_url origin 非法")
        val trustedOrigins = allowedOrigins.mapNotNull { staticOrigin(it) }.toSet()
        if (origin !in trustedOrigins) {
            return DeclarativeCommandDecision.Blocked(ORIGIN_DENIED, "open_url 目标不在 allowed_origins 内：" + origin)
        }
        return DeclarativeCommandDecision.Allowed(
            ResolvedDeclarativeCommand(pluginId, declaration, "open_url", raw)
        )
    }

    private fun resolveHostRoute(
        pluginId: String,
        declaration: PluginCommandDeclaration,
        allowedHostRoutes: Set<String>
    ): DeclarativeCommandDecision {
        val route = declaration.route ?: return DeclarativeCommandDecision.Blocked(TARGET_INVALID, "open_host_route 缺少 route")
        if (route.isBlank() || route.length > 120 || route.any { it.isISOControl() || it == '\' || it == ' ' }) {
            return DeclarativeCommandDecision.Blocked(TARGET_INVALID, "open_host_route route 格式非法")
        }
        if (route !in allowedHostRoutes) {
            return DeclarativeCommandDecision.Blocked(ROUTE_DENIED, "open_host_route 不在宿主白名单：" + route)
        }
        return DeclarativeCommandDecision.Allowed(
            ResolvedDeclarativeCommand(pluginId, declaration, "open_host_route", route)
        )
    }

    private fun resolveShowData(
        pluginId: String,
        declaration: PluginCommandDeclaration
    ): DeclarativeCommandDecision {
        val artifactPath = declaration.artifactPath
            ?: return DeclarativeCommandDecision.Blocked(TARGET_INVALID, "show_data 缺少 artifact_path")
        if (!isSafeRelativePath(artifactPath)) {
            return DeclarativeCommandDecision.Blocked(TARGET_INVALID, "show_data artifact_path 不安全")
        }
        return DeclarativeCommandDecision.Allowed(
            ResolvedDeclarativeCommand(pluginId, declaration, "show_data", artifactPath)
        )
    }

    private fun originOf(uri: URI): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port >= 0) ":" + uri.port else ""
        return scheme + "://" + host + port
    }

    private fun staticOrigin(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) return null
        if (!uri.path.isNullOrEmpty() && uri.path != "/") return null
        if (uri.query != null || uri.fragment != null || uri.userInfo != null) return null
        return originOf(uri)
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.length > 240 || path.startsWith("/") || path.contains("\\")) return false
        if (path.contains("..")) return false
        return path.split('/').all { it.isNotBlank() && it != "." && it.length <= 80 }
    }
}

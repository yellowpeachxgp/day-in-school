package cn.edu.haut.campus.core.uiruntime

import cn.edu.haut.campus.core.packages.v2.ExtensionPluginInstaller
import java.nio.charset.StandardCharsets

/** Executes show_data by re-verifying package integrity, enabled state, and command permission at execution time. */
object VerifiedShowDataExecutor {
    fun readUtf8(
        installer: ExtensionPluginInstaller,
        schoolId: String,
        command: ResolvedDeclarativeCommand,
        maxChars: Int = 600
    ): String? = runCatching {
        require(command.type == "show_data") { "only show_data may read signed artifacts" }
        val installation = installer.installation(schoolId, command.pluginId) ?: error("插件未安装")
        if (installation.status != "ENABLED") error("插件已停用")
        val verified = installer.verifiedStoredPackage(schoolId, command.pluginId)
        val decision = DeclarativeCommandGate.resolve(
            pluginId = command.pluginId,
            commandName = command.declaration.name,
            declarations = verified.manifest.commands,
            grantedPermissions = installation.grantedPermissions.toSet(),
            allowedOrigins = verified.manifest.allowedOrigins,
            allowedHostRoutes = emptySet()
        )
        if (decision !is DeclarativeCommandDecision.Allowed || decision.command.target != command.target) {
            error("命令权限在执行前复核失败")
        }
        verified.artifactMap[command.target]?.let { String(it, StandardCharsets.UTF_8).take(maxChars) }
    }.getOrNull()
}

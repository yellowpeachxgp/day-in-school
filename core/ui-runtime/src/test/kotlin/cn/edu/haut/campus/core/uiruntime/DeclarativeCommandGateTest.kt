package cn.edu.haut.campus.core.uiruntime

import cn.edu.haut.campus.core.packages.v2.PluginCommandDeclaration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclarativeCommandGateTest {
    private val openUrl = PluginCommandDeclaration(
        name = "open",
        description = "open site",
        requiredPermission = "network.request",
        type = "open_url",
        url = "https://club.example.com/signup?source=card"
    )
    private val showData = PluginCommandDeclaration(
        name = "show",
        description = "show data",
        requiredPermission = "ui.slot.render",
        type = "show_data",
        artifactPath = "assets/data.json"
    )
    private val hostRoute = PluginCommandDeclaration(
        name = "route",
        description = "open host route",
        requiredPermission = null,
        type = "open_host_route",
        route = "parcel.hub"
    )

    @Test
    fun allowsOnlyDeclaredCommandsWithGrantedPermissionAndTrustedOrigin() {
        val decision = DeclarativeCommandGate.resolve(
            pluginId = "org.example.demo",
            commandName = "open",
            declarations = listOf(openUrl),
            grantedPermissions = setOf("network.request"),
            allowedOrigins = setOf("https://club.example.com"),
            allowedHostRoutes = emptySet()
        )
        assertTrue(decision is DeclarativeCommandDecision.Allowed)
        assertEquals("open_url", (decision as DeclarativeCommandDecision.Allowed).command.type)
    }

    @Test
    fun revokedPermissionBlocksImmediately() {
        val decision = DeclarativeCommandGate.resolve(
            pluginId = "org.example.demo",
            commandName = "open",
            declarations = listOf(openUrl),
            grantedPermissions = emptySet(),
            allowedOrigins = setOf("https://club.example.com"),
            allowedHostRoutes = emptySet()
        )
        assertTrue(decision is DeclarativeCommandDecision.Blocked)
        assertEquals(DeclarativeCommandGate.PERMISSION_DENIED, (decision as DeclarativeCommandDecision.Blocked).errorCode)
    }

    @Test
    fun untrustedOriginUnknownTypeAndUnsafeDataPathAreUnsupportedOrDenied() {
        val deniedOrigin = DeclarativeCommandGate.resolve(
            pluginId = "org.example.demo",
            commandName = "open",
            declarations = listOf(openUrl),
            grantedPermissions = setOf("network.request"),
            allowedOrigins = setOf("https://other.example.com"),
            allowedHostRoutes = emptySet()
        )
        assertEquals(DeclarativeCommandGate.ORIGIN_DENIED, (deniedOrigin as DeclarativeCommandDecision.Blocked).errorCode)

        val unknownType = openUrl.copy(type = "javascript", url = null)
        val unsupported = DeclarativeCommandGate.resolve(
            pluginId = "org.example.demo",
            commandName = "open",
            declarations = listOf(unknownType),
            grantedPermissions = setOf("network.request"),
            allowedOrigins = setOf("https://club.example.com"),
            allowedHostRoutes = emptySet()
        )
        assertEquals(DeclarativeCommandGate.COMMAND_UNSUPPORTED, (unsupported as DeclarativeCommandDecision.Blocked).errorCode)

        val evilData = showData.copy(artifactPath = "../secret")
        val badPath = DeclarativeCommandGate.resolve(
            pluginId = "org.example.demo",
            commandName = "show",
            declarations = listOf(evilData),
            grantedPermissions = setOf("ui.slot.render"),
            allowedOrigins = emptySet(),
            allowedHostRoutes = emptySet()
        )
        assertEquals(DeclarativeCommandGate.TARGET_INVALID, (badPath as DeclarativeCommandDecision.Blocked).errorCode)
    }

    @Test
    fun hostRouteMustBeExactWhitelistMatch() {
        val denied = DeclarativeCommandGate.resolve(
            pluginId = "org.example.demo",
            commandName = "route",
            declarations = listOf(hostRoute),
            grantedPermissions = emptySet(),
            allowedOrigins = emptySet(),
            allowedHostRoutes = setOf("settings.extensions")
        )
        assertEquals(DeclarativeCommandGate.ROUTE_DENIED, (denied as DeclarativeCommandDecision.Blocked).errorCode)

        val allowed = DeclarativeCommandGate.resolve(
            pluginId = "org.example.demo",
            commandName = "route",
            declarations = listOf(hostRoute),
            grantedPermissions = emptySet(),
            allowedOrigins = emptySet(),
            allowedHostRoutes = setOf("parcel.hub")
        )
        assertTrue(allowed is DeclarativeCommandDecision.Allowed)
    }
}

package cn.edu.haut.campus.core.packages.permission

import cn.edu.haut.campus.core.model.CapabilityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络权限匹配与撤销用例。
 *
 * UNVERIFIED：来源、路径与用途都是合成示例，不与任何真实学校教务地址对应。
 */
class PermissionScopeTest {

    private val readScope = PermissionScope(
        origin = "https://jwgl.example.edu",
        pathPrefix = "/app/",
        methods = setOf(PermissionMethod.GET),
        purposes = setOf(PermissionPurpose.READ)
    )

    @Test
    fun 精确来源与路径必须匹配() {
        assertTrue(
            "UNVERIFIED：同源同前缀的 GET 读取必须匹配",
            readScope.matches(PermissionMethod.GET, "https://jwgl.example.edu/app/query?term=1", PermissionPurpose.READ)
        )
    }

    @Test
    fun 其它来源与子域必须不匹配() {
        assertFalse(
            "UNVERIFIED：不同主机必须不匹配",
            readScope.matches(PermissionMethod.GET, "https://evil.example.com/app/query", PermissionPurpose.READ)
        )
        assertFalse(
            "UNVERIFIED：父域不匹配时子域也不能匹配",
            readScope.matches(PermissionMethod.GET, "https://jwgl.example.edu.evil.com/app/query", PermissionPurpose.READ)
        )
        assertFalse(
            "UNVERIFIED：不做子域通配",
            readScope.matches(PermissionMethod.GET, "https://sub.jwgl.example.edu/app/query", PermissionPurpose.READ)
        )
    }

    @Test
    fun 路径前缀必须按完整段比较() {
        assertFalse(
            "UNVERIFIED：/app 不能匹配 /application",
            readScope.matches(PermissionMethod.GET, "https://jwgl.example.edu/application", PermissionPurpose.READ)
        )
        assertTrue(
            "UNVERIFIED：前缀本身必须匹配",
            readScope.matches(PermissionMethod.GET, "https://jwgl.example.edu/app", PermissionPurpose.READ)
        )
        assertFalse(
            "UNVERIFIED：前缀之外的路径不能匹配",
            readScope.matches(PermissionMethod.GET, "https://jwgl.example.edu/other/app", PermissionPurpose.READ)
        )
    }

    @Test
    fun 路径穿越式的URL必须不匹配() {
        listOf(
            "https://jwgl.example.edu/app/../admin",
            "https://jwgl.example.edu/app/%2e%2e/admin",
            "https://jwgl.example.edu/app/..%2fadmin",
            "https://jwgl.example.edu/app/./grades"
        ).forEach { url ->
            assertFalse(
                "UNVERIFIED：带点段的地址不得落在 /app 授权内：" + url,
                readScope.matches(PermissionMethod.GET, url, PermissionPurpose.READ)
            )
        }
    }

    @Test
    fun 方法与用途必须同时匹配() {
        assertFalse(
            "UNVERIFIED：未授权的方法必须拒绝",
            readScope.matches(PermissionMethod.POST, "https://jwgl.example.edu/app/query", PermissionPurpose.READ)
        )
        assertFalse(
            "UNVERIFIED：未授权的用途必须拒绝",
            readScope.matches(PermissionMethod.GET, "https://jwgl.example.edu/app/query", PermissionPurpose.AUTH)
        )
    }

    @Test
    fun 明文用户信息片段与端口漂移必须不匹配() {
        assertFalse(
            "UNVERIFIED：明文 HTTP 必须拒绝",
            readScope.matches(PermissionMethod.GET, "http://jwgl.example.edu/app/query", PermissionPurpose.READ)
        )
        assertFalse(
            "UNVERIFIED：带用户信息的地址必须拒绝",
            readScope.matches(PermissionMethod.GET, "https://user@jwgl.example.edu/app/query", PermissionPurpose.READ)
        )
        assertFalse(
            "UNVERIFIED：带片段的地址必须拒绝",
            readScope.matches(PermissionMethod.GET, "https://jwgl.example.edu/app/query#frag", PermissionPurpose.READ)
        )
        assertFalse(
            "UNVERIFIED：端口不同必须拒绝",
            readScope.matches(PermissionMethod.GET, "https://jwgl.example.edu:8443/app/query", PermissionPurpose.READ)
        )
    }

    @Test
    fun 覆盖判定只看更宽的授权() {
        val broader = PermissionScope(
            origin = "https://jwgl.example.edu",
            pathPrefix = "/",
            methods = setOf(PermissionMethod.GET, PermissionMethod.POST),
            purposes = setOf(PermissionPurpose.READ, PermissionPurpose.AUTH, PermissionPurpose.WRITE)
        )
        assertTrue("UNVERIFIED：更宽的授权必须覆盖窄授权", broader.covers(readScope))
        assertFalse("UNVERIFIED：窄授权不能覆盖宽授权", readScope.covers(broader))
        val rightEdge = readScope.copy(pathPrefix = "/app/grades/")
        assertTrue("UNVERIFIED：同源更深路径应被前缀覆盖", readScope.covers(rightEdge))
    }

    @Test
    fun 权限扩大需要重新授权() {
        val base = PermissionSet.of(readScope)
        val widenedOrigin = PermissionSet.of(
            readScope.copy(origin = "https://other.example.edu")
        )
        val widenedPurposes = PermissionSet.of(readScope.copy(purposes = setOf(PermissionPurpose.READ, PermissionPurpose.AUTH)))
        val sameSet = PermissionSet.of(readScope)

        assertTrue(
            "UNVERIFIED：新增来源必须触发重新授权",
            PermissionRules.needsReauthorization(base, widenedOrigin)
        )
        assertTrue(
            "UNVERIFIED：新增用途必须触发重新授权",
            PermissionRules.needsReauthorization(base, widenedPurposes)
        )
        assertFalse(
            "UNVERIFIED：权限不变时不得要求重新授权",
            PermissionRules.needsReauthorization(base, sameSet)
        )
        assertFalse(
            "UNVERIFIED：权限缩小时不得要求重新授权",
            PermissionRules.needsReauthorization(base, PermissionSet.EMPTY)
        )
        assertEquals(
            "UNVERIFIED：新增条目必须可枚举",
            listOf(widenedOrigin.scopes.single()),
            PermissionRules.addedScopes(base, widenedOrigin)
        )
    }

    @Test
    fun 非法授权声明在构造阶段就拒绝() {
        val failures = listOf<() -> Unit>(
            { readScope.copy(origin = "http://jwgl.example.edu") },
            { readScope.copy(origin = "https://jwgl.example.edu/app") },
            { readScope.copy(pathPrefix = "app/") },
            { readScope.copy(pathPrefix = "/app/../admin") },
            { PermissionScope("https://jwgl.example.edu", "/app/", setOf(PermissionMethod.GET), setOf(PermissionPurpose.WRITE)) },
            { readScope.copy(methods = emptySet()) }
        )
        failures.forEachIndexed { index, block ->
            val error = try {
                block()
                null
            } catch (error: IllegalArgumentException) {
                error
            }
            assertTrue("UNVERIFIED：非法授权必须被拒绝，用例 " + index, error != null)
        }
    }

    @Test
    fun 撤销后所有能力立即失效() {
        val registry = PackageGrantRegistry()
        registry.grant(
            packageId = "cn.example.synthetic-profile",
            permissions = PermissionSet.of(readScope),
            capabilities = setOf(CapabilityKind.TIMETABLE_READ)
        )
        assertTrue(
            "UNVERIFIED：授权后必须允许匹配请求",
            registry.allows(
                "cn.example.synthetic-profile",
                PermissionMethod.GET,
                "https://jwgl.example.edu/app/query",
                PermissionPurpose.READ
            )
        )
        assertEquals(
            "UNVERIFIED：授权后能力必须可见",
            setOf(CapabilityKind.TIMETABLE_READ),
            registry.capabilities("cn.example.synthetic-profile")
        )

        registry.revoke("cn.example.synthetic-profile")
        assertFalse(
            "UNVERIFIED：撤销后网络请求必须立即失效",
            registry.allows(
                "cn.example.synthetic-profile",
                PermissionMethod.GET,
                "https://jwgl.example.edu/app/query",
                PermissionPurpose.READ
            )
        )
        assertTrue(
            "UNVERIFIED：撤销后能力集合必须立即为空",
            registry.capabilities("cn.example.synthetic-profile").isEmpty()
        )
        assertTrue(
            "UNVERIFIED：撤销后权限集合必须立即为空",
            registry.permissions("cn.example.synthetic-profile").isEmpty
        )
        assertTrue(
            "UNVERIFIED：撤销后重新安装必须再次授权",
            registry.reauthorizationRequired("cn.example.synthetic-profile", PermissionSet.of(readScope))
        )
    }

    @Test
    fun 重新授权后恢复能力但仍保留历史撤销记录直到再次授权() {
        val registry = PackageGrantRegistry()
        registry.grant(
            packageId = "pkg",
            permissions = PermissionSet.of(readScope),
            capabilities = setOf(CapabilityKind.GRADES_READ)
        )
        registry.revoke("pkg")
        assertTrue("UNVERIFIED：撤销状态必须可查询", registry.isRevoked("pkg"))
        assertEquals("UNVERIFIED：撤销集合必须包含该包", setOf("pkg"), registry.revokedPackages())
        registry.grant(
            packageId = "pkg",
            permissions = PermissionSet.of(readScope),
            capabilities = setOf(CapabilityKind.GRADES_READ)
        )
        assertFalse("UNVERIFIED：重新授权后不再处于撤销状态", registry.isRevoked("pkg"))
        assertTrue(
            "UNVERIFIED：重新授权后能力恢复",
            registry.capabilities("pkg").contains(CapabilityKind.GRADES_READ)
        )
    }
}

package cn.edu.haut.campus.core.packages.install

import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.SyntheticPackages
import cn.edu.haut.campus.core.packages.SyntheticPackages.SignedPackage
import cn.edu.haut.campus.core.packages.assertInstallRejected
import cn.edu.haut.campus.core.packages.manifest.PackageManifestCodec
import cn.edu.haut.campus.core.packages.permission.PackageGrantRegistry
import cn.edu.haut.campus.core.packages.permission.PermissionMethod
import cn.edu.haut.campus.core.packages.permission.PermissionPurpose
import cn.edu.haut.campus.core.packages.permission.PermissionSet
import cn.edu.haut.campus.core.packages.store.InMemoryPackageStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装编排用例：签名、宿主兼容、目标匹配、权限授权、失败不激活、回滚。
 *
 * UNVERIFIED：全部包、密钥与宿主版本都是合成数据；没有对任何真实学校包做过安装验证。
 */
class PackageInstallerTest {

    private val keyPair = SyntheticPackages.keyPair()
    private val keys = SyntheticPackages.registry(keyPair)
    private val grants = PackageGrantRegistry()
    private val store = InMemoryPackageStore()
    private val installer = PackageInstaller(store, grants, keys)

    private val scope = InstallScope(
        schoolId = SchoolId(SyntheticPackages.SCHOOL_ID),
        providerId = SyntheticPackages.PROVIDER_ID
    )

    private val host = HostEnvironment(
        hostApi = HostCompatibility.CURRENT_HOST_API,
        appVersionCode = 1103
    )

    private fun contents(version: String): Map<String, String> =
        mapOf("profile/school.json" to "{\"version\":\"" + version + "\"}")

    private fun permissions(vararg origins: String): String = "[" + origins.joinToString(",") { origin ->
        "{\"kind\":\"network.request\",\"origins\":[\"" + origin + "\"]," +
            "\"paths\":[\"/app/\"],\"methods\":[\"GET\"],\"purposes\":[\"READ\"]}"
    } + "]"

    private fun signedManifest(
        version: String = "1.0.0",
        permissions: String = SyntheticPackages.DEFAULT_PERMISSIONS,
        capabilities: List<String> = listOf("TIMETABLE_READ", "GRADES_READ"),
        minHostApi: Int = 1,
        minAppVersionCode: Int = 1103,
        targetSchool: String = SyntheticPackages.SCHOOL_ID,
        targetProvider: String? = SyntheticPackages.PROVIDER_ID,
        contents: Map<String, String> = contents(version)
    ): SignedPackage {
        val bundle = SyntheticPackages.bundle(contents)
        val text = SyntheticPackages.manifest(
            bundle = bundle,
            version = version,
            permissions = permissions,
            capabilities = capabilities,
            minHostApi = minHostApi,
            minAppVersionCode = minAppVersionCode,
            targetSchool = targetSchool,
            targetProvider = targetProvider
        )
        val bytes = text.toByteArray(Charsets.UTF_8)
        return SignedPackage(
            bundle = bundle,
            manifestBytes = bytes,
            signature = SyntheticPackages.sign(keyPair, bytes),
            manifestText = text
        )
    }

    private fun request(
        signed: SignedPackage,
        consent: PermissionSet? = null,
        scope: InstallScope = this.scope,
        host: HostEnvironment = this.host
    ): InstallRequest = InstallRequest(
        manifestBytes = signed.manifestBytes,
        bundle = signed.bundle,
        signature = signed.signature,
        scope = scope,
        host = host,
        consent = consent
    )

    private fun consentOf(signed: SignedPackage): PermissionSet =
        PackageManifestCodec.parse(signed.manifestBytes).permissionSet

    @Test
    fun 首次安装没有显式同意时拒绝且不激活() {
        val signed = signedManifest()
        val outcome = installer.install(request(signed))
        assertInstallRejected(PackageError.PERMISSION_REAUTHORIZATION_REQUIRED, outcome)
        val rejected = outcome as InstallOutcome.Rejected
        assertTrue("UNVERIFIED：必须列出被要求的权限条目", rejected.requiredScopes.isNotEmpty())
        assertNull("UNVERIFIED：未同意时不得激活", store.active(SyntheticPackages.PACKAGE_ID))
        assertTrue(
            "UNVERIFIED：未同意时不得登记能力",
            grants.capabilities(SyntheticPackages.PACKAGE_ID).isEmpty()
        )
    }

    @Test
    fun 取得同意后安装成功并登记能力() {
        val signed = signedManifest()
        val outcome = installer.install(request(signed, consent = consentOf(signed)))
        val installed = outcome as? InstallOutcome.Installed
        assertNotNull("UNVERIFIED：同意后必须安装成功，实际为 " + outcome, installed)
        assertEquals("UNVERIFIED：活动版本必须为清单版本", "1.0.0", installed!!.active.version.value)
        assertEquals("UNVERIFIED：清单摘要必须为 64 位", 64, installed.manifestDigest.length)
        assertTrue(
            "UNVERIFIED：安装后能力必须登记",
            grants.capabilities(SyntheticPackages.PACKAGE_ID).contains(CapabilityKind.TIMETABLE_READ)
        )
        assertTrue(
            "UNVERIFIED：安装后授权范围内的请求必须允许",
            grants.allows(
                SyntheticPackages.PACKAGE_ID,
                PermissionMethod.GET,
                SyntheticPackages.ORIGIN + "/app/query",
                PermissionPurpose.READ
            )
        )
        assertNotNull(
            "UNVERIFIED：安装后必须存在活动版本",
            installer.activeVersion(SyntheticPackages.PACKAGE_ID)
        )
    }

    @Test
    fun 伪造签名必须在写盘前拒绝() {
        val signed = signedManifest()
        val attacker = SyntheticPackages.keyPair()
        val forged = signed.copy(signature = SyntheticPackages.sign(attacker, signed.manifestBytes))
        val outcome = installer.install(request(forged, consent = consentOf(signed)))
        assertInstallRejected(PackageError.SIGNATURE_INVALID, outcome)
        assertNull("UNVERIFIED：伪造签名不得激活", store.active(SyntheticPackages.PACKAGE_ID))
    }

    @Test
    fun 宿主API版本过低必须拒绝() {
        val signed = signedManifest(minHostApi = HostCompatibility.CURRENT_HOST_API + 1)
        val outcome = installer.install(request(signed, consent = consentOf(signed)))
        assertInstallRejected(PackageError.HOST_API_TOO_OLD, outcome)
        assertNull("UNVERIFIED：不兼容宿主不得激活", store.active(SyntheticPackages.PACKAGE_ID))
    }

    @Test
    fun App版本码过低必须拒绝() {
        val signed = signedManifest(minAppVersionCode = 9999)
        val outcome = installer.install(request(signed, consent = consentOf(signed)))
        assertInstallRejected(PackageError.APP_VERSION_TOO_OLD, outcome)
    }

    @Test
    fun 目标学校或教务实例不一致必须拒绝() {
        val signed = signedManifest()
        val otherSchool = installer.install(
            request(
                signed,
                consent = consentOf(signed),
                scope = InstallScope(SchoolId("other-univ"), SyntheticPackages.PROVIDER_ID)
            )
        )
        assertInstallRejected(PackageError.TARGET_MISMATCH, otherSchool)

        val otherProvider = installer.install(
            request(
                signed,
                consent = consentOf(signed),
                scope = InstallScope(SchoolId(SyntheticPackages.SCHOOL_ID), "other-provider")
            )
        )
        assertInstallRejected(PackageError.TARGET_MISMATCH, otherProvider)
        assertNull("UNVERIFIED：目标不一致不得激活", store.active(SyntheticPackages.PACKAGE_ID))
    }

    @Test
    fun 权限扩大必须重新授权且拒绝时不升级版本() {
        val first = signedManifest(version = "1.0.0")
        val firstOutcome = installer.install(request(first, consent = consentOf(first)))
        assertTrue("UNVERIFIED：首版必须安装成功", firstOutcome is InstallOutcome.Installed)

        val widened = signedManifest(version = "1.1.0", permissions = permissions(SyntheticPackages.ORIGIN, "https://other.example.edu"))
        val withoutConsent = installer.install(request(widened))
        assertInstallRejected(PackageError.PERMISSION_REAUTHORIZATION_REQUIRED, withoutConsent)
        val rejected = withoutConsent as InstallOutcome.Rejected
        assertEquals(
            "UNVERIFIED：必须只列出新增的权限条目",
            1,
            rejected.requiredScopes.size
        )
        assertEquals(
            "UNVERIFIED：活动版本必须仍为旧版本",
            "1.0.0",
            store.active(SyntheticPackages.PACKAGE_ID)?.version?.value
        )

        val widenedConsent = PermissionSet(consentOf(first).scopes + consentOf(widened).scopes)
        val upgraded = installer.install(request(widened, consent = widenedConsent))
        assertTrue("UNVERIFIED：重新授权后必须升级成功", upgraded is InstallOutcome.Installed)
        assertEquals(
            "UNVERIFIED：升级后活动版本必须是新版本",
            "1.1.0",
            store.active(SyntheticPackages.PACKAGE_ID)?.version?.value
        )
        assertTrue(
            "UNVERIFIED：升级后新来源必须可用",
            grants.allows(
                SyntheticPackages.PACKAGE_ID,
                PermissionMethod.GET,
                "https://other.example.edu/app/query",
                PermissionPurpose.READ
            )
        )
    }

    @Test
    fun 撤销后能力立即失效且重装必须重新授权() {
        val signed = signedManifest()
        assertTrue(
            "UNVERIFIED：首装必须成功",
            installer.install(request(signed, consent = consentOf(signed))) is InstallOutcome.Installed
        )
        installer.revoke(SyntheticPackages.PACKAGE_ID)
        assertTrue(
            "UNVERIFIED：撤销后能力必须立即为空",
            grants.capabilities(SyntheticPackages.PACKAGE_ID).isEmpty()
        )
        assertTrue(
            "UNVERIFIED：撤销后请求必须立即拒绝",
            !grants.allows(
                SyntheticPackages.PACKAGE_ID,
                PermissionMethod.GET,
                SyntheticPackages.ORIGIN + "/app/query",
                PermissionPurpose.READ
            )
        )
        val withoutConsent = installer.install(request(signed))
        assertInstallRejected(PackageError.PERMISSION_REAUTHORIZATION_REQUIRED, withoutConsent)

        val emptyConsent = installer.install(request(signed, consent = PermissionSet.EMPTY))
        assertInstallRejected(PackageError.PERMISSION_REAUTHORIZATION_REQUIRED, emptyConsent)

        val reauthorized = installer.install(request(signed, consent = consentOf(signed)))
        assertTrue("UNVERIFIED：重新授权后必须可安装", reauthorized is InstallOutcome.Installed)
        assertTrue(
            "UNVERIFIED：重新授权后能力必须恢复",
            grants.capabilities(SyntheticPackages.PACKAGE_ID).contains(CapabilityKind.TIMETABLE_READ)
        )
    }

    @Test
    fun 文件内容与清单不一致时拒绝且不登记授权() {
        val signed = signedManifest()
        val tampered = signed.copy(bundle = SyntheticPackages.bundle(contents("1.0.0-tampered")))
        val outcome = installer.install(request(tampered, consent = consentOf(signed)))
        assertInstallRejected(PackageError.FILE_HASH_MISMATCH, outcome)
        assertNull("UNVERIFIED：内容被篡改不得激活", store.active(SyntheticPackages.PACKAGE_ID))
        assertTrue(
            "UNVERIFIED：失败安装不得留下授权",
            grants.permissions(SyntheticPackages.PACKAGE_ID).isEmpty
        )
    }

    @Test
    fun 安装器可以回滚到上一个已验证版本() {
        val first = signedManifest(version = "1.0.0")
        val second = signedManifest(version = "1.1.0")
        assertTrue(
            "UNVERIFIED：首版必须安装成功",
            installer.install(request(first, consent = consentOf(first))) is InstallOutcome.Installed
        )
        assertTrue(
            "UNVERIFIED：次版必须安装成功",
            installer.install(request(second, consent = consentOf(second))) is InstallOutcome.Installed
        )
        val rolledBack = installer.rollback(SyntheticPackages.PACKAGE_ID)
        assertEquals("UNVERIFIED：回滚必须回到首版", "1.0.0", rolledBack.version.value)
        assertEquals(
            "UNVERIFIED：回滚后活动版本必须同步",
            "1.0.0",
            store.active(SyntheticPackages.PACKAGE_ID)?.version?.value
        )
    }
}

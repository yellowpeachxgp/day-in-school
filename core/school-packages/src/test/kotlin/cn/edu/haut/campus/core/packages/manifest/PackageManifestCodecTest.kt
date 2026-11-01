package cn.edu.haut.campus.core.packages.manifest

import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.SyntheticPackages
import cn.edu.haut.campus.core.packages.assertPackageError
import cn.edu.haut.campus.core.packages.permission.PermissionMethod
import cn.edu.haut.campus.core.packages.permission.PermissionPurpose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dis-campus-plugin/v1 清单解析用例。
 *
 * UNVERIFIED：全部输入都是按公开协议格式构造的合成清单，不代表任何真实学校或发行渠道。
 */
class PackageManifestCodecTest {

    private val bundle = SyntheticPackages.bundle()

    @Test
    fun 合法合成清单可以解析出全部字段() {
        val manifest = PackageManifestCodec.parse(SyntheticPackages.manifest(bundle))
        assertEquals("UNVERIFIED：包 ID 解析错误", SyntheticPackages.PACKAGE_ID, manifest.id)
        assertEquals("UNVERIFIED：版本解析错误", PackageVersion("1.0.0"), manifest.version)
        assertEquals("UNVERIFIED：包类型解析错误", PackageType.SCHOOL_PROFILE, manifest.type)
        assertEquals("UNVERIFIED：许可证解析错误", "Apache-2.0", manifest.license.spdxId)
        assertEquals("UNVERIFIED：能力集合解析错误", 2, manifest.capabilities.size)
        assertEquals("UNVERIFIED：文件摘要与真实内容不一致", bundle.digest, manifest.filesDigest)
        assertEquals("UNVERIFIED：目标学校解析错误", SyntheticPackages.SCHOOL_ID, manifest.targets.single().schoolId.value)
        assertEquals("UNVERIFIED：验证等级解析错误", VerificationLevel.MOCK_TESTED, manifest.verification.level)
        val scope = manifest.permissionSet.scopes.single()
        assertEquals("UNVERIFIED：权限方法解析错误", setOf(PermissionMethod.GET), scope.methods)
        assertEquals("UNVERIFIED：权限用途解析错误", setOf(PermissionPurpose.READ), scope.purposes)
    }

    @Test
    fun 未知顶层字段必须拒绝而不是忽略() {
        val text = SyntheticPackages.manifest(bundle, extraTopLevel = "\"exec\":\"native\"")
        assertPackageError(PackageError.MANIFEST_INVALID) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 非声明式包类型必须拒绝() {
        val text = SyntheticPackages.manifest(bundle, type = "native-code")
        assertPackageError(PackageError.MANIFEST_UNSUPPORTED_FORMAT) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 非白名单许可证必须拒绝() {
        val text = SyntheticPackages.manifest(bundle, license = "GPL-3.0")
        assertPackageError(PackageError.MANIFEST_INVALID) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 文件摘要格式错误必须拒绝() {
        val text = SyntheticPackages.manifest(bundle, files = mapOf("profile/school.json" to "NOT-A-SHA256"))
        assertPackageError(PackageError.MANIFEST_INVALID) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 写用途必须同时声明POST() {
        val permissions = "[" +
            "{\"kind\":\"network.request\",\"origins\":[\"" + SyntheticPackages.ORIGIN + "\"]," +
            "\"paths\":[\"/app/\"],\"methods\":[\"GET\"],\"purposes\":[\"WRITE\"]}]"
        val text = SyntheticPackages.manifest(bundle, permissions = permissions)
        assertPackageError(PackageError.MANIFEST_INVALID) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 未知能力必须拒绝() {
        val text = SyntheticPackages.manifest(bundle, capabilities = listOf("ROOT_SHELL"))
        assertPackageError(PackageError.MANIFEST_INVALID) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 清单声明路径穿越的文件必须拒绝() {
        val text = SyntheticPackages.manifest(
            bundle,
            files = mapOf("../etc/passwd" to bundle.files.single().sha256)
        )
        assertPackageError(PackageError.UNSAFE_PATH) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 权限条目带未知字段必须拒绝() {
        val permissions = "[" +
            "{\"kind\":\"network.request\",\"origins\":[\"" + SyntheticPackages.ORIGIN + "\"]," +
            "\"paths\":[\"/app/\"],\"methods\":[\"GET\"],\"purposes\":[\"READ\"]," +
            "\"headers\":{\"Cookie\":\"x\"}}]"
        val text = SyntheticPackages.manifest(bundle, permissions = permissions)
        assertPackageError(PackageError.MANIFEST_INVALID) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 非HTTPS来源必须拒绝() {
        val permissions = "[" +
            "{\"kind\":\"network.request\",\"origins\":[\"http://jwgl.example.edu\"]," +
            "\"paths\":[\"/app/\"],\"methods\":[\"GET\"],\"purposes\":[\"READ\"]}]"
        val text = SyntheticPackages.manifest(bundle, permissions = permissions)
        assertPackageError(PackageError.MANIFEST_INVALID) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 不支持的格式标识必须按格式错误拒绝() {
        val text = SyntheticPackages.manifest(bundle).replace("dis-campus-plugin/v1", "evil-plugin/v9")
        assertPackageError(PackageError.MANIFEST_UNSUPPORTED_FORMAT) { PackageManifestCodec.parse(text) }
    }

    @Test
    fun 非法JSON必须拒绝() {
        assertPackageError(PackageError.MANIFEST_INVALID) { PackageManifestCodec.parse("{") }
    }

    @Test
    fun 规范化字节与空白和数字写法无关() {
        val text = SyntheticPackages.manifest(bundle)
        val reflowed = text
            .replace("\"minHostApi\": 1", "\"minHostApi\": 1.0")
            .replace("\n", " ")
            .replace("  ", "")
        val canonicalA = String(PackageManifestCodec.canonicalBytes(text.toByteArray()), Charsets.UTF_8)
        val canonicalB = String(PackageManifestCodec.canonicalBytes(reflowed.toByteArray()), Charsets.UTF_8)
        assertEquals("UNVERIFIED：等价清单必须得到同一规范化形式", canonicalA, canonicalB)
        assertEquals(
            "UNVERIFIED：等价清单必须得到同一摘要",
            Digests.sha256Hex(canonicalA),
            Digests.sha256Hex(canonicalB)
        )
        assertTrue("UNVERIFIED：规范化形式不应包含换行", !canonicalA.contains('\n'))
    }
}

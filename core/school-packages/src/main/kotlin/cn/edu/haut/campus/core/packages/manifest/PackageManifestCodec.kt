package cn.edu.haut.campus.core.packages.manifest

import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonParseException
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.bundle.PackageFileDigest
import cn.edu.haut.campus.core.packages.permission.NetworkPermission
import cn.edu.haut.campus.core.packages.permission.PermissionMethod
import cn.edu.haut.campus.core.packages.permission.PermissionPurpose
import java.nio.charset.StandardCharsets

/**
 * dis-campus-plugin/v1 清单的严格解析器。
 *
 * 解析失败一律抛 [PackageException]，错误码区分"格式不认识"与"字段不合法"。
 * 顶层与权限条目都拒绝未知字段：清单是签名覆盖对象，多一个未解释字段
 * 就等于签名者要求宿主做未定义的事。
 *
 * UNVERIFIED：清单格式是本仓库按公开协议格式自行设计的合成格式，
 * 不代表任何真实学校、发行渠道或第三方插件市场。
 */
object PackageManifestCodec {

    const val FORMAT: String = "dis-campus-plugin/v1"

    /** 清单上限 256 KiB；正常清单只有几 KiB。 */
    const val MAX_MANIFEST_BYTES: Int = 256 * 1024

    private val TOP_LEVEL_KEYS = setOf(
        "format", "id", "version", "type", "publisher", "license", "targets",
        "minHostApi", "minAppVersionCode", "capabilities", "requiredPermissions",
        "files", "verification"
    )

    private val PERMISSION_KEYS = setOf("kind", "origins", "paths", "methods", "purposes")

    private val NETWORK_PERMISSION_KIND = "network.request"

    fun parse(text: String): PackageManifest = fromDocument(document(text))

    fun parse(bytes: ByteArray): PackageManifest = parse(String(bytes, StandardCharsets.UTF_8))

    /** 规范化后的清单字节；签名只覆盖它的摘要，因此格式差异不影响验签。 */
    fun canonicalBytes(bytes: ByteArray): ByteArray =
        Json.canonical(document(String(bytes, StandardCharsets.UTF_8))).toByteArray(StandardCharsets.UTF_8)

    /**
     * 解析文档并做与具体字段无关的结构检查（体积、JSON 合法性、顶层对象、
     * 未知字段、format 取值）。签名载荷也复用这一步，保证"验签看到的清单"
     * 与"安装时解释的清单"是同一份结构。
     */
    internal fun document(text: String): JsonValue.Obj {
        if (text.length > MAX_MANIFEST_BYTES) {
            throw PackageException(PackageError.MANIFEST_TOO_LARGE, "清单超出体积上限")
        }
        val value = try {
            Json.parse(text)
        } catch (error: JsonParseException) {
            throw PackageException(PackageError.MANIFEST_INVALID, "清单不是合法 JSON：" + error.message)
        }
        val obj = value.asObjOrNull
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "清单顶层必须是 JSON 对象")
        val unknown = obj.fields.keys - TOP_LEVEL_KEYS
        if (unknown.isNotEmpty()) {
            throw PackageException(PackageError.MANIFEST_INVALID, "清单包含未知字段：" + unknown.sorted().joinToString())
        }
        val format = obj.fields["format"]?.stringOrNull()
            ?: throw PackageException(PackageError.MANIFEST_UNSUPPORTED_FORMAT, "清单缺少 format")
        if (format != FORMAT) {
            throw PackageException(PackageError.MANIFEST_UNSUPPORTED_FORMAT, "不支持的清单格式：" + format)
        }
        return obj
    }

    private fun fromDocument(obj: JsonValue.Obj): PackageManifest {
        val id = requiredString(obj, "id")
        if (!PackageManifest.PACKAGE_ID.matches(id)) {
            fail(PackageError.MANIFEST_INVALID, "包 ID 必须是小写标识符")
        }
        val versionRaw = requiredString(obj, "version")
        val version = PackageVersion.parseOrNull(versionRaw)
            ?: fail(PackageError.MANIFEST_INVALID, "版本号必须形如 1.2.3 或 1.2.3-beta.1")
        val typeRaw = requiredString(obj, "type")
        val type = PackageType.fromWire(typeRaw)
            ?: fail(PackageError.MANIFEST_UNSUPPORTED_FORMAT, "不支持的包类型：" + typeRaw)
        val publisher = requiredString(obj, "publisher")
        val licenseRaw = requiredString(obj, "license")
        if (licenseRaw !in PackageLicense.ALLOWED) {
            fail(PackageError.MANIFEST_INVALID, "许可证不在允许清单内：" + licenseRaw)
        }
        val targets = requiredArray(obj, "targets").map { item -> parseTarget(item) }
        val minHostApi = requiredInt(obj, "minHostApi")
        val minAppVersionCode = requiredInt(obj, "minAppVersionCode")
        val capabilities = requiredArray(obj, "capabilities").map { item ->
            val name = item.stringOrNull()
                ?: fail(PackageError.MANIFEST_INVALID, "capabilities 条目必须是字符串")
            CapabilityKind.entries.firstOrNull { it.name == name }
                ?: fail(PackageError.MANIFEST_INVALID, "未知能力：" + name)
        }.toSet()
        val permissions = requiredArray(obj, "requiredPermissions").map { parsePermission(it) }
        val files = requiredObject(obj, "files").fields.mapValues { (path, value) ->
            val digest = value.stringOrNull()
                ?: fail(PackageError.MANIFEST_INVALID, "files 摘要必须是字符串：" + path)
            if (!PackageFileDigest.isSha256(digest)) {
                fail(PackageError.MANIFEST_INVALID, "files 摘要必须是 64 位小写十六进制：" + path)
            }
            digest
        }
        val verification = parseVerification(obj)
        return try {
            PackageManifest(
                id = id,
                version = version,
                type = type,
                publisher = publisher,
                license = PackageLicense(licenseRaw),
                targets = targets,
                minHostApi = minHostApi,
                minAppVersionCode = minAppVersionCode,
                capabilities = capabilities,
                permissions = permissions,
                files = files,
                verification = verification
            )
        } catch (error: PackageException) {
            throw error
        } catch (error: IllegalArgumentException) {
            fail(PackageError.MANIFEST_INVALID, error.message ?: "清单字段不合法")
        }
    }

    private fun parseTarget(item: JsonValue): PackageTarget {
        val obj = item.asObjOrNull ?: fail(PackageError.MANIFEST_INVALID, "targets 条目必须是对象")
        val unknown = obj.fields.keys - setOf("schoolId", "provider")
        if (unknown.isNotEmpty()) {
            fail(PackageError.MANIFEST_INVALID, "targets 包含未知字段：" + unknown.sorted().joinToString())
        }
        val schoolRaw = requiredString(obj, "schoolId")
        val schoolId = try {
            SchoolId(schoolRaw)
        } catch (_: IllegalArgumentException) {
            fail(PackageError.MANIFEST_INVALID, "schoolId 非法：" + schoolRaw)
        }
        val provider = obj.fields["provider"]?.let { value ->
            value.stringOrNull() ?: fail(PackageError.MANIFEST_INVALID, "provider 必须是字符串")
        }
        return try {
            PackageTarget(schoolId = schoolId, provider = provider)
        } catch (_: IllegalArgumentException) {
            fail(PackageError.MANIFEST_INVALID, "provider 非法")
        }
    }

    private fun parsePermission(item: JsonValue): NetworkPermission {
        val obj = item.asObjOrNull ?: fail(PackageError.MANIFEST_INVALID, "requiredPermissions 条目必须是对象")
        val unknown = obj.fields.keys - PERMISSION_KEYS
        if (unknown.isNotEmpty()) {
            fail(PackageError.MANIFEST_INVALID, "requiredPermissions 包含未知字段：" + unknown.sorted().joinToString())
        }
        val kind = requiredString(obj, "kind")
        if (kind != NETWORK_PERMISSION_KIND) {
            fail(PackageError.MANIFEST_UNSUPPORTED_FORMAT, "不支持的权限类型：" + kind)
        }
        val origins = requiredStringArray(obj, "origins").toSet()
        val paths = requiredStringArray(obj, "paths").toSet()
        val methods = requiredStringArray(obj, "methods").map { raw ->
            PermissionMethod.entries.firstOrNull { it.name == raw }
                ?: fail(PackageError.MANIFEST_INVALID, "未知请求方法：" + raw)
        }.toSet()
        val purposes = requiredStringArray(obj, "purposes").map { raw ->
            PermissionPurpose.entries.firstOrNull { it.name == raw }
                ?: fail(PackageError.MANIFEST_INVALID, "未知请求用途：" + raw)
        }.toSet()
        val permission = try {
            NetworkPermission(origins = origins, paths = paths, methods = methods, purposes = purposes)
        } catch (error: IllegalArgumentException) {
            fail(PackageError.MANIFEST_INVALID, error.message ?: "网络权限不合法")
        }
        // 立即展开一次：来源必须是 HTTPS、路径前缀必须规范，否则清单整体不成立。
        try {
            permission.scopes()
        } catch (error: IllegalArgumentException) {
            fail(PackageError.MANIFEST_INVALID, error.message ?: "网络权限不合法")
        }
        return permission
    }

    private fun parseVerification(obj: JsonValue.Obj): PackageVerification {
        val verification = obj.fields["verification"]?.asObjOrNull
            ?: fail(PackageError.MANIFEST_INVALID, "清单缺少 verification 对象")
        val unknown = verification.fields.keys - setOf("level", "fixtureDigest")
        if (unknown.isNotEmpty()) {
            fail(PackageError.MANIFEST_INVALID, "verification 包含未知字段：" + unknown.sorted().joinToString())
        }
        val levelRaw = requiredString(verification, "level")
        val level = VerificationLevel.entries.firstOrNull { it.name == levelRaw }
            ?: fail(PackageError.MANIFEST_INVALID, "未知验证等级：" + levelRaw)
        val fixtureDigest = requiredString(verification, "fixtureDigest")
        if (!PackageFileDigest.isSha256(fixtureDigest)) {
            fail(PackageError.MANIFEST_INVALID, "fixtureDigest 必须是 64 位小写十六进制")
        }
        return PackageVerification(level = level, fixtureDigest = fixtureDigest)
    }

    private fun requiredString(obj: JsonValue.Obj, key: String): String =
        obj.fields[key]?.stringOrNull() ?: fail(PackageError.MANIFEST_INVALID, "清单字段缺失或类型错误：" + key)

    private fun requiredInt(obj: JsonValue.Obj, key: String): Int {
        val value = obj.fields[key] ?: fail(PackageError.MANIFEST_INVALID, "清单字段缺失：" + key)
        return value.intOrNull() ?: fail(PackageError.MANIFEST_INVALID, "清单字段必须是整数：" + key)
    }

    private fun requiredArray(obj: JsonValue.Obj, key: String): List<JsonValue> =
        obj.fields[key]?.asArrOrNull?.items ?: fail(PackageError.MANIFEST_INVALID, "清单字段必须是数组：" + key)

    private fun requiredStringArray(obj: JsonValue.Obj, key: String): List<String> =
        requiredArray(obj, key).map { item ->
            item.stringOrNull() ?: fail(PackageError.MANIFEST_INVALID, key + " 条目必须是字符串")
        }

    private fun requiredObject(obj: JsonValue.Obj, key: String): JsonValue.Obj =
        obj.fields[key]?.asObjOrNull ?: fail(PackageError.MANIFEST_INVALID, "清单字段必须是对象：" + key)

    private fun fail(error: PackageError, message: String): Nothing = throw PackageException(error, message)
}

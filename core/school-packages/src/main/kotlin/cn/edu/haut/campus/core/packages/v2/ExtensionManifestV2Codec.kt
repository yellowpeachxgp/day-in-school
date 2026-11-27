package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.manifest.PackageManifest
import cn.edu.haut.campus.core.packages.manifest.PackageManifestCodec
import cn.edu.haut.campus.core.packages.manifest.PackageVersion

data class UiSlotDeclaration(
    val slotId: String,
    val cardId: String,
    val title: String,
    val subtitle: String,
    val actionCommand: String
)

data class PluginCommandDeclaration(
    val name: String,
    val description: String,
    val requiredPermission: String?
)

data class ExtensionArtifactDigest(
    val path: String,
    val sha256: String,
    val sizeBytes: Long
)

data class ExtensionSignatureDeclaration(
    val algorithm: String,
    val keyId: String,
    val valueBase64: String
)

data class ExtensionManifestV2(
    val schemaVersion: String, // dis-extension/v2；dis-plugin/v2 仅为兼容别名
    val legacySchemaVersion: String?,
    val pluginId: String,
    val name: String,
    val version: PackageVersion,
    val minHostApi: Int,
    val maxHostApi: Int,
    val permissions: Set<String>,
    val allowedOrigins: Set<String>,
    val uiSlots: List<UiSlotDeclaration>,
    val commands: List<PluginCommandDeclaration>,
    val entryArtifacts: List<ExtensionArtifactDigest>,
    val signature: ExtensionSignatureDeclaration? = null
)

/**
 * snake_case dis-extension/v2 运行时清单解析器。
 *
 * contracts/compatibility/host-api-matrix.yaml 是唯一契约权威；本对象打包的
 * runtime-host-api-matrix.json 只是运行时便利镜像，测试逐项对照契约矩阵，避免第二事实源漂移。
 */
object ExtensionManifestV2Codec {

    const val RUNTIME_SCHEMA_INVALID = "runtime_schema_invalid"
    const val RUNTIME_FORMAT_UNSUPPORTED = "runtime_format_unsupported"
    const val RUNTIME_HOST_API_RANGE_INVALID = "runtime_host_api_range_invalid"
    const val RUNTIME_HOST_API_OUT_OF_RANGE = "runtime_host_api_out_of_range"
    const val RUNTIME_PERMISSION_UNKNOWN = "runtime_permission_unknown"
    const val RUNTIME_ORIGIN_INVALID = "runtime_origin_invalid"
    const val RUNTIME_SLOT_UNKNOWN = "runtime_slot_unknown"
    const val RUNTIME_PATH_UNSAFE = "runtime_path_unsafe"
    const val RUNTIME_DIGEST_INVALID = "runtime_digest_invalid"
    const val RUNTIME_SIGNATURE_INVALID = "runtime_signature_invalid"

    val SUPPORTED_SCHEMAS = setOf("dis-extension/v2", "dis-plugin/v2")
    val SUPPORTED_UI_SLOTS = loadRuntimeUiSlots()
    val ALLOWED_PERMISSIONS = setOf(
        "network.request",
        "account.session",
        "storage.scoped",
        "notification.send",
        "location.coarse",
        "library.booking.read",
        "library.booking.create",
        "parcel.read",
        "parcel.ai.invoke",
        "ui.slot.render",
        "parcel.write.import"
    )

    private val PLUGIN_ID = Regex("^[a-z0-9]+(\\.[a-z0-9-]+)+$")
    private val COMMAND_IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    private val CARD_IDENTIFIER = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$")
    private val HTTPS_ORIGIN = Regex("^https://[a-zA-Z0-9.-]+(:[0-9]+)?$")
    private val SHA256_LOWER = Regex("^[a-f0-9]{64}$")
    private val BASE64_SIGNATURE = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val INTEGER_LITERAL = Regex("^-?[0-9]+$")
    private const val MAX_SIZE_BYTES: Long = 2147483647L

    private val TOP_LEVEL_KEYS = setOf(
        "schema_version",
        "legacy_schema_version",
        "plugin_id",
        "name",
        "version",
        "min_host_api",
        "max_host_api",
        "permissions",
        "allowed_origins",
        "ui_slots",
        "commands",
        "entry_artifacts",
        "signature"
    )
    private val UI_SLOT_KEYS = setOf("slot_id", "card_id", "title", "subtitle", "action_command")
    private val COMMAND_KEYS = setOf("name", "description", "required_permission")
    private val ARTIFACT_KEYS = setOf("path", "sha256", "size_bytes")
    private val SIGNATURE_KEYS = setOf("algorithm", "key_id", "value_base64")

    fun parseOrAdapt(jsonText: String, currentHostApi: Int = 2): ExtensionManifestV2 {
        val root = try {
            Json.parse(jsonText).asObjOrNull
                ?: reject(PackageError.MANIFEST_INVALID, "插件清单顶层必须是 JSON 对象")
        } catch (e: PackageException) {
            throw e
        } catch (e: Exception) {
            reject(PackageError.MANIFEST_INVALID, "插件清单 JSON 解析失败: " + e.message)
        }

        val formatOrSchema = root.fields["schema_version"]?.stringOrNull()
            ?: root.fields["format"]?.stringOrNull()
            ?: reject(
                PackageError.MANIFEST_INVALID,
                "缺少 schema_version 或 format",
                RUNTIME_SCHEMA_INVALID
            )

        if (formatOrSchema == PackageManifestCodec.FORMAT) {
            val v1 = PackageManifestCodec.parse(jsonText)
            return adaptFromV1(v1, currentHostApi)
        }

        if (formatOrSchema !in SUPPORTED_SCHEMAS) {
            reject(
                PackageError.MANIFEST_UNSUPPORTED_FORMAT,
                "不支持的插件清单版本: " + formatOrSchema,
                RUNTIME_FORMAT_UNSUPPORTED
            )
        }

        rejectUnknownFields(root, TOP_LEVEL_KEYS, "插件清单")

        val legacySchemaVersion = parseLegacySchemaVersion(root.fields["legacy_schema_version"])

        val pluginId = requiredString(root, "plugin_id")
        if (!PLUGIN_ID.matches(pluginId) || pluginId.length < 5 || pluginId.length > 120) {
            reject(PackageError.MANIFEST_INVALID, "plugin_id 格式非法: " + pluginId)
        }

        val name = requiredString(root, "name")
        if (isEcmaScriptBlank(name) || name.length > 80) {
            reject(PackageError.MANIFEST_INVALID, "插件名称 name 长度必须为 1..80")
        }

        val versionRaw = requiredString(root, "version")
        val version = PackageVersion.parseOrNull(versionRaw)
            ?: reject(PackageError.MANIFEST_INVALID, "无效的 SemVer 版本号: " + versionRaw)

        val minHostApi = requiredStrictInt(root, "min_host_api")
        val maxHostApi = requiredStrictInt(root, "max_host_api")
        if (minHostApi < 1 || maxHostApi < 1) {
            reject(PackageError.MANIFEST_INVALID, "Host API 版本必须是正整数")
        }
        if (minHostApi > maxHostApi) {
            reject(
                PackageError.MANIFEST_INVALID,
                "插件 Host API 区间倒置 [" + minHostApi + ".." + maxHostApi + "]",
                RUNTIME_HOST_API_RANGE_INVALID
            )
        }
        if (currentHostApi < minHostApi || currentHostApi > maxHostApi) {
            reject(
                PackageError.HOST_API_TOO_OLD,
                "插件要求 Host API [" + minHostApi + ".." + maxHostApi + "]，当前宿主为 " + currentHostApi,
                RUNTIME_HOST_API_OUT_OF_RANGE
            )
        }

        val permissions = requiredArray(root, "permissions").map { item ->
            val permission = item.stringOrNull()
                ?: reject(PackageError.MANIFEST_INVALID, "permissions 条目必须是字符串")
            if (permission !in ALLOWED_PERMISSIONS) {
                reject(
                    PackageError.PERMISSION_REAUTHORIZATION_REQUIRED,
                    "插件申请了未知或越权权限: " + permission,
                    RUNTIME_PERMISSION_UNKNOWN
                )
            }
            permission
        }.toSet()

        val allowedOrigins = requiredArray(root, "allowed_origins").map { item ->
            val origin = item.stringOrNull()
                ?: reject(PackageError.MANIFEST_INVALID, "allowed_origins 条目必须是字符串")
            if (!HTTPS_ORIGIN.matches(origin)) {
                reject(
                    PackageError.MANIFEST_INVALID,
                    "插件网络源必须是无路径 HTTPS origin: " + origin,
                    RUNTIME_ORIGIN_INVALID
                )
            }
            origin
        }.toSet()

        val uiSlots = requiredArray(root, "ui_slots").map { item ->
            val obj = item.asObjOrNull
                ?: reject(PackageError.MANIFEST_INVALID, "ui_slots 条目必须是对象")
            rejectUnknownFields(obj, UI_SLOT_KEYS, "ui_slots 条目")
            val slotId = requiredString(obj, "slot_id")
            if (slotId !in SUPPORTED_UI_SLOTS) {
                reject(
                    PackageError.MANIFEST_INVALID,
                    "不支持的 UI 扩展槽位: " + slotId,
                    RUNTIME_SLOT_UNKNOWN
                )
            }
            val cardId = requiredString(obj, "card_id")
            if (!CARD_IDENTIFIER.matches(cardId)) {
                reject(PackageError.MANIFEST_INVALID, "ui_slots.card_id 格式非法")
            }
            val title = requiredString(obj, "title")
            if (isEcmaScriptBlank(title) || title.length > 60) {
                reject(PackageError.MANIFEST_INVALID, "ui_slots.title 长度必须为 1..60")
            }
            val subtitle = optionalString(obj, "subtitle") ?: ""
            if (subtitle.length > 120) {
                reject(PackageError.MANIFEST_INVALID, "ui_slots.subtitle 长度不能超过 120")
            }
            val actionCommand = requiredString(obj, "action_command")
            if (!COMMAND_IDENTIFIER.matches(actionCommand)) {
                reject(PackageError.MANIFEST_INVALID, "ui_slots.action_command 格式非法")
            }
            UiSlotDeclaration(
                slotId = slotId,
                cardId = cardId,
                title = title,
                subtitle = subtitle,
                actionCommand = actionCommand
            )
        }

        val commands = requiredArray(root, "commands").map { item ->
            val obj = item.asObjOrNull
                ?: reject(PackageError.MANIFEST_INVALID, "commands 条目必须是对象")
            rejectUnknownFields(obj, COMMAND_KEYS, "commands 条目")
            val commandName = requiredString(obj, "name")
            if (!COMMAND_IDENTIFIER.matches(commandName)) {
                reject(PackageError.MANIFEST_INVALID, "command.name 格式非法")
            }
            val description = optionalString(obj, "description") ?: ""
            if (description.length > 200) {
                reject(PackageError.MANIFEST_INVALID, "command.description 长度不能超过 200")
            }
            val requiredPermission = optionalString(obj, "required_permission")
            if (requiredPermission != null && requiredPermission !in ALLOWED_PERMISSIONS) {
                reject(
                    PackageError.PERMISSION_REAUTHORIZATION_REQUIRED,
                    "command 申请了未知或越权权限: " + requiredPermission,
                    RUNTIME_PERMISSION_UNKNOWN
                )
            }
            PluginCommandDeclaration(
                name = commandName,
                description = description,
                requiredPermission = requiredPermission
            )
        }

        val artifacts = requiredArray(root, "entry_artifacts").map { item ->
            val obj = item.asObjOrNull
                ?: reject(PackageError.MANIFEST_INVALID, "entry_artifacts 条目必须是对象")
            rejectUnknownFields(obj, ARTIFACT_KEYS, "entry_artifacts 条目")
            val relPath = requiredString(obj, "path")
            if (isEcmaScriptBlank(relPath) || relPath.length > 240) {
                reject(PackageError.MANIFEST_INVALID, "插件文件路径长度必须为 1..240")
            }
            assertSafeRelativePathWithRuntimeCode(relPath)
            val sha256 = requiredString(obj, "sha256")
            if (!SHA256_LOWER.matches(sha256)) {
                reject(
                    PackageError.FILE_HASH_MISMATCH,
                    "无效的 64 位小写 SHA-256 摘要",
                    RUNTIME_DIGEST_INVALID
                )
            }
            val sizeBytes = requiredStrictLong(obj, "size_bytes")
            if (sizeBytes < 0 || sizeBytes > MAX_SIZE_BYTES) {
                reject(PackageError.MANIFEST_INVALID, "size_bytes 必须是 0.." + MAX_SIZE_BYTES + " 的整数")
            }
            ExtensionArtifactDigest(
                path = relPath,
                sha256 = sha256,
                sizeBytes = sizeBytes
            )
        }

        val signature = parseSignature(root.fields["signature"])

        return ExtensionManifestV2(
            schemaVersion = formatOrSchema,
            legacySchemaVersion = legacySchemaVersion,
            pluginId = pluginId,
            name = name,
            version = version,
            minHostApi = minHostApi,
            maxHostApi = maxHostApi,
            permissions = permissions,
            allowedOrigins = allowedOrigins,
            uiSlots = uiSlots,
            commands = commands,
            entryArtifacts = artifacts,
            signature = signature
        )
    }

    fun adaptFromV1(v1: PackageManifest, currentHostApi: Int = 2): ExtensionManifestV2 {
        if (currentHostApi < v1.minHostApi) {
            throw PackageException(
                PackageError.HOST_API_TOO_OLD,
                "旧版插件要求更高的 Host API",
                RUNTIME_HOST_API_OUT_OF_RANGE
            )
        }
        val origins = v1.permissions.flatMap { it.origins }.toSet()
        val perms = buildSet {
            add("ui.slot.render")
            add("storage.scoped")
            if (origins.isNotEmpty()) add("network.request")
        }
        return ExtensionManifestV2(
            schemaVersion = "dis-extension/v2",
            legacySchemaVersion = PackageManifestCodec.FORMAT,
            pluginId = v1.id,
            name = v1.publisher + " (" + v1.id + ")",
            version = v1.version,
            minHostApi = v1.minHostApi,
            maxHostApi = maxOf(currentHostApi, v1.minHostApi),
            permissions = perms,
            allowedOrigins = origins,
            uiSlots = listOf(
                UiSlotDeclaration(
                    slotId = "campus.services",
                    cardId = v1.id + ".legacy.card",
                    title = v1.id,
                    subtitle = "兼容自 dis-campus-plugin/v1",
                    actionCommand = "open_legacy"
                )
            ),
            commands = listOf(
                PluginCommandDeclaration(
                    name = "open_legacy",
                    description = "Open migrated v1 school package",
                    requiredPermission = "ui.slot.render"
                )
            ),
            entryArtifacts = v1.files.map { (path, sha) ->
                assertSafeRelativePathWithRuntimeCode(path)
                ExtensionArtifactDigest(path = path, sha256 = sha, sizeBytes = 0L)
            },
            signature = null
        )
    }

    fun assertSafeRelativePath(path: String) {
        if (isEcmaScriptBlank(path) ||
            path.startsWith("/") ||
            path.startsWith("\\") ||
            path.contains("..") ||
            path.contains('\u0000') ||
            Regex("^[a-zA-Z]:").containsMatchIn(path)
        ) {
            throw PackageException(
                PackageError.UNSAFE_PATH,
                "拒绝不安全的插件文件路径 (Zip-Slip 防护): " + path,
                RUNTIME_PATH_UNSAFE
            )
        }
    }

    private fun assertSafeRelativePathWithRuntimeCode(path: String) {
        try {
            assertSafeRelativePath(path)
        } catch (e: PackageException) {
            reject(
                e.error,
                e.message ?: "拒绝不安全的插件文件路径",
                RUNTIME_PATH_UNSAFE
            )
        }
    }

    private fun parseLegacySchemaVersion(value: JsonValue?): String? {
        if (value == null) return null
        val legacy = value.stringOrNull()
            ?: reject(PackageError.MANIFEST_INVALID, "legacy_schema_version 必须是字符串")
        if (legacy != PackageManifestCodec.FORMAT) {
            reject(
                PackageError.MANIFEST_INVALID,
                "legacy_schema_version 只支持 " + PackageManifestCodec.FORMAT + ": " + legacy
            )
        }
        return legacy
    }

    private fun parseSignature(value: JsonValue?): ExtensionSignatureDeclaration? {
        if (value == null) return null
        val obj = value.asObjOrNull
            ?: reject(PackageError.SIGNATURE_INVALID, "signature 必须是对象", RUNTIME_SIGNATURE_INVALID)
        rejectUnknownFields(obj, SIGNATURE_KEYS, "signature", RUNTIME_SIGNATURE_INVALID)
        val algorithm = requiredString(obj, "algorithm", RUNTIME_SIGNATURE_INVALID, PackageError.SIGNATURE_INVALID)
        if (algorithm != "Ed25519") {
            reject(PackageError.SIGNATURE_INVALID, "signature.algorithm 必须是 Ed25519", RUNTIME_SIGNATURE_INVALID)
        }
        val keyId = requiredString(obj, "key_id", RUNTIME_SIGNATURE_INVALID, PackageError.SIGNATURE_INVALID)
        if (keyId.length < 3 || keyId.length > 64) {
            reject(PackageError.SIGNATURE_INVALID, "signature.key_id 长度必须为 3..64", RUNTIME_SIGNATURE_INVALID)
        }
        val valueBase64 = requiredString(obj, "value_base64", RUNTIME_SIGNATURE_INVALID, PackageError.SIGNATURE_INVALID)
        if (valueBase64.length < 64 || valueBase64.length > 128 || !BASE64_SIGNATURE.matches(valueBase64)) {
            reject(PackageError.SIGNATURE_INVALID, "signature.value_base64 不是合法 Base64 签名", RUNTIME_SIGNATURE_INVALID)
        }
        return ExtensionSignatureDeclaration(
            algorithm = algorithm,
            keyId = keyId,
            valueBase64 = valueBase64
        )
    }

    private fun requiredString(
        obj: JsonValue.Obj,
        key: String,
        runtimeCode: String = RUNTIME_SCHEMA_INVALID,
        packageError: PackageError = PackageError.MANIFEST_INVALID
    ): String {
        val raw = obj.fields[key]
            ?: reject(packageError, "缺少必填字段 " + key, runtimeCode)
        return raw.stringOrNull()
            ?: reject(packageError, "字段 " + key + " 必须是字符串", runtimeCode)
    }

    private fun optionalString(obj: JsonValue.Obj, key: String): String? {
        val raw = obj.fields[key] ?: return null
        return raw.stringOrNull()
            ?: reject(PackageError.MANIFEST_INVALID, "字段 " + key + " 必须是字符串")
    }

    private fun requiredArray(obj: JsonValue.Obj, key: String): List<JsonValue> {
        val raw = obj.fields[key]
            ?: reject(PackageError.MANIFEST_INVALID, "缺少必填数组字段 " + key)
        return raw.asArrOrNull?.items
            ?: reject(PackageError.MANIFEST_INVALID, "字段 " + key + " 必须是数组")
    }

    private fun requiredStrictInt(obj: JsonValue.Obj, key: String): Int {
        val raw = obj.fields[key]
            ?: reject(PackageError.MANIFEST_INVALID, "缺少必填整数字段 " + key)
        val literal = strictIntegerLiteral(raw, key)
        return literal.toIntOrNull()
            ?: reject(PackageError.MANIFEST_INVALID, "字段 " + key + " 超出整数范围")
    }

    private fun requiredStrictLong(obj: JsonValue.Obj, key: String): Long {
        val raw = obj.fields[key]
            ?: reject(PackageError.MANIFEST_INVALID, "缺少必填整数字段 " + key)
        val literal = strictIntegerLiteral(raw, key)
        return literal.toLongOrNull()
            ?: reject(PackageError.MANIFEST_INVALID, "字段 " + key + " 超出整数范围")
    }

    private fun strictIntegerLiteral(raw: JsonValue?, key: String): String {
        val literal = (raw as? JsonValue.Num)?.literal
        if (literal == null || !INTEGER_LITERAL.matches(literal)) {
            reject(PackageError.MANIFEST_INVALID, "字段 " + key + " 必须是整数")
        }
        return literal
    }

    /**
     * ECMAScript \s 的手写等价集。Kotlin/Java isBlank 会把 U+001C..U+001F
     * 当作空白且不包含 U+FEFF；运行时清单跨端 parity 必须以 ECMAScript 为准。
     */
    private fun isEcmaScriptWhitespace(ch: Char): Boolean {
        val code = ch.code
        return code == 0x0009 || code == 0x000A || code == 0x000B ||
            code == 0x000C || code == 0x000D || code == 0x0020 ||
            code == 0x00A0 || code == 0x1680 ||
            code in 0x2000..0x200A ||
            code == 0x2028 || code == 0x2029 || code == 0x202F ||
            code == 0x205F || code == 0x3000 || code == 0xFEFF
    }

    private fun isEcmaScriptBlank(value: String): Boolean = value.all(::isEcmaScriptWhitespace)

    private fun rejectUnknownFields(
        obj: JsonValue.Obj,
        allowed: Set<String>,
        context: String,
        runtimeCode: String = RUNTIME_SCHEMA_INVALID
    ) {
        val unknown = obj.fields.keys.firstOrNull { it !in allowed } ?: return
        reject(
            PackageError.MANIFEST_INVALID,
            context + " 包含未声明字段: " + unknown,
            runtimeCode
        )
    }

    private fun reject(
        error: PackageError,
        message: String,
        runtimeCode: String = RUNTIME_SCHEMA_INVALID
    ): Nothing = throw PackageException(error, message, runtimeCode)

    private fun loadRuntimeUiSlots(): Set<String> {
        val stream = ExtensionManifestV2Codec::class.java.getResourceAsStream("/runtime-host-api-matrix.json")
            ?: error("runtime-host-api-matrix.json resource is missing")
        val text = stream.use { String(it.readBytes(), Charsets.UTF_8) }
        val root = Json.parse(text).asObjOrNull ?: error("runtime host matrix must be an object")
        val versions = root.fields["host_api_versions"]?.asArrOrNull?.items.orEmpty()
        val host = versions.mapNotNull { it.asObjOrNull }.firstOrNull {
            it.fields["host_api"]?.intOrNull() == 2
        } ?: error("runtime host matrix is missing host_api 2")
        val slots = host.fields["supported_ui_slots"]?.asArrOrNull?.items
            ?.mapNotNull { it.stringOrNull() }
            .orEmpty()
        require(slots.isNotEmpty()) { "runtime host matrix host_api 2 has no UI slots" }
        return slots.toSet()
    }
}

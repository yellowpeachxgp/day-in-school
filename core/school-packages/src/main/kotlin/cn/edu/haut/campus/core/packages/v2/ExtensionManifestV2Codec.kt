package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.bundle.PackageFileDigest
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

data class ExtensionManifestV2(
    val schemaVersion: String, // dis-extension/v2 | dis-plugin/v2
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
    val entryArtifacts: List<ExtensionArtifactDigest>
)

object ExtensionManifestV2Codec {

    val SUPPORTED_SCHEMAS = setOf("dis-extension/v2", "dis-plugin/v2")
    val SUPPORTED_UI_SLOTS = setOf(
        "home.cards",
        "campus.services",
        "library.actions",
        "parcel.cards",
        "parcel.providers",
        "settings.entries"
    )
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

    fun parseOrAdapt(jsonText: String, currentHostApi: Int = 2): ExtensionManifestV2 {
        val root = try {
            Json.parse(jsonText).asObjOrNull
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "插件清单顶层必须是 JSON 对象")
        } catch (e: PackageException) {
            throw e
        } catch (e: Exception) {
            throw PackageException(PackageError.MANIFEST_INVALID, "插件清单 JSON 解析失败: ${e.message}")
        }

        val formatOrSchema = root.fields["schema_version"]?.stringOrNull()
            ?: root.fields["format"]?.stringOrNull()
            ?: throw PackageException(PackageError.MANIFEST_UNSUPPORTED_FORMAT, "缺少 schema_version 或 format")

        if (formatOrSchema == PackageManifestCodec.FORMAT) {
            val v1 = PackageManifestCodec.parse(jsonText)
            return adaptFromV1(v1, currentHostApi)
        }

        if (formatOrSchema !in SUPPORTED_SCHEMAS) {
            throw PackageException(
                PackageError.MANIFEST_UNSUPPORTED_FORMAT,
                "不支持的插件清单版本: $formatOrSchema"
            )
        }

        val pluginId = root.fields["plugin_id"]?.stringOrNull()
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "缺少 plugin_id")
        if (!PackageManifest.PACKAGE_ID.matches(pluginId)) {
            throw PackageException(PackageError.MANIFEST_INVALID, "plugin_id 格式非法: $pluginId")
        }

        val name = root.fields["name"]?.stringOrNull()?.takeIf { it.isNotBlank() }
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "缺少插件名称 name")

        val versionRaw = root.fields["version"]?.stringOrNull()
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "缺少 version")
        val version = PackageVersion.parseOrNull(versionRaw)
            ?: throw PackageException(PackageError.MANIFEST_INVALID, "无效的 SemVer 版本号: $versionRaw")

        val minHostApi = root.fields["min_host_api"]?.intOrNull() ?: 2
        val maxHostApi = root.fields["max_host_api"]?.intOrNull() ?: 2
        if (currentHostApi < minHostApi || currentHostApi > maxHostApi) {
            throw PackageException(
                PackageError.HOST_API_TOO_OLD,
                "插件要求 Host API [$minHostApi..$maxHostApi]，当前宿主为 $currentHostApi"
            )
        }

        val permissions = (root.fields["permissions"]?.asArrOrNull?.items ?: emptyList()).map { item ->
            val p = item.stringOrNull()
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "permissions 条目必须是字符串")
            if (p !in ALLOWED_PERMISSIONS) {
                throw PackageException(PackageError.PERMISSION_REAUTHORIZATION_REQUIRED, "插件申请了未知或越权权限: $p")
            }
            p
        }.toSet()

        val allowedOrigins = (root.fields["allowed_origins"]?.asArrOrNull?.items ?: emptyList()).map { item ->
            val origin = item.stringOrNull()
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "allowed_origins 条目必须是字符串")
            if (!origin.startsWith("https://")) {
                throw PackageException(PackageError.MANIFEST_INVALID, "插件网络源必须使用 HTTPS: $origin")
            }
            origin
        }.toSet()

        val uiSlots = (root.fields["ui_slots"]?.asArrOrNull?.items ?: emptyList()).map { item ->
            val obj = item.asObjOrNull
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "ui_slots 条目必须是对象")
            val slotId = obj.fields["slot_id"]?.stringOrNull()
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "ui_slots 缺少 slot_id")
            if (slotId !in SUPPORTED_UI_SLOTS) {
                throw PackageException(PackageError.MANIFEST_INVALID, "不支持的 UI 扩展槽位: $slotId")
            }
            UiSlotDeclaration(
                slotId = slotId,
                cardId = obj.fields["card_id"]?.stringOrNull() ?: "$pluginId.card",
                title = obj.fields["title"]?.stringOrNull() ?: name,
                subtitle = obj.fields["subtitle"]?.stringOrNull() ?: "",
                actionCommand = obj.fields["action_command"]?.stringOrNull() ?: "open"
            )
        }

        val commands = (root.fields["commands"]?.asArrOrNull?.items ?: emptyList()).map { item ->
            val obj = item.asObjOrNull
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "commands 条目必须是对象")
            PluginCommandDeclaration(
                name = obj.fields["name"]?.stringOrNull()
                    ?: throw PackageException(PackageError.MANIFEST_INVALID, "command 缺少 name"),
                description = obj.fields["description"]?.stringOrNull() ?: "",
                requiredPermission = obj.fields["required_permission"]?.stringOrNull()
            )
        }

        val artifacts = (root.fields["entry_artifacts"]?.asArrOrNull?.items ?: emptyList()).map { item ->
            val obj = item.asObjOrNull
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "entry_artifacts 条目必须是对象")
            val relPath = obj.fields["path"]?.stringOrNull()
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "entry_artifacts 缺少 path")
            assertSafeRelativePath(relPath)
            val sha256 = obj.fields["sha256"]?.stringOrNull()
                ?: throw PackageException(PackageError.MANIFEST_INVALID, "entry_artifacts 缺少 sha256")
            if (!PackageFileDigest.isSha256(sha256.lowercase())) {
                throw PackageException(PackageError.FILE_HASH_MISMATCH, "无效的 SHA-256 摘要: $sha256")
            }
            ExtensionArtifactDigest(
                path = relPath,
                sha256 = sha256.lowercase(),
                sizeBytes = (obj.fields["size_bytes"]?.intOrNull() ?: 0).toLong()
            )
        }

        return ExtensionManifestV2(
            schemaVersion = formatOrSchema,
            legacySchemaVersion = null,
            pluginId = pluginId,
            name = name,
            version = version,
            minHostApi = minHostApi,
            maxHostApi = maxHostApi,
            permissions = permissions,
            allowedOrigins = allowedOrigins,
            uiSlots = uiSlots,
            commands = commands,
            entryArtifacts = artifacts
        )
    }

    fun adaptFromV1(v1: PackageManifest, currentHostApi: Int = 2): ExtensionManifestV2 {
        if (currentHostApi < v1.minHostApi) {
            throw PackageException(PackageError.HOST_API_TOO_OLD, "旧版插件要求更高的 Host API")
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
            name = "${v1.publisher} (${v1.id})",
            version = v1.version,
            minHostApi = v1.minHostApi,
            maxHostApi = maxOf(currentHostApi, v1.minHostApi),
            permissions = perms,
            allowedOrigins = origins,
            uiSlots = listOf(
                UiSlotDeclaration(
                    slotId = "campus.services",
                    cardId = "${v1.id}.legacy.card",
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
                assertSafeRelativePath(path)
                ExtensionArtifactDigest(path = path, sha256 = sha, sizeBytes = 0L)
            }
        )
    }

    fun assertSafeRelativePath(path: String) {
        if (path.isBlank() ||
            path.startsWith("/") ||
            path.startsWith("\\") ||
            path.contains("..") ||
            path.contains('\u0000') ||
            Regex("^[a-zA-Z]:").containsMatchIn(path)
        ) {
            throw PackageException(
                PackageError.UNSAFE_PATH,
                "拒绝不安全的插件文件路径 (Zip-Slip 防护): $path"
            )
        }
    }
}

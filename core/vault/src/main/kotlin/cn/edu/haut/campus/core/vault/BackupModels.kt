package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant

/** dis-backup/v1 的可移植数据分类。设备本地授权、运行时调度态和派生索引刻意不属于任何分类。 */
enum class BackupCategory {
    ACADEMIC,
    CAMPUS_REFERENCE,
    REMINDER_PREFERENCES,
    UTILITY_FOCUS,
    LIBRARY_AUTOMATION,
    PARCELS,
    PERSONAL_EVENTS,
    BLOBS
}

/** 备份选择。v1 默认全量选择所有可移植分类；空选择没有意义，直接 fail-closed。 */
data class BackupSelection(
    val categories: Set<BackupCategory> = BackupCategory.entries.toSet()
) {
    init {
        require(categories.isNotEmpty()) { "备份选择不能为空" }
    }

    companion object {
        val ALL: BackupSelection = BackupSelection()
    }
}

enum class RestoreMode {
    /** 只合入备份内行；冲突行必须由用户逐行选择，缺失行不删除。 */
    MERGE,

    /** 所选分类覆盖为备份内容；同一事务内先清后写，失败整体回滚。 */
    REPLACE
}

enum class BackupConflictResolution {
    USE_BACKUP,
    KEEP_EXISTING
}

/** 一行可移植数据的稳定冲突键；primaryKey 使用长度前缀编码，支持复合主键且不依赖设备序号。 */
data class BackupRowKey(
    val table: String,
    val primaryKey: String
) {
    init {
        require(table.matches(Regex("[a-z][a-z0-9_]*"))) { "备份表名非法" }
        require(primaryKey.isNotBlank() && primaryKey.length <= 1024) { "备份行键为空或过长" }
    }
}

data class BackupRowConflict(
    val key: BackupRowKey,
    val existingSha256: String,
    val incomingSha256: String
) {
    init {
        require(existingSha256.matches(Regex("[0-9a-f]{64}"))) { "existingSha256 非法" }
        require(incomingSha256.matches(Regex("[0-9a-f]{64}"))) { "incomingSha256 非法" }
        require(existingSha256 != incomingSha256) { "冲突行摘要不能相同" }
    }
}

data class BackupTableManifest(
    val table: String,
    val rowCount: Int,
    val sha256: String
) {
    init {
        require(rowCount >= 0) { "备份表行数不能为负" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "备份表 sha256 非法" }
    }
}

/** manifest.json 的强类型视图；格式版本、schema 版本、Profile 指纹和摘要均在解密后再次校验。 */
data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val createdAt: Instant,
    val appVersion: String,
    val schemaVersion: Int,
    val profileFingerprint: String,
    val entryCount: Int,
    val payloadSha256: String,
    val tables: List<BackupTableManifest>
) {
    init {
        require(format == BackupFormatConstants.FORMAT) { "备份格式不是 dis-backup/v1" }
        require(formatVersion >= 1) { "备份格式版本非法" }
        require(appVersion.isNotBlank() && appVersion.length <= 128) { "appVersion 非法" }
        require(schemaVersion > 0) { "schemaVersion 非法" }
        require(profileFingerprint.matches(Regex("[0-9a-f]{64}"))) { "Profile 指纹非法" }
        require(entryCount >= 0) { "条目计数不能为负" }
        require(payloadSha256.matches(Regex("[0-9a-f]{64}"))) { "payload sha256 非法" }
        require(tables.map { it.table }.distinct().size == tables.size) { "manifest 表名重复" }
    }
}

data class BackupTablePreview(
    val table: String,
    val incomingRows: Int,
    val unchangedRows: Int,
    val newRows: Int,
    val conflictRows: Int,
    val removableRowsInReplace: Int
)

/** inspect 的只读结果。调用方必须把同一对象传给 restore；restore 会在事务内复核当前库未在预览后变化。 */
class RestorePreview internal constructor(
    val manifest: BackupManifest,
    val scope: SchoolAccountScope,
    val tablePreviews: List<BackupTablePreview>,
    val conflicts: List<BackupRowConflict>,
    val insertCount: Int,
    val unchangedCount: Int,
    val removableCount: Int,
    internal val preparedArchive: PreparedBackupArchive,
    internal val currentTableFingerprints: Map<String, String>
) {
    val requiresConflictResolution: Boolean get() = conflicts.isNotEmpty()

    /** 恢复完成或放弃后主动清零 blob 明文引用；文本行仍按普通 Kotlin 对象等待 GC。 */
    fun discardSensitiveMaterial() {
        preparedArchive.blobs.values.forEach { it.fill(0) }
    }
}

data class RestoreReport(
    val mode: RestoreMode,
    val insertedRows: Int,
    val updatedRows: Int,
    val unchangedRows: Int,
    val keptExistingRows: Int,
    val removedRows: Int,
    val journaledEntities: Int,
    val searchIndexedDocuments: Int
)

/** MASTER_PLAN §6.2 的备份服务边界；实现必须保证错误路径不发生任何部分写入。 */
interface BackupService {
    val scope: SchoolAccountScope

    suspend fun export(
        scope: SchoolAccountScope,
        selection: BackupSelection,
        passphrase: CharArray,
        out: OutputStream
    ): BackupManifest

    suspend fun inspect(input: InputStream, passphrase: CharArray): RestorePreview

    suspend fun restore(
        preview: RestorePreview,
        mode: RestoreMode,
        resolutions: Map<BackupRowKey, BackupConflictResolution>
    ): RestoreReport
}

object BackupFormatConstants {
    const val FORMAT = "dis-backup/v1"
    const val DATA_FORMAT = "dis-backup-data/v1"
    const val ENVELOPE_FORMAT = "dis-backup-envelope/v1"
    const val FORMAT_VERSION = 1
    const val MANIFEST_ENTRY = "manifest.json"
    const val DATA_ENTRY = "data.json"
    const val BLOB_ENTRY_PREFIX = "blobs/"
    const val MIN_PBKDF2_ITERATIONS = 100_000
    const val DEFAULT_PBKDF2_ITERATIONS = 120_000
    const val MAX_PBKDF2_ITERATIONS = 500_000
    const val MAX_HEADER_BYTES = 64 * 1024
    const val MAX_ARCHIVE_BYTES = 128L * 1024L * 1024L
    const val MAX_DATA_JSON_BYTES = 64 * 1024 * 1024
}

sealed class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 容器、ZIP、JSON、摘要、行类型或 blob 完整性损坏。 */
class BackupCorruptedException(detail: String, cause: Throwable? = null) :
    BackupException("备份文件损坏：" + detail, cause)

/** key-check 不匹配；只能在派生密钥错误时确定，不能把口令或密文写进错误。 */
class BackupWrongPassphraseException :
    BackupException("备份口令不正确")

class BackupVersionTooNewException(
    val component: String,
    val foundVersion: Int,
    val supportedVersion: Int
) : BackupException(
    "备份版本过高：" + component + "=" + foundVersion + "，当前支持 " + supportedVersion
)

class BackupIdentityMismatchException :
    BackupException("备份身份与当前 Profile 不匹配，已停止恢复")

class BackupRestoreConflictException(val missing: List<BackupRowKey>) :
    BackupException("MERGE 恢复缺少 " + missing.size + " 条冲突行选择")

class BackupRestoreStaleException :
    BackupException("预览后本地数据已变化，请重新检查备份后再恢复")

class BackupValidationException(detail: String) :
    BackupException("备份内容校验失败：" + detail)

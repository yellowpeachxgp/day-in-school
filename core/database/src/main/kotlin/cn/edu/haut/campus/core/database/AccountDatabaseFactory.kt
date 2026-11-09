package cn.edu.haut.campus.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal const val LEGACY_DATABASE_NAME = "haut_campus_assistant.db"
internal const val LEGACY_PREFERENCES_NAME = "haut_campus_prefs"

internal data class AccountStoragePartition(
    val databaseName: String,
    val preferencesName: String
)

/** 文件名只含不可逆摘要，避免把学号或用户命名带进可备份、可枚举的路径。 */
internal fun isolatedAccountStoragePartition(profileId: String): AccountStoragePartition {
    require(profileId.isNotBlank()) { "账号 Profile 标识不能为空" }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(profileId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return AccountStoragePartition(
        databaseName = "account_$digest.db",
        preferencesName = "account_$digest"
    )
}

internal fun requireIsolatedStoragePartition(profile: AccountProfileEntity) {
    val expected = if (profile.isLegacy) {
        AccountStoragePartition(LEGACY_DATABASE_NAME, LEGACY_PREFERENCES_NAME)
    } else {
        isolatedAccountStoragePartition(profile.id)
    }
    check(profile.databaseName == expected.databaseName && profile.preferencesName == expected.preferencesName) {
        "账号存储分区与 Profile 不匹配，已停止打开数据"
    }
}

/** 一个 Profile 永久对应一个 Room/DataStore 实例，旧资料不迁移文件、不执行破坏性降级。 */
internal class AccountDatabaseFactory(private val context: Context) {
    private val repositories = ConcurrentHashMap<String, CampusRepository>()

    @Synchronized
    fun open(profile: AccountProfileEntity): CampusRepository {
        requireIsolatedStoragePartition(profile)
        return repositories.getOrPut(profile.id) {
            val database = Room.databaseBuilder(context, HautDatabase::class.java, profile.databaseName)
                .addMigrations(
                    CampusRepository.MIGRATION_1_2,
                    CampusRepository.MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8
                )
                .build()
            val preferences = PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(profile.preferencesName) }
            )
            CampusRepository(context, database, preferences, profile)
        }
    }

    data class LegacySnapshot(val oldVersion: Int, val tableCounts: Map<String, Long>) {
        val serializedTableCounts: String
            get() = tableCounts.entries.joinToString("\n") { "${it.key}=${it.value}" }
    }

    data class AdoptionEvidence(
        val oldVersion: Int,
        val newVersion: Int,
        val serializedTableCounts: String
    )

    fun currentSchemaVersion(profile: AccountProfileEntity): Int {
        check(profile.isLegacy) { "只有旧版 Profile 可以执行 adoption" }
        requireIsolatedStoragePartition(profile)
        val path = context.getDatabasePath(profile.databaseName)
        if (!path.exists()) return 0
        return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db -> db.version }
    }

    fun inspectLegacy(profile: AccountProfileEntity): LegacySnapshot {
        check(profile.isLegacy) { "只有旧版 Profile 可以执行 adoption" }
        requireIsolatedStoragePartition(profile)
        val path = context.getDatabasePath(profile.databaseName)
        var oldVersion = 0
        val counts = linkedMapOf<String, Long>()
        if (path.exists()) {
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                oldVersion = db.version
                check(oldVersion > 0) { "旧数据库缺少可验证的 schema 版本" }
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' AND name != 'android_metadata' ORDER BY name", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val table = cursor.getString(0)
                        require(table.all { it.isLetterOrDigit() || it == '_' }) { "旧数据库表结构无效" }
                        db.rawQuery("SELECT COUNT(*) FROM `$table`", null).use { rows ->
                            check(rows.moveToFirst())
                            counts[table] = rows.getLong(0)
                        }
                    }
                }
            }
        }
        return LegacySnapshot(oldVersion, counts)
    }

    fun openAndVerifyLegacy(
        profile: AccountProfileEntity,
        snapshot: LegacySnapshot
    ): AdoptionEvidence {
        check(profile.isLegacy) { "只有旧版 Profile 可以执行 adoption" }
        val path = context.getDatabasePath(profile.databaseName)
        if (snapshot.oldVersion == 0) {
            check(!path.exists()) { "旧库在 adoption 期间出现，已停止覆盖" }
        } else {
            check(path.exists()) { "旧库在 adoption 期间消失，已停止打开数据" }
        }
        val repository = open(profile)
        val db = repository.databaseForAdoption().openHelper.writableDatabase
        check(db.version >= snapshot.oldVersion) { "旧库发生非预期降级，已停止 adoption" }
        snapshot.tableCounts.forEach { (table, expected) ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) == expected) { "旧数据采用验证失败，已保留原库" }
            }
        }
        return AdoptionEvidence(
            oldVersion = snapshot.oldVersion,
            newVersion = db.version,
            serializedTableCounts = snapshot.serializedTableCounts
        )
    }
}

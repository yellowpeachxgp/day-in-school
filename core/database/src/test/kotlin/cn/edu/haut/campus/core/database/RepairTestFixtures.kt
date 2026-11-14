package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.DataStore
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * 课程/考试修复回归测试共用的 in-memory fixture：
 * 独立 Room 实例 + 临时 DataStore + 合成账号 Profile，绝不访问真实学校或网络。
 */
internal data class RepairTestFixture(
    val database: HautDatabase,
    val repository: CampusRepository,
    val dataStore: DataStore<Preferences>,
    val profile: AccountProfileEntity,
    private val storeFile: File
) {
    val dao: CampusDao get() = database.campusDao()

    fun close() {
        database.close()
        runCatching { storeFile.delete() }
    }

    companion object {
        private var counter = 0

        fun create(context: Context, profileSeed: String = "prof_repair_test"): RepairTestFixture {
            val n = ++counter
            val database = Room.inMemoryDatabaseBuilder(context, HautDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            val storeFile = File.createTempFile("haut_repair_test_${n}_", ".preferences_pb")
            storeFile.deleteOnExit()
            val dataStore = PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                produceFile = { storeFile }
            )
            val storage = isolatedAccountStoragePartition(profileSeed)
            val profile = AccountProfileEntity(
                id = profileSeed,
                accountId = "acct_${profileSeed}",
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "合成测试账号",
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            )
            val repository = CampusRepository(
                appContext = context,
                database = database,
                preferences = dataStore,
                accountProfile = profile
            )
            repository.activateSession(1L)
            return RepairTestFixture(database, repository, dataStore, profile, storeFile)
        }
    }
}

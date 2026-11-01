package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class GlobalProfileRegistryTest {

    @Test
    fun `registry 初始记录在同一 Room 事务内提交或全部回滚`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            GlobalProfileRegistry::class.java
        ).allowMainThreadQueries().build()
        val dao = database.profiles()
        val profile = nonLegacyProfile("profile-transaction")
        try {
            val failure = runCatching {
                database.withTransaction {
                    dao.insertProfile(profile)
                    dao.saveActive(ActiveProfileEntity(profileId = profile.id, generation = 1))
                    dao.saveJournal(journal(profile))
                    error("合成写入中断")
                }
            }

            assertTrue(failure.isFailure)
            assertNull(dao.profile(profile.id))
            assertNull(dao.active())
            assertNull(dao.journal(LEGACY_ADOPTION_JOURNAL_ID))

            database.withTransaction {
                dao.insertProfile(profile)
                dao.saveActive(ActiveProfileEntity(profileId = profile.id, generation = 1))
                dao.saveJournal(journal(profile))
            }

            assertEquals(profile, dao.profile(profile.id))
            assertEquals(profile.id, dao.active()?.profileId)
            assertEquals(ADOPTION_SOURCE_CAPTURED, dao.journal(LEGACY_ADOPTION_JOURNAL_ID)?.status)
        } finally {
            database.close()
        }
    }

    @Test
    fun `每个 profile 的 Room 与 DataStore 名称由稳定摘要隔离`() {
        val first = isolatedAccountStoragePartition("student-20260001")
        val repeated = isolatedAccountStoragePartition("student-20260001")
        val second = isolatedAccountStoragePartition("student-20260002")

        assertEquals(first, repeated)
        assertFalse(first.databaseName == second.databaseName)
        assertFalse(first.preferencesName == second.preferencesName)
        assertFalse(first.databaseName.contains("student"))
        assertFalse(first.preferencesName.contains("student"))
        val context = RuntimeEnvironment.getApplication()
        assertFalse(
            context.preferencesDataStoreFile(first.preferencesName).absolutePath ==
                context.preferencesDataStoreFile(second.preferencesName).absolutePath
        )
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest("student-20260001".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            first.databaseName.removePrefix("account_").removeSuffix(".db")
        )

        val profile = nonLegacyProfile("student-20260001").copy(preferencesName = first.databaseName)
        assertThrows(IllegalStateException::class.java) {
            requireIsolatedStoragePartition(profile)
        }
    }

    @Test
    fun `adoption 计数只接受唯一的非负表记录并保留源迁移信息`() {
        assertEquals(
            mapOf("courses" to 3L, "grades" to 0L),
            decodeAdoptionTableCounts("courses=3\ngrades=0")
        )
        assertTrue(runCatching { decodeAdoptionTableCounts("courses=3\ncourses=4") }.isFailure)
        assertTrue(runCatching { decodeAdoptionTableCounts("courses=-1") }.isFailure)
        assertTrue(runCatching { decodeAdoptionTableCounts("courses=1\n") }.isFailure)
    }

    private fun nonLegacyProfile(id: String): AccountProfileEntity {
        val storage = isolatedAccountStoragePartition(id)
        return AccountProfileEntity(
            id = id,
            accountId = "account-$id",
            schoolId = "example-university",
            schoolName = "示例大学",
            province = "示例省",
            city = "示例市",
            campusId = null,
            campusName = null,
            timeZoneId = "Asia/Shanghai",
            label = "测试身份",
            isLegacy = false,
            databaseName = storage.databaseName,
            preferencesName = storage.preferencesName,
            createdAtEpochMs = 1L
        )
    }

    private fun journal(profile: AccountProfileEntity) = ProfileAdoptionJournalEntity(
        id = LEGACY_ADOPTION_JOURNAL_ID,
        profileId = profile.id,
        sourceDatabase = LEGACY_DATABASE_NAME,
        sourceSchemaVersion = 4,
        adoptedSchemaVersion = 0,
        originalTableCounts = "courses=3",
        status = ADOPTION_SOURCE_CAPTURED,
        recordedAtEpochMs = 1L
    )
}

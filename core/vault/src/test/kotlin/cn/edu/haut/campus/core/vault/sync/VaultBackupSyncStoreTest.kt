package cn.edu.haut.campus.core.vault.sync

import androidx.room.Room
import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.PersonalEventEntity
import cn.edu.haut.campus.core.database.ParcelItemEntity
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.vault.BackupCell
import cn.edu.haut.campus.core.vault.BackupRow
import cn.edu.haut.campus.core.vault.BackupTableCatalog
import cn.edu.haut.campus.core.vault.CourseEntitySnapshotStore
import cn.edu.haut.campus.core.vault.ExamScheduleSnapshotStore
import cn.edu.haut.campus.core.vault.GradeAttemptSnapshotStore
import cn.edu.haut.campus.core.vault.PersonalEventSnapshotStore
import cn.edu.haut.campus.core.vault.RoomEventOutbox
import cn.edu.haut.campus.core.vault.RoomSearchIndex
import cn.edu.haut.campus.core.vault.RoomVaultJournal
import cn.edu.haut.campus.core.vault.VaultBackupService
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Room 级适配器测试：确认同步写真的落到 dis-backup schema、scope 本地化与 Actor.SYNC 审计。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultBackupSyncStoreTest {
    private lateinit var database: HautDatabase
    private lateinit var journal: RoomVaultJournal
    private lateinit var service: VaultBackupService
    private lateinit var store: VaultBackupSyncStore
    private var generated = 0
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_sync_room"),
        profileId = ProfileId("prof_sync_room"),
        campusId = CampusId("lianhuajie")
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val searchIndex = RoomSearchIndex(database, clock = { fixedInstant() })
        journal = RoomVaultJournal(
            database = database,
            outbox = RoomEventOutbox(database),
            snapshotStores = listOf(
                CourseEntitySnapshotStore(database),
                GradeAttemptSnapshotStore(database),
                ExamScheduleSnapshotStore(database),
                PersonalEventSnapshotStore(database, searchIndex)
            ),
            clock = { fixedInstant() },
            idGenerator = { "sync-room-test-" + (++generated) },
            searchIndex = searchIndex
        )
        service = VaultBackupService(database, scope, journal, "sync-test", clock = { fixedInstant() })
        store = VaultBackupSyncStore(service)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun captureReadApplyAndDeleteRoundTripThroughRealBackupSchema() = runBlocking {
        database.campusDao().upsertCourse(
            CourseEntity(
                id = "course-room-sync",
                externalId = "ext-room-sync",
                name = "本机课程",
                teacher = "王老师",
                colorArgb = 1L,
                creditPlain = "2.0",
                source = "USER",
                userModified = true,
                lastSyncEpochMilli = 1L
            )
        )
        val captured = store.captureRows().first { it.table == "courses" }
        assertEquals("本机课程", (captured.row.cells["name"] as BackupCell.TextValue).value)
        val read = store.readRow(captured.table, captured.rowKey)
        assertNotNull(read)
        assertEquals(captured.rowSha256, read?.rowSha256)

        val remoteCells = LinkedHashMap(captured.row.cells)
        remoteCells["name"] = BackupCell.TextValue("远端课程")
        val remoteRow = BackupRow(remoteCells)
        store.applyUpserts(listOf(SyncBackupRowSnapshot(captured.policy, remoteRow, emptyMap())))
        assertEquals("远端课程", database.campusDao().getCourseById("course-room-sync")?.name)
        val history = journal.history(EntityRef(EntityType.COURSE, "course-room-sync", scope)).first()
        assertTrue(history.any { it.actor == Actor.SYNC && it.actorDetail == "e2ee-cloud-sync" })

        store.applyDeletes(listOf(captured.table to captured.rowKey))
        assertNull(database.campusDao().getCourseById("course-room-sync"))
    }

    @Test
    fun captureNormalizesScopedRowsAndApplyLocalizesToCurrentProfile() = runBlocking {
        database.vaultDao().upsertPersonalEvent(personalEvent("pe-sync-room", "本机日程"))
        val captured = store.captureRows().first { it.table == "personal_events" }
        assertEquals("sync", (captured.row.cells["scopeAccountId"] as BackupCell.TextValue).value)
        assertEquals("sync", (captured.row.cells["scopeProfileId"] as BackupCell.TextValue).value)
        assertEquals(BackupCell.NullValue, captured.row.cells["scopeCampusId"])

        val remoteCells = LinkedHashMap(captured.row.cells)
        remoteCells["title"] = BackupCell.TextValue("远端日程")
        store.applyUpserts(listOf(SyncBackupRowSnapshot(captured.policy, BackupRow(remoteCells), emptyMap())))
        val applied = database.vaultDao().getPersonalEvent("pe-sync-room")
        assertEquals("远端日程", applied?.title)
        assertEquals(scope.accountId.value, applied?.scopeAccountId)
        assertEquals(scope.profileId.value, applied?.scopeProfileId)
        assertEquals(scope.campusId?.value, applied?.scopeCampusId)
        assertTrue(applied?.provenanceJson?.contains("dis-sync-e2ee/v1") == true)
        assertTrue(applied?.provenanceJson?.contains("E2EE_CLOUD_SYNC") == true)
        assertTrue(applied?.provenanceJson?.contains("LOCAL_BACKUP_RESTORE") != true)
    }

    @Test
    fun nonEntityBackupRowsStillWritePreciseSyncJournalRefs() = runBlocking {
        database.cloudAndParcelDao().upsertParcelItem(
            ParcelItemEntity(
                id = "parcel-sync-journal",
                trackingNumber = "YT-1",
                carrierCode = "manual",
                carrierName = "校园快递",
                status = "IN_TRANSIT",
                pickupCode = null,
                stationName = null,
                itemTitle = "本机包裹",
                estimatedDeliveryText = "运输中",
                sourcesJson = "[]",
                fieldProvenanceJson = "{}",
                checkpointsJson = "[]",
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 1L
            )
        )
        val captured = store.captureRows().first { it.table == "parcel_items" }
        val remoteCells = LinkedHashMap(captured.row.cells)
        remoteCells["itemTitle"] = BackupCell.TextValue("远端包裹")
        store.applyUpserts(listOf(SyncBackupRowSnapshot(captured.policy, BackupRow(remoteCells), emptyMap())))

        val history = journal.recent(scope, setOf(cn.edu.haut.campus.core.model.EntityType.PARCEL_ITEM), 10).first()
        assertTrue(history.any { it.actor == Actor.SYNC && it.actorDetail == "e2ee-cloud-sync" })
        val change = history.first { it.actor == Actor.SYNC }
        assertTrue(change.before?.text?.contains("本机包裹") == true)
        assertTrue(change.after?.text?.contains("远端包裹") == true)
    }

    private fun personalEvent(id: String, title: String) = PersonalEventEntity(
        id = id,
        scopeSchoolId = scope.schoolId.value,
        scopeAccountId = scope.accountId.value,
        scopeProfileId = scope.profileId.value,
        scopeCampusId = scope.campusId?.value,
        kind = "PERSONAL",
        title = title,
        note = null,
        startAtEpochMs = 1000L,
        endAtEpochMs = null,
        timeSemantics = "DEADLINE",
        zoneId = "Asia/Shanghai",
        placeText = null,
        placeRefId = null,
        status = "SCHEDULED",
        busy = "BUSY",
        termId = null,
        revision = 1L,
        provenanceJson = "{}",
        sourceEntityType = null,
        sourceEntityId = null,
        linksJson = "[]",
        createdAtEpochMs = 1000L,
        updatedAtEpochMs = 1000L
    )

    private fun fixedInstant(): Instant = Instant.parse("2026-01-02T03:04:05Z")
}

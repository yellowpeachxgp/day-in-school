package cn.edu.haut.campus.core.vault

import androidx.room.Room
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.EventStatus
import cn.edu.haut.campus.core.timeline.PersonalEventDraft
import cn.edu.haut.campus.core.timeline.PersonalTimelineSource
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomPersonalEventStoreTest {
    private lateinit var database: HautDatabase
    private lateinit var searchIndex: RoomSearchIndex
    private lateinit var journal: RoomVaultJournal
    private lateinit var store: RoomPersonalEventStore
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_personal"),
        profileId = ProfileId("prof_personal"),
        campusId = CampusId("lianhuajie")
    )
    private var generatedId = 0

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
        searchIndex = RoomSearchIndex(database, clock = { Instant.parse("2026-01-02T03:04:05Z") })
        journal = RoomVaultJournal(
            database = database,
            outbox = RoomEventOutbox(database),
            snapshotStores = listOf(PersonalEventSnapshotStore(database, searchIndex)),
            clock = { Instant.parse("2026-01-02T03:04:05Z") },
            idGenerator = { "journal-" + (++generatedId) }
        )
        store = RoomPersonalEventStore(
            database = database,
            scope = scope,
            writer = journal,
            searchIndex = searchIndex,
            clock = { Instant.parse("2026-01-02T04:00:00Z") },
            idGenerator = { "event-" + (++generatedId) }
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun upsertPersistsTimelineJournalOutboxAndSearchWithoutDuplicateNoise() = runBlocking {
        val draft = draft(title = "复习离散数学")
        val id = store.upsert(draft, Actor.USER)

        assertEquals("personal", id.sourceId)
        val row = database.vaultDao().getPersonalEvent(id.value)
        assertEquals("复习离散数学", row?.title)
        assertEquals(scope.accountId.value, row?.scopeAccountId)
        assertEquals(1L, row?.revision)
        assertEquals(1, database.vaultDao().countEntityJournal())
        assertEquals(1, database.vaultDao().countDomainOutbox())
        assertEquals(listOf(id.value), searchIndex.query(scope, SearchQuery(scope, "离散")).hits.map { it.document.ref.id })

        val window = TimeWindow(Instant.parse("2026-01-10T00:00:00Z"), Instant.parse("2026-01-11T00:00:00Z"))
        val records = store.observe(scope, window).first()
        assertEquals(1, records.size)
        assertEquals(TimelineKind.REVIEW_BLOCK, records[0].kind)
        assertEquals(BusyLevel.BUSY, records[0].busy)
        assertEquals(TimelineTimeSemantics.TIMED, records[0].timeSemantics)
        assertEquals("TIMED", row?.timeSemantics)

        val timeline = PersonalTimelineSource(store).observe(scope, window).first()
        assertEquals(1, timeline.size)
        assertEquals(id, timeline[0].id)
        assertEquals(id.value, timeline[0].provenance.sourceRecordId)
        assertEquals(id.value, timeline[0].source.provenance.sourceRecordId)
        assertEquals(cn.edu.haut.campus.core.model.EntityType.PERSONAL_EVENT, timeline[0].source.entity.type)
        assertEquals(id.value, timeline[0].source.entity.id)

        store.upsert(draft.copy(id = id), Actor.USER)
        assertEquals(1, database.vaultDao().countEntityJournal())
        assertEquals(1L, database.vaultDao().getPersonalEvent(id.value)?.revision)
    }

    @Test
    fun uncertainPersonalEventRoundTripsWithoutFabricatingBusyWindow() = runBlocking {
        val id = store.upsert(
            draft(
                title = "时间待定的复习",
                end = null,
                busy = BusyLevel.FREE,
                timeSemantics = TimelineTimeSemantics.UNCERTAIN
            ),
            Actor.USER
        )

        assertEquals("UNCERTAIN", database.vaultDao().getPersonalEvent(id.value)?.timeSemantics)
        val window = TimeWindow(Instant.parse("2026-01-10T00:00:00Z"), Instant.parse("2026-01-11T00:00:00Z"))
        val record = store.observe(scope, window).first().single()
        assertEquals(TimelineTimeSemantics.UNCERTAIN, record.timeSemantics)
        val event = PersonalTimelineSource(store).observe(scope, window).first().single()
        assertTrue(event.isTimeUncertain)
        assertEquals(BusyLevel.FREE, event.busy)
        assertNull(event.end)
        assertEquals(id.value, event.provenance.sourceRecordId)
    }

    @Test
    fun revertUpdateRestoresPersonalEventAndSearchIndex() = runBlocking {
        val id = store.upsert(draft(title = "原始标题"), Actor.USER)
        store.upsert(draft(id = id, title = "误改标题"), Actor.USER)
        val refHistory = journal.history(cn.edu.haut.campus.core.model.EntityRef(
            cn.edu.haut.campus.core.model.EntityType.PERSONAL_EVENT,
            id.value,
            scope
        )).first()
        val update = refHistory.first { it.op == ChangeKind.UPDATED }

        val result = journal.revert(update.changeId, Actor.USER)

        assertEquals(RevertStatus.APPLIED, result.status)
        assertEquals("原始标题", database.vaultDao().getPersonalEvent(id.value)?.title)
        assertEquals(listOf(id.value), searchIndex.query(scope, SearchQuery(scope, "原始标题")).hits.map { it.document.ref.id })
        assertEquals(0, searchIndex.query(scope, SearchQuery(scope, "误改标题")).totalCount)
        val history = journal.history(cn.edu.haut.campus.core.model.EntityRef(
            cn.edu.haut.campus.core.model.EntityType.PERSONAL_EVENT,
            id.value,
            scope
        )).first()
        assertEquals(result.revertChangeId, history.first { it.changeId == update.changeId }.revertedByChangeId)
    }

    @Test
    fun deleteRemovesRowAndSearchGhostThenRevertRestoresBoth() = runBlocking {
        val id = store.upsert(draft(title = "高数复习"), Actor.USER)
        store.delete(id, Actor.USER)

        assertNull(database.vaultDao().getPersonalEvent(id.value))
        assertEquals(0, searchIndex.query(scope, SearchQuery(scope, "高数")).totalCount)
        val ref = cn.edu.haut.campus.core.model.EntityRef(
            cn.edu.haut.campus.core.model.EntityType.PERSONAL_EVENT,
            id.value,
            scope
        )
        val delete = journal.history(ref).first().first { it.op == ChangeKind.DELETED }
        val result = journal.revert(delete.changeId, Actor.USER)

        assertEquals(RevertStatus.APPLIED, result.status)
        assertEquals("高数复习", database.vaultDao().getPersonalEvent(id.value)?.title)
        assertEquals(listOf(id.value), searchIndex.query(scope, SearchQuery(scope, "高数")).hits.map { it.document.ref.id })
    }

    private fun draft(
        id: cn.edu.haut.campus.core.timeline.TimelineEventId? = null,
        title: String,
        end: Instant? = Instant.parse("2026-01-10T02:00:00Z"),
        busy: BusyLevel = BusyLevel.BUSY,
        timeSemantics: TimelineTimeSemantics = if (end == null) {
            TimelineTimeSemantics.DEADLINE
        } else {
            TimelineTimeSemantics.TIMED
        }
    ) = PersonalEventDraft(
        id = id,
        scope = scope,
        kind = TimelineKind.REVIEW_BLOCK,
        title = title,
        note = "第 3 章习题",
        start = Instant.parse("2026-01-10T01:00:00Z"),
        end = end,
        timeSemantics = timeSemantics,
        zone = ZoneId.of("Asia/Shanghai"),
        placeText = "图书馆",
        status = EventStatus.SCHEDULED,
        busy = busy,
        termId = "2025-2026-1",
        provenance = DataProvenance(
            providerId = "unit-test",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "personal-event-test",
            fetchedAt = Instant.parse("2026-01-02T03:04:05Z"),
            completeness = SnapshotCompleteness.COMPLETE,
            trust = VerificationLevel.MOCK_TESTED
        )
    )
}

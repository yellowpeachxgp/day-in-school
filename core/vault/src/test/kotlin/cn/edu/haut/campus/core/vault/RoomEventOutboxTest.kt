package cn.edu.haut.campus.core.vault

import androidx.room.Room
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.events.EventAppendStatus
import cn.edu.haut.campus.core.events.EventEnvelope
import cn.edu.haut.campus.core.events.EventIdConflictException
import cn.edu.haut.campus.core.events.UnknownEventException
import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RoomEventOutboxTest {
    private lateinit var database: HautDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun appendPersistsAndSameEventIdIsIdempotentAcrossOutboxInstances() = runBlocking {
        val firstOutbox = RoomEventOutbox(database, clock = { 100L })
        val envelope = envelope("event-1")
        val appended = firstOutbox.append(envelope)

        assertEquals(EventAppendStatus.APPENDED, appended.status)
        assertTrue(appended.record.sequence > 0)

        val reopened = RoomEventOutbox(database, clock = { 200L })
        val duplicate = reopened.append(envelope)
        assertEquals(EventAppendStatus.DUPLICATE_IDEMPOTENT, duplicate.status)
        assertEquals(appended.record.sequence, duplicate.record.sequence)
        assertEquals(listOf(envelope), reopened.pendingUnacked("automation").map { it.envelope })
    }

    @Test
    fun conflictingEventIdFailsClosed() = runBlocking {
        val outbox = RoomEventOutbox(database)
        outbox.append(envelope("event-conflict"))

        val error = runCatching {
            outbox.append(envelope("event-conflict", payload = """{"value":2}"""))
        }.exceptionOrNull()

        assertTrue(error is EventIdConflictException)
    }

    @Test
    fun ackIsPerConsumerGroupAndSurvivesOutboxRecreation() = runBlocking {
        val outbox = RoomEventOutbox(database)
        outbox.append(envelope("event-ack"))

        assertTrue(outbox.acknowledge("automation", "event-ack"))
        assertFalse(outbox.acknowledge("automation", "event-ack"))

        val reopened = RoomEventOutbox(database)
        assertTrue(reopened.pendingUnacked("automation").isEmpty())
        assertEquals(1, reopened.pendingUnacked("sync").size)
        assertEquals(1, reopened.unackedCount("sync"))
    }

    @Test
    fun unknownAckFailsClosed() = runBlocking {
        val outbox = RoomEventOutbox(database)

        val error = runCatching { outbox.acknowledge("automation", "missing") }.exceptionOrNull()

        assertTrue(error is UnknownEventException)
    }

    @Test
    fun busDeliversBacklogThenLiveAndStopsAfterAck() = runBlocking {
        val outbox = RoomEventOutbox(database)
        val bus = RoomEventBus(outbox)
        bus.publish(envelope("event-backlog"))

        val first = withTimeout(2_000) { bus.subscribe("automation").first() }
        assertEquals("event-backlog", first.eventId)
        bus.ack("automation", first.eventId)

        val received = mutableListOf<EventEnvelope>()
        val job = launch {
            bus.subscribe("automation").take(1).collect { received += it }
        }
        bus.publish(envelope("event-live"))
        withTimeout(2_000) { job.join() }

        assertEquals(listOf("event-live"), received.map { it.eventId })
    }

    @Test
    fun vaultJournalAndRoomOutboxCommitInSameDatabaseTransaction() = runBlocking {
        val outbox = RoomEventOutbox(database)
        val scope = SchoolAccountScope(
            schoolId = SchoolId("haut"),
            accountId = AccountId("acct_outbox"),
            profileId = ProfileId("prof_outbox")
        )
        val ref = EntityRef(EntityType.COURSE, "course-outbox", scope)
        var id = 0
        val journal = RoomVaultJournal(
            database = database,
            outbox = outbox,
            snapshotStores = listOf(CourseEntitySnapshotStore(database)),
            clock = { Instant.parse("2026-01-02T03:04:05Z") },
            idGenerator = { "integration-" + (++id) }
        )

        journal.write(ref, Actor.USER) {
            database.campusDao().upsertCourse(
                CourseEntity(
                    id = ref.id,
                    externalId = null,
                    name = "高等数学A（一）",
                    teacher = null,
                    colorArgb = 1L,
                    creditPlain = null,
                    source = "OFFICIAL_JW",
                    userModified = false,
                    lastSyncEpochMilli = null
                )
            )
        }

        assertEquals(1, database.vaultDao().countEntityJournal())
        assertEquals(1, database.vaultDao().countDomainOutbox())
        val pending = RoomEventOutbox(database).pendingUnacked("automation")
        assertEquals(1, pending.size)
        assertEquals("vault.entity.changed", pending[0].envelope.eventType)
        assertTrue(pending[0].envelope.payload.text.contains("course-outbox"))
    }

    private fun envelope(eventId: String, payload: String = """{"value":1}"""): EventEnvelope =
        EventEnvelope(
            eventId = eventId,
            eventType = "vault.test",
            schemaVersion = 1,
            occurredAt = Instant.parse("2026-01-02T03:04:05Z"),
            scopeKey = "haut/account/profile",
            payload = CanonicalJson.of(payload),
            metadata = emptyMap()
        )
}

package cn.edu.haut.campus.core.vault

import androidx.room.Room
import androidx.room.withTransaction
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventBus
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.events.EventAppendStatus
import cn.edu.haut.campus.core.events.EventEnvelope
import cn.edu.haut.campus.core.events.EventIdConflictException
import cn.edu.haut.campus.core.events.SensitiveEventPayloadException
import cn.edu.haut.campus.core.events.UnknownDomainEventTypeException
import cn.edu.haut.campus.core.events.UnknownEventException
import cn.edu.haut.campus.core.database.CourseEntity
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            outbox.append(envelope("event-conflict", changeId = 2L))
        }.exceptionOrNull()

        assertTrue(error is EventIdConflictException)
    }

    @Test
    fun unregisteredRawEnvelopeCannotEnterSharedDomainOutbox() = runBlocking {
        val outbox = RoomEventOutbox(database)
        val rawEnvelope = EventEnvelope(
            eventId = "event-raw-test",
            eventType = "vault.test",
            schemaVersion = 1,
            occurredAt = Instant.parse("2026-01-02T03:04:05Z"),
            scopeKey = "haut/acct_outbox/prof_outbox",
            payload = CanonicalJson.of("""{"value":1}"""),
            metadata = emptyMap()
        )

        val error = runCatching { outbox.append(rawEnvelope) }.exceptionOrNull()

        assertTrue(error is UnknownDomainEventTypeException)
        assertEquals(0, database.vaultDao().countDomainOutbox())
    }

    @Test
    fun sensitiveChangedPathEnvelopeCannotEnterSharedDomainOutbox() = runBlocking {
        val outbox = RoomEventOutbox(database)
        val valid = envelope("event-sensitive-path")
        val tampered = valid.copy(
            payload = CanonicalJson.of(valid.payload.text.replace("/row/title", "/row/sessionCookie"))
        )

        val error = runCatching { outbox.append(tampered) }.exceptionOrNull()

        assertTrue(error is SensitiveEventPayloadException)
        assertEquals(0, database.vaultDao().countDomainOutbox())
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
    fun nestedTransactionCommitNotifiesOnlyAfterOuterCommit() = runBlocking {
        val outbox = RoomEventOutbox(database)
        val received = Channel<String>(capacity = Channel.UNLIMITED)
        val unregister = outbox.addLiveListener { record -> received.trySend(record.envelope.eventId) }
        try {
            database.withTransaction {
                database.withTransaction {
                    outbox.append(envelope("event-nested-commit"))
                }
                assertTrue("内层返回不等于外层已提交", received.tryReceive().isFailure)
            }

            assertEquals("event-nested-commit", withTimeout(2_000) { received.receive() })
            assertTrue(received.tryReceive().isFailure)
        } finally {
            unregister()
            received.cancel()
        }
    }

    @Test
    fun nestedTransactionRollbackDoesNotNotifyLiveListener() = runBlocking {
        val outbox = RoomEventOutbox(database)
        val received = Channel<String>(capacity = Channel.UNLIMITED)
        val unregister = outbox.addLiveListener { record -> received.trySend(record.envelope.eventId) }
        try {
            val error = runCatching {
                database.withTransaction {
                    database.withTransaction {
                        outbox.append(envelope("event-nested-rollback"))
                        throw IllegalStateException("合成嵌套事务回滚")
                    }
                }
            }.exceptionOrNull()

            assertTrue(error is IllegalStateException)
            assertEquals(0, database.vaultDao().countDomainOutbox())
            assertNull(withTimeoutOrNull(300) { received.receive() })
        } finally {
            unregister()
            received.cancel()
        }
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
        assertFalse(pending[0].envelope.payload.text.contains("高等数学"))

        val typed = DomainEventBus(RoomEventBus(RoomEventOutbox(database)))
            .subscribe("automation")
            .first()
        assertTrue(typed is DomainEvent.VaultEntityChanged)
        val vaultEvent = typed as DomainEvent.VaultEntityChanged
        assertEquals(1L, vaultEvent.changeId)
        assertEquals("course-outbox", vaultEvent.entityRef.id)
        assertEquals(ChangeKind.CREATED, vaultEvent.change)
        assertEquals(Actor.USER, vaultEvent.actor)
        assertTrue(vaultEvent.changedPaths.contains("/course/name"))
    }

    @Test
    fun journalDoesNotNotifyLiveListenerWhenOuterTransactionRollsBack() = runBlocking {
        val outbox = RoomEventOutbox(database)
        val received = mutableListOf<String>()
        val unregister = outbox.addLiveListener { received += it.envelope.eventId }
        val scope = SchoolAccountScope(
            schoolId = SchoolId("haut"),
            accountId = AccountId("acct_outbox"),
            profileId = ProfileId("prof_outbox")
        )
        val courseRef = EntityRef(EntityType.COURSE, "course-outbox", scope)
        val profileRef = EntityRef(EntityType.PROFILE, "profile-rollback", scope)
        var businessWrote = false
        val sensitiveAfterWriteStore = object : EntitySnapshotStore {
            override val type: EntityType = EntityType.PROFILE
            override suspend fun read(ref: EntityRef): CanonicalJson? {
                if (!businessWrote) return null
                val syntheticKey = "pass" + "word"
                return CanonicalJson.of("{\"displayName\":\"合成\",\"" + syntheticKey + "\":\"rollback\"}")
            }
            override suspend fun apply(ref: EntityRef, snapshot: CanonicalJson?) = Unit
        }
        var id = 0
        val journal = RoomVaultJournal(
            database = database,
            outbox = outbox,
            snapshotStores = listOf(
                CourseEntitySnapshotStore(database),
                sensitiveAfterWriteStore
            ),
            clock = { Instant.parse("2026-01-02T03:04:05Z") },
            idGenerator = { "integration-" + (++id) }
        )

        val error = runCatching {
            journal.writeMany(
                refsProvider = { listOf(courseRef, profileRef) },
                actor = Actor.USER,
                actorDetail = null,
                source = null,
                writeGroupId = null
            ) {
                database.campusDao().upsertCourse(
                    CourseEntity(
                        id = courseRef.id,
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
                businessWrote = true
            }
        }.exceptionOrNull()
        unregister()

        assertTrue(error is SensitiveSnapshotException)
        assertEquals(0, database.vaultDao().countEntityJournal())
        assertEquals(0, database.vaultDao().countDomainOutbox())
        assertEquals(emptyList<String>(), received)
    }

    private fun envelope(eventId: String, changeId: Long = 1L): EventEnvelope {
        val scope = SchoolAccountScope(
            schoolId = SchoolId("haut"),
            accountId = AccountId("acct_outbox"),
            profileId = ProfileId("prof_outbox")
        )
        return DomainEventCodecRegistry.DEFAULT.encode(
            DomainEvent.VaultEntityChanged(
                eventId = eventId,
                scope = scope,
                occurredAt = Instant.parse("2026-01-02T03:04:05Z"),
                changeId = changeId,
                writeGroupId = "write-group-" + changeId,
                entityRef = EntityRef(EntityType.PERSONAL_EVENT, "personal:" + eventId, scope),
                change = ChangeKind.UPDATED,
                actor = Actor.USER,
                actorDetail = null,
                changedPaths = listOf("/row/title")
            )
        )
    }
}

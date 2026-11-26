package cn.edu.haut.campus.core.events

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryEventBusTest {
    @Test
    fun unackedBacklogIsDeliveredAtLeastOnceUntilAck() = runTest {
        val outbox = InMemoryEventOutbox()
        val bus = InMemoryEventBus(outbox)
        val event = testEnvelope("event-at-least-once")

        bus.publish(event)
        assertEquals(event, bus.subscribe("group-a").first())
        assertEquals(event, bus.subscribe("group-a").first())

        bus.ack("group-a", event.eventId)
        assertEquals(0, outbox.unackedCount("group-a"))
        val afterAck = withTimeoutOrNull(100) { bus.subscribe("group-a").first() }
        assertEquals(null, afterAck)
    }

    @Test
    fun consumerGroupsReceiveBacklogThenLiveIndependently() = runTest {
        val bus = InMemoryEventBus()
        val first = testEnvelope("event-backlog")
        val second = testEnvelope("event-live")
        bus.publish(first)

        val firstDelivered = CompletableDeferred<Unit>()
        val received = ArrayList<String>()
        val job = launch {
            bus.subscribe("group-a").take(2).collect { envelope ->
                received += envelope.eventId
                if (envelope.eventId == first.eventId) firstDelivered.complete(Unit)
            }
        }
        firstDelivered.await()
        bus.publish(second)
        job.join()

        assertEquals(listOf(first.eventId, second.eventId), received)
        val groupBBacklog = bus.subscribe("group-b").take(2).toList().map { it.eventId }
        assertEquals(listOf(first.eventId, second.eventId), groupBBacklog)
    }

    @Test
    fun publishIsIdempotentByEventIdAndRejectsConflictingPayload() = runTest {
        val outbox = InMemoryEventOutbox()
        val bus = InMemoryEventBus(outbox)
        val event = testEnvelope("event-idempotent")

        bus.publish(event)
        bus.publish(event)
        assertEquals(1, outbox.unackedCount("group-a"))

        val conflicting = event.copy(payload = CanonicalJson.of("{\"different\":true}"))
        val error = runCatching { bus.publish(conflicting) }.exceptionOrNull()
        assertTrue(error is EventIdConflictException)
        assertEquals(1, outbox.unackedCount("group-a"))
    }

    @Test
    fun duplicateAndConcurrentAckAreIdempotent() = runTest {
        val outbox = InMemoryEventOutbox()
        val bus = InMemoryEventBus(outbox)
        val event = testEnvelope("event-ack")
        bus.publish(event)

        val outcomes = (1..32).map {
            async(Dispatchers.Default) { outbox.acknowledge("group-a", event.eventId) }
        }.awaitAll()
        assertEquals(1, outcomes.count { it })
        assertEquals(31, outcomes.count { !it })
        assertEquals(0, outbox.unackedCount("group-a"))

        bus.ack("group-a", event.eventId)
        assertFalse(outbox.acknowledge("group-a", event.eventId))
        assertEquals(0, outbox.unackedCount("group-a"))
    }

    @Test
    fun rebuildingBusWithSameOutboxRedeliversUnackedEvents() = runTest {
        val outbox = InMemoryEventOutbox()
        val event = testEnvelope("event-rebuild")
        val firstBus = InMemoryEventBus(outbox)
        firstBus.publish(event)
        assertEquals(event, firstBus.subscribe("group-a").first())

        val rebuiltBus = InMemoryEventBus(outbox)
        assertEquals(event, rebuiltBus.subscribe("group-a").first())
    }

    @Test
    fun subscriptionFilterAppliesToBacklogAndLiveEvents() = runTest {
        val bus = InMemoryEventBus()
        val wanted = testEnvelope("event-wanted", eventType = "wanted")
        val filteredOut = testEnvelope("event-filtered", eventType = "filtered")
        bus.publish(filteredOut)
        bus.publish(wanted)

        val received = bus.subscribe("group-a") { it.eventType == "wanted" }.first()
        assertEquals(wanted, received)
    }

    private fun testEnvelope(
        eventId: String,
        eventType: String = "test.event"
    ): EventEnvelope = EventEnvelope(
        eventId = eventId,
        eventType = eventType,
        schemaVersion = 1,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        scopeKey = "scope-1",
        payload = CanonicalJson.of("{\"id\":\"" + eventId + "\"}"),
        metadata = mapOf("source" to "unit-test")
    )
}

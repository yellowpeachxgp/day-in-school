package cn.edu.haut.campus.core.events

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainEventBusIntegrationTest {
    @Test
    fun typedEventsUseOutboxDeliveryAndRedeliverAfterBusRebuild() = runTest {
        val outbox = InMemoryEventOutbox()
        val events = DomainEventTestFixtures.allEvents()
        val firstBus = DomainEventBus(InMemoryEventBus(outbox))

        events.forEach { event -> firstBus.publish(event) }

        val firstReceived = firstBus.subscribe("group-typed")
            .take(events.size)
            .toList()
        assertEquals(events, firstReceived)

        val rebuiltBus = DomainEventBus(InMemoryEventBus(outbox))
        val redelivered = rebuiltBus.subscribe("group-typed")
            .take(events.size)
            .toList()
        assertEquals(events, redelivered)
        assertEquals(events.size, outbox.unackedCount("group-typed"))

        events.forEach { event -> rebuiltBus.ack("group-typed", event.eventId) }
        assertEquals(0, outbox.unackedCount("group-typed"))
        val afterAck = withTimeoutOrNull(100) {
            rebuiltBus.subscribe("group-typed").first()
        }
        assertNull(afterAck)
    }

    @Test
    fun typedPublicationRemainsIdempotentByEventId() = runTest {
        val outbox = InMemoryEventOutbox()
        val bus = DomainEventBus(InMemoryEventBus(outbox))
        val event = DomainEventTestFixtures.allEvents().first()

        bus.publish(event)
        bus.publish(event)

        assertEquals(1, outbox.unackedCount("group-typed"))
        assertEquals(event, bus.subscribe("group-typed").first())
    }
}

package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventBus
import cn.edu.haut.campus.core.events.InMemoryEventBus
import cn.edu.haut.campus.core.events.InMemoryEventOutbox
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainEventTriggerSupportTest {
    private val occurredAt: Instant = Instant.parse("2026-03-02T08:00:00Z")

    @Test
    fun outboxRedeliveryKeepsSameTriggerInstanceKeyUntilAck() = runTest {
        val outbox = InMemoryEventOutbox()
        val bus = DomainEventBus(InMemoryEventBus(outbox))
        val event = dataStaleEvent()
        bus.publish(event)

        val first = bus.subscribe("automation-test").first()
        val replayed = bus.subscribe("automation-test").first()

        assertEquals(event, first)
        assertEquals(event, replayed)
        assertEquals(
            DomainEventTriggerSupport.triggerInstanceKey(first),
            DomainEventTriggerSupport.triggerInstanceKey(replayed)
        )
        assertTrue(DomainEventTriggerSupport.triggerInstanceKey(first).contains(event.eventId))
    }

    @Test
    fun onEventFilterUsesCanonicalJsonSubsetSemantics() {
        val event = dataStaleEvent()
        val matching = TriggerSpec.OnEvent(
            eventType = "data.stale",
            filter = JsonValue.Obj(mapOf("capability" to JsonValue.Str("TIMETABLE_READ")))
        )
        val mismatched = TriggerSpec.OnEvent(
            eventType = "data.stale",
            filter = JsonValue.Obj(mapOf("capability" to JsonValue.Str("GRADES_READ")))
        )
        val wrongType = TriggerSpec.OnEvent(eventType = "course.changed")

        assertTrue(DomainEventTriggerSupport.matches(matching, event))
        assertFalse(DomainEventTriggerSupport.matches(mismatched, event))
        assertFalse(DomainEventTriggerSupport.matches(wrongType, event))
    }

    private fun dataStaleEvent(): DomainEvent.DataStale = DomainEvent.DataStale(
        eventId = "stale-event-1",
        scope = AutomationTestFixtures.scope,
        occurredAt = occurredAt,
        capability = CapabilityKind.TIMETABLE_READ,
        since = occurredAt.minusSeconds(1800)
    )
}

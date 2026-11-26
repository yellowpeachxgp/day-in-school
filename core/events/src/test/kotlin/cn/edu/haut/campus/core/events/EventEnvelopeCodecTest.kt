package cn.edu.haut.campus.core.events

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventEnvelopeCodecTest {
    @Test
    fun envelopeRoundTripIsDeterministic() {
        val envelope = testEnvelope(
            eventId = "event-1",
            payloadText = "{\"z\":1,\"a\":\"x\"}",
            metadata = linkedMapOf("source" to "test", "card" to "W1-01a")
        )
        val encoded = CanonicalEventEnvelopeCodec.encode(envelope)
        assertEquals(
            "{\"eventId\":\"event-1\",\"eventType\":\"test.event\",\"metadata\":{\"card\":\"W1-01a\",\"source\":\"test\"},\"occurredAt\":\"2026-01-01T00:00:00Z\",\"payload\":{\"a\":\"x\",\"z\":1},\"schemaVersion\":1,\"scopeKey\":\"scope-1\"}",
            encoded.text
        )
        assertEquals(encoded.text, CanonicalEventEnvelopeCodec.encode(envelope).text)
        assertEquals(envelope, CanonicalEventEnvelopeCodec.decode(encoded))
    }

    @Test
    fun decodeRejectsUnexpectedTopLevelFields() {
        val encoded = CanonicalJson.of(
            "{\"eventId\":\"event-1\",\"eventType\":\"test.event\",\"schemaVersion\":1,\"occurredAt\":\"2026-01-01T00:00:00Z\",\"scopeKey\":null,\"payload\":{},\"metadata\":{},\"extra\":true}"
        )
        val error = runCatching { CanonicalEventEnvelopeCodec.decode(encoded) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    private fun testEnvelope(
        eventId: String,
        payloadText: String,
        metadata: Map<String, String>
    ): EventEnvelope = EventEnvelope(
        eventId = eventId,
        eventType = "test.event",
        schemaVersion = 1,
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
        scopeKey = "scope-1",
        payload = CanonicalJson.of(payloadText),
        metadata = metadata
    )
}

package cn.edu.haut.campus.core.events

import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainEventCodecTest {
    private val registry = DomainEventCodecRegistry.DEFAULT

    @Test
    fun eightDomainEventsKeepSchemaVersionAndCanonicalRoundTrip() {
        val events = DomainEventTestFixtures.allEvents()
        assertEquals(8, events.size)
        assertEquals(
            setOf(
                "course.changed",
                "exam.published",
                "grade.released",
                "utility.balance.sampled",
                "geofence.transition",
                "notice.confirmed",
                "group.event.confirmed",
                "data.stale"
            ),
            registry.supportedEventTypes
        )

        events.forEach { event ->
            val envelope = registry.encode(event)
            assertEquals(1, envelope.schemaVersion)
            assertEquals(event.eventId, envelope.eventId)
            assertEquals("haut/account-opaque/profile-opaque/lianhuajie", envelope.scopeKey)
            assertTrue(envelope.metadata.isEmpty())

            val decoded = registry.decode(envelope)
            assertEquals(event, decoded)

            val reencoded = registry.encode(decoded)
            assertEquals(envelope, reencoded)
            assertEquals(envelope.payload.text, reencoded.payload.text)
        }
    }

    @Test
    fun canonicalPayloadUsesStableSortedWireFormat() {
        val event = DomainEventTestFixtures.allEvents().filterIsInstance<DomainEvent.DataStale>().single()

        val first = registry.encode(event)
        val second = registry.encode(event)

        assertEquals(
            "{"capability":"TIMETABLE_READ","since":"2026-01-02T02:00:00Z"}",
            first.payload.text
        )
        assertEquals(first.payload.text, second.payload.text)
    }

    @Test
    fun unknownEventTypeIsRejectedFailClosed() {
        val envelope = registry.encode(DomainEventTestFixtures.allEvents().first())
            .copy(eventType = "course.unknown")

        val error = runCatching { registry.decode(envelope) }.exceptionOrNull()

        assertTrue(error is UnknownDomainEventTypeException)
    }

    @Test
    fun unknownSchemaVersionIsRejectedFailClosed() {
        val envelope = registry.encode(DomainEventTestFixtures.allEvents().first())
            .copy(schemaVersion = 2)

        val error = runCatching { registry.decode(envelope) }.exceptionOrNull()

        assertTrue(error is UnsupportedDomainEventSchemaVersionException)
    }

    @Test
    fun unknownPayloadFieldIsRejectedFailClosed() {
        val original = registry.encode(DomainEventTestFixtures.allEvents().filterIsInstance<DomainEvent.DataStale>().single())
        val tampered = original.copy(
            payload = CanonicalJson.of(
                "{"capability":"TIMETABLE_READ","since":"2026-01-02T02:00:00Z","extra":true}"
            )
        )

        val error = runCatching { registry.decode(tampered) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun unknownEnumValueIsRejectedFailClosed() {
        val original = registry.encode(DomainEventTestFixtures.allEvents().filterIsInstance<DomainEvent.DataStale>().single())
        val tampered = original.copy(
            payload = CanonicalJson.of(
                "{"capability":"NOT_A_CAPABILITY","since":"2026-01-02T02:00:00Z"}"
            )
        )

        val error = runCatching { registry.decode(tampered) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun missingOrMalformedScopeKeyIsRejectedFailClosed() {
        val envelope = registry.encode(DomainEventTestFixtures.allEvents().first())

        assertTrue(runCatching { registry.decode(envelope.copy(scopeKey = null)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { registry.decode(envelope.copy(scopeKey = "bad-scope")) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun nonEmptyMetadataIsRejectedInsteadOfSilentlyDropped() {
        val envelope = registry.encode(DomainEventTestFixtures.allEvents().first())
            .copy(metadata = mapOf("trace" to "test"))

        val error = runCatching { registry.decode(envelope) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun credentialOrCookieNamesInFieldDiffAreRejected() {
        val unsafeDiff = FieldDiff(
            listOf(
                FieldChange(
                    path = "/sessionCookie",
                    kind = FieldChangeKind.ADDED,
                    before = null,
                    after = JsonValue.Str("redacted-test-value")
                )
            )
        )

        val error = runCatching {
            DomainEvent.CourseChanged(
                eventId = "event-unsafe-diff",
                scope = DomainEventTestFixtures.scope,
                occurredAt = DomainEventTestFixtures.occurredAt,
                courseRef = DomainEventTestFixtures.ref(EntityType.COURSE, "course-math-1"),
                change = ChangeKind.UPDATED,
                diff = unsafeDiff
            )
        }.exceptionOrNull()

        assertTrue(error is SensitiveEventPayloadException)
    }

    @Test
    fun oversizedPayloadIsRejectedInsteadOfCarryingBlobData() {
        val largeDiff = FieldDiff(
            listOf(
                FieldChange(
                    path = "/largeText",
                    kind = FieldChangeKind.ADDED,
                    before = null,
                    after = JsonValue.Str("x".repeat(MAX_DOMAIN_EVENT_PAYLOAD_BYTES + 128))
                )
            )
        )
        val event = DomainEvent.CourseChanged(
            eventId = "event-large-payload",
            scope = DomainEventTestFixtures.scope,
            occurredAt = DomainEventTestFixtures.occurredAt,
            courseRef = DomainEventTestFixtures.ref(EntityType.COURSE, "course-math-1"),
            change = ChangeKind.UPDATED,
            diff = largeDiff
        )

        val error = runCatching { registry.encode(event) }.exceptionOrNull()

        assertTrue(error is DomainEventPayloadTooLargeException)
    }

    @Test
    fun fieldDiffNumbersAreCanonicalizedBeforeEncoding() {
        val diff = FieldDiff(
            listOf(
                FieldChange(
                    path = "/count",
                    kind = FieldChangeKind.ADDED,
                    before = null,
                    after = JsonValue.Num("1.0")
                )
            )
        )
        val event = DomainEvent.CourseChanged(
            eventId = "event-number-canonical",
            scope = DomainEventTestFixtures.scope,
            occurredAt = DomainEventTestFixtures.occurredAt,
            courseRef = DomainEventTestFixtures.ref(EntityType.COURSE, "course-math-1"),
            change = ChangeKind.UPDATED,
            diff = diff
        )

        val first = registry.encode(event)
        val second = registry.encode(registry.decode(first))

        assertTrue(first.payload.text.contains("\"value\":1"))
        assertEquals(first.payload.text, second.payload.text)
    }

    @Test
    fun entityReferenceTypeAndScopeAreValidated() {
        val wrongType = runCatching {
            DomainEvent.ExamPublished(
                eventId = "event-wrong-ref-type",
                scope = DomainEventTestFixtures.scope,
                occurredAt = DomainEventTestFixtures.occurredAt,
                examRef = DomainEventTestFixtures.ref(EntityType.COURSE, "course-math-1")
            )
        }.exceptionOrNull()
        val wrongScope = runCatching {
            DomainEvent.ExamPublished(
                eventId = "event-wrong-ref-scope",
                scope = DomainEventTestFixtures.scope,
                occurredAt = DomainEventTestFixtures.occurredAt,
                examRef = DomainEventTestFixtures.ref(
                    EntityType.EXAM,
                    "exam-math-final",
                    DomainEventTestFixtures.scope.copy(campusId = null)
                )
            )
        }.exceptionOrNull()

        assertTrue(wrongType is IllegalArgumentException)
        assertTrue(wrongScope is IllegalArgumentException)
    }
}

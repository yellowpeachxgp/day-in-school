package cn.edu.haut.campus.core.events

import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainEventCodecTest {
    private val registry = DomainEventCodecRegistry.DEFAULT

    @Test
    fun nineDomainEventsKeepSchemaVersionAndCanonicalRoundTrip() {
        val events = DomainEventTestFixtures.allEvents()
        assertEquals(9, events.size)
        assertEquals(
            setOf(
                "course.changed",
                "exam.published",
                "grade.released",
                "utility.balance.sampled",
                "geofence.transition",
                "notice.confirmed",
                "group.event.confirmed",
                "vault.entity.changed",
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
            """{"capability":"TIMETABLE_READ","since":"2026-01-02T02:00:00Z"}""",
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
                """{"capability":"TIMETABLE_READ","since":"2026-01-02T02:00:00Z","extra":true}"""
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
                """{"capability":"NOT_A_CAPABILITY","since":"2026-01-02T02:00:00Z"}"""
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
    fun vaultEntityChangedRejectsSensitiveChangedPathAtConstructionAndEncodeBoundary() {
        val error = runCatching {
            DomainEvent.VaultEntityChanged(
                eventId = "event-vault-sensitive-path",
                scope = DomainEventTestFixtures.scope,
                occurredAt = DomainEventTestFixtures.occurredAt,
                changeId = 7L,
                writeGroupId = "write-group-sensitive",
                entityRef = DomainEventTestFixtures.ref(EntityType.PERSONAL_EVENT, "personal:sensitive"),
                change = ChangeKind.UPDATED,
                actor = cn.edu.haut.campus.core.model.Actor.USER,
                actorDetail = null,
                changedPaths = listOf("/row/sessionCookie")
            )
        }.exceptionOrNull()

        assertTrue(error is SensitiveEventPayloadException)
    }

    @Test
    fun vaultEntityChangedDecodeRejectsTamperedSensitiveChangedPath() {
        val valid = DomainEventTestFixtures.allEvents().filterIsInstance<DomainEvent.VaultEntityChanged>().single()
        val envelope = registry.encode(valid)
        val tampered = envelope.copy(
            payload = CanonicalJson.of(envelope.payload.text.replace("/row/title", "/row/sessionCookie"))
        )

        val error = runCatching { registry.decode(tampered) }.exceptionOrNull()

        assertTrue(error is SensitiveEventPayloadException)
    }

    @Test
    fun mutableListAliasCannotChangeVaultEntityChangedPathsAfterValidation() {
        val backingPaths = mutableListOf("/row/title")
        val event = DomainEvent.VaultEntityChanged(
            eventId = "event-vault-path-alias",
            scope = DomainEventTestFixtures.scope,
            occurredAt = DomainEventTestFixtures.occurredAt,
            changeId = 8L,
            writeGroupId = "write-group-alias",
            entityRef = DomainEventTestFixtures.ref(EntityType.PERSONAL_EVENT, "personal:alias"),
            change = ChangeKind.UPDATED,
            actor = cn.edu.haut.campus.core.model.Actor.USER,
            actorDetail = null,
            changedPaths = backingPaths
        )

        backingPaths.add("/row/sessionCookie")

        assertEquals(2, backingPaths.size)
        assertEquals(listOf("/row/title"), event.changedPaths)
        val encoded = registry.encode(event)
        assertFalse(encoded.payload.text.contains("sessionCookie"))
    }

    @Test
    fun mutableListAliasCannotChangeFieldDiffAfterValidation() {
        val backingChanges = mutableListOf(
            FieldChange(
                path = "/room",
                kind = FieldChangeKind.MODIFIED,
                before = JsonValue.Str("W111"),
                after = JsonValue.Str("W112")
            )
        )
        val diff = FieldDiff(backingChanges)
        val event = courseChanged("event-mutable-diff-alias", diff)

        backingChanges.add(
            FieldChange(
                path = "/sessionCookie",
                kind = FieldChangeKind.ADDED,
                before = null,
                after = JsonValue.Str("must-not-enter-event")
            )
        )

        assertEquals(2, backingChanges.size)
        assertEquals(1, event.diff.changes.size)
        val encoded = registry.encode(event)
        assertFalse(encoded.payload.text.contains("sessionCookie"))
        assertFalse(encoded.payload.text.contains("must-not-enter-event"))
    }

    @Test
    fun mutableJsonBackingCollectionsCannotChangeFieldDiffAfterValidation() {
        val arrayBacking = mutableListOf<JsonValue>(JsonValue.Str("safe"))
        val objectBacking = mutableMapOf<String, JsonValue>(
            "items" to JsonValue.Arr(arrayBacking)
        )
        val after = JsonValue.Obj(objectBacking)
        val diff = FieldDiff(
            listOf(
                FieldChange(
                    path = "/payload",
                    kind = FieldChangeKind.ADDED,
                    before = null,
                    after = after
                )
            )
        )
        val event = courseChanged("event-mutable-json-alias", diff)

        objectBacking["password"] = JsonValue.Str("must-not-enter-event")
        arrayBacking.add(JsonValue.Obj(mapOf("secret" to JsonValue.Str("must-not-enter-event"))))

        val snapshotAfter = event.diff.changes.single().after as JsonValue.Obj
        assertEquals(setOf("items"), snapshotAfter.fields.keys)
        assertEquals(1, (snapshotAfter.fields.getValue("items") as JsonValue.Arr).items.size)
        val encoded = registry.encode(event)
        assertFalse(encoded.payload.text.contains("password"))
        assertFalse(encoded.payload.text.contains("secret"))
        assertFalse(encoded.payload.text.contains("must-not-enter-event"))
    }

    @Test
    fun foreignScopeCreatedRefsMutationCannotChangeNoticeAfterValidation() {
        val backingRefs = mutableListOf(
            DomainEventTestFixtures.ref(EntityType.PERSONAL_EVENT, "personal-event-valid")
        )
        val event = DomainEvent.NoticeConfirmed(
            eventId = "event-created-refs-alias",
            scope = DomainEventTestFixtures.scope,
            occurredAt = DomainEventTestFixtures.occurredAt,
            inboxItemId = "inbox-item-created-refs",
            createdRefs = backingRefs
        )
        val foreignScope = DomainEventTestFixtures.scope.copy(campusId = null)

        backingRefs.add(
            DomainEventTestFixtures.ref(EntityType.PERSONAL_EVENT, "personal-event-foreign", foreignScope)
        )

        assertEquals(2, backingRefs.size)
        assertEquals(1, event.createdRefs.size)
        val encoded = registry.encode(event)
        assertFalse(encoded.payload.text.contains("personal-event-foreign"))

        val directForeignScopeError = runCatching {
            DomainEvent.NoticeConfirmed(
                eventId = "event-created-refs-foreign",
                scope = DomainEventTestFixtures.scope,
                occurredAt = DomainEventTestFixtures.occurredAt,
                inboxItemId = "inbox-item-created-refs",
                createdRefs = backingRefs
            )
        }.exceptionOrNull()
        assertTrue(directForeignScopeError is IllegalArgumentException)
    }

    @Test
    fun fiveSensitiveWordClassesAreRejectedInPathsAndNestedJsonKeys() {
        val sensitiveNames = listOf(
            "Password",
            "user_credential",
            "session-cookie",
            "access.token",
            "CLIENT_SECRET"
        )

        sensitiveNames.forEach { sensitiveName ->
            val pathDiff = FieldDiff(
                listOf(
                    FieldChange(
                        path = "/" + sensitiveName,
                        kind = FieldChangeKind.ADDED,
                        before = null,
                        after = JsonValue.Str("x")
                    )
                )
            )
            val pathError = runCatching { validateFieldDiffSafety(pathDiff) }.exceptionOrNull()
            assertTrue("敏感路径应被拒绝: " + sensitiveName, pathError is SensitiveEventPayloadException)

            val nestedDiff = FieldDiff(
                listOf(
                    FieldChange(
                        path = "/payload",
                        kind = FieldChangeKind.ADDED,
                        before = null,
                        after = JsonValue.Obj(
                            mapOf(
                                "safeContainer" to JsonValue.Obj(
                                    mapOf(sensitiveName to JsonValue.Str("x"))
                                )
                            )
                        )
                    )
                )
            )
            val nestedError = runCatching { validateFieldDiffSafety(nestedDiff) }.exceptionOrNull()
            assertTrue("嵌套敏感键应被拒绝: " + sensitiveName, nestedError is SensitiveEventPayloadException)
        }
    }

    @Test
    fun ordinaryAcademicFieldNamesAreNotFalsePositiveSensitiveWords() {
        val benignPaths = listOf(
            "/room",
            "/teacher",
            "/courseName",
            "/weekCount",
            "/note",
            "/metadata/author",
            "/publicKey",
            "/session",
            "/sourceSystem",
            "/contentRevision"
        )

        benignPaths.forEachIndexed { index, path ->
            val diff = FieldDiff(
                listOf(
                    FieldChange(
                        path = path,
                        kind = FieldChangeKind.ADDED,
                        before = null,
                        after = JsonValue.Str("ordinary-value")
                    )
                )
            )
            val event = courseChanged("event-benign-field-" + index, diff)

            validateDomainEventInvariant(event)
            val encoded = registry.encode(event)
            assertTrue(encoded.payload.text.contains("ordinary-value"))
        }
    }

    @Test
    fun exact16KiBPayloadIsAcceptedAndOneByteBeyondIsRejected() {
        fun largeTextDiff(textLength: Int): FieldDiff = FieldDiff(
            listOf(
                FieldChange(
                    path = "/largeText",
                    kind = FieldChangeKind.ADDED,
                    before = null,
                    after = JsonValue.Str("x".repeat(textLength))
                )
            )
        )

        fun payloadSize(textLength: Int): Int = registry
            .encode(courseChanged("event-size-probe", largeTextDiff(textLength)))
            .payload
            .text
            .toByteArray(Charsets.UTF_8)
            .size

        val emptyStringPayloadSize = payloadSize(0)
        val exactTextLength = MAX_DOMAIN_EVENT_PAYLOAD_BYTES - emptyStringPayloadSize
        assertTrue(exactTextLength > 0)
        assertEquals(MAX_DOMAIN_EVENT_PAYLOAD_BYTES, payloadSize(exactTextLength))

        val error = runCatching { payloadSize(exactTextLength + 1) }.exceptionOrNull()
        assertTrue(error is DomainEventPayloadTooLargeException)
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

    @Test
    fun hostileBackingArrayCannotRewriteCreatedRefsAfterConstruction() {
        val validRef = DomainEventTestFixtures.ref(EntityType.PERSONAL_EVENT, "personal-event-valid")
        val hostile = HostileBackingArrayList(listOf(validRef))
        val event = DomainEvent.NoticeConfirmed(
            eventId = "event-created-refs-hostile-array",
            scope = DomainEventTestFixtures.scope,
            occurredAt = DomainEventTestFixtures.occurredAt,
            inboxItemId = "inbox-item-created-refs",
            createdRefs = hostile
        )
        assertEquals(0, hostile.toArrayCalls)
        val foreignScopeRef = DomainEventTestFixtures.ref(
            EntityType.PERSONAL_EVENT,
            "personal-event-foreign",
            DomainEventTestFixtures.scope.copy(campusId = null)
        )

        hostile.overwrite(0, foreignScopeRef)

        assertEquals(listOf(validRef), event.createdRefs)
        val encoded = registry.encode(event)
        assertFalse(encoded.payload.text.contains("personal-event-foreign"))
    }

    private fun courseChanged(eventId: String, diff: FieldDiff): DomainEvent.CourseChanged =
        DomainEvent.CourseChanged(
            eventId = eventId,
            scope = DomainEventTestFixtures.scope,
            occurredAt = DomainEventTestFixtures.occurredAt,
            courseRef = DomainEventTestFixtures.ref(EntityType.COURSE, "course-math-1"),
            change = ChangeKind.UPDATED,
            diff = diff
        )
}

/**
 * toArray() 违反约定返回自有 backing array 的自定义 Collection 探针。
 *
 * 快照边界若退回 ArrayList(Collection)（或经 toMutableList/toList 等间接使用同一构造），
 * 构造过程必然会调用 source.toArray()：在仍共享数组的运行时（如 Android 12/13）上，
 * 构造后 overwrite 即可改写快照。逐元素复制的快照边界完全不消费 toArray() 结果，
 * toArrayCalls 保持为 0，该断言不依赖任何 JDK/Android 版本实现细节。
 */
private class HostileBackingArrayList<T>(elements: List<T>) : AbstractList<T>() {
    private val backing: Array<Any?> = arrayOfNulls(elements.size)

    var toArrayCalls: Int = 0
        private set

    init {
        elements.forEachIndexed { index, element -> backing[index] = element }
    }

    override val size: Int
        get() = backing.size

    override fun get(index: Int): T {
        @Suppress("UNCHECKED_CAST")
        return backing[index] as T
    }

    override fun toArray(): Array<Any?> {
        toArrayCalls++
        return backing
    }

    fun overwrite(index: Int, value: T) {
        backing[index] = value
    }
}

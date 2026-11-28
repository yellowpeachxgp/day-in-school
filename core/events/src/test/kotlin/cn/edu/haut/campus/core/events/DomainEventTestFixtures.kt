package cn.edu.haut.campus.core.events

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.CapabilityKind
import cn.edu.haut.campus.core.model.ChangeKind
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.JsonValue
import java.time.Instant

internal object DomainEventTestFixtures {
    val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account-opaque"),
        profileId = ProfileId("profile-opaque"),
        campusId = CampusId("lianhuajie")
    )
    val occurredAt: Instant = Instant.parse("2026-01-02T03:04:05Z")

    fun ref(type: EntityType, id: String, refScope: SchoolAccountScope = scope): EntityRef =
        EntityRef(type = type, id = id, scope = refScope)

    fun courseDiff(): FieldDiff = FieldDiff(
        listOf(
            FieldChange(
                path = "/room",
                kind = FieldChangeKind.MODIFIED,
                before = JsonValue.Str("W111"),
                after = JsonValue.Str("W112")
            ),
            FieldChange(
                path = "/note",
                kind = FieldChangeKind.ADDED,
                before = null,
                after = JsonValue.Null
            ),
            FieldChange(
                path = "/weekCount",
                kind = FieldChangeKind.MODIFIED,
                before = JsonValue.Num("1"),
                after = JsonValue.Num("2")
            )
        )
    )

    fun provenance(): DataProvenance = DataProvenance(
        providerId = "utility-provider",
        schoolId = scope.schoolId,
        accountId = scope.accountId,
        sourceSystem = "utility-system",
        sourceRecordId = "meter-1",
        fetchedAt = Instant.parse("2026-01-02T03:00:00Z"),
        completeness = SnapshotCompleteness.COMPLETE,
        trust = VerificationLevel.MOCK_TESTED,
        schemaVersion = 1,
        contentRevision = "rev-20260102"
    )

    fun allEvents(): List<DomainEvent> = listOf(
        DomainEvent.CourseChanged(
            eventId = "event-course-changed",
            scope = scope,
            occurredAt = occurredAt,
            courseRef = ref(EntityType.COURSE, "course-math-1"),
            change = ChangeKind.UPDATED,
            diff = courseDiff()
        ),
        DomainEvent.ExamPublished(
            eventId = "event-exam-published",
            scope = scope,
            occurredAt = occurredAt,
            examRef = ref(EntityType.EXAM, "exam-math-final")
        ),
        DomainEvent.GradeReleased(
            eventId = "event-grade-released",
            scope = scope,
            occurredAt = occurredAt,
            attemptRef = ref(EntityType.GRADE_ATTEMPT, "attempt-math-1")
        ),
        DomainEvent.UtilityBalanceSampled(
            eventId = "event-utility-sampled",
            scope = scope,
            occurredAt = occurredAt,
            meterRef = ref(EntityType.UTILITY_METER, "meter-dorm-1"),
            balanceCents = 1234L,
            estimatedDaysLeft = 4.5,
            provenance = provenance()
        ),
        DomainEvent.GeofenceTransition(
            eventId = "event-geofence-enter",
            scope = scope,
            occurredAt = occurredAt,
            campusId = CampusId("lianhuajie"),
            enter = true,
            accuracyMeters = 12.5f
        ),
        DomainEvent.NoticeConfirmed(
            eventId = "event-notice-confirmed",
            scope = scope,
            occurredAt = occurredAt,
            inboxItemId = "inbox-item-1",
            createdRefs = listOf(
                ref(EntityType.PERSONAL_EVENT, "personal-event-1"),
                ref(EntityType.TIMELINE_EVENT, "timeline-event-1")
            )
        ),
        DomainEvent.GroupEventConfirmed(
            eventId = "event-group-confirmed",
            scope = scope,
            occurredAt = occurredAt,
            groupId = "group-study-1",
            eventRef = ref(EntityType.GROUP_EVENT, "group-event-1")
        ),
        DomainEvent.VaultEntityChanged(
            eventId = "event-vault-entity-changed",
            scope = scope,
            occurredAt = occurredAt,
            changeId = 42L,
            writeGroupId = "write-group-1",
            entityRef = ref(EntityType.PERSONAL_EVENT, "personal:event-1"),
            change = ChangeKind.UPDATED,
            actor = Actor.USER,
            actorDetail = "fixture-update",
            changedPaths = listOf("/row/title", "/row/note")
        ),
        DomainEvent.DataStale(
            eventId = "event-data-stale",
            scope = scope,
            occurredAt = occurredAt,
            capability = CapabilityKind.TIMETABLE_READ,
            since = Instant.parse("2026-01-02T02:00:00Z")
        )
    )
}

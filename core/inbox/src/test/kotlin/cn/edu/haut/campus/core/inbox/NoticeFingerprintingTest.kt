package cn.edu.haut.campus.core.inbox

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.VerificationLevel
import cn.edu.haut.campus.core.model.json.JsonValue
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class NoticeFingerprintingTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("synthetic-school"),
        accountId = AccountId("synthetic-account"),
        profileId = ProfileId("synthetic-profile")
    )
    private val otherScope = scope.copy(accountId = AccountId("other-account"), profileId = ProfileId("other-profile"))
    private val mathRef = EntityRef(EntityType.COURSE, "course-math", scope)
    private val term = TermContext(
        termId = "2026-2027-1",
        startDate = LocalDate.of(2026, 9, 7),
        weekStartDay = DayOfWeek.MONDAY,
        weekCount = 20
    )
    private val parseContext = ParseContext(
        scope = scope,
        referenceDate = LocalDate.of(2026, 11, 25),
        term = term,
        zone = zone,
        provenance = DataProvenance(
            providerId = "inbox-test",
            schoolId = scope.schoolId,
            accountId = scope.accountId,
            sourceSystem = "synthetic-notice",
            trust = VerificationLevel.UNVERIFIED
        ),
        courses = listOf(InboxCourseCandidate(mathRef, "高等数学"))
    )
    private val parser = RuleNoticeParser()

    @Test
    fun repeatedShareWithDifferentOffsetsAndEvidenceHasSameFingerprint() {
        val first = parseSingle("高等数学本周五停课")
        val second = parseSingle("请查收：高等数学本周五停课")

        val firstFp = NoticeFingerprintFactory.fingerprint(scope, first)
        val secondFp = NoticeFingerprintFactory.fingerprint(scope, second)

        assertEquals(firstFp, secondFp)
        assertTrue(firstFp.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first.sourceSpans, second.sourceSpans)
    }

    @Test
    fun semanticChangeInDateOrRoomChangesFingerprint() {
        val base = parseSingle("高等数学本周五停课")
        val otherDate = parseSingle("高等数学下周一停课")

        assertNotEquals(
            NoticeFingerprintFactory.fingerprint(scope, base),
            NoticeFingerprintFactory.fingerprint(scope, otherDate)
        )

        val roomA = parseSingle("下周三高等数学改到A203")
        val roomB = parseSingle("下周三高等数学改到B203")
        assertNotEquals(
            NoticeFingerprintFactory.fingerprint(scope, roomA),
            NoticeFingerprintFactory.fingerprint(scope, roomB)
        )
    }

    @Test
    fun sameTaskInDifferentProfilesNeverSharesFingerprint() {
        val action = CreateTaskAction(title = "提交实验报告", due = Instant.parse("2026-11-27T15:59:00Z"))

        val first = NoticeFingerprintFactory.fingerprint(scope, action)
        val second = NoticeFingerprintFactory.fingerprint(otherScope, action)

        assertNotEquals(first, second)
    }

    @Test
    fun titlePunctuationAndWhitespaceNormalizationIsConservativeAndDeterministic() {
        val due = Instant.parse("2026-11-27T15:59:00Z")
        val first = CreateTaskAction("提交 实验报告！", due)
        val second = CreateTaskAction("提交 实验报告", due)
        val different = CreateTaskAction("提交实验报告A", due)

        assertEquals(
            NoticeFingerprintFactory.fingerprint(scope, first),
            NoticeFingerprintFactory.fingerprint(scope, second)
        )
        assertNotEquals(
            NoticeFingerprintFactory.fingerprint(scope, first),
            NoticeFingerprintFactory.fingerprint(scope, different)
        )
    }

    @Test
    fun updateExistingFingerprintDoesNotDependOnDiffOrdering() {
        val target = EntityRef(EntityType.TIMELINE_EVENT, "event-1", scope)
        val titleChange = FieldChange(
            path = "/title",
            kind = FieldChangeKind.MODIFIED,
            before = JsonValue.Str("旧标题"),
            after = JsonValue.Str("新标题")
        )
        val placeChange = FieldChange(
            path = "/placeText",
            kind = FieldChangeKind.ADDED,
            before = null,
            after = JsonValue.Str("A203")
        )
        val first = ProposedAction.UpdateExisting(
            target = target,
            diff = FieldDiff(listOf(titleChange, placeChange)),
            confidence = mapOf("title" to Confidence.HIGH),
            sourceSpans = listOf(TextSpan(0, 4))
        )
        val second = ProposedAction.UpdateExisting(
            target = target,
            diff = FieldDiff(listOf(placeChange, titleChange)),
            confidence = mapOf("title" to Confidence.LOW),
            sourceSpans = listOf(TextSpan(4, 8))
        )

        assertEquals(
            NoticeFingerprintFactory.fingerprint(scope, first),
            NoticeFingerprintFactory.fingerprint(scope, second)
        )
    }

    @Test
    fun inMemoryIndexFindsOnlyRememberedFingerprintWithinScope() = runBlocking {
        val action = parseSingle("高等数学本周五停课")
        val target = EntityRef(EntityType.COURSE_OCCURRENCE, "occurrence-1", scope)
        val index = InMemoryNoticeFingerprinter(scope)
        val fp = index.remember(action, target)

        assertEquals(target, index.findExisting(fp))
        assertNull(index.findExisting("0".repeat(64)))
        assertNull(InMemoryNoticeFingerprinter(otherScope).findExisting(fp))
    }

    @Test
    fun inMemoryIndexRefusesToRemapSameFingerprintToDifferentEntity() {
        val action = parseSingle("高等数学本周五停课")
        val first = EntityRef(EntityType.COURSE_OCCURRENCE, "occurrence-1", scope)
        val second = EntityRef(EntityType.COURSE_OCCURRENCE, "occurrence-2", scope)
        val index = InMemoryNoticeFingerprinter(scope)
        index.remember(action, first)

        assertThrows(IllegalArgumentException::class.java) {
            index.remember(action, second)
        }
    }

    private fun parseSingle(text: String): ProposedAction =
        parser.parseReport(ExtractedText.fromPlainText(text), parseContext).actions.single()

    private fun CreateTaskAction(title: String, due: Instant?): ProposedAction.CreateTask =
        ProposedAction.CreateTask(
            title = title,
            due = due,
            checklist = emptyList(),
            confidence = mapOf("title" to Confidence.HIGH),
            sourceSpans = listOf(TextSpan(0, 1))
        )
}

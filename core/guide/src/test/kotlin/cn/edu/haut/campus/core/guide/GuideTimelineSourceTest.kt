package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.timeline.BusyLevel
import cn.edu.haut.campus.core.timeline.TimeWindow
import cn.edu.haut.campus.core.timeline.TimelineKind
import cn.edu.haut.campus.core.timeline.TimelineTimeSemantics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GuideTimelineSourceTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC)
    private val window = TimeWindow(
        Instant.parse("2026-03-02T00:00:00Z"),
        Instant.parse("2026-03-06T00:00:00Z")
    )

    @Test
    fun scheduled_procedure_step_projects_timed_tentative_event() = runTest {
        val pack = procedurePack()
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "student-certificate")
        runner.scheduleStep(
            GuideTestFixtures.scope,
            pack,
            "student-certificate",
            "prepare",
            Instant.parse("2026-03-02T01:00:00Z"),
            Duration.ofMinutes(30)
        )
        val source = GuideTimelineSource(listOf(pack), store, zone)

        val events = source.observe(GuideTestFixtures.scope, window).first()

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(TimelineKind.PROCEDURE_STEP, event.kind)
        assertEquals(TimelineTimeSemantics.TIMED, event.timeSemantics)
        assertEquals(Instant.parse("2026-03-02T01:30:00Z"), event.end)
        assertEquals(BusyLevel.TENTATIVE, event.busy)
        assertEquals("guide", event.id.sourceId)
        assertEquals("prepare", event.sourceMetadata["stepId"])
        assertEquals("haut/lianhuajie/service/student-affairs", event.place?.id)
    }

    @Test
    fun step_without_positive_duration_is_uncertain_never_fake_deadline() = runTest {
        val item = GuideTestFixtures.simpleProcedure()
        val pack = GuideTestFixtures.guidePackage(DisDatapackKind.PROCEDURE_GUIDE, listOf(item))
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "simple")
        runner.scheduleStep(
            GuideTestFixtures.scope,
            pack,
            "simple",
            "only",
            Instant.parse("2026-03-02T02:00:00Z")
        )
        val source = GuideTimelineSource(listOf(pack), store, zone)

        val event = source.observe(GuideTestFixtures.scope, window).first().single()

        assertEquals(TimelineTimeSemantics.UNCERTAIN, event.timeSemantics)
        assertEquals(null, event.end)
        assertEquals(BusyLevel.FREE, event.busy)
        assertTrue(event.isTimeUncertain)
    }

    @Test
    fun tracked_opportunity_deadline_projects_deadline_point_event() = runTest {
        val pack = opportunityPack()
        val store = InMemoryGuideProgressStore()
        val tracker = OpportunityProgressTracker(store, clock)
        tracker.track(GuideTestFixtures.scope, pack, "innovation-grant")
        val source = GuideTimelineSource(listOf(pack), store, zone)

        val event = source.observe(GuideTestFixtures.scope, window).first().single()

        assertEquals(TimelineKind.OPPORTUNITY_MILESTONE, event.kind)
        assertEquals(TimelineTimeSemantics.DEADLINE, event.timeSemantics)
        assertEquals(Instant.parse("2026-03-05T08:00:00Z"), event.start)
        assertEquals(null, event.end)
        assertEquals(BusyLevel.FREE, event.busy)
        assertEquals("innovation-grant", event.sourceMetadata["itemId"])
    }

    @Test
    fun untracked_opportunity_does_not_pollute_personal_timeline() = runTest {
        val pack = opportunityPack()
        val store = InMemoryGuideProgressStore()
        val source = GuideTimelineSource(listOf(pack), store, zone)

        val events = source.observe(GuideTestFixtures.scope, window).first()

        assertTrue(events.isEmpty())
    }

    @Test
    fun artifact_mismatch_fails_closed_instead_of_projecting_stale_progress() = runTest {
        val pack = procedurePack()
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "student-certificate")
        runner.scheduleStep(
            GuideTestFixtures.scope,
            pack,
            "student-certificate",
            "prepare",
            Instant.parse("2026-03-02T01:00:00Z"),
            Duration.ofMinutes(30)
        )
        val changed = pack.copy(artifactSha256 = "e".repeat(64))
        val source = GuideTimelineSource(listOf(changed), store, zone)

        assertFailsWithSuspend<GuideTimelineProjectionException> {
            source.observe(GuideTestFixtures.scope, window).first()
        }
    }

    private fun procedurePack() = GuideTestFixtures.guidePackage(
        DisDatapackKind.PROCEDURE_GUIDE,
        listOf(GuideTestFixtures.branchProcedure())
    )

    private fun opportunityPack() = GuideTestFixtures.guidePackage(
        DisDatapackKind.OPPORTUNITY_CATALOG,
        listOf(GuideTestFixtures.opportunity())
    )

    private suspend inline fun <reified T : Throwable> assertFailsWithSuspend(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        fail("预期抛出 " + T::class.simpleName)
        throw AssertionError("unreachable")
    }
}

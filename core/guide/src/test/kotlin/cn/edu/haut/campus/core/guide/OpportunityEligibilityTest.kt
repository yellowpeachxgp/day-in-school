package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.packages.datapack.OpportunityRequirement
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpportunityEligibilityTest {
    private val item = GuideTestFixtures.opportunity()
    private val pack = GuideTestFixtures.guidePackage(DisDatapackKind.OPPORTUNITY_CATALOG, listOf(item))

    @Test
    fun missing_information_is_needs_verification_never_eligible() {
        val assessment = OpportunityEligibilityEvaluator.assess(item)

        assertEquals(EligibilityState.NEEDS_VERIFICATION, assessment.baseState)
        assertEquals(EligibilityState.NEEDS_VERIFICATION, assessment.effectiveState)
        assertEquals(listOf("enrolled", "mentor"), assessment.missingRequirementIds)
    }

    @Test
    fun all_computed_satisfied_requirements_are_eligible() {
        val computableItem = item.copy(
            requirements = listOf(
                OpportunityRequirement("enrolled", "在读学生", computable = true),
                OpportunityRequirement("credits", "达到最低学分", computable = true)
            )
        )
        val assessment = OpportunityEligibilityEvaluator.assess(
            computableItem,
            facts = mapOf(
                "enrolled" to OpportunityRequirementFact(RequirementSatisfaction.SATISFIED, RequirementFactSource.COMPUTED),
                "credits" to OpportunityRequirementFact(RequirementSatisfaction.SATISFIED, RequirementFactSource.COMPUTED)
            )
        )

        assertEquals(EligibilityState.ELIGIBLE, assessment.baseState)
    }

    @Test
    fun user_attested_yes_remains_needs_verification_without_manual_override() {
        val assessment = OpportunityEligibilityEvaluator.assess(
            item,
            facts = mapOf(
                "enrolled" to OpportunityRequirementFact(RequirementSatisfaction.SATISFIED, RequirementFactSource.COMPUTED),
                "mentor" to OpportunityRequirementFact(RequirementSatisfaction.SATISFIED, RequirementFactSource.USER_ATTESTATION)
            )
        )

        assertEquals(EligibilityState.NEEDS_VERIFICATION, assessment.baseState)
        assertEquals(listOf("mentor"), assessment.missingRequirementIds)
    }

    @Test
    fun explicit_not_satisfied_wins_over_missing_information() {
        val assessment = OpportunityEligibilityEvaluator.assess(
            item,
            facts = mapOf(
                "enrolled" to OpportunityRequirementFact(RequirementSatisfaction.NOT_SATISFIED, RequirementFactSource.COMPUTED)
            )
        )

        assertEquals(EligibilityState.NOT_ELIGIBLE, assessment.baseState)
    }

    @Test
    fun non_computable_requirement_cannot_accept_computed_fact() {
        assertFailsWith<GuideOperationException> {
            OpportunityEligibilityEvaluator.assess(
                item,
                facts = mapOf(
                    "mentor" to OpportunityRequirementFact(RequirementSatisfaction.SATISFIED, RequirementFactSource.COMPUTED)
                )
            )
        }
    }

    @Test
    fun manual_override_corrects_effective_state_but_preserves_base_audit() {
        val override = ManualEligibilityOverride(
            EligibilityState.ELIGIBLE,
            "学院书面确认可放宽该条件",
            Instant.parse("2026-11-28T12:00:00Z")
        )

        val assessment = OpportunityEligibilityEvaluator.assess(item, manualOverride = override)

        assertEquals(EligibilityState.NEEDS_VERIFICATION, assessment.baseState)
        assertEquals(EligibilityState.ELIGIBLE, assessment.effectiveState)
        assertTrue(assessment.overridden)
    }

    @Test
    fun tracker_records_facts_material_validity_and_application_status() = runTest {
        val clock = MutableClock(Instant.parse("2026-11-01T00:00:00Z"))
        val store = InMemoryGuideProgressStore()
        val tracker = OpportunityProgressTracker(store, clock)
        tracker.track(GuideTestFixtures.scope, pack, "innovation-grant")

        var state = tracker.recordFact(
            GuideTestFixtures.scope,
            pack,
            "innovation-grant",
            "enrolled",
            RequirementSatisfaction.SATISFIED,
            RequirementFactSource.COMPUTED
        )
        assertEquals(EligibilityState.NEEDS_VERIFICATION, state.assessment.effectiveState)

        state = tracker.setMaterialCompleted(GuideTestFixtures.scope, pack, "innovation-grant", 0, true)
        assertTrue(state.materials[0].effectivelyCompleted)
        assertEquals(Instant.parse("2026-11-08T00:00:00Z"), state.materials[0].expiresAt)

        clock.instant = Instant.parse("2026-11-08T00:00:00Z")
        state = tracker.load(GuideTestFixtures.scope, pack, "innovation-grant")!!
        assertTrue(state.materials[0].expired)
        assertFalse(state.materials[0].effectivelyCompleted)

        state = tracker.setApplicationStatus(
            GuideTestFixtures.scope,
            pack,
            "innovation-grant",
            OpportunityApplicationStatus.SUBMITTED
        )
        assertEquals(OpportunityApplicationStatus.SUBMITTED, state.snapshot.applicationStatus)
    }

    private class MutableClock(var instantValue: Instant) : Clock() {
        var instant: Instant
            get() = instantValue
            set(value) { instantValue = value }

        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = instantValue
    }

    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
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

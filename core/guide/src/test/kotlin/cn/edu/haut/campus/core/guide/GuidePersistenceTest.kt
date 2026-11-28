package cn.edu.haut.campus.core.guide

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GuidePersistenceTest {
    private val created = Instant.parse("2026-11-28T10:00:00Z")
    private val updated = Instant.parse("2026-11-28T11:00:00Z")

    @Test
    fun procedure_snapshot_codec_roundtrip_preserves_answers_completion_and_schedule() {
        val snapshot = ProcedureRunSnapshot(
            scope = GuideTestFixtures.scope,
            packId = "haut.procedures",
            itemId = "student-certificate",
            packVersion = "2026.11.1",
            artifactSha256 = "a".repeat(64),
            answers = linkedMapOf("minor" to "yes"),
            completedStepIds = listOf("prepare"),
            scheduledSteps = mapOf("guardian" to ScheduledStep(Instant.parse("2026-12-01T01:00:00Z"), Duration.ofMinutes(30))),
            createdAt = created,
            updatedAt = updated,
            revision = 4
        )

        val decoded = GuideProgressCodec.decodeProcedureRun(GuideProgressCodec.encodeProcedureRun(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun procedure_schedule_codec_preserves_subsecond_and_fractional_second_durations() {
        val snapshot = ProcedureRunSnapshot(
            scope = GuideTestFixtures.scope,
            packId = "haut.procedures",
            itemId = "student-certificate",
            packVersion = "2026.11.1",
            artifactSha256 = "a".repeat(64),
            scheduledSteps = linkedMapOf(
                "prepare" to ScheduledStep(Instant.parse("2026-12-01T01:00:00Z"), Duration.ofMillis(500)),
                "guardian" to ScheduledStep(Instant.parse("2026-12-01T02:00:00Z"), Duration.ofMillis(1500))
            ),
            createdAt = created,
            updatedAt = updated,
            revision = 2
        )

        val encoded = GuideProgressCodec.encodeProcedureRun(snapshot)
        val decoded = GuideProgressCodec.decodeProcedureRun(encoded)

        assertEquals(snapshot, decoded)
        assertTrue(encoded.contains("\"durationMicros\":500000"))
        assertTrue(encoded.contains("\"durationMicros\":1500000"))
    }

    @Test
    fun opportunity_snapshot_codec_roundtrip_preserves_facts_materials_override_and_status() {
        val snapshot = OpportunityProgressSnapshot(
            scope = GuideTestFixtures.scope,
            packId = "haut.opportunities",
            itemId = "innovation-grant",
            packVersion = "2026.11.1",
            artifactSha256 = "a".repeat(64),
            facts = mapOf(
                "enrolled" to OpportunityRequirementFact(
                    RequirementSatisfaction.SATISFIED,
                    RequirementFactSource.COMPUTED,
                    note = "成绩系统已有在读状态",
                    recordedAt = created
                )
            ),
            completedMaterials = mapOf(0 to created),
            eligibilityOverride = ManualEligibilityOverride(EligibilityState.NEEDS_VERIFICATION, "等待学院确认", updated),
            applicationStatus = OpportunityApplicationStatus.SUBMITTED,
            createdAt = created,
            updatedAt = updated,
            revision = 5
        )

        val decoded = GuideProgressCodec.decodeOpportunityProgress(GuideProgressCodec.encodeOpportunityProgress(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun codec_treats_whitespace_option_value_as_schema_valid_opaque_string() {
        val snapshot = ProcedureRunSnapshot(
            scope = GuideTestFixtures.scope,
            packId = "haut.procedures",
            itemId = "student-certificate",
            packVersion = "2026.11.1",
            artifactSha256 = "a".repeat(64),
            answers = linkedMapOf("minor" to " "),
            createdAt = created,
            updatedAt = updated,
            revision = 1
        )

        val decoded = GuideProgressCodec.decodeProcedureRun(GuideProgressCodec.encodeProcedureRun(snapshot))

        assertEquals(snapshot, decoded)
        assertEquals(" ", decoded.answers["minor"])
    }

    @Test
    fun codec_rejects_unknown_fields_instead_of_forward_compatible_guessing() {
        val snapshot = ProcedureRunSnapshot(
            scope = GuideTestFixtures.scope,
            packId = "haut.procedures",
            itemId = "student-certificate",
            packVersion = "2026.11.1",
            artifactSha256 = "a".repeat(64),
            createdAt = created,
            updatedAt = updated,
            revision = 1
        )
        val encoded = GuideProgressCodec.encodeProcedureRun(snapshot)
        val tampered = encoded.dropLast(1) + ",\"extra\":true}"

        assertFailsWith<GuidePersistenceException> {
            GuideProgressCodec.decodeProcedureRun(tampered)
        }
    }

    @Test
    fun codec_rejects_wrong_schema_and_unknown_enum() {
        val snapshot = OpportunityProgressSnapshot(
            scope = GuideTestFixtures.scope,
            packId = "haut.opportunities",
            itemId = "innovation-grant",
            packVersion = "2026.11.1",
            artifactSha256 = "a".repeat(64),
            facts = mapOf("enrolled" to OpportunityRequirementFact(RequirementSatisfaction.SATISFIED, RequirementFactSource.COMPUTED)),
            createdAt = created,
            updatedAt = updated,
            revision = 1
        )
        val encoded = GuideProgressCodec.encodeOpportunityProgress(snapshot)

        assertFailsWith<GuidePersistenceException> {
            GuideProgressCodec.decodeOpportunityProgress(encoded.replace("dis-guide-progress/v1", "dis-guide-progress/v2"))
        }
        assertFailsWith<GuidePersistenceException> {
            GuideProgressCodec.decodeOpportunityProgress(encoded.replace("SATISFIED", "MAYBE"))
        }
    }

    @Test
    fun evaluator_rejects_persisted_answer_outside_declarative_options() {
        val item = GuideTestFixtures.branchProcedure()

        assertFailsWith<GuidePersistenceException> {
            ProcedurePathEvaluator.evaluate(item, mapOf("minor" to "perhaps"), emptyList())
        }
    }

    @Test
    fun scheduled_step_rejects_duration_that_would_lose_submicrosecond_precision() {
        assertFailsWith<IllegalArgumentException> {
            ScheduledStep(Instant.parse("2026-12-01T01:00:00Z"), Duration.ofNanos(500))
        }
    }

    @Test
    fun codec_rejects_non_positive_scheduled_duration_as_persistence_error() {
        val snapshot = ProcedureRunSnapshot(
            scope = GuideTestFixtures.scope,
            packId = "haut.procedures",
            itemId = "student-certificate",
            packVersion = "2026.11.1",
            artifactSha256 = "a".repeat(64),
            scheduledSteps = mapOf("prepare" to ScheduledStep(Instant.parse("2026-12-01T01:00:00Z"), Duration.ofMinutes(30))),
            createdAt = created,
            updatedAt = updated,
            revision = 1
        )
        val encoded = GuideProgressCodec.encodeProcedureRun(snapshot)
        val tampered = encoded.replace("\"durationMicros\":1800000000", "\"durationMicros\":-1")

        assertFailsWith<GuidePersistenceException> {
            GuideProgressCodec.decodeProcedureRun(tampered)
        }
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

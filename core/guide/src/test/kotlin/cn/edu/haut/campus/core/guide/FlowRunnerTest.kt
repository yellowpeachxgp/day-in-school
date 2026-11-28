package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.packages.datapack.DisDatapackKind
import cn.edu.haut.campus.core.packages.datapack.ProcedureQuestion
import cn.edu.haut.campus.core.packages.datapack.ProcedureQuestionOption
import cn.edu.haut.campus.core.packages.datapack.ProcedureStep
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FlowRunnerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC)
    private val pack = GuideTestFixtures.guidePackage(
        DisDatapackKind.PROCEDURE_GUIDE,
        listOf(GuideTestFixtures.branchProcedure())
    )

    @Test
    fun negative_branch_generates_personal_path_without_unselected_steps() = runTest {
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)

        var state = runner.start(GuideTestFixtures.scope, pack, "student-certificate")
        assertEquals(GuideRunStatus.AWAITING_QUESTION, state.status)
        assertEquals("prepare", state.currentStep?.id)
        assertEquals("minor", state.actionableQuestion?.id)
        assertEquals(listOf("prepare"), state.path.steps.map { it.id })

        state = runner.answer(GuideTestFixtures.scope, pack, "student-certificate", "minor", "no")
        assertEquals(GuideRunStatus.IN_PROGRESS, state.status)
        assertEquals(listOf("prepare", "submit"), state.path.steps.map { it.id })
        assertTrue(state.path.complete)
        assertEquals("prepare", state.currentStep?.id)
        assertEquals(listOf("学生证或校园卡", "一寸照片"), state.path.materials.map { it.name })

        state = runner.completeCurrentStep(GuideTestFixtures.scope, pack, "student-certificate", "prepare")
        assertEquals("submit", state.currentStep?.id)
        state = runner.completeCurrentStep(GuideTestFixtures.scope, pack, "student-certificate", "submit")
        assertEquals(GuideRunStatus.COMPLETED, state.status)
        assertEquals(2, state.completedCount)
        assertEquals(2, state.resolvedStepCount)
    }

    @Test
    fun affirmative_branch_includes_conditional_step_in_personal_path() = runTest {
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "student-certificate")

        var state = runner.answer(GuideTestFixtures.scope, pack, "student-certificate", "minor", "yes")

        assertEquals(listOf("prepare", "guardian", "submit"), state.path.steps.map { it.id })
        assertEquals("prepare", state.currentStep?.id)
        assertEquals(GuideRunStatus.IN_PROGRESS, state.status)
        state = runner.completeCurrentStep(GuideTestFixtures.scope, pack, "student-certificate", "prepare")
        assertEquals("guardian", state.currentStep?.id)
    }

    @Test
    fun known_path_question_can_be_answered_before_completion_but_value_is_declarative() = runTest {
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "student-certificate")

        assertFailsWithSuspend<GuideOperationException> {
            runner.answer(GuideTestFixtures.scope, pack, "student-certificate", "minor", "perhaps")
        }
        val state = runner.answer(GuideTestFixtures.scope, pack, "student-certificate", "minor", "no")
        assertEquals(GuideRunStatus.IN_PROGRESS, state.status)
        assertEquals("prepare", state.currentStep?.id)
    }

    @Test
    fun same_artifact_progress_restores_without_losing_answers() = runTest {
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "student-certificate")
        runner.completeCurrentStep(GuideTestFixtures.scope, pack, "student-certificate", "prepare")
        runner.answer(GuideTestFixtures.scope, pack, "student-certificate", "minor", "yes")

        val restored = runner.restore(GuideTestFixtures.scope, pack, "student-certificate")

        assertTrue(restored is GuideRestoreResult.Restored)
        val state = (restored as GuideRestoreResult.Restored).state
        assertEquals(mapOf("minor" to "yes"), state.snapshot.answers)
        assertEquals(listOf("prepare"), state.snapshot.completedStepIds)
        assertEquals("guardian", state.currentStep?.id)
    }

    @Test
    fun artifact_change_is_reported_as_definition_change() = runTest {
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "student-certificate")
        val changed = pack.copy(artifactSha256 = "d".repeat(64))

        val restored = runner.restore(GuideTestFixtures.scope, changed, "student-certificate")

        assertTrue(restored is GuideRestoreResult.DefinitionChanged)
    }

    @Test
    fun removed_item_is_reported_as_definition_change_not_missing_item() = runTest {
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "student-certificate")
        val removed = GuideTestFixtures.guidePackage(
            DisDatapackKind.PROCEDURE_GUIDE,
            listOf(GuideTestFixtures.simpleProcedure("other-procedure")),
            artifactSha256 = "e".repeat(64)
        )

        val restored = runner.restore(GuideTestFixtures.scope, removed, "student-certificate")

        assertTrue(restored is GuideRestoreResult.DefinitionChanged)
        assertEquals(pack.artifactSha256, (restored as GuideRestoreResult.DefinitionChanged).storedArtifactSha256)
        assertEquals(removed.artifactSha256, restored.currentArtifactSha256)
    }

    @Test
    fun correcting_prior_answer_excludes_old_branch_completion_from_new_path() = runTest {
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, pack, "student-certificate")
        runner.completeCurrentStep(GuideTestFixtures.scope, pack, "student-certificate", "prepare")
        runner.answer(GuideTestFixtures.scope, pack, "student-certificate", "minor", "yes")
        runner.completeCurrentStep(GuideTestFixtures.scope, pack, "student-certificate", "guardian")

        var state = runner.clearAnswerFrom(GuideTestFixtures.scope, pack, "student-certificate", "minor")
        assertEquals(GuideRunStatus.AWAITING_QUESTION, state.status)
        state = runner.answer(GuideTestFixtures.scope, pack, "student-certificate", "minor", "no")

        assertEquals(listOf("prepare", "submit"), state.path.steps.map { it.id })
        assertEquals(1, state.completedCount)
        assertEquals("submit", state.currentStep?.id)
        assertTrue(state.snapshot.completedStepIds.contains("guardian"))
    }

    @Test
    fun jump_does_not_permanently_skip_precollection_question_at_same_trigger() = runTest {
        val branchAtStart = ProcedureQuestion(
            id = "branch-at-start",
            question = "是否跳过起始步骤？",
            options = listOf(
                ProcedureQuestionOption("stay", "不跳过", nextStepId = "start"),
                ProcedureQuestionOption("skip", "跳过", nextStepId = "end")
            )
        )
        val precollection = ProcedureQuestion(
            id = "precollection",
            question = "前置事实是否已确认？",
            options = listOf(
                ProcedureQuestionOption("yes", "已确认", nextStepId = null),
                ProcedureQuestionOption("no", "未确认", nextStepId = null)
            )
        )
        val item = GuideTestFixtures.simpleProcedure("pre-collection-jump").copy(
            steps = listOf(
                ProcedureStep("start", "开始", "开始"),
                ProcedureStep("middle", "中段", "中段"),
                ProcedureStep("end", "结束", "结束")
            ),
            questions = listOf(branchAtStart, precollection)
        )
        val jumpPack = GuideTestFixtures.guidePackage(DisDatapackKind.PROCEDURE_GUIDE, listOf(item))
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, jumpPack, "pre-collection-jump")

        var state = runner.answer(GuideTestFixtures.scope, jumpPack, "pre-collection-jump", "branch-at-start", "skip")
        assertEquals("precollection", state.actionableQuestion?.id)
        assertTrue(!state.path.complete)

        state = runner.answer(GuideTestFixtures.scope, jumpPack, "pre-collection-jump", "precollection", "yes")
        assertTrue(state.path.complete)
        assertEquals(listOf("end"), state.path.steps.map { it.id })
        assertEquals(mapOf("branch-at-start" to "skip", "precollection" to "yes"), state.snapshot.answers)
    }

    @Test
    fun clearing_answer_invalidates_downstream_questions_by_trigger_not_declaration_order() = runTest {
        val lateDeclaredFirst = ProcedureQuestion(
            id = "late",
            question = "末端确认？",
            options = listOf(
                ProcedureQuestionOption("yes", "是", nextStepId = "end"),
                ProcedureQuestionOption("no", "否", nextStepId = "end")
            )
        )
        val earlyDeclaredSecond = ProcedureQuestion(
            id = "early",
            question = "需要中段步骤吗？",
            options = listOf(
                ProcedureQuestionOption("yes", "需要", nextStepId = "branch"),
                ProcedureQuestionOption("no", "不需要", nextStepId = "end")
            )
        )
        val item = GuideTestFixtures.simpleProcedure("ordered").copy(
            steps = listOf(
                ProcedureStep("start", "开始", "开始"),
                ProcedureStep("branch", "中段", "中段"),
                ProcedureStep("end", "结束", "结束")
            ),
            questions = listOf(lateDeclaredFirst, earlyDeclaredSecond)
        )
        val orderedPack = GuideTestFixtures.guidePackage(DisDatapackKind.PROCEDURE_GUIDE, listOf(item))
        val store = InMemoryGuideProgressStore()
        val runner = FlowRunner(store, clock)
        runner.start(GuideTestFixtures.scope, orderedPack, "ordered")
        runner.answer(GuideTestFixtures.scope, orderedPack, "ordered", "early", "yes")
        runner.answer(GuideTestFixtures.scope, orderedPack, "ordered", "late", "yes")
        val before = runner.restore(GuideTestFixtures.scope, orderedPack, "ordered")
        assertTrue(before is GuideRestoreResult.Restored)
        assertEquals(2, (before as GuideRestoreResult.Restored).state.snapshot.answers.size)

        val cleared = runner.clearAnswerFrom(GuideTestFixtures.scope, orderedPack, "ordered", "early")

        assertEquals(emptyMap<String, String>(), cleared.snapshot.answers)
    }

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

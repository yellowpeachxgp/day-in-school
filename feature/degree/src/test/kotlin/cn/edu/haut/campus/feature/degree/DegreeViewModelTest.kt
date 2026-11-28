package cn.edu.haut.campus.feature.degree

import cn.edu.haut.campus.core.degree.DegreeRepository
import cn.edu.haut.campus.core.degree.OverrideInput
import cn.edu.haut.campus.core.degree.TrainingPlanImportRecord
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.plan.AuditOverride
import cn.edu.haut.campus.core.plan.AuditReport
import cn.edu.haut.campus.core.plan.ImportInput
import cn.edu.haut.campus.core.plan.PlanSource
import cn.edu.haut.campus.core.plan.TrainingPlan
import cn.edu.haut.campus.feature.degree.preview.DegreePreviewFixtures
import cn.edu.haut.campus.feature.degree.state.DegreeStatus
import cn.edu.haut.campus.feature.degree.state.DegreeUiAction
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DegreeViewModel 状态机测试：假仓库验证加载、导入、确认、认定闭环。合成数据（UNVERIFIED）。 */
class DegreeViewModelTest {

    private class FakeRepository : DegreeRepository {
        val imports = mutableListOf<TrainingPlanImportRecord>()
        val overrides = mutableListOf<AuditOverride>()
        var activeId: String? = null
        var reportToReturn: AuditReport? = null
        var closed = false

        override suspend fun importPlan(input: ImportInput, actor: Actor): TrainingPlanImportRecord {
            val record = TrainingPlanImportRecord(
                importId = "tpl-fake",
                planId = "fake-plan",
                title = "假方案",
                planVersion = "1",
                format = "univ-training-plan/v1",
                contentDigest = "0".repeat(64),
                warnings = emptyList(),
                skippedRowCount = 0,
                source = PlanSource.UNKNOWN,
                confirmedForStudent = false,
                importedAt = Instant.parse("2026-02-01T00:00:00Z"),
                importedBy = actor,
                active = true
            )
            if (imports.none { it.importId == record.importId }) imports += record
            if (activeId == null) activeId = record.importId
            return record
        }

        override suspend fun listImports(): List<TrainingPlanImportRecord> =
            imports.map { it.copy(active = it.importId == activeId) }

        override suspend fun loadPlan(importId: String): TrainingPlan? = null

        override suspend fun setConfirmedForStudent(importId: String, confirmed: Boolean, actor: Actor) {
            val index = imports.indexOfFirst { it.importId == importId }
            if (index >= 0) {
                imports[index] = imports[index].copy(
                    confirmedForStudent = confirmed,
                    source = if (confirmed) PlanSource.USER_CONFIRMED else imports[index].source
                )
            }
        }

        override suspend fun setActiveImport(importId: String?, actor: Actor) {
            activeId = importId
        }

        override suspend fun activeImport(): TrainingPlanImportRecord? =
            listImports().firstOrNull { it.importId == activeId }

        override suspend fun recordOverride(input: OverrideInput, actor: Actor): AuditOverride {
            val override = AuditOverride(
                id = "ovr-fake",
                attemptRef = cn.edu.haut.campus.core.model.EntityRef(
                    cn.edu.haut.campus.core.model.EntityType.GRADE_ATTEMPT,
                    input.attemptId,
                    cn.edu.haut.campus.core.model.SchoolAccountScope(
                        cn.edu.haut.campus.core.model.SchoolId("demo"),
                        cn.edu.haut.campus.core.model.AccountId("acct"),
                        cn.edu.haut.campus.core.model.ProfileId("prof")
                    )
                ),
                action = input.action,
                mapToGroupId = input.mapToGroupId,
                reason = input.reason,
                actor = actor,
                at = Instant.parse("2026-02-01T00:00:00Z")
            )
            overrides += override
            return override
        }

        override suspend fun listOverrides(): List<AuditOverride> = overrides.toList()

        override suspend fun auditActivePlan(
            attempts: List<GradeAttempt>,
            now: LocalDate,
            context: cn.edu.haut.campus.core.plan.AuditContext
        ): AuditReport? = if (activeId == null) null else reportToReturn

        override fun close() {
            closed = true
        }
    }

    private fun createViewModel(fake: FakeRepository): DegreeViewModel =
        DegreeViewModel(
            repositoryFactory = { fake },
            gradeAttemptsProvider = { emptyList() },
            nowProvider = { LocalDate.of(2026, 3, 1) }
        )

    private suspend fun waitFor(condition: () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(10)
        }
    }

    @Test
    fun emptyRepositoryLeadsToEmptyState() = runBlocking {
        val fake = FakeRepository()
        val vm = createViewModel(fake)
        waitFor { vm.uiState.value.status == DegreeStatus.EMPTY }
        assertEquals(DegreeStatus.EMPTY, vm.uiState.value.status)
        vm.closeForSessionEnd()
        assertTrue(fake.closed)
    }

    @Test
    fun importFlowEndsInReadyState() = runBlocking {
        val fake = FakeRepository()
        fake.reportToReturn = DegreePreviewFixtures.reportDerived()
        val vm = createViewModel(fake)
        waitFor { vm.uiState.value.status == DegreeStatus.EMPTY }

        vm.onAction(DegreeUiAction.OpenImportDialog)
        assertTrue(vm.uiState.value.showImportDialog)
        vm.onAction(DegreeUiAction.ImportTextChanged("{}"))
        vm.onAction(DegreeUiAction.SubmitImport)
        waitFor { vm.uiState.value.status == DegreeStatus.READY }
        assertEquals(DegreeStatus.READY, vm.uiState.value.status)
        assertNotNull(vm.uiState.value.report)
        assertEquals(1, fake.imports.size)
        assertTrue(vm.uiState.value.notice.orEmpty().contains("已导入"))
        vm.closeForSessionEnd()
    }

    @Test
    fun emptyImportTextShowsError() = runBlocking {
        val fake = FakeRepository()
        val vm = createViewModel(fake)
        waitFor { vm.uiState.value.status == DegreeStatus.EMPTY }
        vm.onAction(DegreeUiAction.OpenImportDialog)
        vm.onAction(DegreeUiAction.SubmitImport)
        assertNotNull(vm.uiState.value.importError)
        vm.closeForSessionEnd()
    }

    @Test
    fun overrideRequiresReason() = runBlocking {
        val fake = FakeRepository()
        fake.reportToReturn = DegreePreviewFixtures.reportDerived()
        val vm = createViewModel(fake)
        waitFor { vm.uiState.value.status == DegreeStatus.EMPTY }
        vm.onAction(DegreeUiAction.OpenImportDialog)
        vm.onAction(DegreeUiAction.ImportTextChanged("{}"))
        vm.onAction(DegreeUiAction.SubmitImport)
        waitFor { vm.uiState.value.status == DegreeStatus.READY }

        vm.onAction(DegreeUiAction.OpenOverride("MATH1", "高等数学A"))
        assertNotNull(vm.uiState.value.overrideDraft)
        // 目标未选且依据为空：先选组不填依据
        vm.onAction(DegreeUiAction.OverrideTargetChanged("open", false))
        vm.onAction(DegreeUiAction.SubmitOverride)
        assertTrue(vm.uiState.value.notice.orEmpty().contains("依据"))
        assertEquals(0, fake.overrides.size)

        vm.onAction(DegreeUiAction.OverrideReasonChanged("教务处认定可计入"))
        vm.onAction(DegreeUiAction.SubmitOverride)
        waitFor { fake.overrides.size == 1 }
        assertNull(vm.uiState.value.overrideDraft)
        assertEquals("open", fake.overrides.first().mapToGroupId)
        vm.closeForSessionEnd()
    }

    @Test
    fun overrideOpenResolvesAttemptIdFromReport() = runBlocking {
        val fake = FakeRepository()
        fake.reportToReturn = DegreePreviewFixtures.reportDerived()
        val vm = createViewModel(fake)
        waitFor { vm.uiState.value.status == DegreeStatus.EMPTY }
        vm.onAction(DegreeUiAction.OpenImportDialog)
        vm.onAction(DegreeUiAction.ImportTextChanged("{}"))
        vm.onAction(DegreeUiAction.SubmitImport)
        waitFor { vm.uiState.value.status == DegreeStatus.READY }

        vm.onAction(DegreeUiAction.OpenOverride("MATH1", "高等数学A"))
        val draft = vm.uiState.value.overrideDraft
        assertNotNull(draft)
        assertEquals("at-math", draft!!.attemptId)

        vm.onAction(DegreeUiAction.OpenOverride("NOPE", "不存在"))
        assertTrue(vm.uiState.value.notice.orEmpty().contains("找不到"))
        vm.closeForSessionEnd()
    }

    @Test
    fun overrideOpenAcceptsDirectAttemptIdAndRetractIsRecorded() = runBlocking {
        val fake = FakeRepository()
        fake.reportToReturn = DegreePreviewFixtures.reportDerived()
        val vm = createViewModel(fake)
        waitFor { vm.uiState.value.status == DegreeStatus.EMPTY }
        vm.onAction(DegreeUiAction.OpenImportDialog)
        vm.onAction(DegreeUiAction.ImportTextChanged("{}"))
        vm.onAction(DegreeUiAction.SubmitImport)
        waitFor { vm.uiState.value.status == DegreeStatus.READY }

        vm.onAction(DegreeUiAction.OpenOverride("at-math", "高等数学A"))
        assertEquals("at-math", vm.uiState.value.overrideDraft?.attemptId)
        vm.onAction(DegreeUiAction.CloseOverride)

        vm.onAction(DegreeUiAction.RetractOverride("at-math", "高等数学A"))
        waitFor { fake.overrides.any { it.action == cn.edu.haut.campus.core.plan.OverrideAction.RETRACT } }
        val retract = fake.overrides.last()
        assertEquals("at-math", retract.attemptRef.id)
        assertTrue(retract.reason.isNotBlank())
        vm.closeForSessionEnd()
    }

}

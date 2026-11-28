package cn.edu.haut.campus.core.degree

import androidx.room.Room
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.plan.ImportInput
import cn.edu.haut.campus.core.plan.OverrideAction
import cn.edu.haut.campus.core.plan.PlanSource
import cn.edu.haut.campus.core.plan.Provenance
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * VaultDegreeRepository 端到端测试：导入、去重、确认、激活、人工认定进 journal、审计闭环。
 * 全部为合成数据（UNVERIFIED），不代表任何真实学校。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultDegreeRepositoryTest {

    private lateinit var database: HautDatabase
    private lateinit var repository: DegreeRepository
    private var now: Instant = Instant.parse("2026-03-01T08:00:00Z")

    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct_degree"),
        profileId = ProfileId("prof_degree")
    )

    private fun sampleJson(): String = """
        {
          "format": "univ-training-plan/v1",
          "planId": "demo-cs-2023",
          "title": "计算机示范方案",
          "version": "2023.1",
          "applicableGradeYears": [1, 2, 3, 4],
          "majors": ["计算机科学与技术"],
          "groups": [
            {
              "id": "basic", "title": "学科基础", "kind": "required", "requiredCredits": 9,
              "courses": [
                {"code": "MATH1", "name": "高等数学A"},
                {"code": "CS101", "name": "程序设计基础"}
              ]
            },
            {
              "id": "open", "title": "开放选修", "kind": "general", "requiredCredits": 2,
              "courses": []
            }
          ]
        }
    """.trimIndent()

    private fun attempt(
        courseKey: String,
        name: String,
        credits: String,
        id: String,
        passed: Boolean = true
    ) = GradeAttempt(
        id = id,
        termId = "2024-1",
        academicYear = "2024-2025",
        termNumber = 1,
        courseKey = courseKey,
        name = name,
        credits = BigDecimal(credits),
        rawGrade = "80",
        numericScore = BigDecimal("80"),
        gradePoint = BigDecimal("3.0"),
        attemptType = AttemptType.NORMAL,
        passed = passed,
        syncedAt = Instant.parse("2026-01-01T00:00:00Z")
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = DegreeRuntimeFactory.create(database, scope, clock = { now })
    }

    @After
    fun tearDown() {
        repository.close()
        database.close()
    }

    @Test
    fun importPlanPersistsAndAutoActivates() = runBlocking {
        val record = repository.importPlan(ImportInput.JsonDocument(sampleJson()), Actor.USER)
        assertTrue(record.importId.startsWith("tpl-"))
        assertEquals("demo-cs-2023", record.planId)
        assertEquals(PlanSource.UNKNOWN, record.source)
        val imports = repository.listImports()
        assertEquals(1, imports.size)
        assertTrue(imports.first().active)
        val plan = repository.loadPlan(record.importId)
        assertNotNull(plan)
        assertEquals(2, plan!!.allGroups().first { it.id == "basic" }.courses.size)
        // 重复导入同一内容：同一 importId，不产生第二条记录
        val again = repository.importPlan(ImportInput.JsonDocument(sampleJson()), Actor.USER)
        assertEquals(record.importId, again.importId)
        assertEquals(1, repository.listImports().size)
    }

    @Test
    fun confirmFlipsTrustInAudit() = runBlocking {
        val record = repository.importPlan(ImportInput.JsonDocument(sampleJson()), Actor.USER)
        val attempts = listOf(attempt("MATH1", "高等数学A", "5", "at-1"))
        val before = repository.auditActivePlan(attempts, LocalDate.of(2026, 3, 1))
        assertNotNull(before)
        assertEquals(Provenance.ESTIMATED, before!!.trust)

        repository.setConfirmedForStudent(record.importId, true, Actor.USER)
        val confirmedPlan = repository.loadPlan(record.importId)
        assertNotNull(confirmedPlan)
        assertTrue(confirmedPlan!!.confirmedForStudent)
        // 文件导入确认后为 USER_CONFIRMED，审计信任级别升为 DERIVED
        val after = repository.auditActivePlan(attempts, LocalDate.of(2026, 3, 1))
        assertNotNull(after)
        assertEquals(Provenance.DERIVED, after!!.trust)
    }

    @Test
    fun overrideIsJournaledAndAppliedToAudit() = runBlocking {
        repository.importPlan(ImportInput.JsonDocument(sampleJson()), Actor.USER)
        val journalBefore = database.vaultDao().countEntityJournal()
        val override = repository.recordOverride(
            OverrideInput(
                attemptId = "at-art",
                action = OverrideAction.ASSIGN,
                mapToGroupId = "open",
                reason = "教务处认定可计入开放选修"
            ),
            Actor.USER
        )
        assertTrue(override.id.startsWith("ovr-"))
        val journalAfter = database.vaultDao().countEntityJournal()
        assertTrue("人工认定必须写 journal", journalAfter > journalBefore)

        val attempts = listOf(attempt("ART101", "艺术鉴赏", "2", "at-art"))
        val report = repository.auditActivePlan(attempts, LocalDate.of(2026, 3, 1))
        assertNotNull(report)
        val open = report!!.groupById("open")!!
        assertEquals(0, BigDecimal("2").compareTo(open.earnedCredits))
        assertTrue(report.trace.any { it.rule == "override-assign" })

        val overrides = repository.listOverrides()
        assertEquals(1, overrides.size)
        assertEquals("教务处认定可计入开放选修", overrides.first().reason)
    }

    @Test
    fun retakeCreditsCountedOnceEndToEnd() = runBlocking {
        repository.importPlan(ImportInput.JsonDocument(sampleJson()), Actor.USER)
        val attempts = listOf(
            attempt("MATH1", "高等数学A", "5", "at-r1", passed = false),
            attempt("MATH1", "高等数学A", "5", "at-r2", passed = true)
        )
        val report = repository.auditActivePlan(attempts, LocalDate.of(2026, 3, 1))
        assertNotNull(report)
        val basic = report!!.groupById("basic")!!
        assertEquals(0, BigDecimal("5").compareTo(basic.earnedCredits))
        assertEquals(0, BigDecimal("5").compareTo(report.totalEarnedCredits))
    }

    @Test
    fun noActivePlanMeansNoReport() = runBlocking {
        val report = repository.auditActivePlan(emptyList(), LocalDate.of(2026, 3, 1))
        assertNull(report)
    }

    @Test
    fun blobRowsAreScopedToProfile() = runBlocking {
        repository.importPlan(ImportInput.JsonDocument(sampleJson()), Actor.USER)
        repository.recordOverride(
            OverrideInput("at-x", OverrideAction.EXCLUDE, null, "测试排除"),
            Actor.USER
        )
        val rows = database.vaultDao().getBlobsByOwnerType(
            schoolId = scope.schoolId.value,
            accountId = scope.accountId.value,
            profileId = scope.profileId.value,
            ownerEntityType = "TRAINING_PLAN_IMPORT"
        )
        assertTrue(rows.isNotEmpty())
        rows.forEach { row ->
            assertEquals(scope.schoolId.value, row.scopeSchoolId)
            assertEquals(scope.accountId.value, row.scopeAccountId)
            assertEquals(scope.profileId.value, row.scopeProfileId)
        }
    }
}

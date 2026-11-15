package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cn.edu.haut.campus.core.model.ExamScheduleItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/**
 * 考试学期修复 V2 回归：
 * - 有权威来源（originTermId）且 termId 被篡改的行 → 修复并逐条审计；
 * - 无来源旧行：有同步证据 → 不动；污染签名/非法学期 → 保留并标记待核对；
 * - 绝不凭显示日期猜学期；V1 标志不阻止 V2 核查；重跑与崩溃恢复幂等。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ExamTermRepairTest {
    private lateinit var fixture: RepairTestFixture
    private val dao get() = fixture.dao
    private val repo get() = fixture.repository

    private val poisonTerm = "2026-2027-1"
    private val springTerm = "2025-2026-2"

    private fun exam(
        id: String,
        termId: String,
        courseCode: String = "CS101",
        courseName: String = "合成课程",
        examDateIso: String? = "2026-06-20",
        originTermId: String? = null,
        seatNumber: String? = null,
        rawRoom: String = "1号教学楼1204",
        examBatchName: String = "期末考试",
        examStageLabel: String = "期末集中考",
        examFormat: String = "闭卷",
        startTimeIso: String? = "09:00",
        endTimeIso: String? = "11:00",
        isRetake: Boolean = false,
        syncedAt: Long = 1_700_000_000_000L
    ) = ExamScheduleEntity(
        id = id,
        termId = termId,
        courseCode = courseCode,
        courseName = courseName,
        examBatchName = examBatchName,
        examStageLabel = examStageLabel,
        examFormat = examFormat,
        rawTimeText = "2026-06-20 09:00-11:00",
        examDateIso = examDateIso,
        startTimeIso = startTimeIso,
        endTimeIso = endTimeIso,
        rawRoom = rawRoom,
        seatNumber = seatNumber,
        campusName = "莲花街校区",
        isRetake = isRetake,
        syncedAtEpochMilli = syncedAt,
        originTermId = originTermId
    )

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_exam_${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private suspend fun audits() = dao.getExamTermRepairAuditsSnapshot()
    private suspend fun exams() = dao.getExamSchedulesSnapshot()

    @Test
    fun `V2 凭 originTermId 权威来源修复被错标学期的行并写审计`() = runBlocking {
        // 可信管线写入 originTermId=2025-2026-2，termId 后被错改为 2026-2027-1
        dao.upsertExamSchedules(listOf(exam("ex_1", poisonTerm, originTermId = springTerm)))
        repo.ensureExamTermRepairV2IfNeeded()
        val row = exams().single { it.id == "ex_1" }
        assertEquals(springTerm, row.termId)
        // originTermId 保持不变，作为永久权威证据
        assertEquals(springTerm, row.originTermId)
        val audit = audits().single { it.examRowId == "ex_1" }
        assertEquals(ExamTermRepairAuditEntity.DISPOSITION_REPAIRED, audit.disposition)
        assertEquals(poisonTerm, audit.previousTermId)
        assertEquals(springTerm, audit.newTermId)
        assertEquals("originTermId", audit.evidenceRef)
        assertEquals(CampusRepository.EXAM_TERM_REPAIR_RULE_V2, audit.ruleVersion)
    }

    @Test
    fun `V2 重跑幂等不产生重复审计或二次修改`() = runBlocking {
        dao.upsertExamSchedules(listOf(
            exam("ex_rep", poisonTerm, originTermId = springTerm),
            exam("ex_ok", springTerm, originTermId = springTerm)
        ))
        repo.ensureExamTermRepairV2IfNeeded()
        val firstExams = exams()
        val firstAudits = audits()
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(firstExams, exams())
        assertEquals(firstAudits, audits())
        assertEquals(1, audits().size)
    }

    @Test
    fun `旧 V1 标志为 true 不阻止 V2 核查与修复`() = runBlocking {
        fixture.dataStore.edit { it[booleanPreferencesKey("exam_term_poison_repaired_v1")] = true }
        dao.upsertExamSchedules(listOf(exam("ex_v1flag", poisonTerm, originTermId = springTerm)))
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(springTerm, exams().single().termId)
        assertEquals(1, audits().size)
    }

    @Test
    fun `无来源旧行在污染学期且无证据时保留并标记待核对绝不盲目反改`() = runBlocking {
        // 一月考试不凭日期硬归春季：日期落在 2027-01（真实秋季期末考窗口）
        dao.upsertExamSchedules(listOf(
            exam("ex_jan", poisonTerm, examDateIso = "2027-01-12", originTermId = null),
            exam("ex_nodate", poisonTerm, examDateIso = null, originTermId = null)
        ))
        repo.ensureExamTermRepairV2IfNeeded()
        // 行原样保留（V2 不按显示日期猜学期）
        assertEquals(2, exams().size)
        assertTrue(exams().all { it.termId == poisonTerm })
        val auditRows = audits()
        assertEquals(2, auditRows.size)
        assertTrue(auditRows.all { it.disposition == ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED })
        assertTrue(auditRows.all { it.newTermId == null })
        assertTrue(auditRows.all { it.evidenceRef == "missing_provenance" })
    }

    @Test
    fun `旧行在已同步或有导入证据的学期保持不变`() = runBlocking {
        fixture.dataStore.edit {
            it[stringPreferencesKey("saved_jw_sync_xnm")] = "2026"
            it[stringPreferencesKey("saved_jw_sync_xqm")] = "3"
        }
        dao.insertImportAudit(
            ImportAuditEntity(
                id = "aud_1", termId = "2024-2025-1", importSource = "OFFICIAL_JW",
                importedAtEpochMilli = 1L, rowCount = 1, conflictCount = 0, outcome = "导入"
            )
        )
        dao.upsertExamSchedules(listOf(
            exam("ex_evidenced", poisonTerm, originTermId = null),   // 2026-2027-1 = savedSyncTermId
            exam("ex_audit", "2024-2025-1", originTermId = null)      // 有导入审计
        ))
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(2, exams().size)
        assertTrue(audits().isEmpty())
    }

    @Test
    fun `非法学期格式一律标记待核对不修复`() = runBlocking {
        dao.upsertExamSchedules(listOf(
            exam("ex_bad", "unknown-term", originTermId = null)
        ))
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals("unknown-term", exams().single().termId)
        assertEquals(ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED, audits().single().disposition)
    }

    @Test
    fun `目标学期已有同指纹考试时错标行合并去重不产生重复考试`() = runBlocking {
        val correct = exam("ex_correct", springTerm, originTermId = springTerm)
        val stale = exam("ex_stale", poisonTerm, originTermId = springTerm)
        dao.upsertExamSchedules(listOf(correct, stale))
        repo.ensureExamTermRepairV2IfNeeded()
        val rows = exams()
        assertEquals(1, rows.size)
        assertEquals("ex_correct", rows.single().id)
        val audit = audits().single { it.examRowId == "ex_stale" }
        assertEquals(ExamTermRepairAuditEntity.DISPOSITION_MERGED_DUPLICATE, audit.disposition)
        assertEquals(poisonTerm, audit.previousTermId)
        assertEquals(springTerm, audit.newTermId)
    }

    private fun trustedExamId(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(seed.toByteArray(Charsets.UTF_8))
        return "exam_" + digest.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `权威重同步清除同指纹旧错标行并审计`() = runBlocking {
        dao.upsertExamSchedules(listOf(exam("ex_legacy", poisonTerm, originTermId = null)))
        val newId = trustedExamId("ex_new")
        repo.saveImportedExams(
            exams = listOf(
                ExamScheduleItem(
                    id = newId, termId = springTerm, courseCode = "CS101",
                    courseName = "合成课程", examBatchName = "期末考试",
                    examStageLabel = "期末集中考", examFormat = "闭卷",
                    rawTimeText = "2026-06-20 09:00-11:00",
                    examDate = LocalDate.of(2026, 6, 20),
                    rawRoom = "1号教学楼1204", seatNumber = null,
                    startTime = java.time.LocalTime.of(9, 0),
                    endTime = java.time.LocalTime.of(11, 0)
                )
            ),
            replaceExisting = true,
            targetTermId = springTerm
        )
        val rows = exams()
        assertEquals(1, rows.size)
        assertEquals(newId, rows.single().id)
        assertEquals(springTerm, rows.single().originTermId)
        val audit = audits().single { it.examRowId == "ex_legacy" }
        assertEquals(ExamTermRepairAuditEntity.DISPOSITION_MERGED_DUPLICATE, audit.disposition)
    }

    @Test
    fun `待核对行经权威重同步修正后审计标记为已解决`() = runBlocking {
        dao.upsertExamSchedules(listOf(exam("ex_review", poisonTerm, originTermId = null)))
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(
            ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED,
            audits().single().disposition
        )
        // 权威重同步：该行被写入正确学期并带来源
        dao.upsertExamSchedules(listOf(exam("ex_review", springTerm, originTermId = springTerm)))
        repo.ensureExamTermRepairV2IfNeeded()
        val audit = audits().single { it.examRowId == "ex_review" }
        assertEquals(ExamTermRepairAuditEntity.DISPOSITION_RESOLVED_BY_RESYNC, audit.disposition)
        assertEquals(springTerm, audit.newTermId)
        assertEquals(springTerm, exams().single().termId)
    }

    @Test
    fun `崩溃恢复安全 修复审计同事务无中间态且可安全重跑`() = runBlocking {
        // 模拟进程在「DataStore 无门控」语义下崩溃：DB 审计即事实来源，重跑不重复
        dao.upsertExamSchedules(listOf(
            exam("ex_crash", poisonTerm, originTermId = springTerm),
            exam("ex_review2", poisonTerm, originTermId = null)
        ))
        repo.ensureExamTermRepairV2IfNeeded()
        val afterFirst = Pair(exams(), audits())
        // 再次启动重跑：结果完全一致（行不重复修、审计不重复写）
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(afterFirst, Pair(exams(), audits()))
        assertEquals(2, audits().size)
        assertEquals(
            setOf(
                ExamTermRepairAuditEntity.DISPOSITION_REPAIRED,
                ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED
            ),
            audits().map { it.disposition }.toSet()
        )
    }

    @Test
    fun `来源正确的行不产生审计也不被修改`() = runBlocking {
        val good = exam("ex_good", springTerm, originTermId = springTerm)
        dao.upsertExamSchedules(listOf(good))
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(listOf(good), exams())
        assertTrue(audits().isEmpty())
    }

    @Test
    fun `同 sourceKey 的不同学期考试互不串数据`() = runBlocking {
        dao.upsertExamSchedules(listOf(
            exam("ex_t1", "2025-2026-1", courseCode = "K1", originTermId = "2025-2026-1"),
            exam("ex_t2", poisonTerm, courseCode = "K1", originTermId = poisonTerm)
        ))
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(2, exams().size)
        assertTrue(audits().isEmpty())
    }

    // ---------- Round-2 A2：init 不再运行 V1 日期猜测 ----------

    @Test
    fun `完整初始化不再按日期猜学期 无来源一月行保留原学期并记 REVIEW_REQUIRED`() = runBlocking {
        // schema8 形态旧库行：无 originTermId 证明；日期落在 2026-01-12（V1 曾猜归 2025-2026-2）
        dao.upsertExamSchedules(listOf(
            exam("ex_jan_init", poisonTerm, examDateIso = "2026-01-12", originTermId = null)
        ))
        repo.ensureInitialSeedIfNeeded()
        val row = exams().single { it.id == "ex_jan_init" }
        assertEquals(poisonTerm, row.termId)
        assertNull(row.originTermId)
        val audit = audits().single { it.examRowId == "ex_jan_init" }
        assertEquals(ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED, audit.disposition)
        // 重复启动幂等：行与审计完全一致
        val firstState = Pair(exams(), audits())
        repo.ensureInitialSeedIfNeeded()
        assertEquals(firstState, Pair(exams(), audits()))
        assertEquals(1, audits().size)
    }

    @Test
    fun `旧 V1 标志为 true 初始化仍不按日期猜学期`() = runBlocking {
        fixture.dataStore.edit { it[booleanPreferencesKey("exam_term_poison_repaired_v1")] = true }
        dao.upsertExamSchedules(listOf(
            exam("ex_v1flag_init", poisonTerm, examDateIso = "2026-01-12", originTermId = null)
        ))
        repo.ensureInitialSeedIfNeeded()
        assertEquals(poisonTerm, exams().single { it.id == "ex_v1flag_init" }.termId)
        assertEquals(
            ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED,
            audits().single { it.examRowId == "ex_v1flag_init" }.disposition
        )
    }

    @Test
    fun `legacy 账号初始化分支同样不运行 V1 日期猜测`() = runBlocking {
        val legacy = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(), "prof_exam_legacy_${System.nanoTime()}", isLegacy = true
        )
        try {
            legacy.dao.upsertExamSchedules(listOf(
                exam("ex_legacy_jan", poisonTerm, examDateIso = "2026-01-12", originTermId = null)
            ))
            legacy.repository.ensureInitialSeedIfNeeded()
            assertEquals(poisonTerm, legacy.dao.getExamSchedulesSnapshot().single { it.id == "ex_legacy_jan" }.termId)
            assertEquals(
                ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED,
                legacy.dao.getExamTermRepairAuditsSnapshot().single { it.examRowId == "ex_legacy_jan" }.disposition
            )
        } finally {
            legacy.close()
        }
    }

    @Test
    fun `已被 V1 误改学期的行绝不盲目反改`() = runBlocking {
        // 该行表现如同 V1 已把 2026-01-12 考试改写进 2025-2026-2：无证据，禁止再按日期反猜
        dao.upsertExamSchedules(listOf(
            exam("ex_v1done", springTerm, examDateIso = "2026-01-12", originTermId = null)
        ))
        repo.ensureInitialSeedIfNeeded()
        val row = exams().single { it.id == "ex_v1done" }
        assertEquals(springTerm, row.termId)
        // 学期格式合法且非已知污染签名 → 不产生审计，需权威重同步纠正
        assertTrue(audits().isEmpty())
    }

    @Test
    fun `多账号初始化各自独立完成修复互不干扰`() = runBlocking {
        val fixtureB = RepairTestFixture.create(
            RuntimeEnvironment.getApplication(), "prof_exam_b_${System.nanoTime()}"
        )
        try {
            dao.upsertExamSchedules(listOf(exam("ex_a", poisonTerm, examDateIso = "2026-01-12", originTermId = null)))
            fixtureB.dao.upsertExamSchedules(listOf(exam("ex_b", poisonTerm, examDateIso = "2026-01-13", originTermId = null)))
            repo.ensureInitialSeedIfNeeded()
            fixtureB.repository.ensureInitialSeedIfNeeded()
            assertEquals(poisonTerm, exams().single { it.id == "ex_a" }.termId)
            assertEquals(poisonTerm, fixtureB.dao.getExamSchedulesSnapshot().single { it.id == "ex_b" }.termId)
            assertEquals(1, audits().size)
            assertEquals(1, fixtureB.dao.getExamTermRepairAuditsSnapshot().size)
            assertTrue(fixtureB.dao.getExamSchedulesSnapshot().none { it.id == "ex_a" })
        } finally {
            fixtureB.close()
        }
    }

    // ---------- Round-2 C：证据收紧 ----------

    @Test
    fun `学期级证据不再解除 REVIEW_REQUIRED 待核对维持到行自身证据出现`() = runBlocking {
        dao.upsertExamSchedules(listOf(exam("ex_hold", poisonTerm, originTermId = null)))
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(
            ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED,
            audits().single { it.examRowId == "ex_hold" }.disposition
        )
        // 学期级证据出现：该学期被保存为同步学期 + 存在该学期的课表导入审计
        fixture.dataStore.edit {
            it[stringPreferencesKey("saved_jw_sync_xnm")] = "2026"
            it[stringPreferencesKey("saved_jw_sync_xqm")] = "3"
        }
        dao.insertImportAudit(
            ImportAuditEntity(
                id = "aud_term_only", termId = poisonTerm, importSource = "OFFICIAL_JW",
                importedAtEpochMilli = 1L, rowCount = 20, conflictCount = 0, outcome = "导入"
            )
        )
        repo.ensureExamTermRepairV2IfNeeded()
        // 行自身仍无来源证明 → 维持待核对，绝不因学期级证据翻转
        assertEquals(poisonTerm, exams().single { it.id == "ex_hold" }.termId)
        val audit = audits().single { it.examRowId == "ex_hold" }
        assertEquals(ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED, audit.disposition)
        assertNull(audit.newTermId)

        // 行自身证据出现（权威重同步落库带来 originTermId）→ 才真正解决
        dao.upsertExamSchedules(listOf(exam("ex_hold", poisonTerm, originTermId = poisonTerm)))
        repo.ensureExamTermRepairV2IfNeeded()
        assertEquals(
            ExamTermRepairAuditEntity.DISPOSITION_RESOLVED_BY_RESYNC,
            audits().single { it.examRowId == "ex_hold" }.disposition
        )
    }

    @Test
    fun `时间待定的跨学期同指纹考试不自动删除旧行 保留两行并标记待核对`() = runBlocking {
        // 同一门课在两个学期各有「时间待定」考试：字段全等撞指纹，但属于不同场次，禁止自动删除
        dao.upsertExamSchedules(listOf(
            exam(
                "ex_pending_old", springTerm,
                examDateIso = null, startTimeIso = null, endTimeIso = null,
                originTermId = null
            )
        ))
        val newId = trustedExamId("pending_new")
        repo.saveImportedExams(
            exams = listOf(
                ExamScheduleItem(
                    id = newId, termId = poisonTerm, courseCode = "CS101",
                    courseName = "合成课程", examBatchName = "期末考试",
                    examStageLabel = "期末集中考", examFormat = "闭卷",
                    rawTimeText = "时间待定",
                    examDate = null, startTime = null, endTime = null,
                    rawRoom = "1号教学楼1204", seatNumber = null
                )
            ),
            replaceExisting = true,
            targetTermId = poisonTerm
        )
        val rows = exams()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.id == "ex_pending_old" && it.termId == springTerm })
        assertTrue(rows.any { it.id == newId && it.termId == poisonTerm })
        val audit = audits().single { it.examRowId == "ex_pending_old" }
        assertEquals(ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED, audit.disposition)
        assertNull(audit.newTermId)
        assertTrue(audit.evidenceRef.contains("insufficient_session_identity"))
    }

    @Test
    fun `明确日期但来源 id 非可信形态的同指纹考试不误删旧行`() = runBlocking {
        dao.upsertExamSchedules(listOf(exam("ex_stale2", springTerm, originTermId = null)))
        repo.saveImportedExams(
            exams = listOf(
                ExamScheduleItem(
                    id = "ex_handcrafted", termId = poisonTerm, courseCode = "CS101",
                    courseName = "合成课程", examBatchName = "期末考试",
                    examStageLabel = "期末集中考", examFormat = "闭卷",
                    rawTimeText = "2026-06-20 09:00-11:00",
                    examDate = LocalDate.of(2026, 6, 20),
                    rawRoom = "1号教学楼1204", seatNumber = null,
                    startTime = java.time.LocalTime.of(9, 0),
                    endTime = java.time.LocalTime.of(11, 0)
                )
            ),
            replaceExisting = true,
            targetTermId = poisonTerm
        )
        val rows = exams()
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.id == "ex_stale2" })
        assertEquals(
            ExamTermRepairAuditEntity.DISPOSITION_REVIEW_REQUIRED,
            audits().single { it.examRowId == "ex_stale2" }.disposition
        )
    }

    @Test
    fun `指纹去重标记幂等 重复同步不重复写审计`() = runBlocking {
        dao.upsertExamSchedules(listOf(
            exam(
                "ex_pending_rep", springTerm,
                examDateIso = null, startTimeIso = null, endTimeIso = null,
                originTermId = null
            )
        ))
        val item = ExamScheduleItem(
            id = trustedExamId("pending_rep"), termId = poisonTerm, courseCode = "CS101",
            courseName = "合成课程", examBatchName = "期末考试",
            examStageLabel = "期末集中考", examFormat = "闭卷",
            rawTimeText = "时间待定",
            examDate = null, startTime = null, endTime = null,
            rawRoom = "1号教学楼1204", seatNumber = null
        )
        repo.saveImportedExams(listOf(item), replaceExisting = true, targetTermId = poisonTerm)
        val firstAudits = audits()
        assertEquals(1, firstAudits.size)
        repo.saveImportedExams(listOf(item), replaceExisting = true, targetTermId = poisonTerm)
        assertEquals(firstAudits, audits())
    }
}

package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.GpaSourceType
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class GpaAndBuildingResolverTest {

    @Test
    fun `GPA calculator computes exact weighted average from JW course grade points and deduplicates retakes`() {
        val t1 = Instant.parse("2026-01-15T10:00:00Z")
        val t2 = Instant.parse("2026-07-01T10:00:00Z")

        val attempts = listOf(
            // 高等数学：首次 55 分(0绩点)，重修 85 分(3.5绩点)，4 学分 -> 应取重修 3.5，学分不重复累加
            GradeAttempt(
                id = "g1_fail",
                termId = "2025-2026-1",
                academicYear = "2025-2026",
                termNumber = 1,
                courseKey = "MATH101",
                name = "高等数学A(1)",
                category = CourseCategory.COMPULSORY,
                credits = BigDecimal("4.0"),
                rawGrade = "55",
                numericScore = BigDecimal("55"),
                gradePoint = BigDecimal("0.0"),
                attemptType = AttemptType.NORMAL,
                passed = false,
                syncedAt = t1
            ),
            GradeAttempt(
                id = "g1_retake",
                termId = "2025-2026-2",
                academicYear = "2025-2026",
                termNumber = 2,
                courseKey = "MATH101",
                name = "高等数学A(1)",
                category = CourseCategory.COMPULSORY,
                credits = BigDecimal("4.0"),
                rawGrade = "85",
                numericScore = BigDecimal("85"),
                gradePoint = BigDecimal("3.5"),
                attemptType = AttemptType.RETAKE,
                passed = true,
                syncedAt = t2
            ),
            // 数据结构：92 分，绩点 4.2，4 学分
            GradeAttempt(
                id = "g2",
                termId = "2025-2026-2",
                academicYear = "2025-2026",
                termNumber = 2,
                courseKey = "CS201",
                name = "数据结构",
                category = CourseCategory.COMPULSORY,
                credits = BigDecimal("4.0"),
                rawGrade = "92",
                numericScore = BigDecimal("92"),
                gradePoint = BigDecimal("4.2"),
                attemptType = AttemptType.NORMAL,
                passed = true,
                syncedAt = t2
            ),
            // 0学分形势政策：不计入分母
            GradeAttempt(
                id = "g3_zero",
                termId = "2025-2026-2",
                academicYear = "2025-2026",
                termNumber = 2,
                courseKey = "POL001",
                name = "公益劳动",
                category = CourseCategory.PRACTICE,
                credits = BigDecimal("0.0"),
                rawGrade = "优秀",
                gradePoint = BigDecimal("4.5"),
                attemptType = AttemptType.NORMAL,
                passed = true,
                syncedAt = t2
            )
        )

        val report = GpaCalculator.calculateReport(
            attempts = attempts,
            currentTermId = "2025-2026-2",
            currentAcademicYear = "2025-2026"
        )

        // (4.0 * 3.5 + 4.0 * 4.2) / 8.0 = 30.8 / 8.0 = 3.85
        assertEquals(GpaSourceType.LOCAL_WEIGHTED_FROM_JW_POINTS, report.primarySourceType)
        assertEquals(BigDecimal("3.85"), report.cumulativeGpa)
        assertEquals(BigDecimal("8.0"), report.totalEarnedCredits)
        assertEquals(BigDecimal("8.0"), report.gpaIncludedCredits)
    }

    @Test
    fun `GPA calculator refuses to guess when gradePoint is missing and no official policy is configured`() {
        val attempts = listOf(
            GradeAttempt(
                id = "g_raw_only",
                termId = "2025-2026-1",
                academicYear = "2025-2026",
                termNumber = 1,
                courseKey = "CS101",
                name = "程序设计基础",
                credits = BigDecimal("3.0"),
                rawGrade = "88",
                numericScore = BigDecimal("88"),
                gradePoint = null, // 缺少教务返回的课程级绩点
                syncedAt = Instant.now()
            )
        )

        val report = GpaCalculator.calculateReport(
            attempts = attempts,
            currentTermId = "2025-2026-1",
            currentAcademicYear = "2025-2026"
        )

        assertEquals(GpaSourceType.INSUFFICIENT_RULE, report.primarySourceType)
        assertNull(report.cumulativeGpa)
        assertTrue(report.diagnosticNotes.any { it.contains("国际学生 5.0 规定不适用于普通本科") })
    }

    @Test
    fun `GPA calculator prioritizes official JW snapshot when available`() {
        val attempts = listOf(
            GradeAttempt(
                id = "g1",
                termId = "2025-2026-1",
                academicYear = "2025-2026",
                termNumber = 1,
                courseKey = "CS101",
                name = "程序设计基础",
                credits = BigDecimal("3.0"),
                rawGrade = "88",
                numericScore = BigDecimal("88"),
                gradePoint = BigDecimal("3.7"),
                syncedAt = Instant.now()
            )
        )
        val official = OfficialGpaSnapshot(
            cumulativeGpa = BigDecimal("3.68"),
            termGpas = mapOf("2025-2026-1" to BigDecimal("3.68")),
            fetchedAt = Instant.now(),
            fieldDefinitionNote = "正方教务系统 xscjcx 汇总平均学分绩点"
        )

        val report = GpaCalculator.calculateReport(
            attempts = attempts,
            currentTermId = "2025-2026-1",
            currentAcademicYear = "2025-2026",
            officialSnapshot = official
        )

        assertEquals(GpaSourceType.OFFICIAL_JW, report.primarySourceType)
        assertEquals(BigDecimal("3.68"), report.cumulativeGpa)
    }

    @Test
    fun `ClassroomBuildingResolver distinguishes verified exact match from fuzzy match requiring confirmation`() {
        val buildings = listOf(
            Building(
                id = "b_1",
                name = "1号教学楼",
                shortName = "1教",
                latitude = 34.8321,
                longitude = 113.5482,
                category = CampusCategory.TEACHING_BUILDING,
                entrances = listOf("南门主入口", "东侧连廊"),
                description = "莲花街校区1号公共教学楼",
                verifiedAt = LocalDate.of(2026, 10, 1)
            ),
            Building(
                id = "b_3",
                name = "3号教学楼",
                shortName = "3教",
                latitude = 34.8330,
                longitude = 113.5495,
                category = CampusCategory.TEACHING_BUILDING,
                entrances = listOf("西门入口"),
                description = "莲花街校区3号教学楼",
                verifiedAt = LocalDate.of(2026, 10, 1)
            )
        )
        val aliases = listOf(
            BuildingAlias("a1", "b_1", "1号教学楼", 1.0f, true),
            BuildingAlias("a2", "b_1", "莲花街1教", 0.95f, true),
            BuildingAlias("a3", "b_3", "A座", 0.65f, false) // 未核验低置信度
        )

        val exact = ClassroomBuildingResolver.resolve("莲花街校区 1号教学楼1104", buildings, aliases)
        assertEquals(BuildingMatchStatus.EXACT_VERIFIED, exact.status)
        assertEquals("b_1", exact.building?.id)

        val fuzzyAlias = ClassroomBuildingResolver.resolve("A座204", buildings, aliases)
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, fuzzyAlias.status)
        assertEquals("b_3", fuzzyAlias.building?.id)

        val fuzzyFourDigit = ClassroomBuildingResolver.resolve("3206", buildings, aliases)
        assertEquals(BuildingMatchStatus.FUZZY_NEEDS_CONFIRMATION, fuzzyFourDigit.status)
        assertEquals("b_3", fuzzyFourDigit.building?.id)
    }
}

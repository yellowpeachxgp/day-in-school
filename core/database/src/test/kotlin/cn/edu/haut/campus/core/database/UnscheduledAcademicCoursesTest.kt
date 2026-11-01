package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.academic.UnscheduledAcademicCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class UnscheduledAcademicCoursesTest {
    private val course = UnscheduledAcademicCourse(
        sourceId = "synthetic-course", name = "合成实践课程", teacher = null,
        credit = BigDecimal("2.50"), rawWeeks = "第 11–12 周", category = "集中实践"
    )

    @Test
    fun `待排课程保留原始信息且标识不依赖数组顺序`() {
        val now = Instant.parse("2026-11-02T00:00:00Z")
        val second = course.copy(sourceId = null, name = "另一门合成实践")
        val first = listOf(course, second).toUnscheduledEntities("term-a", now)
        val reordered = listOf(second, course).toUnscheduledEntities("term-a", now)
        assertEquals(first.toSet(), reordered.toSet())
        assertEquals(course, first.first().toDomain())
        assertNotEquals(first.first().sourceKey, first.last().sourceKey)
        assertEquals("term-a", first.first().termId)
    }

    @Test
    fun `同一课程分段实践独立保存而完全重复行去重`() {
        val anotherWeek = course.copy(rawWeeks = "第 15 周")
        val entities = listOf(course, course, anotherWeek).toUnscheduledEntities("term-a")
        assertEquals(2, entities.size)
    }

    @Test
    fun `身份相同但内容冲突的待排课不能静默覆盖`() {
        val conflict = course.copy(credit = BigDecimal("9"))
        assertTrue(runCatching { listOf(course, conflict).toUnscheduledEntities("term-a") }.isFailure)
    }
}

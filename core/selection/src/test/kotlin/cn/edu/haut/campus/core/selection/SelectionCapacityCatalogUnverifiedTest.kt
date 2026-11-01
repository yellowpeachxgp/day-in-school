package cn.edu.haut.campus.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * UNVERIFIED：课程与容量数字均为合成数据，用于验证“容量未知不得显示约满/有位”的展示规则，
 * 不代表任何真实学校的教学班容量。
 */
class SelectionCapacityCatalogUnverifiedTest {

    private fun teachingClass(
        id: String = "class-1",
        capacity: ClassCapacity = ClassCapacity.Unknown,
        offeringId: String = "off-1"
    ): TeachingClass = TeachingClass(
        id = id,
        offeringId = offeringId,
        name = "教学班 " + id + "（合成）",
        capacity = capacity,
        teachers = listOf("教师甲（合成）"),
        slots = listOf(ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16), "A101"))
    )

    @Test
    fun `容量未知时不得显示约满或有余位`() {
        val unknown = teachingClass(capacity = ClassCapacity.Unknown)
        assertEquals(CapacityVerdict.UNKNOWN, unknown.capacityVerdict)
        assertEquals("容量未知", unknown.capacityText)
        for (forbidden in listOf("约满", "有位", "已满", "剩余")) {
            assertFalse(
                "容量未知时禁止出现『" + forbidden + "』（UNVERIFIED 合成数据）",
                unknown.capacityText.contains(forbidden)
            )
        }
    }

    @Test
    fun `已知满员的文案与判定`() {
        val full = teachingClass(capacity = ClassCapacity.Known(total = 60, selected = 60))
        assertEquals(CapacityVerdict.FULL, full.capacityVerdict)
        assertEquals(0, (full.capacity as ClassCapacity.Known).remaining)
        assertTrue(full.capacityText.contains("已满"))
        assertFalse(full.capacityText.contains("剩余"))
    }

    @Test
    fun `超选时剩余名额归零并标记超额`() {
        val overbooked = ClassCapacity.Known(total = 40, selected = 45)
        assertEquals(0, overbooked.remaining)
        assertTrue(overbooked.isOverbooked)
        assertTrue(overbooked.isFull)
    }

    @Test
    fun `已知有余位时显示剩余名额`() {
        val available = teachingClass(capacity = ClassCapacity.Known(total = 60, selected = 30))
        assertEquals(CapacityVerdict.AVAILABLE, available.capacityVerdict)
        assertTrue(available.capacityText.contains("剩余 30"))
        assertTrue(available.capacityText.contains("30 / 共 60"))
    }

    @Test
    fun `可选教学班排除满员但保留容量未知`() {
        val offering = CourseOffering(
            id = "off-1",
            name = "高等数学（合成）",
            termId = SelectionFixtures.TERM,
            roundId = SelectionFixtures.OPEN_ROUND.id,
            courseCode = "MATH1001",
            credits = BigDecimal("4.0"),
            classes = listOf(
                teachingClass(id = "class-full", capacity = ClassCapacity.Known(60, 60)),
                teachingClass(id = "class-unknown", capacity = ClassCapacity.Unknown),
                teachingClass(id = "class-open", capacity = ClassCapacity.Known(60, 10))
            )
        )
        val selectable = offering.selectableClasses()
        assertEquals(listOf("class-unknown", "class-open"), selectable.map { it.id })
        assertTrue("容量未知不能因为缺数据被当成满员", selectable.any { it.id == "class-unknown" })
    }

    @Test
    fun `课程通过 ID 查找教学班`() {
        val offering = CourseOffering(
            id = "off-1",
            name = "高等数学（合成）",
            termId = SelectionFixtures.TERM,
            roundId = SelectionFixtures.OPEN_ROUND.id,
            classes = listOf(teachingClass(id = "class-1"), teachingClass(id = "class-2"))
        )
        assertEquals("class-2", offering.classById("class-2")?.id)
        assertNull("不存在的教学班必须返回 null 而不是猜测", offering.classById("class-x"))
    }

    @Test
    fun `重复教学班 ID 被拒绝`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CourseOffering(
                id = "off-1",
                name = "高等数学（合成）",
                termId = SelectionFixtures.TERM,
                roundId = SelectionFixtures.OPEN_ROUND.id,
                classes = listOf(teachingClass(id = "class-1"), teachingClass(id = "class-1"))
            )
        }
        assertTrue(error.message!!.contains("重复教学班"))
    }

    @Test
    fun `教学班课程归属不一致被拒绝`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            CourseOffering(
                id = "off-1",
                name = "高等数学（合成）",
                termId = SelectionFixtures.TERM,
                roundId = SelectionFixtures.OPEN_ROUND.id,
                classes = listOf(TeachingClass(id = "class-1", offeringId = "off-2", name = "别的教学班（合成）"))
            )
        }
        assertTrue(error.message!!.contains("课程归属"))
    }

    @Test
    fun `负数容量被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { ClassCapacity.Known(total = -1, selected = 0) }
        assertThrows(IllegalArgumentException::class.java) { ClassCapacity.Known(total = 10, selected = -1) }
    }

    @Test
    fun `课程必须绑定学期与轮次`() {
        assertThrows(IllegalArgumentException::class.java) {
            CourseOffering(id = "off-1", name = "高等数学（合成）", termId = " ", roundId = "round-1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CourseOffering(id = "off-1", name = "高等数学（合成）", termId = SelectionFixtures.TERM, roundId = "")
        }
    }
}

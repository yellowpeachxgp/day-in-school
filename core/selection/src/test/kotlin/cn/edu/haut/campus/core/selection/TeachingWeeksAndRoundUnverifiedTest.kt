package cn.edu.haut.campus.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * UNVERIFIED：周次文本、节次与轮次时间都是合成数据，只验证判定逻辑，
 * 不代表任何真实学校的选课规则。
 */
class TeachingWeeksAndRoundUnverifiedTest {

    @Test
    fun `单周与双周没有共同周次`() {
        val odd = TeachingWeeks.odd(1, 15)
        val even = TeachingWeeks.even(2, 16)
        assertFalse("合成夹具(UNVERIFIED)：单周与双周不可能同周上课", odd.intersects(even))
        assertTrue("共同周次必须为空", odd.sharedWeeks(even).isEmpty())
    }

    @Test
    fun `全周与单周相交并返回共同周次`() {
        val all = TeachingWeeks.range(1, 16)
        val odd = TeachingWeeks.odd(1, 15)
        assertTrue("1-16 周必然覆盖单周", all.intersects(odd))
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), all.sharedWeeks(odd))
    }

    @Test
    fun `跨周次区间的课程不算冲突`() {
        val firstHalf = TeachingWeeks.range(1, 8)
        val secondHalf = TeachingWeeks.range(9, 16)
        assertFalse("前后半学期不重叠（UNVERIFIED 合成周次）", firstHalf.intersects(secondHalf))
        assertTrue(firstHalf.sharedWeeks(secondHalf).isEmpty())
    }

    @Test
    fun `周次未知时按可能冲突处理而不是判为安全`() {
        val unknown = TeachingWeeks.UNKNOWN
        assertFalse("空集合代表未知", unknown.isKnown)
        assertTrue("未知周次必须保守判为相交", unknown.intersects(TeachingWeeks.range(1, 16)))
        assertTrue("未知时不伪造共同周次", unknown.sharedWeeks(TeachingWeeks.range(1, 16)).isEmpty())
        assertEquals("周次未知", unknown.displayText)
    }

    @Test
    fun `解析周次文本得到精确周集合`() {
        assertEquals(TeachingWeeks.range(1, 16), TeachingWeeks.parse("1-16周"))
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), TeachingWeeks.parse("1-15周(单)").weeks)
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), TeachingWeeks.parse("2-16周（双）").weeks)
        assertEquals("空文本不得猜测周次", TeachingWeeks.UNKNOWN, TeachingWeeks.parse("  "))
        assertEquals("非法区间不得猜测周次", TeachingWeeks.UNKNOWN, TeachingWeeks.parse("16-1周"))
    }

    @Test
    fun `周次越界被拒绝`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TeachingWeeks.of(listOf(0, 1))
        }
        assertTrue("必须说明周次越界", error.message!!.contains("教学周"))
    }

    @Test
    fun `相邻节次不冲突而共享一节冲突`() {
        val first = ClassSlot(weekday = 1, startSection = 1, endSection = 2)
        val adjacent = ClassSlot(weekday = 1, startSection = 3, endSection = 4)
        val overlapping = ClassSlot(weekday = 1, startSection = 2, endSection = 3)
        assertFalse("半开区间 [1,3) 与 [3,5) 不相交", first.sharesSectionWith(adjacent))
        assertTrue("共享第 2 节必须判为冲突", first.sharesSectionWith(overlapping))
        assertEquals("第 1-2 节", first.sectionText)
        assertEquals("第 5 节", ClassSlot(1, 5, 5).sectionText)
        assertEquals("周一", first.weekdayLabel)
    }

    @Test
    fun `不同星期与非法时段的边界`() {
        val monday = ClassSlot(weekday = 1, startSection = 1, endSection = 2)
        val tuesday = ClassSlot(weekday = 2, startSection = 1, endSection = 2)
        assertFalse("不同星期不算冲突", monday.sharesSectionWith(tuesday))
        assertThrows(IllegalArgumentException::class.java) { ClassSlot(weekday = 0, startSection = 1, endSection = 2) }
        assertThrows(IllegalArgumentException::class.java) { ClassSlot(weekday = 1, startSection = 3, endSection = 2) }
    }

    @Test
    fun `轮次阶段按半开窗口判定`() {
        val round = SelectionFixtures.OPEN_ROUND
        assertEquals(RoundPhase.NOT_STARTED, round.phaseAt(Instant.parse("2026-08-31T23:59:59Z")))
        assertEquals(RoundPhase.OPEN, round.phaseAt(Instant.parse("2026-09-01T00:00:00Z")))
        assertEquals("到达结束时刻即视为已关闭", RoundPhase.CLOSED, round.phaseAt(Instant.parse("2026-09-07T00:00:00Z")))
        assertTrue(round.acceptsWritesAt(SelectionFixtures.NOW))
    }

    @Test
    fun `窗口不完整时不得猜用户能否写入`() {
        val unknownWindow = SelectionFixtures.OPEN_ROUND.copy(closesAt = null)
        assertFalse(unknownWindow.hasKnownWindow)
        assertEquals(RoundPhase.WINDOW_UNKNOWN, unknownWindow.phaseAt(SelectionFixtures.NOW))
        assertFalse("窗口未知时必须禁止写入", unknownWindow.acceptsWritesAt(SelectionFixtures.NOW))
    }

    @Test
    fun `未声明可写的轮次即使开放也禁止写入`() {
        val readOnly = SelectionFixtures.READ_ONLY_ROUND
        assertEquals(RoundPhase.OPEN, readOnly.phaseAt(SelectionFixtures.NOW))
        assertFalse("缺少写能力证据时必须保持禁用（UNVERIFIED 默认）", readOnly.acceptsWritesAt(SelectionFixtures.NOW))
        assertFalse(SelectionFixtures.CLOSED_ROUND.acceptsWritesAt(SelectionFixtures.NOW))
    }

    @Test
    fun `轮次时间倒置被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            SelectionRound(
                id = "round-bad",
                termId = SelectionFixtures.TERM,
                label = "坏轮次（合成）",
                opensAt = Instant.parse("2026-09-07T00:00:00Z"),
                closesAt = Instant.parse("2026-09-01T00:00:00Z")
            )
        }
    }
}

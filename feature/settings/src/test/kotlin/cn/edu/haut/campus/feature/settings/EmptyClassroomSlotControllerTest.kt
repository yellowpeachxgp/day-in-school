package cn.edu.haut.campus.feature.settings

import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * R3-D1a 自习室「时钟 × 槽位选择」回归（注入时钟缝，纯 JVM）：
 * - tick 永远推进 today 与自动解析，手动点选只保护选中槽位；
 * - 「恢复自动定位」立即按当前时刻重解析（绝不读 60s 前旧值）；
 * - 跨午夜 today 照常翻页；
 * - 边界矩阵：20:45/21:09 仍 9-10节、21:10 进下一时段、21:15 起 11-12节。
 */
class EmptyClassroomSlotControllerTest {

    private class FakeClock(initial: ZonedDateTime) {
        var now: ZonedDateTime = initial
        fun provider(): () -> ZonedDateTime = { now }
    }

    private fun zdt(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), HAUT_ZONE_ID)

    private fun newController(at: ZonedDateTime): Pair<FakeClock, EmptyClassroomSlotController> {
        val clock = FakeClock(at)
        val controller = EmptyClassroomSlotController(clock.provider())
        // 初始 refreshNow：让 autoSlotResolution 反映注入时刻（构造期已按时钟解析过一次，幂等）
        controller.refreshNow(periodTimes = emptyList(), timeTableId = null)
        return clock to controller
    }

    @Test
    fun `boundary matrix keeps 9-10 until 21-09 then next slot at 21-10 and 11-12 from 21-15`() {
        for ((hh, mm) in listOf(20 to 45, 20 to 50, 21 to 9)) {
            val (_, c) = newController(zdt(2026, 11, 15, hh, mm))
            assertEquals("%02d:%02d 应仍处 9-10节".format(hh, mm), 4, c.autoSlotResolution.slotIndex)
            assertEquals(
                CampusStudyRoomCatalog.StudySlotTiming.IN_SESSION,
                c.autoSlotResolution.timing
            )
        }
        for ((hh, mm) in listOf(21 to 10, 21 to 12, 21 to 14)) {
            val (_, c) = newController(zdt(2026, 11, 15, hh, mm))
            assertEquals("%02d:%02d 应预选 11-12节下一时段".format(hh, mm), 5, c.autoSlotResolution.slotIndex)
            assertEquals(
                CampusStudyRoomCatalog.StudySlotTiming.UPCOMING_NEXT,
                c.autoSlotResolution.timing
            )
        }
        for ((hh, mm) in listOf(21 to 15, 21 to 30)) {
            val (_, c) = newController(zdt(2026, 11, 15, hh, mm))
            assertEquals("%02d:%02d 应处 11-12节".format(hh, mm), 5, c.autoSlotResolution.slotIndex)
            assertEquals(
                CampusStudyRoomCatalog.StudySlotTiming.IN_SESSION,
                c.autoSlotResolution.timing
            )
        }
    }

    @Test
    fun `manual pick survives ticks while clock and auto resolution keep advancing`() {
        val (clock, c) = newController(zdt(2026, 11, 15, 19, 50))
        // 19:50 处于 9-10节窗口(19:30-21:10)内 -> 自动槽位 4
        assertEquals(4, c.autoSlotResolution.slotIndex)
        assertFalse(c.slotManuallyPicked)

        // 手动点选 5-6节（index 2）
        c.pickSlot(2)
        assertTrue(c.slotManuallyPicked)
        assertEquals(2, c.selectedSlotIndex)

        // 时钟推进到 21:20（tick 照常走）：today/自动解析刷新，手动选中槽位绝不被覆盖
        clock.now = zdt(2026, 11, 15, 21, 20)
        c.refreshNow(periodTimes = emptyList(), timeTableId = null)
        assertEquals(2, c.selectedSlotIndex)
        assertEquals("自动解析必须随时钟前进", 5, c.autoSlotResolution.slotIndex)
        assertEquals(LocalDate.of(2026, 11, 15), c.today)
    }

    @Test
    fun `restore auto resolves current slot immediately not the stale resolution`() {
        val (clock, c) = newController(zdt(2026, 11, 15, 20, 0))
        assertEquals(4, c.autoSlotResolution.slotIndex)

        // 手动选 1-2节，随后时钟推进到 11-12节窗口
        c.pickSlot(0)
        clock.now = zdt(2026, 11, 15, 21, 20)

        // 恢复自动：立即按 21:20 解析并选中 11-12节（index 5），绝不使用点击前的旧解析
        c.restoreAuto(periodTimes = emptyList(), timeTableId = null)
        assertFalse(c.slotManuallyPicked)
        assertEquals(5, c.selectedSlotIndex)
        assertEquals(5, c.autoSlotResolution.slotIndex)
        assertEquals(
            CampusStudyRoomCatalog.StudySlotTiming.IN_SESSION,
            c.autoSlotResolution.timing
        )

        // 跨节次边界再恢复：22:55 已过最后窗口 -> AFTER_LAST 仍选中最后槽位
        clock.now = zdt(2026, 11, 15, 22, 55)
        c.pickSlot(3)
        c.restoreAuto(periodTimes = emptyList(), timeTableId = null)
        assertEquals(5, c.selectedSlotIndex)
        assertEquals(
            CampusStudyRoomCatalog.StudySlotTiming.AFTER_LAST,
            c.autoSlotResolution.timing
        )
    }

    @Test
    fun `cross midnight tick refreshes today even while a slot is manually picked`() {
        val (clock, c) = newController(zdt(2026, 11, 15, 23, 59))
        assertEquals(LocalDate.of(2026, 11, 15), c.today)

        c.pickSlot(1)
        // 跨午夜 tick：today 必须翻页（旧实现手动模式冻结时钟，today 停留在昨日）
        clock.now = zdt(2026, 11, 16, 0, 1)
        c.refreshNow(periodTimes = emptyList(), timeTableId = null)
        assertEquals(LocalDate.of(2026, 11, 16), c.today)
        assertTrue(c.slotManuallyPicked)
        assertEquals(1, c.selectedSlotIndex)

        // 非手动模式跨午夜：today 翻页且选中槽位跟随新一天的自动解析
        c.restoreAuto(periodTimes = emptyList(), timeTableId = null)
        clock.now = zdt(2026, 11, 17, 0, 5)
        c.refreshNow(periodTimes = emptyList(), timeTableId = null)
        assertEquals(LocalDate.of(2026, 11, 17), c.today)
        // 凌晨 0:05 早于首个槽位窗口 -> 预选 1-2节（index 0）
        assertEquals(0, c.autoSlotResolution.slotIndex)
        assertEquals(0, c.selectedSlotIndex)
    }

    // ---------- R4-D1a：跨午夜日期选择——自动跟新今日 / 手动绝对日期过期标记 ----------

    @Test
    fun `cross midnight auto mode selects new today not stale weekday next week`() {
        // 周日 23:59 打开（自动模式）：selectedDate 即 today
        val (clock, c) = newController(zdt(2026, 11, 15, 23, 59))
        assertEquals(LocalDate.of(2026, 11, 15), c.today)
        assertNull(c.manualSelectedDate)
        assertEquals(LocalDate.of(2026, 11, 15), c.selectedDate)

        // 跨午夜到周一：自动模式选中日期跟到新今日（周一），
        // 绝不把陈旧"周日"选择投影成下周日 2026-11-22
        clock.now = zdt(2026, 11, 16, 0, 1)
        c.refreshNow(periodTimes = emptyList(), timeTableId = null)
        assertEquals(LocalDate.of(2026, 11, 16), c.today)
        assertEquals(
            "自动模式跨午夜必须选中新今日，绝不滑入下周同星期",
            LocalDate.of(2026, 11, 16),
            c.selectedDate
        )
        // 凌晨 0:01 早于首个槽位窗口：自动槽位预选 1-2节（index 0）
        assertEquals(0, c.autoSlotResolution.slotIndex)
        assertEquals(0, c.selectedSlotIndex)
    }

    @Test
    fun `manual picked date stays absolute and is flagged expired after rollover`() {
        val (clock, c) = newController(zdt(2026, 11, 15, 20, 0))
        // 手动点选当日：固化为绝对日期 11-15
        c.pickDate(LocalDate.of(2026, 11, 15))
        assertEquals(LocalDate.of(2026, 11, 15), c.selectedDate)
        assertFalse(c.manualDateExpired)

        // 跨午夜：所选仍是 11-15（绝不静默改指 11-22），并诚实标记过期
        clock.now = zdt(2026, 11, 16, 0, 1)
        c.refreshNow(periodTimes = emptyList(), timeTableId = null)
        assertEquals(LocalDate.of(2026, 11, 15), c.selectedDate)
        assertTrue("手动所选日期早于新 today 必须标记过期", c.manualDateExpired)

        // 回到今天：退出手动日期模式，选中日期重新跟随新今日
        c.restoreAutoDate()
        assertNull(c.manualSelectedDate)
        assertEquals(LocalDate.of(2026, 11, 16), c.selectedDate)
        assertFalse(c.manualDateExpired)
    }

    @Test
    fun `manual picked future date survives rollover without expired flag`() {
        val (clock, c) = newController(zdt(2026, 11, 15, 20, 0))
        // 手动点选未来日期（下周三）：跨午夜后仍有效，不标过期
        c.pickDate(LocalDate.of(2026, 11, 18))
        clock.now = zdt(2026, 11, 16, 0, 1)
        c.refreshNow(periodTimes = emptyList(), timeTableId = null)
        assertEquals(LocalDate.of(2026, 11, 18), c.selectedDate)
        assertFalse(c.manualDateExpired)
    }
}

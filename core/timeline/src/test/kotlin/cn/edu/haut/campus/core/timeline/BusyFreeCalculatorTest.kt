package cn.edu.haut.campus.core.timeline

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class BusyFreeCalculatorTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `忙块合并重叠且 BUSY 优先于 TENTATIVE`() {
        val window = TimeWindow(instant("2026-03-02T08:00:00Z"), instant("2026-03-02T12:00:00Z"))
        val result = BusyFreeCalculator.busyBlocks(
            window = window,
            intervals = listOf(
                busy("2026-03-02T09:00:00Z", "2026-03-02T10:00:00Z", BusyLevel.BUSY),
                busy("2026-03-02T09:30:00Z", "2026-03-02T10:30:00Z", BusyLevel.TENTATIVE),
                busy("2026-03-02T11:00:00Z", "2026-03-02T11:30:00Z", BusyLevel.TENTATIVE)
            ),
            granularity = Duration.ofMinutes(1)
        )

        assertEquals(
            listOf(
                busy("2026-03-02T09:00:00Z", "2026-03-02T10:00:00Z", BusyLevel.BUSY),
                busy("2026-03-02T10:00:00Z", "2026-03-02T10:30:00Z", BusyLevel.TENTATIVE),
                busy("2026-03-02T11:00:00Z", "2026-03-02T11:30:00Z", BusyLevel.TENTATIVE)
            ),
            result
        )
    }

    @Test
    fun `忙块按粒度向外取整而不是泄漏分钟边界`() {
        val window = TimeWindow(instant("2026-03-02T08:00:00Z"), instant("2026-03-02T12:00:00Z"))
        val result = BusyFreeCalculator.busyBlocks(
            window = window,
            intervals = listOf(busy("2026-03-02T09:07:00Z", "2026-03-02T09:08:00Z", BusyLevel.BUSY)),
            granularity = Duration.ofMinutes(30)
        )

        assertEquals(
            listOf(busy("2026-03-02T09:00:00Z", "2026-03-02T09:30:00Z", BusyLevel.BUSY)),
            result
        )
    }

    @Test
    fun `粒度取整后的忙块仍不越过查询窗口`() {
        val window = TimeWindow(instant("2026-03-02T09:07:00Z"), instant("2026-03-02T09:08:00Z"))
        val result = BusyFreeCalculator.busyBlocks(
            window = window,
            intervals = listOf(busy("2026-03-02T09:07:00Z", "2026-03-02T09:08:00Z", BusyLevel.BUSY)),
            granularity = Duration.ofMinutes(30)
        )

        assertEquals(listOf(busy("2026-03-02T09:07:00Z", "2026-03-02T09:08:00Z", BusyLevel.BUSY)), result)
    }

    @Test
    fun `跨日占用不会在午夜被错误切开或遗漏`() {
        val window = TimeWindow(instant("2026-03-02T12:00:00Z"), instant("2026-03-03T12:00:00Z"))
        val result = BusyFreeCalculator.freeSlots(
            window = window,
            intervals = listOf(busy("2026-03-02T15:00:00Z", "2026-03-02T17:00:00Z", BusyLevel.BUSY)),
            minLength = Duration.ofMinutes(30),
            constraints = SlotConstraints(zone = shanghai)
        )

        assertEquals(
            listOf(
                FreeSlot(instant("2026-03-02T12:00:00Z"), instant("2026-03-02T15:00:00Z")),
                FreeSlot(instant("2026-03-02T17:00:00Z"), instant("2026-03-03T12:00:00Z"))
            ),
            result
        )
    }

    @Test
    fun `跨周窗口按每周约束生成候选`() {
        val window = TimeWindow(instant("2026-03-02T00:00:00Z"), instant("2026-03-10T00:00:00Z"))
        val result = BusyFreeCalculator.freeSlots(
            window = window,
            intervals = emptyList(),
            minLength = Duration.ofHours(1),
            constraints = SlotConstraints(
                zone = shanghai,
                allowedDays = setOf(DayOfWeek.MONDAY),
                dailyWindows = listOf(LocalTimeWindow(LocalTime.of(8, 0), LocalTime.of(10, 0)))
            )
        )

        assertEquals(
            listOf(
                FreeSlot(instant("2026-03-02T00:00:00Z"), instant("2026-03-02T02:00:00Z")),
                FreeSlot(instant("2026-03-09T00:00:00Z"), instant("2026-03-09T02:00:00Z"))
            ),
            result
        )
    }

    @Test
    fun `跨午夜可用窗与跨日查询正确相交`() {
        val window = TimeWindow(instant("2026-03-02T12:00:00Z"), instant("2026-03-03T01:00:00Z"))
        val result = BusyFreeCalculator.freeSlots(
            window = window,
            intervals = emptyList(),
            minLength = Duration.ofMinutes(30),
            constraints = SlotConstraints(
                zone = shanghai,
                dailyWindows = listOf(LocalTimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0)))
            )
        )

        assertEquals(
            listOf(FreeSlot(instant("2026-03-02T14:00:00Z"), instant("2026-03-02T22:00:00Z"))),
            result
        )
    }

    @Test
    fun `TENTATIVE 默认占用空闲也可显式视为可用`() {
        val window = TimeWindow(instant("2026-03-02T09:00:00Z"), instant("2026-03-02T12:00:00Z"))
        val tentative = busy("2026-03-02T10:00:00Z", "2026-03-02T11:00:00Z", BusyLevel.TENTATIVE)

        val conservative = BusyFreeCalculator.freeSlots(
            window = window,
            intervals = listOf(tentative),
            minLength = Duration.ofMinutes(30),
            constraints = SlotConstraints(zone = shanghai)
        )
        val permissive = BusyFreeCalculator.freeSlots(
            window = window,
            intervals = listOf(tentative),
            minLength = Duration.ofMinutes(30),
            constraints = SlotConstraints(zone = shanghai, tentativeCountsAsBusy = false)
        )

        assertEquals(
            listOf(
                FreeSlot(instant("2026-03-02T09:00:00Z"), instant("2026-03-02T10:00:00Z")),
                FreeSlot(instant("2026-03-02T11:00:00Z"), instant("2026-03-02T12:00:00Z"))
            ),
            conservative
        )
        assertEquals(listOf(FreeSlot(window.start, window.end)), permissive)
    }

    @Test
    fun `短于最小时长的空隙不会误报`() {
        val window = TimeWindow(instant("2026-03-02T09:00:00Z"), instant("2026-03-02T10:20:00Z"))
        val result = BusyFreeCalculator.freeSlots(
            window = window,
            intervals = listOf(busy("2026-03-02T09:10:00Z", "2026-03-02T10:00:00Z", BusyLevel.BUSY)),
            minLength = Duration.ofMinutes(30),
            constraints = SlotConstraints(zone = shanghai)
        )

        assertEquals(emptyList<FreeSlot>(), result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `忙块粒度必须整秒`() {
        BusyFreeCalculator.busyBlocks(
            window = TimeWindow(instant("2026-03-02T09:00:00Z"), instant("2026-03-02T10:00:00Z")),
            intervals = emptyList(),
            granularity = Duration.ofMillis(1500)
        )
    }

    private fun busy(start: String, end: String, level: BusyLevel): BusyBlock =
        BusyBlock(instant(start), instant(end), level)

    private fun instant(text: String): Instant = Instant.parse(text)
}

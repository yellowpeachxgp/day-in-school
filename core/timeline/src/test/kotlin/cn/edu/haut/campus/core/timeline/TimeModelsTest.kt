package cn.edu.haut.campus.core.timeline

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeModelsTest {

    @Test
    fun `时间窗是半开区间且能求交集`() {
        val window = TimeWindow(instant("2026-03-02T09:00:00Z"), instant("2026-03-02T10:00:00Z"))

        assertTrue(window.contains(instant("2026-03-02T09:00:00Z")))
        assertFalse(window.contains(instant("2026-03-02T10:00:00Z")))
        assertTrue(window.overlaps(TimeWindow(instant("2026-03-02T09:59:00Z"), instant("2026-03-02T10:30:00Z"))))
        assertFalse(window.overlaps(TimeWindow(instant("2026-03-02T10:00:00Z"), instant("2026-03-02T11:00:00Z"))))
        assertEquals(
            TimeWindow(instant("2026-03-02T09:30:00Z"), instant("2026-03-02T10:00:00Z")),
            window.intersection(TimeWindow(instant("2026-03-02T09:30:00Z"), instant("2026-03-02T11:00:00Z")))
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `时间窗拒绝零长度`() {
        TimeWindow(instant("2026-03-02T09:00:00Z"), instant("2026-03-02T09:00:00Z"))
    }

    @Test
    fun `跨午夜本地窗按显式校区时区投影`() {
        val local = LocalTimeWindow(LocalTime.of(22, 0), LocalTime.of(6, 0))
        val projected = local.toTimeWindow(LocalDate.of(2026, 3, 2), ZoneId.of("Asia/Shanghai"))

        assertEquals(instant("2026-03-02T14:00:00Z"), projected.start)
        assertEquals(instant("2026-03-02T22:00:00Z"), projected.end)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `本地窗拒绝用相同起止冒充全天`() {
        LocalTimeWindow(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT)
    }

    private fun instant(text: String): Instant = Instant.parse(text)
}

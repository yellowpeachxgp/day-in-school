package cn.edu.haut.campus.core.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class LiveProgressModelTest {
    private val startAt: Instant = Instant.parse("2026-11-27T02:00:00Z")
    private val endAt: Instant = startAt.plusMillis(5_400_000L) // 90 分钟，含毫秒精度
    private val leadWindow: Duration = Duration.ofMinutes(45)

    @Test
    fun `before lead window returns null`() {
        val now = startAt.minus(leadWindow).minusMillis(1)

        assertNull(LiveProgressModel.compute(now, startAt, endAt, leadWindow))
    }

    @Test
    fun `lead window start endpoint returns zero pre class progress`() {
        val now = startAt.minus(leadWindow)

        val snapshot = LiveProgressModel.compute(now, startAt, endAt, leadWindow)

        assertEquals(LiveProgressModel.Phase.BEFORE_CLASS, snapshot?.phase)
        assertEquals(0L, snapshot?.elapsedInPhaseMs)
        assertEquals(leadWindow.toMillis(), snapshot?.totalInPhaseMs)
        assertEquals(0, snapshot?.progressUnits)
        assertEquals(startAt.toEpochMilli(), snapshot?.countdownTargetEpochMs)
    }

    @Test
    fun `pre class progress uses millisecond ratio rather than whole minutes`() {
        val now = startAt.minus(leadWindow).plusMillis(1_234_567L)

        val snapshot = LiveProgressModel.compute(now, startAt, endAt, leadWindow)

        val expected = (1_234_567L * LiveProgressModel.PROGRESS_SCALE) / leadWindow.toMillis()
        assertEquals(1_234_567L, snapshot?.elapsedInPhaseMs)
        assertEquals(expected.toInt(), snapshot?.progressUnits)
        assertEquals(Duration.between(now, startAt).toMillis(), snapshot?.remainingMs)
    }

    @Test
    fun `class start endpoint switches to in class zero progress`() {
        val snapshot = LiveProgressModel.compute(startAt, startAt, endAt, leadWindow)

        assertEquals(LiveProgressModel.Phase.IN_CLASS, snapshot?.phase)
        assertEquals(0L, snapshot?.elapsedInPhaseMs)
        assertEquals(0, snapshot?.progressUnits)
        assertEquals(endAt.toEpochMilli(), snapshot?.countdownTargetEpochMs)
    }

    @Test
    fun `in class progress uses exact millisecond ratio`() {
        val now = startAt.plusMillis(1_234_567L)

        val snapshot = LiveProgressModel.compute(now, startAt, endAt, leadWindow)

        assertEquals(2_286, snapshot?.progressUnits) // floor(1_234_567 * 10_000 / 5_400_000)
        assertEquals(5_400_000L, snapshot?.totalInPhaseMs)
        assertEquals(5_400_000L - 1_234_567L, snapshot?.remainingMs)
    }

    @Test
    fun `crossing class end endpoint closes the live window`() {
        val beforeEnd = LiveProgressModel.compute(endAt.minusMillis(1), startAt, endAt, leadWindow)
        val atEnd = LiveProgressModel.compute(endAt, startAt, endAt, leadWindow)
        val afterEnd = LiveProgressModel.compute(endAt.plusMillis(1), startAt, endAt, leadWindow)

        assertEquals(LiveProgressModel.Phase.IN_CLASS, beforeEnd?.phase)
        assertEquals(1L, beforeEnd?.remainingMs)
        assertNull(atEnd)
        assertNull(afterEnd)
    }

    @Test
    fun `zero or negative class duration returns null`() {
        assertNull(LiveProgressModel.compute(startAt, startAt, startAt, leadWindow))
        assertNull(LiveProgressModel.compute(startAt, startAt, startAt.minusMillis(1), leadWindow))
    }

    @Test
    fun `zero lead window suppresses pre class phase but keeps in class progress`() {
        assertNull(LiveProgressModel.compute(startAt.minusMillis(1), startAt, endAt, Duration.ZERO))

        val inClass = LiveProgressModel.compute(startAt.plusMillis(1), startAt, endAt, Duration.ZERO)

        assertEquals(LiveProgressModel.Phase.IN_CLASS, inClass?.phase)
        assertEquals(0, inClass?.progressUnits)
    }

    @Test
    fun `negative lead window is rejected`() {
        assertNull(LiveProgressModel.compute(startAt, startAt, endAt, Duration.ofMillis(-1)))
    }
}

package cn.edu.haut.campus.feature.settings

import cn.edu.haut.campus.core.campusservices.*
import cn.edu.haut.campus.core.database.CloudPlanPriceDriftException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** 使用生产页面持有者及生产 submitter；端口仅替换查询与最终写入。 */
class LibraryBookingDateStateTest {
    private class TestClock(var instantValue: Instant = Instant.parse("2026-11-15T15:59:00Z")) : Clock() {
        override fun instant() = instantValue
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId): Clock = this
        fun midnight() { instantValue = Instant.parse("2026-11-15T16:00:00Z") }
    }
    private val identity = BookingIdentityContext("profile", "user", 1, "https://test.invalid", "test-student")
    private val region = LibraryStudyRegionItem(1, "测试区域")
    private suspend fun query(state: LibraryBookingDateState) {
        state.loadRegions(null) { Result.success(listOf(region)) }
        state.loadSeats(region, null) { _, date -> Result.success(seats(date)) }
    }
    private fun seats(date: String) = LibraryRegionSeatsSnapshot(1, region.regionName, date, "07:00", "22:00", false, listOf(LibrarySeatItem(2, "测试座位", 2)))
    private fun submitter(state: LibraryBookingDateState, clock: TestClock, mode: BookingExecutionMode,
        machine: LibraryBookingSubmitStateMachine = LibraryBookingSubmitStateMachine(),
        time: () -> String = { "07:00" }, identityProvider: suspend () -> BookingIdentityContext = { identity },
        submit: suspend (BookingDraftSnapshot) -> Result<Unit>
    ) = LibraryBookingPlanSubmitter(machine, identityProvider,
        { state.draftParams(mode, time(), state.selectedRegion?.regionName.orEmpty(), 1) }, submit,
        prepareSubmission = state::prepareSubmission, nowEpochMillis = clock::millis)

    @Test fun `前台轮询和恢复前台对今天明天更新摘要查询并清空旧座位`() = runBlocking {
        for (selection in listOf("TODAY", "TOMORROW")) {
            val clock = TestClock()
            val machine = LibraryBookingSubmitStateMachine()
            val state = LibraryBookingDateState(clock, selection, machine::cancelPendingReconfirmation)
            query(state)
            val oldDate = state.targetDate
            clock.midnight()
            assertTrue(state.refreshClock()) // 与生产前台轮询、ON_RESUME 使用相同入口
            assertEquals(oldDate.plusDays(1), state.targetDate)
            assertNull(state.seatSnapshot)
            assertTrue(state.selectedSeatNames.isEmpty())
            assertFalse(state.prepareSubmission())
            query(state)
            assertEquals(state.targetDate.toString(), state.seatSnapshot!!.bookingDate)
        }
    }

    @Test fun `跨日发生在点击之前时两引擎均先拒绝待重新查询确认`() = runBlocking {
        for (mode in listOf(BookingExecutionMode.LOCAL_DEVICE, BookingExecutionMode.CLOUD_HOSTED)) {
            for (selection in listOf("TODAY", "TOMORROW", "2026-11-18")) {
                val clock = TestClock()
                val state = LibraryBookingDateState(clock, selection)
                query(state)
                val writes = mutableListOf<BookingDraftSnapshot>()
                val submitter = submitter(state, clock, mode) { writes += it; Result.success(Unit) }
                val oldDate = state.targetDate
                clock.midnight()
                val result = submitter.submitNewAttempt()
                if (selection.startsWith("2026")) {
                    assertTrue(result is BookingSubmitEvent.Accepted)
                    assertEquals(oldDate.toString(), writes.single().targetDate)
                } else {
                    assertEquals(BookingSubmitEvent.StaleConfirmationRefused, result)
                    assertTrue(writes.isEmpty())
                    query(state)
                    assertTrue(submitter.submitNewAttempt() is BookingSubmitEvent.Accepted)
                    assertEquals(state.seatSnapshot!!.bookingDate, writes.single().targetDate)
                }
            }
        }
    }

    @Test fun `跨午夜和手动改期时旧区域与座位在途响应均不得安装`() = runBlocking {
        for (change in listOf("midnight", "manual")) {
            val clock = TestClock()
            val state = LibraryBookingDateState(clock, "TOMORROW")
            val gate = CompletableDeferred<Unit>()
            val regions = async(start = CoroutineStart.UNDISPATCHED) {
                state.loadRegions(null) { gate.await(); Result.success(listOf(region)) }
            }
            if (change == "midnight") clock.midnight() else state.choose("2026-11-19")
            gate.complete(Unit); regions.await()
            assertTrue(state.regions.isEmpty())
            query(state)
            val seatGate = CompletableDeferred<Unit>()
            val seats = async(start = CoroutineStart.UNDISPATCHED) {
                state.loadSeats(region, null) { _, date -> seatGate.await(); Result.success(seats(date)) }
            }
            state.choose("2026-11-20")
            seatGate.complete(Unit); seats.await()
            assertNull(state.seatSnapshot)
            assertTrue(state.selectedSeatNames.isEmpty())
        }
    }

    @Test fun `报价弹窗跨日和改时间后旧确认均不能写入两种引擎`() = runBlocking {
        for (mode in listOf(BookingExecutionMode.LOCAL_DEVICE, BookingExecutionMode.CLOUD_HOSTED)) {
            for (change in listOf("midnight", "time", "date")) {
                val clock = TestClock()
                val machine = LibraryBookingSubmitStateMachine()
                val state = LibraryBookingDateState(clock, "TOMORROW", machine::cancelPendingReconfirmation)
                var time = "07:00"
                var calls = 0
                query(state)
                val submitter = submitter(state, clock, mode, machine, time = { time }) {
                    calls++
                    Result.failure(CloudPlanPriceDriftException(10000, 20000, "测试报价"))
                }
                assertTrue(submitter.submitNewAttempt() is BookingSubmitEvent.PriceReconfirmationRequired)
                when (change) {
                    "midnight" -> clock.midnight()
                    "time" -> time = "08:05"
                    else -> state.choose("2026-11-19")
                }
                assertEquals(BookingSubmitEvent.StaleConfirmationRefused, submitter.confirmPendingReconfirmation())
                assertEquals(1, calls)
                assertNull(machine.visiblePendingReconfirmation())
            }
        }
    }

    @Test fun `用户改日期和时间后新提交与页面和查询一致且挂起身份读取不能偷换参数`() = runBlocking {
        for (mode in listOf(BookingExecutionMode.LOCAL_DEVICE, BookingExecutionMode.CLOUD_HOSTED)) {
            val clock = TestClock()
            val state = LibraryBookingDateState(clock, "TODAY")
            state.choose("2026-11-18")
            query(state)
            var time = "09:15"
            val writes = mutableListOf<BookingDraftSnapshot>()
            val submitter = submitter(state, clock, mode, time = { time }) { writes += it; Result.success(Unit) }
            assertTrue(submitter.submitNewAttempt() is BookingSubmitEvent.Accepted)
            assertEquals("2026-11-18", writes.single().targetDate)
            assertEquals(time, writes.single().targetTime)
            val gate = CompletableDeferred<Unit>()
            val slow = submitter(state, clock, mode, time = { time }, identityProvider = { gate.await(); identity }) { writes += it; Result.success(Unit) }
            val pending = async(start = CoroutineStart.UNDISPATCHED) { slow.submitNewAttempt() }
            time = "10:20"
            gate.complete(Unit)
            assertEquals(BookingSubmitEvent.StaleConfirmationRefused, pending.await())
            assertEquals(1, writes.size)
        }
    }

    @Test fun `查询挂起跨午夜后重新查询成功且晚到报价不污染新日期`() = runBlocking {
        val clock = TestClock()
        val state = LibraryBookingDateState(clock, "TOMORROW")
        query(state)
        val gate = CompletableDeferred<Unit>()
        val machine = LibraryBookingSubmitStateMachine()
        val submitter = submitter(state, clock, BookingExecutionMode.CLOUD_HOSTED, machine) {
            gate.await(); Result.failure(CloudPlanPriceDriftException(10000, 20000, "测试报价"))
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { submitter.submitNewAttempt() }
        clock.midnight(); state.refreshClock(); query(state)
        gate.complete(Unit)
        assertEquals(BookingSubmitEvent.StaleResultIgnored, pending.await())
        assertNull(machine.visiblePendingReconfirmation())
    }
    @Test fun `座位查询在途跨午夜不能把昨天结果装入新日期`() = runBlocking {
        val clock = TestClock()
        val state = LibraryBookingDateState(clock, "TOMORROW")
        query(state)
        val gate = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            state.loadSeats(region, null) { _, date -> gate.await(); Result.success(seats(date)) }
        }
        clock.midnight()
        gate.complete(Unit); pending.await()
        assertEquals("2026-11-17", state.targetDate.toString())
        assertNull(state.seatSnapshot)
        assertFalse(state.prepareSubmission())
        query(state)
        assertEquals("2026-11-17", state.seatSnapshot!!.bookingDate)
    }

    @Test fun `指定日期过期保持绝对值并拒绝提交而不自动改到下一天`() = runBlocking {
        val clock = TestClock()
        val state = LibraryBookingDateState(clock, "2026-11-15")
        query(state); clock.midnight()
        assertFalse(state.prepareSubmission())
        assertEquals("2026-11-15", state.targetDate.toString())
    }

    @Test fun `日期往返编辑仍使在途旧报价失效且已过期报价不能确认`() = runBlocking {
        val clock = TestClock()
        val state = LibraryBookingDateState(clock, "2026-11-18")
        query(state)
        val gate = CompletableDeferred<Unit>()
        val machine = LibraryBookingSubmitStateMachine()
        val late = submitter(state, clock, BookingExecutionMode.CLOUD_HOSTED, machine) {
            gate.await(); Result.failure(CloudPlanPriceDriftException(10000, 20000, "测试报价"))
        }
        val pending = async(start = CoroutineStart.UNDISPATCHED) { late.submitNewAttempt() }
        state.choose("2026-11-19"); state.choose("2026-11-18"); query(state)
        gate.complete(Unit)
        assertEquals(BookingSubmitEvent.StaleResultIgnored, pending.await())
        var calls = 0
        val expired = submitter(state, clock, BookingExecutionMode.CLOUD_HOSTED) {
            calls++
            Result.failure(CloudPlanPriceDriftException(10000, 20000, "测试报价",
                CloudReservationQuote("q_test", 20000, "test-v1", "PER_CONFIRMED_RESERVATION", "user", clock.millis())))
        }
        assertTrue(expired.submitNewAttempt() is BookingSubmitEvent.PriceReconfirmationRequired)
        assertEquals(BookingSubmitEvent.StaleConfirmationRefused, expired.confirmPendingReconfirmation())
        assertEquals(1, calls)
    }

    @Test fun `真实学校适配器查询到实际双引擎提交贯通同一绝对日期`() = runBlocking {
        for (mode in listOf(BookingExecutionMode.LOCAL_DEVICE, BookingExecutionMode.CLOUD_HOSTED)) {
            val clock = TestClock()
            val state = LibraryBookingDateState(clock, "TOMORROW")
            val urls = java.util.Collections.synchronizedList(mutableListOf<String>())
            val get: (String, String) -> String = { url, _ ->
                urls += java.net.URLDecoder.decode(url, "UTF-8")
                when {
                    url.contains("getDefaultTime") -> """{"code":200,"startDate":"2000-01-01","endDate":"2000-01-01"}"""
                    url.contains("/building") -> """{"code":200,"builderInfo":[{"id":1}]}"""
                    url.contains("/region") -> """{"code":200,"regionList":[{"id":1,"regionName":"测试区域","num":2,"cannum":1}]}"""
                    else -> """{"code":200,"layout":{"regionName":"测试区域"},"seatList":[{"id":2,"seatName":"测试区域01","seatStatus":2}]}"""
                }
            }
            suspend fun load() {
                state.loadRegions(null) { date -> HautLibrarySeatAuthClient.fetchLibraryStudyRegions("test-query-token-0000", date, get) }
                state.loadSeats(state.selectedRegion!!, null) { id, date -> HautLibrarySeatAuthClient.fetchLibraryRegionSeats("test-query-token-0000", id, date, get) }
            }
            load(); clock.midnight()
            assertFalse(state.prepareSubmission())
            load()
            val writes = mutableListOf<BookingDraftSnapshot>()
            assertTrue(submitter(state, clock, mode) { writes += it; Result.success(Unit) }.submitNewAttempt() is BookingSubmitEvent.Accepted)
            assertEquals("2026-11-17", writes.single().targetDate)
            assertEquals(writes.single().targetDate, state.seatSnapshot!!.bookingDate)
            assertTrue(urls.last().contains("starttime=2026-11-17 "))
        }
    }

    @Test fun `跨午夜报价确认使相对日期失效而明确日期保留原参数`() = runBlocking {
        for (mode in listOf(BookingExecutionMode.LOCAL_DEVICE, BookingExecutionMode.CLOUD_HOSTED)) {
            for (selection in listOf("TODAY", "TOMORROW", "2026-11-18")) {
                val clock = TestClock()
                val machine = LibraryBookingSubmitStateMachine()
                val state = LibraryBookingDateState(clock, selection, machine::cancelPendingReconfirmation)
                query(state)
                val date = state.targetDate.toString()
                val writes = mutableListOf<BookingDraftSnapshot>()
                val quote = CloudReservationQuote("q_manual", 20000, "test-v1", "PER_CONFIRMED_RESERVATION", "user", clock.millis() + 600000)
                val submitter = submitter(state, clock, mode, machine) {
                    writes += it
                    if (writes.size == 1) Result.failure(CloudPlanPriceDriftException(10000, 20000, "测试报价", quote)) else Result.success(Unit)
                }
                assertTrue(submitter.submitNewAttempt() is BookingSubmitEvent.PriceReconfirmationRequired)
                clock.midnight()
                val confirmed = submitter.confirmPendingReconfirmation()
                if (selection == "2026-11-18") {
                    assertTrue(confirmed is BookingSubmitEvent.Accepted)
                    assertEquals(date, writes.last().targetDate)
                    assertEquals(20000L, writes.last().acceptedPriceMicropeach)
                } else {
                    assertEquals(BookingSubmitEvent.StaleConfirmationRefused, confirmed)
                    assertEquals(1, writes.size)
                }
            }
        }
    }

}

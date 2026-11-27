package cn.edu.haut.campus.feature.settings.library.state

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
        state.selectedSeatNames = listOf("测试座位")
    }
    private fun seats(date: String) = LibraryRegionSeatsSnapshot(1, region.regionName, date, "07:00", "22:00", false, listOf(LibrarySeatItem(2, "测试座位", 2)))
    private fun submitter(state: LibraryBookingDateState, clock: TestClock, mode: BookingExecutionMode,
        machine: LibraryBookingSubmitStateMachine = LibraryBookingSubmitStateMachine(),
        time: () -> String = { "07:00" }, identityProvider: suspend () -> BookingIdentityContext = { identity },
        submit: suspend (BookingDraftSnapshot) -> Result<Unit>
    ) = LibraryBookingPlanSubmitter(machine, identityProvider,
        { state.draftParams(mode, time(), state.selectedRegion?.regionName.orEmpty(), 1) }, submit,
        prepareSubmission = state::prepareSubmission, nowEpochMillis = clock::millis)

    @Test fun `前台轮询和恢复前台更新日期清除旧实时结果并保留偏好`() = runBlocking {
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
            assertEquals(listOf("测试座位"), state.selectedSeatNames)
            assertTrue(state.prepareSubmission())
            query(state)
            assertEquals(state.today.toString(), state.seatSnapshot!!.bookingDate)
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
                    assertEquals(state.targetDate.toString(), writes.single().targetDate)
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
            assertEquals(listOf("测试座位"), state.selectedSeatNames)
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
        assertEquals(listOf("测试座位"), state.selectedSeatNames)
        assertTrue(state.prepareSubmission())
        query(state)
        assertEquals("2026-11-16", state.seatSnapshot!!.bookingDate)
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

    @Test fun `真实学校适配器读取目录而实际双引擎提交保持未来绝对日期`() = runBlocking {
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
                state.selectedSeatNames = listOf("测试区域01")
            }
            load(); clock.midnight()
            assertFalse(state.prepareSubmission())
            load()
            val writes = mutableListOf<BookingDraftSnapshot>()
            assertTrue(submitter(state, clock, mode) { writes += it; Result.success(Unit) }.submitNewAttempt() is BookingSubmitEvent.Accepted)
            assertEquals("2026-11-17", writes.single().targetDate)
            assertEquals("2026-11-16", state.seatSnapshot!!.bookingDate)
            assertTrue(urls.last().contains("starttime=2026-11-16 "))
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

    @Test fun `未来日期未开放默认读取今日目录并允许选择当前被占的偏好`() = runBlocking {
        for (mode in listOf(BookingExecutionMode.LOCAL_DEVICE, BookingExecutionMode.CLOUD_HOSTED)) {
            val clock = TestClock()
            val state = LibraryBookingDateState(clock, "TOMORROW")
            val queries = mutableListOf<String>()
            state.loadRegions(null) { date ->
                queries += date
                Result.failure(IllegalStateException("目标日期尚未开放"))
            }
            assertEquals(listOf("2026-11-15"), queries)
            assertFalse(state.prepareSubmission())
            state.loadRegions(null) { date -> queries += date; Result.success(listOf(region)) }
            state.loadSeats(region, null) { _, date ->
                queries += date
                Result.success(seats(date).copy(seats = listOf(LibrarySeatItem(2, "测试座位", 1))))
            }
            assertTrue(state.isReferenceLayout)
            assertEquals("2026-11-15", state.layoutDate.toString())
            assertEquals("2026-11-16", state.targetDate.toString())
            assertEquals(listOf("2026-11-15", "2026-11-15", "2026-11-15"), queries)
            assertFalse(state.seatSnapshot!!.seats.single().isAvailable)
            assertTrue(state.selectedSeatNames.isEmpty())
            state.selectedSeatNames = listOf("测试座位")
            assertTrue(state.prepareSubmission())
            assertFalse(state.prepareImmediateBooking())
            val writes = mutableListOf<BookingDraftSnapshot>()
            val sender = submitter(state, clock, mode) { writes += it; Result.success(Unit) }
            assertTrue(sender.submitNewAttempt() is BookingSubmitEvent.Accepted)
            assertEquals("2026-11-16", writes.single().targetDate)
            assertEquals("测试座位", writes.single().seatLabel)
        }
    }

    @Test fun `修改座位偏好使旧报价失效且晚到目录不能覆盖新选择`() = runBlocking {
        val clock = TestClock()
        val machine = LibraryBookingSubmitStateMachine()
        val state = LibraryBookingDateState(clock, "TOMORROW", machine::cancelPendingReconfirmation)
        query(state)
        var writes = 0
        val sender = submitter(state, clock, BookingExecutionMode.CLOUD_HOSTED, machine) {
            writes++
            Result.failure(CloudPlanPriceDriftException(10000, 20000, "测试报价"))
        }
        assertTrue(sender.submitNewAttempt() is BookingSubmitEvent.PriceReconfirmationRequired)
        val gate = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            state.loadSeats(region, null) { _, date -> gate.await(); Result.success(seats(date)) }
        }
        state.selectedSeatNames = listOf("新备选")
        gate.complete(Unit); pending.await()
        assertEquals(listOf("新备选"), state.selectedSeatNames)
        assertNull(machine.visiblePendingReconfirmation())
        assertEquals(BookingSubmitEvent.StaleConfirmationRefused, sender.confirmPendingReconfirmation())
        assertEquals(1, writes)
        query(state)
        state.selectedSeatNames = listOf("测试座位")
        state.selectedRegion = LibraryStudyRegionItem(0, "其他自习室")
        assertTrue(state.selectedSeatNames.isEmpty())
        assertNull(state.seatSnapshot)
        assertFalse(state.prepareSubmission())
    }

    @Test fun `偏好布局跨午夜遵守明确日期固定与明天跟随的原规则`() = runBlocking {
        for (selection in listOf("TOMORROW", "2026-11-18")) {
            val clock = TestClock()
            val state = LibraryBookingDateState(clock, selection)
            query(state)
            state.selectedSeatNames = listOf("测试座位")
            clock.midnight()
            if (selection == "TOMORROW") {
                assertFalse(state.prepareSubmission())
                assertEquals("2026-11-17", state.targetDate.toString())
                assertTrue(state.isReferenceLayout)
                assertNull(state.seatSnapshot)
            } else {
                assertTrue(state.prepareSubmission())
                assertEquals("2026-11-18", state.targetDate.toString())
                assertEquals("2026-11-16", state.layoutDate.toString())
                assertFalse(state.prepareImmediateBooking())
            }
        }
    }

    @Test fun `选择明天默认读取今日座位目录而不请求尚未开放的明天`() = runBlocking {
        val clock = TestClock()
        val state = LibraryBookingDateState(clock, "TOMORROW")
        val dates = mutableListOf<String>()
        state.loadRegions(null) { date ->
            dates += date
            if (date == "2026-11-15") Result.success(listOf(region)) else Result.failure(IllegalStateException("未开放座位查询"))
        }
        state.loadSeats(region, null) { _, date -> dates += date; Result.success(seats(date)) }
        assertEquals(listOf("2026-11-15", "2026-11-15"), dates)
        state.selectedSeatNames = listOf("测试座位")
        assertTrue(state.prepareSubmission())
        assertEquals("2026-11-16", state.draftParams(BookingExecutionMode.CLOUD_HOSTED, "07:00", region.regionName, 1).targetDate)
    }

    @Test fun `使用已知座位创建两种自动计划完全不要求查询成功`() = runBlocking {
        for (mode in listOf(BookingExecutionMode.LOCAL_DEVICE, BookingExecutionMode.CLOUD_HOSTED)) {
            val clock = TestClock()
            val state = LibraryBookingDateState(clock, "TOMORROW")
            state.loadRegions(null) { Result.failure(IllegalStateException("未开放座位查询")) }
            state.selectedRegion = LibraryStudyRegionItem(0, "已知自习室")
            state.selectedSeatNames = listOf("首选座位", "备选座位")
            val writes = mutableListOf<BookingDraftSnapshot>()
            val sender = submitter(state, clock, mode) { writes += it; Result.success(Unit) }
            assertTrue(sender.submitNewAttempt() is BookingSubmitEvent.Accepted)
            assertEquals("2026-11-16", writes.single().targetDate)
            assertEquals("已知自习室", writes.single().areaName)
            assertEquals("首选座位, 备选座位", writes.single().seatLabel)
            assertFalse(state.prepareImmediateBooking())
        }
    }

    @Test fun `刷新遇到未开放也不清空用户已选偏好或阻止开启计划`() = runBlocking {
        val clock = TestClock()
        val state = LibraryBookingDateState(clock, "TOMORROW")
        query(state)
        state.selectedSeatNames = listOf("测试座位")
        state.loadSeats(region, null) { _, _ -> Result.failure(IllegalStateException("未开放座位查询")) }
        assertEquals(listOf("测试座位"), state.selectedSeatNames)
        assertTrue(state.prepareSubmission())
        val sender = submitter(state, clock, BookingExecutionMode.CLOUD_HOSTED) { Result.success(Unit) }
        assertTrue(sender.submitNewAttempt() is BookingSubmitEvent.Accepted)
    }

    @Test fun `缓存与手工偏好可建计划但不能直接即时预约`() = runBlocking {
        val state = LibraryBookingDateState(TestClock(), "TODAY")
        state.loadRegions(null) { Result.success(listOf(region)) }
        state.loadSeats(region, null) { _, date -> Result.success(seats(date).copy(isCachedLayout = true)) }
        state.selectedSeatNames = listOf("测试座位")
        assertTrue(state.prepareSubmission())
        assertFalse(state.prepareImmediateBooking())
        state.loadSeats(region, null) { _, date -> Result.success(seats(date)) }
        assertTrue(state.prepareImmediateBooking())
        state.loadSeats(region, null) { _, _ -> Result.failure(IllegalStateException("未开放")) }
        assertTrue(state.prepareSubmission())
        assertFalse(state.prepareImmediateBooking())
    }

    @Test fun `手工区域同为零编号也不能在更名后确认旧报价`() = runBlocking {
        val clock = TestClock()
        val machine = LibraryBookingSubmitStateMachine()
        val state = LibraryBookingDateState(clock, "TOMORROW", machine::cancelPendingReconfirmation)
        state.selectedRegion = LibraryStudyRegionItem(0, "区域甲")
        state.selectedSeatNames = listOf("001")
        var calls = 0
        val sender = submitter(state, clock, BookingExecutionMode.CLOUD_HOSTED, machine) {
            calls++; Result.failure(CloudPlanPriceDriftException(10000, 20000, "测试报价"))
        }
        assertTrue(sender.submitNewAttempt() is BookingSubmitEvent.PriceReconfirmationRequired)
        state.selectedRegion = LibraryStudyRegionItem(0, "区域乙")
        state.selectedSeatNames = listOf("001")
        assertEquals(BookingSubmitEvent.StaleConfirmationRefused, sender.confirmPendingReconfirmation())
        assertEquals(1, calls)
    }
    @Test fun `指定座位和复用计划保留座位名称内部空格及候选顺序`() {
        assertEquals(listOf("05桌 A座", "02桌 B座"),
            parseLibrarySeatPreferences("05桌 A座, 02桌 B座，05桌 A座"))
    }
    @Test fun `刷新在途时用户清空座位不能被晚到的历史偏好重新填回`() = runBlocking {
        val clock = TestClock()
        val state = LibraryBookingDateState(clock, "TOMORROW")
        query(state)
        val recent = LibrarySeatBookingRecord(1, 2, "测试座位", region.regionName, region.id,
            "2026-11-15 07:00:00", "2026-11-15 22:00:00", LibrarySeatBookingStatus.SIGNED_OUT)
        val gate = CompletableDeferred<Unit>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            state.loadSeats(region, recent) { _, date -> gate.await(); Result.success(seats(date)) }
        }
        state.selectedSeatNames = emptyList()
        gate.complete(Unit); pending.await()
        assertTrue(state.selectedSeatNames.isEmpty())
        var writes = 0
        val sender = submitter(state, clock, BookingExecutionMode.CLOUD_HOSTED) { writes++; Result.success(Unit) }
        assertEquals(BookingSubmitEvent.StaleConfirmationRefused, sender.submitNewAttempt())
        assertEquals(0, writes)
    }
}

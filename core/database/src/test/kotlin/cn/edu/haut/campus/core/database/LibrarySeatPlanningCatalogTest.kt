package cn.edu.haut.campus.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatItem
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 临时真实 DataStore 文件与假学校接口；不访问学校账号。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class LibrarySeatPlanningCatalogTest {
    private val file = File.createTempFile("seat_catalog_", ".preferences_pb")
    private var job = SupervisorJob()
    private var store = newStore()
    private val room = LibraryStudyRegionItem(1, "测试自习室", totalSeats = 2)
    private val date = "2026-11-15"
    private fun newStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
    private fun snapshot(region: LibraryStudyRegionItem = room, name: String = "001") =
        LibraryRegionSeatsSnapshot(region.id, region.regionName, date, "07:00", "22:00", false,
            listOf(LibrarySeatItem(10, name, 2)))
    private fun catalog(
        regions: suspend (String) -> Result<List<LibraryStudyRegionItem>> = { Result.success(listOf(room)) },
        seats: suspend (Long, String) -> Result<LibraryRegionSeatsSnapshot> = { _, _ -> Result.success(snapshot()) },
        now: () -> Long = { 100L }
    ) = LibrarySeatPlanningCatalog(store, regions, seats, now)

    @After fun close() = runBlocking { job.cancelAndJoin(); file.delete(); Unit }

    @Test fun `重建后学校未开放仍可读磁盘目录且绝不伪造空闲`() = runBlocking {
        val writer = catalog()
        writer.loadRegions(date).getOrThrow()
        writer.loadSeats(room, date).getOrThrow()
        job.cancelAndJoin()
        job = SupervisorJob(); store = newStore()
        var calls = 0
        val offline = catalog(regions = { calls++; Result.failure(Exception("未开放")) },
            seats = { _, _ -> calls++; Result.failure(Exception("未开放")) })
        assertEquals(room.regionName, offline.loadRegions("2026-11-16").getOrThrow().single().regionName)
        val result = offline.loadSeats(room, "2026-11-16").getOrThrow()
        assertEquals(0, calls)
        assertEquals(date, result.bookingDate)
        assertTrue(result.isCachedLayout)
        assertFalse(result.seats.single().isAvailable)
        assertEquals(0, result.seats.single().seatStatus)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("seatStatus"))
    }

    @Test fun `更新为空或未开放不能清掉已有目录`() = runBlocking {
        catalog().apply { loadRegions(date); loadSeats(room, date) }
        val unavailable = catalog(regions = { Result.success(emptyList()) },
            seats = { _, _ -> Result.failure(Exception("未开放")) })
        assertEquals(1, unavailable.loadRegions(date, refresh = true).getOrThrow().size)
        assertEquals("001", unavailable.loadSeats(room, date, refresh = true).getOrThrow().seats.single().seatName)
    }

    @Test fun `区域编号复用时不同房间不能取得旧房间的目录`() = runBlocking {
        catalog().loadSeats(room, date).getOrThrow()
        val offline = catalog(seats = { _, _ -> Result.failure(Exception("未开放")) })
        assertTrue(offline.loadSeats(room.copy(regionName = "另一间"), date).isFailure)
    }

    @Test fun `旧查询晚到不能覆盖较新的目录且不同房间并发不丢失`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val old = catalog(seats = { _, _ -> gate.await(); Result.success(snapshot(name = "旧座位")) }, now = { 100L })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { old.loadSeats(room, date, refresh = true) }
        val next = room.copy(id = 2, regionName = "二号自习室")
        val latest = catalog(seats = { id, _ -> Result.success(snapshot(if (id == 1L) room else next, "新座位")) }, now = { 200L })
        awaitAll(async { latest.loadSeats(room, date, true) }, async { latest.loadSeats(next, date, true) })
        gate.complete(Unit); pending.await()
        val reader = catalog(seats = { _, _ -> error("有缓存不应联网") })
        assertEquals("新座位", reader.loadSeats(room, date).getOrThrow().seats.single().seatName)
        assertEquals("新座位", reader.loadSeats(next, date).getOrThrow().seats.single().seatName)
    }
    @Test fun `学校布局未重复提供区域名称仍按请求区域保存目录`() = runBlocking {
        val source = catalog(seats = { _, _ -> Result.success(snapshot().copy(regionName = "")) })
        source.loadSeats(room, date).getOrThrow()
        val cached = catalog(seats = { _, _ -> error("已有目录") }).loadSeats(room, date).getOrThrow()
        assertEquals(room.regionName, cached.regionName)
        assertEquals("001", cached.seats.single().seatName)
    }
}

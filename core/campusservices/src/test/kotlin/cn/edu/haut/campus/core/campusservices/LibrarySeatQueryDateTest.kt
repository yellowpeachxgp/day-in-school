package cn.edu.haut.campus.core.campusservices

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class LibrarySeatQueryDateTest {
    @Test fun `学校实际查询端口保持目标绝对日期且不伪造未来空闲`() = runBlocking {
        val target = "2030-01-02"
        val queries = mutableListOf<String>()
        val get: (String, String) -> String = { url, _ ->
            queries += URLDecoder.decode(url, "UTF-8")
            when {
                url.contains("/getDefaultTime") -> """{"code":200,"startDate":"2000-01-01","endDate":"2000-01-01","startTime":"07:30","endTime":"22:00"}"""
                url.contains("/layout/building") -> """{"code":200,"builderInfo":[{"id":1,"buildingName":"测试馆"}]}"""
                url.contains("/layout/region") -> """{"code":200,"regionList":[{"id":7,"regionName":"测试区","num":10,"cannum":3}]}"""
                url.contains("/layout/query") -> """{"code":200,"layout":{"regionName":"测试区"},"seatList":[{"id":1,"seatName":"测试区01","seatStatus":1}]}"""
                else -> error("未预期端口：$url")
            }
        }
        val regions = HautLibrarySeatAuthClient.fetchLibraryStudyRegions("test-query-token-0000", target, get).getOrThrow()
        val seats = HautLibrarySeatAuthClient.fetchLibraryRegionSeats("test-query-token-0000", 7, target, get).getOrThrow()
        assertEquals(3, regions.single().availableSeats)
        assertFalse(seats.seats.single().isAvailable)
        assertEquals(target, seats.bookingDate)
        assertTrue(queries.filter { it.contains("starttime=") }.all { it.contains("starttime=$target ") && it.contains("endtime=$target ") })
        assertTrue(queries.filter { it.contains("bookingdate=") }.all { it.contains("bookingdate=$target") })
    }

    @Test fun `目标日期查询未开放不得静默改查今天`() = runBlocking {
        val calls = mutableListOf<String>()
        val target = "2030-01-02"
        val result = HautLibrarySeatAuthClient.fetchLibraryRegionSeats("test-query-token-0000", 7, target) { url, _ ->
            calls += URLDecoder.decode(url, "UTF-8")
            if (url.contains("getDefaultTime")) """{"code":200,"startTime":"07:30","endTime":"22:00"}"""
            else """{"code":500,"msg":"seat.10057"}"""
        }
        assertTrue(result.isFailure)
        assertTrue(calls.all { !it.contains("starttime=") || it.contains("starttime=$target ") })
    }
}

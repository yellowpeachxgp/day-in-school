package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.campusservices.OnlineUtilityDevice
import cn.edu.haut.campus.core.campusservices.UtilityDailySample
import cn.edu.haut.campus.core.campusservices.UtilityMeterCategory
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DormElectricityNotificationSubsystemTest {

    @Test
    fun parseBalanceAndFormatDefaultUnit_handlesRawAndPlainNumbers() {
        assertEquals(28.5, DormElectricityNotificationSubsystem.parseBalanceNumber("28.50度")!!, 1e-6)
        assertEquals(12.3, DormElectricityNotificationSubsystem.parseBalanceNumber("12.3 kWh")!!, 1e-6)
        assertEquals(9.0, DormElectricityNotificationSubsystem.parseBalanceNumber("9")!!, 1e-6)
        assertNull(DormElectricityNotificationSubsystem.parseBalanceNumber("—"))

        assertEquals("3.40度", DormElectricityNotificationSubsystem.formatMetricWithDefaultUnit("3.4", "度"))
        assertEquals("28.50度", DormElectricityNotificationSubsystem.formatMetricWithDefaultUnit("28.50度", "度"))
        assertEquals("—", DormElectricityNotificationSubsystem.formatMetricWithDefaultUnit("—", "度"))
    }

    @Test
    fun shouldTriggerAutoNotify_deduplicatesByDateAndRespectsHourOrLowBalance() {
        // Already notified today -> never re-notify
        assertFalse(
            DormElectricityNotificationSubsystem.shouldTriggerAutoNotify(
                enabled = true,
                lastNotifiedDate = "2026-11-07",
                todayDateText = "2026-11-07",
                currentHourInShanghai = 21,
                targetHour = 20,
                isLowBalance = true
            )
        )

        // Normal balance before target hour -> wait until targetHour
        assertFalse(
            DormElectricityNotificationSubsystem.shouldTriggerAutoNotify(
                enabled = true,
                lastNotifiedDate = "2026-11-06",
                todayDateText = "2026-11-07",
                currentHourInShanghai = 15,
                targetHour = 20,
                isLowBalance = false
            )
        )

        // Normal balance at or after target hour -> notify
        assertTrue(
            DormElectricityNotificationSubsystem.shouldTriggerAutoNotify(
                enabled = true,
                lastNotifiedDate = "2026-11-06",
                todayDateText = "2026-11-07",
                currentHourInShanghai = 20,
                targetHour = 20,
                isLowBalance = false
            )
        )

        // Low balance during daytime (>= 08:00) -> notify early even before targetHour
        assertTrue(
            DormElectricityNotificationSubsystem.shouldTriggerAutoNotify(
                enabled = true,
                lastNotifiedDate = "2026-11-06",
                todayDateText = "2026-11-07",
                currentHourInShanghai = 10,
                targetHour = 20,
                isLowBalance = true
            )
        )
    }

    @Test
    fun buildNotificationPayload_formatsOnlineRoomNormalAndLowBalance() {
        val normalDevicesJson = encodeOnlineDevicesJson(
            listOf(
                OnlineUtilityDevice(
                    name = "照明电表",
                    category = UtilityMeterCategory.ELECTRICITY,
                    balanceRaw = "42.80度",
                    todayUseRaw = "3.60度",
                    totalBoughtRaw = "200.00度",
                    statusRaw = "正常"
                )
            )
        )
        val normalRoom = UtilityOnlineRoomDomain(
            roomId = "room-1",
            roomName = "北苑3号楼402",
            campusCode = "LIANHUA",
            devicesJson = normalDevicesJson,
            updatedAtEpochMs = 1_762_500_000_000L,
            failed = false
        )

        val normalPayload = DormElectricityNotificationSubsystem.buildNotificationPayload(
            onlineRooms = listOf(normalRoom),
            dailySamples = emptyList(),
            manualMeters = emptyList(),
            manualReadings = emptyList(),
            todayDateText = "2026-11-07",
            lowThreshold = 15.0
        )
        assertNotNull(normalPayload)
        assertFalse(normalPayload!!.isLowBalance)
        assertEquals("今日宿舍用电 · 北苑3号楼402", normalPayload.title)
        assertEquals("今日用电 3.60度 · 剩余电量 42.80度", normalPayload.body)

        val lowDevicesJson = encodeOnlineDevicesJson(
            listOf(
                OnlineUtilityDevice(
                    name = "照明电表",
                    category = UtilityMeterCategory.ELECTRICITY,
                    balanceRaw = "9.50度",
                    todayUseRaw = "—",
                    totalBoughtRaw = "200.00度",
                    statusRaw = "正常"
                )
            )
        )
        val lowRoom = normalRoom.copy(devicesJson = lowDevicesJson)
        val sample = UtilityDailySample(
            id = "room-1:照明电表:2026-11-07",
            roomId = "room-1",
            roomName = "北苑3号楼402",
            deviceName = "照明电表",
            dateText = "2026-11-07",
            usedValue = 4.25,
            unit = "kWh",
            sampledAtEpochMs = 1_762_500_000_000L
        )
        val lowPayload = DormElectricityNotificationSubsystem.buildNotificationPayload(
            onlineRooms = listOf(lowRoom),
            dailySamples = listOf(sample),
            manualMeters = emptyList(),
            manualReadings = emptyList(),
            todayDateText = "2026-11-07",
            lowThreshold = 15.0
        )
        assertNotNull(lowPayload)
        assertTrue(lowPayload!!.isLowBalance)
        assertEquals("电费不足 · 北苑3号楼402", lowPayload.title)
        assertEquals("今日用电 4.25度 · 剩余电量 9.50度，请及时充电费", lowPayload.body)
    }

    @Test
    fun buildNotificationPayload_fallsBackToManualMeterWhenNoOnlineRoom() {
        val meter = UtilityMeterDomain(
            id = "meter-1",
            name = "宿舍电表",
            campusName = "莲花街校区",
            roomName = "南苑6号楼208",
            unit = "度",
            lowThreshold = 15.0,
            createdAtEpochMs = 1_762_400_000_000L
        )
        val readings = listOf(
            UtilityReadingDomain("r1", "meter-1", 18.0, 0.0, 1_762_400_000_000L, ""),
            UtilityReadingDomain("r2", "meter-1", 12.5, 0.0, 1_762_486_400_000L, "")
        )
        val payload = DormElectricityNotificationSubsystem.buildNotificationPayload(
            onlineRooms = emptyList(),
            dailySamples = emptyList(),
            manualMeters = listOf(meter),
            manualReadings = readings,
            todayDateText = "2026-11-07",
            lowThreshold = 15.0
        )
        assertNotNull(payload)
        assertTrue(payload!!.isLowBalance)
        assertEquals("电费不足 · 南苑6号楼208", payload.title)
        assertEquals("近期已用 5.50度 · 剩余 12.50度，请及时充电费", payload.body)
    }
}

package cn.edu.haut.campus.core.automation.android

import androidx.room.Room
import cn.edu.haut.campus.core.campusservices.OnlineUtilityDevice
import cn.edu.haut.campus.core.campusservices.UtilityMeterCategory
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.database.UtilityOnlineRoomEntity
import cn.edu.haut.campus.core.database.encodeOnlineDevicesJson
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.events.DomainEventCodecRegistry
import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class UtilityBalanceEventProjectorTest {
    private lateinit var database: HautDatabase
    private val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("acct-utility-projector"),
        profileId = ProfileId("prof-utility-projector"),
        campusId = CampusId("haut-lianhuajie")
    )

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun onlineNumericBalancePublishesOnlyChangesIntoOutbox() = runBlocking {
        upsertRoom("12.50 度", updatedAt = 1000L)
        var projectionNow = Instant.parse("2026-03-02T08:00:00Z")
        val projector = UtilityBalanceEventProjector(database, scope) { projectionNow }

        assertEquals(1, projector.publishChangedBalances())
        assertEquals(0, projector.publishChangedBalances())

        val firstEvent = decodeUtilityEvents().single()
        assertEquals(1250L, firstEvent.balanceCents)
        assertEquals(scope, firstEvent.scope)
        assertNotNull(firstEvent.meterRef)

        projectionNow = projectionNow.plusSeconds(60)
        upsertRoom("10.00 度", updatedAt = 2000L)
        assertEquals(1, projector.publishChangedBalances())
        val events = decodeUtilityEvents()
        assertEquals(2, events.size)
        assertEquals(1000L, events.maxByOrNull { it.occurredAt }?.balanceCents)

        database.utilityAndFocusDao().clearAllOnlineRooms()
        upsertRoom("余额未知", updatedAt = 3000L)
        val before = database.vaultDao().countDomainOutbox()
        assertEquals(0, projector.publishChangedBalances())
        assertEquals(before, database.vaultDao().countDomainOutbox())
    }

    private suspend fun upsertRoom(balanceRaw: String, updatedAt: Long) {
        database.utilityAndFocusDao().upsertOnlineRooms(
            listOf(
                UtilityOnlineRoomEntity(
                    roomId = "room-1",
                    roomName = "测试宿舍",
                    campusCode = "haut-lianhuajie",
                    devicesJson = encodeOnlineDevicesJson(
                        listOf(
                            OnlineUtilityDevice(
                                name = "电表",
                                category = UtilityMeterCategory.ELECTRICITY,
                                balanceRaw = balanceRaw,
                                todayUseRaw = "0.5",
                                totalBoughtRaw = "20",
                                statusRaw = "正常"
                            )
                        )
                    ),
                    updatedAtEpochMs = updatedAt,
                    failed = false
                )
            )
        )
    }

    private fun decodeUtilityEvents(): List<DomainEvent.UtilityBalanceSampled> =
        database.vaultDao().getOutboxSnapshot().mapNotNull { row ->
            if (row.eventType != "utility.balance.sampled") return@mapNotNull null
            runCatching {
                DomainEventCodecRegistry.DEFAULT.decode(
                    cn.edu.haut.campus.core.events.CanonicalEventEnvelopeCodec.decode(
                        cn.edu.haut.campus.core.events.CanonicalJson.of(row.envelopeJson)
                    )
                ) as? DomainEvent.UtilityBalanceSampled
            }.getOrNull()
        }
}

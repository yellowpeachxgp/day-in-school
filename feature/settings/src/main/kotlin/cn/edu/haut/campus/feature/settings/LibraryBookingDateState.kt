package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatBookingRecord
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 页面、查询和提交共用的绝对日期。仅今天/明天跟随上海日期，指定日期始终固定。 */
class LibraryBookingDateState(
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Shanghai")),
    initialSelection: String = if (LocalTime.now(clock).hour >= 7) "TOMORROW" else "TODAY",
    private val onDateChanged: () -> Unit = {}
) {
    var selection by mutableStateOf(initialSelection)
        private set
    var today by mutableStateOf(LocalDate.now(clock))
        private set
    var targetDate by mutableStateOf(resolveDate())
        private set
    var regions by mutableStateOf<List<LibraryStudyRegionItem>>(emptyList())
        private set
    private var regionValue by mutableStateOf<LibraryStudyRegionItem?>(null)
    var selectedRegion: LibraryStudyRegionItem?
        get() = regionValue
        set(value) {
            if (value?.id != regionValue?.id) {
                seatRequest++
                seatSnapshot = null
                selectedSeatNames = emptyList()
                isLoadingSeats = false
                onDateChanged()
            }
            regionValue = value
        }
    var seatSnapshot by mutableStateOf<LibraryRegionSeatsSnapshot?>(null)
        private set
    var selectedSeatNames by mutableStateOf<List<String>>(emptyList())
    var isLoadingRegions by mutableStateOf(false)
        private set
    var isLoadingSeats by mutableStateOf(false)
        private set
    var regionsLoadError by mutableStateOf<String?>(null)
        private set
    var seatsLoadError by mutableStateOf<String?>(null)
        private set
    private var revision = 0L
    private var regionRequest = 0L
    private var seatRequest = 0L

    private fun resolveDate(): LocalDate = when (selection) {
        "TODAY" -> today
        "TOMORROW" -> today.plusDays(1)
        else -> LocalDate.parse(selection)
    }

    fun choose(value: String) {
        selection = value
        refreshClock()
        updateDate()
    }

    fun refreshClock(): Boolean {
        today = LocalDate.now(clock)
        return updateDate()
    }

    private fun updateDate(): Boolean {
        val next = resolveDate()
        if (next == targetDate) return false
        targetDate = next
        revision++
        regionRequest++
        seatRequest++
        regions = emptyList()
        selectedRegion = null
        seatSnapshot = null
        selectedSeatNames = emptyList()
        isLoadingRegions = false
        isLoadingSeats = false
        regionsLoadError = null
        seatsLoadError = null
        onDateChanged()
        return true
    }

    /** 点击时发现跨日先更新页面并拒绝本次操作，待新结果呈现后再点击。 */
    fun prepareSubmission(): Boolean = !refreshClock() && targetDate >= today &&
        seatSnapshot?.bookingDate == targetDate.toString() &&
        seatSnapshot?.regionId == selectedRegion?.id && selectedSeatNames.isNotEmpty() &&
        selectedSeatNames.all { name -> seatSnapshot?.seats?.any { it.seatName == name } == true }

    fun draftParams(engine: BookingExecutionMode, time: String, area: String, repeatDays: Int): BookingDraftParams {
        refreshClock()
        return BookingDraftParams(
            engine, targetDate.toString(), time, area, selectedSeatNames.joinToString(", "), repeatDays,
            if (engine == BookingExecutionMode.CLOUD_HOSTED) engine.priceMicropeach else null,
            dateRevision = revision
        )
    }

    suspend fun loadRegions(
        recent: LibrarySeatBookingRecord?,
        fetch: suspend (String) -> Result<List<LibraryStudyRegionItem>>
    ) {
        refreshClock()
        val date = targetDate
        val epoch = revision
        val request = ++regionRequest
        isLoadingRegions = true
        regionsLoadError = null
        val result = fetch(date.toString())
        refreshClock()
        if (epoch != revision || request != regionRequest) return
        result.onSuccess { list ->
            regions = list
            selectedRegion = list.firstOrNull { it.id == selectedRegion?.id }
                ?: list.firstOrNull { it.id == recent?.buildingLayerRegionId }
                ?: list.firstOrNull { recent?.regionName?.contains(it.regionName) == true }
                ?: list.firstOrNull()
            if (selectedRegion == null) { seatSnapshot = null; selectedSeatNames = emptyList() }
        }.onFailure { regionsLoadError = it.message ?: "读取自习室列表失败，请重试" }
        isLoadingRegions = false
    }

    suspend fun loadSeats(
        region: LibraryStudyRegionItem,
        recent: LibrarySeatBookingRecord?,
        fetch: suspend (Long, String) -> Result<LibraryRegionSeatsSnapshot>
    ) {
        refreshClock()
        if (selectedRegion?.id != region.id) return
        val date = targetDate
        val epoch = revision
        val request = ++seatRequest
        seatSnapshot = null
        selectedSeatNames = emptyList()
        isLoadingSeats = true
        seatsLoadError = null
        val result = fetch(region.id, date.toString())
        refreshClock()
        if (epoch != revision || request != seatRequest || selectedRegion?.id != region.id) return
        result.onSuccess { snap ->
            if (snap.bookingDate != date.toString() || snap.regionId != region.id) {
                seatsLoadError = "座位结果日期或区域已变化，请重新查询"
                return@onSuccess
            }
            seatSnapshot = snap
            val recentSeat = recent?.seatName?.takeIf { name ->
                recent.regionName.contains(region.regionName) && snap.seats.any { it.seatName == name }
            }
            selectedSeatNames = recentSeat?.let { listOf(it) }
                ?: snap.seats.filter { it.isAvailable }.take(2).map { it.seatName }
        }.onFailure { seatsLoadError = it.message ?: "读取座位失败，请重试" }
        isLoadingSeats = false
    }
}

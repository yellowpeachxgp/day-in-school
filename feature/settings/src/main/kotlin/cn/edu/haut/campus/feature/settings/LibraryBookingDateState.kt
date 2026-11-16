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

/** 目标日期是计划事实；座位目录只用于选择偏好，学校当天的空闲状态不限制提前建计划。 */
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
    var layoutDate by mutableStateOf(today)
        private set
    val isReferenceLayout: Boolean get() = layoutDate != targetDate || seatSnapshot?.isCachedLayout == true
    var regions by mutableStateOf<List<LibraryStudyRegionItem>>(emptyList())
        private set
    private var regionValue by mutableStateOf<LibraryStudyRegionItem?>(null)
    var selectedRegion: LibraryStudyRegionItem?
        get() = regionValue
        set(value) {
            if (value?.regionName != regionValue?.regionName || value?.id != regionValue?.id) {
                seatRequest++
                seatSnapshot = null
                isLoadingSeats = false
            }
            if (value?.regionName != regionValue?.regionName) {
                selectedSeatNames = emptyList()
                contextChanged()
            }
            regionValue = value
        }
    var seatSnapshot by mutableStateOf<LibraryRegionSeatsSnapshot?>(null)
        private set
    private var seatNamesValue by mutableStateOf<List<String>>(emptyList())
    var selectedSeatNames: List<String>
        get() = seatNamesValue
        set(value) {
            if (value != seatNamesValue) { seatNamesValue = value.toList(); contextChanged() }
        }
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

    val canCreatePlan: Boolean get() = selectedRegion?.regionName?.isNotBlank() == true &&
        selectedSeatNames.size in 1..3 && selectedSeatNames.all { it.isNotBlank() } &&
        selectedSeatNames.distinct().size == selectedSeatNames.size

    private fun contextChanged() { revision++; onDateChanged() }
    private fun resolveDate(): LocalDate = when (selection) {
        "TODAY" -> today
        "TOMORROW" -> today.plusDays(1)
        else -> LocalDate.parse(selection)
    }

    fun choose(value: String) { selection = value; refreshClock() }

    fun refreshClock(): Boolean {
        val previousToday = today
        today = LocalDate.now(clock)
        val next = resolveDate()
        val changed = next != targetDate
        if (changed) { targetDate = next; contextChanged() }
        if (changed || previousToday != today) {
            layoutDate = today
            invalidateQueries()
        }
        return changed
    }

    private fun invalidateQueries() {
        regionRequest++; seatRequest++
        seatSnapshot = null
        isLoadingRegions = false; isLoadingSeats = false
        regionsLoadError = null; seatsLoadError = null
    }

    /** 点击时才发现相对日期已跨日，先更新页面并拒绝本次操作；已选偏好仍保留供再次确认。 */
    fun prepareSubmission(): Boolean = !refreshClock() && targetDate >= today && canCreatePlan

    /** 即时预约仍要求本次实时结果；缓存或手工偏好只能用于计划。 */
    fun prepareImmediateBooking(): Boolean = prepareSubmission() && !isLoadingSeats &&
        seatSnapshot?.let { snap ->
            !snap.isCachedLayout && snap.bookingDate == targetDate.toString() &&
                snap.regionId == selectedRegion?.id && snap.regionName == selectedRegion?.regionName &&
                selectedSeatNames.all { name -> snap.seats.any { it.seatName == name } }
        } == true

    fun draftParams(engine: BookingExecutionMode, time: String, area: String, repeatDays: Int): BookingDraftParams {
        refreshClock()
        return BookingDraftParams(
            engine, targetDate.toString(), time, area, selectedSeatNames.joinToString(", "), repeatDays,
            if (engine == BookingExecutionMode.CLOUD_HOSTED) engine.priceMicropeach else null,
            dateRevision = revision
        )
    }

    suspend fun loadRegions(recent: LibrarySeatBookingRecord?, fetch: suspend (String) -> Result<List<LibraryStudyRegionItem>>) {
        refreshClock()
        val request = ++regionRequest
        val epoch = revision
        isLoadingRegions = true
        regionsLoadError = null
        val result = fetch(layoutDate.toString())
        refreshClock()
        if (request != regionRequest) return
        result.onSuccess { list ->
            regions = list
            // 网络结果只能补全同名区域的信息，不能把用户刚改过的选择换成列表第一项。
            if (epoch == revision) {
                val chosen = selectedRegion
                selectedRegion = if (chosen != null) {
                    list.firstOrNull { it.regionName == chosen.regionName } ?: chosen.copy(id = 0)
                } else {
                    list.firstOrNull { it.regionName == recent?.regionName }
                        ?: list.firstOrNull()
                }
            }
        }.onFailure { regionsLoadError = it.message ?: "暂时无法更新座位目录" }
        isLoadingRegions = false
    }

    suspend fun loadSeats(region: LibraryStudyRegionItem, recent: LibrarySeatBookingRecord?,
        fetch: suspend (Long, String) -> Result<LibraryRegionSeatsSnapshot>
    ) {
        refreshClock()
        if (region.id <= 0 || selectedRegion?.id != region.id || selectedRegion?.regionName != region.regionName) return
        val date = layoutDate
        val request = ++seatRequest
        seatSnapshot = null
        isLoadingSeats = true
        seatsLoadError = null
        val result = fetch(region.id, date.toString())
        refreshClock()
        if (request != seatRequest || selectedRegion?.id != region.id || selectedRegion?.regionName != region.regionName) return
        result.onSuccess { snap ->
            if ((!snap.isCachedLayout && snap.bookingDate != date.toString()) || snap.regionId != region.id) {
                seatsLoadError = "座位目录的日期或区域已变化，请更新目录"
                return@onSuccess
            }
            seatSnapshot = snap.copy(regionName = region.regionName)
            if (selectedSeatNames.isEmpty() && recent?.regionName == region.regionName && recent.seatName.isNotBlank()) {
                selectedSeatNames = listOf(recent.seatName)
            }
        }.onFailure { seatsLoadError = it.message ?: "暂时无法更新座位目录" }
        isLoadingSeats = false
    }
}

/** 保留名称内部空格，例如「05桌 A座」；分隔规则与执行器一致。 */
internal fun parseLibrarySeatPreferences(text: String): List<String> =
    text.split(",", "，", "、", "/", "|", ";", "；", "\n")
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()

package cn.edu.haut.campus.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.model.PeriodTime
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 自习室页「时钟 × 槽位选择」状态机（纯 JVM 可测，Compose 状态字段可被界面订阅）：
 *
 * - 时钟 tick **永远推进**：[refreshNow] 每次都会刷新 [today] 与 [autoSlotResolution]
 *   （跨午夜日期翻页、课间→上课自动跟槽、ON_RESUME 即时重定位皆由它驱动）；
 * - 手动点选仅保护 [selectedSlotIndex] 不被自动重定位覆盖，**绝不暂停时钟**——
 *   修复旧实现"手动模式停 tick → today/解析结果最多冻结 60s 甚至跨午夜"的缺陷；
 * - 日期轴同理分自动/手动：[manualSelectedDate] 持久保存用户点选的**绝对日期**，
 *   自动模式（null）下 [selectedDate] 永远等于最新 [today]——跨午夜自动跟到新今日，
 *   绝不把陈旧星期几带进新一周（旧实现把周日选择滑成"下周日"+7 天的缺陷）；
 *   手动日期若早于新 today 则以 [manualDateExpired] 诚实标记过期，绝不静默改指下周；
 * - 「恢复自动定位」必须立即按注入时钟重解析并选中当前槽位，绝不复用最多 60s 前的旧解析；
 * - [nowProvider] 为可注入时钟缝：测试用假时钟驱动 tick/跨午夜/恢复自动，不依赖真机时间。
 */
class EmptyClassroomSlotController(
    private val nowProvider: () -> ZonedDateTime
) {
    /** 当前日期（时钟推进即更新，跨午夜自动翻页） */
    var today: LocalDate by mutableStateOf(nowProvider().toLocalDate())
        private set

    /** 当前自动槽位解析结果（永远反映最后一次时钟推进，绝非陈旧值） */
    var autoSlotResolution: CampusStudyRoomCatalog.StudySlotResolution by mutableStateOf(
        CampusStudyRoomCatalog.resolveActiveSlotForTimeTable(
            nowTime = nowProvider().toLocalTime(),
            periodTimes = emptyList(),
            timeTableId = null
        )
    )
        private set

    var selectedSlotIndex: Int by mutableIntStateOf(autoSlotResolution.slotIndex)
        private set

    var slotManuallyPicked: Boolean by mutableStateOf(false)
        private set

    /**
     * 手动点选的**绝对日期**；null = 自动模式。
     * 以绝对日期而非「周偏移+星期几」固化点击意图：跨午夜后绝不随周一对齐滑入下一周。
     */
    var manualSelectedDate: LocalDate? by mutableStateOf(null)
        private set

    /**
     * 当前选中日期：手动模式为点选当天，自动模式永远等于最新 [today]——
     * 跨午夜自动模式选中日期即新今日（绝非"上周选的星期几"在新一周的投影）。
     */
    val selectedDate: LocalDate
        get() = manualSelectedDate ?: today

    /**
     * 手动所选日期早于今日 → 已过期（manual-stale）：UI 必须诚实标注并给出回到今日的入口，
     * 绝不把过期选择静默改指为下周同星期。
     */
    val manualDateExpired: Boolean
        get() = manualSelectedDate?.isBefore(today) == true

    /** 用户手动点选日期（界面按其周偏移/星期几语义换算后的绝对日期） */
    fun pickDate(date: LocalDate) {
        manualSelectedDate = date
    }

    /** 退出手动日期选择：回到自动模式，选中日期立即重新跟随最新 [today] */
    fun restoreAutoDate() {
        manualSelectedDate = null
    }

    /**
     * 时钟推进入口（60s tick / ON_RESUME / 作息表数据到达）：
     * 无条件刷新 today 与自动解析；仅在非手动模式下让选中槽位跟随自动定位。
     */
    fun refreshNow(periodTimes: List<PeriodTime>, timeTableId: String?) {
        val now = nowProvider()
        today = now.toLocalDate()
        autoSlotResolution = CampusStudyRoomCatalog.resolveActiveSlotForTimeTable(
            nowTime = now.toLocalTime(),
            periodTimes = periodTimes,
            timeTableId = timeTableId
        )
        if (!slotManuallyPicked) {
            selectedSlotIndex = autoSlotResolution.slotIndex
        }
    }

    /** 用户手动点选节次：仅锁定选中槽位，时钟与自动解析照常推进 */
    fun pickSlot(index: Int) {
        slotManuallyPicked = true
        selectedSlotIndex = index
    }

    /**
     * 恢复自动定位：立即按注入时钟重解析当前槽位并选中（不等下一个 tick、绝不读旧解析）。
     */
    fun restoreAuto(periodTimes: List<PeriodTime>, timeTableId: String?) {
        slotManuallyPicked = false
        refreshNow(periodTimes, timeTableId)
    }
}

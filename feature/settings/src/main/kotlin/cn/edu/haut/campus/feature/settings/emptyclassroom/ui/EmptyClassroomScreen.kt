package cn.edu.haut.campus.feature.settings.emptyclassroom.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.feature.settings.emptyclassroom.state.EmptyClassroomUiState
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackBanner
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackTone
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSearchField
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.HospitalMedicineCategory
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CampusClubCategory
import cn.edu.haut.campus.core.model.CampusClubItem
import cn.edu.haut.campus.core.model.CampusCompetitionCategory
import cn.edu.haut.campus.core.model.CampusCompetitionItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.CompetitionCareerGoal
import cn.edu.haut.campus.core.model.CompetitionCompassEngine
import cn.edu.haut.campus.core.model.CompetitionGradeStage
import cn.edu.haut.campus.core.model.CompetitionSeasonWindow
import cn.edu.haut.campus.core.model.CompetitionSkillPreference
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.PresetQqChannelItem
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.feature.settings.emptyclassroom.state.EmptyClassroomSlotController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmptyClassroomScreenContent(
    state: EmptyClassroomUiState,
    onNavigateToMapWithRoom: (String) -> Unit,
    onBack: () -> Unit,
    nowProvider: () -> ZonedDateTime = { ZonedDateTime.now(HAUT_ZONE_ID) }
) {
    val currentTerm = state.currentTerm
    val courses = state.courses
    val rules = state.rules
    val periodTimes = state.periodTimes
    val calendarOverrides = state.calendarOverrides
    val occurrenceOverrides = state.occurrenceOverrides
    val prefs = state.prefs
    val lifecycleOwner = LocalLifecycleOwner.current

    // 时钟×槽位控制器：时钟 tick 永远推进（today + 自动槽位解析保持最新）；
    // 手动选槽仅保护 selectedSlotIndex，绝不暂停时钟（修复手动模式冻结时钟/跨午夜不翻页缺陷）
    val slotController = remember(nowProvider) { EmptyClassroomSlotController(nowProvider) }
    val today = slotController.today
    val weekMonday = remember(today) {
        today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    val currentTeachingWeek = remember(today, currentTerm, calendarOverrides) {
        currentTerm?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides).teachingWeek } ?: 1
    }

    var selectedCampusKey by remember(prefs.selectedCampusId, rules.size) {
        mutableStateOf(
            if (prefs.selectedCampusId.contains("songshan", ignoreCase = true) ||
                (prefs.selectedCampusId.isBlank() &&
                    rules.any { it.rawRoom.contains("嵩山") || it.rawRoom.contains("齐礼阎") })
            ) "songshanlu" else "lianhuajie"
        )
    }
    val campusDisplayName = if (selectedCampusKey == "songshanlu") "嵩山路校区" else "莲花街校区"

    // 日期选择全部经 slotController 的「绝对日期」模型：
    // 自动模式 selectedDate 恒等于最新 today——跨午夜后自动跟到新今日，
    // 绝不把陈旧星期几投影进新一周（旧实现周日翻周一后跳到下周日的缺陷）；
    // 手动点选固化为绝对日期，过期由 manualDateExpired 诚实标记。
    val selectedDate = slotController.selectedDate
    val selectedDayOfWeek = selectedDate.dayOfWeek
    val selectedWeekOffset = remember(weekMonday, selectedDate) {
        ChronoUnit.WEEKS.between(
            weekMonday,
            selectedDate.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        ).toInt()
    }
    val selectedDateInCurrentWeek = selectedDate
    val selectedTeachingWeek = remember(selectedDateInCurrentWeek, currentTerm, calendarOverrides, currentTeachingWeek, selectedWeekOffset) {
        currentTerm?.let { TeachingWeekCalculator.resolveDate(selectedDateInCurrentWeek, it, calendarOverrides).teachingWeek }
            ?: (currentTeachingWeek + selectedWeekOffset)
    }
    val selectedDateShortLabel = remember(selectedDateInCurrentWeek) {
        String.format("%02d-%02d", selectedDateInCurrentWeek.monthValue, selectedDateInCurrentWeek.dayOfMonth)
    }
    val autoSlotResolution = slotController.autoSlotResolution
    // "个人无冲突"统计是否具备本学期课表依据：与展开器共用严格"可展开输入"校验
    // （孤儿停课覆盖/跨学期残留 rules/缺作息表且未自带合法时间均不构成依据）；
    // 判定结果带原因态——依据不完整与尚无依据是两种不同的诚实文案
    val scheduleBasisEvaluation = remember(currentTerm, courses, rules, periodTimes, occurrenceOverrides) {
        CampusStudyRoomCatalog.evaluateCurrentTermScheduleBasis(
            term = currentTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            occurrenceOverrides = occurrenceOverrides
        )
    }
    val hasScheduleBasis = scheduleBasisEvaluation.hasBasis
    val basisReasonLabel = scheduleBasisEvaluation.reasonLabel ?: "尚无课表依据"
    val selectedSlotIndex = slotController.selectedSlotIndex
    val slotManuallyPicked = slotController.slotManuallyPicked

    // 时钟与作息数据的最新值（tick/ON_RESUME 协程内读取，避免闭包捕获过期值）
    val latestPeriodTimes by rememberUpdatedState(periodTimes)
    val latestTimeTableId by rememberUpdatedState(currentTerm?.timeTableId)

    // 作息表/学期数据到达或变化时立即重解析（替代原 remember(nowMinuteTick) 依赖）
    LaunchedEffect(periodTimes, currentTerm?.timeTableId) {
        slotController.refreshNow(periodTimes, currentTerm?.timeTableId)
    }
    // 60s 时钟 tick：无条件推进——手动选槽期间也照常刷新 today 与自动解析（跨午夜翻页不冻结）
    LaunchedEffect(slotController) {
        while (true) {
            delay(60_000L)
            slotController.refreshNow(latestPeriodTimes, latestTimeTableId)
        }
    }
    // 回前台立即重解析（ON_RESUME 不等下一个 tick）
    DisposableEffect(lifecycleOwner, slotController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                slotController.refreshNow(latestPeriodTimes, latestTimeTableId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val campusRoomsMap = remember(selectedCampusKey) {
        CampusStudyRoomCatalog.studyRoomsMapForCampus(selectedCampusKey)
    }
    val totalCampusCandidateCount = remember(campusRoomsMap) { campusRoomsMap.values.sumOf { it.size } }
    val buildingKeys = remember(campusRoomsMap) { campusRoomsMap.keys.toList() }
    var selectedBuildingFilter by remember(selectedCampusKey) { mutableStateOf<String?>(null) }

    val activeSlot = CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS[selectedSlotIndex]
    val busyRoomsInSlot = remember(
        selectedDateInCurrentWeek,
        currentTerm,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        activeSlot
    ) {
        CampusStudyRoomCatalog.findBusyRawRoomsForDate(
            date = selectedDateInCurrentWeek,
            term = currentTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            slot = activeSlot
        )
    }
    val freeCampusRoomCount = remember(campusRoomsMap, busyRoomsInSlot) {
        campusRoomsMap.values.flatten().count { code -> CampusStudyRoomCatalog.isRoomFree(code, busyRoomsInSlot) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "自习教室与无课参考",
            subtitle = if (hasScheduleBasis) {
                "$campusDisplayName · 名录 $freeCampusRoomCount/$totalCampusCandidateCount 间个人无冲突参考"
            } else {
                "$campusDisplayName · 名录 $totalCampusCandidateCount 间自习教室 · $basisReasonLabel"
            },
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            XPeachWell(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (hasScheduleBasis) {
                        "数据来源说明：当前展示为校区公共教学楼自习教室名录 + 个人课表无冲突参考（已自动排除您本人在所选节次有课的教室），非全校教务实时排课占用数据，现场请以教室实际使用情况为准。"
                    } else {
                        "$basisReasonLabel：以下为校区公共教学楼自习教室完整名录（未做个人无冲突过滤），非全校教务实时排课占用数据，现场请以教室实际使用情况为准。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "选择校区、周次、星期与节次",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "第${selectedTeachingWeek}周 · $selectedDateShortLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    XPeachFilterPill(
                        label = "莲花街校区",
                        selected = selectedCampusKey == "lianhuajie",
                        onClick = { selectedCampusKey = "lianhuajie" }
                    )
                    XPeachFilterPill(
                        label = "嵩山路校区",
                        selected = selectedCampusKey == "songshanlu",
                        onClick = { selectedCampusKey = "songshanlu" }
                    )
                    XPeachFilterPill(
                        label = "本周",
                        selected = selectedWeekOffset == 0,
                        onClick = {
                            // 保持当前星期几，把选择固化为本周对应的绝对日期
                            slotController.pickDate(
                                weekMonday.plusDays((selectedDayOfWeek.value - 1).toLong())
                            )
                        }
                    )
                    XPeachFilterPill(
                        label = "下周",
                        selected = selectedWeekOffset == 1,
                        onClick = {
                            slotController.pickDate(
                                weekMonday.plusWeeks(1)
                                    .plusDays((selectedDayOfWeek.value - 1).toLong())
                            )
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (day in DayOfWeek.entries) {
                        val isExactToday = selectedWeekOffset == 0 && day == today.dayOfWeek
                        XPeachFilterPill(
                            label = if (isExactToday) "${day.ChineseLabel}(今)" else day.ChineseLabel,
                            selected = selectedDayOfWeek == day,
                            onClick = {
                                // 在当前展示周内点选星期几 → 固化为对应绝对日期，
                                // 跨午夜后绝不随周一对齐漂移成别周同一天
                                slotController.pickDate(
                                    weekMonday.plusWeeks(selectedWeekOffset.toLong())
                                        .plusDays((day.value - 1).toLong())
                                )
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS.forEachIndexed { idx, slot ->
                        XPeachFilterPill(
                            label = slot.label,
                            selected = selectedSlotIndex == idx,
                            onClick = {
                                slotController.pickSlot(idx)
                            }
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = CampusStudyRoomCatalog.studySlotStatusLabel(
                            manuallyPickedSlotLabel = if (slotManuallyPicked) {
                                CampusStudyRoomCatalog.STUDY_PERIOD_SLOTS[selectedSlotIndex].label
                            } else {
                                null
                            },
                            resolution = autoSlotResolution
                        ) + if (slotController.manualDateExpired) {
                            // 手动所选日期已早于今日：诚实标注过期，绝不静默改指下周
                            " · 所选日期已过期"
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (slotManuallyPicked) {
                        TextButton(
                            onClick = {
                                // 立即按注入时钟重解析并选中当前槽位，绝不复用旧解析结果
                                slotController.restoreAuto(latestPeriodTimes, latestTimeTableId)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("恢复自动定位", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (slotController.manualSelectedDate != null) {
                        TextButton(
                            onClick = {
                                // 退出手动日期选择：选中日期立即重新跟随最新今日
                                slotController.restoreAutoDate()
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("回到今天", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    XPeachFilterPill(
                        label = "全部教学楼",
                        selected = selectedBuildingFilter == null,
                        onClick = { selectedBuildingFilter = null }
                    )
                    for (bName in buildingKeys) {
                        XPeachFilterPill(
                            label = bName.removeSuffix("教学楼"),
                            selected = selectedBuildingFilter == bName,
                            onClick = { selectedBuildingFilter = bName }
                        )
                    }
                }
            }

            val buildingsToShow = if (selectedBuildingFilter != null) {
                listOf(selectedBuildingFilter!!)
            } else {
                buildingKeys
            }

            for (buildingName in buildingsToShow) {
                val candidateRooms = campusRoomsMap[buildingName].orEmpty()
                val freeRooms = if (hasScheduleBasis) {
                    candidateRooms.filter { code ->
                        CampusStudyRoomCatalog.isRoomFree(code, busyRoomsInSlot)
                    }
                } else {
                    // 无本学期课表依据：展示完整名录，绝不以跨学期/空输入冒充"全部空闲"
                    candidateRooms
                }
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildingName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        XPeachStatusDotText(
                            text = if (hasScheduleBasis) {
                                "个人无冲突 ${freeRooms.size}/${candidateRooms.size} 间"
                            } else {
                                basisReasonLabel
                            },
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (roomCode in freeRooms) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                                    .border(
                                        width = 0.8.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        val navTarget = if (roomCode.contains("嵩山")) {
                                            roomCode
                                        } else {
                                            "${buildingName.removeSuffix("教学楼")}-$roomCode"
                                        }
                                        onNavigateToMapWithRoom(navTarget)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = roomCode,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = "导航去自习",
                                    tint = XPeachTheme.readablePrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

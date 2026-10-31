package cn.edu.haut.campus.feature.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.academic.HautSessionManager
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.GpaCalculator
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetThemeStyle
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

private val AUDIT_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

/**
 * 莲花街校区主要教学楼典型自习/公共教室池，结合用户本地已导入课表排课数据进行实时空闲教室反查
 */
private val LIANHUAJIE_STUDY_ROOMS_BY_BUILDING: Map<String, List<String>> = linkedMapOf(
    "5号教学楼" to listOf("5101", "5103", "5105", "5201", "5202", "5204", "5301", "5303", "5402"),
    "6号教学楼" to listOf("6101", "6102", "6201", "6203", "6205", "6301", "6304", "6401"),
    "7号教学楼" to listOf("7102", "7104", "7201", "7203", "7302", "7305", "7401"),
    "8号教学楼" to listOf("8101", "8103", "8201", "8202", "8301", "8303", "8402"),
    "9号教学楼" to listOf("9101", "9102", "9201", "9204", "9301", "9303"),
    "11号教学楼" to listOf("11101", "11102", "11201", "11203", "11301", "11302")
)

private data class StudyPeriodSlot(
    val label: String,
    val startPeriod: Int,
    val endPeriod: Int
)

private val STUDY_PERIOD_SLOTS = listOf(
    StudyPeriodSlot("1-2节", 1, 2),
    StudyPeriodSlot("3-4节", 3, 4),
    StudyPeriodSlot("5-6节", 5, 6),
    StudyPeriodSlot("7-8节", 7, 8),
    StudyPeriodSlot("9-10节", 9, 10)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    repository: CampusRepository,
    onBack: (() -> Unit)? = null,
    onNavigateToImporter: () -> Unit = {},
    onNavigateToMapWithRoom: (String) -> Unit = {},
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            scrollState.animateScrollTo(0)
        }
    }

    val studentProfile by repository.studentProfileFlow.collectAsState(initial = null)
    val terms by repository.termsFlow.collectAsState(initial = emptyList())
    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val attempts by repository.gradeAttemptsFlow.collectAsState(initial = emptyList())
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())
    val occurrenceOverrides by repository.occurrenceOverridesFlow.collectAsState(initial = emptyList())
    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())
    val importAudits by repository.importAuditsFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

    val today = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val resolvedWeekInfo = remember(today, currentTerm, calendarOverrides) {
        currentTerm?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides) }
    }
    val currentTeachingWeek = resolvedWeekInfo?.teachingWeek ?: 1

    // 本周总课时计算
    val thisWeekClassCount = remember(
        currentTeachingWeek,
        currentTerm,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings
    ) {
        val activeTerm = currentTerm ?: return@remember 0
        val map = ScheduleEngine.resolveOccurrencesForTeachingWeek(
            teachingWeek = currentTeachingWeek,
            term = activeTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings
        )
        map.values.sumOf { it.size }
    }

    // 学业 GPA 报告
    val officialSnapshot = remember(prefs.officialGpaValuePlain, prefs.officialGpaNote) {
        prefs.officialGpaValuePlain?.toBigDecimalOrNull()?.let { valBd ->
            OfficialGpaSnapshot(
                cumulativeGpa = valBd,
                fetchedAt = Instant.now(),
                fieldDefinitionNote = prefs.officialGpaNote ?: "来自教务官方平均学分绩点"
            )
        }
    }
    val latestTermId = remember(attempts) { attempts.firstOrNull()?.termId }
    val latestAcademicYear = remember(attempts) { attempts.firstOrNull()?.academicYear }
    val gpaReport = remember(attempts, latestTermId, latestAcademicYear, officialSnapshot) {
        GpaCalculator.calculateReport(
            attempts = attempts,
            currentTermId = latestTermId,
            currentAcademicYear = latestAcademicYear,
            officialSnapshot = officialSnapshot
        )
    }

    // 各类弹窗与折叠状态
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showImportHistoryDialog by remember { mutableStateOf(false) }
    var showAddCalendarOverrideDialog by remember { mutableStateOf(false) }
    var editingPeriod by remember { mutableStateOf<PeriodTime?>(null) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    var widgetPrivacyDefault by remember { mutableStateOf(false) }
    var widgetCompactDefault by remember { mutableStateOf(false) }
    var expandPeriodTable by remember { mutableStateOf(false) }
    var expandStudyFinder by remember { mutableStateOf(true) }

    // 空教室速查过滤状态
    val buildingKeys = remember { LIANHUAJIE_STUDY_ROOMS_BY_BUILDING.keys.toList() }
    var selectedStudyBuilding by remember { mutableStateOf(buildingKeys.first()) }
    var selectedSlotIndex by remember {
        val nowHour = ZonedDateTime.now(HAUT_ZONE_ID).hour
        val defaultIdx = when {
            nowHour < 10 -> 0
            nowHour < 12 -> 1
            nowHour < 16 -> 2
            nowHour < 18 -> 3
            else -> 4
        }
        mutableIntStateOf(defaultIdx)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = if (onBack == null) "我的" else "个人中心与设置",
            subtitle = "学籍名片 · 校园工具箱 · 偏好设置",
            badgeText = resolvedWeekInfo?.teachingWeek?.let { "第 $it 周" },
            navigationIcon = if (onBack != null) {
                {
                    XPeachIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        onClick = onBack
                    )
                }
            } else null,
            actions = {
                XPeachIconButton(
                    icon = Icons.Default.History,
                    contentDescription = "同步记录",
                    onClick = { showImportHistoryDialog = true }
                )
                XPeachIconButton(
                    icon = Icons.Default.CloudSync,
                    contentDescription = "教务同步",
                    onClick = onNavigateToImporter
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (feedbackText != null) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = feedbackText!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { feedbackText = null }) {
                            Text("知道了", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            // 1. 数字学生证 / 个人学籍名片
            StudentDigitalIdCard(
                profile = studentProfile,
                privacyMasked = prefs.gpaPrivacyShieldEnabled,
                lastAudit = importAudits.firstOrNull(),
                onEditProfile = { showEditProfileDialog = true },
                onSyncNow = onNavigateToImporter
            )

            // 2. 本学期学业与课表 4 维全景看板
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfileMetricItem(
                        label = "本周课时",
                        value = "${thisWeekClassCount}节",
                        modifier = Modifier.weight(1f)
                    )
                    ProfileMetricItem(
                        label = "在修课程",
                        value = "${courses.size}门",
                        modifier = Modifier.weight(1f)
                    )
                    ProfileMetricItem(
                        label = "已获学分",
                        value = if (prefs.gpaPrivacyShieldEnabled) "**.*" else gpaReport.totalEarnedCredits.stripTrailingZeros().toPlainString(),
                        modifier = Modifier.weight(1f)
                    )
                    ProfileMetricItem(
                        label = "平均绩点",
                        value = if (prefs.gpaPrivacyShieldEnabled) "*.**" else (gpaReport.cumulativeGpa?.toPlainString() ?: "--"),
                        highlight = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. 校历学期进度环 & 调休/停课管理
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                val totalWeeks = (currentTerm?.weekCount ?: 20).coerceAtLeast(1)
                val weekProgress = (currentTeachingWeek.toFloat() / totalWeeks.toFloat()).coerceIn(0.05f, 1f)
                val examStartDate = currentTerm?.startDate?.plusWeeks(18)
                val daysToExam = examStartDate?.let { ChronoUnit.DAYS.between(today, it) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = currentTerm?.displayName ?: "2025-2026 学年",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (daysToExam != null && daysToExam > 0) {
                                "第 $currentTeachingWeek / $totalWeeks 周 · 距期末周约 $daysToExam 天"
                            } else {
                                "第 $currentTeachingWeek / $totalWeeks 周 · 学期进行中"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachStatusBadge(
                        text = "${(weekProgress * 100).toInt()}%",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                LinearProgressIndicator(
                    progress = { weekProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "校历调休与停课安排 (${calendarOverrides.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    XPeachFilterPill(
                        label = "+ 新增调休/停课",
                        selected = false,
                        onClick = { showAddCalendarOverrideDialog = true }
                    )
                }

                if (calendarOverrides.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (ov in calendarOverrides.sortedBy { it.date }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${ov.date} · ${ov.title}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when (ov.overrideType) {
                                            CalendarOverrideType.HOLIDAY_NO_CLASS -> "全天停课放假"
                                            CalendarOverrideType.MAKEUP_FOLLOW_DATE -> "调休补课（按 ${ov.followDate ?: "指定日"} 课表）"
                                            CalendarOverrideType.CUSTOM_TEACHING_WEEK -> "自定义教学周"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            repository.deleteCalendarOverride(ov.id)
                                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                        }
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "删除调休",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. 校园工具箱：莲花街校区「今日空教室 / 自习室速查」
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "空教室与自习室速查",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${today.dayOfWeek.ChineseLabel} · 基于已同步课表反查空闲教室，点击可导航",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachFilterPill(
                        label = if (expandStudyFinder) "收起" else "展开",
                        selected = expandStudyFinder,
                        onClick = { expandStudyFinder = !expandStudyFinder }
                    )
                }

                AnimatedVisibility(visible = expandStudyFinder) {
                    Column(modifier = Modifier.padding(top = 10.dp)) {
                        // 选择节次段
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            STUDY_PERIOD_SLOTS.forEachIndexed { idx, slot ->
                                XPeachFilterPill(
                                    label = slot.label,
                                    selected = selectedSlotIndex == idx,
                                    onClick = { selectedSlotIndex = idx }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 选择教学楼
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (bName in buildingKeys) {
                                XPeachFilterPill(
                                    label = bName.removeSuffix("教学楼"),
                                    selected = selectedStudyBuilding == bName,
                                    onClick = { selectedStudyBuilding = bName }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        val activeSlot = STUDY_PERIOD_SLOTS[selectedSlotIndex]
                        val busyRoomsInSlot = remember(
                            rules,
                            currentTeachingWeek,
                            today,
                            activeSlot
                        ) {
                            rules.filter { rule ->
                                rule.weekday == today.dayOfWeek &&
                                    (rule.weeks.isEmpty() || currentTeachingWeek in rule.weeks) &&
                                    rule.startPeriod <= activeSlot.endPeriod &&
                                    rule.endPeriod >= activeSlot.startPeriod
                            }.map { it.rawRoom }
                        }

                        val candidateRooms = LIANHUAJIE_STUDY_ROOMS_BY_BUILDING[selectedStudyBuilding].orEmpty()
                        val freeRooms = remember(candidateRooms, busyRoomsInSlot) {
                            candidateRooms.filter { code ->
                                busyRoomsInSlot.none { raw -> raw.contains(code) }
                            }
                        }

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
                                            onNavigateToMapWithRoom("${selectedStudyBuilding.removeSuffix("教学楼")}-$roomCode")
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = roomCode,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Navigation,
                                        contentDescription = "导航去自习",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. 学分结构分类统计（当存在已导入成绩时展示）
            if (attempts.isNotEmpty()) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Text("修读学分构成", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    val categoryCredits = remember(attempts) {
                        val passed = attempts.filter { it.passed }.distinctBy { it.courseKey }
                        CourseCategory.entries.associateWith { cat ->
                            passed.filter { it.category == cat }
                                .fold(BigDecimal.ZERO) { acc, item -> acc.add(item.credits) }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (cat in CourseCategory.entries) {
                            val cred = categoryCredits[cat] ?: BigDecimal.ZERO
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = if (prefs.gpaPrivacyShieldEnabled) "**" else cred.stripTrailingZeros().toPlainString(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Text(
                                    text = cat.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            // 6. 学期切换与桌面小组件中心
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("学期与桌面小组件", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (term in terms) {
                        XPeachFilterPill(
                            label = term.id,
                            selected = term.id == currentTerm?.id,
                            onClick = {
                                scope.launch {
                                    repository.selectCurrentTerm(term.id)
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    feedbackText = "已切换至 ${term.displayName}"
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(
                    thickness = 0.6.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("小组件紧凑布局", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = widgetCompactDefault,
                        onCheckedChange = { checked ->
                            widgetCompactDefault = checked
                            scope.launch {
                                for (id in 0..20) {
                                    repository.saveWidgetConfig(
                                        WidgetInstanceConfig(
                                            appWidgetId = id,
                                            widgetType = WidgetType.NEXT_CLASS_2X2,
                                            themeStyle = WidgetThemeStyle.DYNAMIC_SYSTEM,
                                            compact = checked,
                                            privacyMode = widgetPrivacyDefault
                                        )
                                    )
                                }
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                feedbackText = "已更新小组件紧凑布局。"
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("小组件隐藏课程名与教室", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = widgetPrivacyDefault,
                        onCheckedChange = { checked ->
                            widgetPrivacyDefault = checked
                            scope.launch {
                                for (id in 0..20) {
                                    repository.saveWidgetConfig(
                                        WidgetInstanceConfig(
                                            appWidgetId = id,
                                            widgetType = WidgetType.NEXT_CLASS_2X2,
                                            themeStyle = WidgetThemeStyle.DYNAMIC_SYSTEM,
                                            compact = widgetCompactDefault,
                                            privacyMode = checked
                                        )
                                    )
                                }
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                feedbackText = "已更新小组件隐私保护。"
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                fun requestPin(label: String, providerClass: String) {
                    val mgr = AppWidgetManager.getInstance(context)
                    val provider = ComponentName(context.packageName, providerClass)
                    if (mgr.isRequestPinAppWidgetSupported) {
                        val ok = mgr.requestPinAppWidget(provider, null, null)
                        feedbackText = if (ok) {
                            "请在系统弹窗中确认添加「$label」"
                        } else {
                            "请长按手机桌面空白处添加小组件"
                        }
                    } else {
                        feedbackText = "请长按手机桌面空白处添加小组件"
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            requestPin("2×2 下一节", "cn.edu.haut.campus.widget.nextclass.NextClassAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 2×2 下一节", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            requestPin("4×2 今日课程", "cn.edu.haut.campus.widget.today.TodayAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×2 今日", maxLines = 1, softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            requestPin("4×4 时间轴", "cn.edu.haut.campus.widget.timeline.TimelineAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×4 时间轴", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            requestPin("4×4 周课表", "cn.edu.haut.campus.widget.week.WeekAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×4 周课表", maxLines = 1, softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                FilledTonalButton(
                    onClick = {
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        feedbackText = "已刷新全部桌面小组件。"
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("立即刷新全部小组件", maxLines = 1, softWrap = false)
                }
            }

            // 7. 作息节次表（默认折叠）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("莲花街作息时间表", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "08:00–21:25 · 共 ${periodTimes.size} 节 · 支持自定义微调",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachFilterPill(
                        label = if (expandPeriodTable) "收起" else "展开编辑",
                        selected = expandPeriodTable,
                        onClick = { expandPeriodTable = !expandPeriodTable }
                    )
                }

                AnimatedVisibility(visible = expandPeriodTable) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        for (pt in periodTimes) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "第 ${pt.periodNo} 节 (${pt.sectionLabel})",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(onClick = { editingPeriod = pt }) {
                                    Text(
                                        text = "${pt.startLocalTime}–${pt.endLocalTime}",
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 8. 外观、隐私安全与数据管理
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("外观、隐私与数据", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("系统壁纸动态取色 (Material You)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "开启后跟随 Android 12+ 壁纸色彩，关闭则使用工大蓝主题",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.useDynamicColor,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setUseDynamicColor(enabled) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("成绩与学籍隐私护盾", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "隐藏绩点分值并开启系统级防截屏保护 (FLAG_SECURE)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.gpaPrivacyShieldEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { repository.setGpaPrivacyShield(enabled) }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                repository.resetOnboardingForGuide()
                                onBack?.invoke()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("打开初始化向导", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = { showWipeConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清除个人数据", maxLines = 1, softWrap = false)
                    }
                }
            }

            // 9. 关于与版本信息
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                val pkgInfo = remember(context) {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("日在校园 · 河南工业大学", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            text = "莲花街校区本地优先课表、学业与实景导航",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "v${pkgInfo?.versionName ?: "0.7.0"} · Android ${Build.VERSION.RELEASE}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            // 预留底部悬浮胶囊导航栏高度
            Spacer(modifier = Modifier.height(96.dp))
        }
    }

    // 弹窗 1：编辑个人学籍名片
    if (showEditProfileDialog) {
        var nameInput by remember(studentProfile) { mutableStateOf(studentProfile?.displayName ?: "") }
        var schoolIdInput by remember(studentProfile) { mutableStateOf(studentProfile?.schoolId ?: "") }
        var majorInput by remember(studentProfile) { mutableStateOf(studentProfile?.major ?: "") }
        var yearInput by remember(studentProfile) { mutableStateOf(studentProfile?.enrollmentYear?.toString() ?: "2023") }

        AlertDialog(
            onDismissRequest = { showEditProfileDialog = false },
            title = { Text("编辑个人学籍名片") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("姓名 / 昵称") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = schoolIdInput,
                        onValueChange = { schoolIdInput = it },
                        label = { Text("学号") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = majorInput,
                        onValueChange = { majorInput = it },
                        label = { Text("学院 / 专业") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = yearInput,
                        onValueChange = { yearInput = it },
                        label = { Text("入学年份 (如 2023)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.saveStudentProfile(
                                StudentProfile(
                                    id = studentProfile?.id ?: "stu_local",
                                    schoolId = schoolIdInput.trim().ifBlank { "未填写" },
                                    displayName = nameInput.trim().ifBlank { null },
                                    major = majorInput.trim().ifBlank { null },
                                    enrollmentYear = yearInput.trim().toIntOrNull()
                                )
                            )
                            showEditProfileDialog = false
                            feedbackText = "已保存个人学籍名片。"
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProfileDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 弹窗 2：新增校历调休 / 停课安排
    if (showAddCalendarOverrideDialog) {
        var dateIso by remember { mutableStateOf(today.toString()) }
        var titleInput by remember { mutableStateOf("") }
        var isMakeup by remember { mutableStateOf(false) }
        var followDateIso by remember { mutableStateOf(today.minusDays(2).toString()) }

        AlertDialog(
            onDismissRequest = { showAddCalendarOverrideDialog = false },
            title = { Text("新增调休或停课日期") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = dateIso,
                        onValueChange = { dateIso = it },
                        label = { Text("目标日期 (YYYY-MM-DD)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("事由名称 (如 校运会停课 / 国庆调休)") },
                        singleLine = true
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        XPeachFilterPill(
                            label = "全天停课",
                            selected = !isMakeup,
                            onClick = { isMakeup = false }
                        )
                        XPeachFilterPill(
                            label = "调休补课",
                            selected = isMakeup,
                            onClick = { isMakeup = true }
                        )
                    }
                    if (isMakeup) {
                        OutlinedTextField(
                            value = followDateIso,
                            onValueChange = { followDateIso = it },
                            label = { Text("按哪一天的课表上课 (YYYY-MM-DD)") },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedDate = runCatching { LocalDate.parse(dateIso.trim()) }.getOrNull()
                        val parsedFollow = runCatching { LocalDate.parse(followDateIso.trim()) }.getOrNull()
                        if (parsedDate != null && titleInput.isNotBlank()) {
                            scope.launch {
                                repository.saveCalendarOverride(
                                    CalendarOverride(
                                        id = "cal_${UUID.randomUUID().toString().take(8)}",
                                        termId = currentTerm?.id ?: "2025-2026-2",
                                        date = parsedDate,
                                        overrideType = if (isMakeup) {
                                            CalendarOverrideType.MAKEUP_FOLLOW_DATE
                                        } else {
                                            CalendarOverrideType.HOLIDAY_NO_CLASS
                                        },
                                        followDate = if (isMakeup) parsedFollow else null,
                                        title = titleInput.trim()
                                    )
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                showAddCalendarOverrideDialog = false
                                feedbackText = "已添加校历调休/停课项。"
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddCalendarOverrideDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    // 弹窗 3：历史同步审计记录
    if (showImportHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showImportHistoryDialog = false },
            title = { Text("教务与课表同步记录") },
            text = {
                if (importAudits.isEmpty()) {
                    Text(
                        text = "暂无同步记录。点击右上角同步图标可从正方教务系统导入课表与成绩。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (audit in importAudits.take(8)) {
                            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "${AUDIT_TIME_FMT.format(audit.importedAt)} · ${audit.termId}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = audit.outcome,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportHistoryDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    // 弹窗 4：修改单节作息时间
    val ptToEdit = editingPeriod
    if (ptToEdit != null) {
        var startIso by remember(ptToEdit) { mutableStateOf(ptToEdit.startLocalTime.toString()) }
        var endIso by remember(ptToEdit) { mutableStateOf(ptToEdit.endLocalTime.toString()) }

        AlertDialog(
            onDismissRequest = { editingPeriod = null },
            title = { Text("第 ${ptToEdit.periodNo} 节时间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startIso,
                        onValueChange = { startIso = it },
                        label = { Text("开始 (HH:mm)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endIso,
                        onValueChange = { endIso = it },
                        label = { Text("结束 (HH:mm)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedStart = runCatching { LocalTime.parse(startIso.trim()) }.getOrNull()
                        val parsedEnd = runCatching { LocalTime.parse(endIso.trim()) }.getOrNull()
                        if (parsedStart != null && parsedEnd != null && parsedStart.isBefore(parsedEnd)) {
                            scope.launch {
                                repository.updatePeriodTime(
                                    ptToEdit.copy(
                                        startLocalTime = parsedStart,
                                        endLocalTime = parsedEnd
                                    )
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                feedbackText = "已更新第 ${ptToEdit.periodNo} 节时间。"
                            }
                        }
                        editingPeriod = null
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPeriod = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 弹窗 5：清除个人数据确认
    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("清除个人数据") },
            text = {
                Text("将清空本地课表、成绩、学籍名片、网页登录状态与小组件配置。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            HautSessionManager.clearAuthorizedSessionCookies()
                            repository.wipeAllPersonalData()
                            repository.resetOnboardingForGuide()
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            showWipeConfirmDialog = false
                            onBack?.invoke()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("确认清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun StudentDigitalIdCard(
    profile: StudentProfile?,
    privacyMasked: Boolean,
    lastAudit: cn.edu.haut.campus.core.model.ImportAudit?,
    onEditProfile: () -> Unit,
    onSyncNow: () -> Unit
) {
    val displayName = profile?.displayName?.takeIf { it.isNotBlank() } ?: "工大学子"
    val rawSchoolId = profile?.schoolId?.takeIf { it.isNotBlank() } ?: "点击右侧同步教务绑定学号"
    val displaySchoolId = if (privacyMasked && rawSchoolId.length >= 8) {
        rawSchoolId.take(4) + "****" + rawSchoolId.takeLast(3)
    } else {
        rawSchoolId
    }
    val majorText = buildString {
        append(profile?.major?.takeIf { it.isNotBlank() } ?: "河南工业大学 · 莲花街校区")
        profile?.enrollmentYear?.let { append(" · ${it}级") }
    }

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                // 简约圆角头像徽章
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = displayName.take(1),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        XPeachStatusBadge(
                            text = if (profile != null) "已认证" else "未同步",
                            containerColor = if (profile != null) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (profile != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Text(
                        text = displaySchoolId,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = majorText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            XPeachIconButton(
                icon = Icons.Default.Edit,
                contentDescription = "编辑资料",
                onClick = onEditProfile
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(
            thickness = 0.6.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (lastAudit != null) {
                    "上次同步：${AUDIT_TIME_FMT.format(lastAudit.importedAt)}"
                } else {
                    "支持正方教务直连同步课表与成绩"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            XPeachFilterPill(
                label = "立即同步",
                selected = true,
                onClick = onSyncNow
            )
        }
    }
}

@Composable
private fun ProfileMetricItem(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false
        )
    }
}

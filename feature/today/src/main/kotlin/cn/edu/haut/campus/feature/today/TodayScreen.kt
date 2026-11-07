package cn.edu.haut.campus.feature.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.database.isPendingPickup
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.ClassEndMealRecommendation
import cn.edu.haut.campus.core.domain.ReminderPlanner
import cn.edu.haut.campus.core.domain.ReminderPolicyEngine
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.ScheduleBreakInterval
import cn.edu.haut.campus.core.model.SnoozeOptionEvaluation
import cn.edu.haut.campus.core.model.SnoozeOutcome
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TermDateStatus
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TITLE_FMT = DateTimeFormatter.ofPattern("MM月dd日")

@Composable
fun TodayScreen(
    repository: CampusRepository,
    onNavigateToMapWithRoom: (String) -> Unit,
    onNavigateToImporter: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToFocus: () -> Unit = {},
    onNavigateToElectricity: () -> Unit = {},
    onNavigateToLibraryCatalog: () -> Unit = {},
    onNavigateToLibrarySeats: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToCompetitions: () -> Unit = onNavigateToCommunity,
    onNavigateToParcelHub: () -> Unit = {},
    onNavigateToGrades: () -> Unit = {},
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val terms by repository.termsFlow.collectAsState(initial = emptyList())
    val term by repository.currentTermFlow.collectAsState(initial = null)
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())
    val occurrenceOverrides by repository.occurrenceOverridesFlow.collectAsState(initial = emptyList())
    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())
    val examSchedules by repository.examSchedulesFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val activeFocusSession by repository.activeFocusSessionFlow.collectAsState(initial = null)
    val completedFocusSessions by repository.completedFocusSessionsFlow.collectAsState(initial = emptyList())
    val onlineRooms by repository.utilityOnlineRoomsFlow.collectAsState(initial = emptyList())
    val utilityMeters by repository.utilityMetersFlow.collectAsState(initial = emptyList())
    val utilityReadings by repository.utilityReadingsFlow.collectAsState(initial = emptyList())
    val parcelItems by repository.parcelItemsFlow.collectAsState(initial = emptyList())
    val bookingPlans by repository.libraryBookingPlansFlow.collectAsState(initial = emptyList())
    val activeReminderTasks by repository.activeReminderTasksFlow.collectAsState(initial = emptyList())
    val reminderOverrides by repository.reminderEventOverridesFlow.collectAsState(initial = emptyList())
    val reminderCoordinator = remember(context) { ReminderCoordinator.getInstance(context) }

    val tasksByEventKey = remember(activeReminderTasks) {
        activeReminderTasks.associateBy { it.eventKey }
    }
    val overridesByScope = remember(reminderOverrides) {
        reminderOverrides.associateBy { it.eventScopeKey }
    }

    var currentNow by remember { mutableStateOf(ZonedDateTime.now(HAUT_ZONE_ID)) }
    var selectedDate by remember { mutableStateOf(currentNow.toLocalDate()) }
    var selectedOccurrenceForDetail by remember { mutableStateOf<TimelineOccurrenceState?>(null) }
    var isDynamicSyncing by remember { mutableStateOf(false) }
    var dynamicSyncBanner by remember { mutableStateOf<String?>(null) }
    var showTermSwitcherDialog by remember { mutableStateOf(false) }
    var showCardCustomizerDialog by remember { mutableStateOf(false) }
    var showDiningDialog by remember { mutableStateOf(false) }
    var diningShuffleOffset by remember { mutableIntStateOf(0) }
    var dismissedMealBlockKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val updatedNow = ZonedDateTime.now(HAUT_ZONE_ID)
                val previousDate = currentNow.toLocalDate()
                currentNow = updatedNow
                if (selectedDate == previousDate && updatedNow.toLocalDate() != previousDate) {
                    selectedDate = updatedNow.toLocalDate()
                }
                val secondsToNextMinute = (60 - updatedNow.second).coerceIn(5, 60)
                delay(secondsToNextMinute * 1000L)
            }
        }
    }

    val effectiveScale = prefs.timelineZoomScale.coerceIn(1.1f, 2.2f)
    val todayDate = currentNow.toLocalDate()

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0) {
            selectedDate = todayDate
            scrollState.animateScrollTo(0)
        }
    }

    val snapshot = remember(
        selectedDate,
        currentNow,
        term,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings,
        effectiveScale
    ) {
        ScheduleEngine.buildDayTimelineSnapshot(
            date = selectedDate,
            now = currentNow,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings,
            pxPerMinute = effectiveScale
        )
    }

    val termRules = remember(rules, term?.id) {
        val activeTermId = term?.id
        if (activeTermId != null) rules.filter { it.termId == activeTermId } else rules
    }

    val mealRecommendation = remember(
        selectedDate,
        currentNow,
        snapshot.items,
        buildings,
        diningShuffleOffset
    ) {
        CampusDiningRecommender.buildRecommendation(
            date = selectedDate,
            now = currentNow.toInstant(),
            occurrences = snapshot.items.map { it.occurrence },
            buildings = buildings,
            shuffleOffset = diningShuffleOffset
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = if (selectedDate == todayDate) "今天" else selectedDate.format(DATE_TITLE_FMT),
            subtitle = "${selectedDate.format(DATE_TITLE_FMT)} ${selectedDate.dayOfWeek.ChineseLabel} · ${term?.displayName ?: "选择学期"}",
            badgeText = snapshot.teachingWeek?.let { "第 ${it} 周" } ?: "非教学周",
            onSubtitleClick = { showTermSwitcherDialog = true },
            actions = {
                AnimatedVisibility(
                    visible = selectedDate != todayDate,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(140))
                ) {
                    XPeachFilterPill(
                        label = "回今天",
                        selected = true,
                        compact = true,
                        onClick = { selectedDate = todayDate }
                    )
                }
                XPeachIconButton(
                    icon = Icons.Default.CloudDownload,
                    contentDescription = "同步教务",
                    onClick = {
                        if (prefs.hasSavedCredentials && !isDynamicSyncing) {
                            scope.launch {
                                isDynamicSyncing = true
                                dynamicSyncBanner = "正在同步…"
                                val res = repository.performDynamicAcademicSync(force = true)
                                isDynamicSyncing = false
                                if (res == null) {
                                    onNavigateToImporter()
                                } else {
                                    res.onSuccess { syncData ->
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                        dynamicSyncBanner = "已同步 ${syncData.timetableCandidates.size} 门课程、${syncData.examItems.size} 场考试"
                                    }.onFailure { err ->
                                        dynamicSyncBanner = "同步失败：${err.message ?: "请检查网络"}"
                                    }
                                }
                            }
                        } else {
                            onNavigateToImporter()
                        }
                    }
                )
                XPeachIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "设置",
                    onClick = onNavigateToSettings
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AnimatedVisibility(
                visible = dynamicSyncBanner != null,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
            ) {
                dynamicSyncBanner?.let { bannerText ->
                    XPeachCard(
                        containerColor = XPeachTheme.primaryAccentCardColor,
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = bannerText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { dynamicSyncBanner = null }) {
                                Text("收起", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            // 1. 支持左右无极连续滑动的日期选择条（同时支持左右箭头快速翻周）
            WeekDaySelectorStrip(
                selectedDate = selectedDate,
                todayDate = todayDate,
                term = term,
                termRules = termRules,
                calendarOverrides = calendarOverrides,
                occurrenceOverrides = occurrenceOverrides,
                onSelectDate = { selectedDate = it },
                onPreviousWeek = { selectedDate = selectedDate.minusWeeks(1) },
                onNextWeek = { selectedDate = selectedDate.plusWeeks(1) }
            )

            AnimatedVisibility(
                visible = !snapshot.calendarNotice.isNullOrBlank(),
                enter = expandVertically(tween(200)) + fadeIn(tween(160)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
            ) {
                snapshot.calendarNotice?.takeIf { it.isNotBlank() }?.let { notice ->
                    XPeachCard(
                        containerColor = XPeachTheme.primaryAccentCardColor,
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
                    ) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            // 1.5 动态待取快递弹出提醒卡（有待取快递时自动在主页显著弹出，按驿站聚合超大取件码，全部取完自动收起）
            val pendingPickups = remember(parcelItems) {
                parcelItems.filter { it.isPendingPickup() }
            }
            AnimatedVisibility(
                visible = pendingPickups.isNotEmpty(),
                enter = expandVertically(tween(240, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                TodayExpressPickupHeroCard(
                    pendingPickups = pendingPickups,
                    onMarkPickedUp = { parcelId ->
                        scope.launch {
                            repository.markParcelPickedUp(parcelId)
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        }
                    },
                    onMarkStationAllPickedUp = { stationName ->
                        scope.launch {
                            repository.markAllStationParcelsPickedUp(stationName)
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        }
                    },
                    onSyncParcels = {
                        scope.launch {
                            dynamicSyncBanner = "正在同步快递…"
                            runCatching { repository.syncXiaomiExpressPackages() }
                            runCatching { repository.syncWithCampusCloud() }
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            dynamicSyncBanner = "快递已更新"
                        }
                    },
                    onOpenParcelHub = onNavigateToParcelHub
                )
            }

            // 1.6 动态今日图书馆座位 / 自动抢座状态卡（有已约座位或待执行抢座计划时自动展示）
            val activeSeatPlans = remember(bookingPlans) {
                bookingPlans.filter {
                    it.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK", "CONFIRMED")
                }
            }
            AnimatedVisibility(
                visible = activeSeatPlans.isNotEmpty(),
                enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
            ) {
                TodayLibrarySeatDynamicCard(
                    activePlans = activeSeatPlans,
                    onOpenLibrarySeats = onNavigateToLibrarySeats,
                    onCancelPlan = { planId ->
                        scope.launch {
                            repository.cancelDualEngineBookingPlan(planId)
                        }
                    }
                )
            }

            // 1.7 离下课 1 小时自动食堂推荐卡片（进入下课前 60 分钟窗口自动弹出）
            val showDiningHero = selectedDate == todayDate &&
                prefs.mealRecommendEnabled &&
                mealRecommendation.isWithinOneHourBeforeEnd &&
                mealRecommendation.block?.blockKey != dismissedMealBlockKey
            AnimatedVisibility(
                visible = showDiningHero,
                enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
            ) {
                TodayDiningRecommendHeroCard(
                    recommendation = mealRecommendation,
                    onShuffle = { diningShuffleOffset += 1 },
                    onOpenAllCanteens = { showDiningDialog = true },
                    onNavigateToCanteen = { canteenName ->
                        onNavigateToMapWithRoom(canteenName)
                    },
                    onDismiss = {
                        dismissedMealBlockKey = mealRecommendation.block?.blockKey
                    }
                )
            }

            // 2. 上半部分：唯一「当天课表卡片」（合并当前/下一节课状态与行内一键去教室，移除顶部重复大卡）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                val heroItem = snapshot.activeOrNextClass
                val activeBreak = snapshot.activeBreakInterval
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (selectedDate == todayDate) "今日课程" else "当日课程",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (heroItem != null) {
                        val isOngoing = heroItem.status == ClassStatus.IN_PROGRESS
                        val badgeLabel = if (isOngoing) {
                            "上课中 · ${heroItem.statusText}"
                        } else {
                            "下一节 ${heroItem.occurrence.startTime.format(TIME_FMT)}"
                        }
                        XPeachStatusDotText(
                            text = badgeLabel,
                            dotColor = if (isOngoing) MaterialTheme.colorScheme.tertiary else XPeachTheme.readablePrimary,
                            textColor = XPeachTheme.readablePrimary
                        )
                    } else {
                        Text(
                            text = when {
                                courses.isEmpty() -> "未导入"
                                snapshot.items.isEmpty() -> "今日无课"
                                else -> "共 ${snapshot.items.size} 节已结束"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                AnimatedVisibility(
                    visible = activeBreak != null,
                    enter = expandVertically(tween(200)) + fadeIn(tween(160)),
                    exit = shrinkVertically(tween(160)) + fadeOut(tween(120))
                ) {
                    activeBreak?.let { brk ->
                        Column {
                            XPeachInsetDivider(verticalPadding = 8.dp)
                            XPeachStatusDotText(
                                text = "${brk.label} · ${brk.startTime.format(TIME_FMT)}–${brk.endTime.format(TIME_FMT)}" +
                                    (brk.minutesRemainingInBreak?.let { "（剩 ${it} 分钟）" } ?: ""),
                                dotColor = XPeachTheme.readablePrimary,
                                textColor = XPeachTheme.readablePrimary
                            )
                            brk.roomTransferText?.let { transfer ->
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = transfer,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                AnimatedContent(
                    targetState = selectedDate,
                    transitionSpec = {
                        val direction = if (!targetState.isBefore(initialState)) {
                            AnimatedContentTransitionScope.SlideDirection.Start
                        } else {
                            AnimatedContentTransitionScope.SlideDirection.End
                        }
                        (slideIntoContainer(
                            towards = direction,
                            animationSpec = tween(220, easing = FastOutSlowInEasing)
                        ) { it / 10 } + fadeIn(tween(180))) togetherWith
                            (slideOutOfContainer(
                                towards = direction,
                                animationSpec = tween(180, easing = FastOutSlowInEasing)
                            ) { it / 12 } + fadeOut(tween(130)))
                    },
                    label = "dayScheduleTransition",
                    modifier = Modifier.fillMaxWidth()
                ) { dateForView ->
                    val daySnapshot = remember(
                        dateForView,
                        currentNow,
                        term,
                        courses,
                        rules,
                        periodTimes,
                        calendarOverrides,
                        occurrenceOverrides,
                        buildings,
                        effectiveScale
                    ) {
                        ScheduleEngine.buildDayTimelineSnapshot(
                            date = dateForView,
                            now = currentNow,
                            term = term,
                            courses = courses,
                            rules = rules,
                            periodTimes = periodTimes,
                            calendarOverrides = calendarOverrides,
                            occurrenceOverrides = occurrenceOverrides,
                            buildings = buildings,
                            pxPerMinute = effectiveScale
                        )
                    }
                    if (daySnapshot.items.isEmpty()) {
                        EmptyDayTimelineBox(
                            hasAnyCourses = courses.isNotEmpty(),
                            nextClassHint = daySnapshot.nextClassDayHint,
                            onJumpToDate = { selectedDate = it },
                            onOpenImporter = onNavigateToImporter
                        )
                    } else {
                        MinimalistDayScheduleFlow(
                            items = daySnapshot.items,
                            breakIntervals = daySnapshot.breakIntervals,
                            highlightedOccurrenceId = daySnapshot.activeOrNextClass?.occurrence?.occurrenceId,
                            termId = term?.id ?: "default",
                            globalReminderEnabled = prefs.reminderConfig.enabled,
                            defaultLeadMinutes = prefs.reminderConfig.classLeadMinutes,
                            tasksByEventKey = tasksByEventKey,
                            overridesByScope = overridesByScope,
                            onItemClick = { selectedOccurrenceForDetail = it },
                            onNavigateRoom = onNavigateToMapWithRoom
                        )
                    }
                }
            }

            // 3. 下半部分：支持用户自定义排序、显隐与尺寸（通栏/双列）的模块化 Bento 卡片矩阵
            TodayCampusBentoSection(
                examSchedules = examSchedules,
                activeTermId = term?.id,
                selectedDate = selectedDate,
                todayDate = todayDate,
                currentNow = currentNow,
                teachingWeek = snapshot.teachingWeek ?: 1,
                rules = rules,
                activeFocusSession = activeFocusSession,
                completedFocusSessions = completedFocusSessions,
                onlineRooms = onlineRooms,
                utilityMeters = utilityMeters,
                utilityReadings = utilityReadings,
                parcelItems = parcelItems,
                bookingPlans = bookingPlans,
                cardOrder = prefs.homeCardOrder,
                hiddenCards = prefs.hiddenHomeCards,
                fullWidthCards = prefs.fullWidthHomeCards,
                campusClubsCount = prefs.campusCommunityCatalog.clubs.size,
                competitionsCount = prefs.campusCommunityCatalog.competitions.size,
                mealRecommendation = mealRecommendation,
                onOpenDiningDialog = { showDiningDialog = true },
                onOpenCustomizer = { showCardCustomizerDialog = true },
                onNavigateToGrades = onNavigateToGrades,
                onNavigateToMapWithRoom = onNavigateToMapWithRoom,
                onNavigateToFocus = onNavigateToFocus,
                onNavigateToElectricity = onNavigateToElectricity,
                onNavigateToLibrarySeats = onNavigateToLibrarySeats,
                onNavigateToLibraryCatalog = onNavigateToLibraryCatalog,
                onNavigateToEmptyClassrooms = onNavigateToEmptyClassrooms,
                onNavigateToCommunity = onNavigateToCommunity,
                onNavigateToCompetitions = onNavigateToCompetitions,
                onNavigateToParcelHub = onNavigateToParcelHub
            )

            Spacer(modifier = Modifier.height(92.dp))
        }
    }

    if (showCardCustomizerDialog) {
        HomeCardLayoutCustomizerDialog(
            currentOrder = prefs.homeCardOrder,
            currentHidden = prefs.hiddenHomeCards,
            currentFullWidth = prefs.fullWidthHomeCards,
            onSave = { newOrder, newHidden, newFullWidth ->
                scope.launch {
                    repository.updateHomeCardLayout(newOrder, newHidden, newFullWidth)
                    showCardCustomizerDialog = false
                }
            },
            onResetDefault = {
                scope.launch {
                    repository.resetHomeCardLayout()
                    showCardCustomizerDialog = false
                }
            },
            onDismiss = { showCardCustomizerDialog = false }
        )
    }

    if (showDiningDialog) {
        CampusDiningRecommendationDialog(
            recommendation = mealRecommendation,
            mealRecommendEnabled = prefs.mealRecommendEnabled,
            onToggleEnabled = { enabled ->
                scope.launch {
                    repository.setMealRecommendEnabled(enabled)
                }
            },
            onShuffle = { diningShuffleOffset += 1 },
            onTestNotification = {
                scope.launch {
                    val ok = runCatching {
                        reminderCoordinator.triggerTestMealRecommendation(diningShuffleOffset)
                    }.getOrDefault(false)
                    dynamicSyncBanner = if (ok) {
                        "已发送下课食堂推荐通知"
                    } else {
                        "请先在系统设置中开启通知权限"
                    }
                }
            },
            onNavigateToCanteen = { canteenName ->
                showDiningDialog = false
                onNavigateToMapWithRoom(canteenName)
            },
            onDismiss = { showDiningDialog = false }
        )
    }

    val detailState = selectedOccurrenceForDetail
    if (detailState != null) {
        CourseDetailAndSnoozeDialog(
            detailState = detailState,
            termId = term?.id ?: "default",
            repository = repository,
            reminderCoordinator = reminderCoordinator,
            globalConfig = prefs.reminderConfig,
            activeTask = tasksByEventKey["CLASS:${term?.id ?: "default"}:${detailState.occurrence.occurrenceId}"],
            overridesByScope = overridesByScope,
            onNavigateToMapWithRoom = { room ->
                selectedOccurrenceForDetail = null
                onNavigateToMapWithRoom(room)
            },
            onDismiss = { selectedOccurrenceForDetail = null }
        )
    }

    if (showTermSwitcherDialog) {
        XPeachAlertDialog(
            onDismissRequest = { showTermSwitcherDialog = false },
            title = { Text("切换学期") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (t in terms) {
                        val isCurrent = t.id == term?.id
                        val count = rules.count { it.termId == t.id }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isCurrent) XPeachTheme.primaryAccentCardColor
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                                .clickable {
                                    scope.launch {
                                        repository.selectCurrentTerm(t.id)
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                        showTermSwitcherDialog = false
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = t.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${t.startDate} 开学 · 共 ${t.weekCount} 周",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = if (isCurrent) "当前 ($count 门)" else "$count 门课",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    FilledTonalButton(
                        onClick = {
                            showTermSwitcherDialog = false
                            onNavigateToImporter()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("同步其他学期", maxLines = 1, softWrap = false)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTermSwitcherDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

private fun formatOccurrenceReminderBadge(
    termId: String,
    item: TimelineOccurrenceState,
    globalReminderEnabled: Boolean,
    defaultLeadMinutes: Int,
    tasksByEventKey: Map<String, ReminderTaskDomain>,
    overridesByScope: Map<String, ReminderEventOverride>
): String? {
    if (item.status == ClassStatus.FINISHED) return null
    val occ = item.occurrence
    val eventKey = "CLASS:$termId:${occ.occurrenceId}"
    val occOverride = overridesByScope["OCCURRENCE:$eventKey"]
    val courseOverride = overridesByScope["COURSE:${occ.courseId}"]
    val enabled = occOverride?.enabledOverride
        ?: courseOverride?.enabledOverride
        ?: globalReminderEnabled
    if (!enabled) return "已静音"

    val activeTask = tasksByEventKey[eventKey]
    if (activeTask != null) {
        val triggerTime = Instant.ofEpochMilli(activeTask.scheduledAtEpochMs)
            .atZone(HAUT_ZONE_ID)
            .toLocalTime()
            .format(TIME_FMT)
        return when (activeTask.status) {
            ReminderTaskStatus.SNOOZED -> "$triggerTime 再提醒"
            ReminderTaskStatus.FIRED -> "已提醒"
            ReminderTaskStatus.PENDING -> "$triggerTime 提醒"
            else -> null
        }
    }

    if (item.status == ClassStatus.UPCOMING) {
        val leadMinutes = occOverride?.leadMinutesOverride
            ?: courseOverride?.leadMinutesOverride
            ?: defaultLeadMinutes
        return "课前${leadMinutes}分钟提醒"
    }
    return null
}

@Composable
private fun CourseDetailAndSnoozeDialog(
    detailState: TimelineOccurrenceState,
    termId: String,
    repository: CampusRepository,
    reminderCoordinator: ReminderCoordinator,
    globalConfig: cn.edu.haut.campus.core.model.ReminderGlobalConfig,
    activeTask: ReminderTaskDomain?,
    overridesByScope: Map<String, ReminderEventOverride>,
    onNavigateToMapWithRoom: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val occ = detailState.occurrence
    val event = remember(termId, occ) {
        ReminderPlanner.projectClassOccurrenceToEvent(termId, occ)
    }
    val courseScopeKey = "COURSE:${occ.courseId}"
    val courseOverride = overridesByScope[courseScopeKey]
    val effectivePolicy = remember(event, globalConfig, overridesByScope) {
        ReminderPolicyEngine.resolveEffectivePolicy(event, globalConfig, overridesByScope)
    }

    var snoozeOptions by remember { mutableStateOf<List<SnoozeOptionEvaluation>>(emptyList()) }
    var actionFeedback by remember { mutableStateOf<String?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(event, activeTask, effectivePolicy, refreshTrigger) {
        snoozeOptions = reminderCoordinator.evaluateSnoozeOptionsForEvent(
            event = event,
            kind = ReminderKind.CLASS_BEFORE,
            now = Instant.now()
        )
    }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(occ.courseName, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${occ.startTime.format(TIME_FMT)}–${occ.endTime.format(TIME_FMT)} · ${occ.periodRangeText}",
                    style = MaterialTheme.typography.labelMedium,
                    color = XPeachTheme.readablePrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "状态：${detailState.statusText}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "教室：${occ.rawRoom}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!occ.teacher.isNullOrBlank()) {
                        Text(
                            text = "教师：${occ.teacher}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    occ.internalBreaksSummary?.let { brkSummary ->
                        Text(
                            text = "课间：$brkSummary",
                            color = XPeachTheme.readablePrimary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                XPeachInsetDivider(verticalPadding = 2.dp)

                // 智能提醒与「稍后提醒」控制区
                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (effectivePolicy.enabled) {
                                        Icons.Default.NotificationsActive
                                    } else {
                                        Icons.Default.NotificationsOff
                                    },
                                    contentDescription = null,
                                    tint = if (effectivePolicy.enabled) {
                                        XPeachTheme.readablePrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "上课提醒",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            XPeachFilterPill(
                                label = if (effectivePolicy.enabled) "已开启" else "已关闭",
                                selected = effectivePolicy.enabled,
                                compact = true,
                                onClick = {
                                    scope.launch {
                                        val nextEnabled = !effectivePolicy.enabled
                                        if (nextEnabled && !globalConfig.enabled) {
                                            repository.updateReminderGlobalConfig { it.copy(enabled = true) }
                                        }
                                        repository.upsertReminderEventOverride(
                                            ReminderEventOverride(
                                                eventScopeKey = courseScopeKey,
                                                enabledOverride = nextEnabled,
                                                leadMinutesOverride = courseOverride?.leadMinutesOverride,
                                                maxSnoozesOverride = courseOverride?.maxSnoozesOverride,
                                                updatedAtEpochMs = System.currentTimeMillis()
                                            )
                                        )
                                        actionFeedback = if (nextEnabled) "已开启提醒" else "已关闭提醒"
                                        refreshTrigger++
                                    }
                                }
                            )
                        }

                        val statusSummary = when {
                            !effectivePolicy.enabled -> "已关闭提醒"
                            activeTask?.status == ReminderTaskStatus.SNOOZED -> {
                                val t = Instant.ofEpochMilli(activeTask.scheduledAtEpochMs)
                                    .atZone(HAUT_ZONE_ID).toLocalTime().format(TIME_FMT)
                                "将在 $t 再次提醒"
                            }
                            activeTask?.status == ReminderTaskStatus.FIRED -> {
                                "已提醒"
                            }
                            activeTask?.status == ReminderTaskStatus.PENDING -> {
                                val t = Instant.ofEpochMilli(activeTask.scheduledAtEpochMs)
                                    .atZone(HAUT_ZONE_ID).toLocalTime().format(TIME_FMT)
                                "将在 $t 提醒"
                            }
                            else -> "课前 ${effectivePolicy.classLeadMinutes} 分钟提醒"
                        }
                        Text(
                            text = statusSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 本门课提前分钟数快捷切换
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "提前时间：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                            for (lead in listOf(10, 15, 20, 30)) {
                                XPeachFilterPill(
                                    label = "${lead}分钟",
                                    selected = effectivePolicy.classLeadMinutes == lead,
                                    compact = true,
                                    onClick = {
                                        scope.launch {
                                            repository.upsertReminderEventOverride(
                                                ReminderEventOverride(
                                                    eventScopeKey = courseScopeKey,
                                                    enabledOverride = true,
                                                    leadMinutesOverride = lead,
                                                    maxSnoozesOverride = courseOverride?.maxSnoozesOverride,
                                                    updatedAtEpochMs = System.currentTimeMillis()
                                                )
                                            )
                                            actionFeedback = "已设为课前 $lead 分钟提醒"
                                            refreshTrigger++
                                        }
                                    }
                                )
                            }
                        }

                        if (detailState.status != ClassStatus.FINISHED && snoozeOptions.isNotEmpty()) {
                            Text(
                                text = "稍后提醒：",
                                style = MaterialTheme.typography.labelSmall,
                                color = XPeachTheme.readablePrimary,
                                fontWeight = FontWeight.Medium
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (opt in snoozeOptions) {
                                    val targetText = opt.targetInstant.atZone(HAUT_ZONE_ID)
                                        .toLocalTime()
                                        .format(TIME_FMT)
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable(enabled = opt.available) {
                                                scope.launch {
                                                    val outcome = reminderCoordinator.snoozeEventFromUi(
                                                        event = event,
                                                        kind = ReminderKind.CLASS_BEFORE,
                                                        choice = opt.choice,
                                                        now = Instant.now()
                                                    )
                                                    actionFeedback = when (outcome) {
                                                        is SnoozeOutcome.Accepted -> {
                                                            val t = outcome.triggerAt.atZone(HAUT_ZONE_ID)
                                                                .toLocalTime().format(TIME_FMT)
                                                            "将在 $t 再次提醒"
                                                        }
                                                        is SnoozeOutcome.AcceptedPendingRepair -> {
                                                            "已设置稍后提醒"
                                                        }
                                                        is SnoozeOutcome.Rejected -> outcome.reason.userMessage
                                                        SnoozeOutcome.EventChanged -> "课程时间已变更，请刷新后重试"
                                                        SnoozeOutcome.ConcurrentlyHandled -> "提醒已更新"
                                                        SnoozeOutcome.NotFound -> "未找到提醒"
                                                        SnoozeOutcome.InternalError -> "设置失败"
                                                    }
                                                    refreshTrigger++
                                                }
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (opt.available) {
                                            XPeachTheme.primaryAccentCardColor
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Snooze,
                                                    contentDescription = null,
                                                    tint = if (opt.available) {
                                                        XPeachTheme.readablePrimary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    },
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = "${opt.label} ($targetText)",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (opt.available) {
                                                        MaterialTheme.colorScheme.onSurface
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                                    }
                                                )
                                            }
                                            Text(
                                                text = if (opt.available) "选择" else (opt.disabledReason?.userMessage ?: "不可选"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (opt.available) {
                                                    XPeachTheme.readablePrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        actionFeedback?.let { feedback ->
                            Text(
                                text = feedback,
                                style = MaterialTheme.typography.labelMedium,
                                color = XPeachTheme.readablePrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onNavigateToMapWithRoom(occ.rawRoom) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("路线导航", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                repository.saveOccurrenceOverride(
                                    OccurrenceOverride(
                                        id = "ov_cancel_${occ.date}_${occ.ruleId ?: UUID.randomUUID()}",
                                        termId = termId,
                                        courseRuleId = occ.ruleId,
                                        courseId = occ.courseId,
                                        date = occ.date,
                                        canceled = true,
                                        reason = "单次停课"
                                    )
                                )
                                WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                onDismiss()
                            }
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("今日停课", maxLines = 1, softWrap = false)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private const val STRIP_TOTAL_DAYS = 2001
private const val STRIP_CENTER_INDEX = 1000

private fun doesDateHaveClasses(
    date: LocalDate,
    term: Term?,
    termRules: List<CourseScheduleRule>,
    calendarOverrides: List<CalendarOverride>,
    occurrenceOverrides: List<OccurrenceOverride>
): Boolean {
    if (term == null || termRules.isEmpty()) return false
    val resolved = TeachingWeekCalculator.resolveDate(date, term, calendarOverrides)
    if (resolved.status == TermDateStatus.HOLIDAY_NO_CLASS) return false
    val weekNo = resolved.teachingWeek ?: return false
    val canceledRulesOnDate = occurrenceOverrides
        .filter { it.date == date && it.canceled }
        .mapNotNull { it.courseRuleId }
        .toSet()
    return termRules.any { rule ->
        rule.id !in canceledRulesOnDate &&
            rule.weekday == resolved.weekdayUsed &&
            (rule.weeks.isEmpty() || weekNo in rule.weeks)
    }
}

@Composable
private fun WeekDaySelectorStrip(
    selectedDate: LocalDate,
    todayDate: LocalDate,
    term: Term?,
    termRules: List<CourseScheduleRule>,
    calendarOverrides: List<CalendarOverride>,
    occurrenceOverrides: List<OccurrenceOverride>,
    onSelectDate: (LocalDate) -> Unit,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit
) {
    val initialMonday = remember {
        selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
    val initialFirstIndex = remember(todayDate) {
        (STRIP_CENTER_INDEX + ChronoUnit.DAYS.between(todayDate, initialMonday).toInt())
            .coerceIn(0, STRIP_TOTAL_DAYS - 7)
    }
    val lazyListState = rememberLazyListState(initialFirstVisibleItemIndex = initialFirstIndex)

    LaunchedEffect(selectedDate, todayDate) {
        val selectedIndex = (STRIP_CENTER_INDEX + ChronoUnit.DAYS.between(todayDate, selectedDate).toInt())
            .coerceIn(0, STRIP_TOTAL_DAYS - 1)
        val visibleIndices = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }
        if (visibleIndices.isNotEmpty() && selectedIndex !in visibleIndices) {
            val targetMonday = selectedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val targetStart = (STRIP_CENTER_INDEX + ChronoUnit.DAYS.between(todayDate, targetMonday).toInt())
                .coerceIn(0, STRIP_TOTAL_DAYS - 7)
            lazyListState.animateScrollToItem(targetStart)
        }
    }

    XPeachCard(
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onPreviousWeek),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "上一周",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            BoxWithConstraints(
                modifier = Modifier.weight(1f)
            ) {
                val itemWidth = maxWidth / 7
                LazyRow(
                    state = lazyListState,
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(
                        count = STRIP_TOTAL_DAYS,
                        key = { index -> index }
                    ) { index ->
                        val date = remember(todayDate, index) {
                            todayDate.plusDays((index - STRIP_CENTER_INDEX).toLong())
                        }
                        val isSelected = date == selectedDate
                        val isToday = date == todayDate
                        val hasClasses = remember(date, term, termRules, calendarOverrides, occurrenceOverrides) {
                            doesDateHaveClasses(date, term, termRules, calendarOverrides, occurrenceOverrides)
                        }

                        Column(
                            modifier = Modifier
                                .width(itemWidth)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        isToday -> MaterialTheme.colorScheme.primaryContainer
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable { onSelectDate(date) }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = date.dayOfWeek.ChineseLabel.replace("周", ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.88f)
                                    isToday -> XPeachTheme.readablePrimary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = if (date.dayOfMonth == 1) "${date.monthValue}月" else "${date.dayOfMonth}",
                                style = if (date.dayOfMonth == 1 && date.monthValue >= 10) {
                                    MaterialTheme.typography.labelSmall
                                } else if (date.dayOfMonth == 1) {
                                    MaterialTheme.typography.labelMedium
                                } else {
                                    MaterialTheme.typography.titleMedium
                                },
                                fontWeight = if (isSelected || isToday || date.dayOfMonth == 1) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Medium
                                },
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimary
                                    isToday -> XPeachTheme.readablePrimary
                                    date.dayOfMonth == 1 -> XPeachTheme.readablePrimary
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            !hasClasses -> Color.Transparent
                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                            isToday -> XPeachTheme.readablePrimary
                                            else -> XPeachTheme.readablePrimary
                                        }
                                    )
                            )
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onNextWeek),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "下一周",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private data class HomeModularCardSpec(
    val id: String,
    val title: String,
    val badgeText: String,
    val subtitle: String,
    val highlighted: Boolean = false,
    val actionPillText: String? = null,
    val onActionPillClick: (() -> Unit)? = null,
    val onClick: () -> Unit
)

private fun String?.cleanUiText(): String? {
    val trimmed = this?.trim() ?: return null
    if (trimmed.isEmpty()) return null
    val lower = trimmed.lowercase()
    if (lower == "null" || lower == "undefined" || lower == "none" || lower == "nil") return null
    val cleaned = trimmed
        .replace(Regex("(?i)\\s*·\\s*null\\b"), "")
        .replace(Regex("(?i)\\bnull\\s*·\\s*"), "")
        .trim()
    if (cleaned.isEmpty() || cleaned.equals("null", ignoreCase = true)) return null
    return cleaned
}

@Composable
private fun TodayExpressPickupHeroCard(
    pendingPickups: List<cn.edu.haut.campus.core.database.ParcelItemEntity>,
    onMarkPickedUp: (String) -> Unit,
    onMarkStationAllPickedUp: (String) -> Unit,
    onSyncParcels: () -> Unit,
    onOpenParcelHub: () -> Unit
) {
    val groupedByStation = remember(pendingPickups) {
        pendingPickups.groupBy {
            it.stationName.cleanUiText() ?: "快递驿站"
        }
    }

    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.primaryAccentCardColor,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    XPeachStatusBadge(
                        text = "待取 ${pendingPickups.size} 件",
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = "待取快递",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XPeachFilterPill(
                        label = "同步",
                        selected = false,
                        compact = true,
                        onClick = onSyncParcels
                    )
                    XPeachFilterPill(
                        label = "全部快递",
                        selected = true,
                        compact = true,
                        onClick = onOpenParcelHub
                    )
                }
            }

            groupedByStation.entries.forEachIndexed { stationIdx, (stationName, stationParcels) ->
                if (stationIdx > 0) {
                    XPeachInsetDivider(verticalPadding = 4.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📍 $stationName (${stationParcels.size}件)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = XPeachTheme.readablePrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (stationParcels.size > 1 && stationParcels.first().stationName.cleanUiText() != null) {
                            XPeachFilterPill(
                                label = "全部已取",
                                selected = false,
                                compact = true,
                                onClick = { onMarkStationAllPickedUp(stationName) }
                            )
                        }
                    }

                    stationParcels.take(4).forEachIndexed { parcelIdx, parcel ->
                        if (parcelIdx > 0) {
                            XPeachInsetDivider(verticalPadding = 2.dp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onOpenParcelHub)
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = parcel.pickupCode.cleanUiText() ?: "暂未出码",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = XPeachTheme.readablePrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val carrier = parcel.carrierName.cleanUiText() ?: "快递"
                                val desc = parcel.itemTitle.cleanUiText()
                                    ?: "尾号 ${parcel.trackingNumber.takeLast(6)}"
                                Text(
                                    text = "$carrier · $desc",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            XPeachFilterPill(
                                label = "已取件",
                                selected = true,
                                compact = true,
                                onClick = { onMarkPickedUp(parcel.id) }
                            )
                        }
                    }

                    if (stationParcels.size > 4) {
                        Text(
                            text = "其余 ${stationParcels.size - 4} 件 ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary,
                            modifier = Modifier.clickable(onClick = onOpenParcelHub)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayLibrarySeatDynamicCard(
    activePlans: List<cn.edu.haut.campus.core.database.LibraryBookingPlanEntity>,
    onOpenLibrarySeats: () -> Unit,
    onCancelPlan: (String) -> Unit
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.secondaryAccentCardColor,
        borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.40f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    XPeachStatusBadge(
                        text = if (activePlans.any { it.status == "CONFIRMED" }) "已预约" else "待预约",
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = "座位预约",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                XPeachFilterPill(
                    label = "选座",
                    selected = false,
                    compact = true,
                    onClick = onOpenLibrarySeats
                )
            }

            activePlans.take(2).forEachIndexed { idx, plan ->
                XPeachInsetDivider(verticalPadding = if (idx == 0) 4.dp else 6.dp)
                val cleanArea = plan.preferredAreaName.cleanUiText() ?: "自习区"
                val firstSeat = plan.preferredSeatLabel.cleanUiText().orEmpty().split(",")
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() } ?: "自动选座"
                val statusLabel = when (plan.status) {
                    "CONFIRMED" -> "预约成功"
                    "FINAL_CHECK_OK" -> "即将预约"
                    "PRECHECK_OK" -> "等待预约"
                    else -> "已设定时预约"
                }
                val engineLabel = if (plan.executionMode == "CLOUD_HOSTED") {
                    "云端预约"
                } else {
                    "本机预约"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "$cleanArea · $firstSeat 号座",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${plan.targetLocalDate} ${plan.targetLocalTime} · $engineLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary
                        )
                        XPeachStatusDotText(
                            text = statusLabel,
                            dotColor = if (plan.status == "CONFIRMED") {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                XPeachTheme.readablePrimary
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        XPeachFilterPill(
                            label = "查看",
                            selected = true,
                            compact = true,
                            onClick = onOpenLibrarySeats
                        )
                        if (plan.status != "CONFIRMED") {
                            XPeachFilterPill(
                                label = "取消",
                                selected = false,
                                compact = true,
                                onClick = { onCancelPlan(plan.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayCampusBentoSection(
    examSchedules: List<ExamScheduleItem>,
    activeTermId: String?,
    selectedDate: LocalDate,
    todayDate: LocalDate,
    currentNow: ZonedDateTime,
    teachingWeek: Int,
    rules: List<CourseScheduleRule>,
    activeFocusSession: cn.edu.haut.campus.core.model.FocusSessionDomain?,
    completedFocusSessions: List<cn.edu.haut.campus.core.model.FocusSessionDomain>,
    onlineRooms: List<cn.edu.haut.campus.core.model.UtilityOnlineRoomDomain>,
    utilityMeters: List<cn.edu.haut.campus.core.model.UtilityMeterDomain>,
    utilityReadings: List<cn.edu.haut.campus.core.model.UtilityReadingDomain>,
    parcelItems: List<cn.edu.haut.campus.core.database.ParcelItemEntity>,
    bookingPlans: List<cn.edu.haut.campus.core.database.LibraryBookingPlanEntity>,
    cardOrder: List<String>,
    hiddenCards: Set<String>,
    fullWidthCards: Set<String>,
    campusClubsCount: Int = PresetCampusCommunityCatalog.defaultCampusClubs.size,
    competitionsCount: Int = PresetCampusCommunityCatalog.defaultCompetitions.size,
    mealRecommendation: ClassEndMealRecommendation,
    onOpenDiningDialog: () -> Unit,
    onOpenCustomizer: () -> Unit,
    onNavigateToGrades: () -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit,
    onNavigateToFocus: () -> Unit,
    onNavigateToElectricity: () -> Unit,
    onNavigateToLibrarySeats: () -> Unit,
    onNavigateToLibraryCatalog: () -> Unit,
    onNavigateToEmptyClassrooms: () -> Unit,
    onNavigateToCommunity: () -> Unit,
    onNavigateToCompetitions: () -> Unit = onNavigateToCommunity,
    onNavigateToParcelHub: () -> Unit
) {
    // 顶部板块栏：左标题 + 右侧「编辑」按钮
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "常用功能",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        XPeachFilterPill(
            label = "编辑",
            selected = false,
            onClick = onOpenCustomizer
        )
    }

    val nearestExam = remember(examSchedules, activeTermId, selectedDate, todayDate) {
        val sameDate = examSchedules.firstOrNull { it.examDate == selectedDate }
        if (sameDate != null) {
            sameDate
        } else {
            val pool = if (activeTermId != null) {
                examSchedules.filter { it.termId == activeTermId }.ifEmpty { examSchedules }
            } else {
                examSchedules
            }
            pool.filter { it.examDate == null || !it.examDate!!.isBefore(todayDate) }
                .minByOrNull { it.examDate ?: LocalDate.of(2099, 12, 31) }
        }
    }

    val todayDoneMins = remember(completedFocusSessions, todayDate) {
        completedFocusSessions.sumOf { s ->
            val doneMs = s.completedAtEpochMs ?: s.expectedEndAtEpochMs
            val d = Instant.ofEpochMilli(doneMs).atZone(HAUT_ZONE_ID).toLocalDate()
            if (d == todayDate) s.durationMinutes else 0
        }
    }
    val runningFocus = activeFocusSession
    val focusBadge = if (runningFocus != null) "进行中" else "番茄钟"
    val focusSub = if (runningFocus != null) {
        val remMin = ((runningFocus.remainingMillis(System.currentTimeMillis()) + 59_999L) / 60_000L)
            .coerceAtLeast(1L)
        "剩余约 $remMin 分钟"
    } else if (todayDoneMins > 0) {
        "今日已专注 $todayDoneMins 分钟"
    } else {
        "番茄钟倒计时"
    }

    val (elecBadge, elecSub) = remember(onlineRooms, utilityMeters, utilityReadings) {
        if (onlineRooms.isNotEmpty()) {
            val room = onlineRooms.first()
            val firstDev = decodeOnlineDevicesJson(room.devicesJson).firstOrNull()
            val b = firstDev?.balanceRaw?.takeIf { it.isNotBlank() } ?: "已绑定"
            b to room.roomName
        } else if (utilityMeters.isNotEmpty()) {
            val meter = utilityMeters.first()
            val latest = utilityReadings.filter { it.meterId == meter.id }
                .maxByOrNull { it.recordedAtEpochMs }
            val b = if (latest != null) "${latest.value}${meter.unit}" else "记录"
            b to "${meter.roomName} · ${meter.name}"
        } else {
            "电费" to "剩余电量与用电记录"
        }
    }

    val (currentSlotLabel, freeRoomsCount) = remember(currentNow, selectedDate, teachingWeek, activeTermId, rules) {
        val slot = CampusStudyRoomCatalog.resolveActiveSlot(currentNow.toLocalTime())
        val freeCount = CampusStudyRoomCatalog.computeFreeRoomsCount(
            rules = rules,
            activeTermId = activeTermId,
            teachingWeek = teachingWeek,
            weekday = selectedDate.dayOfWeek,
            slot = slot
        )
        slot.label to freeCount
    }

    val examSpec = if (nearestExam != null) {
        val daysLeft = nearestExam.examDate?.let { ChronoUnit.DAYS.between(todayDate, it) }
        val countdownBadge = when {
            daysLeft == null -> "待定"
            daysLeft == 0L -> "今天考试"
            daysLeft == 1L -> "明天考试"
            daysLeft > 1L -> "还有 ${daysLeft} 天"
            else -> "考试安排"
        }
        HomeModularCardSpec(
            id = "EXAM_SUMMARY",
            title = nearestExam.courseName,
            badgeText = countdownBadge,
            subtitle = "${nearestExam.rawTimeText} · ${nearestExam.rawRoom}",
            highlighted = daysLeft == 0L || daysLeft == 1L,
            actionPillText = "去考场",
            onActionPillClick = { onNavigateToMapWithRoom(nearestExam.rawRoom) },
            onClick = onNavigateToGrades
        )
    } else {
        HomeModularCardSpec(
            id = "EXAM_SUMMARY",
            title = "考试与成绩",
            badgeText = "学业",
            subtitle = if (examSchedules.isNotEmpty()) {
                "本学期 ${examSchedules.size} 场考试 · 成绩与绩点"
            } else {
                "成绩、绩点与考试安排"
            },
            onClick = onNavigateToGrades
        )
    }

    val specsById = mapOf(
        "EXAM_SUMMARY" to examSpec,
        "STUDY_FOCUS" to HomeModularCardSpec(
            id = "STUDY_FOCUS",
            title = "自习专注",
            badgeText = focusBadge,
            subtitle = focusSub,
            highlighted = runningFocus != null,
            onClick = onNavigateToFocus
        ),
        "DORM_ELECTRICITY" to HomeModularCardSpec(
            id = "DORM_ELECTRICITY",
            title = "宿舍电费",
            badgeText = elecBadge,
            subtitle = elecSub,
            onClick = onNavigateToElectricity
        ),
        "LIBRARY_SEAT" to run {
            val activePlan = bookingPlans.firstOrNull {
                it.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK", "CONFIRMED")
            }
            if (activePlan != null) {
                val cleanArea = activePlan.preferredAreaName.cleanUiText() ?: "自习区"
                val seatNo = activePlan.preferredSeatLabel.cleanUiText()?.split(",")?.firstOrNull()?.trim() ?: "自动选座"
                HomeModularCardSpec(
                    id = "LIBRARY_SEAT",
                    title = "座位预约",
                    badgeText = if (activePlan.status == "CONFIRMED") "已预约" else "待预约",
                    subtitle = "$cleanArea · ${seatNo}号 (${activePlan.targetLocalTime})",
                    highlighted = true,
                    onClick = onNavigateToLibrarySeats
                )
            } else {
                HomeModularCardSpec(
                    id = "LIBRARY_SEAT",
                    title = "座位预约",
                    badgeText = "选座",
                    subtitle = "选区选座与定时预约",
                    onClick = onNavigateToLibrarySeats
                )
            }
        },
        HomeCardModule.PARCEL_HUB.id to run {
            val waitingParcels = parcelItems.filter { it.isPendingPickup() }
            val waitingCount = waitingParcels.size
            val firstWaiting = waitingParcels.firstOrNull()
            HomeModularCardSpec(
                id = HomeCardModule.PARCEL_HUB.id,
                title = HomeCardModule.PARCEL_HUB.title,
                badgeText = if (waitingCount > 0) "待取 $waitingCount" else "取件码",
                subtitle = if (firstWaiting != null) {
                    val codeLabel = firstWaiting.pickupCode.cleanUiText() ?: "待取件"
                    val stationOrTitle = firstWaiting.stationName.cleanUiText()
                        ?: firstWaiting.itemTitle.cleanUiText()
                        ?: firstWaiting.trackingNumber
                    "$codeLabel · $stationOrTitle"
                } else {
                    "取件码汇总与查件"
                },
                highlighted = waitingCount > 0,
                onClick = onNavigateToParcelHub
            )
        },
        "LIBRARY_CATALOG" to HomeModularCardSpec(
            id = "LIBRARY_CATALOG",
            title = "馆藏检索",
            badgeText = "馆藏",
            subtitle = "书目搜索与在馆状态",
            onClick = onNavigateToLibraryCatalog
        ),
        "EMPTY_CLASSROOM" to HomeModularCardSpec(
            id = "EMPTY_CLASSROOM",
            title = HomeCardModule.EMPTY_CLASSROOM.title,
            badgeText = currentSlotLabel,
            subtitle = if (rules.isNotEmpty()) "当前 $freeRoomsCount 间教室空闲" else "按教学楼与节次查询",
            onClick = onNavigateToEmptyClassrooms
        ),
        HomeCardModule.CAMPUS_COMMUNITY.id to HomeModularCardSpec(
            id = HomeCardModule.CAMPUS_COMMUNITY.id,
            title = HomeCardModule.CAMPUS_COMMUNITY.title,
            badgeText = "$campusClubsCount 社团",
            subtitle = "校园社团与交流群",
            onClick = onNavigateToCommunity
        ),
        HomeCardModule.COMPETITION_GUIDE.id to HomeModularCardSpec(
            id = HomeCardModule.COMPETITION_GUIDE.id,
            title = HomeCardModule.COMPETITION_GUIDE.title,
            badgeText = "$competitionsCount 赛事",
            subtitle = HomeCardModule.COMPETITION_GUIDE.subtitle,
            actionPillText = "查赛事",
            onActionPillClick = onNavigateToCompetitions,
            onClick = onNavigateToCompetitions
        ),
        HomeCardModule.CAMPUS_DINING.id to HomeModularCardSpec(
            id = HomeCardModule.CAMPUS_DINING.id,
            title = HomeCardModule.CAMPUS_DINING.title,
            badgeText = mealRecommendation.primaryCandidate.profile.shortName,
            subtitle = mealRecommendation.primaryCandidate.recommendedDishes.take(2).joinToString(" / "),
            highlighted = mealRecommendation.isWithinOneHourBeforeEnd,
            actionPillText = "去导航",
            onActionPillClick = { onNavigateToMapWithRoom(mealRecommendation.primaryCandidate.profile.name) },
            onClick = onOpenDiningDialog
        )
    )

    val visibleSpecs = cardOrder
        .filter { it !in hiddenCards }
        .mapNotNull { specsById[it] }

    if (visibleSpecs.isEmpty()) {
        XPeachWell(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenCustomizer)
        ) {
            Text(
                text = "卡片已全部隐藏，点击恢复",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        // 智能模块化排版：通栏卡片独占一行，相邻双列卡片自动两两配对成双列 Bento 网格
        var idx = 0
        while (idx < visibleSpecs.size) {
            val current = visibleSpecs[idx]
            val isCurrentFull = current.id in fullWidthCards
            if (isCurrentFull) {
                FullWidthModularCard(spec = current)
                idx += 1
            } else {
                val next = visibleSpecs.getOrNull(idx + 1)
                if (next != null && next.id !in fullWidthCards) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BentoModuleTile(
                            modifier = Modifier.weight(1f),
                            title = current.title,
                            badgeText = current.badgeText,
                            subtitle = current.subtitle,
                            highlighted = current.highlighted,
                            onClick = current.onClick
                        )
                        BentoModuleTile(
                            modifier = Modifier.weight(1f),
                            title = next.title,
                            badgeText = next.badgeText,
                            subtitle = next.subtitle,
                            highlighted = next.highlighted,
                            onClick = next.onClick
                        )
                    }
                    idx += 2
                } else {
                    FullWidthModularCard(spec = current)
                    idx += 1
                }
            }
        }
    }
}

@Composable
private fun FullWidthModularCard(spec: HomeModularCardSpec) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (spec.highlighted) {
            XPeachTheme.primaryAccentCardColor
        } else {
            MaterialTheme.colorScheme.surface
        },
        borderColor = if (spec.highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        onClick = spec.onClick,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (spec.highlighted) {
                        XPeachStatusBadge(
                            text = spec.badgeText,
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        text = spec.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!spec.highlighted) {
                        XPeachStatusDotText(text = spec.badgeText)
                    }
                }
                Text(
                    text = spec.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (spec.actionPillText != null && spec.onActionPillClick != null) {
                XPeachFilterPill(
                    label = spec.actionPillText,
                    selected = spec.highlighted,
                    onClick = spec.onActionPillClick
                )
            }
        }
    }
}

@Composable
private fun HomeCardLayoutCustomizerDialog(
    currentOrder: List<String>,
    currentHidden: Set<String>,
    currentFullWidth: Set<String>,
    onSave: (List<String>, Set<String>, Set<String>) -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit
) {
    var workingOrder by remember(currentOrder) {
        mutableStateOf(HomeCardModule.normalizeOrderIds(currentOrder))
    }
    var workingHidden by remember(currentHidden) { mutableStateOf(currentHidden) }
    var workingFullWidth by remember(currentFullWidth) { mutableStateOf(currentFullWidth) }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("卡片管理", style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "调整顺序、尺寸与显示状态",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                workingOrder.forEachIndexed { index, cardId ->
                    val title = HomeCardModule.fromId(cardId)?.title ?: cardId
                    val isVisible = cardId !in workingHidden
                    val isFull = cardId in workingFullWidth

                    if (index > 0) {
                        XPeachInsetDivider(verticalPadding = 6.dp)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${index + 1}. $title",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isVisible) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            XPeachStatusDotText(
                                text = if (!isVisible) {
                                    "已隐藏"
                                } else if (isFull) {
                                    "大卡片"
                                } else {
                                    "小卡片"
                                },
                                dotColor = if (isVisible) {
                                    XPeachTheme.readablePrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                XPeachFilterPill(
                                    label = if (isFull) "小卡" else "大卡",
                                    selected = isFull,
                                    compact = true,
                                    onClick = {
                                        workingFullWidth = if (isFull) {
                                            workingFullWidth - cardId
                                        } else {
                                            workingFullWidth + cardId
                                        }
                                    }
                                )
                                XPeachFilterPill(
                                    label = if (isVisible) "显示" else "隐藏",
                                    selected = isVisible,
                                    compact = true,
                                    onClick = {
                                        workingHidden = if (isVisible) {
                                            workingHidden + cardId
                                        } else {
                                            workingHidden - cardId
                                        }
                                    }
                                )
                                XPeachFilterPill(
                                    label = "↑",
                                    selected = false,
                                    compact = true,
                                    onClick = {
                                        if (index > 0) {
                                            val mut = workingOrder.toMutableList()
                                            val tmp = mut[index - 1]
                                            mut[index - 1] = mut[index]
                                            mut[index] = tmp
                                            workingOrder = mut
                                        }
                                    }
                                )
                                XPeachFilterPill(
                                    label = "↓",
                                    selected = false,
                                    compact = true,
                                    onClick = {
                                        if (index < workingOrder.lastIndex) {
                                            val mut = workingOrder.toMutableList()
                                            val tmp = mut[index + 1]
                                            mut[index + 1] = mut[index]
                                            mut[index] = tmp
                                            workingOrder = mut
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                TextButton(
                    onClick = onResetDefault,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("恢复默认")
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSave(workingOrder, workingHidden, workingFullWidth) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun BentoModuleTile(
    modifier: Modifier = Modifier,
    title: String,
    badgeText: String,
    subtitle: String,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    XPeachCard(
        modifier = modifier,
        containerColor = if (highlighted) {
            XPeachTheme.primaryAccentCardColor
        } else {
            MaterialTheme.colorScheme.surface
        },
        borderColor = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
            if (highlighted) {
                XPeachStatusBadge(
                    text = badgeText,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                XPeachStatusDotText(text = badgeText)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyDayTimelineBox(
    hasAnyCourses: Boolean,
    nextClassHint: cn.edu.haut.campus.core.model.NextClassDayHint?,
    onJumpToDate: (LocalDate) -> Unit,
    onOpenImporter: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        XPeachInsetDivider(verticalPadding = 4.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (hasAnyCourses) "当日无课" else "暂未导入课表",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (nextClassHint != null) {
                FilledTonalButton(
                    onClick = { onJumpToDate(nextClassHint.nextDate) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "查看 ${nextClassHint.nextDate.format(DATE_TITLE_FMT)} (${nextClassHint.firstCourseName})",
                        maxLines = 1,
                        softWrap = false
                    )
                }
            } else if (!hasAnyCourses) {
                FilledTonalButton(
                    onClick = onOpenImporter,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("导入课表", maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * 极简流式日程列表：左列展示起止时间与节次，中列圆角彩条，右列呼吸感课程卡片，行内高亮正在上课/下一节课并提供一键去教室。
 */
@Composable
private fun MinimalistDayScheduleFlow(
    items: List<TimelineOccurrenceState>,
    breakIntervals: List<ScheduleBreakInterval>,
    highlightedOccurrenceId: String?,
    termId: String,
    globalReminderEnabled: Boolean,
    defaultLeadMinutes: Int,
    tasksByEventKey: Map<String, ReminderTaskDomain>,
    overridesByScope: Map<String, ReminderEventOverride>,
    onItemClick: (TimelineOccurrenceState) -> Unit,
    onNavigateRoom: (String) -> Unit
) {
    val sortedItems = remember(items) {
        items.sortedBy { it.occurrence.startTime }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        sortedItems.forEachIndexed { index, item ->
            val occ = item.occurrence
            val isFinished = item.status == ClassStatus.FINISHED
            val isOngoing = item.status == ClassStatus.IN_PROGRESS
            val isHighlighted = isOngoing || (highlightedOccurrenceId != null && occ.occurrenceId == highlightedOccurrenceId)
            val accentColor = XPeachTheme.courseAccent(occ.colorArgb)
            val reminderBadge = formatOccurrenceReminderBadge(
                termId = termId,
                item = item,
                globalReminderEnabled = globalReminderEnabled,
                defaultLeadMinutes = defaultLeadMinutes,
                tasksByEventKey = tasksByEventKey,
                overridesByScope = overridesByScope
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .alpha(if (isFinished) 0.62f else 1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧时间与节次列
                Column(
                    modifier = Modifier
                        .width(52.dp)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = occ.startTime.format(TIME_FMT),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = occ.endTime.format(TIME_FMT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        text = occ.periodRangeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = accentColor,
                        maxLines = 1,
                        softWrap = false
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 竖向彩色圆角指示条
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // 右侧课程内容柔和卡片
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onItemClick(item) },
                    shape = RoundedCornerShape(14.dp),
                    color = when {
                        isOngoing -> XPeachTheme.courseContainer(occ.colorArgb, active = true)
                        isHighlighted -> XPeachTheme.courseContainer(occ.colorArgb, active = false)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = when {
                        isOngoing -> androidx.compose.foundation.BorderStroke(
                            1.dp,
                            XPeachTheme.courseBorder(occ.colorArgb, active = true)
                        )
                        isHighlighted -> androidx.compose.foundation.BorderStroke(
                            0.9.dp,
                            XPeachTheme.courseBorder(occ.colorArgb, active = false)
                        )
                        XPeachTheme.isLiquidGlass -> androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            XPeachTheme.specularRimBrush(intensity = 0.72f)
                        )
                        XPeachTheme.isDark -> androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.60f)
                        )
                        else -> null
                    }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = occ.courseName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            Text(
                                text = item.statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isHighlighted) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${occ.rawRoom}${occ.teacher?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            )
                            if (isHighlighted && occ.rawRoom.isNotBlank()) {
                                XPeachFilterPill(
                                    label = "去教室",
                                    selected = isOngoing,
                                    onClick = { onNavigateRoom(occ.rawRoom) }
                                )
                            }
                        }

                        if (!reminderBadge.isNullOrBlank()) {
                            Text(
                                text = reminderBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = XPeachTheme.readablePrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        occ.internalBreaksSummary?.let { brkText ->
                            Text(
                                text = brkText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (item.activeInternalBreak != null) {
                                    XPeachTheme.readablePrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 两门课之间的课间连接条
            val nextItem = sortedItems.getOrNull(index + 1)
            if (nextItem != null) {
                val matchingBreak = breakIntervals.firstOrNull {
                    it.startTime == occ.endTime && it.endTime == nextItem.occurrence.startTime
                }
                if (matchingBreak != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 65.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(18.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        Spacer(modifier = Modifier.width(13.dp))
                        Surface(
                            color = if (matchingBreak.isActiveNow) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.then(
                                if (matchingBreak.requiresRoomChange) {
                                    Modifier.clickable { onNavigateRoom(matchingBreak.toRoom) }
                                } else Modifier
                            )
                        ) {
                            Text(
                                text = buildString {
                                    append("${matchingBreak.label} ${matchingBreak.durationMinutes} 分钟")
                                    matchingBreak.roomTransferText?.let { append(" · $it") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (matchingBreak.isActiveNow) {
                                    XPeachTheme.readablePrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(
                                    horizontal = if (matchingBreak.isActiveNow) 10.dp else 0.dp,
                                    vertical = 2.dp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodayDiningRecommendHeroCard(
    recommendation: ClassEndMealRecommendation,
    onShuffle: () -> Unit,
    onOpenAllCanteens: () -> Unit,
    onNavigateToCanteen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val primary = recommendation.primaryCandidate
    val minsLeft = recommendation.minutesUntilClassEnd
    val badgeText = if (minsLeft != null && minsLeft > 0) {
        "距下课 ${minsLeft} 分钟"
    } else {
        "${recommendation.mealSlot.label}推荐"
    }

    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.primaryAccentCardColor,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
        onClick = onOpenAllCanteens
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                XPeachStatusBadge(
                    text = badgeText,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = "下课去 · ${primary.profile.name}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onDismiss) {
                Text("收起", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = primary.reasonTag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "推荐：${primary.recommendedDishes.joinToString(" · ")}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XPeachFilterPill(
                    label = "换一家",
                    selected = false,
                    compact = true,
                    onClick = onShuffle
                )
                XPeachFilterPill(
                    label = "全部食堂",
                    selected = false,
                    compact = true,
                    onClick = onOpenAllCanteens
                )
            }
            XPeachFilterPill(
                label = "导航去${primary.profile.shortName}",
                selected = true,
                compact = true,
                onClick = { onNavigateToCanteen(primary.profile.name) }
            )
        }
    }
}

@Composable
private fun CampusDiningRecommendationDialog(
    recommendation: ClassEndMealRecommendation,
    mealRecommendEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onShuffle: () -> Unit,
    onTestNotification: () -> Unit,
    onNavigateToCanteen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val primary = recommendation.primaryCandidate
    val block = recommendation.block
    val minsLeft = recommendation.minutesUntilClassEnd

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("下课食堂推荐")
                XPeachFilterPill(
                    label = "换个推荐",
                    selected = true,
                    compact = true,
                    onClick = onShuffle
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. 当前首选推荐卡片
                XPeachWell {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "首选 · ${primary.profile.name}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = XPeachTheme.readablePrimary
                        )
                        XPeachStatusBadge(
                            text = if (minsLeft != null && minsLeft > 0) "距下课 ${minsLeft}m" else recommendation.mealSlot.label
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    if (block != null) {
                        Text(
                            text = "参考课段：${block.courseTitle}（${block.classroomText} · ${block.endTime.format(TIME_FMT)} 下课）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    Text(
                        text = primary.reasonTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "推荐档口：${primary.recommendedDishes.joinToString(" / ")}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { onNavigateToCanteen(primary.profile.name) },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("步行导航去${primary.profile.shortName}")
                    }
                }

                // 2. 校区全部食堂对比
                Text(
                    text = "全部食堂（按当前教室距离与顺路排序）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                for (cand in recommendation.allCandidates) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .clickable { onNavigateToCanteen(cand.profile.name) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cand.profile.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                cand.walkMinutes?.let { wm ->
                                    Text(
                                        text = "约 ${wm} 分钟",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = XPeachTheme.readablePrimary
                                    )
                                }
                            }
                            Text(
                                text = cand.profile.areaLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = cand.recommendedDishes.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        XPeachFilterPill(
                            label = "去这里",
                            selected = cand.profile.buildingId == primary.profile.buildingId,
                            compact = true,
                            onClick = { onNavigateToCanteen(cand.profile.name) }
                        )
                    }
                }

                XPeachInsetDivider(verticalPadding = 4.dp)

                // 3. 离下课 1 小时自动通知开关与测试
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "离下课 1 小时自动通知",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "距连续课段下课 60 分钟时弹窗推荐就近食堂",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = mealRecommendEnabled,
                        onCheckedChange = onToggleEnabled
                    )
                }

                OutlinedButton(
                    onClick = onTestNotification,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("发送测试推荐通知")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

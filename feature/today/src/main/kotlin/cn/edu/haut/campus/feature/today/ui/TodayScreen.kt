package cn.edu.haut.campus.feature.today.ui

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.edu.haut.campus.core.database.isPendingPickup
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import kotlinx.coroutines.delay
import cn.edu.haut.campus.feature.today.state.TodayScreenStatus
import cn.edu.haut.campus.feature.today.state.TodayUiAction
import cn.edu.haut.campus.feature.today.state.TodayUiState
import cn.edu.haut.campus.feature.today.state.TodayWriteKind

@Composable
fun TodayScreen(
    state: TodayUiState,
    onAction: (TodayUiAction) -> Unit,
    modifier: Modifier = Modifier,
    resetTrigger: Int = 0,
    extensionSlot: @Composable () -> Unit = {}
) {
    when (state.status) {
        TodayScreenStatus.LOADING,
        TodayScreenStatus.ERROR_RETRYABLE,
        TodayScreenStatus.ERROR_TERMINAL,
        TodayScreenStatus.NEEDS_PERMISSION,
        TodayScreenStatus.NEEDS_LOGIN,
        TodayScreenStatus.UNSUPPORTED -> TodayStatusScaffold(
            status = state.status,
            onAction = onAction,
            modifier = modifier
        )
        else -> TodayLegacyContent(
            state = state,
            onAction = onAction,
            modifier = modifier,
            resetTrigger = resetTrigger,
            extensionSlot = extensionSlot
        )
    }
}

@Composable
private fun TodayLegacyContent(
    state: TodayUiState,
    onAction: (TodayUiAction) -> Unit,
    modifier: Modifier = Modifier,
    resetTrigger: Int = 0,
    extensionSlot: @Composable () -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberScrollState()

    val terms = state.terms
    val term = state.currentTerm
    val courses = state.courses
    val rules = state.scheduleRules
    val periodTimes = state.periodTimes
    val calendarOverrides = state.calendarOverrides
    val occurrenceOverrides = state.occurrenceOverrides
    val buildings = state.buildings
    val examSchedules = state.examSchedules
    val unscheduledCourses = state.unscheduledCourses
    val prefs = state.prefs
    val activeFocusSession = state.activeFocusSession
    val completedFocusSessions = state.completedFocusSessions
    val onlineRooms = state.onlineRooms
    val utilityMeters = state.utilityMeters
    val utilityReadings = state.utilityReadings
    val parcelItems = state.parcelItems
    val bookingPlans = state.bookingPlans
    val activeReminderTasks = state.activeReminderTasks
    val reminderOverrides = state.reminderOverrides
    val isDynamicSyncing = state.isDynamicSyncing
    val dynamicSyncBanner = state.dynamicSyncBanner
    val pendingLegacyRestoreTargets = state.pendingLegacyRestoreTargets
    val legacyDeletePending = state.pendingWrites.any { it.kind == TodayWriteKind.DeleteLegacyOccurrenceOverride }

    val onNavigateToMapWithRoom: (String) -> Unit = { room -> onAction(TodayUiAction.OpenMapRoom(room)) }
    val onNavigateToImporter: () -> Unit = { onAction(TodayUiAction.OpenImporter) }
    val onNavigateToSettings: () -> Unit = { onAction(TodayUiAction.OpenSettings) }
    val onNavigateToFocus: () -> Unit = { onAction(TodayUiAction.OpenFocus) }
    val onNavigateToElectricity: () -> Unit = { onAction(TodayUiAction.OpenElectricity) }
    val onNavigateToLibraryCatalog: () -> Unit = { onAction(TodayUiAction.OpenLibraryCatalog) }
    val onNavigateToLibrarySeats: () -> Unit = { onAction(TodayUiAction.OpenLibrarySeats) }
    val onNavigateToEmptyClassrooms: () -> Unit = { onAction(TodayUiAction.OpenEmptyClassrooms) }
    val onNavigateToCommunity: () -> Unit = { onAction(TodayUiAction.OpenCommunity) }
    val onNavigateToCompetitions: () -> Unit = { onAction(TodayUiAction.OpenCompetitions) }
    val onNavigateToMoments: () -> Unit = { onAction(TodayUiAction.OpenMoments) }
    val onNavigateToParcelHub: () -> Unit = { onAction(TodayUiAction.OpenParcelHub) }
    val onNavigateToGrades: () -> Unit = { onAction(TodayUiAction.OpenGrades) }
    val onNavigateToTimetable: () -> Unit = { onAction(TodayUiAction.OpenTimetable) }

    val tasksByEventKey = remember(activeReminderTasks) {
        activeReminderTasks.associateBy { it.eventKey }
    }
    val overridesByScope = remember(reminderOverrides) {
        reminderOverrides.associateBy { it.eventScopeKey }
    }

    val todayClock = LocalTodayClock.current
    val interactionState = rememberTodayInteractionState(todayClock.now())
    var currentNow by interactionState.currentNow
    var selectedDate by interactionState.selectedDate
    var selectedOccurrenceForDetail by interactionState.selectedOccurrenceForDetail
    var showTermSwitcherDialog by interactionState.showTermSwitcherDialog
    var showCardCustomizerDialog by interactionState.showCardCustomizerDialog
    var showDiningDialog by interactionState.showDiningDialog
    var diningShuffleOffset by interactionState.diningShuffleOffset
    var dismissedMealBlockKey by interactionState.dismissedMealBlockKey

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val updatedNow = todayClock.now()
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

    // 「自习教室」卡片 N/N 无冲突计数是否具备本学期课表依据：
    // 与展开器共用严格校验——孤儿停课覆盖/跨学期残留 rules/缺作息表且未自带合法时间
    // 一律无依据，改显示纯名录；判定结果带原因态（依据不完整 ≠ 尚无依据）。
    val studyScheduleBasis = remember(term, courses, rules, periodTimes, occurrenceOverrides) {
        CampusStudyRoomCatalog.evaluateCurrentTermScheduleBasis(
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            occurrenceOverrides = occurrenceOverrides
        )
    }
    val hasStudyScheduleBasis = studyScheduleBasis.hasBasis
    val studyBasisReasonLabel = studyScheduleBasis.reasonLabel

    val mealRecommendation = remember(
        selectedDate,
        currentNow,
        snapshot.items,
        buildings,
        diningShuffleOffset,
        prefs.canteenDishReviews
    ) {
        CampusDiningRecommender.buildRecommendation(
            date = selectedDate,
            now = currentNow.toInstant(),
            occurrences = snapshot.items.map { it.occurrence },
            buildings = buildings,
            shuffleOffset = diningShuffleOffset,
            userReviews = prefs.canteenDishReviews
        )
    }

    Column(
        modifier = modifier
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
                            onAction(TodayUiAction.SyncAcademic)
                        } else {
                            onAction(TodayUiAction.OpenImporter)
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
            TodayStatusBanner(status = state.status)
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
                            TextButton(onClick = { onAction(TodayUiAction.DismissDynamicSyncBanner) }) {
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
                        onAction(TodayUiAction.MarkParcelPickedUp(parcelId))
                    },
                    onMarkStationAllPickedUp = { stationName ->
                        onAction(TodayUiAction.MarkStationParcelsPickedUp(stationName))
                    },
                    onSyncParcels = {
                        onAction(TodayUiAction.SyncParcels)
                    },
                    onOpenParcelHub = onNavigateToParcelHub
                )
            }

            // 1.55 已验签插件的 home.cards 贡献槽；宿主只提供公开命令路由， UI 层不接触 Repository/安装器。
            extensionSlot()

            // 1.6 动态今日图书馆座位 / 自动抢座状态卡（有已约座位或待执行抢座计划时自动展示）
            val activeSeatPlans = remember(bookingPlans) {
                bookingPlans.filter {
                    it.status in setOf(
                        "ARMED",
                        "PRECHECK_OK",
                        "FINAL_CHECK_OK",
                        "EXECUTING",
                        "CONFIRMED",
                        "DELEGATION_STOPPED_SCHOOL_CONFIRMED",
                        "RESULT_UNKNOWN",
                        "DELEGATION_STOPPED_RESULT_UNKNOWN",
                        "CANCEL_RESULT_UNKNOWN",
                        "NEEDS_RECONFIRMATION"
                    )
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
                    onStopDelegation = { planId ->
                        onAction(TodayUiAction.StopSeatDelegation(planId))
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
                        val todayCanceledCount = remember(
                            selectedDate,
                            term,
                            courses,
                            rules,
                            periodTimes,
                            calendarOverrides,
                            occurrenceOverrides,
                            buildings
                        ) {
                            ScheduleEngine.resolveCanceledOccurrencesForDate(
                                date = selectedDate,
                                term = term,
                                courses = courses,
                                rules = rules,
                                periodTimes = periodTimes,
                                calendarOverrides = calendarOverrides,
                                occurrenceOverrides = occurrenceOverrides,
                                buildings = buildings
                            ).size
                        }
                        Text(
                            text = when {
                                courses.isEmpty() -> "未导入"
                                snapshot.items.isEmpty() && todayCanceledCount > 0 -> "已停课 ${todayCanceledCount} 节"
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

                val pendingPracticalCourses = remember(unscheduledCourses, term?.id) {
                    unscheduledCourses.filter { term == null || it.termId == term?.id }
                }
                if (pendingPracticalCourses.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(XPeachTheme.secondaryAccentCardColor)
                            .clickable(onClick = onNavigateToTimetable)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${pendingPracticalCourses.size} 门实践课时间待定（可自定义排课时间）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "去课表设置 ▸",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = XPeachTheme.readablePrimary
                        )
                    }
                }

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
                    val canceledOnDate = remember(
                        dateForView,
                        term,
                        courses,
                        rules,
                        periodTimes,
                        calendarOverrides,
                        occurrenceOverrides,
                        buildings
                    ) {
                        ScheduleEngine.resolveCanceledOccurrencesForDate(
                            date = dateForView,
                            term = term,
                            courses = courses,
                            rules = rules,
                            periodTimes = periodTimes,
                            calendarOverrides = calendarOverrides,
                            occurrenceOverrides = occurrenceOverrides,
                            buildings = buildings
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (daySnapshot.items.isEmpty() && canceledOnDate.isEmpty()) {
                            EmptyDayTimelineBox(
                                hasAnyCourses = courses.isNotEmpty(),
                                nextClassHint = daySnapshot.nextClassDayHint,
                                onJumpToDate = { selectedDate = it },
                                onOpenImporter = onNavigateToImporter
                            )
                        } else if (daySnapshot.items.isNotEmpty()) {
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
                        if (canceledOnDate.isNotEmpty()) {
                            XPeachWell(
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "当日已停课 (${canceledOnDate.size} 节) · 可随时恢复上课",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    for (canceledOcc in canceledOnDate) {
                                        val restoreWriteLifecycle = rememberTodayWriteLifecycle(
                                            key = "restore-" + canceledOcc.occurrenceId,
                                            writeCompletions = state.writeCompletions,
                                            onConsumeCompletion = { token ->
                                                onAction(TodayUiAction.ConsumeWriteCompletion(token))
                                            }
                                        )
                                        Column(modifier = Modifier.fillMaxWidth()) {
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
                                                        text = canceledOcc.courseName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "${canceledOcc.periodRangeText} · ${canceledOcc.startTime.format(TIME_FMT)}–${canceledOcc.endTime.format(TIME_FMT)} · ${canceledOcc.rawRoom}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                XPeachFilterPill(
                                                    label = if (restoreWriteLifecycle.pending) "恢复中…" else "恢复上课",
                                                    selected = true,
                                                    compact = true,
                                                    modifier = Modifier.alpha(if (restoreWriteLifecycle.pending) 0.58f else 1f),
                                                    onClick = {
                                                        if (!restoreWriteLifecycle.pending) {
                                                            // 点击瞬间冻结全部恢复输入；token 只关联本次恢复，同 kind 并发/旧结果不会错位。
                                                            val writeToken = restoreWriteLifecycle.begin(TodayWriteKind.RestoreCanceledOccurrence)
                                                            onAction(
                                                                TodayUiAction.RestoreCanceledOccurrence(
                                                                    termId = term?.id,
                                                                    date = canceledOcc.date,
                                                                    courseName = canceledOcc.courseName,
                                                                    overrideId = canceledOcc.overrideId,
                                                                    mergedRuleIds = canceledOcc.mergedRuleIds,
                                                                    occurrenceOverrides = occurrenceOverrides,
                                                                    writeToken = writeToken
                                                                )
                                                            )
                                                        }
                                                    }
                                                )
                                            }
                                            restoreWriteLifecycle.errorMessage?.let { message ->
                                                Text(
                                                    text = message,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
                resolvedDateOccurrences = snapshot.items.map { it.occurrence },
                hasStudyScheduleBasis = hasStudyScheduleBasis,
                studyBasisReasonLabel = studyBasisReasonLabel,
                selectedCampusId = prefs.selectedCampusId,
                activeTimeTableId = term?.timeTableId,
                periodTimes = periodTimes,
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
                onNavigateToMoments = onNavigateToMoments,
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
                onAction(TodayUiAction.SaveHomeCardLayout(newOrder, newHidden, newFullWidth))
                showCardCustomizerDialog = false
            },
            onResetDefault = {
                onAction(TodayUiAction.ResetHomeCardLayout)
                showCardCustomizerDialog = false
            },
            onDismiss = { showCardCustomizerDialog = false }
        )
    }

    if (showDiningDialog) {
        CampusDiningRecommendationDialog(
            recommendation = mealRecommendation,
            userReviews = prefs.canteenDishReviews,
            mealRecommendEnabled = prefs.mealRecommendEnabled,
            onToggleEnabled = { enabled ->
                onAction(TodayUiAction.SetMealRecommendEnabled(enabled))
            },
            onSaveReview = { canteenId, dishName, stallLocation, priceYuan, stars, tags, comment ->
                onAction(
                    TodayUiAction.SaveCanteenDishReview(
                        canteenBuildingId = canteenId,
                        dishName = dishName,
                        stallLocation = stallLocation,
                        priceYuan = priceYuan,
                        stars = stars,
                        tags = tags,
                        comment = comment
                    )
                )
            },
            onDeleteReview = { canteenId, dishName ->
                onAction(TodayUiAction.DeleteCanteenDishReview(canteenId, dishName))
            },
            onShuffle = { diningShuffleOffset += 1 },
            onTestNotification = {
                onAction(TodayUiAction.TestMealRecommendation(diningShuffleOffset))
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
        val detailTermId = term?.id ?: "default"
        val detailEventKey = "CLASS:" + detailTermId + ":" + detailState.occurrence.occurrenceId
        CourseDetailAndSnoozeDialog(
            detailState = detailState,
            termId = detailTermId,
            snoozeOptions = state.snoozeOptions.takeIf { state.snoozeOptionsEventKey == detailEventKey }.orEmpty(),
            actionFeedback = state.courseReminderFeedback.takeIf { state.courseReminderFeedbackEventKey == detailEventKey },
            globalConfig = prefs.reminderConfig,
            activeTask = tasksByEventKey[detailEventKey],
            overridesByScope = overridesByScope,
            writeCompletions = state.writeCompletions,
            onAction = onAction,
            onNavigateToMapWithRoom = { room ->
                selectedOccurrenceForDetail = null
                onAction(TodayUiAction.OpenMapRoom(room))
            },
            onNavigateToTimetable = {
                selectedOccurrenceForDetail = null
                onAction(TodayUiAction.OpenTimetable)
            },
            onDismiss = { selectedOccurrenceForDetail = null }
        )
    }

    // 旧版模糊调课恢复后的待核对目标：逐项由用户决定删除或保留
    if (pendingLegacyRestoreTargets.isNotEmpty()) {
        XPeachAlertDialog(
            onDismissRequest = { if (!legacyDeletePending) onAction(TodayUiAction.ClearLegacyRestoreTargets) },
            title = { Text("核对旧版调课调入记录") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "该停课记录来自旧版调课，源时段已恢复。以下调入记录可能与之关联，请逐条确认是否删除；不确定时可保留待核对。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    for (target in pendingLegacyRestoreTargets) {
                        val targetCourse = target.courseId?.let { cid -> courses.firstOrNull { it.id == cid } }
                        val deleteWriteLifecycle = rememberTodayWriteLifecycle(
                            key = "legacy-delete-" + target.id,
                            writeCompletions = state.writeCompletions,
                            onConsumeCompletion = { token ->
                                onAction(TodayUiAction.ConsumeWriteCompletion(token))
                            }
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
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
                                        text = targetCourse?.name ?: "课程记录",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = buildString {
                                            append("调入 ${target.date}")
                                            if (target.startPeriod != null && target.endPeriod != null) {
                                                append(" · 第${target.startPeriod}-${target.endPeriod}节")
                                            }
                                            target.room?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                XPeachFilterPill(
                                    label = if (deleteWriteLifecycle.pending) "删除中…" else "删除",
                                    selected = false,
                                    compact = true,
                                    modifier = Modifier.alpha(if (deleteWriteLifecycle.pending) 0.58f else 1f),
                                    onClick = {
                                        if (!deleteWriteLifecycle.pending) {
                                            val writeToken = deleteWriteLifecycle.begin(TodayWriteKind.DeleteLegacyOccurrenceOverride)
                                            onAction(
                                                TodayUiAction.DeleteLegacyOccurrenceOverride(
                                                    overrideId = target.id,
                                                    writeToken = writeToken
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                            deleteWriteLifecycle.errorMessage?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onAction(TodayUiAction.ClearLegacyRestoreTargets) },
                    enabled = !legacyDeletePending
                ) {
                    Text("保留待核对")
                }
            }
        )
    }

    if (showTermSwitcherDialog) {
        val termWriteLifecycle = rememberTodayWriteLifecycle(
            key = "term-switcher",
            writeCompletions = state.writeCompletions,
            onConsumeCompletion = { token ->
                onAction(TodayUiAction.ConsumeWriteCompletion(token))
            },
            onWriteSuccess = { showTermSwitcherDialog = false }
        )
        val termWritePending = termWriteLifecycle.pending
        XPeachAlertDialog(
            onDismissRequest = { if (!termWritePending) showTermSwitcherDialog = false },
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
                                .clickable(enabled = !termWritePending) {
                                    val writeToken = termWriteLifecycle.begin(TodayWriteKind.SelectCurrentTerm)
                                    onAction(TodayUiAction.SelectCurrentTerm(t.id, writeToken = writeToken))
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
                                text = if (termWritePending) "写入中…" else if (isCurrent) "当前 ($count 门)" else "$count 门课",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isCurrent) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    termWriteLifecycle.errorMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            showTermSwitcherDialog = false
                            onNavigateToImporter()
                        },
                        enabled = !termWritePending,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("同步其他学期", maxLines = 1, softWrap = false)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showTermSwitcherDialog = false },
                    enabled = !termWritePending
                ) {
                    Text("关闭")
                }
            }
        )
    }
}

/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.feature.settings.library.seat.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.database.LibrarySeatAlarmSubsystem
import cn.edu.haut.campus.core.database.LocalSeatPermissionSnapshot
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.DualEngineReservationPlanner
import cn.edu.haut.campus.core.campusservices.HautLibrarySeatAuthClient
import cn.edu.haut.campus.core.campusservices.LibraryRegionSeatsSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionType
import cn.edu.haut.campus.core.campusservices.LibrarySeatBookingRecord
import cn.edu.haut.campus.core.campusservices.LibrarySeatBookingStatus
import cn.edu.haut.campus.core.campusservices.LibrarySeatItem
import cn.edu.haut.campus.core.campusservices.LibrarySeatOverviewSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.LibraryStudyRegionItem
import cn.edu.haut.campus.core.campusservices.OfficialServiceRoutePolicy
import cn.edu.haut.campus.core.campusservices.OfficialWebScripts
import cn.edu.haut.campus.core.campusservices.SafeWebNavigationPolicy
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.campusservices.cleanNullLiteral
import cn.edu.haut.campus.core.campusservices.cleanNullableLiteral
import androidx.compose.foundation.verticalScroll
import cn.edu.haut.campus.core.campusservices.TripleScheduleStage
import cn.edu.haut.campus.core.database.CloudIdentityBindingEntity
import cn.edu.haut.campus.feature.settings.library.seat.state.BookingSubmissionIdentity
import cn.edu.haut.campus.feature.settings.library.seat.state.LibrarySeatUiAction
import cn.edu.haut.campus.core.database.LibraryBookingPlanEntity
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachGlassSurface
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.feature.settings.library.state.BookingDraftParams
import cn.edu.haut.campus.feature.settings.library.state.BookingDraftSnapshot
import cn.edu.haut.campus.feature.settings.library.state.BookingIdentityContext
import cn.edu.haut.campus.feature.settings.library.state.BookingSubmitEvent
import cn.edu.haut.campus.feature.settings.library.state.BookingSubmitPhase
import cn.edu.haut.campus.feature.settings.library.state.LibraryBookingDateState
import cn.edu.haut.campus.feature.settings.library.state.LibraryBookingPlanSubmitter
import cn.edu.haut.campus.feature.settings.library.state.LibraryBookingSubmitStateMachine
import cn.edu.haut.campus.feature.settings.library.state.PendingPriceReconfirmation
import cn.edu.haut.campus.feature.settings.library.state.parseLibrarySeatPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AutomatedLibrarySeatBookingWorkbench(
    actions: LibrarySeatUiAction,
    sessionState: LibrarySeatSessionState,
    seatOverview: LibrarySeatOverviewSnapshot?,
    bookingPlans: List<LibraryBookingPlanEntity>,
    cloudBinding: CloudIdentityBindingEntity?,
    hasBoundCloudAccount: Boolean,
    hasSavedSchoolCredentials: Boolean,
    onRequireLogin: () -> Unit,
    onNavigateToXpeachCloud: (() -> Unit)? = null,
    onRefreshSeatOverview: () -> Unit,
    onFeedback: (Boolean, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val recentRecord = remember(seatOverview) {
        seatOverview?.currentBooking ?: seatOverview?.recentHistory?.firstOrNull()
    }
    var selectedMode by remember(hasBoundCloudAccount) {
        mutableStateOf(BookingExecutionMode.CLOUD_HOSTED)
    }
    val submitMachine = remember { LibraryBookingSubmitStateMachine() }
    // 保存日期选择而非相对天数计算结果；恢复前台后立即依据时钟核验。
    var savedDateSelection by rememberSaveable { mutableStateOf<String?>(null) }
    val dateState = remember(actions) {
        LibraryBookingDateState(
            initialSelection = savedDateSelection ?: if (java.time.LocalTime.now(ZoneId.of("Asia/Shanghai")).hour >= 7) "TOMORROW" else "TODAY",
            onDateChanged = { submitMachine.cancelPendingReconfirmation() }
        )
    }
    fun chooseDate(selection: String) {
        savedDateSelection = selection
        dateState.choose(selection)
    }
    val useTomorrow = dateState.selection == "TOMORROW"
    var selectedHour by rememberSaveable { mutableIntStateOf(7) }
    var selectedMinute by rememberSaveable { mutableIntStateOf(0) }
    var showMoreTimeSlots by rememberSaveable { mutableStateOf(false) }
    var selectedRepeatDays by rememberSaveable { mutableIntStateOf(1) }

    val targetTimeText = remember(selectedHour, selectedMinute) {
        "%02d:%02d".format(selectedHour, selectedMinute)
    }
    val todayDate = dateState.today
    val tomorrowDate = todayDate.plusDays(1)
    val targetDate = dateState.targetDate
    val layoutDate = dateState.layoutDate
    val isReferenceLayout = dateState.isReferenceLayout
    val regions = dateState.regions
    val isLoadingRegions = dateState.isLoadingRegions
    val regionsLoadError = dateState.regionsLoadError
    var selectedFloorFilter by remember { mutableStateOf("全部楼层") }
    val selectedRegion = dateState.selectedRegion
    val seatSnapshot = dateState.seatSnapshot
    val isLoadingSeats = dateState.isLoadingSeats
    val seatsLoadError = dateState.seatsLoadError
    var selectedSeatPage by remember { mutableIntStateOf(0) }
    val selectedSeatNames = dateState.selectedSeatNames
    var isRunningImmediateBooking by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionSnapshot by remember { mutableStateOf(LibrarySeatAlarmSubsystem.inspectPermissions(context)) }
    var showPermissionActivationDialog by remember { mutableStateOf(false) }
    // R5-D3 预约提交草稿状态机（EDITING→SUBMITTING→PRICE_CHANGED→USER_CONFIRMED→SUBMITTING→ACCEPTED）：
    // 待确认记录绑定不可变 BookingDraftSnapshot（身份/学号/世代/base/参数），
    // 任何上下文变化 → revision++ 并清空 pending；取消绝不重发；确认绑定同一不可变 draft。
    // 提交接线层：identityProvider 每次复核取最新身份会话（含世代/base/学号），
    // paramsProvider 每次取最新草稿参数；submitPlan 只消费不可变快照
    val submitter = remember(actions) {
        LibraryBookingPlanSubmitter(
            machine = submitMachine,
            identityProvider = {
                val session = actions.snapshotBookingSubmissionIdentity()
                BookingIdentityContext(
                    profileId = session.profileId,
                    cloudUserId = session.cloudUserId,
                    identityGeneration = session.generation,
                    normalizedBase = session.apiBaseUrl,
                    studentNo = session.studentNo
                )
            },
            paramsProvider = {
                dateState.draftParams(
                    engine = selectedMode,
                    time = "%02d:%02d".format(selectedHour, selectedMinute),
                    area = dateState.selectedRegion?.regionName.orEmpty(),
                    repeatDays = selectedRepeatDays
                )
            },
            prepareSubmission = { dateState.prepareSubmission() },
            submitPlan = { snap ->
                actions.createDualEngineBookingPlan(
                    executionMode = snap.engine,
                    targetDate = LocalDate.parse(snap.targetDate),
                    targetTimeText = snap.targetTime,
                    preferredAreaName = snap.areaName,
                    preferredSeatLabel = snap.seatLabel,
                    repeatDays = snap.repeatDays,
                    acceptedPriceMicropeach = snap.acceptedPriceMicropeach,
                    expectedIdentity = BookingSubmissionIdentity(
                        snap.profileId, snap.cloudUserId, snap.identityGeneration, snap.normalizedBase, snap.studentNo
                    )
                ).map { }
            }
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        permissionSnapshot = LibrarySeatAlarmSubsystem.inspectPermissions(context)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionSnapshot = LibrarySeatAlarmSubsystem.inspectPermissions(context)
                scope.launch {
                    // 回到前台即复核身份会话：跨页切号/换址/换学号后，
                    // 状态机修订号推进并即时作废旧待确认快照
                    dateState.refreshClock()
                    runCatching { submitter.refreshContext() }
                    runCatching { actions.rescheduleActiveLocalBookingPlans() }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun requestNotificationPermissionOrSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                context.startActivity(LibrarySeatAlarmSubsystem.buildNotificationSettingsIntent(context))
            }
        }
    }

    fun requestExactAlarmPermission() {
        runCatching {
            context.startActivity(LibrarySeatAlarmSubsystem.buildExactAlarmSettingsIntent(context))
        }
    }

    fun requestIgnoreBatteryOptimizations() {
        runCatching {
            context.startActivity(LibrarySeatAlarmSubsystem.buildIgnoreBatteryOptimizationsIntent(context))
        }.onFailure {
            runCatching {
                context.startActivity(LibrarySeatAlarmSubsystem.buildBatteryOptimizationListIntent())
            }
        }
    }

    fun reloadStudyRegions(refresh: Boolean = false) {
        scope.launch {
            dateState.loadRegions(recentRecord) { actions.loadLibraryPlanningRegions(it, refresh) }
        }
    }

    fun reloadSeatsForRegion(region: LibraryStudyRegionItem, refresh: Boolean = false) {
        scope.launch {
            dateState.loadSeats(region, recentRecord) { _, date ->
                actions.loadLibraryPlanningSeats(region, date, refresh)
            }
            selectedSeatPage = 0
        }
    }

    var showKnownSeatEditor by rememberSaveable { mutableStateOf(false) }
    var knownArea by rememberSaveable { mutableStateOf("") }
    var knownSeats by rememberSaveable { mutableStateOf("") }
    fun openKnownSeatEditor() {
        knownArea = dateState.selectedRegion?.regionName.orEmpty()
        knownSeats = dateState.selectedSeatNames.joinToString(", ")
        showKnownSeatEditor = true
    }
    if (showKnownSeatEditor) {
        val candidates = parseLibrarySeatPreferences(knownSeats)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showKnownSeatEditor = false },
            title = { Text("指定已知座位") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("填写学校显示的自习室名称和座位名称，最多 3 个，按首选、备选排序。到点自动核验并尝试预约。")
                    OutlinedTextField(value = knownArea, onValueChange = { knownArea = it },
                        label = { Text("自习室名称") }, singleLine = true)
                    OutlinedTextField(value = knownSeats, onValueChange = { knownSeats = it },
                        label = { Text("首选和备选座位，用逗号分隔") }, singleLine = true)
                    if (candidates.size > 3) Text("最多选择 3 个座位")
                }
            },
            confirmButton = {
                TextButton(enabled = knownArea.isNotBlank() && candidates.size in 1..3, onClick = {
                    val area = knownArea.trim()
                    dateState.selectedRegion = regions.firstOrNull { it.regionName == area }
                        ?: LibraryStudyRegionItem(0, area)
                    dateState.selectedSeatNames = candidates
                    showKnownSeatEditor = false
                }) { Text("使用这些座位") }
            },
            dismissButton = { TextButton(onClick = { showKnownSeatEditor = false }) { Text("取消") } }
        )
    }

    // 前台日期可观察；后台暂停轮询，ON_RESUME 和每次查询/提交仍复核时钟。
    LaunchedEffect(dateState, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (dateState.refreshClock()) submitter.refreshContext()
                delay(1_000)
            }
        }
    }

    LaunchedEffect(actions, targetDate, layoutDate, sessionState is LibrarySeatSessionState.Authenticated) {
        reloadStudyRegions()
    }

    LaunchedEffect(selectedRegion?.id, selectedRegion?.regionName, targetDate, layoutDate) {
        val region = selectedRegion ?: return@LaunchedEffect
        reloadSeatsForRegion(region)
    }

    val floorOptions = remember(regions) {
        listOf("全部楼层") + regions.map { it.layerName }.filter { it.isNotBlank() }.distinct()
    }
    val filteredRegions = remember(regions, selectedFloorFilter) {
        if (selectedFloorFilter == "全部楼层") {
            regions
        } else {
            regions.filter { it.layerName == selectedFloorFilter }
        }
    }

    val allSeats = seatSnapshot?.seats.orEmpty()
    val filteredSeats = allSeats
    val seatChunks = remember(filteredSeats) {
        filteredSeats.chunked(60)
    }
    val currentSeatPageItems = seatChunks.getOrNull(selectedSeatPage).orEmpty()

    val computedScheduleResult = remember(targetTimeText, targetDate) {
        runCatching {
            DualEngineReservationPlanner.computeSchedule(
                planId = "preview",
                targetDate = targetDate,
                targetTime = DualEngineReservationPlanner.parseTargetLocalTime(targetTimeText)
            )
        }
    }

    val roomLabel = selectedRegion?.regionName.orEmpty()
    val preferredSeatsText = selectedSeatNames.joinToString(", ")

    // 上下文同步：身份（含世代/base 变更重写绑定行）与全部草稿参数任一变化
    // → 状态机修订号 +1 并清空 pending（旧确认快照即刻作废）
    LaunchedEffect(
        selectedMode, targetDate, targetTimeText, roomLabel, preferredSeatsText,
        selectedRepeatDays, cloudBinding?.cloudUserId, cloudBinding?.profileId,
        cloudBinding?.updatedAtEpochMillis
    ) {
        submitter.refreshContext()
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 顶部核心卡片：抢座执行方式（默认云端抢座）与 XPeach 云端账号 / 学号状态
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "抢座引擎",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                XPeachStatusBadge(
                    text = if (selectedMode == BookingExecutionMode.CLOUD_HOSTED) "手机关机断网照常开抢" else selectedMode.label,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = XPeachTheme.readablePrimary
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    BookingExecutionMode.CLOUD_HOSTED,
                    BookingExecutionMode.LOCAL_DEVICE,
                    BookingExecutionMode.MANUAL_REMINDER
                ).forEach { mode ->
                    XPeachFilterPill(
                        label = mode.label,
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode }
                    )
                }
            }
            Text(
                text = selectedMode.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (selectedMode == BookingExecutionMode.CLOUD_HOSTED) {
                XPeachInsetDivider(verticalPadding = 8.dp)
                if (cloudBinding != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            XPeachStatusDotText(
                                text = "已连接 XPeach：${cloudBinding.displayName}",
                                dotColor = XPeachTheme.readablePrimary
                            )
                            Text(
                                text = "当前余额 %.2f 🍑 · 仅学校确认成功后扣 1 🍑".format(
                                    cloudBinding.peachBalanceMicropeach / 10_000.0
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val snap = actions.syncWithCampusCloud().getOrNull()
                                        val bal = snap?.peachBalance ?: actions.refreshXpeachWalletBalance()
                                        if (bal != null) {
                                            onFeedback(true, "已同步云端任务与最新余额：%.2f 🍑".format(bal))
                                        } else {
                                            onFeedback(true, "已触发云端同步")
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("同步", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://campus.yellowpeachxgp.com/reservations")).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text("网页端 ›", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    Text(
                        text = "尚未登录 XPeach 云账号：登录后即可在云端服务器每日 07:00 毫秒级自动抢座",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (onNavigateToXpeachCloud != null) {
                            Button(
                                onClick = onNavigateToXpeachCloud,
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("登录 XPeach 云账号")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://campus.yellowpeachxgp.com/reservations")).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("打开网页版云端抢座")
                        }
                    }
                }
            }
        }

        if (sessionState !is LibrarySeatSessionState.Authenticated) {
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "尚未登录校园学号",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "登录学号与统一认证密码后，即可读取实时空座并授权云端 07:00 自动抢座",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onRequireLogin,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("登录学号")
                    }
                }
            }
        }

        // 第一步：选择日期与时间（全点选，零手输）
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "1. 选择日期与时间",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                XPeachFilterPill(
                    label = "明天 ($tomorrowDate)",
                    selected = useTomorrow,
                    onClick = { chooseDate("TOMORROW") }
                )
                XPeachFilterPill(
                    label = "今天 ($todayDate)",
                    selected = dateState.selection == "TODAY",
                    onClick = {
                        chooseDate("TODAY")
                        val nowSh = java.time.LocalTime.now(ZoneId.of("Asia/Shanghai"))
                        val picked = java.time.LocalTime.of(selectedHour, selectedMinute)
                        if (picked.isBefore(nowSh) && nowSh.hour in 7..20) {
                            val nextMin = ((nowSh.minute / 5) + 1) * 5
                            if (nextMin < 60) {
                                selectedHour = nowSh.hour
                                selectedMinute = nextMin
                            } else {
                                selectedHour = (nowSh.hour + 1).coerceAtMost(21)
                                selectedMinute = 0
                            }
                        }
                    }
                )
            }

            TextButton(onClick = {
                android.app.DatePickerDialog(context, { _, year, month, day ->
                    chooseDate(LocalDate.of(year, month + 1, day).toString())
                }, targetDate.year, targetDate.monthValue - 1, targetDate.dayOfMonth).show()
            }) {
                Text(if (dateState.selection !in setOf("TODAY", "TOMORROW")) "指定日期：$targetDate" else "选择指定日期")
            }
            Text("今天、明天会随日期更新并重新查询；指定日期保持固定。跨日后请核实座位并重新确认。", style = MaterialTheme.typography.labelSmall)

            Text(
                text = "抢座时间（已选 $targetTimeText）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val quickPresets = listOf(
                    Triple(7, 0, "07:00 开馆"),
                    Triple(7, 30, "07:30"),
                    Triple(8, 0, "08:00"),
                    Triple(8, 30, "08:30"),
                    Triple(9, 0, "09:00"),
                    Triple(12, 30, "12:30"),
                    Triple(14, 0, "14:00"),
                    Triple(20, 59, "20:59")
                )
                for ((hh, mm, label) in quickPresets) {
                    XPeachFilterPill(
                        label = label,
                        selected = selectedHour == hh && selectedMinute == mm,
                        onClick = {
                            selectedHour = hh
                            selectedMinute = mm
                        }
                    )
                }
                XPeachFilterPill(
                    label = if (showMoreTimeSlots) "收起 ▲" else "自定义时间 ▼",
                    selected = showMoreTimeSlots,
                    onClick = { showMoreTimeSlots = !showMoreTimeSlots }
                )
            }

            AnimatedVisibility(visible = showMoreTimeSlots) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "选择小时（07:00 起，21:30 前）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (h in 7..21) {
                            XPeachFilterPill(
                                label = "%02d点".format(h),
                                selected = selectedHour == h,
                                onClick = {
                                    selectedHour = h
                                    if (h == 21 && selectedMinute >= 30) {
                                        selectedMinute = 25
                                    }
                                }
                            )
                        }
                    }
                    Text(
                        text = "选择分钟：",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val minuteOptions = if (selectedHour == 21) {
                            listOf(0, 5, 10, 15, 20, 25)
                        } else {
                            listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 59)
                        }
                        for (m in minuteOptions) {
                            XPeachFilterPill(
                                label = "%02d分".format(m),
                                selected = selectedMinute == m,
                                onClick = { selectedMinute = m }
                            )
                        }
                    }
                }
            }

            Text(
                text = "连续预约周期",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val repeatOptions = listOf(
                    1 to "仅抢 1 天",
                    3 to "连续 3 天",
                    7 to "连续 7 天",
                    14 to "连续 14 天",
                    30 to "连续 30 天"
                )
                for ((days, label) in repeatOptions) {
                    XPeachFilterPill(
                        label = label,
                        selected = selectedRepeatDays == days,
                        onClick = { selectedRepeatDays = days }
                    )
                }
            }
            if (selectedRepeatDays > 1) {
                Text(
                    text = "已开启连续 $selectedRepeatDays 天抢座：$targetDate 至 ${targetDate.plusDays((selectedRepeatDays - 1).toLong())}，每天 $targetTimeText 自动执行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = XPeachTheme.readablePrimary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 第二步：自动读取全馆自习室列表，点击即选
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "2. 选择自习室",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(
                    onClick = { reloadStudyRegions(refresh = true) },
                    enabled = !isLoadingRegions,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(if (isLoadingRegions) "刷新中…" else "刷新", style = MaterialTheme.typography.labelSmall)
                }
            }

            Text(
                "先选座位，再到点自动预约。目录仅提供座位名称，不代表 $targetDate 的空闲情况。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            seatSnapshot?.let { snapshot ->
                Text("目录来源：${snapshot.bookingDate}${if (snapshot.isCachedLayout) "（已保存）" else ""}",
                    style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { openKnownSeatEditor() }) { Text("指定已知座位") }
            val savedPreferences = bookingPlans.map { it.preferredAreaName to it.preferredSeatLabel }
                .filter { it.first.isNotBlank() && it.second.isNotBlank() }.distinct().take(3)
            savedPreferences.forEach { (area, seats) ->
                TextButton(onClick = {
                    dateState.selectedRegion = regions.firstOrNull { it.regionName == area }
                        ?: LibraryStudyRegionItem(0, area)
                    dateState.selectedSeatNames = parseLibrarySeatPreferences(seats)
                }) { Text("已保存：$area · $seats") }
            }

            if (recentRecord != null && recentRecord.regionName.isNotBlank()) {
                FilledTonalButton(
                    onClick = {
                        val match = regions.firstOrNull {
                            it.id == recentRecord.buildingLayerRegionId ||
                                it.regionName.contains(recentRecord.regionName) ||
                                recentRecord.regionName.contains(it.regionName)
                        }
                        dateState.selectedRegion = match ?: LibraryStudyRegionItem(0, recentRecord.regionName)
                        if (recentRecord.seatName.isNotBlank()) {
                            dateState.selectedSeatNames = listOf(recentRecord.seatName)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Text(
                        text = "使用上次座位：${recentRecord.regionName} · ${recentRecord.seatName}号",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (floorOptions.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (floor in floorOptions) {
                        XPeachFilterPill(
                            label = floor,
                            selected = selectedFloorFilter == floor,
                            onClick = { selectedFloorFilter = floor }
                        )
                    }
                }
            }

            if (isLoadingRegions && regions.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "正在加载自习室列表…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (regionsLoadError != null && regions.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "暂时无法更新目录，可使用已保存座位或指定已知座位，不影响提前建计划。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { reloadStudyRegions(refresh = true) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("重新加载")
                        }
                        OutlinedButton(
                            onClick = onRequireLogin,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("登录学号")
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (region in filteredRegions) {
                        val isSelected = selectedRegion?.regionName == region.regionName
                        val bgColor = if (isSelected) {
                            XPeachTheme.primaryAccentCardColor
                        } else {
                            XPeachTheme.elevatedWellColor.copy(alpha = if (XPeachTheme.isDark) 0.58f else 0.42f)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor, RoundedCornerShape(12.dp))
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            1.dp,
                                            XPeachTheme.readablePrimary.copy(alpha = 0.55f),
                                            RoundedCornerShape(12.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    if (selectedRegion?.regionName != region.regionName) {
                                        dateState.selectedSeatNames = emptyList()
                                        dateState.selectedRegion = region
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val cleanRegionName = region.regionName.cleanNullLiteral("自习室")
                                val cleanLayerName = region.layerName.cleanNullLiteral("图书馆")
                                val cleanOpenTime = region.openTime.cleanNullLiteral("07:30")
                                val cleanCloseTime = region.closeTime.cleanNullLiteral("22:00")
                                Text(
                                    text = if (isSelected) "✓ $cleanRegionName" else cleanRegionName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "$cleanLayerName · 参考开放时间 $cleanOpenTime-$cleanCloseTime",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text("${region.totalSeats} 个座位", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // 第三步：自动读取该自习室下属全部座位号，点击即选（最多 3 个）
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "3. 选择座位（最多 3 个）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (selectedRegion != null) {
                            "${selectedRegion?.regionName} · 首选被占时自动顺延备选座位"
                        } else {
                            "请先在上方选择自习室"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedRegion != null) {
                    TextButton(
                        onClick = { selectedRegion?.let { reloadSeatsForRegion(it, refresh = true) } },
                        enabled = !isLoadingSeats,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(if (isLoadingSeats) "读取中…" else "刷新", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 已选座位展示条
            XPeachInsetDivider(verticalPadding = 8.dp)
            if (selectedSeatNames.isEmpty()) {
                Text(
                    text = "请在下方点击选择座位号（最多 3 个）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "已选座位（点击取消）：",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(
                        onClick = { dateState.selectedSeatNames = emptyList() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("清空", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    selectedSeatNames.forEachIndexed { idx, seatNo ->
                        val rankLabel = when (idx) {
                            0 -> "首选"
                            1 -> "备选1"
                            else -> "备选2"
                        }
                        XPeachFilterPill(
                            label = "$rankLabel: ${seatNo}号 ✕",
                            selected = true,
                            compact = true,
                            onClick = {
                                dateState.selectedSeatNames = selectedSeatNames.filterNot { it == seatNo }
                            }
                        )
                    }
                }
            }

            if (isLoadingSeats) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "正在加载座位列表…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (seatsLoadError != null && allSeats.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "暂时无法更新座位表，已选座位保留；也可以指定已知座位。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = {
                                val r = selectedRegion
                                if (r != null) reloadSeatsForRegion(r) else reloadStudyRegions()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("重新加载")
                        }
                        if (sessionState !is LibrarySeatSessionState.Authenticated) {
                            OutlinedButton(
                                onClick = onRequireLogin,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("登录学号")
                            }
                        }
                    }
                }
            } else if (allSeats.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "还没有座位表，可在上方指定已知座位，或更新目录后点选。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FilledTonalButton(
                        onClick = {
                            val r = selectedRegion
                            if (r != null) reloadSeatsForRegion(r) else reloadStudyRegions()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("重新加载座位表")
                    }
                }
            } else if (allSeats.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XPeachFilterPill(
                        label = "随机选 3 个",
                        selected = false,
                        onClick = {
                            dateState.selectedSeatNames = allSeats.shuffled().take(3).map { it.seatName }
                        }
                    )
                }

                if (seatChunks.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        seatChunks.forEachIndexed { pageIndex, chunk ->
                            val firstSeat = chunk.firstOrNull()?.seatName.orEmpty()
                            val lastSeat = chunk.lastOrNull()?.seatName.orEmpty()
                            XPeachFilterPill(
                                label = "${firstSeat}-${lastSeat}号",
                                selected = selectedSeatPage == pageIndex,
                                onClick = { selectedSeatPage = pageIndex }
                            )
                        }
                    }
                }

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (seat in currentSeatPageItems) {
                        val isPicked = seat.seatName in selectedSeatNames
                        val pickIndex = selectedSeatNames.indexOf(seat.seatName)
                        val seatLabel = when {
                            isPicked && pickIndex == 0 -> "★${seat.seatName}"
                            isPicked -> "✓${seat.seatName}"
                            else -> seat.seatName
                        }
                        XPeachFilterPill(
                            label = seatLabel,
                            selected = isPicked,
                            onClick = {
                                dateState.selectedSeatNames = if (isPicked) {
                                    selectedSeatNames.filterNot { it == seat.seatName }
                                } else if (selectedSeatNames.size < 3) {
                                    selectedSeatNames + seat.seatName
                                } else {
                                    selectedSeatNames.drop(1) + seat.seatName
                                }
                            }
                        )
                    }
                }
            }
        }

        // 第四步：确认参数并一键开启抢座
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "4. 确认并开启抢座",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    BookingExecutionMode.CLOUD_HOSTED,
                    BookingExecutionMode.LOCAL_DEVICE,
                    BookingExecutionMode.MANUAL_REMINDER
                ).forEach { mode ->
                    XPeachFilterPill(
                        label = mode.label,
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode }
                    )
                }
            }
            Text(
                text = if (selectedMode == BookingExecutionMode.CLOUD_HOSTED && !hasBoundCloudAccount) {
                    "使用云端抢座需先登录 XPeach 云账号（支持手机关机断网准点自动抢座）"
                } else {
                    selectedMode.subtitle
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (selectedMode == BookingExecutionMode.CLOUD_HOSTED && !hasBoundCloudAccount) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp)
            )
            if (selectedMode == BookingExecutionMode.CLOUD_HOSTED && !hasBoundCloudAccount && onNavigateToXpeachCloud != null) {
                FilledTonalButton(
                    onClick = onNavigateToXpeachCloud,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                ) {
                    Text("去登录 XPeach 云账号")
                }
            }

            if (selectedMode != BookingExecutionMode.CLOUD_HOSTED) {
                XPeachInsetDivider(verticalPadding = 8.dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (permissionSnapshot.allReady) {
                            "本机定时抢座权限已就绪"
                        } else {
                            "本机抢座权限（待开启 ${permissionSnapshot.missingCount} 项）"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (permissionSnapshot.allReady) {
                            XPeachTheme.readablePrimary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    TextButton(
                        onClick = {
                            val ok = LibrarySeatAlarmSubsystem.triggerTestWakeupNotification(context)
                            permissionSnapshot = LibrarySeatAlarmSubsystem.inspectPermissions(context)
                            if (ok) {
                                onFeedback(true, "已发送测试通知，请查看状态栏")
                            } else {
                                requestNotificationPermissionOrSettings()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("测试通知", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    XPeachFilterPill(
                        label = if (permissionSnapshot.exactAlarmAllowed) "✓ 精准闹钟" else "开启精准闹钟 →",
                        selected = permissionSnapshot.exactAlarmAllowed,
                        compact = true,
                        onClick = { requestExactAlarmPermission() }
                    )
                    XPeachFilterPill(
                        label = if (permissionSnapshot.ignoringBatteryOptimizations) "✓ 后台白名单" else "加入后台白名单 →",
                        selected = permissionSnapshot.ignoringBatteryOptimizations,
                        compact = true,
                        onClick = { requestIgnoreBatteryOptimizations() }
                    )
                    XPeachFilterPill(
                        label = if (permissionSnapshot.notificationGranted) "✓ 结果通知" else "开启结果通知 →",
                        selected = permissionSnapshot.notificationGranted,
                        compact = true,
                        onClick = { requestNotificationPermissionOrSettings() }
                    )
                }
            }

            XPeachInsetDivider(verticalPadding = 8.dp)
            Text(
                if (selectedMode == BookingExecutionMode.MANUAL_REMINDER) {
                    "此模式到点仅提醒，需您手动选座，不会自动提交预约。"
                } else {
                    "到点自动按所选顺序尝试首选和备选；不换到未选择的座位。所有候选都不可用时，本次预约失败，不扣费。"
                },
                style = MaterialTheme.typography.labelSmall
            )
            if (isReferenceLayout) {
                Text(
                    if (selectedMode == BookingExecutionMode.MANUAL_REMINDER) {
                        "已选的是座位偏好，目标日可用性未知；到点请查询后手动预约。"
                    } else {
                        "已选的是座位偏好，目标日可用性未知。开启计划后无需再次打开 App 确认；本机模式需保持手机联网和后台运行。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            computedScheduleResult.fold(
                onSuccess = { calc ->
                    val repeatSummary = if (selectedRepeatDays > 1) " · 连续 $selectedRepeatDays 天" else ""
                    Text(
                        text = "已选：$targetDate $targetTimeText$repeatSummary · ${roomLabel.ifBlank { "未选自习室" }} · ${
                            if (selectedSeatNames.isEmpty()) "未选座位" else "${selectedSeatNames.joinToString("、")}号座"
                        }",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary
                    )
                    Text(
                        text = "将于 ${DualEngineReservationPlanner.formatEpochToShanghaiHms(calc.finalCheckAtEpochMillis)} 预检登录状态，并在 ${DualEngineReservationPlanner.formatLocalTimeHms(calc.targetLocalTime)} 准时抢座${if (selectedRepeatDays > 1) "（连续 $selectedRepeatDays 天）" else ""}。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onFailure = { err ->
                    Text(
                        text = err.message ?: "所选时间已过，请切换为明天或选择更晚时间",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            )

            // 提交事件统一落反馈：Accepted → 成功 toast；PriceReconfirmationRequired →
            // 弹窗由状态机 pending 驱动；Stale* 一律诚实吸收，绝不污染新上下文
            fun reportSubmitEvent(ev: BookingSubmitEvent) {
                when (ev) {
                    is BookingSubmitEvent.Accepted -> {
                        val created = ev.snapshot
                        val repeatTag = if (created.repeatDays > 1) "（连续 ${created.repeatDays} 天）" else ""
                        val modePrefix = if (created.engine == BookingExecutionMode.CLOUD_HOSTED) {
                            "已开启云端抢座"
                        } else {
                            "已开启本机定时抢座"
                        }
                        onFeedback(true, "$modePrefix：${created.targetDate} ${created.targetTime}$repeatTag · ${created.areaName}")
                    }
                    is BookingSubmitEvent.PriceReconfirmationRequired -> {
                        val newPrice = ev.pending.effectiveProposedPriceMicropeach
                        if (newPrice == null || newPrice == ev.pending.oldPriceMicropeach) {
                            // 无有效新价可确认：不弹死框，直接反馈
                            submitter.cancelPendingReconfirmation()
                            onFeedback(false, "云端抢座价格已调整，请核实最新价格后重新创建计划")
                        }
                        // 有效新价：弹窗由 submitMachine.visiblePendingReconfirmation() 渲染
                    }
                    is BookingSubmitEvent.Failed -> onFeedback(false, ev.message)
                    is BookingSubmitEvent.StaleConfirmationRefused ->
                        onFeedback(false, "日期、参数或账号状态已变化，请检查当前选择后重新确认")
                    BookingSubmitEvent.Busy, BookingSubmitEvent.StaleResultIgnored -> Unit
                }
            }

            fun saveBookingPlanNow() {
                // 双击/旋转重入守卫：状态机至多一个在途业务尝试，重入被吸收
                if (submitMachine.phase == BookingSubmitPhase.SUBMITTING) return
                scope.launch {
                    reportSubmitEvent(submitter.submitNewAttempt())
                }
            }

            if (showPermissionActivationDialog) {
                XPeachAlertDialog(
                    onDismissRequest = { showPermissionActivationDialog = false },
                    title = { Text("开启本机定时抢座权限") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "为确保息屏状态下准时唤醒抢座，建议开启以下权限：",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!permissionSnapshot.exactAlarmAllowed) {
                                FilledTonalButton(
                                    onClick = { requestExactAlarmPermission() },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("1. 开启精准闹钟权限")
                                }
                            }
                            if (!permissionSnapshot.ignoringBatteryOptimizations) {
                                FilledTonalButton(
                                    onClick = { requestIgnoreBatteryOptimizations() },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("2. 允许后台无限制运行")
                                }
                            }
                            if (!permissionSnapshot.notificationGranted) {
                                FilledTonalButton(
                                    onClick = { requestNotificationPermissionOrSettings() },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("3. 开启抢座结果通知")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showPermissionActivationDialog = false
                                saveBookingPlanNow()
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("继续开启定时抢座")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermissionActivationDialog = false }) {
                            Text("稍后")
                        }
                    }
                )
            }

            // 云端调价再确认弹窗（PRICE_CHANGED）：渲染复核（状态机仅当 pending 仍属
            // 当前草稿才可见）。如实展示账户 + 计划摘要 + 旧价 vs 新价 + 按次计费规则。
            // 「取消」仅关闭弹窗——前次 409 已被云端证明 pre-persist，本机排队计划已被恢复，绝不自动重发；
            // 「接受新价」经确认按钮复核（同一不可变 draft）后才以新价发起全新业务键的重新创建。
            submitMachine.visiblePendingReconfirmation()?.let { pending ->
                val newPrice = pending.effectiveProposedPriceMicropeach
                val oldPrice = pending.oldPriceMicropeach
                    ?: pending.snapshot.acceptedPriceMicropeach
                    ?: 0L
                val accountLabel = cloudBinding?.displayName?.takeIf { it.isNotBlank() }
                    ?: pending.snapshot.cloudUserId.takeIf { it.isNotBlank() }
                    ?: "当前账号"
                val planSummary = buildString {
                    append(pending.snapshot.targetDate)
                    append(" ")
                    append(pending.snapshot.targetTime)
                    append(" · ")
                    append(pending.snapshot.areaName.ifBlank { "未选自习室" })
                    append(" · ")
                    append(pending.snapshot.seatLabel.ifBlank { "未选座位" })
                    if (pending.snapshot.repeatDays > 1) {
                        append(" · 连续 ")
                        append(pending.snapshot.repeatDays)
                        append(" 天")
                    }
                }
                XPeachAlertDialog(
                    onDismissRequest = { submitter.cancelPendingReconfirmation() },
                    title = { Text("云端抢座价格已调整") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "账户：$accountLabel",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "计划：$planSummary",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "您创建时看到的价格为 ${formatMicropeachPriceLabel(oldPrice)} 🍑，云端最新价格为 ${formatMicropeachPriceLabel(newPrice ?: oldPrice)} 🍑（每次抢座成功扣减一次，未抢中不扣费）。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "接受新价将以最新价格重新创建云端抢座计划；取消则保留原有排队安排，不会自动提交。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            enabled = newPrice != null && submitMachine.phase != BookingSubmitPhase.SUBMITTING,
                            onClick = {
                                scope.launch {
                                    // 确认按钮复核：状态机验证 pending 仍属当前不可变 draft，
                                    // 绑定该 draft 携带确认价 + 新 attemptId 发起新尝试
                                    reportSubmitEvent(submitter.confirmPendingReconfirmation())
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("以 ${formatMicropeachPriceLabel(newPrice ?: 0L)} 🍑 重新创建")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { submitter.cancelPendingReconfirmation() }) {
                            Text("取消")
                        }
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = computedScheduleResult.isSuccess && dateState.canCreatePlan,
                    onClick = {
                        if (selectedMode == BookingExecutionMode.CLOUD_HOSTED && !hasBoundCloudAccount) {
                            onFeedback(false, "请先登录 XPeach 云账号后再开启云端抢座")
                            onNavigateToXpeachCloud?.invoke()
                            return@Button
                        }
                        if (selectedMode == BookingExecutionMode.CLOUD_HOSTED && !hasSavedSchoolCredentials) {
                            onFeedback(false, "开启云端准点抢座需保存校园学号与统一认证密码，以便到点自动续签登录")
                            onRequireLogin()
                            return@Button
                        }
                        permissionSnapshot = LibrarySeatAlarmSubsystem.inspectPermissions(context)
                        if (selectedMode != BookingExecutionMode.CLOUD_HOSTED && !permissionSnapshot.allReady) {
                            showPermissionActivationDialog = true
                        } else {
                            saveBookingPlanNow()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        when (selectedMode) {
                            BookingExecutionMode.CLOUD_HOSTED -> "开启云端抢座"
                            BookingExecutionMode.LOCAL_DEVICE -> "开启本机抢座"
                            BookingExecutionMode.MANUAL_REMINDER -> "开启到点提醒"
                        }
                    )
                }

                FilledTonalButton(
                    enabled = !isRunningImmediateBooking && !isReferenceLayout && seatSnapshot != null && dateState.canCreatePlan,
                    onClick = {
                        if (!dateState.prepareImmediateBooking()) {
                            onFeedback(false, "日期或座位结果已变化，请重新查询并确认")
                            return@FilledTonalButton
                        }
                        val immediateDraft = dateState.draftParams(selectedMode, targetTimeText, roomLabel, selectedRepeatDays)
                        val sameDayBlockingPlan = bookingPlans.firstOrNull {
                            it.targetLocalDate == targetDate.toString() &&
                                it.status !in setOf("CANCELLED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED", "REMINDER_SENT")
                        }
                        if (sameDayBlockingPlan != null) {
                            onFeedback(
                                false,
                                "该日期（$targetDate）已有生效、排队或待核对的预约任务（状态：${sameDayBlockingPlan.status}），为防止重复预约，请先在下方任务列表核实或停止现有任务"
                            )
                            return@FilledTonalButton
                        }
                        isRunningImmediateBooking = true
                        scope.launch {
                            val sessionRes = actions.ensureLibrarySeatSession(forceRefresh = false)
                            if (sessionRes !is LibrarySeatSessionState.Authenticated) {
                                isRunningImmediateBooking = false
                                onFeedback(false, "请先登录学号后再选座")
                                onRequireLogin()
                                return@launch
                            }
                            if (!dateState.prepareImmediateBooking() || immediateDraft != dateState.draftParams(selectedMode, "%02d:%02d".format(selectedHour, selectedMinute), dateState.selectedRegion?.regionName.orEmpty(), selectedRepeatDays)) {
                                isRunningImmediateBooking = false
                                onFeedback(false, "预约上下文已变化，请核实日期和座位后重新确认")
                                return@launch
                            }
                            val bookRes = withContext(Dispatchers.IO) {
                                HautLibrarySeatAuthClient.executeAutomatedSeatBooking(
                                    rawToken = sessionRes.snapshot.rawToken,
                                    targetDate = immediateDraft.targetDate,
                                    preferredAreaName = immediateDraft.areaName,
                                    preferredSeatLabel = immediateDraft.seatLabel
                                )
                            }
                            isRunningImmediateBooking = false
                            bookRes.fold(
                                onSuccess = { outcome ->
                                    onFeedback(outcome.confirmed, outcome.diagnosticMessage)
                                    if (outcome.confirmed) {
                                        onRefreshSeatOverview()
                                        selectedRegion?.let { reloadSeatsForRegion(it) }
                                    }
                                },
                                onFailure = { err ->
                                    onFeedback(false, "选座失败：${err.message}")
                                }
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isRunningImmediateBooking) "正在选座…" else "立即选座")
                }
            }
        }

        // 定时选座任务列表
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "定时任务 (${bookingPlans.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (bookingPlans.isEmpty()) {
                Text(
                    text = "暂无定时任务",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    bookingPlans.forEachIndexed { idx, plan ->
                        if (idx > 0) {
                            XPeachInsetDivider(verticalPadding = 8.dp)
                        }
                        val friendlyStatus = when (plan.status) {
                            "ARMED" -> "等待开抢"
                            "PRECHECK_OK", "FINAL_CHECK_OK" -> "就绪待抢"
                            "EXECUTING" -> "正在执行"
                            "CONFIRMED" -> "抢座成功"
                            "DELEGATION_STOPPED_SCHOOL_CONFIRMED" -> "委托已停·预约已成"
                            "SCHOOL_CANCELLED" -> "已退座释放"
                            "CANCEL_RESULT_UNKNOWN" -> "退座待核对"
                            "RESULT_UNKNOWN" -> "结果待核对"
                            "DELEGATION_STOPPED_RESULT_UNKNOWN" -> "已停委托·待核对"
                            "NEEDS_RECONFIRMATION" -> "待重确认授权"
                            "REMINDER_SENT" -> "已发送提醒"
                            "EXPIRED" -> "已过窗口"
                            "FAILED" -> "未抢到"
                            "CANCELLED" -> "已取消"
                            else -> "状态待核实"
                        }
                        val friendlyMode = when (plan.executionMode) {
                            "CLOUD_HOSTED" -> "云端抢座"
                            "NOTIFICATION_ONLY", "MANUAL_REMINDER" -> "到点提醒"
                            else -> "本机抢座"
                        }
                        val cleanAreaName = plan.preferredAreaName.cleanNullLiteral("图书馆自习区")
                        val cleanSeatLabel = plan.preferredSeatLabel.cleanNullLiteral("自动优选")
                        val cleanDiag = plan.lastDiagnosticMessage.cleanNullableLiteral() ?: "等待到点自动抢座"
                        val repeatBadge = if (plan.repeatDays > 1 && plan.remainingDays > 0) {
                            " · 连续${plan.repeatDays}天(剩${plan.remainingDays}天)"
                        } else {
                            ""
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${plan.targetLocalDate} ${plan.targetLocalTime} · $cleanAreaName$repeatBadge",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            )
                            XPeachStatusBadge(
                                text = friendlyStatus,
                                containerColor = when (plan.status) {
                                    "CONFIRMED", "REMINDER_SENT" -> XPeachTheme.successContainer
                                    "DELEGATION_STOPPED_SCHOOL_CONFIRMED", "NEEDS_RECONFIRMATION",
                                    "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN", "CANCEL_RESULT_UNKNOWN" ->
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    "FAILED", "CANCELLED", "SCHOOL_CANCELLED", "EXPIRED" -> XPeachTheme.dangerContainer
                                    else -> MaterialTheme.colorScheme.secondaryContainer
                                },
                                contentColor = when (plan.status) {
                                    "CONFIRMED", "REMINDER_SENT" -> XPeachTheme.onSuccessContainer
                                    "DELEGATION_STOPPED_SCHOOL_CONFIRMED", "NEEDS_RECONFIRMATION",
                                    "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN", "CANCEL_RESULT_UNKNOWN" ->
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    "FAILED", "CANCELLED", "SCHOOL_CANCELLED", "EXPIRED" -> XPeachTheme.onDangerContainer
                                    else -> MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            )
                        }
                        Text(
                            text = "$friendlyMode · 已选座位：$cleanSeatLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = cleanDiag,
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary
                        )
                        val actionPresentation = remember(plan.status, plan.fireStatus, plan.externalBookingId) {
                            cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper.resolveBookingPlanActions(
                                status = plan.status,
                                fireStatus = plan.fireStatus,
                                hasExternalBookingId = !plan.externalBookingId.isNullOrBlank()
                            )
                        }
                        actionPresentation.cautionCaption?.let { caution ->
                            Text(
                                text = caution,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        when {
                            plan.status in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED", "CANCEL_RESULT_UNKNOWN") -> {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                actions.cancelActiveLibrarySeatBooking(
                                                    planId = plan.id,
                                                    bookingId = plan.externalBookingId
                                                ).fold(
                                                    onSuccess = { msg ->
                                                        onFeedback(true, msg)
                                                        onRefreshSeatOverview()
                                                        selectedRegion?.let { reloadSeatsForRegion(it) }
                                                    },
                                                    onFailure = { err ->
                                                        onFeedback(false, err.message ?: "退座待核对")
                                                    }
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(
                                            text = actionPresentation.primaryButtonLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT") -> {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                val stopped = actions.stopInFlightOrQueuedDelegation(plan.id)
                                                onFeedback(
                                                    true,
                                                    stopped?.lastDiagnosticMessage ?: "已停止后续尝试与续期；若单次请求已发出请核实学校端记录"
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(
                                            text = actionPresentation.primaryButtonLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            plan.status == "NEEDS_RECONFIRMATION" -> {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                actions.reconfirmAndRestoreCloudBookingPlans().fold(
                                                    onSuccess = {
                                                        onFeedback(true, "已重新确认云端授权并恢复同步")
                                                    },
                                                    onFailure = { err ->
                                                        onFeedback(false, err.message ?: "重新确认授权失败")
                                                    }
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(actionPresentation.primaryButtonLabel, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                actions.stopInFlightOrQueuedDelegation(plan.id)
                                                onFeedback(true, "已取消该待确认任务")
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(
                                            text = actionPresentation.secondaryButtonLabel ?: "取消排队委托",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            plan.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK") -> {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                // 云端托管计划走显式"现在执行"入口（服务端裁决窗口），
                                                // 本机/提醒计划仍走定时 stage 推进
                                                val updated = if (plan.executionMode.equals(BookingExecutionMode.CLOUD_HOSTED.wireName, ignoreCase = true)) {
                                                    actions.executeCloudBookingPlanNow(plan.id).getOrNull()
                                                } else {
                                                    actions.advanceBookingPlanStage(
                                                        plan.id,
                                                        TripleScheduleStage.FIRE
                                                    ).getOrNull()
                                                }
                                                if (updated != null) {
                                                    onFeedback(
                                                        updated.status in setOf("CONFIRMED", "REMINDER_SENT"),
                                                        updated.lastDiagnosticMessage ?: "已尝试抢座"
                                                    )
                                                    if (updated.status == "CONFIRMED") {
                                                        onRefreshSeatOverview()
                                                    }
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(actionPresentation.primaryButtonLabel, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                actions.stopInFlightOrQueuedDelegation(plan.id)
                                                onFeedback(true, "已停止该排队抢座委托")
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(
                                            text = actionPresentation.secondaryButtonLabel ?: "取消排队委托",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                            plan.status in setOf("RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN") -> {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            onRefreshSeatOverview()
                                            scope.launch { runCatching { actions.syncWithCampusCloud() } }
                                            onFeedback(true, "已发起状态核实，请查看当前有效预约与列表状态")
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(actionPresentation.primaryButtonLabel, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

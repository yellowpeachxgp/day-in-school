/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.feature.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.foundation.verticalScroll
import cn.edu.haut.campus.core.campusservices.TripleScheduleStage
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.LibraryBookingPlanEntity
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private data class PendingSeatActionRequest(
    val action: LibrarySeatActionType,
    val booking: LibrarySeatBookingRecord?
)

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LibrarySeatBookingScreen(
    onBack: () -> Unit,
    repository: CampusRepository? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val prefs = repository?.preferencesFlow?.collectAsState(initial = null)?.value

    val initialCachedSnapshot = remember(repository) {
        repository?.readCachedLibrarySeatSession()?.also { cached ->
            HautLibrarySeatAuthClient.syncCasCookiesToWebView(
                casTgcCookie = cached.casTgcCookie,
                casJsessionId = cached.casJsessionId
            )
        }
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var activeToken by remember { mutableStateOf(initialCachedSnapshot?.rawToken) }
    var sessionState by remember {
        mutableStateOf<LibrarySeatSessionState>(
            if (initialCachedSnapshot != null) {
                LibrarySeatSessionState.Authenticated(initialCachedSnapshot)
            } else if (repository != null) {
                LibrarySeatSessionState.Refreshing("正在自动登录图书馆选座系统…")
            } else {
                LibrarySeatSessionState.Idle
            }
        )
    }
    var initialWebLoadReady by remember {
        mutableStateOf(repository == null || initialCachedSnapshot != null)
    }
    var currentUrl by remember {
        mutableStateOf(OfficialServiceRoutePolicy.buildAuthenticatedSeatBookingUrl(initialCachedSnapshot?.rawToken))
    }
    var pendingReturnToSeatSelection by remember { mutableStateOf(true) }
    var showClearSessionDialog by remember { mutableStateOf(false) }
    var showBindCredentialDialog by remember { mutableStateOf(false) }
    var showRecordsDialog by remember { mutableStateOf(false) }
    var selectedBookingViewMode by remember { mutableIntStateOf(0) }
    var lastSilentReloginAttemptMs by remember { mutableLongStateOf(0L) }
    val bookingPlans = repository?.libraryBookingPlansFlow?.collectAsState(initial = emptyList())?.value.orEmpty()
    val cloudBinding = repository?.cloudIdentityBindingFlow?.collectAsState(initial = null)?.value

    // 原生座位预约概览与动作控制状态
    var seatOverview by remember { mutableStateOf<LibrarySeatOverviewSnapshot?>(null) }
    var isRefreshingOverview by remember { mutableStateOf(false) }
    var pendingActionRequest by remember { mutableStateOf<PendingSeatActionRequest?>(null) }
    var isExecutingSeatAction by remember { mutableStateOf(false) }
    var actionFeedbackBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var nowEpochMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    var inputStudentId by remember(prefs?.savedStudentId) {
        mutableStateOf(prefs?.savedStudentId.orEmpty())
    }
    var inputPassword by remember { mutableStateOf("") }
    var showPasswordPlain by remember { mutableStateOf(false) }
    var isSavingCredentials by remember { mutableStateOf(false) }

    fun refreshSeatOverviewAsync() {
        val repo = repository ?: return
        scope.launch {
            isRefreshingOverview = true
            repo.fetchLibrarySeatOverview().onSuccess { snapshot ->
                seatOverview = snapshot
            }
            isRefreshingOverview = false
        }
    }

    fun applyAuthenticatedTokenToWebView(rawToken: String, reloadEntryUrl: Boolean) {
        val clean = HautLibrarySeatAuthClient.normalizeRawToken(rawToken)
        if (clean.isBlank()) return
        activeToken = clean
        val wv = webViewRef ?: return
        val syncScript = OfficialWebScripts.buildLibraryTokenSyncScript(
            rawToken = clean,
            navigateToSelectRoom = true
        )
        wv.evaluateJavascript(syncScript, null)
        if (reloadEntryUrl) {
            pendingReturnToSeatSelection = true
            wv.loadUrl(OfficialServiceRoutePolicy.buildAuthenticatedSeatBookingUrl(clean))
        }
    }

    fun triggerSilentSessionEnsure(forceRefresh: Boolean, reasonHint: String) {
        val repo = repository ?: return
        scope.launch {
            sessionState = LibrarySeatSessionState.Refreshing(reasonHint)
            val previousToken = activeToken
            val resultState = repo.ensureLibrarySeatSession(forceRefresh = forceRefresh)
            sessionState = resultState
            when (resultState) {
                is LibrarySeatSessionState.Authenticated -> {
                    val freshToken = resultState.snapshot.rawToken
                    val shouldReload = !initialWebLoadReady ||
                        forceRefresh ||
                        previousToken != freshToken ||
                        OfficialServiceRoutePolicy.isSchoolCasAuthUrl(currentUrl)
                    initialWebLoadReady = true
                    applyAuthenticatedTokenToWebView(
                        rawToken = freshToken,
                        reloadEntryUrl = shouldReload
                    )
                    refreshSeatOverviewAsync()
                }
                else -> {
                    initialWebLoadReady = true
                }
            }
        }
    }

    fun executeConfirmedSeatAction(request: PendingSeatActionRequest) {
        val repo = repository ?: return
        scope.launch {
            isExecutingSeatAction = true
            val result = repo.executeLibrarySeatAction(
                action = request.action,
                bookingId = request.booking?.bookingId
            )
            isExecutingSeatAction = false
            pendingActionRequest = null
            result.fold(
                onSuccess = { outcome ->
                    actionFeedbackBanner = outcome.success to outcome.message
                    refreshSeatOverviewAsync()
                    webViewRef?.reload()
                },
                onFailure = { error ->
                    actionFeedbackBanner = false to (error.message ?: "${request.action.title}失败，请稍后重试")
                }
            )
        }
    }

    // 自动收起顶部操作反馈提示条（成功提示 5 秒，详细规则说明 9 秒）
    LaunchedEffect(actionFeedbackBanner) {
        val current = actionFeedbackBanner ?: return@LaunchedEffect
        delay(if (current.first) 5_000L else 9_000L)
        actionFeedbackBanner = null
    }

    // 1 秒级实时倒计时与后台状态同步（确保选座后 5 分钟免入馆取消倒计时实时跳动，且自动触发 T-30m / T-5m / T 三阶段抢座计划）
    LaunchedEffect(repository, sessionState, bookingPlans) {
        if (repository == null) return@LaunchedEffect
        var tickCount = 0
        while (isActive) {
            delay(1_000L)
            val now = System.currentTimeMillis()
            nowEpochMs = now
            tickCount++
            if (sessionState is LibrarySeatSessionState.Authenticated && tickCount % 10 == 0 && !isRefreshingOverview && !isExecutingSeatAction) {
                refreshSeatOverviewAsync()
            }
            for (plan in bookingPlans) {
                if (plan.status in setOf("CANCELLED", "CONFIRMED", "FAILED")) continue
                when {
                    now >= plan.fireAtEpochMillis && plan.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK") -> {
                        val updated = repository.advanceBookingPlanStage(plan.id, TripleScheduleStage.FIRE).getOrNull()
                        if (updated?.status == "CONFIRMED") {
                            actionFeedbackBanner = true to (updated.lastDiagnosticMessage ?: "自动抢座成功！")
                            refreshSeatOverviewAsync()
                        } else if (updated?.status == "FAILED") {
                            actionFeedbackBanner = false to (updated.lastDiagnosticMessage ?: "自动抢座未命中")
                        }
                    }
                    now >= plan.finalCheckAtEpochMillis && now < plan.fireAtEpochMillis && plan.status in setOf("ARMED", "PRECHECK_OK") -> {
                        repository.advanceBookingPlanStage(plan.id, TripleScheduleStage.FINAL_CHECK)
                    }
                    now >= plan.precheckAtEpochMillis && now < plan.finalCheckAtEpochMillis && plan.status == "ARMED" -> {
                        repository.advanceBookingPlanStage(plan.id, TripleScheduleStage.PRECHECK)
                    }
                }
            }
        }
    }

    // 进入页面时自动登录并拉取当前预约与暂离状态
    LaunchedEffect(repository) {
        if (repository != null) {
            triggerSilentSessionEnsure(
                forceRefresh = false,
                reasonHint = "正在自动登录图书馆选座系统…"
            )
        }
    }

    // 轮询监控网页是否弹出「登录已失效请重新登录」提示，发现即自动重登
    LaunchedEffect(webViewRef, initialWebLoadReady, prefs?.hasSavedCredentials, sessionState) {
        while (isActive) {
            delay(1_400L)
            val wv = webViewRef ?: continue
            if (!initialWebLoadReady || isLoading) continue
            val urlNow = wv.url.orEmpty()
            if (!urlNow.startsWith("https://wslib.haut.edu.cn")) continue
            wv.evaluateJavascript(OfficialWebScripts.LIBRARY_DETECT_AND_DISMISS_EXPIRED_MODAL_SCRIPT) { rawResult ->
                val cleanResult = rawResult?.trim()?.removeSurrounding("\"") ?: return@evaluateJavascript
                if (cleanResult == "EXPIRED_MODAL") {
                    val now = System.currentTimeMillis()
                    if (repository != null &&
                        prefs?.hasSavedCredentials == true &&
                        now - lastSilentReloginAttemptMs > 15_000L
                    ) {
                        lastSilentReloginAttemptMs = now
                        triggerSilentSessionEnsure(
                            forceRefresh = true,
                            reasonHint = "登录已过期，正在自动重新登录…"
                        )
                    } else if (repository != null && prefs?.hasSavedCredentials != true) {
                        sessionState = LibrarySeatSessionState.CredentialsRequired(
                            "请先登录学号，以后打开会自动保持登录"
                        )
                        showBindCredentialDialog = true
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val activePlanCount = bookingPlans.count { it.status !in setOf("CANCELLED", "CONFIRMED", "FAILED") }
        XPeachTopBar(
            title = "图书馆选座",
            subtitle = if (selectedBookingViewMode == 1) {
                "选好自习室和座位，到点自动帮你抢"
            } else {
                "河南工业大学图书馆"
            },
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            },
            actions = {
                if (repository != null) {
                    XPeachFilterPill(
                        label = if (activePlanCount > 0) "定时抢座 ($activePlanCount)" else "定时抢座",
                        selected = selectedBookingViewMode == 1 || activePlanCount > 0,
                        onClick = {
                            selectedBookingViewMode = if (selectedBookingViewMode == 1) 0 else 1
                        }
                    )
                }
            }
        )

        if (repository != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                XPeachSegmentedTabs(
                    items = listOf(
                        "网页选座",
                        if (activePlanCount > 0) "自动抢座 / 快速选座 ($activePlanCount)" else "自动抢座 / 快速选座"
                    ),
                    selectedIndex = selectedBookingViewMode,
                    onSelect = { selectedBookingViewMode = it }
                )
            }
        }

        if (repository != null) {
            AnimatedContent(
                targetState = sessionState,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) },
                label = "seatSessionStatusBanner"
            ) { state ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (state) {
                        is LibrarySeatSessionState.Refreshing -> {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.height(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = state.hint,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        is LibrarySeatSessionState.Authenticated -> {
                            val userLabel = state.snapshot.userInfo.userName.ifBlank {
                                state.snapshot.userInfo.userNo
                            }
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "已登录",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Text(
                                    text = userLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        lastSilentReloginAttemptMs = System.currentTimeMillis()
                                        triggerSilentSessionEnsure(
                                            forceRefresh = true,
                                            reasonHint = "正在刷新登录状态…"
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("刷新", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = { showBindCredentialDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("换账号", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        is LibrarySeatSessionState.CredentialsRequired -> {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { showBindCredentialDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(28.dp)
                            ) {
                                Text(
                                    "登录学号",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        is LibrarySeatSessionState.AuthError -> {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = {
                                        lastSilentReloginAttemptMs = System.currentTimeMillis()
                                        triggerSilentSessionEnsure(
                                            forceRefresh = true,
                                            reasonHint = "正在重新登录…"
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("重试", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = { showBindCredentialDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("改密码", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        LibrarySeatSessionState.Idle -> Unit
                    }
                }
            }
        }

        // 操作反馈提示条（如：取消预约成功 / 短暂离成功 / 暂离返回成功 / 签退成功）
        AnimatedVisibility(
            visible = actionFeedbackBanner != null,
            enter = expandVertically(tween(200)) + fadeIn(tween(180)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
        ) {
            actionFeedbackBanner?.let { (ok, msg) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (ok) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.errorContainer
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ok) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { actionFeedbackBanner = null },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("知道了", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 原生「我的座位控制台」（直接提供签到、取消预约、短暂离、长暂离、暂离返回、续座、签退与历史记录控件）
        if (repository != null && sessionState is LibrarySeatSessionState.Authenticated) {
            val activeBooking = seatOverview?.currentBooking?.takeIf { it.isActive }
            val shortMin = seatOverview?.shortLeaveMinutes ?: 30
            val longMin = seatOverview?.longLeaveMinutes ?: 120
            val historyCount = (seatOverview?.inProgressBookings?.size ?: 0) + (seatOverview?.recentHistory?.size ?: 0)

            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                if (activeBooking != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val badgeColor = when (activeBooking.status) {
                                    LibrarySeatBookingStatus.FULFILLED -> MaterialTheme.colorScheme.tertiaryContainer
                                    LibrarySeatBookingStatus.LEAVING -> MaterialTheme.colorScheme.errorContainer
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                }
                                val badgeTextColor = when (activeBooking.status) {
                                    LibrarySeatBookingStatus.FULFILLED -> MaterialTheme.colorScheme.onTertiaryContainer
                                    LibrarySeatBookingStatus.LEAVING -> MaterialTheme.colorScheme.onErrorContainer
                                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                                }
                                Box(
                                    modifier = Modifier
                                        .background(badgeColor, RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = activeBooking.status.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = badgeTextColor
                                    )
                                }
                                Text(
                                    text = activeBooking.displaySeatTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = { showRecordsDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("预约记录", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        Text(
                            text = "预约时段：${activeBooking.timeWindowText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (activeBooking.status == LibrarySeatBookingStatus.FULFILLED) {
                            val remainSec = activeBooking.remainingSecondsBeforeStart(nowEpochMs)
                            if (remainSec != null && remainSec > 0L) {
                                val mm = remainSec / 60L
                                val ss = remainSec % 60L
                                Text(
                                    text = "⏱ 免入馆取消窗口剩余 %d分%02d秒（系统默认选座 5 分钟后正式生效，开始后未刷脸入馆不可直接取消）".format(mm, ss),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            } else if (activeBooking.hasStarted(nowEpochMs)) {
                                val nextSync = LibrarySeatBookingRecord.computeNextGateSyncTimeText(nowEpochMs)
                                Text(
                                    text = "⚠️ 预约已正式开始：若您已刷脸入馆，系统每整 5 分钟（预计 $nextSync）同步签到后即可「签退释放」；未入馆需先过闸机刷脸自动签到。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        // 原生快捷动作控件行：取消预约 / 签到 / 短暂离 / 长暂离 / 暂离返回 / 续座 / 签退释放
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (activeBooking.canCancel) {
                                val remainSec = activeBooking.remainingSecondsBeforeStart(nowEpochMs)
                                val cancelLabel = when {
                                    remainSec != null && remainSec > 0L ->
                                        "取消预约 (%d:%02d)".format(remainSec / 60L, remainSec % 60L)
                                    activeBooking.hasStarted(nowEpochMs) ->
                                        "取消 / 自动退座"
                                    else -> "取消预约"
                                }
                                OutlinedButton(
                                    onClick = {
                                        pendingActionRequest = PendingSeatActionRequest(
                                            action = LibrarySeatActionType.CANCEL_BOOKING,
                                            booking = activeBooking
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(
                                        cancelLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }

                            if (activeBooking.canSignIn) {
                                FilledTonalButton(
                                    onClick = {
                                        pendingActionRequest = PendingSeatActionRequest(
                                            action = LibrarySeatActionType.SIGN_IN,
                                            booking = activeBooking
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("立即签到", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            if (activeBooking.canReturnFromLeave) {
                                FilledTonalButton(
                                    onClick = {
                                        pendingActionRequest = PendingSeatActionRequest(
                                            action = LibrarySeatActionType.LEAVE_BACK,
                                            booking = activeBooking
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("暂离返回", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            if (activeBooking.canLeave) {
                                FilledTonalButton(
                                    onClick = {
                                        pendingActionRequest = PendingSeatActionRequest(
                                            action = LibrarySeatActionType.SHORT_LEAVE,
                                            booking = activeBooking
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("短暂离 (${shortMin}分)", style = MaterialTheme.typography.labelMedium)
                                }

                                OutlinedButton(
                                    onClick = {
                                        pendingActionRequest = PendingSeatActionRequest(
                                            action = LibrarySeatActionType.LONG_LEAVE,
                                            booking = activeBooking
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("长暂离 (${longMin}分)", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            if (activeBooking.canRenew) {
                                OutlinedButton(
                                    onClick = {
                                        pendingActionRequest = PendingSeatActionRequest(
                                            action = LibrarySeatActionType.RENEW_SEAT,
                                            booking = activeBooking
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("续约座位", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            if (activeBooking.canSignOut) {
                                OutlinedButton(
                                    onClick = {
                                        pendingActionRequest = PendingSeatActionRequest(
                                            action = LibrarySeatActionType.SIGN_OUT,
                                            booking = activeBooking
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(
                                        "签退释放",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isRefreshingOverview) "正在同步当前预约状态…" else "当前无进行中座位 · 预约后可在此一键取消/暂离/签退",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    pendingActionRequest = PendingSeatActionRequest(
                                        action = LibrarySeatActionType.QUICK_SIGN_IN,
                                        booking = null
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("快速签到", style = MaterialTheme.typography.labelSmall)
                            }
                            TextButton(
                                onClick = {
                                    refreshSeatOverviewAsync()
                                    showRecordsDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text(
                                    if (historyCount > 0) "预约记录 ($historyCount)" else "预约记录",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (selectedBookingViewMode == 1 && repository != null) {
            AutomatedLibrarySeatBookingWorkbench(
                repository = repository,
                sessionState = sessionState,
                seatOverview = seatOverview,
                bookingPlans = bookingPlans,
                hasBoundCloudAccount = cloudBinding != null,
                onRequireLogin = { showBindCredentialDialog = true },
                onRefreshSeatOverview = { refreshSeatOverviewAsync() },
                onFeedback = { ok, msg -> actionFeedbackBanner = ok to msg },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            if (isLoading || !initialWebLoadReady) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (initialWebLoadReady) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            SchoolWebSessionManager.configureHardenedSchoolWebView(this, supportZoom = true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val targetUrl = request?.url?.toString().orEmpty()
                                    val tokenInTarget = HautLibrarySeatAuthClient.extractTokenFromUrl(targetUrl)
                                    if (!tokenInTarget.isNullOrBlank()) {
                                        activeToken = tokenInTarget
                                        scope.launch {
                                            repository?.captureLibrarySeatWebToken(tokenInTarget)?.let { snap ->
                                                sessionState = LibrarySeatSessionState.Authenticated(snap)
                                                refreshSeatOverviewAsync()
                                            }
                                        }
                                    }
                                    if (OfficialServiceRoutePolicy.isSchoolCasAuthUrl(targetUrl)) {
                                        pendingReturnToSeatSelection = true
                                        val now = System.currentTimeMillis()
                                        val manualCaptchaRequired =
                                            (sessionState as? LibrarySeatSessionState.AuthError)?.requiresManualVerification == true
                                        if (repository != null &&
                                            prefs?.hasSavedCredentials == true &&
                                            !manualCaptchaRequired &&
                                            now - lastSilentReloginAttemptMs > 15_000L
                                        ) {
                                            lastSilentReloginAttemptMs = now
                                            triggerSilentSessionEnsure(
                                                forceRefresh = true,
                                                reasonHint = "Cookie 已过期，正在后台自动重登…"
                                            )
                                            return true
                                        }
                                    }
                                    return SafeWebNavigationPolicy.shouldBlockNavigation(request, view)
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    return SafeWebNavigationPolicy.interceptSubresourceIfBlocked(
                                        request = request,
                                        allowCatalogCovers = false
                                    )
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    SafeWebNavigationPolicy.cancelOnSslError(handler, error)
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                    }
                                    val tokenInUrl = HautLibrarySeatAuthClient.extractTokenFromUrl(url)
                                    if (!tokenInUrl.isNullOrBlank()) {
                                        activeToken = tokenInUrl
                                        scope.launch {
                                            repository?.captureLibrarySeatWebToken(tokenInUrl)?.let { snap ->
                                                sessionState = LibrarySeatSessionState.Authenticated(snap)
                                                refreshSeatOverviewAsync()
                                            }
                                        }
                                    }
                                    if (OfficialServiceRoutePolicy.isSchoolCasAuthUrl(url)) {
                                        pendingReturnToSeatSelection = true
                                        val now = System.currentTimeMillis()
                                        val manualCaptchaRequired =
                                            (sessionState as? LibrarySeatSessionState.AuthError)?.requiresManualVerification == true
                                        if (repository != null &&
                                            prefs?.hasSavedCredentials == true &&
                                            !manualCaptchaRequired &&
                                            now - lastSilentReloginAttemptMs > 15_000L
                                        ) {
                                            lastSilentReloginAttemptMs = now
                                            view?.stopLoading()
                                            triggerSilentSessionEnsure(
                                                forceRefresh = true,
                                                reasonHint = "Cookie 已过期，正在后台自动重登…"
                                            )
                                            return
                                        }
                                    }
                                    // 在 wslib 页面脚本执行前预先写入持久化 Token 到 localStorage，防止首屏读取到旧失效 Token
                                    if (url?.startsWith("https://wslib.haut.edu.cn") == true && !activeToken.isNullOrBlank()) {
                                        view?.evaluateJavascript(
                                            OfficialWebScripts.buildLibraryTokenSyncScript(
                                                rawToken = activeToken,
                                                navigateToSelectRoom = false
                                            ),
                                            null
                                        )
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                    }
                                    val shouldJumpToSelectRoom = pendingReturnToSeatSelection &&
                                        OfficialServiceRoutePolicy.isLibrarySeatIndexDocument(url)
                                    if (shouldJumpToSelectRoom) {
                                        pendingReturnToSeatSelection = false
                                    }
                                    if (url?.startsWith("https://wslib.haut.edu.cn") == true) {
                                        view?.evaluateJavascript(
                                            OfficialWebScripts.buildLibraryTokenSyncScript(
                                                rawToken = activeToken,
                                                navigateToSelectRoom = shouldJumpToSelectRoom
                                            ),
                                            null
                                        )
                                        // 用户在网页内完成预约或切换页面后，自动同步顶部原生座位控制台状态
                                        refreshSeatOverviewAsync()
                                    }
                                    SchoolWebSessionManager.persistCookiesAsync()
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView?,
                                    url: String?,
                                    isReload: Boolean
                                ) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                    }
                                    // uni-app 选座 H5 采用 #/seatPage/... 单页 Hash 路由切换，不会触发 onPageFinished；
                                    // 在路由变更（如选座成功跳转 AddBeatStatus 或进入 SeatBooking）时立即同步原生座位控制台与倒计时。
                                    if (url?.startsWith("https://wslib.haut.edu.cn") == true) {
                                        view?.evaluateJavascript(
                                            OfficialWebScripts.buildLibraryTokenSyncScript(
                                                rawToken = activeToken,
                                                navigateToSelectRoom = false
                                            ),
                                            null
                                        )
                                        refreshSeatOverviewAsync()
                                    }
                                }
                            }
                            val initialEntryUrl = OfficialServiceRoutePolicy.buildAuthenticatedSeatBookingUrl(activeToken)
                            loadUrl(initialEntryUrl)
                            webViewRef = this
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在自动登录图书馆选座系统…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 底部辅助导航栏：后退、刷新、选座列表、我的预约、重登
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    val wv = webViewRef
                    if (wv?.canGoBack() == true) {
                        wv.goBack()
                    } else {
                        onBack()
                    }
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(0.9f)
            ) {
                Text("后退", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = {
                    refreshSeatOverviewAsync()
                    if (repository != null && prefs?.hasSavedCredentials == true) {
                        lastSilentReloginAttemptMs = System.currentTimeMillis()
                        triggerSilentSessionEnsure(
                            forceRefresh = false,
                            reasonHint = "正在刷新登录状态…"
                        )
                    }
                    webViewRef?.reload()
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(0.9f)
            ) {
                Text("刷新", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }

            FilledTonalButton(
                onClick = {
                    val wv = webViewRef ?: return@FilledTonalButton
                    if (OfficialServiceRoutePolicy.isLibrarySeatIndexDocument(wv.url)) {
                        wv.evaluateJavascript(
                            OfficialWebScripts.buildLibraryTokenSyncScript(
                                rawToken = activeToken,
                                navigateToSelectRoom = true
                            ),
                            null
                        )
                        wv.evaluateJavascript(
                            OfficialWebScripts.LIBRARY_FORCE_CONTINUE_SEATS_SCRIPT,
                            null
                        )
                    } else {
                        pendingReturnToSeatSelection = true
                        wv.loadUrl(OfficialServiceRoutePolicy.buildAuthenticatedSeatBookingUrl(activeToken))
                    }
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(1.15f)
            ) {
                Text("选座列表", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }

            FilledTonalButton(
                onClick = {
                    refreshSeatOverviewAsync()
                    val wv = webViewRef
                    if (wv != null && OfficialServiceRoutePolicy.isLibrarySeatIndexDocument(wv.url)) {
                        wv.evaluateJavascript(
                            OfficialWebScripts.buildLibraryTokenSyncScript(
                                rawToken = activeToken,
                                navigateToSelectRoom = false
                            ),
                            null
                        )
                        wv.evaluateJavascript(
                            OfficialWebScripts.LIBRARY_OPEN_MY_BOOKINGS_SCRIPT,
                            null
                        )
                    } else {
                        pendingReturnToSeatSelection = false
                        wv?.loadUrl(OfficialServiceRoutePolicy.buildAuthenticatedMySeatManageUrl(activeToken))
                    }
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(1.15f)
            ) {
                Text("我的预约", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }

            OutlinedButton(
                onClick = { showClearSessionDialog = true },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.weight(0.95f)
            ) {
                Text("重登", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    // 原生座位动作二次确认弹窗（取消预约 / 签到 / 短暂离 / 长暂离 / 暂离返回 / 续座 / 签退释放）
    pendingActionRequest?.let { req ->
        val customDetail = when {
            req.action == LibrarySeatActionType.CANCEL_BOOKING && req.booking?.hasStarted(nowEpochMs) == true -> {
                val nextSync = LibrarySeatBookingRecord.computeNextGateSyncTimeText(nowEpochMs)
                "当前已到达预约开始时间（选座满 5 分钟），系统将自动尝试「直接取消 → 自动转签退释放」链路：\n" +
                    "• 若您已刷脸入馆且满整 5 分钟同步（预计 $nextSync），将自动为您完成签退退座；\n" +
                    "• 若您尚未刷脸入馆，学校系统需先过馆内闸机刷脸自动签到后方可签退释放。"
            }
            req.action == LibrarySeatActionType.SHORT_LEAVE -> {
                val min = seatOverview?.shortLeaveMinutes ?: 30
                "座位将为您保留 $min 分钟，请在规定时间内返回并点击「暂离返回」，否则将自动释放座位并记违约。确定办理短暂离开吗？"
            }
            req.action == LibrarySeatActionType.LONG_LEAVE -> {
                val min = seatOverview?.longLeaveMinutes ?: 120
                "座位将为您保留 $min 分钟，请在规定时间内返回并点击「暂离返回」，否则将自动释放座位并记违约。确定办理长时间暂离吗？"
            }
            else -> req.action.confirmMessage
        }
        AlertDialog(
            onDismissRequest = { if (!isExecutingSeatAction) pendingActionRequest = null },
            title = { Text(req.action.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    req.booking?.let { b ->
                        Text(
                            text = "${b.displaySeatTitle}（${b.timeWindowText}）",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = customDetail,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    enabled = !isExecutingSeatAction,
                    onClick = { executeConfirmedSeatAction(req) }
                ) {
                    Text(if (isExecutingSeatAction) "正在处理…" else "确认${req.action.title}")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isExecutingSeatAction,
                    onClick = { pendingActionRequest = null }
                ) {
                    Text("再想想")
                }
            }
        )
    }

    // 原生「我的预约与历史记录」管理弹窗（支持查看进行中预约、一键取消/暂离/签退，或从历史记录快速跳到对应区域选座）
    if (showRecordsDialog) {
        val activeItems = remember(seatOverview) {
            val cur = seatOverview?.currentBooking
            val inProg = seatOverview?.inProgressBookings.orEmpty()
            (listOfNotNull(cur) + inProg).distinctBy { it.bookingId }.filter { it.isActive }
        }
        val historyItems = remember(seatOverview) {
            seatOverview?.recentHistory.orEmpty()
        }
        AlertDialog(
            onDismissRequest = { showRecordsDialog = false },
            title = { Text("我的座位预约与管理") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (activeItems.isEmpty() && historyItems.isEmpty()) {
                        Text(
                            text = if (isRefreshingOverview) "正在同步预约记录…" else "暂无预约记录。您可以在下方网页中选择阅览室与座位发起预约。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (activeItems.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "当前有效预约",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                items(activeItems, key = { "active_${it.bookingId}" }) { item ->
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                                                RoundedCornerShape(10.dp)
                                            )
                                            .border(0.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                                            .padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = item.displaySeatTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = item.status.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                        Text(
                                            text = item.timeWindowText,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            if (item.canSignIn) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        showRecordsDialog = false
                                                        pendingActionRequest = PendingSeatActionRequest(
                                                            LibrarySeatActionType.SIGN_IN,
                                                            item
                                                        )
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Text("签到", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            if (item.canCancel) {
                                                OutlinedButton(
                                                    onClick = {
                                                        showRecordsDialog = false
                                                        pendingActionRequest = PendingSeatActionRequest(
                                                            LibrarySeatActionType.CANCEL_BOOKING,
                                                            item
                                                        )
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Text(
                                                        "取消预约",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                            if (item.canLeave) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        showRecordsDialog = false
                                                        pendingActionRequest = PendingSeatActionRequest(
                                                            LibrarySeatActionType.SHORT_LEAVE,
                                                            item
                                                        )
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Text("短暂离", style = MaterialTheme.typography.labelSmall)
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        showRecordsDialog = false
                                                        pendingActionRequest = PendingSeatActionRequest(
                                                            LibrarySeatActionType.LONG_LEAVE,
                                                            item
                                                        )
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Text("长暂离", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            if (item.canReturnFromLeave) {
                                                FilledTonalButton(
                                                    onClick = {
                                                        showRecordsDialog = false
                                                        pendingActionRequest = PendingSeatActionRequest(
                                                            LibrarySeatActionType.LEAVE_BACK,
                                                            item
                                                        )
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Text("暂离返回", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            if (item.canSignOut) {
                                                OutlinedButton(
                                                    onClick = {
                                                        showRecordsDialog = false
                                                        pendingActionRequest = PendingSeatActionRequest(
                                                            LibrarySeatActionType.SIGN_OUT,
                                                            item
                                                        )
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(30.dp)
                                                ) {
                                                    Text(
                                                        "签退释放",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (historyItems.isNotEmpty()) {
                                item {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                    Text(
                                        text = "最近预约记录（点击可再次预约同区域）",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                items(historyItems, key = { "his_${it.bookingId}" }) { item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                RoundedCornerShape(8.dp)
                                            )
                                            .clickable {
                                                val regionId = item.buildingLayerRegionId
                                                showRecordsDialog = false
                                                if (regionId != null && regionId > 0L) {
                                                    webViewRef?.evaluateJavascript(
                                                        "location.hash = '#/seatPage/SelectSeat/SelectSeat?id=$regionId';",
                                                        null
                                                    )
                                                }
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = item.displaySeatTitle,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                text = item.timeWindowText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = item.status.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRecordsDialog = false
                        val target = currentUrl.takeIf {
                            OfficialServiceRoutePolicy.isAllowedMainFrameUrl(it)
                        } ?: OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }
                    }
                ) {
                    Text("系统浏览器打开")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecordsDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showBindCredentialDialog && repository != null) {
        AlertDialog(
            onDismissRequest = { if (!isSavingCredentials) showBindCredentialDialog = false },
            title = { Text("登录图书馆选座账号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "请输入你的学号和统一身份认证密码（与智慧工大、教务系统密码相同）。保存后会自动帮你保持登录状态，抢座时不用再重新输密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = inputStudentId,
                        onValueChange = { inputStudentId = it.trim() },
                        label = { Text("学号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = inputPassword,
                        onValueChange = { inputPassword = it },
                        label = { Text("统一身份认证密码") },
                        singleLine = true,
                        visualTransformation = if (showPasswordPlain) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { showPasswordPlain = !showPasswordPlain }) {
                                Text(
                                    if (showPasswordPlain) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                FilledTonalButton(
                    enabled = !isSavingCredentials && inputStudentId.isNotBlank() && inputPassword.isNotBlank(),
                    onClick = {
                        focusManager.clearFocus()
                        isSavingCredentials = true
                        scope.launch {
                            sessionState = LibrarySeatSessionState.Refreshing("正在登录图书馆选座系统…")
                            val newState = repository.saveLibrarySeatCredentialsAndLogin(
                                studentId = inputStudentId,
                                passwordPlain = inputPassword
                            )
                            isSavingCredentials = false
                            sessionState = newState
                            if (newState is LibrarySeatSessionState.Authenticated) {
                                inputPassword = ""
                                showBindCredentialDialog = false
                                initialWebLoadReady = true
                                applyAuthenticatedTokenToWebView(
                                    rawToken = newState.snapshot.rawToken,
                                    reloadEntryUrl = true
                                )
                                refreshSeatOverviewAsync()
                            }
                        }
                    }
                ) {
                    Text(if (isSavingCredentials) "正在登录…" else "保存并登录")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSavingCredentials,
                    onClick = { showBindCredentialDialog = false }
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showClearSessionDialog) {
        AlertDialog(
            onDismissRequest = { showClearSessionDialog = false },
            title = { Text("重新同步登录状态") },
            text = {
                Text("如果网页显示异常或无法选座，点击确定即可清理网页缓存并自动重新登录。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearSessionDialog = false
                        scope.launch {
                            activeToken = null
                            seatOverview = null
                            repository?.clearLibrarySeatSession()
                            SchoolWebSessionManager.clearAllSchoolWebSessions(webViewRef)
                            pendingReturnToSeatSelection = true
                            if (repository != null && prefs?.hasSavedCredentials == true) {
                                lastSilentReloginAttemptMs = System.currentTimeMillis()
                                triggerSilentSessionEnsure(
                                    forceRefresh = true,
                                    reasonHint = "正在重新登录图书馆选座系统…"
                                )
                            } else {
                                webViewRef?.loadUrl(OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL)
                            }
                        }
                    }
                ) {
                    Text("重新登录")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomatedLibrarySeatBookingWorkbench(
    repository: CampusRepository,
    sessionState: LibrarySeatSessionState,
    seatOverview: LibrarySeatOverviewSnapshot?,
    bookingPlans: List<LibraryBookingPlanEntity>,
    hasBoundCloudAccount: Boolean,
    onRequireLogin: () -> Unit,
    onRefreshSeatOverview: () -> Unit,
    onFeedback: (Boolean, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val recentRecord = remember(seatOverview) {
        seatOverview?.currentBooking ?: seatOverview?.recentHistory?.firstOrNull()
    }
    var selectedMode by remember { mutableStateOf(BookingExecutionMode.LOCAL_DEVICE) }
    var useTomorrow by remember { mutableStateOf(true) }
    var selectedHour by remember { mutableIntStateOf(7) }
    var selectedMinute by remember { mutableIntStateOf(0) }
    var showMoreTimeSlots by remember { mutableStateOf(false) }

    val targetTimeText = remember(selectedHour, selectedMinute) {
        "%02d:%02d".format(selectedHour, selectedMinute)
    }
    val todayDate = remember { LocalDate.now(ZoneId.of("Asia/Shanghai")) }
    val tomorrowDate = remember(todayDate) { todayDate.plusDays(1) }
    val targetDate = if (useTomorrow) tomorrowDate else todayDate

    // 自动读取自习区域列表与下属座位号列表（全程点选，零手输）
    var regions by remember { mutableStateOf<List<LibraryStudyRegionItem>>(emptyList()) }
    var isLoadingRegions by remember { mutableStateOf(false) }
    var regionsLoadError by remember { mutableStateOf<String?>(null) }
    var selectedFloorFilter by remember { mutableStateOf("全部楼层") }
    var selectedRegion by remember { mutableStateOf<LibraryStudyRegionItem?>(null) }

    var seatSnapshot by remember { mutableStateOf<LibraryRegionSeatsSnapshot?>(null) }
    var isLoadingSeats by remember { mutableStateOf(false) }
    var seatsLoadError by remember { mutableStateOf<String?>(null) }
    var onlyAvailableSeats by remember { mutableStateOf(false) }
    var selectedSeatPage by remember { mutableIntStateOf(0) }
    var selectedSeatNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var isRunningImmediateBooking by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionSnapshot by remember { mutableStateOf(LibrarySeatAlarmSubsystem.inspectPermissions(context)) }
    var showPermissionActivationDialog by remember { mutableStateOf(false) }

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
                    runCatching { repository.rescheduleActiveLocalBookingPlans() }
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

    fun reloadStudyRegions() {
        scope.launch {
            isLoadingRegions = true
            regionsLoadError = null
            repository.fetchLibraryStudyRegions(targetDate.toString())
                .onSuccess { list ->
                    regions = list
                    if (list.isNotEmpty()) {
                        val currentId = selectedRegion?.id
                        val matchedByCurrent = list.firstOrNull { it.id == currentId }
                        val matchedByRecentId = recentRecord?.buildingLayerRegionId?.let { rid ->
                            list.firstOrNull { it.id == rid }
                        }
                        val matchedByRecentName = recentRecord?.regionName?.takeIf { it.isNotBlank() }?.let { rName ->
                            list.firstOrNull { it.regionName.contains(rName) || rName.contains(it.regionName) }
                        }
                        val nextRegion = matchedByCurrent ?: matchedByRecentId ?: matchedByRecentName ?: list.first()
                        if (selectedRegion?.id != nextRegion.id) {
                            selectedRegion = nextRegion
                        }
                    }
                }
                .onFailure { err ->
                    regionsLoadError = err.message ?: "暂时没能读到自习室列表，请先登录学号后重试"
                }
            isLoadingRegions = false
        }
    }

    fun reloadSeatsForRegion(region: LibraryStudyRegionItem) {
        scope.launch {
            isLoadingSeats = true
            seatsLoadError = null
            repository.fetchLibraryRegionSeats(region.id, targetDate.toString())
                .onSuccess { snap ->
                    seatSnapshot = snap
                    selectedSeatPage = 0
                    val validNames = snap.seats.map { it.seatName }.toSet()
                    val retained = selectedSeatNames.filter { it in validNames }
                    if (retained.isNotEmpty()) {
                        selectedSeatNames = retained
                    } else {
                        val recentSeat = recentRecord?.seatName?.takeIf {
                            recentRecord.regionName.contains(region.regionName) && it in validNames
                        }
                        val defaultPicks = if (recentSeat != null) {
                            listOf(recentSeat)
                        } else {
                            snap.seats.filter { it.isAvailable }.take(2).map { it.seatName }
                        }
                        selectedSeatNames = defaultPicks
                    }
                }
                .onFailure { err ->
                    seatsLoadError = err.message ?: "读取该自习室座位失败，请点击重试"
                }
            isLoadingSeats = false
        }
    }

    LaunchedEffect(repository, targetDate, sessionState is LibrarySeatSessionState.Authenticated) {
        reloadStudyRegions()
    }

    LaunchedEffect(selectedRegion?.id, targetDate) {
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
    val filteredSeats = remember(allSeats, onlyAvailableSeats) {
        if (onlyAvailableSeats) allSeats.filter { it.isAvailable } else allSeats
    }
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

    val roomLabel = selectedRegion?.regionName ?: recentRecord?.regionName.orEmpty()
    val preferredSeatsText = selectedSeatNames.joinToString(", ")

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (sessionState !is LibrarySeatSessionState.Authenticated) {
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "请先登录你的学号",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "登录后即可自动读取全馆自习室和座位表，直接点选座位",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onRequireLogin,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("登录学号")
                    }
                }
            }
        }

        // 第一步：选择日期与时间（全点选，零手输）
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "第一步：选择哪天去、几点自动抢",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                XPeachFilterPill(
                    label = "明天去自习 ($tomorrowDate)",
                    selected = useTomorrow,
                    onClick = { useTomorrow = true }
                )
                XPeachFilterPill(
                    label = "今天去自习 ($todayDate)",
                    selected = !useTomorrow,
                    onClick = { useTomorrow = false }
                )
            }

            Text(
                text = "点选自动抢座时间（当前已选：$targetTimeText）",
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
                    Triple(7, 0, "明早 07:00 开馆抢"),
                    Triple(7, 30, "07:30"),
                    Triple(8, 0, "08:00"),
                    Triple(8, 30, "08:30"),
                    Triple(9, 0, "09:00"),
                    Triple(12, 30, "12:30"),
                    Triple(14, 0, "14:00"),
                    Triple(20, 59, "晚上 20:59")
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
                    label = if (showMoreTimeSlots) "收起更多时间 ▲" else "自选其他几点几分 ▼",
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
                        text = "点选小时（07:00 - 21:30）：",
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
                                    if (h == 21 && selectedMinute > 30) {
                                        selectedMinute = 30
                                    }
                                }
                            )
                        }
                    }
                    Text(
                        text = "点选分钟：",
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
                            listOf(0, 5, 10, 15, 20, 25, 30)
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
        }

        // 第二步：自动读取全馆自习室列表，点击即选
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "第二步：点选你想去的自习室",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    onClick = { reloadStudyRegions() },
                    enabled = !isLoadingRegions,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(if (isLoadingRegions) "刷新中…" else "🔄 刷新列表", style = MaterialTheme.typography.labelSmall)
                }
            }

            if (recentRecord != null && recentRecord.regionName.isNotBlank()) {
                FilledTonalButton(
                    onClick = {
                        val match = regions.firstOrNull {
                            it.id == recentRecord.buildingLayerRegionId ||
                                it.regionName.contains(recentRecord.regionName) ||
                                recentRecord.regionName.contains(it.regionName)
                        }
                        if (match != null) {
                            selectedRegion = match
                        }
                        if (recentRecord.seatName.isNotBlank()) {
                            selectedSeatNames = listOf(recentRecord.seatName)
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Text(
                        text = "⭐ 一键用上次坐过的：${recentRecord.regionName} · ${recentRecord.seatName}号座",
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
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "正在自动读取图书馆全部自习室…",
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
                        text = regionsLoadError.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { reloadStudyRegions() }) {
                            Text("重新读取自习室")
                        }
                        OutlinedButton(onClick = onRequireLogin) {
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
                        val isSelected = selectedRegion?.id == region.id
                        val borderColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                        val bgColor = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bgColor, RoundedCornerShape(10.dp))
                                .border(if (isSelected) 1.5.dp else 0.5.dp, borderColor, RoundedCornerShape(10.dp))
                                .clickable {
                                    if (selectedRegion?.id != region.id) {
                                        selectedSeatNames = emptyList()
                                        selectedRegion = region
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isSelected) "✅ ${region.regionName}" else region.regionName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${region.layerName} · 开放时间 ${region.openTime}-${region.closeTime}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            XPeachStatusBadge(
                                text = "空余 ${region.availableSeats}/${region.totalSeats} 座",
                                containerColor = if (region.availableSeats > 0) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.errorContainer
                                },
                                contentColor = if (region.availableSeats > 0) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onErrorContainer
                                }
                            )
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
                        text = "第三步：点选座位号（可多选 3 个防撞车）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedRegion != null) {
                            "当前自习室：${selectedRegion?.regionName}（第1个为首选，被抢时自动试第2、第3个）"
                        } else {
                            "请先在上方点击选择一个自习室"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedRegion != null) {
                    TextButton(
                        onClick = { selectedRegion?.let { reloadSeatsForRegion(it) } },
                        enabled = !isLoadingSeats,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text(if (isLoadingSeats) "读取中…" else "🔄 刷新座位", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // 已选座位展示条
            XPeachWell(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                if (selectedSeatNames.isEmpty()) {
                    Text(
                        text = "👆 请在下方列表中直接点击你想坐的座位号（最多点选 3 个）",
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
                            text = "已选座位（点击可取消）：",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        TextButton(
                            onClick = { selectedSeatNames = emptyList() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier = Modifier.height(22.dp)
                        ) {
                            Text("清空重选", style = MaterialTheme.typography.labelSmall)
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
                                onClick = {
                                    selectedSeatNames = selectedSeatNames.filterNot { it == seatNo }
                                }
                            )
                        }
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
                    CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = "正在读取该自习室的全部座位号…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (seatsLoadError != null && allSeats.isEmpty()) {
                Text(
                    text = seatsLoadError.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
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
                        label = if (onlyAvailableSeats) "✓ 只看当前空座" else "只看当前空座",
                        selected = onlyAvailableSeats,
                        onClick = {
                            onlyAvailableSeats = !onlyAvailableSeats
                            selectedSeatPage = 0
                        }
                    )
                    XPeachFilterPill(
                        label = "🎲 随机帮我选 3 个空座",
                        selected = false,
                        onClick = {
                            val randomThree = allSeats
                                .filter { it.isAvailable }
                                .shuffled()
                                .take(3)
                                .map { it.seatName }
                            if (randomThree.isNotEmpty()) {
                                selectedSeatNames = randomThree
                            } else {
                                selectedSeatNames = allSeats.shuffled().take(3).map { it.seatName }
                            }
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
                            seat.isAvailable -> "${seat.seatName} 空"
                            else -> "${seat.seatName} 占"
                        }
                        XPeachFilterPill(
                            label = seatLabel,
                            selected = isPicked,
                            onClick = {
                                selectedSeatNames = if (isPicked) {
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

        // 第四步：选择抢座方式并一键开启
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "第四步：选择抢座方式并开始",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BookingExecutionMode.entries.forEach { mode ->
                    XPeachFilterPill(
                        label = mode.label,
                        selected = selectedMode == mode,
                        onClick = { selectedMode = mode }
                    )
                }
            }
            Text(
                text = if (selectedMode == BookingExecutionMode.CLOUD_HOSTED && !hasBoundCloudAccount) {
                    "⚠️ 使用云端关机代抢需要先在「设置 → 云账号与扩展功能」登录你的账号。"
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

            if (selectedMode != BookingExecutionMode.CLOUD_HOSTED) {
                XPeachWell(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (permissionSnapshot.allReady) {
                                "🛡️ 本机准点抢座权限已全部激活"
                            } else {
                                "⚡ 本机抢座权限（待激活 ${permissionSnapshot.missingCount} 项）"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (permissionSnapshot.allReady) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                        TextButton(
                            onClick = {
                                val ok = LibrarySeatAlarmSubsystem.triggerTestWakeupNotification(context)
                                permissionSnapshot = LibrarySeatAlarmSubsystem.inspectPermissions(context)
                                if (ok) {
                                    onFeedback(true, "已发送测试唤醒通知，请查看顶部通知栏")
                                } else {
                                    requestNotificationPermissionOrSettings()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text("🔔 测试本地唤醒", style = MaterialTheme.typography.labelSmall)
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
                            label = if (permissionSnapshot.exactAlarmAllowed) "✓ 精准定时闹钟" else "开启精准定时闹钟 →",
                            selected = permissionSnapshot.exactAlarmAllowed,
                            onClick = { requestExactAlarmPermission() }
                        )
                        XPeachFilterPill(
                            label = if (permissionSnapshot.ignoringBatteryOptimizations) "✓ 后台省电白名单" else "加入后台白名单 →",
                            selected = permissionSnapshot.ignoringBatteryOptimizations,
                            onClick = { requestIgnoreBatteryOptimizations() }
                        )
                        XPeachFilterPill(
                            label = if (permissionSnapshot.notificationGranted) "✓ 抢座结果通知" else "开启抢座通知 →",
                            selected = permissionSnapshot.notificationGranted,
                            onClick = { requestNotificationPermissionOrSettings() }
                        )
                    }
                }
            }

            computedScheduleResult.getOrNull()?.let { calc ->
                XPeachWell(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(
                        text = "📋 已选汇总：$targetDate $targetTimeText · ${roomLabel.ifBlank { "未选自习室" }} · ${
                            if (selectedSeatNames.isEmpty()) "未选座位" else "${selectedSeatNames.joinToString("、")}号座"
                        }",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "系统会在 ${DualEngineReservationPlanner.formatEpochToShanghaiHms(calc.finalCheckAtEpochMillis)} 提前帮你自动检查登录状态，并在 ${DualEngineReservationPlanner.formatLocalTimeHms(calc.targetLocalTime)} 准时抢座。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            fun saveBookingPlanNow() {
                scope.launch {
                    val result = repository.createDualEngineBookingPlan(
                        executionMode = selectedMode,
                        targetDate = targetDate,
                        targetTimeText = targetTimeText,
                        preferredAreaName = roomLabel,
                        preferredSeatLabel = preferredSeatsText
                    )
                    result.fold(
                        onSuccess = { created ->
                            onFeedback(
                                true,
                                "已设置好定时抢座：${created.targetLocalDate} ${created.targetLocalTime} · ${created.preferredAreaName}"
                            )
                        },
                        onFailure = { err ->
                            onFeedback(false, err.message ?: "设置失败，请重试")
                        }
                    )
                }
            }

            if (showPermissionActivationDialog) {
                AlertDialog(
                    onDismissRequest = { showPermissionActivationDialog = false },
                    title = { Text("激活本机准点抢座权限") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "为了让手机在早上 07:00 息屏状态下也能准时唤醒帮你抢座，建议先点亮以下权限：",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!permissionSnapshot.exactAlarmAllowed) {
                                FilledTonalButton(
                                    onClick = { requestExactAlarmPermission() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("1. 开启「精准定时闹钟」权限")
                                }
                            }
                            if (!permissionSnapshot.ignoringBatteryOptimizations) {
                                FilledTonalButton(
                                    onClick = { requestIgnoreBatteryOptimizations() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("2. 允许「后台无限制 / 忽略电池优化」")
                                }
                            }
                            if (!permissionSnapshot.notificationGranted) {
                                FilledTonalButton(
                                    onClick = { requestNotificationPermissionOrSettings() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("3. 开启「抢座结果通知」权限")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showPermissionActivationDialog = false
                                saveBookingPlanNow()
                            }
                        ) {
                            Text("已设置好，立即开启定时抢座")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showPermissionActivationDialog = false }) {
                            Text("稍后")
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
                    enabled = computedScheduleResult.isSuccess && roomLabel.isNotBlank() && selectedSeatNames.isNotEmpty(),
                    onClick = {
                        permissionSnapshot = LibrarySeatAlarmSubsystem.inspectPermissions(context)
                        if (selectedMode != BookingExecutionMode.CLOUD_HOSTED && !permissionSnapshot.allReady) {
                            showPermissionActivationDialog = true
                        } else {
                            saveBookingPlanNow()
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("⏰ 开启定时抢座")
                }

                FilledTonalButton(
                    enabled = !isRunningImmediateBooking && roomLabel.isNotBlank() && selectedSeatNames.isNotEmpty(),
                    onClick = {
                        isRunningImmediateBooking = true
                        scope.launch {
                            val sessionRes = repository.ensureLibrarySeatSession(forceRefresh = false)
                            if (sessionRes !is LibrarySeatSessionState.Authenticated) {
                                isRunningImmediateBooking = false
                                onFeedback(false, "请先点击上方登录学号后再选座")
                                onRequireLogin()
                                return@launch
                            }
                            val bookRes = withContext(Dispatchers.IO) {
                                HautLibrarySeatAuthClient.executeAutomatedSeatBooking(
                                    rawToken = sessionRes.snapshot.rawToken,
                                    targetDate = targetDate.toString(),
                                    preferredAreaName = roomLabel,
                                    preferredSeatLabel = preferredSeatsText
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
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isRunningImmediateBooking) "正在帮你选座…" else "⚡ 现在立刻选这个座")
                }
            }
        }

        // 我的定时抢座任务列表
        XPeachCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "我的抢座任务 (${bookingPlans.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (bookingPlans.isEmpty()) {
                Text(
                    text = "还没有设置定时抢座任务。在上面点选自习室和座位号后，点击「⏰ 开启定时抢座」即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (plan in bookingPlans) {
                        val friendlyStatus = when (plan.status) {
                            "ARMED" -> "等待到点开抢"
                            "PRECHECK_OK", "FINAL_CHECK_OK" -> "已准备好，等待开抢"
                            "CONFIRMED" -> "🎉 抢座成功"
                            "FAILED" -> "未抢到"
                            "CANCELLED" -> "已取消"
                            else -> plan.status
                        }
                        val friendlyMode = when (plan.executionMode) {
                            "CLOUD_HOSTED" -> "云端代抢"
                            "NOTIFICATION_ONLY", "MANUAL_REMINDER" -> "仅提醒"
                            else -> "手机自动抢"
                        }
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${plan.targetLocalDate} ${plan.targetLocalTime} · ${plan.preferredAreaName}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                XPeachStatusBadge(
                                    text = "$friendlyMode · $friendlyStatus",
                                    containerColor = when (plan.status) {
                                        "CONFIRMED" -> MaterialTheme.colorScheme.primaryContainer
                                        "FAILED", "CANCELLED" -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    contentColor = when (plan.status) {
                                        "CONFIRMED" -> MaterialTheme.colorScheme.primary
                                        "FAILED", "CANCELLED" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                            }
                            Text(
                                text = "已选座位：${plan.preferredSeatLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = plan.lastDiagnosticMessage ?: "等待到点自动抢座",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (plan.status == "CONFIRMED") {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                repository.cancelActiveLibrarySeatBooking(
                                                    planId = plan.id,
                                                    bookingId = plan.externalBookingId
                                                ).fold(
                                                    onSuccess = { msg ->
                                                        onFeedback(true, msg)
                                                        onRefreshSeatOverview()
                                                        selectedRegion?.let { reloadSeatsForRegion(it) }
                                                    },
                                                    onFailure = { err ->
                                                        onFeedback(false, err.message ?: "退座失败")
                                                    }
                                                )
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("退座释放", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            } else if (plan.status !in setOf("CONFIRMED", "CANCELLED", "FAILED")) {
                                Row(
                                    modifier = Modifier.padding(top = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            scope.launch {
                                                val updated = repository.advanceBookingPlanStage(
                                                    plan.id,
                                                    TripleScheduleStage.FIRE
                                                ).getOrNull()
                                                if (updated != null) {
                                                    onFeedback(
                                                        updated.status == "CONFIRMED",
                                                        updated.lastDiagnosticMessage ?: "已尝试抢座"
                                                    )
                                                    if (updated.status == "CONFIRMED") {
                                                        onRefreshSeatOverview()
                                                    }
                                                }
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("⚡ 现在立刻抢", style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                repository.cancelDualEngineBookingPlan(plan.id)
                                                onFeedback(true, "已取消该抢座任务")
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("取消任务", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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

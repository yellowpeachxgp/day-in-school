/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.feature.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
import cn.edu.haut.campus.core.campusservices.HautLibrarySeatAuthClient
import cn.edu.haut.campus.core.campusservices.LibrarySeatActionType
import cn.edu.haut.campus.core.campusservices.LibrarySeatBookingRecord
import cn.edu.haut.campus.core.campusservices.LibrarySeatBookingStatus
import cn.edu.haut.campus.core.campusservices.LibrarySeatOverviewSnapshot
import cn.edu.haut.campus.core.campusservices.LibrarySeatSessionState
import cn.edu.haut.campus.core.campusservices.OfficialServiceRoutePolicy
import cn.edu.haut.campus.core.campusservices.OfficialWebScripts
import cn.edu.haut.campus.core.campusservices.SafeWebNavigationPolicy
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
                LibrarySeatSessionState.Refreshing("正在校验持久化 Cookie 与加密凭据…")
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
    var lastSilentReloginAttemptMs by remember { mutableLongStateOf(0L) }

    // 原生座位预约概览与动作控制状态
    var seatOverview by remember { mutableStateOf<LibrarySeatOverviewSnapshot?>(null) }
    var isRefreshingOverview by remember { mutableStateOf(false) }
    var pendingActionRequest by remember { mutableStateOf<PendingSeatActionRequest?>(null) }
    var isExecutingSeatAction by remember { mutableStateOf(false) }
    var actionFeedbackBanner by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

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

    // 自动在 5 秒后收起顶部操作反馈提示条
    LaunchedEffect(actionFeedbackBanner) {
        if (actionFeedbackBanner != null) {
            delay(5_000L)
            actionFeedbackBanner = null
        }
    }

    // 进入页面时自动校验持久会话并拉取当前预约与暂离状态
    LaunchedEffect(repository) {
        if (repository != null) {
            triggerSilentSessionEnsure(
                forceRefresh = false,
                reasonHint = "正在校验持久会话并自动后台登录…"
            )
        }
    }

    // 轮询监控 wslib H5 内部是否因异步接口 401 弹出了「登录已失效请重新登录」模态框，发现即自动静默续期
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
                            reasonHint = "检测到选座会话过期，正在后台自动重登…"
                        )
                    } else if (repository != null && prefs?.hasSavedCredentials != true) {
                        sessionState = LibrarySeatSessionState.CredentialsRequired(
                            "检测到选座登录已失效，绑定统一身份认证账号后将自动后台重登"
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
        XPeachTopBar(
            title = "图书馆座位预约",
            subtitle = if (OfficialServiceRoutePolicy.isSchoolCasAuthUrl(currentUrl)) {
                "统一身份认证 (mapp.haut.edu.cn)"
            } else {
                "学校官方空间预约系统 (wslib.haut.edu.cn)"
            },
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            }
        )

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
                                        text = "持久保活",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Text(
                                    text = "$userLabel · ${state.snapshot.refreshSource.label}",
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
                                            reasonHint = "正在刷新持久化 Cookie 与选座 Token…"
                                        )
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("续期", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = { showBindCredentialDialog = true },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("凭据", style = MaterialTheme.typography.labelSmall)
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
                                    "绑定自动重登",
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
                                            reasonHint = "正在重试后台自动登录…"
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
                                    Text("账号", style = MaterialTheme.typography.labelSmall)
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

                        // 原生快捷动作控件行：取消预约 / 签到 / 短暂离 / 长暂离 / 暂离返回 / 续座 / 签退释放
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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

                            if (activeBooking.canCancel) {
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
                                        "取消预约",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
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
                        text = "正在使用加密凭据建立持久化选座会话…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 底部辅助导航栏：后退、刷新、选座列表、我的预约(取消/暂离)、清会话
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
                            reasonHint = "正在校验持久会话并刷新状态…"
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
                Text("清会话", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
            }
        }
    }

    // 原生座位动作二次确认弹窗（取消预约 / 签到 / 短暂离 / 长暂离 / 暂离返回 / 续座 / 签退释放）
    pendingActionRequest?.let { req ->
        val customDetail = when (req.action) {
            LibrarySeatActionType.SHORT_LEAVE -> {
                val min = seatOverview?.shortLeaveMinutes ?: 30
                "座位将为您保留 $min 分钟，请在规定时间内返回并点击「暂离返回」，否则将自动释放座位并记违约。确定办理短暂离开吗？"
            }
            LibrarySeatActionType.LONG_LEAVE -> {
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
            title = { Text("绑定选座自动重登凭据") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "学号与统一身份认证密码将使用 Android Keystore AES-256-GCM 硬件密钥加密保存在本地数据库。当图书馆选座 Cookie 或 Token 失效时，系统将自动在后台完成重登与续期。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = inputStudentId,
                        onValueChange = { inputStudentId = it.trim() },
                        label = { Text("学号（统一身份认证账号）") },
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
                            sessionState = LibrarySeatSessionState.Refreshing("正在加密保存凭据并后台登录选座系统…")
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
                    Text(if (isSavingCredentials) "加密登录中…" else "加密保存并登录")
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
            title = { Text("清除网页 Cookie 并重新同步会话") },
            text = {
                Text("将清除内嵌网页缓存与当前持久化选座 Token。若已绑定加密统一认证凭据，清除后可随时一键自动后台重登。")
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
                                    reasonHint = "已清理旧缓存，正在用加密凭据重新建立持久会话…"
                                )
                            } else {
                                webViewRef?.loadUrl(OfficialServiceRoutePolicy.SEAT_BOOKING_ENTRY_URL)
                            }
                        }
                    }
                ) {
                    Text("确认清除")
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

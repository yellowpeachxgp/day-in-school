package cn.edu.haut.campus.feature.importer

import android.annotation.SuppressLint
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.academic.HautLiveSyncResult
import cn.edu.haut.campus.core.academic.syncSummaryText
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import cn.edu.haut.campus.core.academic.HautSessionManager
import cn.edu.haut.campus.core.academic.HautZfsoftParser
import cn.edu.haut.campus.core.academic.TimetableInterchangeAdapter
import cn.edu.haut.campus.core.campusservices.OfficialServiceRoutePolicy
import cn.edu.haut.campus.core.campusservices.SafeWebNavigationPolicy
import cn.edu.haut.campus.core.campusservices.SchoolWebSessionManager
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.model.AcademicTermResolver
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImporterScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val audits by repository.importAuditsFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 统一认证, 1: 网页登录, 2: 文件与 CSV
    var diffPreview by remember { mutableStateOf<ImportDiffPreview?>(null) }
    var pendingInterchangeMeta by remember {
        mutableStateOf<TimetableInterchangeAdapter.InterchangeParseResult?>(null)
    }
    var applyInterchangeCalendar by remember { mutableStateOf(true) }
    var overwriteUserEdits by remember { mutableStateOf(false) }
    var rememberForAutoSync by remember { mutableStateOf(true) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }

    val openTimetableFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val rawText = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }.orEmpty()
                val parsed = TimetableInterchangeAdapter.parse(rawText)
                val termId = currentTerm?.id ?: AcademicTermResolver.defaultTermId()
                pendingInterchangeMeta = parsed
                diffPreview = repository.previewCourseImportDiff(
                    termId = termId,
                    source = CourseSource.CSV_ICS,
                    candidates = parsed.candidates
                )
                val warnSuffix = if (parsed.warnings.isNotEmpty()) {
                    "（提示：${parsed.warnings.joinToString("；")}）"
                } else {
                    ""
                }
                statusBanner = "已从${parsed.sourceLabel}解析 ${parsed.candidates.size} 条课程记录，请确认下方预览。$warnSuffix"
            }.onFailure { err ->
                statusBanner = "导入文件失败：${err.message ?: "请检查文件格式"}"
            }
        }
    }

    val exportJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val jsonText = TimetableInterchangeAdapter.exportJson(
                    term = currentTerm,
                    courses = courses,
                    rules = rules,
                    periodTimes = periodTimes
                )
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(jsonText.toByteArray(Charsets.UTF_8))
                }
                statusBanner = "已导出 JSON 课表文件（不含个人信息）。"
            }.onFailure { err ->
                statusBanner = "导出失败：${err.message ?: "无法写入文件"}"
            }
        }
    }

    val exportMarkdownLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val mdText = TimetableInterchangeAdapter.exportMarkdown(
                    term = currentTerm,
                    courses = courses,
                    rules = rules,
                    periodTimes = periodTimes
                )
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(mdText.toByteArray(Charsets.UTF_8))
                }
                statusBanner = "已导出 Markdown 课表文件（不含个人信息）。"
            }.onFailure { err ->
                statusBanner = "导出失败：${err.message ?: "无法写入文件"}"
            }
        }
    }

    var studentIdInput by remember(prefs.savedStudentId) { mutableStateOf(prefs.savedStudentId ?: "") }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val initialTermPair = remember(currentTerm?.id, prefs.syncXnm, prefs.syncXqm) {
        currentTerm?.id?.let { HautAcademicEndpoints.fromTermId(it) }
            ?: (prefs.syncXnm to prefs.syncXqm)
    }
    var selectedXnm by remember(initialTermPair.first) { mutableStateOf(initialTermPair.first) }
    var selectedXqm by remember(initialTermPair.second) { mutableStateOf(initialTermPair.second) }
    val selectedTermId = remember(selectedXnm, selectedXqm) {
        HautAcademicEndpoints.toTermId(selectedXnm, selectedXqm)
    }

    var csvInputText by remember {
        mutableStateOf(
            """
            # 课程名,教师,星期(1-7),开始节,结束节,周次,教室,学分
            编译原理,王志远,3,1,2,1-16周,莲1号教学楼1306,3.5
            数字图像处理,陈静,4,5,6,2-16周(双),莲4号教学楼4402,2.5
            """.trimIndent()
        )
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "同步与导入",
            subtitle = "当前学期：${currentTerm?.displayName ?: selectedTermId}",
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
            XPeachSegmentedTabs(
                items = listOf("账号登录", "网页登录", "文件与 CSV"),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            val focusManager = LocalFocusManager.current

            AnimatedVisibility(
                visible = statusBanner != null,
                enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(140))
            ) {
                statusBanner?.let { bannerMsg ->
                    XPeachCard(
                        containerColor = XPeachTheme.primaryAccentCardColor,
                        borderColor = XPeachTheme.readablePrimary.copy(alpha = 0.32f)
                    ) {
                        Text(
                            text = bannerMsg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    val direction = if (targetState >= initialState) {
                        AnimatedContentTransitionScope.SlideDirection.Start
                    } else {
                        AnimatedContentTransitionScope.SlideDirection.End
                    }
                    (slideIntoContainer(
                        towards = direction,
                        animationSpec = tween(240, easing = FastOutSlowInEasing)
                    ) { it / 8 } + fadeIn(tween(200))) togetherWith
                        (slideOutOfContainer(
                            towards = direction,
                            animationSpec = tween(190, easing = FastOutSlowInEasing)
                        ) { it / 10 } + fadeOut(tween(140)))
                },
                label = "importerTabTransition",
                modifier = Modifier.fillMaxWidth()
            ) { targetTab ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (targetTab) {
                        0 -> {
                            val triggerDirectSync: () -> Unit = {
                                focusManager.clearFocus()
                                if (!isSyncing && passwordInput.isBlank() && prefs.hasSavedCredentials) {
                                    scope.launch {
                                        isSyncing = true
                                        repository.selectCurrentTerm(selectedTermId)
                                        statusBanner = "正在更新 $selectedTermId 课表、考试与成绩..."
                                        val res = repository.performDynamicAcademicSync(force = true)
                                        isSyncing = false
                                        res?.onSuccess { syncData ->
                                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                            statusBanner = syncData.syncSummaryText()
                                        }?.onFailure { err ->
                                            statusBanner = "更新失败：${err.message ?: "请检查网络或重新输入密码"}"
                                        }
                                    }
                                } else if (!isSyncing && studentIdInput.isNotBlank() && passwordInput.isNotBlank()) {
                                    scope.launch {
                                        val plainPwd = passwordInput
                                        isSyncing = true
                                        statusBanner = "正在连接统一身份认证并同步 $selectedTermId 课表、考试与成绩..."
                                        val result = HautLiveAcademicClient.fetchLiveAcademicData(
                                            studentId = studentIdInput,
                                            passwordPlain = plainPwd,
                                            xnm = selectedXnm,
                                            xqm = selectedXqm
                                        )
                                        passwordInput = ""
                                        isSyncing = false

                                        result.onSuccess { syncData ->
                                            val applied = runCatching {
                                                repository.applyLiveSyncData(syncData, overwriteUserConflicts = overwriteUserEdits)
                                            }
                                            if (applied.isFailure) {
                                                statusBanner = "保存失败，请重试（已有数据不受影响）"
                                                return@onSuccess
                                            }
                                            val credentialFailure = if (rememberForAutoSync) {
                                                val saved = runCatching {
                                                    repository.saveAuthorizedSyncCredentials(
                                                        studentIdInput, plainPwd, selectedXnm, selectedXqm
                                                    )
                                                }
                                                repository.setAutoSyncEnabled(saved.isSuccess)
                                                saved.isFailure
                                            } else false
                                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                            diffPreview = null
                                            statusBanner = syncData.syncSummaryText() + if (credentialFailure) {
                                                "\n密码保存失败，已关闭自动更新"
                                            } else ""
                                        }.onFailure { err ->
                                            statusBanner = "同步失败：${err.message ?: "请检查网络或账号密码"}"
                                        }
                                    }
                                }
                            }

                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "教务账号登录",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    XPeachStatusDotText(
                                        text = "课表 + 考试 + 成绩",
                                        dotColor = XPeachTheme.readablePrimary
                                    )
                                }

                                if (prefs.academicReauthenticationRequired) {
                                    Text("登录已过期，请重新输入密码", color = MaterialTheme.colorScheme.error)
                                }
                                if (prefs.hasSavedCredentials) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "已绑定学号 ${prefs.savedStudentId ?: ""}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "无需重输密码即可更新本学期课表、考试与成绩",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            FilledTonalButton(
                                                enabled = !isSyncing,
                                                onClick = triggerDirectSync,
                                                shape = RoundedCornerShape(12.dp)
                                            ) {
                                                Text("立即更新", maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = studentIdInput,
                                    onValueChange = { studentIdInput = it },
                                    label = { Text("学号") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(
                                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = {
                                        Text(if (prefs.hasSavedCredentials) "密码（已保存，如需更新请重新输入）" else "密码")
                                    },
                                    singleLine = true,
                                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(
                                        onDone = { triggerDirectSync() }
                                    ),
                                    trailingIcon = {
                                        IconButton(onClick = { showPassword = !showPassword }) {
                                            Icon(
                                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                                contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("学年", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                    for (yr in AcademicTermResolver.recentAcademicYears()) {
                                        XPeachFilterPill(
                                            label = "$yr-${yr.toInt() + 1}",
                                            selected = selectedXnm == yr,
                                            compact = true,
                                            onClick = {
                                                selectedXnm = yr
                                                scope.launch {
                                                    repository.selectCurrentTerm(HautAcademicEndpoints.toTermId(yr, selectedXqm))
                                                }
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("学期", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                    XPeachFilterPill(
                                        label = "第 1 学期",
                                        selected = selectedXqm == "3",
                                        compact = true,
                                        onClick = {
                                            selectedXqm = "3"
                                            scope.launch {
                                                repository.selectCurrentTerm(HautAcademicEndpoints.toTermId(selectedXnm, "3"))
                                            }
                                        }
                                    )
                                    XPeachFilterPill(
                                        label = "第 2 学期",
                                        selected = selectedXqm == "12",
                                        compact = true,
                                        onClick = {
                                            selectedXqm = "12"
                                            scope.launch {
                                                repository.selectCurrentTerm(HautAcademicEndpoints.toTermId(selectedXnm, "12"))
                                            }
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = rememberForAutoSync,
                                        onCheckedChange = { rememberForAutoSync = it }
                                    )
                                    Text(
                                        text = "启动时自动更新课表、考试与成绩",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        enabled = !isSyncing && studentIdInput.isNotBlank() &&
                                            (passwordInput.isNotBlank() || prefs.hasSavedCredentials),
                                        onClick = triggerDirectSync,
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text(
                                            text = if (isSyncing) "同步中..." else "开始同步",
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    if (isSyncing) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }

                        1 -> {
                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "网页登录导入",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    XPeachStatusDotText(
                                        text = "课表 + 考试 + 成绩",
                                        dotColor = XPeachTheme.readablePrimary
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("学年", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                    for (yr in AcademicTermResolver.recentAcademicYears()) {
                                        XPeachFilterPill(
                                            label = "$yr-${yr.toInt() + 1}",
                                            selected = selectedXnm == yr,
                                            compact = true,
                                            onClick = {
                                                selectedXnm = yr
                                                scope.launch {
                                                    repository.selectCurrentTerm(HautAcademicEndpoints.toTermId(yr, selectedXqm))
                                                }
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("学期", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                    XPeachFilterPill(
                                        label = "第 1 学期",
                                        selected = selectedXqm == "3",
                                        compact = true,
                                        onClick = {
                                            selectedXqm = "3"
                                            scope.launch {
                                                repository.selectCurrentTerm(HautAcademicEndpoints.toTermId(selectedXnm, "3"))
                                            }
                                        }
                                    )
                                    XPeachFilterPill(
                                        label = "第 2 学期",
                                        selected = selectedXqm == "12",
                                        compact = true,
                                        onClick = {
                                            selectedXqm = "12"
                                            scope.launch {
                                                repository.selectCurrentTerm(HautAcademicEndpoints.toTermId(selectedXnm, "12"))
                                            }
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilledTonalButton(
                                        onClick = {
                                            webViewRef?.loadUrl(HautAcademicEndpoints.CAS_SSO_ENTRY_URL)
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("刷新登录页", maxLines = 1, softWrap = false)
                                    }
                                    Button(
                                        onClick = {
                                            val js = """
                                                (function() {
                                                    if (location.hostname !== 'jwglxt.haut.edu.cn') {
                                                        return JSON.stringify({ error: 'NOT_ON_JWGLXT', host: location.hostname });
                                                    }
                                                    function postSync(path, body) {
                                                        try {
                                                            var xhr = new XMLHttpRequest();
                                                            xhr.open('POST', path, false);
                                                            xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
                                                            xhr.send(body);
                                                            return xhr.responseText || '';
                                                        } catch (e) {
                                                            return '';
                                                        }
                                                    }
                                                    var kb = postSync('${HautAcademicEndpoints.TIMETABLE_API_PATH}', 'xnm=$selectedXnm&xqm=$selectedXqm&kzlx=ck&xsdm=');
                                                    var zc = postSync('${HautAcademicEndpoints.TERM_WEEKS_API_PATH}', 'xnm=$selectedXnm&xqm=$selectedXqm');
                                                    var cj = postSync('${HautAcademicEndpoints.GRADES_API_PATH}', 'xnm=&xqm=&_search=false&queryModel.showCount=500&queryModel.currentPage=1&queryModel.sortName=&queryModel.sortOrder=asc&time=0');
                                                    var ks = postSync('${HautAcademicEndpoints.EXAMS_API_PATH}', 'xnm=&xqm=&_search=false&queryModel.showCount=200&queryModel.currentPage=1&queryModel.sortName=&queryModel.sortOrder=asc&time=0');
                                                    return JSON.stringify({
                                                        kb: kb,
                                                        zc: zc,
                                                        cj: cj,
                                                        ks: ks
                                                    });
                                                })();
                                            """.trimIndent()
                                            webViewRef?.evaluateJavascript(js) { rawBody ->
                                                scope.launch {
                                                    val decoded = runCatching {
                                                        JSONTokener(rawBody ?: "").nextValue() as? String
                                                    }.getOrNull().orEmpty()
                                                    val wrapper = runCatching { JSONObject(decoded) }.getOrNull()
                                                    if (wrapper?.optString("error") == "NOT_ON_JWGLXT") {
                                                        statusBanner = "请先在下方网页完成统一身份认证登录，等待页面跳转至正方教务系统后再点击「同步教务」。"
                                                        return@launch
                                                    }
                                                    val kbJson = wrapper?.optString("kb").orEmpty()
                                                    val zcJson = wrapper?.optString("zc").orEmpty()
                                                    val cjJson = wrapper?.optString("cj").orEmpty()
                                                    val ksJson = wrapper?.optString("ks").orEmpty()
                                                    val breakdown = runCatching {
                                                        HautZfsoftParser.parseTimetableWithBreakdown(kbJson)
                                                    }.getOrNull()
                                                    val parsed = breakdown?.allCandidates.orEmpty()
                                                    val unscheduled = breakdown?.unscheduledCourses.orEmpty()
                                                    val termCal = runCatching {
                                                        HautZfsoftParser.parseTermCalendarJson(zcJson)
                                                    }.getOrNull()
                                                    val gradesRead = runCatching {
                                                        HautZfsoftParser.parseGradesJson(cjJson)
                                                    }
                                                    val examsRead = runCatching {
                                                        HautZfsoftParser.parseExamsJson(ksJson)
                                                    }
                                                    val gradesList = gradesRead.getOrDefault(emptyList())
                                                    val examsList = examsRead.getOrDefault(emptyList())

                                                    if (parsed.isNotEmpty() || unscheduled.isNotEmpty() || gradesList.isNotEmpty()) {
                                                        val profile = HautZfsoftParser.parseStudentProfileFromTimetableJson(kbJson)
                                                        val termId = HautAcademicEndpoints.toTermId(selectedXnm, selectedXqm)
                                                        val syncData = HautLiveSyncResult(
                                                            termId = termId,
                                                            studentProfile = profile,
                                                            timetableCandidates = parsed,
                                                            gradeAttempts = gradesList,
                                                            examItems = examsList,
                                                            semesterStart = termCal?.first,
                                                            totalWeeks = termCal?.second,
                                                            regularCourseCount = breakdown?.regularCount ?: parsed.size,
                                                            practiceCourseCount = breakdown?.practiceCount ?: 0,
                                                            gradesComplete = cjJson.contains("\"items\"") && gradesRead.isSuccess,
                                                            examsComplete = ksJson.contains("\"items\"") && examsRead.isSuccess,
                                                            unscheduledCourses = unscheduled
                                                        )
                                                        repository.applyLiveSyncData(
                                                            syncData = syncData,
                                                            overwriteUserConflicts = overwriteUserEdits
                                                        )
                                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                                        diffPreview = null
                                                        statusBanner = syncData.syncSummaryText()
                                                    } else {
                                                        statusBanner = "未在 $selectedTermId 检测到教务数据，请确认下方网页已登录进入教务系统，或切换学年/学期后重试。"
                                                    }
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("同步教务", maxLines = 1, softWrap = false)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                HautSessionManager.clearAuthorizedSessionCookies()
                                                SchoolWebSessionManager.clearAllSchoolWebSessions(webViewRef)
                                                statusBanner = "已清除网页会话。"
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text("清除", maxLines = 1, softWrap = false)
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                AndroidView(
                                    factory = { ctx ->
                                        val teachingFixScript = runCatching {
                                            ctx.assets.open("teaching-layout.js").bufferedReader().use { it.readText() }
                                        }.getOrNull()
                                        WebView(ctx).apply {
                                            SchoolWebSessionManager.configureHardenedSchoolWebView(this)
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(
                                                    view: WebView?,
                                                    request: WebResourceRequest?
                                                ): Boolean {
                                                    return SafeWebNavigationPolicy.shouldBlockNavigation(request, view)
                                                }

                                                override fun shouldInterceptRequest(
                                                    view: WebView?,
                                                    request: WebResourceRequest?
                                                ): android.webkit.WebResourceResponse? {
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

                                                override fun onPageFinished(view: WebView?, url: String?) {
                                                    super.onPageFinished(view, url)
                                                    if (!teachingFixScript.isNullOrBlank() &&
                                                        OfficialServiceRoutePolicy.isAllowedMainFrameUrl(url.orEmpty())
                                                    ) {
                                                        view?.evaluateJavascript(teachingFixScript, null)
                                                    }
                                                    SchoolWebSessionManager.persistCookiesAsync()
                                                }
                                            }
                                            loadUrl(HautAcademicEndpoints.CAS_SSO_ENTRY_URL)
                                            webViewRef = this
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(360.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                )
                            }
                        }

                        2 -> {
                            if (prefs.timetableInterchangeEnabled) {
                                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "课表文件导入与导出",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        XPeachStatusDotText(
                                            text = ".md / .json",
                                            dotColor = XPeachTheme.readablePrimary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "支持导入或导出 .md / .json 课表文件（导出文件不包含学号等个人信息）。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                openTimetableFileLauncher.launch(
                                                    arrayOf("application/json", "text/markdown", "text/plain", "*/*")
                                                )
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                            modifier = Modifier.weight(1.2f)
                                        ) {
                                            Text("选择课表文件", maxLines = 1, softWrap = false)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                exportMarkdownLauncher.launch("haut-timetable-v1.md")
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("导出 .md", maxLines = 1, softWrap = false)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                exportJsonLauncher.launch("haut-timetable-v1.json")
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("导出 .json", maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }

                            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "文本导入（支持 CSV / Markdown 课表 / JSON）",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = csvInputText,
                                    onValueChange = { csvInputText = it },
                                    minLines = 5,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            val termId = currentTerm?.id ?: AcademicTermResolver.defaultTermId()
                                            val trimmed = csvInputText.trim()
                                            val isInterchangeFormat = trimmed.startsWith("{") ||
                                                trimmed.startsWith("[") ||
                                                trimmed.contains("| 课程") ||
                                                trimmed.contains("```json")
                                            if (isInterchangeFormat) {
                                                runCatching {
                                                    val parsed = TimetableInterchangeAdapter.parse(trimmed)
                                                    pendingInterchangeMeta = parsed
                                                    diffPreview = repository.previewCourseImportDiff(
                                                        termId = termId,
                                                        source = CourseSource.CSV_ICS,
                                                        candidates = parsed.candidates
                                                    )
                                                    statusBanner = "已解析 ${parsed.candidates.size} 条课程，请确认下方预览。"
                                                }.onFailure { err ->
                                                    statusBanner = "解析失败：${err.message ?: "请检查文本格式"}"
                                                }
                                            } else {
                                                pendingInterchangeMeta = null
                                                val candidates = HautZfsoftParser.parseCsvSchedule(csvInputText)
                                                diffPreview = repository.previewCourseImportDiff(
                                                    termId = termId,
                                                    source = CourseSource.CSV_ICS,
                                                    candidates = candidates
                                                )
                                                statusBanner = "已解析 ${candidates.size} 条 CSV 课程，请确认下方预览。"
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text("解析文本", maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }

            val preview = diffPreview
            if (preview != null) {
                XPeachCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = XPeachTheme.readablePrimary.copy(alpha = 0.45f)
                ) {
                    Text(
                        text = "导入预览",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "新增 ${preview.addedCount} · 更新 ${preview.modifiedCount} · 冲突 ${preview.conflictUserEditCount} · 不变 ${preview.unchangedCount}",
                        style = MaterialTheme.typography.labelMedium,
                        color = XPeachTheme.readablePrimary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(modifier = Modifier.fillMaxWidth()) {
                        preview.entries.forEachIndexed { idx, entry ->
                            if (idx > 0) {
                                XPeachInsetDivider(verticalPadding = 6.dp)
                            }
                            Text(
                                text = "[${entry.changeType.label}] ${entry.summary}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (entry.changeType == ImportChangeType.CONFLICT_USER_MODIFIED) {
                                    XPeachTheme.readablePrimary
                                } else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    val meta = pendingInterchangeMeta
                    if (meta != null && meta.hasCalendarMetadata) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = applyInterchangeCalendar,
                                onCheckedChange = { applyInterchangeCalendar = it }
                            )
                            Text(
                                text = "同时应用文件中的开学日期与作息节次时间",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    if (preview.conflictUserEditCount > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = !overwriteUserEdits,
                                onCheckedChange = { keepLocal -> overwriteUserEdits = !keepLocal }
                            )
                            Text(
                                text = "保留手动修改过的课程项",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    repository.commitCourseImportDiff(
                                        preview = preview,
                                        overwriteUserConflicts = overwriteUserEdits
                                    )
                                    val calMeta = pendingInterchangeMeta
                                    if (calMeta != null && calMeta.hasCalendarMetadata && applyInterchangeCalendar) {
                                        repository.applyInterchangeCalendarMetadata(
                                            termId = preview.termId,
                                            semesterStart = calMeta.semesterStart,
                                            totalWeeks = calMeta.totalWeeks,
                                            sectionTimes = calMeta.sectionTimes.map { it.toPeriodTime() }
                                        )
                                    }
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    diffPreview = null
                                    pendingInterchangeMeta = null
                                    statusBanner = "已保存课表。"
                                }
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("保存课表", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = {
                                diffPreview = null
                                pendingInterchangeMeta = null
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("取消", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            if (audits.isNotEmpty()) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Text("导入记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    for (audit in audits.take(5)) {
                        val srcLabel = when (audit.importSource) {
                            CourseSource.OFFICIAL_JW -> "教务同步"
                            CourseSource.CSV_ICS -> "文件/文本导入"
                            CourseSource.OCR -> "图片识别导入"
                            CourseSource.MANUAL -> "手动添加"
                        }
                        Text(
                            text = "$srcLabel · 成功导入 ${audit.rowCount} 条",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

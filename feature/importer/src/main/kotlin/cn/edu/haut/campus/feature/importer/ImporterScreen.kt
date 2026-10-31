package cn.edu.haut.campus.feature.importer

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.academic.HautLiveAcademicClient
import cn.edu.haut.campus.core.academic.HautSessionManager
import cn.edu.haut.campus.core.academic.HautZfsoftParser
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImporterScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val audits by repository.importAuditsFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 河南工大官方抓取, 1: CSV 本地导入, 2: 差异预览演练
    var diffPreview by remember { mutableStateOf<ImportDiffPreview?>(null) }
    var overwriteUserEdits by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var isSyncing by remember { mutableStateOf(false) }

    // 统一身份认证单次直连参数（仅内存态，绝不持久化存储密码）
    var studentIdInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var selectedXnm by remember { mutableStateOf("2025") }
    var selectedXqm by remember { mutableStateOf("12") }

    var csvInputText by remember {
        mutableStateOf(
            """
            # 课程名,教师,星期(1-7),开始节,结束节,周次,教室,学分
            编译原理,王志远 教授,3,1,2,1-16周,莲1号教学楼1306,3.5
            数字图像处理,陈静 副教授,4,5,6,2-16周(双),莲4号教学楼4402,2.5
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
            title = "课表与成绩导入",
            subtitle = "河南工业大学教务同步 · 含差异预览与冲突保护",
            navigationIcon = {
                XPeachIconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            XPeachSegmentedTabs(
                items = listOf("河工大官方抓取", "CSV 本地导入", "冲突演练"),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            if (statusBanner != null) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ) {
                    Text(
                        text = statusBanner!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "方式 A：统一身份认证直连一键抓取",
                                style = MaterialTheme.typography.titleMedium
                            )
                            XPeachStatusBadge(text = "课表 + 成绩")
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "通过学校统一身份认证（CAS -> /sso/jhlogin）自动完成单点登录并抓取个人课表与全部历史成绩。密码仅用于本次内存请求，绝不保存至本地或上传任何第三方。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = studentIdInput,
                            onValueChange = { studentIdInput = it },
                            label = { Text("学号（如 231040600xxx）") },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("统一身份认证密码（仅当次内存使用）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("学年：", style = MaterialTheme.typography.labelLarge)
                            for (yr in listOf("2025", "2024", "2023")) {
                                XPeachFilterPill(
                                    label = "$yr-${yr.toInt() + 1}",
                                    selected = selectedXnm == yr,
                                    onClick = { selectedXnm = yr }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("学期：", style = MaterialTheme.typography.labelLarge)
                            XPeachFilterPill(
                                label = "第 1 学期",
                                selected = selectedXqm == "3",
                                onClick = { selectedXqm = "3" }
                            )
                            XPeachFilterPill(
                                label = "第 2 学期",
                                selected = selectedXqm == "12",
                                onClick = { selectedXqm = "12" }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                enabled = !isSyncing && studentIdInput.isNotBlank() && passwordInput.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        isSyncing = true
                                        statusBanner = "正在通过统一身份认证连接 jwglxt.haut.edu.cn 抓取课表与成绩..."
                                        val result = HautLiveAcademicClient.fetchLiveAcademicData(
                                            studentId = studentIdInput,
                                            passwordPlain = passwordInput,
                                            xnm = selectedXnm,
                                            xqm = selectedXqm
                                        )
                                        passwordInput = "" // 抓取完毕后立即清空内存密码
                                        isSyncing = false

                                        result.onSuccess { syncData ->
                                            syncData.studentProfile?.let { profile ->
                                                repository.saveStudentProfile(profile)
                                            }
                                            if (syncData.gradeAttempts.isNotEmpty()) {
                                                repository.saveImportedGrades(
                                                    attempts = syncData.gradeAttempts,
                                                    replaceExisting = true
                                                )
                                            }
                                            val targetTermId = currentTerm?.id ?: syncData.termId
                                            diffPreview = repository.previewCourseImportDiff(
                                                termId = targetTermId,
                                                source = CourseSource.OFFICIAL_JW,
                                                candidates = syncData.timetableCandidates
                                            )
                                            val stuName = syncData.studentProfile?.displayName ?: studentIdInput
                                            statusBanner = "抓取成功（$stuName）：已同步 ${syncData.gradeAttempts.size} 条学业成绩记录，并解析到 ${syncData.timetableCandidates.size} 条排课，请确认下方课表差异预览！"
                                        }.onFailure { err ->
                                            statusBanner = "抓取失败：${err.message ?: "请检查校园网络或账号密码"}"
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (isSyncing) "正在同步..." else "直连抓取课表与全部成绩")
                            }
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            }
                        }
                    }

                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "方式 B：官方 WebView 授权会话抓取",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "支持统一身份认证入口（${HautAcademicEndpoints.CAS_SSO_ENTRY_URL}）或教务直连入口。完成登录后点击「会话内拉取课表」，将直接在已登录 WebView 内请求接口。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    webViewRef?.loadUrl(HautAcademicEndpoints.CAS_SSO_ENTRY_URL)
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("统一认证登录")
                            }
                            FilledTonalButton(
                                onClick = {
                                    val js = """
                                        (function() {
                                            var xhr = new XMLHttpRequest();
                                            xhr.open('POST', '${HautAcademicEndpoints.TIMETABLE_API_PATH}', false);
                                            xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded;charset=UTF-8');
                                            xhr.send('xnm=$selectedXnm&xqm=$selectedXqm&kzlx=ck&xsdm=');
                                            return xhr.responseText;
                                        })();
                                    """.trimIndent()
                                    webViewRef?.evaluateJavascript(js) { rawBody ->
                                        scope.launch {
                                            val unescaped = rawBody
                                                ?.trim('"')
                                                ?.replace("\\\"", "\"")
                                                ?.replace("\\n", "\n")
                                                ?.replace("\\\\", "\\")
                                                .orEmpty()
                                            val parsed = runCatching {
                                                HautZfsoftParser.parseTimetableJson(unescaped)
                                            }.getOrDefault(emptyList())

                                            if (parsed.isNotEmpty()) {
                                                HautZfsoftParser.parseStudentProfileFromTimetableJson(unescaped)
                                                    ?.let { repository.saveStudentProfile(it) }
                                                val termId = currentTerm?.id ?: "2025-2026-2"
                                                diffPreview = repository.previewCourseImportDiff(
                                                    termId = termId,
                                                    source = CourseSource.OFFICIAL_JW,
                                                    candidates = parsed
                                                )
                                                statusBanner = "从 WebView 教务会话拉取到 ${parsed.size} 条排课，请确认下方差异预览。"
                                            } else {
                                                statusBanner = "未检测到有效课表数据，请确认已在下方页面完成登录并进入教务系统主页。"
                                            }
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("会话内拉取课表")
                            }
                            OutlinedButton(
                                onClick = {
                                    HautSessionManager.clearAuthorizedSessionCookies()
                                    statusBanner = "已清除 WebView 教务授权 Cookie 会话。"
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("清除会话")
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    webViewClient = WebViewClient()
                                    loadUrl(HautAcademicEndpoints.CAS_SSO_ENTRY_URL)
                                    webViewRef = this
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(340.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        )
                    }
                }

                1 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text("本地 CSV 课表解析与差异预览", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = csvInputText,
                            onValueChange = { csvInputText = it },
                            minLines = 5,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val termId = currentTerm?.id ?: "2025-2026-2"
                                    val candidates = HautZfsoftParser.parseCsvSchedule(csvInputText)
                                    diffPreview = repository.previewCourseImportDiff(
                                        termId = termId,
                                        source = CourseSource.CSV_ICS,
                                        candidates = candidates
                                    )
                                    statusBanner = "已解析 ${candidates.size} 条 CSV 课程，请确认下方差异预览。"
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("预览 CSV 导入差异")
                        }
                    }
                }

                2 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "本地优先 · 导入差异预览与冲突保护验证",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "规范要求：教务导入结果必须先预览并给出「新增 / 修改 / 冲突（保留用户修改）」明细，确认后才入库；错误时不影响已有离线缓存。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val termId = currentTerm?.id ?: "2025-2026-2"
                                    val candidates = HautZfsoftParser.createSimulatedJwTimetableCandidates()
                                    diffPreview = repository.previewCourseImportDiff(
                                        termId = termId,
                                        source = CourseSource.OFFICIAL_JW,
                                        candidates = candidates
                                    )
                                    statusBanner = "已生成导入差异预览，请核对下方明细后确认提交。"
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("生成教务同步差异预览")
                        }
                    }
                }
            }

            // 差异预览确认区
            val preview = diffPreview
            if (preview != null) {
                XPeachCard(
                    modifier = Modifier.fillMaxWidth(),
                    borderColor = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "导入差异明细预览（确认后才入库）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "新增 ${preview.addedCount} 项 · 更新 ${preview.modifiedCount} 项 · 用户修改冲突 ${preview.conflictUserEditCount} 项 · 无变化 ${preview.unchangedCount} 项",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (entry in preview.entries) {
                            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "[${entry.changeType.label}] ${entry.summary}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (entry.changeType == ImportChangeType.CONFLICT_USER_MODIFIED) {
                                        FontWeight.Bold
                                    } else FontWeight.Normal,
                                    color = if (entry.changeType == ImportChangeType.CONFLICT_USER_MODIFIED) {
                                        MaterialTheme.colorScheme.primary
                                    } else MaterialTheme.colorScheme.onSurface
                                )
                            }
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
                                text = "保留我手动修改过的课程属性（推荐，防止教务同步静默覆盖）",
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
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    diffPreview = null
                                    statusBanner = "导入已提交入库，并已通知全部桌面小组件同步刷新！"
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("确认提交入库")
                        }
                        OutlinedButton(
                            onClick = { diffPreview = null },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("放弃本次导入")
                        }
                    }
                }
            }

            // 历史导入审计记录
            if (audits.isNotEmpty()) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Text("最近导入审计记录", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    for (audit in audits.take(5)) {
                        Text(
                            text = "• [${audit.importSource.name}] ${audit.outcome} (共 ${audit.rowCount} 条)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

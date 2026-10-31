package cn.edu.haut.campus.feature.importer

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cn.edu.haut.campus.core.academic.HautAcademicEndpoints
import cn.edu.haut.campus.core.academic.HautSessionManager
import cn.edu.haut.campus.core.academic.HautZfsoftParser
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.ImportChangeType
import cn.edu.haut.campus.core.model.ImportDiffPreview
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImporterScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val audits by repository.importAuditsFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 模拟/演练差异预览, 1: 官方教务WebView, 2: 本地CSV导入
    var diffPreview by remember { mutableStateOf<ImportDiffPreview?>(null) }
    var overwriteUserEdits by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    var csvInputText by remember {
        mutableStateOf(
            """
            # 课程名,教师,星期(1-7),开始节,结束节,周次,教室,学分
            编译原理,王志远 教授,3,1,2,1-16周,1号教学楼1306,3.5
            数字图像处理,陈静 副教授,4,5,6,2-16周(双),4号教学楼4402,2.5
            """.trimIndent()
        )
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表与成绩导入（含差异预览）") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    label = { Text("教务同步差异演练") }
                )
                FilterChip(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    label = { Text("河南工大官方登录") }
                )
                FilterChip(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    label = { Text("CSV 本地导入") }
                )
            }

            if (statusBanner != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusBanner!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "本地优先 · 导入差异预览与冲突保护验证",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "规范要求：教务导入结果必须先预览并给出「新增 / 修改 / 冲突（保留用户修改）」明细，确认后才入库；错误时不影响已有离线缓存。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        val termId = currentTerm?.id ?: "2026-2027-1"
                                        val candidates = HautZfsoftParser.createSimulatedJwTimetableCandidates()
                                        diffPreview = repository.previewCourseImportDiff(
                                            termId = termId,
                                            source = CourseSource.OFFICIAL_JW,
                                            candidates = candidates
                                        )
                                        statusBanner = "已生成导入差异预览，请核对下方明细后确认提交。"
                                    }
                                }
                            ) {
                                Text("生成教务同步差异预览")
                            }
                        }
                    }
                }

                1 -> {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "河南工业大学正方教务官方授权会话",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "入口：${HautAcademicEndpoints.OFFICIAL_LOGIN_URL}\n说明：请直接在下方学校官方页面输入学号、密码与验证码。App 绝不收集明文账号密码，不绕过验证码。",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        webViewRef?.loadUrl(HautAcademicEndpoints.OFFICIAL_LOGIN_URL)
                                    }
                                ) {
                                    Text("加载登录页")
                                }
                                FilledTonalButton(
                                    onClick = {
                                        webViewRef?.evaluateJavascript(
                                            "(function(){ return document.body.innerText; })();"
                                        ) { rawBody ->
                                            scope.launch {
                                                val unescaped = rawBody
                                                    ?.trim('"')
                                                    ?.replace("\\\"", "\"")
                                                    ?.replace("\\n", "\n")
                                                    .orEmpty()
                                                val parsed = runCatching {
                                                    HautZfsoftParser.parseTimetableJson(unescaped)
                                                }.getOrDefault(emptyList())

                                                if (parsed.isNotEmpty()) {
                                                    val termId = currentTerm?.id ?: "2026-2027-1"
                                                    diffPreview = repository.previewCourseImportDiff(
                                                        termId = termId,
                                                        source = CourseSource.OFFICIAL_JW,
                                                        candidates = parsed
                                                    )
                                                    statusBanner = "从当前教务会话解析到 ${parsed.size} 条排课，请确认差异预览。"
                                                } else {
                                                    statusBanner = "当前页面未检测到标准 kbList JSON（请先在教务内打开个人课表接口或使用模拟同步验证）。"
                                                }
                                            }
                                        }
                                    }
                                ) {
                                    Text("解析当前会话课表")
                                }
                                OutlinedButton(
                                    onClick = {
                                        HautSessionManager.clearAuthorizedSessionCookies()
                                        statusBanner = "已清除 WebView 教务授权 Cookie 会话。"
                                    }
                                ) {
                                    Text("清除会话")
                                }
                            }

                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        webViewClient = WebViewClient()
                                        loadUrl(HautAcademicEndpoints.OFFICIAL_LOGIN_URL)
                                        webViewRef = this
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(340.dp)
                            )
                        }
                    }
                }

                2 -> {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("本地 CSV 课表解析与差异预览", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(
                                value = csvInputText,
                                onValueChange = { csvInputText = it },
                                minLines = 5,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        val termId = currentTerm?.id ?: "2026-2027-1"
                                        val candidates = HautZfsoftParser.parseCsvSchedule(csvInputText)
                                        diffPreview = repository.previewCourseImportDiff(
                                            termId = termId,
                                            source = CourseSource.CSV_ICS,
                                            candidates = candidates
                                        )
                                        statusBanner = "已解析 ${candidates.size} 条 CSV 课程，请确认下方差异预览。"
                                    }
                                }
                            ) {
                                Text("预览 CSV 导入差异")
                            }
                        }
                    }
                }
            }

            // 差异预览确认区
            val preview = diffPreview
            if (preview != null) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "导入差异明细预览（确认后才入库）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "新增 ${preview.addedCount} 项 · 更新 ${preview.modifiedCount} 项 · 用户修改冲突 ${preview.conflictUserEditCount} 项 · 无变化 ${preview.unchangedCount} 项",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )

                        for (entry in preview.entries) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
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
                                }
                            ) {
                                Text("确认提交入库")
                            }
                            OutlinedButton(onClick = { diffPreview = null }) {
                                Text("放弃本次导入")
                            }
                        }
                    }
                }
            }

            // 历史导入审计记录
            if (audits.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("最近导入审计记录", style = MaterialTheme.typography.titleMedium)
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
}

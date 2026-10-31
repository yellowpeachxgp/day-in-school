package cn.edu.haut.campus.feature.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.academic.HautSessionManager
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetThemeStyle
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val terms by repository.termsFlow.collectAsState(initial = emptyList())
    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

    var editingPeriod by remember { mutableStateOf<PeriodTime?>(null) }
    var showWipeConfirmDialog by remember { mutableStateOf(false) }
    var feedbackText by remember { mutableStateOf<String?>(null) }
    var widgetPrivacyDefault by remember { mutableStateOf(false) }
    var widgetCompactDefault by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置 · 学期节次 · 小组件与隐私") },
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
            if (feedbackText != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = feedbackText!!,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // 1. 学期管理与第一周开学日期配置
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("学期切换与第一教学周起始日", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (term in terms) {
                            FilterChip(
                                selected = term.id == currentTerm?.id,
                                onClick = {
                                    scope.launch {
                                        repository.selectCurrentTerm(term.id)
                                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    }
                                },
                                label = { Text(term.id) }
                            )
                        }
                    }
                    if (currentTerm != null) {
                        Text(
                            text = "当前学期：${currentTerm!!.displayName} · 第一教学周起始于 ${currentTerm!!.startDate}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. 桌面小组件全局/实例预设与主动刷新
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("桌面小组件配置与主动刷新", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "支持 2×2 下一节课 Mini（含 Chronometer 倒计时）、4×2 今日课程、4×4 日程时间轴、4×4 周课表。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("小组件紧凑布局（隐藏次要信息）", style = MaterialTheme.typography.bodyMedium)
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
                                    feedbackText = "已更新小组件紧凑度并广播刷新。"
                                }
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("小组件隐私模式（桌面隐藏具体课程名与教室）", style = MaterialTheme.typography.bodyMedium)
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
                                    feedbackText = "已切换小组件隐私模式并立即刷新桌面组件。"
                                }
                            }
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            feedbackText = "已向所有已安装的桌面小组件发送刷新指令。"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("立即同步刷新全部桌面小组件")
                    }
                }
            }

            // 3. 完整时间节次表编辑器（PRD 2.2：给用户完整时间节次表的编辑权）
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("作息节次时间表（点击任意节次可自定义起止时刻）", style = MaterialTheme.typography.titleMedium)
                    for (pt in periodTimes) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "第 ${pt.periodNo} 节 (${pt.sectionLabel})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            TextButton(onClick = { editingPeriod = pt }) {
                                Text("${pt.startLocalTime} — ${pt.endLocalTime} (修改)")
                            }
                        }
                    }
                }
            }

            // 4. 隐私与本地数据清除（PRD 5 & 11.D）
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("隐私保护与本地数据主权", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("GPA 与成绩页面防截屏/多任务模糊", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "开启后隐藏成绩数值并启用 Android FLAG_SECURE 阻止最近任务缩略图与截屏",
                                style = MaterialTheme.typography.labelMedium,
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    repository.resetToDemoSeedData()
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                    feedbackText = "已恢复河南工业大学莲花街校区演示课表与多学期成绩。"
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("恢复演示数据")
                        }

                        Button(
                            onClick = { showWipeConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("清除全部个人数据")
                        }
                    }
                }
            }
        }
    }

    val ptToEdit = editingPeriod
    if (ptToEdit != null) {
        var startIso by remember(ptToEdit) { mutableStateOf(ptToEdit.startLocalTime.toString()) }
        var endIso by remember(ptToEdit) { mutableStateOf(ptToEdit.endLocalTime.toString()) }

        AlertDialog(
            onDismissRequest = { editingPeriod = null },
            title = { Text("修改第 ${ptToEdit.periodNo} 节上下课时间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startIso,
                        onValueChange = { startIso = it },
                        label = { Text("开始时间 (HH:mm)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endIso,
                        onValueChange = { endIso = it },
                        label = { Text("结束时间 (HH:mm)") },
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
                                feedbackText = "已更新第 ${ptToEdit.periodNo} 节作息时间。"
                            }
                        }
                        editingPeriod = null
                    }
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

    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("确认彻底清除个人数据？") },
            text = {
                Text("将清空本地存储的所有个人课程、手动调课、成绩明细、教务授权 Cookie 会话及小组件配置。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            HautSessionManager.clearAuthorizedSessionCookies()
                            repository.wipeAllPersonalData()
                            WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                            showWipeConfirmDialog = false
                            feedbackText = "已彻底清除个人课表、成绩、教务会话与小组件缓存。"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
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

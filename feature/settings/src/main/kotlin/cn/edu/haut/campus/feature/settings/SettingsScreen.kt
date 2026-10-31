package cn.edu.haut.campus.feature.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.WidgetInstanceConfig
import cn.edu.haut.campus.core.model.WidgetThemeStyle
import cn.edu.haut.campus.core.model.WidgetType
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.launch
import java.time.LocalTime

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "设置与工作台管理",
            subtitle = "学期节次 · 桌面小组件 · 视觉与隐私主权",
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
            if (feedbackText != null) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                ) {
                    Text(
                        text = feedbackText!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // 1. 学期管理与第一周开学日期配置
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("学期切换与第一教学周起始日", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (term in terms) {
                        XPeachFilterPill(
                            label = term.id,
                            selected = term.id == currentTerm?.id,
                            onClick = {
                                scope.launch {
                                    repository.selectCurrentTerm(term.id)
                                    WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                                }
                            }
                        )
                    }
                }
                if (currentTerm != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "当前学期：${currentTerm!!.displayName} · 第一教学周起始于 ${currentTerm!!.startDate}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. 桌面小组件全局/实例预设与主动刷新
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("桌面小组件配置与主动刷新", style = MaterialTheme.typography.titleMedium)
                    XPeachStatusBadge(text = "4 款组件")
                }
                Spacer(modifier = Modifier.height(6.dp))
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
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("立即同步刷新全部桌面小组件")
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "一键添加桌面小组件（Android 12–16 原生 PinAppWidget）：",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                val pinTargets = listOf(
                    "2×2 下一节" to "cn.edu.haut.campus.widget.nextclass.NextClassAppWidgetProvider",
                    "4×2 今日" to "cn.edu.haut.campus.widget.today.TodayAppWidgetProvider",
                    "4×4 时间轴" to "cn.edu.haut.campus.widget.timeline.TimelineAppWidgetProvider",
                    "4×4 周课表" to "cn.edu.haut.campus.widget.week.WeekAppWidgetProvider"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for ((label, providerClass) in pinTargets) {
                        OutlinedButton(
                            onClick = {
                                val mgr = AppWidgetManager.getInstance(context)
                                val provider = ComponentName(context.packageName, providerClass)
                                if (mgr.isRequestPinAppWidgetSupported) {
                                    val ok = mgr.requestPinAppWidget(provider, null, null)
                                    feedbackText = if (ok) {
                                        "已发起「$label」小组件桌面固定请求，请在系统弹窗确认。"
                                    } else {
                                        "当前桌面启动器未响应固定请求，请长按手机桌面添加。"
                                    }
                                } else {
                                    feedbackText = "当前桌面启动器不支持一键添加，请长按桌面空白处添加小组件。"
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // 3. 完整时间节次表编辑器
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("作息节次时间表（点击任意节次可自定义起止时刻）", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
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

            // 4. 视觉、引导页与本地数据清除
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("视觉风格、首次引导与本地数据主权", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Android 12–16 Material You 壁纸动态取色", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "默认关闭以保持 XPeach 扁平化中性蓝灰设计系统；开启后跟随系统壁纸取色",
                            style = MaterialTheme.typography.labelMedium,
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

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                repository.resetOnboardingForGuide()
                                onBack()
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("重新进入欢迎引导页")
                    }

                    Button(
                        onClick = { showWipeConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清除全部个人数据")
                    }
                }
            }

            // 5. 版本号与最新 Android 平台支持信息
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                val pkgInfo = remember(context) {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                }
                Text("关于 · 版本管理与平台特性", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "应用版本：v${pkgInfo?.versionName ?: "0.2.0"} (Build ${pkgInfo?.longVersionCode ?: 200})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "视觉规范：XPeach Flat Design System (1dp Crisp Borders · Clean Neutral Surfaces)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "构建目标：CompileSdk 36 / TargetSdk 36 (Android 16) · MinSdk 31 (Android 12)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "当前设备：Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · 已启用 Edge-to-Edge 与预测式返回手势",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("确认彻底清除个人数据？") },
            text = {
                Text("将清空本地存储的所有个人课程、手动调课、成绩明细、教务授权 Cookie 会话及小组件配置，并回到首次欢迎引导界面。")
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
                            onBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("确认清除并重置")
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

package cn.edu.haut.campus.feature.settings

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilledTonalButton
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
            title = "设置",
            subtitle = "学期 · 小组件 · 作息时间",
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

            // 1. 学期管理
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("当前学期", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
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
                        text = "${currentTerm!!.displayName} · 第一周起始日 ${currentTerm!!.startDate}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. 桌面小组件（2×2 网格，杜绝4列挤压换行）
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("桌面小组件", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("紧凑显示", style = MaterialTheme.typography.bodyMedium)
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
                                feedbackText = "已更新小组件布局。"
                            }
                        }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("隐藏课程名与教室", style = MaterialTheme.typography.bodyMedium)
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
                                feedbackText = "已更新小组件隐私设置。"
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                fun requestPin(label: String, providerClass: String) {
                    val mgr = AppWidgetManager.getInstance(context)
                    val provider = ComponentName(context.packageName, providerClass)
                    if (mgr.isRequestPinAppWidgetSupported) {
                        val ok = mgr.requestPinAppWidget(provider, null, null)
                        feedbackText = if (ok) {
                            "请在系统弹窗中确认添加「$label」"
                        } else {
                            "请长按手机桌面空白处添加小组件"
                        }
                    } else {
                        feedbackText = "请长按手机桌面空白处添加小组件"
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            requestPin("2×2 下一节", "cn.edu.haut.campus.widget.nextclass.NextClassAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 2×2 下一节", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            requestPin("4×2 今日课程", "cn.edu.haut.campus.widget.today.TodayAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×2 今日", maxLines = 1, softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            requestPin("4×4 时间轴", "cn.edu.haut.campus.widget.timeline.TimelineAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×4 时间轴", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            requestPin("4×4 周课表", "cn.edu.haut.campus.widget.week.WeekAppWidgetProvider")
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("添加 4×4 周课表", maxLines = 1, softWrap = false)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                FilledTonalButton(
                    onClick = {
                        WidgetRefreshCoordinator.requestImmediateRefreshAll(context)
                        feedbackText = "已刷新全部桌面小组件。"
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("刷新小组件", maxLines = 1, softWrap = false)
                }
            }

            // 3. 作息节次表
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("作息时间", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                for (pt in periodTimes) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "第 ${pt.periodNo} 节 (${pt.sectionLabel})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { editingPeriod = pt }) {
                            Text(
                                text = "${pt.startLocalTime}–${pt.endLocalTime}",
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }

            // 4. 偏好与数据
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("偏好与数据", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "系统壁纸动态取色",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
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
                    Text(
                        text = "成绩页防截屏与模糊",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
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
                        Text("打开引导页", maxLines = 1, softWrap = false)
                    }

                    Button(
                        onClick = { showWipeConfirmDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("清除个人数据", maxLines = 1, softWrap = false)
                    }
                }
            }

            // 5. 版本信息
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                val pkgInfo = remember(context) {
                    runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("版本", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "v${pkgInfo?.versionName ?: "0.3.0"} (${pkgInfo?.longVersionCode ?: 300}) · Android ${Build.VERSION.RELEASE}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false
                    )
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
            title = { Text("第 ${ptToEdit.periodNo} 节时间") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startIso,
                        onValueChange = { startIso = it },
                        label = { Text("开始 (HH:mm)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = endIso,
                        onValueChange = { endIso = it },
                        label = { Text("结束 (HH:mm)") },
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
                                feedbackText = "已更新第 ${ptToEdit.periodNo} 节时间。"
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
            title = { Text("清除个人数据") },
            text = {
                Text("将清空本地课表、成绩、网页登录状态与小组件配置。")
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

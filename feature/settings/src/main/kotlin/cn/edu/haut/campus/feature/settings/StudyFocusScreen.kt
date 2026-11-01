/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.feature.settings

import androidx.compose.foundation.background
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.domain.FocusSessionEngine
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.reminder.focus.FocusCoordinator
import cn.edu.haut.campus.core.reminder.focus.FocusDeliveryTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val FOCUS_DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
fun StudyFocusScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusCoordinator = remember { FocusCoordinator.getInstance(context) }

    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val activeSession by repository.activeFocusSessionFlow.collectAsState(initial = null)
    val completedSessions by repository.completedFocusSessionsFlow.collectAsState(initial = emptyList())

    val currentTerm by repository.currentTermFlow.collectAsState(initial = null)
    val courses by repository.coursesFlow.collectAsState(initial = emptyList())
    val rules by repository.rulesFlow.collectAsState(initial = emptyList())
    val periodTimes by repository.periodTimesFlow.collectAsState(initial = emptyList())
    val calendarOverrides by repository.calendarOverridesFlow.collectAsState(initial = emptyList())
    val occurrenceOverrides by repository.occurrenceOverridesFlow.collectAsState(initial = emptyList())
    val buildings by repository.buildingsFlow.collectAsState(initial = emptyList())

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var customMinutesInput by remember(prefs.selectedFocusMinutes) {
        mutableStateOf(prefs.selectedFocusMinutes.toString())
    }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var diagnosticSummary by remember {
        mutableStateOf(FocusDeliveryTracker.buildDiagnosticSummary(context, FocusDeliveryTracker.KIND_TEST))
    }

    // 每秒驱动倒计时刷新，并在前台倒计时归零时自动完成当前专注会话
    LaunchedEffect(activeSession?.id, activeSession?.expectedEndAtEpochMs) {
        focusCoordinator.restoreOrCompleteActiveSession()
        while (isActive) {
            nowMs = System.currentTimeMillis()
            val running = activeSession
            if (running != null && nowMs >= running.expectedEndAtEpochMs) {
                focusCoordinator.completeFocusSessionIfDue(
                    sessionId = running.id,
                    expectedGeneration = running.generation,
                    expectedEndAtEpochMs = running.expectedEndAtEpochMs,
                    nowEpochMs = nowMs,
                    isRestorePath = false
                )
            }
            diagnosticSummary = FocusDeliveryTracker.buildDiagnosticSummary(
                context,
                FocusDeliveryTracker.KIND_TEST
            )
            delay(1_000L)
        }
    }

    val plannedMinutes = customMinutesInput.trim().toIntOrNull()?.coerceIn(1, 180)
        ?: prefs.selectedFocusMinutes

    // 计算今日实际排课与课前 20 分钟非强制提示
    val preClassAdvisory = remember(
        currentTerm,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings,
        plannedMinutes,
        nowMs / 30_000L
    ) {
        val term = currentTerm ?: return@remember null
        val today = Instant.ofEpochMilli(nowMs).atZone(HAUT_ZONE_ID).toLocalDate()
        val todayOccurrences = ScheduleEngine.resolveOccurrencesForDate(
            date = today,
            term = term,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings
        )
        FocusSessionEngine.evaluatePreClassAdvisory(
            occurrences = todayOccurrences,
            now = Instant.ofEpochMilli(nowMs),
            plannedFocusMinutes = plannedMinutes
        )
    }

    val todayDate = Instant.ofEpochMilli(nowMs).atZone(HAUT_ZONE_ID).toLocalDate()
    val todayCompletedMinutes = remember(completedSessions, todayDate) {
        completedSessions.sumOf { s ->
            val doneAt = s.completedAtEpochMs ?: s.expectedEndAtEpochMs
            val d = Instant.ofEpochMilli(doneAt).atZone(HAUT_ZONE_ID).toLocalDate()
            if (d == todayDate) s.durationMinutes else 0
        }
    }
    val totalCompletedMinutes = remember(completedSessions) {
        completedSessions.sumOf { it.durationMinutes }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "自习专注",
            subtitle = "倒计时与后台准时提醒",
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
            if (statusBanner != null) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                ) {
                    Text(
                        text = statusBanner!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // 1. 课前 20 分钟非强制提示卡（仅在接近上课时温和提示，不禁用开始按钮）
            if (preClassAdvisory != null) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    borderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "课前时间提醒",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        val recMin = preClassAdvisory.recommendedMaxFocusMinutes
                        if (recMin != null) {
                            TextButton(
                                onClick = {
                                    customMinutesInput = recMin.toString()
                                    scope.launch {
                                        repository.setSelectedFocusMinutes(recMin)
                                    }
                                }
                            ) {
                                Text("设为 ${recMin} 分钟")
                            }
                        }
                    }
                    Text(
                        text = preClassAdvisory.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // 2. 专注计时主卡片
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                val running = activeSession
                if (running != null) {
                    val remSeconds = (running.remainingMillis(nowMs) / 1_000L).coerceAtLeast(0L)
                    val mm = remSeconds / 60L
                    val ss = remSeconds % 60L

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "专注进行中 (${running.durationMinutes} 分钟)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        XPeachStatusBadge(
                            text = if (running.scheduleMode == cn.edu.haut.campus.core.model.ReminderAccuracyMode.EXACT) {
                                "精确闹钟"
                            } else {
                                "省电闹钟"
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "%02d:%02d".format(mm, ss),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    focusCoordinator.stopActiveFocusSession()
                                    statusBanner = "已结束本次自习专注。"
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("结束专注")
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "设定专注时长",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        XPeachStatusBadge(text = "今日已专注 $todayCompletedMinutes 分钟")
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (preset in listOf(15, 25, 45)) {
                            XPeachFilterPill(
                                label = "${preset} 分钟",
                                selected = plannedMinutes == preset,
                                onClick = {
                                    customMinutesInput = preset.toString()
                                    scope.launch {
                                        repository.setSelectedFocusMinutes(preset)
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customMinutesInput,
                            onValueChange = { customMinutesInput = it },
                            label = { Text("自定义时长 (1–180 分钟)") },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val mins = customMinutesInput.trim().toIntOrNull()
                                if (mins == null || mins !in 1..180) {
                                    statusBanner = "请输入 1 至 180 之间的整数分钟数。"
                                    return@Button
                                }
                                scope.launch {
                                    focusCoordinator.startFocusSession(mins)
                                    statusBanner = "已开启 $mins 分钟自习专注，到点将发送系统通知。"
                                }
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("开始专注")
                        }
                    }
                }
            }

            // 3. 10 秒后台送达诊断卡
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "后台提醒送达测试",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    FilledTonalButton(
                        onClick = {
                            focusCoordinator.triggerTenSecondTestAlarm()
                            diagnosticSummary = FocusDeliveryTracker.buildDiagnosticSummary(
                                context,
                                FocusDeliveryTracker.KIND_TEST
                            )
                            statusBanner = "已注册 10 秒后测试闹钟，可立即切回桌面等待通知。"
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("10 秒后台测试")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = diagnosticSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 4. 专注统计与最近完成记录
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "专注记录（累计 $totalCompletedMinutes 分钟 · ${completedSessions.size} 次）",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (completedSessions.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    focusCoordinator.clearAllFocusData()
                                    statusBanner = "已清空专注历史记录。"
                                }
                            }
                        ) {
                            Text("清空")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (completedSessions.isEmpty()) {
                    Text(
                        text = "暂无已完成的专注记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (item in completedSessions.take(10)) {
                            val endMs = item.completedAtEpochMs ?: item.expectedEndAtEpochMs
                            val timeText = Instant.ofEpochMilli(endMs)
                                .atZone(HAUT_ZONE_ID)
                                .format(FOCUS_DATE_TIME_FMT)
                            XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "完成 ${item.durationMinutes} 分钟自习专注",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = timeText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

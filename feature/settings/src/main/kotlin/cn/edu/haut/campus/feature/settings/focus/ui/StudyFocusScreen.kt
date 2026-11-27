/*
 * Adapted in part from HAUTPocket (https://github.com/rainthink-vanilla/HAUTPocket)
 * Commit: aa83d6f7200ebde4a816e91191f19d9990563362
 * Copyright (c) 2026 HAUTPocket contributors
 * Licensed under the MIT License (see licenses/HAUTPocket-MIT.txt).
 */
package cn.edu.haut.campus.feature.settings.focus.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.domain.FocusSessionEngine
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.todayFocusMinutes
import cn.edu.haut.campus.core.model.totalFocusMinutes
import cn.edu.haut.campus.feature.settings.focus.state.StudyFocusUiAction
import cn.edu.haut.campus.feature.settings.focus.state.StudyFocusUiState
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val FOCUS_DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
internal fun StudyFocusScreenContent(
    state: StudyFocusUiState,
    onBack: () -> Unit,
    onAction: (StudyFocusUiAction) -> Unit = {}
) {
    val prefs = state.prefs
    val activeSession = state.activeSession
    val completedSessions = state.completedSessions
    val currentTerm = state.currentTerm
    val courses = state.courses
    val rules = state.rules
    val periodTimes = state.periodTimes
    val calendarOverrides = state.calendarOverrides
    val occurrenceOverrides = state.occurrenceOverrides
    val buildings = state.buildings
    val nowMs = state.nowMs
    val customMinutesInput = state.customMinutesInput
    val statusBanner = state.statusBanner
    val diagnosticSummary = state.diagnosticSummary

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
        completedSessions.todayFocusMinutes(todayDate)
    }
    val totalCompletedMinutes = remember(completedSessions) {
        completedSessions.totalFocusMinutes()
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (statusBanner != null) {
                XPeachCard(
                    containerColor = XPeachTheme.secondaryAccentCardColor
                ) {
                    Text(
                        text = statusBanner!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // 1. 课前 20 分钟非强制提示卡（仅在接近上课时温和提示，不禁用开始按钮）
            if (preClassAdvisory != null) {
                XPeachCard(
                    containerColor = XPeachTheme.tertiaryAccentCardColor
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
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val recMin = preClassAdvisory.recommendedMaxFocusMinutes
                        if (recMin != null) {
                            TextButton(
                                onClick = {
                                    onAction(StudyFocusUiAction.SetSelectedFocusMinutes(recMin))
                                }
                            ) {
                                Text("设为 ${recMin} 分钟")
                            }
                        }
                    }
                    Text(
                        text = preClassAdvisory.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        XPeachStatusDotText(
                            text = if (running.scheduleMode == cn.edu.haut.campus.core.model.ReminderAccuracyMode.EXACT) {
                                "精确闹钟"
                            } else {
                                "省电闹钟"
                            },
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "%02d:%02d".format(mm, ss),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = XPeachTheme.readablePrimary,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onAction(StudyFocusUiAction.StopActiveFocusSession)
                            },
                            shape = RoundedCornerShape(12.dp),
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
                        XPeachStatusDotText(
                            text = "今日已专注 $todayCompletedMinutes 分钟",
                            dotColor = XPeachTheme.readablePrimary
                        )
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
                                    onAction(StudyFocusUiAction.SetSelectedFocusMinutes(preset))
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
                            onValueChange = { onAction(StudyFocusUiAction.UpdateCustomMinutesInput(it)) },
                            label = { Text("自定义时长 (1–180 分钟)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val mins = customMinutesInput.trim().toIntOrNull()
                                if (mins == null || mins !in 1..180) {
                                    onAction(StudyFocusUiAction.ShowInvalidMinutesError)
                                    return@Button
                                }
                                onAction(StudyFocusUiAction.StartFocusSession(mins))
                            },
                            shape = RoundedCornerShape(12.dp)
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
                            onAction(StudyFocusUiAction.TriggerTenSecondTestAlarm)
                        },
                        shape = RoundedCornerShape(12.dp)
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
                                onAction(StudyFocusUiAction.ClearAllFocusData)
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
                    Column(modifier = Modifier.fillMaxWidth()) {
                        completedSessions.take(10).forEachIndexed { idx, item ->
                            val endMs = item.completedAtEpochMs ?: item.expectedEndAtEpochMs
                            val timeText = Instant.ofEpochMilli(endMs)
                                .atZone(HAUT_ZONE_ID)
                                .format(FOCUS_DATE_TIME_FMT)
                            if (idx > 0) {
                                XPeachInsetDivider(verticalPadding = 8.dp)
                            }
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

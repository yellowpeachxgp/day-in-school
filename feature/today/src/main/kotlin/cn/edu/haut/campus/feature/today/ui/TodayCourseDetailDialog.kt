package cn.edu.haut.campus.feature.today.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.ReminderPlanner
import cn.edu.haut.campus.core.domain.ReminderPolicyEngine
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.SnoozeOptionEvaluation
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import java.time.Instant
import cn.edu.haut.campus.feature.today.state.TodayUiAction
import cn.edu.haut.campus.feature.today.state.TodayWriteCompletion
import cn.edu.haut.campus.feature.today.state.TodayWriteKind

@Composable
internal fun CourseDetailAndSnoozeDialog(
    detailState: TimelineOccurrenceState,
    termId: String,
    snoozeOptions: List<SnoozeOptionEvaluation>,
    actionFeedback: String?,
    globalConfig: cn.edu.haut.campus.core.model.ReminderGlobalConfig,
    activeTask: ReminderTaskDomain?,
    overridesByScope: Map<String, ReminderEventOverride>,
    writeCompletions: Map<TodayWriteKind, TodayWriteCompletion>,
    onAction: (TodayUiAction) -> Unit,
    onNavigateToMapWithRoom: (String) -> Unit,
    onNavigateToTimetable: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val occ = detailState.occurrence
    val event = remember(termId, occ) {
        ReminderPlanner.projectClassOccurrenceToEvent(termId, occ)
    }
    val courseScopeKey = "COURSE:" + occ.courseId
    val courseOverride = overridesByScope[courseScopeKey]
    val effectivePolicy = remember(event, globalConfig, overridesByScope) {
        ReminderPolicyEngine.resolveEffectivePolicy(event, globalConfig, overridesByScope)
    }

    var cancelWriteActive by remember(detailState) { mutableStateOf(false) }
    var cancelWriteError by remember(detailState) { mutableStateOf<String?>(null) }
    val cancelWriteCompletion = if (cancelWriteActive) {
        writeCompletions[TodayWriteKind.CancelClassOccurrence]
    } else {
        null
    }
    LaunchedEffect(cancelWriteCompletion?.id, detailState) {
        val completion = cancelWriteCompletion
        if (cancelWriteActive && completion != null) {
            cancelWriteActive = false
            if (completion.isSuccess) {
                onDismiss()
            } else {
                cancelWriteError = completion.errorMessage ?: "写入失败，请重试"
            }
        }
    }
    val cancelWritePending = cancelWriteActive

    var hasLoadedSnoozeOptions by remember(detailState) { mutableStateOf(false) }
    LaunchedEffect(event, activeTask, effectivePolicy) {
        onAction(
            TodayUiAction.LoadSnoozeOptions(
                detailState = detailState,
                termId = termId,
                freshDialogLoad = !hasLoadedSnoozeOptions
            )
        )
        hasLoadedSnoozeOptions = true
    }

    XPeachAlertDialog(
        onDismissRequest = { if (!cancelWritePending) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !cancelWritePending,
            dismissOnClickOutside = !cancelWritePending
        ),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(occ.courseName, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "${occ.formatTimeOrPeriodRange(TIME_FMT)} · ${occ.periodRangeText}",
                    style = MaterialTheme.typography.labelMedium,
                    color = XPeachTheme.readablePrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "状态：${detailState.statusText}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "教室：${occ.rawRoom}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!occ.teacher.isNullOrBlank()) {
                        Text(
                            text = "教师：${occ.teacher}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    occ.internalBreaksSummary?.let { brkSummary ->
                        Text(
                            text = "课间：$brkSummary",
                            color = XPeachTheme.readablePrimary,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                XPeachInsetDivider(verticalPadding = 2.dp)

                // 智能提醒与「稍后提醒」控制区
                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = if (effectivePolicy.enabled) {
                                        Icons.Default.NotificationsActive
                                    } else {
                                        Icons.Default.NotificationsOff
                                    },
                                    contentDescription = null,
                                    tint = if (effectivePolicy.enabled) {
                                        XPeachTheme.readablePrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "上课提醒",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            XPeachFilterPill(
                                label = if (effectivePolicy.enabled) "已开启" else "已关闭",
                                selected = effectivePolicy.enabled,
                                compact = true,
                                onClick = {
                                    val nextEnabled = !effectivePolicy.enabled
                                    onAction(
                                        TodayUiAction.ToggleCourseReminder(
                                            detailState = detailState,
                                            termId = termId,
                                            nextEnabled = nextEnabled,
                                            leadMinutesOverride = courseOverride?.leadMinutesOverride,
                                            maxSnoozesOverride = courseOverride?.maxSnoozesOverride
                                        )
                                    )
                                }
                            )
                        }

                        val statusSummary = when {
                            !effectivePolicy.enabled -> "已关闭提醒"
                            activeTask?.status == ReminderTaskStatus.SNOOZED -> {
                                val t = Instant.ofEpochMilli(activeTask.scheduledAtEpochMs)
                                    .atZone(HAUT_ZONE_ID).toLocalTime().format(TIME_FMT)
                                "将在 $t 再次提醒"
                            }
                            activeTask?.status == ReminderTaskStatus.FIRED -> {
                                "已提醒"
                            }
                            activeTask?.status == ReminderTaskStatus.PENDING -> {
                                val t = Instant.ofEpochMilli(activeTask.scheduledAtEpochMs)
                                    .atZone(HAUT_ZONE_ID).toLocalTime().format(TIME_FMT)
                                "将在 $t 提醒"
                            }
                            else -> "课前 ${effectivePolicy.classLeadMinutes} 分钟提醒"
                        }
                        Text(
                            text = statusSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 本门课提前分钟数快捷切换
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "提前时间：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false
                            )
                            for (lead in listOf(10, 15, 20, 30)) {
                                XPeachFilterPill(
                                    label = "${lead}分钟",
                                    selected = effectivePolicy.classLeadMinutes == lead,
                                    compact = true,
                                    onClick = {
                                        onAction(
                                            TodayUiAction.SetCourseReminderLead(
                                                detailState = detailState,
                                                termId = termId,
                                                leadMinutes = lead,
                                                maxSnoozesOverride = courseOverride?.maxSnoozesOverride
                                            )
                                        )
                                    }
                                )
                            }
                        }

                        if (detailState.status != ClassStatus.FINISHED && snoozeOptions.isNotEmpty()) {
                            Text(
                                text = "稍后提醒：",
                                style = MaterialTheme.typography.labelSmall,
                                color = XPeachTheme.readablePrimary,
                                fontWeight = FontWeight.Medium
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (opt in snoozeOptions) {
                                    val targetText = opt.targetInstant.atZone(HAUT_ZONE_ID)
                                        .toLocalTime()
                                        .format(TIME_FMT)
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable(enabled = opt.available) {
                                                    onAction(
                                                        TodayUiAction.SnoozeCourseReminder(
                                                            detailState = detailState,
                                                            termId = termId,
                                                            choice = opt.choice
                                                        )
                                                    )
                                            },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (opt.available) {
                                            XPeachTheme.primaryAccentCardColor
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Snooze,
                                                    contentDescription = null,
                                                    tint = if (opt.available) {
                                                        XPeachTheme.readablePrimary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    },
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = "${opt.label} ($targetText)",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = if (opt.available) {
                                                        MaterialTheme.colorScheme.onSurface
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                                    }
                                                )
                                            }
                                            Text(
                                                text = if (opt.available) "选择" else (opt.disabledReason?.userMessage ?: "不可选"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (opt.available) {
                                                    XPeachTheme.readablePrimary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        actionFeedback?.let { feedback ->
                            Text(
                                text = feedback,
                                style = MaterialTheme.typography.labelMedium,
                                color = XPeachTheme.readablePrimary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        cancelWriteError?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { onNavigateToMapWithRoom(occ.rawRoom) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("路线导航", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(
                        onClick = {
                            cancelWriteActive = true
                            cancelWriteError = null
                            onAction(TodayUiAction.CancelClassOccurrence(detailState, termId))
                        },
                        enabled = !cancelWritePending,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        val isTodayOcc = occ.date == LocalTodayClock.current.now().toLocalDate()
                        Text(
                            text = if (cancelWritePending) {
                                "停课写入中…"
                            } else if (isTodayOcc) {
                                "今日停课"
                            } else {
                                "本节停课"
                            },
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    OutlinedButton(
                        onClick = onNavigateToTimetable,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("调课/管理", maxLines = 1, softWrap = false)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !cancelWritePending
            ) {
                Text("关闭")
            }
        }
    )
}

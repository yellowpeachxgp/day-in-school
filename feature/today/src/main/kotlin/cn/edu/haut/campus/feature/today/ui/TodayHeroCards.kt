package cn.edu.haut.campus.feature.today.ui

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.database.isPendingPickup
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachSwitch
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.AggregatedDishRating
import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.CanteenDishReview
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.ClassEndMealRecommendation
import cn.edu.haut.campus.core.domain.ReminderPlanner
import cn.edu.haut.campus.core.domain.ReminderPolicyEngine
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.ClassStatus
import cn.edu.haut.campus.core.model.CourseScheduleRule
import cn.edu.haut.campus.core.model.ExamScheduleItem
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.OccurrenceOverride
import cn.edu.haut.campus.core.model.PresetCampusCommunityCatalog
import cn.edu.haut.campus.core.model.ReminderEventOverride
import cn.edu.haut.campus.core.model.ReminderKind
import cn.edu.haut.campus.core.model.ReminderTaskDomain
import cn.edu.haut.campus.core.model.ReminderTaskStatus
import cn.edu.haut.campus.core.model.ScheduleBreakInterval
import cn.edu.haut.campus.core.model.SnoozeOptionEvaluation
import cn.edu.haut.campus.core.model.SnoozeOutcome
import cn.edu.haut.campus.core.model.Term
import cn.edu.haut.campus.core.model.TermDateStatus
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import cn.edu.haut.campus.feature.today.state.TodayScreenStatus
import cn.edu.haut.campus.feature.today.state.TodayUiAction
import cn.edu.haut.campus.feature.today.state.TodayUiState

@Composable
internal fun TodayExpressPickupHeroCard(
    pendingPickups: List<cn.edu.haut.campus.core.database.ParcelItemEntity>,
    onMarkPickedUp: (String) -> Unit,
    onMarkStationAllPickedUp: (String) -> Unit,
    onSyncParcels: () -> Unit,
    onOpenParcelHub: () -> Unit
) {
    val groupedByStation = remember(pendingPickups) {
        pendingPickups.groupBy {
            it.stationName.cleanUiText() ?: "快递驿站"
        }
    }

    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.primaryAccentCardColor,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.42f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    XPeachStatusBadge(
                        text = "待取 ${pendingPickups.size} 件",
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = "待取快递",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    XPeachFilterPill(
                        label = "同步",
                        selected = false,
                        compact = true,
                        onClick = onSyncParcels
                    )
                    XPeachFilterPill(
                        label = "全部快递",
                        selected = true,
                        compact = true,
                        onClick = onOpenParcelHub
                    )
                }
            }

            groupedByStation.entries.forEachIndexed { stationIdx, (stationName, stationParcels) ->
                if (stationIdx > 0) {
                    XPeachInsetDivider(verticalPadding = 4.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📍 $stationName (${stationParcels.size}件)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = XPeachTheme.readablePrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (stationParcels.size > 1 && stationParcels.first().stationName.cleanUiText() != null) {
                            XPeachFilterPill(
                                label = "全部已取",
                                selected = false,
                                compact = true,
                                onClick = { onMarkStationAllPickedUp(stationName) }
                            )
                        }
                    }

                    stationParcels.take(4).forEachIndexed { parcelIdx, parcel ->
                        if (parcelIdx > 0) {
                            XPeachInsetDivider(verticalPadding = 2.dp)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onOpenParcelHub)
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = parcel.pickupCode.cleanUiText() ?: "暂未出码",
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = XPeachTheme.readablePrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val carrier = parcel.carrierName.cleanUiText() ?: "快递"
                                val desc = parcel.itemTitle.cleanUiText()
                                    ?: "尾号 ${parcel.trackingNumber.takeLast(6)}"
                                Text(
                                    text = "$carrier · $desc",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            XPeachFilterPill(
                                label = "已取件",
                                selected = true,
                                compact = true,
                                onClick = { onMarkPickedUp(parcel.id) }
                            )
                        }
                    }

                    if (stationParcels.size > 4) {
                        Text(
                            text = "其余 ${stationParcels.size - 4} 件 ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary,
                            modifier = Modifier.clickable(onClick = onOpenParcelHub)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TodayLibrarySeatDynamicCard(
    activePlans: List<cn.edu.haut.campus.core.database.LibraryBookingPlanEntity>,
    onOpenLibrarySeats: () -> Unit,
    onStopDelegation: (String) -> Unit
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.secondaryAccentCardColor,
        borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.40f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
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
                    XPeachStatusBadge(
                        text = when {
                            activePlans.any { it.status in setOf("CONFIRMED", "DELEGATION_STOPPED_SCHOOL_CONFIRMED") } -> "已预约"
                            activePlans.any { it.status in setOf("RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN", "CANCEL_RESULT_UNKNOWN") } -> "待核实"
                            activePlans.any { it.status == "NEEDS_RECONFIRMATION" } -> "待确认授权"
                            else -> "待预约"
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = "座位预约",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                XPeachFilterPill(
                    label = "选座",
                    selected = false,
                    compact = true,
                    onClick = onOpenLibrarySeats
                )
            }

            activePlans.take(2).forEachIndexed { idx, plan ->
                XPeachInsetDivider(verticalPadding = if (idx == 0) 4.dp else 6.dp)
                val cleanArea = plan.preferredAreaName.cleanUiText() ?: "自习区"
                val firstSeat = plan.preferredSeatLabel.cleanUiText().orEmpty().split(",")
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() } ?: "自动选座"
                val statusLabel = when (plan.status) {
                    "CONFIRMED" -> "预约成功"
                    "DELEGATION_STOPPED_SCHOOL_CONFIRMED" -> "委托已停 · 学校预约已成功"
                    "RESULT_UNKNOWN", "DELEGATION_STOPPED_RESULT_UNKNOWN" -> "结果待核对"
                    "CANCEL_RESULT_UNKNOWN" -> "退座结果待核对"
                    "NEEDS_RECONFIRMATION" -> "需重新确认授权"
                    "EXECUTING" -> "正在执行"
                    "FINAL_CHECK_OK" -> "即将预约"
                    "PRECHECK_OK" -> "等待预约"
                    else -> "已设定时预约"
                }
                val engineLabel = if (plan.executionMode == "CLOUD_HOSTED") {
                    "云端预约"
                } else {
                    "本机预约"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "$cleanArea · $firstSeat 号座",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${plan.targetLocalDate} ${plan.targetLocalTime} · $engineLabel",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary
                        )
                        XPeachStatusDotText(
                            text = statusLabel,
                            dotColor = if (plan.status == "CONFIRMED") {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                XPeachTheme.readablePrimary
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        XPeachFilterPill(
                            label = "查看",
                            selected = true,
                            compact = true,
                            onClick = onOpenLibrarySeats
                        )
                        val isExecutingInFlight = plan.status == "EXECUTING" || plan.fireStatus.startsWith("DISPATCH_INTENT")
                        val canStopDelegationFromCard = isExecutingInFlight ||
                            plan.status in setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK", "NEEDS_RECONFIRMATION")
                        if (canStopDelegationFromCard) {
                            XPeachFilterPill(
                                label = if (isExecutingInFlight) "停止委托" else "取消委托",
                                selected = false,
                                compact = true,
                                onClick = { onStopDelegation(plan.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

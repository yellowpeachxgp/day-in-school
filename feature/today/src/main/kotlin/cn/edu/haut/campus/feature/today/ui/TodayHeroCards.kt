package cn.edu.haut.campus.feature.today.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme

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

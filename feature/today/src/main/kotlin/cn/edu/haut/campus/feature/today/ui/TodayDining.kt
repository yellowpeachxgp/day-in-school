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
internal fun TodayDiningRecommendHeroCard(
    recommendation: ClassEndMealRecommendation,
    onShuffle: () -> Unit,
    onOpenAllCanteens: () -> Unit,
    onNavigateToCanteen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val primary = recommendation.primaryCandidate
    val minsLeft = recommendation.minutesUntilClassEnd
    val badgeText = if (minsLeft != null && minsLeft > 0) {
        "距下课 ${minsLeft} 分钟"
    } else {
        "${recommendation.mealSlot.label}推荐"
    }

    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.primaryAccentCardColor,
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
        onClick = onOpenAllCanteens
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                XPeachStatusBadge(
                    text = badgeText,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = "下课去 · ${primary.profile.name} ★${primary.averageStars}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TextButton(onClick = onDismiss) {
                Text("收起", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = primary.reasonTag,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "推荐：${primary.recommendedDishes.joinToString(" · ")}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XPeachFilterPill(
                    label = "换一家",
                    selected = false,
                    compact = true,
                    onClick = onShuffle
                )
                XPeachFilterPill(
                    label = "打星与红黑榜",
                    selected = false,
                    compact = true,
                    onClick = onOpenAllCanteens
                )
            }
            XPeachFilterPill(
                label = "导航去${primary.profile.shortName}",
                selected = true,
                compact = true,
                onClick = { onNavigateToCanteen(primary.profile.name) }
            )
        }
    }
}

@Composable
internal fun CampusDiningRecommendationDialog(
    recommendation: ClassEndMealRecommendation,
    userReviews: List<CanteenDishReview>,
    mealRecommendEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onSaveReview: (
        canteenBuildingId: String,
        dishName: String,
        stallLocation: String,
        priceYuan: Int?,
        stars: Int,
        tags: List<String>,
        comment: String
    ) -> Unit,
    onDeleteReview: (canteenBuildingId: String, dishName: String) -> Unit,
    onShuffle: () -> Unit,
    onTestNotification: () -> Unit,
    onNavigateToCanteen: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val primary = recommendation.primaryCandidate
    val block = recommendation.block
    val minsLeft = recommendation.minutesUntilClassEnd

    var selectedDialogTab by remember { mutableIntStateOf(0) } // 0: 下课推荐, 1: 饭菜打星与红黑榜
    var selectedCanteenFilter by remember { mutableStateOf<String?>(null) }
    var editingDishTarget by remember { mutableStateOf<AggregatedDishRating?>(null) }
    var showCustomDishEditor by remember { mutableStateOf(false) }

    val aggregatedDishes = remember(userReviews, selectedCanteenFilter) {
        CampusDiningRecommender.aggregateDishRatings(
            userReviews = userReviews,
            canteenBuildingIdFilter = selectedCanteenFilter
        )
    }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("食堂推荐与饭菜评价")
                if (selectedDialogTab == 0) {
                    XPeachFilterPill(
                        label = "换个推荐",
                        selected = true,
                        compact = true,
                        onClick = onShuffle
                    )
                } else {
                    XPeachFilterPill(
                        label = "+ 写评价",
                        selected = true,
                        compact = true,
                        onClick = { showCustomDishEditor = true }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                XPeachSegmentedTabs(
                    items = listOf("下课推荐", "饭菜打星 (${userReviews.size})"),
                    selectedIndex = selectedDialogTab,
                    onSelect = { selectedDialogTab = it }
                )

                if (selectedDialogTab == 0) {
                    // 1. 当前首选推荐卡片
                    XPeachWell {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "首选 · ${primary.profile.name} ★${primary.averageStars}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                            XPeachStatusBadge(
                                text = if (minsLeft != null && minsLeft > 0) "距下课 ${minsLeft}m" else recommendation.mealSlot.label
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (block != null) {
                            Text(
                                text = "参考课段：${block.courseTitle}（${block.classroomText} · ${block.endTime.format(TIME_FMT)} 下课）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                        Text(
                            text = primary.reasonTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "推荐档口：${primary.recommendedDishes.joinToString(" / ")}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { onNavigateToCanteen(primary.profile.name) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Navigation,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("导航去${primary.profile.shortName}")
                            }
                            OutlinedButton(
                                onClick = {
                                    selectedCanteenFilter = primary.profile.buildingId
                                    selectedDialogTab = 1
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("饭菜打星")
                            }
                        }
                    }

                    // 2. 校区全部食堂对比
                    Text(
                        text = "全部食堂（按教室距离与你的打星偏好排序）",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    for (cand in recommendation.allCandidates) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable {
                                    selectedCanteenFilter = cand.profile.buildingId
                                    selectedDialogTab = 1
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${cand.profile.name} ★${cand.averageStars}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    cand.walkMinutes?.let { wm ->
                                        Text(
                                            text = "约 ${wm} 分钟",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = XPeachTheme.readablePrimary
                                        )
                                    }
                                }
                                Text(
                                    text = cand.profile.areaLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = cand.recommendedDishes.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            XPeachFilterPill(
                                label = "去这里",
                                selected = cand.profile.buildingId == primary.profile.buildingId,
                                compact = true,
                                onClick = { onNavigateToCanteen(cand.profile.name) }
                            )
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 4.dp)

                    // 3. 离下课 1 小时自动通知开关与测试
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "离下课 1 小时自动通知",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "距连续课段下课 60 分钟时弹窗推荐就近食堂",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        XPeachSwitch(
                            checked = mealRecommendEnabled,
                            onCheckedChange = onToggleEnabled
                        )
                    }

                    OutlinedButton(
                        onClick = onTestNotification,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("发送测试推荐通知")
                    }
                } else {
                    // Tab 1: 食堂饭菜红黑榜与打星系统
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        XPeachFilterPill(
                            label = "全部",
                            selected = selectedCanteenFilter == null,
                            compact = true,
                            onClick = { selectedCanteenFilter = null }
                        )
                        for (canteen in CampusDiningRecommender.CANTEENS) {
                            XPeachFilterPill(
                                label = canteen.shortName,
                                selected = selectedCanteenFilter == canteen.buildingId,
                                compact = true,
                                onClick = { selectedCanteenFilter = canteen.buildingId }
                            )
                        }
                    }

                    Text(
                        text = "打 4-5★ 将在下课前优先推荐，打 1-2★ 将自动从你的推荐中剔除",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    for (dish in aggregatedDishes) {
                        val myStars = dish.myReview?.stars
                        XPeachWell {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = dish.dishName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        dish.priceYuan?.let { p ->
                                            Text(
                                                text = "¥$p",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = XPeachTheme.readablePrimary
                                            )
                                        }
                                    }
                                    Text(
                                        text = "${dish.canteenShortName} · ${dish.stallLocation} · ${dish.totalReviewCount}人评价",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                XPeachStatusBadge(
                                    text = "★ ${dish.averageStars}",
                                    containerColor = if (dish.isAvoidedByUser) {
                                        XPeachTheme.dangerContainer
                                    } else {
                                        MaterialTheme.colorScheme.primaryContainer
                                    },
                                    contentColor = if (dish.isAvoidedByUser) {
                                        XPeachTheme.onDangerContainer
                                    } else {
                                        XPeachTheme.readablePrimary
                                    }
                                )
                            }

                            if (dish.tags.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = dish.tags.joinToString(" · ") { "#$it" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = XPeachTheme.readablePrimary
                                )
                            }

                            if (dish.displayComment.isNotBlank()) {
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = dish.displayComment,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 快捷 1-5 星打分栏 + 写短评按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    for (star in 1..5) {
                                        val isSelectedStar = myStars != null && star <= myStars
                                        Text(
                                            text = if (isSelectedStar) "★" else "☆",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelectedStar) {
                                                if (myStars <= 2) MaterialTheme.colorScheme.error else XPeachTheme.readablePrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                            },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable {
                                                    onSaveReview(
                                                        dish.canteenBuildingId,
                                                        dish.dishName,
                                                        dish.stallLocation,
                                                        dish.priceYuan,
                                                        star,
                                                        dish.myReview?.tags ?: if (star <= 2) listOf("避雷") else listOf("味道正"),
                                                        dish.myReview?.comment.orEmpty()
                                                    )
                                                }
                                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (myStars != null) {
                                        Text(
                                            text = if (myStars <= 2) "${myStars}★ 已避雷" else "我的 ${myStars}★",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (myStars <= 2) MaterialTheme.colorScheme.error else XPeachTheme.readablePrimary
                                        )
                                    }
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (dish.myReview != null) {
                                        TextButton(
                                            onClick = { onDeleteReview(dish.canteenBuildingId, dish.dishName) },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            Text("撤销", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    XPeachFilterPill(
                                        label = if (dish.myReview != null) "改评语" else "写评价",
                                        selected = false,
                                        compact = true,
                                        onClick = { editingDishTarget = dish }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )

    val targetDish = editingDishTarget
    if (targetDish != null || showCustomDishEditor) {
        DishReviewEditorDialog(
            initialDish = targetDish,
            defaultCanteenId = selectedCanteenFilter ?: primary.profile.buildingId,
            onDismiss = {
                editingDishTarget = null
                showCustomDishEditor = false
            },
            onConfirm = { canteenId, dishName, stall, price, stars, tags, comment ->
                onSaveReview(canteenId, dishName, stall, price, stars, tags, comment)
                editingDishTarget = null
                showCustomDishEditor = false
            }
        )
    }
}

@Composable
internal fun DishReviewEditorDialog(
    initialDish: AggregatedDishRating?,
    defaultCanteenId: String,
    onDismiss: () -> Unit,
    onConfirm: (
        canteenBuildingId: String,
        dishName: String,
        stallLocation: String,
        priceYuan: Int?,
        stars: Int,
        tags: List<String>,
        comment: String
    ) -> Unit
) {
    var canteenId by remember(initialDish, defaultCanteenId) {
        mutableStateOf(initialDish?.canteenBuildingId ?: defaultCanteenId)
    }
    var dishName by remember(initialDish) {
        mutableStateOf(initialDish?.dishName.orEmpty())
    }
    var stallLocation by remember(initialDish) {
        mutableStateOf(initialDish?.stallLocation.orEmpty())
    }
    var priceText by remember(initialDish) {
        mutableStateOf(initialDish?.priceYuan?.toString().orEmpty())
    }
    var stars by remember(initialDish) {
        mutableIntStateOf(initialDish?.myReview?.stars ?: 5)
    }
    var selectedTags by remember(initialDish) {
        mutableStateOf((initialDish?.myReview?.tags ?: initialDish?.tags.orEmpty()).toSet())
    }
    var comment by remember(initialDish) {
        mutableStateOf(initialDish?.myReview?.comment.orEmpty())
    }

    val starMeaning = when (stars) {
        1 -> "1★ 踩雷（下课不再推荐）"
        2 -> "2★ 一般（下课不再推荐）"
        3 -> "3★ 还行"
        4 -> "4★ 推荐（下课优先推荐）"
        else -> "5★ 强烈推荐（下课优先推荐）"
    }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialDish != null) "评价 · ${initialDish.dishName}" else "新增食堂菜品评价") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (initialDish == null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (canteen in CampusDiningRecommender.CANTEENS) {
                            XPeachFilterPill(
                                label = canteen.shortName,
                                selected = canteenId == canteen.buildingId,
                                compact = true,
                                onClick = { canteenId = canteen.buildingId }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = dishName,
                        onValueChange = { dishName = it },
                        label = { Text("菜品名称（如：二楼黄焖鸡米饭）") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = stallLocation,
                            onValueChange = { stallLocation = it },
                            label = { Text("楼层/档口") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            label = { Text("价格(元)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 1-5 星选择器
                XPeachWell {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (s in 1..5) {
                                Text(
                                    text = if (s <= stars) "★" else "☆",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = if (s <= stars) {
                                        if (stars <= 2) MaterialTheme.colorScheme.error else XPeachTheme.readablePrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { stars = s }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = starMeaning,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (stars <= 2) MaterialTheme.colorScheme.error else XPeachTheme.readablePrimary
                        )
                    }
                }

                // 快捷标签
                Text(
                    text = "快捷标签",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (tag in CampusDiningRecommender.QUICK_REVIEW_TAGS) {
                        val isTagSelected = tag in selectedTags
                        XPeachFilterPill(
                            label = tag,
                            selected = isTagSelected,
                            compact = true,
                            onClick = {
                                selectedTags = if (isTagSelected) selectedTags - tag else selectedTags + tag
                            }
                        )
                    }
                }

                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("简短评价（选填，如：肉量足、微辣刚好）") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalName = dishName.trim()
                    if (finalName.isNotEmpty()) {
                        onConfirm(
                            canteenId,
                            finalName,
                            stallLocation.trim(),
                            priceText.trim().toIntOrNull(),
                            stars,
                            selectedTags.toList(),
                            comment.trim()
                        )
                    }
                }
            ) {
                Text("保存评价")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

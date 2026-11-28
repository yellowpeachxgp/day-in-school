package cn.edu.haut.campus.feature.today.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import cn.edu.haut.campus.core.model.TimelineOccurrenceState
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * 今日页纯界面交互状态。与拆分前一致：随组合存在而存在，底栏切换或 Profile 切换
 * 离开组合后即重置；不进入 ViewModel，也不跨组合存活。
 */
internal class TodayInteractionState(initialNow: ZonedDateTime) {
    val currentNow = mutableStateOf(initialNow)
    val selectedDate = mutableStateOf<LocalDate>(initialNow.toLocalDate())
    val selectedOccurrenceForDetail = mutableStateOf<TimelineOccurrenceState?>(null)
    val showTermSwitcherDialog = mutableStateOf(false)
    val showCardCustomizerDialog = mutableStateOf(false)
    val showDiningDialog = mutableStateOf(false)
    val diningShuffleOffset = mutableIntStateOf(0)
    val dismissedMealBlockKey = mutableStateOf<String?>(null)
}

@Composable
internal fun rememberTodayInteractionState(initialNow: ZonedDateTime): TodayInteractionState =
    remember { TodayInteractionState(initialNow) }

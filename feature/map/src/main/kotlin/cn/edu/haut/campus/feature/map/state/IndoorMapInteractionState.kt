package cn.edu.haut.campus.feature.map.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisIndoorFloorPlan
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisIndoorRoomCategory
import cn.edu.haut.campus.core.location.HautOfficialGisClient

/**
 * 室内平面图的本地交互状态 holder。
 *
 * 这里只承接原对话框中的 Compose 本地状态与既有联动 effect，不接触 Repository，
 * 也不改变楼层推断、搜索命中和视口重置的触发时机。
 */
internal class IndoorMapInteractionState {
    var searchQuery by mutableStateOf("")
    var selectedCategoryFilter by mutableStateOf<GisIndoorRoomCategory?>(null)
    var zoom by mutableFloatStateOf(1.0f)
    var panOffset by mutableStateOf(Offset.Zero)

    fun resetViewport() {
        zoom = 1.0f
        panOffset = Offset.Zero
    }
}

@Composable
internal fun rememberIndoorMapInteractionState(
    selectedFloorIndex: Int,
    footprint: GisBuildingFootprint,
    floorPlan: GisIndoorFloorPlan?,
    selectedRoom: GisIndoorRoom?,
    onSelectFloor: (Int) -> Unit,
    onSelectRoom: (GisIndoorRoom?) -> Unit
): IndoorMapInteractionState {
    val state = remember { IndoorMapInteractionState() }

    // 切换楼层时自动重置视口到居中最佳比例
    LaunchedEffect(selectedFloorIndex, footprint.featureId) {
        state.resetViewport()
    }

    // 当在室内搜索框输入跨楼层房间号（例如在 1F 输入 32402）时，自动推断并切换至目标楼层
    LaunchedEffect(state.searchQuery) {
        val q = state.searchQuery.trim()
        if (q.length >= 3) {
            val inferredFloor = HautOfficialGisClient.inferFloorIndexFromRoomQuery(
                rawQuery = q,
                maxLevelIndex = footprint.maxLevelIndex
            )
            if (inferredFloor != null && inferredFloor != selectedFloorIndex) {
                onSelectFloor(inferredFloor)
            }
        }
    }

    // 当平面图加载完毕且存在室内搜索词时，自动匹配对应房间
    LaunchedEffect(floorPlan, state.searchQuery) {
        val q = state.searchQuery.trim()
        if (q.isNotBlank() && floorPlan != null) {
            val clean = HautOfficialGisClient.extractCleanRoomCode(q) ?: q
            val hit = floorPlan.rooms.firstOrNull {
                it.name.equals(clean, ignoreCase = true) ||
                    it.name.equals(q, ignoreCase = true) ||
                    it.name.contains(clean, ignoreCase = true)
            }
            if (hit != null && selectedRoom?.id != hit.id) {
                onSelectRoom(hit)
            }
        }
    }

    return state
}

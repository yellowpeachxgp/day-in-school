package cn.edu.haut.campus.feature.map.state

import cn.edu.haut.campus.core.model.Building

/** 一次性平台事件；Route 消费后立即清除，不进入可逆 UI 状态。 */
internal sealed interface MapUiEffect {
    val id: Long

    data class RequestLocationPermission(override val id: Long) : MapUiEffect
    data class CopyText(
        override val id: Long,
        val label: String,
        val text: String
    ) : MapUiEffect
    data class OpenUrl(override val id: Long, val url: String) : MapUiEffect
    data class OpenWalkingNavigation(override val id: Long, val building: Building) : MapUiEffect
    data class Navigate(override val id: Long, val destination: MapNavigationDestination) : MapUiEffect
}

internal enum class MapNavigationDestination {
    ELECTRICITY,
    EMPTY_CLASSROOMS,
    COMMUNITY,
    COMPETITIONS,
    SCHOOL_CENTER,
    HOSPITAL_PHARMACY
}

/** Route 使用的装配结果：不可变快照、动作处理器和待消费的一次性 effect。 */
internal data class MapScreenAssembly(
    val state: MapUiState,
    val onAction: (MapUiAction) -> Unit,
    val pendingEffect: MapUiEffect?,
    val consumeEffect: () -> Unit
)

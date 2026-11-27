package cn.edu.haut.campus.feature.map.ui

import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.InAppNavigationPlan
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.ResolvedClassroomLocation

/** 自定义建筑坐标气泡文本；格式与原内联实现逐字保持一致。 */
internal fun formatMapPinCoordinate(point: GeoPoint): String =
    "坐标：${"%.5f".format(point.latitude)}, ${"%.5f".format(point.longitude)}"

/** 地图卡片中的步行路线/教室精度摘要；只搬移字符串拼装，不改变文案。 */
internal fun formatMapNavigationSummary(
    targetBuildingName: String?,
    navigationPlan: InAppNavigationPlan?,
    selectedIndoorRoom: GisIndoorRoom?,
    resolution: ResolvedClassroomLocation?
): String = buildString {
    if (targetBuildingName != null && navigationPlan != null) {
        append("步行约 ${navigationPlan.estimatedWalkMinutes} 分钟 (${navigationPlan.estimatedWalkDistanceMeters}m)")
        if (selectedIndoorRoom != null) {
            append(" · ${selectedIndoorRoom.floorLabel} ${selectedIndoorRoom.name}")
        } else if (!resolution?.roomNumberHint.isNullOrBlank()) {
            val precisionNote = if (resolution?.status == BuildingMatchStatus.EXACT_VERIFIED) {
                "已定位至楼栋"
            } else {
                "片区近似待确认，非单栋/房间精确坐标"
            }
            append(" · 房间 ${resolution!!.roomNumberHint}（$precisionNote）")
        }
    } else {
        append("双指缩放/拖拽或点击右侧按钮调整视野")
    }
}

package cn.edu.haut.campus.feature.map.state

import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisMapStyleMode
import cn.edu.haut.campus.core.location.GisSearchResultItem
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.GeoPoint

/** 校园地图用户意图。UI 只发送动作，不直接写 Repository 或触碰平台 API。 */
internal sealed interface MapUiAction {
    data class SetFullScreenMap(val enabled: Boolean) : MapUiAction
    data class SetOfficialWebGisDialogVisible(val visible: Boolean) : MapUiAction
    data class SetHospitalPharmacyDialogVisible(val visible: Boolean) : MapUiAction
    data class SetIndoorSecondaryMapVisible(val visible: Boolean) : MapUiAction

    data class UpdateSearchQuery(val value: String) : MapUiAction
    data object ClearSearchQuery : MapUiAction
    data class SelectCategory(val category: CampusCategory?) : MapUiAction
    data class SelectGisSearchResult(
        val item: GisSearchResultItem,
        val openSecondaryIndoorMap: Boolean
    ) : MapUiAction

    data class SetSelectedBuilding(val building: Building?) : MapUiAction
    data class SetManualOriginBuilding(val building: Building?) : MapUiAction
    data class SetInAppNavigationActive(val active: Boolean) : MapUiAction
    data class SetExpandRouteSteps(val expanded: Boolean) : MapUiAction
    data class SetDrawingZoneMode(val enabled: Boolean) : MapUiAction
    data class UpdateDrawingZoneVertices(val vertices: List<GeoPoint>) : MapUiAction
    data class UpdateDrawingZoneBuildings(val buildings: List<Building>) : MapUiAction
    data class SetZoneAdaptationFeedback(val value: String?) : MapUiAction
    data class SetPendingPinPoint(val point: GeoPoint?) : MapUiAction
    data class SetHasLocationPermission(val granted: Boolean) : MapUiAction
    data class SetFollowingUser(val following: Boolean) : MapUiAction
    data class UpdateLocationRefreshEpoch(val value: Int) : MapUiAction
    data class UpdateMapCenterLatGcj(val value: Double) : MapUiAction
    data class UpdateMapCenterLonGcj(val value: Double) : MapUiAction
    data class UpdateMapZoom(val value: Float) : MapUiAction

    data class SelectBuilding(
        val building: Building,
        val clearIndoorSelection: Boolean = true,
        val centerOnBuilding: Boolean = false,
        val zoom: Float? = null,
        val fullScreenMap: Boolean? = null,
        val followUser: Boolean? = null,
        val activateNavigation: Boolean = true,
        val openIndoorSecondaryMap: Boolean = false
    ) : MapUiAction

    data class SelectCampus(val campusId: String) : MapUiAction
    data class SelectGisStyleMode(val mode: GisMapStyleMode) : MapUiAction

    data object StartCustomZoneDrawing : MapUiAction
    data object ToggleCustomZoneDrawing : MapUiAction
    data class UpdateDrawingZoneName(val value: String) : MapUiAction
    data class AddDrawingZoneVertex(val point: GeoPoint) : MapUiAction
    data object UndoDrawingZoneVertex : MapUiAction
    data object AddPendingPinAtCenter : MapUiAction
    data class UpdatePendingPinName(val value: String) : MapUiAction
    data object ConfirmPendingPin : MapUiAction
    data object DismissPendingPin : MapUiAction
    data object SaveCustomZone : MapUiAction

    data object PrepareZoneImportExport : MapUiAction
    data class SetZoneImportExportVisible(val visible: Boolean) : MapUiAction
    data class UpdateZoneAdaptationJsonInput(val value: String) : MapUiAction
    data object CopyZoneAdaptationJson : MapUiAction
    data object ClearCustomZones : MapUiAction
    data object ImportZoneAdaptationJson : MapUiAction

    data class ViewportChanged(
        val centerLatGcj: Double,
        val centerLonGcj: Double,
        val zoom: Float,
        val fromFullScreenMap: Boolean
    ) : MapUiAction
    data object ZoomIn : MapUiAction
    data object ZoomOut : MapUiAction
    data object ShowRouteOverview : MapUiAction
    data object LocateUser : MapUiAction
    data object FollowUser : MapUiAction
    data class LocationPermissionResult(val anyPermissionGranted: Boolean) : MapUiAction
    data object RefreshLocationPermission : MapUiAction

    data class SelectIndoorFloor(val floorIndex: Int) : MapUiAction
    data class SelectIndoorRoom(val room: GisIndoorRoom?) : MapUiAction
    data class ConfirmIndoorRoomForOutdoorRoute(val room: GisIndoorRoom) : MapUiAction
    data class ConfirmBuildingCandidate(val candidate: Building) : MapUiAction

    data class SelectOriginBuilding(val building: Building?) : MapUiAction
    data object ToggleRouteSteps : MapUiAction
    data object ToggleInAppNavigation : MapUiAction

    data object OpenExternalMap : MapUiAction
    data object OpenOfficialGisExternalBrowser : MapUiAction
    data class OpenUrl(val url: String) : MapUiAction

    data object NavigateToElectricity : MapUiAction
    data object NavigateToEmptyClassrooms : MapUiAction
    data object NavigateToCommunity : MapUiAction
    data object NavigateToCompetitions : MapUiAction
    data object NavigateToSchoolCenter : MapUiAction
    data object NavigateToHospitalPharmacy : MapUiAction
}

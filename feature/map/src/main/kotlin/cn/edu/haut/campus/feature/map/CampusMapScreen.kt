package cn.edu.haut.campus.feature.map

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.map.state.MapNavigationDestination
import cn.edu.haut.campus.feature.map.state.MapUiAction
import cn.edu.haut.campus.feature.map.state.MapUiEffect
import cn.edu.haut.campus.feature.map.state.rememberMapScreenAssembly
import cn.edu.haut.campus.feature.map.ui.CampusMapScreenContent

/**
 * 校园地图 Route：保持原公开入口签名不变，只负责装配状态、权限/生命周期与一次性平台 effect。
 */
@Composable
fun CampusMapScreen(
    repository: CampusRepository,
    initialClassroomQuery: String? = null,
    onNavigateToElectricity: () -> Unit = {},
    onNavigateToEmptyClassrooms: () -> Unit = {},
    onNavigateToCommunity: () -> Unit = {},
    onNavigateToCompetitions: () -> Unit = onNavigateToCommunity,
    onNavigateToSchoolCenter: () -> Unit = {},
    onNavigateToHospitalPharmacy: () -> Unit = {},
    activeSchool: SchoolProfile = remember { SchoolDirectory.hautProfile() },
    resetTrigger: Int = 0
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assembly = rememberMapScreenAssembly(
        repository = repository,
        initialClassroomQuery = initialClassroomQuery,
        activeSchool = activeSchool,
        resetTrigger = resetTrigger,
        context = context
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        assembly.onAction(MapUiAction.LocationPermissionResult(grants.values.any { it }))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                assembly.onAction(MapUiAction.RefreshLocationPermission)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = assembly.state.isFullScreenMap) {
        assembly.onAction(MapUiAction.SetFullScreenMap(false))
    }

    LaunchedEffect(assembly.pendingEffect) {
        when (val effect = assembly.pendingEffect) {
            null -> Unit
            is MapUiEffect.RequestLocationPermission -> {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
                assembly.consumeEffect()
            }
            is MapUiEffect.CopyText -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
                assembly.consumeEffect()
            }
            is MapUiEffect.OpenUrl -> {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(effect.url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                assembly.consumeEffect()
            }
            is MapUiEffect.OpenWalkingNavigation -> {
                CampusMapNavigator.openWalkingNavigation(context, effect.building)
                assembly.consumeEffect()
            }
            is MapUiEffect.Navigate -> {
                when (effect.destination) {
                    MapNavigationDestination.ELECTRICITY -> onNavigateToElectricity()
                    MapNavigationDestination.EMPTY_CLASSROOMS -> onNavigateToEmptyClassrooms()
                    MapNavigationDestination.COMMUNITY -> onNavigateToCommunity()
                    MapNavigationDestination.COMPETITIONS -> onNavigateToCompetitions()
                    MapNavigationDestination.SCHOOL_CENTER -> onNavigateToSchoolCenter()
                    MapNavigationDestination.HOSPITAL_PHARMACY -> onNavigateToHospitalPharmacy()
                }
                assembly.consumeEffect()
            }
        }
    }

    CampusMapScreenContent(
        state = assembly.state,
        onAction = assembly.onAction
    )
}

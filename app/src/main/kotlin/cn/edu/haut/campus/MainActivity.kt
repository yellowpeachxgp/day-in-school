package cn.edu.haut.campus

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.HautCampusTheme
import cn.edu.haut.campus.feature.grades.GradesScreen
import cn.edu.haut.campus.feature.importer.ImporterScreen
import cn.edu.haut.campus.feature.map.CampusMapScreen
import cn.edu.haut.campus.feature.settings.SettingsScreen
import cn.edu.haut.campus.feature.timetable.TimetableScreen
import cn.edu.haut.campus.feature.today.TodayScreen
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator

enum class PrimaryBottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    TODAY("today", "今天", Icons.Default.Schedule),
    TIMETABLE("timetable", "课表", Icons.Default.CalendarViewWeek),
    GRADES("grades", "学业", Icons.Default.Assessment),
    CAMPUS("map", "校园", Icons.Default.Map)
}

class MainActivity : ComponentActivity() {

    private var deepLinkTab by mutableStateOf<String?>(null)
    private var deepLinkRoomQuery by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractWidgetIntent(intent)
        val repository = CampusRepository.getInstance(applicationContext)

        setContent {
            val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())

            // GPA 与成绩防截屏/最近任务缩略图隐私保护（PRD 4.2）
            LaunchedEffect(prefs.gpaPrivacyShieldEnabled) {
                if (prefs.gpaPrivacyShieldEnabled) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            HautCampusTheme {
                HautCampusRootApp(
                    repository = repository,
                    externalTabRequest = deepLinkTab,
                    externalRoomQuery = deepLinkRoomQuery,
                    onConsumeExternalRequest = {
                        deepLinkTab = null
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractWidgetIntent(intent)
    }

    private fun extractWidgetIntent(intent: Intent?) {
        val tab = intent?.getStringExtra(WidgetRefreshCoordinator.EXTRA_NAVIGATE_TAB)
        val room = intent?.getStringExtra(WidgetRefreshCoordinator.EXTRA_RAW_ROOM)
        if (!tab.isNullOrBlank()) {
            deepLinkTab = tab
        }
        if (!room.isNullOrBlank()) {
            deepLinkRoomQuery = room
        }
    }
}

@Composable
fun HautCampusRootApp(
    repository: CampusRepository,
    externalTabRequest: String?,
    externalRoomQuery: String?,
    onConsumeExternalRequest: () -> Unit
) {
    var currentTab by rememberSaveable { mutableStateOf(PrimaryBottomTab.TODAY) }
    var overlayScreen by rememberSaveable { mutableStateOf<String?>(null) } // "importer" or "settings"
    var mapRoomQuery by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(externalTabRequest, externalRoomQuery) {
        if (externalTabRequest != null) {
            overlayScreen = null
            currentTab = PrimaryBottomTab.entries.firstOrNull { it.route == externalTabRequest }
                ?: PrimaryBottomTab.TODAY
            if (!externalRoomQuery.isNullOrBlank()) {
                mapRoomQuery = externalRoomQuery
            }
            onConsumeExternalRequest()
        }
    }

    when (overlayScreen) {
        "importer" -> {
            ImporterScreen(
                repository = repository,
                onBack = { overlayScreen = null }
            )
        }
        "settings" -> {
            SettingsScreen(
                repository = repository,
                onBack = { overlayScreen = null }
            )
        }
        else -> {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        for (tab in PrimaryBottomTab.entries) {
                            NavigationBarItem(
                                selected = currentTab == tab,
                                onClick = { currentTab = tab },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (currentTab) {
                        PrimaryBottomTab.TODAY -> TodayScreen(
                            repository = repository,
                            onNavigateToMapWithRoom = { room ->
                                mapRoomQuery = room
                                currentTab = PrimaryBottomTab.CAMPUS
                            },
                            onNavigateToImporter = { overlayScreen = "importer" },
                            onNavigateToSettings = { overlayScreen = "settings" }
                        )
                        PrimaryBottomTab.TIMETABLE -> TimetableScreen(
                            repository = repository,
                            onNavigateToImporter = { overlayScreen = "importer" },
                            onNavigateToMapWithRoom = { room ->
                                mapRoomQuery = room
                                currentTab = PrimaryBottomTab.CAMPUS
                            }
                        )
                        PrimaryBottomTab.GRADES -> GradesScreen(
                            repository = repository,
                            onNavigateToImporter = { overlayScreen = "importer" }
                        )
                        PrimaryBottomTab.CAMPUS -> CampusMapScreen(
                            repository = repository,
                            initialClassroomQuery = mapRoomQuery
                        )
                    }
                }
            }
        }
    }
}

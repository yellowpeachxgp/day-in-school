package cn.edu.haut.campus.feature.settings.root.home.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.academic.GitHubReleaseInfo
import cn.edu.haut.campus.core.academic.GitHubReleaseUpdater
import cn.edu.haut.campus.core.academic.HautSessionManager
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.database.isPendingPickup
import cn.edu.haut.campus.core.designsystem.PresetThemePalette
import cn.edu.haut.campus.core.designsystem.UiDesignStyle
import cn.edu.haut.campus.core.designsystem.WallpaperThemeEngine
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
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.GpaCalculator
import cn.edu.haut.campus.core.domain.ScheduleEngine
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CalendarOverride
import cn.edu.haut.campus.core.model.CalendarOverrideType
import cn.edu.haut.campus.core.model.CourseCategory
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.HomeCardModule
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.core.model.OfficialGpaSnapshot
import cn.edu.haut.campus.core.model.PeriodTime
import cn.edu.haut.campus.core.model.PresetLocalProxyClient
import cn.edu.haut.campus.core.model.PresetMirrorNode
import cn.edu.haut.campus.core.model.ProxyMode
import cn.edu.haut.campus.core.model.StudentProfile
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.core.model.ThemeVisualDefaults
import cn.edu.haut.campus.core.model.todayFocusMinutes
import cn.edu.haut.campus.core.model.totalFocusMinutes
import cn.edu.haut.campus.core.reminder.ReminderCoordinator
import cn.edu.haut.campus.core.reminder.ReminderPermissions
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import cn.edu.haut.campus.feature.settings.hub.ui.CampusHospitalPharmacyDialog
import cn.edu.haut.campus.feature.settings.root.ui.NetworkProxyAndCloudConfigSection
import cn.edu.haut.campus.feature.settings.root.ui.ProfileDisclaimerSection
import cn.edu.haut.campus.feature.settings.root.ui.ProfileMetricItem
import cn.edu.haut.campus.feature.settings.root.ui.StudentDigitalIdHeader
import cn.edu.haut.campus.feature.settings.root.ui.ThemePaletteAndCardLayoutSection
import cn.edu.haut.campus.feature.settings.root.update.SettingsUpdateRouteSection
import cn.edu.haut.campus.feature.settings.root.network.SettingsNetworkRouteSection
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiAction
import cn.edu.haut.campus.feature.settings.root.home.state.SettingsHomeUiState

private val AUDIT_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsHomeSectionContent(
    state: SettingsHomeUiState,
    activeSchool: SchoolProfile,
    onAction: (SettingsHomeUiAction) -> Unit,
    onNavigateToImporter: () -> Unit,
    onNavigateToElectricity: () -> Unit,
    onNavigateToLibraryCatalog: () -> Unit,
    onNavigateToLibrarySeats: () -> Unit,
    onNavigateToCloudSeatBooking: () -> Unit,
    onNavigateToFocus: () -> Unit,
    onNavigateToEmptyClassrooms: () -> Unit,
    onNavigateToCommunity: () -> Unit,
    onNavigateToCompetitions: () -> Unit,
    onNavigateToFeedbackChannels: () -> Unit,
    onNavigateToMoments: () -> Unit,
    onNavigateToSchoolCenter: () -> Unit,
    onNavigateToParcelHub: () -> Unit,
    onNavigateToXpeachCloud: () -> Unit,
    onShowImportHistory: () -> Unit
) {
    val studentProfile = state.studentProfile
    val terms = state.terms
    val currentTerm = state.currentTerm
    val courses = state.courses
    val rules = state.rules
    val attempts = state.attempts
    val periodTimes = state.periodTimes
    val calendarOverrides = state.calendarOverrides
    val occurrenceOverrides = state.occurrenceOverrides
    val buildings = state.buildings
    val importAudits = state.importAudits
    val examSchedules = state.examSchedules
    val prefs = state.prefs
    val activeFocusSession = state.activeFocusSession
    val completedFocusSessions = state.completedFocusSessions
    val onlineRooms = state.onlineRooms
    val utilityMeters = state.utilityMeters
    val utilityReadings = state.utilityReadings
    val libraryBookingPlans = state.libraryBookingPlans
    val cloudBinding = state.cloudBinding
    val parcelItems = state.parcelItems
    val extensionPlugins = state.extensionPlugins
    val isDynamicSyncing = state.isDynamicSyncing
    val isHautCampus = activeSchool.id == SchoolDirectory.HAUT_SCHOOL_ID
    val today = remember { ZonedDateTime.now(HAUT_ZONE_ID).toLocalDate() }
    val resolvedWeekInfo = remember(today, currentTerm, calendarOverrides) {
        currentTerm?.let { TeachingWeekCalculator.resolveDate(today, it, calendarOverrides) }
    }
    val currentTeachingWeek = resolvedWeekInfo?.teachingWeek ?: 1
    val thisWeekClassCount = remember(
        currentTeachingWeek,
        currentTerm,
        courses,
        rules,
        periodTimes,
        calendarOverrides,
        occurrenceOverrides,
        buildings
    ) {
        val activeTerm = currentTerm ?: return@remember 0
        val map = ScheduleEngine.resolveOccurrencesForTeachingWeek(
            teachingWeek = currentTeachingWeek,
            term = activeTerm,
            courses = courses,
            rules = rules,
            periodTimes = periodTimes,
            calendarOverrides = calendarOverrides,
            occurrenceOverrides = occurrenceOverrides,
            buildings = buildings
        )
        map.values.sumOf { it.size }
    }
    val officialSnapshot = remember(prefs.officialGpaValuePlain, prefs.officialGpaNote) {
        prefs.officialGpaValuePlain?.toBigDecimalOrNull()?.let { valBd ->
            OfficialGpaSnapshot(
                cumulativeGpa = valBd,
                fetchedAt = Instant.now(),
                fieldDefinitionNote = prefs.officialGpaNote ?: "教务系统平均绩点"
            )
        }
    }
    val latestTermId = remember(attempts) { attempts.firstOrNull()?.termId }
    val latestAcademicYear = remember(attempts) { attempts.firstOrNull()?.academicYear }
    val gpaReport = remember(attempts, latestTermId, latestAcademicYear, officialSnapshot) {
        GpaCalculator.calculateReport(
            attempts = attempts,
            currentTermId = latestTermId,
            currentAcademicYear = latestAcademicYear,
            officialSnapshot = officialSnapshot
        )
    }

                // =========================================================================
                // 一级页面：「我的」—— 融合浓缩学籍学业总览、常用服务内测中心与底部免责条款
                // =========================================================================
                val latestAudit = importAudits.firstOrNull()

                // 1. 融合数字学籍、核心学业指标、学分构成与本学期校历进度主卡
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    StudentDigitalIdHeader(
                        profile = studentProfile,
                        schoolLabel = activeSchool.displayName,
                        privacyMasked = prefs.gpaPrivacyShieldEnabled,
                        lastAudit = latestAudit,
                        onSwitchSchool = onNavigateToSchoolCenter,
                        onEditProfile = { onAction(SettingsHomeUiAction.ShowEditProfileDialog) },
                        onSyncNow = {
                            if (!isHautCampus) {
                                onNavigateToImporter()
                            } else if (state.prefs.hasSavedCredentials && !state.isDynamicSyncing) {
                                onAction(SettingsHomeUiAction.PerformDynamicSync)
                            } else {
                                onNavigateToImporter()
                            }
                        }
                    )

                    XPeachInsetDivider(verticalPadding = 10.dp)

                    val activeExamTermId = currentTerm?.id?.takeIf { it.isNotBlank() }
                        ?: cn.edu.haut.campus.core.model.AcademicTermResolver.defaultTermId()
                    val activeTermExamCount = remember(examSchedules, activeExamTermId) {
                        examSchedules.count { it.termId == activeExamTermId }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileMetricItem(
                            label = "本周课时",
                            value = "${thisWeekClassCount}节",
                            modifier = Modifier.weight(1f)
                        )
                        ProfileMetricItem(
                            label = "考试安排",
                            value = "${activeTermExamCount}场",
                            modifier = Modifier.weight(1f)
                        )
                        ProfileMetricItem(
                            label = "已获学分",
                            value = if (prefs.gpaPrivacyShieldEnabled) "**.*" else gpaReport.totalEarnedCredits.stripTrailingZeros().toPlainString(),
                            modifier = Modifier.weight(1f)
                        )
                        ProfileMetricItem(
                            label = "平均绩点",
                            value = if (prefs.gpaPrivacyShieldEnabled) "*.**" else (gpaReport.cumulativeGpa?.toPlainString() ?: "--"),
                            highlight = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (attempts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val categoryCredits = remember(attempts) {
                            val passed = attempts.filter { it.passed }.distinctBy { it.courseKey }
                            CourseCategory.entries.associateWith { cat ->
                                passed.filter { it.category == cat }
                                    .fold(BigDecimal.ZERO) { acc, item -> acc.add(item.credits) }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (cat in CourseCategory.entries) {
                                val cred = categoryCredits[cat] ?: BigDecimal.ZERO
                                val credText = if (prefs.gpaPrivacyShieldEnabled) "**" else cred.stripTrailingZeros().toPlainString()
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(vertical = 6.dp, horizontal = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${cat.label} $credText",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 10.dp)

                    val totalWeeks = (currentTerm?.weekCount ?: 20).coerceAtLeast(1)
                    val weekProgress = (currentTeachingWeek.toFloat() / totalWeeks.toFloat()).coerceIn(0.05f, 1f)
                    val examStartDate = currentTerm?.startDate?.plusWeeks(18)
                    val daysToExam = examStartDate?.let { ChronoUnit.DAYS.between(today, it) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = buildString {
                                append(currentTerm?.displayName ?: "当前学期")
                                append(" · 第 $currentTeachingWeek/$totalWeeks 周")
                                if (daysToExam != null && daysToExam > 0) {
                                    append(" · 距期末约 ${daysToExam} 天")
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        XPeachStatusDotText(
                            text = "进度 ${(weekProgress * 100).toInt()}%",
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { weekProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = XPeachTheme.readablePrimary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    if (terms.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (t in terms) {
                                val count = rules.count { it.termId == t.id }
                                XPeachFilterPill(
                                    label = "${t.displayName} ($count)",
                                    selected = t.id == currentTerm?.id,
                                    compact = true,
                                    onClick = {
                                        onAction(SettingsHomeUiAction.SelectCurrentTerm(t.id))
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. 融合个人校园服务与内测扩展中心卡
                val primaryOnlineRoom = onlineRooms.firstOrNull()
                val onlineDevices = remember(primaryOnlineRoom) {
                    primaryOnlineRoom?.let { decodeOnlineDevicesJson(it.devicesJson) }.orEmpty()
                }
                val primaryMeter = utilityMeters.firstOrNull()
                val latestReading = remember(primaryMeter, utilityReadings) {
                    primaryMeter?.let { m ->
                        utilityReadings
                            .filter { it.meterId == m.id }
                            .maxByOrNull { it.recordedAtEpochMs }
                    }
                }
                val roomSummaryTitle = when {
                    primaryOnlineRoom != null -> primaryOnlineRoom.roomName
                    primaryMeter != null -> "${primaryMeter.roomName} · ${primaryMeter.name}"
                    else -> "未绑定宿舍"
                }
                val roomSummaryValue = when {
                    onlineDevices.isNotEmpty() -> {
                        onlineDevices.joinToString(" · ") { it.balanceRaw.ifBlank { "在线水电" } }
                    }
                    latestReading != null && primaryMeter != null -> "剩余 ${latestReading.value}${primaryMeter.unit}"
                    else -> "绑定寝室水电"
                }
                val todayFocusMinutes = remember(completedFocusSessions, today) {
                    completedFocusSessions.todayFocusMinutes(today, HAUT_ZONE_ID)
                }
                val totalFocusMinutes = remember(completedFocusSessions) {
                    completedFocusSessions.totalFocusMinutes()
                }
                val waitingCount = parcelItems.count { it.isPendingPickup() }
                val peachFormatted = cloudBinding?.let {
                    "%.2f".format(it.peachBalanceMicropeach.toDouble() / 10_000.0)
                }
                val enabledPluginCount = extensionPlugins.count { it.status == "ENABLED" }

                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToParcelHub)
                            .padding(vertical = 2.dp),
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
                                text = "我的快递",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "取件码与物流进度",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (waitingCount > 0) {
                            XPeachStatusBadge(
                                text = "待取 $waitingCount 件",
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            XPeachStatusDotText(
                                text = "共 ${parcelItems.size} 件",
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }
                    }

                    if (isHautCampus) {
                        XPeachInsetDivider(verticalPadding = 8.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onNavigateToElectricity)
                                .padding(vertical = 2.dp),
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
                                    text = "宿舍水电 · $roomSummaryTitle",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = roomSummaryValue,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "水电详情 ›",
                                style = MaterialTheme.typography.labelMedium,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToFocus)
                            .padding(vertical = 2.dp),
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
                                text = if (activeFocusSession != null) {
                                    "自习专注 · 进行中（${activeFocusSession?.durationMinutes}分钟）"
                                } else {
                                    "自习专注"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "今日 ${todayFocusMinutes} 分钟 · 累计 ${totalFocusMinutes} 分钟",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "进入计时 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToMoments)
                            .padding(vertical = 2.dp),
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
                                text = "失物招领与二手教材",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "寻物招领 (${prefs.campusMomentsSnapshot.posts.size}) · 二手课本 (${prefs.campusMomentsSnapshot.bookListings.size}) · 防丢码",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "互助广场 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToCommunity)
                            .padding(vertical = 2.dp),
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
                                text = "合作社团与交流群",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "共 ${prefs.campusCommunityCatalog.clubs.size} 个社团 · 活动介绍与交流群",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "社团名录 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToFeedbackChannels)
                            .padding(vertical = 2.dp),
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
                                text = "用户交流群、QQ频道与公众号",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "问题反馈、防冒充官方声明与校内权威公众号检索",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "反馈与频道 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToCompetitions)
                            .padding(vertical = 2.dp),
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
                                text = "学科竞赛指南",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "共 ${prefs.campusCommunityCatalog.competitions.size} 项赛事 · 专业筛选与赛事官网",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "竞赛指南 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    val activeCloudOrLocalPlans = libraryBookingPlans.count {
                        it.status !in setOf("CANCELLED", "CONFIRMED", "FAILED", "EXPIRED", "SCHOOL_CANCELLED")
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToCloudSeatBooking)
                            .padding(vertical = 2.dp),
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
                                text = "图书馆选座与云端抢座",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (activeCloudOrLocalPlans > 0) {
                                    "当前有 $activeCloudOrLocalPlans 项待执行抢座计划 · 支持关机离线准点抢座"
                                } else {
                                    "07:00 云端离线准点自动抢座、连续多日连抢与网页选座"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (activeCloudOrLocalPlans > 0) {
                            XPeachStatusBadge(
                                text = "待执行 $activeCloudOrLocalPlans",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = XPeachTheme.readablePrimary
                            )
                        } else {
                            Text(
                                text = "云端抢座 ›",
                                style = MaterialTheme.typography.labelMedium,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onNavigateToXpeachCloud)
                            .padding(vertical = 2.dp),
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
                                text = "云账号与扩展",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (cloudBinding != null) {
                                    "已登录 ${cloudBinding?.displayName} · 已启用 $enabledPluginCount 个扩展"
                                } else {
                                    "登录 XPeach 云账号开启云端抢座与扩展功能"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (peachFormatted != null) {
                            XPeachStatusBadge(
                                text = "🍑 ${peachFormatted}",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = XPeachTheme.readablePrimary
                            )
                        } else {
                            Text(
                                text = "去登录 ›",
                                style = MaterialTheme.typography.labelMedium,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                    }

                    XPeachInsetDivider(verticalPadding = 8.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onShowImportHistory)
                            .padding(vertical = 2.dp),
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
                                text = "教务同步记录",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (latestAudit != null) {
                                    "最近同步 ${AUDIT_TIME_FMT.format(latestAudit.importedAt)} · ${courses.size} 门课"
                                } else {
                                    "尚未同步教务数据"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "记录 ›",
                            style = MaterialTheme.typography.labelMedium,
                            color = XPeachTheme.readablePrimary
                        )
                    }
                }

                // 3. 个人主页底部免责条款与内测说明
                ProfileDisclaimerSection()
    // 弹窗 1：编辑个人信息
    if (state.showEditProfileDialog) {
        var nameInput by remember(studentProfile) { mutableStateOf(studentProfile?.displayName ?: "") }
        var schoolIdInput by remember(studentProfile) { mutableStateOf(studentProfile?.schoolId ?: "") }
        var majorInput by remember(studentProfile) { mutableStateOf(studentProfile?.major ?: "") }
        var yearInput by remember(studentProfile) { mutableStateOf(studentProfile?.enrollmentYear?.toString() ?: "2023") }

        XPeachAlertDialog(
            onDismissRequest = { onAction(SettingsHomeUiAction.HideEditProfileDialog) },
            title = { Text("编辑个人信息") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("姓名 / 昵称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = schoolIdInput,
                        onValueChange = { schoolIdInput = it },
                        label = { Text("学号") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = majorInput,
                        onValueChange = { majorInput = it },
                        label = { Text("学院 / 专业") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    OutlinedTextField(
                        value = yearInput,
                        onValueChange = { yearInput = it },
                        label = { Text("入学年份 (如 2023)") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAction(
                                SettingsHomeUiAction.SaveStudentProfile(
                                    StudentProfile(
                                        id = studentProfile?.id ?: "stu_local",
                                        schoolId = schoolIdInput.trim().ifBlank { "未填写" },
                                        displayName = nameInput.trim().ifBlank { null },
                                        major = majorInput.trim().ifBlank { null },
                                        enrollmentYear = yearInput.trim().toIntOrNull()
                                    )
                                )
                            )
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(SettingsHomeUiAction.HideEditProfileDialog) }) {
                    Text("取消")
                }
            }
        )
    }

}

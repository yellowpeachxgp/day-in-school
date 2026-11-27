package cn.edu.haut.campus.feature.settings.root.update.ui

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
import cn.edu.haut.campus.feature.settings.root.ui.NetworkProxyAndCloudConfigSection
import cn.edu.haut.campus.feature.settings.root.ui.ProfileDisclaimerSection
import cn.edu.haut.campus.feature.settings.root.ui.ProfileMetricItem
import cn.edu.haut.campus.feature.settings.root.ui.StudentDigitalIdHeader
import cn.edu.haut.campus.feature.settings.root.ui.ThemePaletteAndCardLayoutSection
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
import java.util.UUID
import cn.edu.haut.campus.feature.settings.root.update.state.SettingsUpdateUiAction
import cn.edu.haut.campus.feature.settings.root.update.state.SettingsUpdateUiState
@Composable
internal fun SettingsUpdateSectionContent(
    state: SettingsUpdateUiState,
    onAction: (SettingsUpdateUiAction) -> Unit = {}
) {
            // 9. 版本更新
            XPeachCard(modifier = Modifier.fillMaxWidth()) {
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
                            text = "版本更新",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "检查新版本并下载安装包 · 当前线路：${state.prefs.networkProxyConfig.statusSummaryLabel()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.releaseInfo?.hasNewerVersion == true) {
                        XPeachStatusBadge(
                            text = "有新版 ${state.releaseInfo?.tagName}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    } else {
                        XPeachStatusDotText(
                            text = "v${state.currentInstalledVersion}",
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启动时自动检查更新", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "有新版本时弹窗提示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    XPeachSwitch(
                        checked = state.prefs.autoCheckUpdateEnabled,
                        onCheckedChange = { enabled ->
onAction(SettingsUpdateUiAction.SetAutoCheckUpdateEnabled(enabled))
                        }
                    )
                }

                XPeachInsetDivider(verticalPadding = 8.dp)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = state.releaseStatusMessage
                            ?: if (state.isCheckingRelease) "正在通过「${state.prefs.networkProxyConfig.statusSummaryLabel()}」检查新版本..."
                            else "当前版本：v${state.currentInstalledVersion} · Android ${Build.VERSION.RELEASE}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (state.releaseInfo?.hasNewerVersion == true) {
                            XPeachTheme.readablePrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )

                    state.releaseInfo?.let { info ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最新版本：${info.releaseTitle} (${info.tagName}) · 通道：${info.resolvedViaRoute}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            if (info.changelog.isNotBlank()) {
                                TextButton(
                                    onClick = { onAction(SettingsUpdateUiAction.ShowReleaseNotes) },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("查看更新日志", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        if (info.changelog.isNotBlank()) {
                            XPeachWell(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAction(SettingsUpdateUiAction.ShowReleaseNotes) }
                            ) {
                                Text(
                                    text = "更新内容（${info.tagName}）：",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = info.changelog,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 8,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (state.isDownloadingApk) {
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { state.apkDownloadProgress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = XPeachTheme.readablePrimary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = !state.isCheckingRelease && !state.isDownloadingApk,
                        onClick = { onAction(SettingsUpdateUiAction.CheckNow) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (state.isCheckingRelease) "正在检查..." else "检查更新"
                        )
                    }

                    Button(
                        enabled = !state.isDownloadingApk && !state.isCheckingRelease,
                        onClick = {
onAction(SettingsUpdateUiAction.DownloadOrInstall)
                        },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = when {
                                state.isDownloadingApk -> "正在下载..."
                                state.downloadedApkFile?.exists() == true -> "安装更新"
                                state.releaseInfo?.hasNewerVersion == true -> "下载新版本"
                                else -> "下载安装包"
                            }
                        )
                    }
                }
            }


    val dialogReleaseInfo = state.releaseInfo
    if (state.showReleaseNotesDialog && dialogReleaseInfo != null) {
        XPeachAlertDialog(
            onDismissRequest = { onAction(SettingsUpdateUiAction.HideReleaseNotes) },
            title = {
                Text(
                    text = if (dialogReleaseInfo.hasNewerVersion) {
                        "发现新版本 ${dialogReleaseInfo.tagName}"
                    } else {
                        "版本更新日志 ${dialogReleaseInfo.tagName}"
                    },
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (dialogReleaseInfo.hasNewerVersion) {
                            "当前版本 v${state.currentInstalledVersion} → 最新版本 ${dialogReleaseInfo.tagName}（${dialogReleaseInfo.resolvedViaRoute}）"
                        } else {
                            "当前版本 v${state.currentInstalledVersion} 已是最新（${dialogReleaseInfo.resolvedViaRoute}）"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = XPeachTheme.readablePrimary
                    )
                    XPeachWell(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = dialogReleaseInfo.changelog.ifBlank { "常规性能优化与体验改进。" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { onAction(SettingsUpdateUiAction.HideReleaseNotes) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (dialogReleaseInfo.hasNewerVersion) "去下载更新" else "知道了")
                }
            }
        )
}
}

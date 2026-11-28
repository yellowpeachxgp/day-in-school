package cn.edu.haut.campus.feature.settings.root.ui

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
import kotlin.math.abs

private val AUDIT_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NetworkProxyAndCloudConfigSection(
    proxyConfig: NetworkProxyConfig,
    onSaveProxyConfig: (NetworkProxyConfig) -> Unit,
    onResetProxyConfig: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var customPrefixInput by remember(proxyConfig.customReverseProxyPrefix) {
        mutableStateOf(proxyConfig.customReverseProxyPrefix)
    }
    var hostInput by remember(proxyConfig.proxyHost) {
        mutableStateOf(proxyConfig.proxyHost)
    }
    var portInput by remember(proxyConfig.proxyPort) {
        mutableStateOf(proxyConfig.proxyPort.toString())
    }
    var cloudEndpointInput by remember(proxyConfig.customCloudApiBaseUrl) {
        mutableStateOf(proxyConfig.customCloudApiBaseUrl)
    }
    var expandCloudEndpoint by remember { mutableStateOf(false) }
    var isTestingConnectivity by remember { mutableStateOf(false) }
    var connectivityMessage by remember { mutableStateOf<String?>(null) }
    var connectivityOk by remember { mutableStateOf<Boolean?>(null) }

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "网络与更新线路",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "设置更新下载线路与网络代理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            XPeachStatusDotText(
                text = proxyConfig.statusSummaryLabel(),
                dotColor = XPeachTheme.readablePrimary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 1. 代理类型横向胶囊切换
        Text(
            text = "代理类型",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (mode in ProxyMode.entries) {
                XPeachFilterPill(
                    label = mode.label,
                    selected = proxyConfig.mode == mode,
                    compact = true,
                    onClick = {
                        connectivityMessage = null
                        onSaveProxyConfig(proxyConfig.copy(mode = mode))
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = proxyConfig.mode.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        XPeachInsetDivider(verticalPadding = 10.dp)

        // 2. 根据代理类型展示对应配置面板
        when (proxyConfig.mode) {
            ProxyMode.PRESET_MIRROR -> {
                Text(
                    text = "预置加速镜像线路",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (rowNodes in PresetMirrorNode.entries.chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (node in rowNodes) {
                                val selected = proxyConfig.presetMirrorKey == node.key
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) XPeachTheme.readablePrimary.copy(alpha = 0.14f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                        )
                                        .border(
                                            width = if (selected) 1.5.dp else 0.6.dp,
                                            color = if (selected) XPeachTheme.readablePrimary else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            connectivityMessage = null
                                            onSaveProxyConfig(proxyConfig.copy(presetMirrorKey = node.key))
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = node.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (selected) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        text = node.prefixUrl.removePrefix("https://").trimEnd('/'),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ProxyMode.CUSTOM_REVERSE_PROXY -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "自定义镜像地址前缀",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = customPrefixInput,
                            onValueChange = { customPrefixInput = it },
                            label = { Text("镜像前缀 (如 https://gh.example.com/)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                val normalized = NetworkProxyConfig.normalizePrefixUrl(customPrefixInput)
                                customPrefixInput = normalized
                                connectivityMessage = null
                                onSaveProxyConfig(proxyConfig.copy(customReverseProxyPrefix = normalized))
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("保存", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }

            ProxyMode.HTTP_PROXY, ProxyMode.SOCKS_PROXY -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "常用代理客户端快捷填入",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (preset in PresetLocalProxyClient.entries) {
                            val isCurrent = proxyConfig.mode == preset.mode &&
                                proxyConfig.proxyHost == preset.host &&
                                proxyConfig.proxyPort == preset.port
                            XPeachFilterPill(
                                label = preset.label,
                                selected = isCurrent,
                                compact = true,
                                onClick = {
                                    hostInput = preset.host
                                    portInput = preset.port.toString()
                                    connectivityMessage = null
                                    onSaveProxyConfig(
                                        proxyConfig.copy(
                                            mode = preset.mode,
                                            proxyHost = preset.host,
                                            proxyPort = preset.port
                                        )
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            label = { Text("服务器地址") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.4f)
                        )
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("端口") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(0.9f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            val validPort = (portInput.toIntOrNull() ?: 7890).coerceIn(1, 65535)
                            val cleanHost = hostInput.trim().ifBlank { "127.0.0.1" }
                            hostInput = cleanHost
                            portInput = validPort.toString()
                            connectivityMessage = null
                            onSaveProxyConfig(
                                proxyConfig.copy(
                                    proxyHost = cleanHost,
                                    proxyPort = validPort
                                )
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("应用代理配置", maxLines = 1, softWrap = false)
                    }
                }
            }

            ProxyMode.DIRECT -> {
                Text(
                    text = "直接连接服务器，建议开启下方「自动切换备用线路」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        XPeachInsetDivider(verticalPadding = 8.dp)

        // 3. 自动回退镜像开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("自动切换备用线路", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "当前线路连接失败时自动尝试其他镜像",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            XPeachSwitch(
                checked = proxyConfig.autoFallbackMirror,
                onCheckedChange = { enabled ->
                    onSaveProxyConfig(proxyConfig.copy(autoFallbackMirror = enabled))
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 4. 云服务 API 网关端点配置（支持后续云同步与自建云服务）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("云服务地址", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = proxyConfig.customCloudApiBaseUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            XPeachFilterPill(
                label = if (expandCloudEndpoint) "收起" else "修改",
                selected = expandCloudEndpoint,
                compact = true,
                onClick = { expandCloudEndpoint = !expandCloudEndpoint }
            )
        }

        AnimatedVisibility(visible = expandCloudEndpoint) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = cloudEndpointInput,
                    onValueChange = { cloudEndpointInput = it },
                    label = { Text("服务器地址") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = {
                        val cleaned = cloudEndpointInput.trim().ifBlank { NetworkProxyConfig.DEFAULT_CLOUD_API_BASE_URL }
                        cloudEndpointInput = cleaned
                        onSaveProxyConfig(proxyConfig.copy(customCloudApiBaseUrl = cleaned))
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("保存", maxLines = 1, softWrap = false)
                }
            }
        }

        if (connectivityMessage != null) {
            XPeachInsetDivider(verticalPadding = 8.dp)
            Text(
                text = connectivityMessage.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = if (connectivityOk == true) {
                    XPeachTheme.readablePrimary
                } else {
                    MaterialTheme.colorScheme.error
                },
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. 连通性测速与恢复默认按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                enabled = !isTestingConnectivity,
                onClick = {
                    scope.launch {
                        isTestingConnectivity = true
                        connectivityMessage = "正在测试「${proxyConfig.statusSummaryLabel()}」连接..."
                        connectivityOk = true
                        val report = GitHubReleaseUpdater.testProxyConnectivity(proxyConfig)
                        isTestingConnectivity = false
                        connectivityOk = report.reachable
                        connectivityMessage = report.detailMessage
                    }
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = if (isTestingConnectivity) "正在测试..." else "测试连接"
                )
            }

            OutlinedButton(
                onClick = {
                    connectivityMessage = null
                    onResetProxyConfig()
                },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text("恢复默认")
            }
        }
    }
}


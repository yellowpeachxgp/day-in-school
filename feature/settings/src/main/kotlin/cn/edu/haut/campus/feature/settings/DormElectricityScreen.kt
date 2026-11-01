package cn.edu.haut.campus.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.campusservices.UtilityAccountReadResult
import cn.edu.haut.campus.core.campusservices.UtilitySamplingPolicy
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.designsystem.CampusMetricChart
import cn.edu.haut.campus.core.designsystem.CampusMetricPoint
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.UtilityMeterDomain
import cn.edu.haut.campus.core.model.UtilityReadingDomain
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

private val UTILITY_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
fun DormElectricityScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val prefs by repository.preferencesFlow.collectAsState(initial = AppPreferencesState())
    val onlineRooms by repository.utilityOnlineRoomsFlow.collectAsState(initial = emptyList())
    val dailySamples by repository.utilityDailySamplesFlow.collectAsState(initial = emptyList())
    val meters by repository.utilityMetersFlow.collectAsState(initial = emptyList())
    val readings by repository.utilityReadingsFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) } // 0: 在线水电与趋势, 1: 手工抄表台账
    var accountReadState by remember { mutableStateOf(repository.readUtilityAccountResult()) }
    var accountInput by remember { mutableStateOf("") }
    var isQuerying by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<String?>(null) }

    var showAddMeterDialog by remember { mutableStateOf(false) }
    var readingTargetMeter by remember { mutableStateOf<UtilityMeterDomain?>(null) }

    LaunchedEffect(Unit) {
        accountReadState = repository.readUtilityAccountResult()
        if (accountReadState is UtilityAccountReadResult.InvalidatedAndReset) {
            statusBanner = "安全密钥已重置，请重新输入本人校园卡账号。"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "宿舍水电",
            subtitle = "电费余量、每日采样与手工抄表",
            navigationIcon = {
                XPeachIconButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    onClick = onBack
                )
            }
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            XPeachSegmentedTabs(
                items = listOf("在线水电与每日趋势", "手工水电台账"),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            if (statusBanner != null) {
                XPeachCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    borderColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
                ) {
                    Text(
                        text = statusBanner!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            if (selectedTab == 0) {
                // 1. 账号与传输门控卡片
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "完美校园·智能水电只读查询",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        XPeachStatusBadge(
                            text = if (prefs.allowUnverifiedUtilityTransport) "已开启在线查询" else "待核验门控"
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "仅查询本人在完美校园已绑定的宿舍水电。请求采用 HTTPS POST 请求体传输，账号通过 Android Keystore 加密保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "允许发起智能水电在线请求",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "租户代码(43)需配合已绑房间账号实测；关闭时仅使用本地缓存或手工台账",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = prefs.allowUnverifiedUtilityTransport,
                                onCheckedChange = { allowed ->
                                    scope.launch {
                                        repository.setAllowUnverifiedUtilityTransport(allowed)
                                    }
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val activeAcc = accountReadState as? UtilityAccountReadResult.Active
                    if (activeAcc != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "当前保存账号：${activeAcc.maskedAccount}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        repository.clearUtilityAccountAndOnlineCache()
                                        accountReadState = repository.readUtilityAccountResult()
                                        accountInput = ""
                                        statusBanner = "已清除水电账号与在线房间缓存。"
                                    }
                                }
                            ) {
                                Text("清除账号与缓存")
                            }
                        }
                    }

                    OutlinedTextField(
                        value = accountInput,
                        onValueChange = { accountInput = it },
                        label = {
                            Text(
                                if (activeAcc != null) "输入新校园卡账号（留空则使用已保存账号）"
                                else "本人校园卡账号（通常为学号）"
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            enabled = !isQuerying && (accountInput.isNotBlank() || activeAcc != null),
                            onClick = {
                                scope.launch {
                                    isQuerying = true
                                    statusBanner = "正在查询已绑定房间水电..."
                                    val res = repository.saveAndRefreshOnlineUtilities(
                                        rawAccount = accountInput.takeIf { it.isNotBlank() },
                                        allowUnverified = prefs.allowUnverifiedUtilityTransport
                                    )
                                    accountReadState = repository.readUtilityAccountResult()
                                    if (accountInput.isNotBlank() && res.isSuccess) {
                                        accountInput = ""
                                    }
                                    isQuerying = false
                                    res.onSuccess { snapshots ->
                                        val okCount = snapshots.count { !it.failed }
                                        statusBanner = "查询完成：共读取 ${snapshots.size} 个绑定房间（$okCount 个成功）。"
                                    }.onFailure { err ->
                                        statusBanner = err.message ?: "查询失败，请检查网络或确认已在完美校园绑定房间"
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(if (isQuerying) "查询中..." else "查询已绑房间")
                        }
                        if (isQuerying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }

                // 2. 在线房间与每日趋势展示
                if (onlineRooms.isEmpty()) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "暂无在线房间快照",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "若已在完美校园绑定宿舍，开启上方开关并输入本人校园卡账号即可查询；也可切换至「手工水电台账」手动记录读数。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    for (room in onlineRooms) {
                        val devices = remember(room.devicesJson) {
                            decodeOnlineDevicesJson(room.devicesJson)
                        }
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = room.roomName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val timeStr = if (room.updatedAtEpochMs > 0L) {
                                        Instant.ofEpochMilli(room.updatedAtEpochMs)
                                            .atZone(HAUT_ZONE_ID)
                                            .format(UTILITY_TIME_FMT)
                                    } else {
                                        "—"
                                    }
                                    Text(
                                        text = if (room.failed) "最近刷新未成功 · 显示上次缓存 ($timeStr)"
                                        else "更新于 $timeStr",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (room.failed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 校区手动标记（不自动根据 customercode=43 猜测校区）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "所属校区：",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                for ((code, label) in listOf(
                                    "LIANHUA" to "莲花街校区",
                                    "SONGSHAN" to "嵩山路校区",
                                    null to "未标记"
                                )) {
                                    XPeachFilterPill(
                                        label = label,
                                        selected = room.campusCode == code,
                                        onClick = {
                                            scope.launch {
                                                repository.updateOnlineRoomCampus(room.roomId, code)
                                            }
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            if (devices.isEmpty()) {
                                Text(
                                    text = "该房间暂未返回水电表计明细",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (dev in devices) {
                                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "${dev.name} (${dev.category.displayLabel})",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                XPeachStatusBadge(text = dev.statusRaw)
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "当前剩余",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = dev.balanceRaw,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Column {
                                                    Text(
                                                        text = "今日用量",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = dev.todayUseRaw,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                                Column {
                                                    Text(
                                                        text = "累计购入",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = dev.totalBoughtRaw,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                }
                                            }

                                            val trendPoints = remember(dailySamples, room.roomId, dev.name) {
                                                UtilitySamplingPolicy.buildDailyTrendPoints(
                                                    samples = dailySamples,
                                                    roomId = room.roomId,
                                                    deviceName = dev.name
                                                ).map { pt ->
                                                    CampusMetricPoint(
                                                        label = pt.label,
                                                        value = pt.value,
                                                        unit = pt.unit,
                                                        subtitle = pt.subtitle,
                                                        segmentBreakBefore = pt.segmentBreakBefore
                                                    )
                                                }
                                            }
                                            if (trendPoints.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Text(
                                                    text = "每日用电采样趋势",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                CampusMetricChart(points = trendPoints)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // 手工水电台账分支
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "本地手工水电表计",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "无需联网，支持记录剩余电量/水量与购入充值，自动计算区间净消耗",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilledTonalButton(
                            onClick = { showAddMeterDialog = true },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.size(4.dp))
                            Text("添加表计")
                        }
                    }
                }

                if (meters.isEmpty()) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "尚未添加手工表计",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "点击右上角「添加表计」可建立宿舍电表或水表台账，随时记录剩余度数或金额。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    for (meter in meters) {
                        val meterReadings = remember(readings, meter.id) {
                            readings.filter { it.meterId == meter.id }.sortedBy { it.recordedAtEpochMs }
                        }
                        val latest = meterReadings.lastOrNull()
                        val isLow = latest != null && latest.value <= meter.lowThreshold

                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${meter.name} · ${meter.roomName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${meter.campusName} · 单位：${meter.unit} · 预警线：${meter.lowThreshold} ${meter.unit}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isLow) {
                                        XPeachStatusBadge(text = "余额偏低")
                                    }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                repository.deleteUtilityMeter(meter.id)
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "删除表计",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (latest != null) {
                                Text(
                                    text = "最新剩余：${"%.2f".format(latest.value)} ${meter.unit}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            val chartPoints = remember(meterReadings, meter.unit) {
                                buildManualMeterChartPoints(meterReadings, meter.unit)
                            }
                            if (chartPoints.size >= 2) {
                                Spacer(modifier = Modifier.height(8.dp))
                                CampusMetricChart(points = chartPoints)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { readingTargetMeter = meter },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("记录新读数")
                                }
                                if (meterReadings.isNotEmpty()) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                repository.deleteUtilityReading(meterReadings.last().id)
                                            }
                                        }
                                    ) {
                                        Text("撤销最近一条")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddMeterDialog) {
        AddUtilityMeterDialog(
            onDismiss = { showAddMeterDialog = false },
            onConfirm = { name, campus, room, unit, threshold ->
                scope.launch {
                    runCatching {
                        repository.saveUtilityMeter(name, campus, room, unit, threshold)
                        showAddMeterDialog = false
                    }.onFailure { err ->
                        statusBanner = err.message
                    }
                }
            }
        )
    }

    val targetMeter = readingTargetMeter
    if (targetMeter != null) {
        AddUtilityReadingDialog(
            meter = targetMeter,
            onDismiss = { readingTargetMeter = null },
            onConfirm = { valNum, topUpNum, note ->
                scope.launch {
                    runCatching {
                        repository.addUtilityReading(targetMeter.id, valNum, topUpNum, note)
                        readingTargetMeter = null
                    }.onFailure { err ->
                        statusBanner = err.message
                    }
                }
            }
        )
    }
}

private fun buildManualMeterChartPoints(
    readings: List<UtilityReadingDomain>,
    unit: String
): List<CampusMetricPoint> {
    var previousValue: Double? = null
    return readings.map { item ->
        val prev = previousValue
        // 若当前剩余读数大于上一次剩余读数 + 本次记录的充值量，说明期间发生了未记录的购电，断开折线
        val unrecordedTopUp = prev != null && item.value > prev + item.topUpValue + 1e-6
        previousValue = item.value
        val label = Instant.ofEpochMilli(item.recordedAtEpochMs)
            .atZone(HAUT_ZONE_ID)
            .format(DateTimeFormatter.ofPattern("MM-dd"))
        CampusMetricPoint(
            label = label,
            value = item.value,
            unit = unit,
            subtitle = "$label · 剩余 ${"%.2f".format(item.value)} $unit" +
                (if (item.topUpValue > 0.0) "（含购入 ${"%.2f".format(item.topUpValue)}）" else ""),
            segmentBreakBefore = unrecordedTopUp
        )
    }
}

@Composable
private fun AddUtilityMeterDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, campus: String, room: String, unit: String, threshold: Double) -> Unit
) {
    var name by remember { mutableStateOf("宿舍电表") }
    var campus by remember { mutableStateOf("莲花街校区") }
    var room by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("度") }
    var thresholdText by remember { mutableStateOf("15") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加水电表计") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("表计名称（如：照明电表 / 空调电表）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = campus,
                    onValueChange = { campus = it },
                    label = { Text("校区（如：莲花街校区 / 嵩山路校区）") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = { room = it },
                    label = { Text("宿舍楼栋与房间号") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("单位(度/元/m³)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = thresholdText,
                        onValueChange = { thresholdText = it },
                        label = { Text("低量预警线") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val threshold = thresholdText.trim().toDoubleOrNull() ?: 10.0
                    if (name.isNotBlank() && room.isNotBlank()) {
                        onConfirm(name, campus, room, unit, threshold)
                    }
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun AddUtilityReadingDialog(
    meter: UtilityMeterDomain,
    onDismiss: () -> Unit,
    onConfirm: (value: Double, topUp: Double, note: String) -> Unit
) {
    var valueText by remember { mutableStateOf("") }
    var topUpText by remember { mutableStateOf("0") }
    var noteText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录读数 · ${meter.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = valueText,
                    onValueChange = { valueText = it },
                    label = { Text("当前剩余读数 (${meter.unit})") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = topUpText,
                    onValueChange = { topUpText = it },
                    label = { Text("本次新充值/购入量 (${meter.unit}，无则填 0)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("备注（选填）") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val v = valueText.trim().toDoubleOrNull() ?: return@TextButton
                    val topUp = topUpText.trim().toDoubleOrNull() ?: 0.0
                    onConfirm(v, topUp, noteText)
                }
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

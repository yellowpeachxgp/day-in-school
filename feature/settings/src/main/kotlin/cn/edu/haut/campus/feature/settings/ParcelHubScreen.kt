package cn.edu.haut.campus.feature.settings

import android.annotation.SuppressLint
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.haut.campus.core.campusservices.ParcelAuthorityStatus
import cn.edu.haut.campus.core.campusservices.ParcelHubEngine
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.campusservices.XiaomiAccountCredential
import cn.edu.haut.campus.core.campusservices.XiaomiPassportClient
import cn.edu.haut.campus.core.campusservices.XiaomiTrackPoint
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.ParcelItemEntity
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParcelHubScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val parcels by repository.parcelItemsFlow.collectAsState(initial = emptyList())
    val plugins by repository.installedExtensionPluginsFlow.collectAsState(initial = emptyList())

    var xiaomiAccounts by remember { mutableStateOf(repository.getXiaomiAccounts()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showXiaomiLoginDialog by remember { mutableStateOf(false) }
    var phoneManageAccount by remember { mutableStateOf<XiaomiAccountCredential?>(null) }
    var detailParcel by remember { mutableStateOf<ParcelItemEntity?>(null) }
    var syncingXiaomi by remember { mutableStateOf(false) }
    var aiConsentGranted by remember { mutableStateOf(false) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }

    fun reloadXiaomiAccounts() {
        xiaomiAccounts = repository.getXiaomiAccounts()
    }

    LaunchedEffect(repository) {
        repository.ensureDefaultReferencePluginsInstalled()
        reloadXiaomiAccounts()
    }

    val readyParcels = remember(parcels) {
        parcels.filter { it.status == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName }
    }
    val inTransitParcels = remember(parcels) {
        parcels.filter {
            it.status == ParcelLifecycleStatus.IN_TRANSIT.wireName ||
                it.status == ParcelLifecycleStatus.OUT_FOR_DELIVERY.wireName ||
                it.status == ParcelLifecycleStatus.CREATED.wireName
        }
    }
    val pickedUpParcels = remember(parcels) {
        parcels.filter { it.status == ParcelLifecycleStatus.PICKED_UP.wireName }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "我的快递与取件码",
            subtitle = "绑定手机号自动查快递 · 驿站取件码集中查看",
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
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            XPeachSegmentedTabs(
                items = listOf(
                    "我的快递 (${parcels.size})",
                    "手机号绑定 (${xiaomiAccounts.size})",
                    "校园驿站汇总"
                ),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            feedbackBanner?.let { msg ->
                XPeachCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { feedbackBanner = null }) {
                            Text("知道了")
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    // 顶部小米账号与手机号一键同步卡片
                    XiaomiQuickConnectCard(
                        xiaomiAccounts = xiaomiAccounts,
                        syncing = syncingXiaomi,
                        onOpenLogin = { showXiaomiLoginDialog = true },
                        onManagePhones = { acc -> phoneManageAccount = acc },
                        onSyncNow = {
                            scope.launch {
                                syncingXiaomi = true
                                repository.syncXiaomiExpressPackages()
                                    .onSuccess { res ->
                                        feedbackBanner = res.message
                                    }
                                    .onFailure { err ->
                                        feedbackBanner = "小米快递同步失败：${err.message}"
                                    }
                                syncingXiaomi = false
                            }
                        }
                    )

                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "待取件 ${readyParcels.size} 件 · 在途 ${inTransitParcels.size} 件",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "支持通过小米账号+手机号自动拉取全网快递，或粘贴菜鸟/丰巢短信提取取件码",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { showAddDialog = true },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("手动/短信导入")
                            }
                        }
                    }

                    if (parcels.isEmpty()) {
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "当前暂无包裹记录",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "推荐上方先点击「手机号/扫码登录小米账号」并绑定收件手机号，即可一键同步菜鸟裹裹、顺丰、京东、小米商城等全渠道快递；也可点击「手动/短信导入」粘贴驿站取件短信。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    for (item in parcels) {
                        val statusEnum = ParcelLifecycleStatus.fromWire(item.status)
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    XPeachStatusBadge(
                                        text = statusEnum.label,
                                        containerColor = when (statusEnum) {
                                            ParcelLifecycleStatus.READY_FOR_PICKUP -> MaterialTheme.colorScheme.tertiaryContainer
                                            ParcelLifecycleStatus.PICKED_UP -> MaterialTheme.colorScheme.surfaceVariant
                                            else -> MaterialTheme.colorScheme.primaryContainer
                                        },
                                        contentColor = when (statusEnum) {
                                            ParcelLifecycleStatus.READY_FOR_PICKUP -> MaterialTheme.colorScheme.onTertiaryContainer
                                            ParcelLifecycleStatus.PICKED_UP -> MaterialTheme.colorScheme.onSurfaceVariant
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    )
                                    Text(
                                        text = item.itemTitle ?: "${item.carrierName} (${item.trackingNumber})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = item.carrierName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (!item.pickupCode.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "取件码：${item.pickupCode}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = "驿站：${item.stationName ?: "校园快递驿站"}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (statusEnum != ParcelLifecycleStatus.PICKED_UP) {
                                            FilledTonalButton(
                                                onClick = {
                                                    scope.launch {
                                                        repository.markParcelPickedUp(item.id)
                                                        feedbackBanner = "已标记「${item.pickupCode}」为已取件"
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("一键已取")
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "最新动态：${item.estimatedDeliveryText}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "运单号：${item.trackingNumber}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = { detailParcel = item }) {
                                        Text("物流轨迹")
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                repository.deleteParcelById(item.id)
                                                feedbackBanner = "已删除运单 ${item.trackingNumber}"
                                            }
                                        }
                                    ) {
                                        Text("删除", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "绑定手机号 · 自动查全网快递",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "使用方法（只需 3 步）：\n" +
                                "1. 点击下方「手机号 / 扫码登录小米账号」，用手机号或扫码完成登录。\n" +
                                "2. 点击「绑定 / 管理收件手机号」，把你平时买东西用的收件手机号绑上（支持绑定多个手机号）。\n" +
                                "3. 点击「立即同步快递」，即可自动拉取菜鸟裹裹、顺丰、京东、中通、圆通、韵达、申通、极兔等快递和驿站取件码。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { showXiaomiLoginDialog = true },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("手机号 / 扫码登录小米账号")
                            }
                            if (xiaomiAccounts.isNotEmpty()) {
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            syncingXiaomi = true
                                            repository.syncXiaomiExpressPackages()
                                                .onSuccess { res -> feedbackBanner = res.message }
                                                .onFailure { err -> feedbackBanner = "同步失败：${err.message}" }
                                            syncingXiaomi = false
                                        }
                                    },
                                    enabled = !syncingXiaomi,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(if (syncingXiaomi) "正在同步快递…" else "立即同步全部快递")
                                }
                            }
                        }
                    }

                    if (xiaomiAccounts.isEmpty()) {
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "当前尚未登录小米账号",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "点击上方「手机号 / 扫码登录小米账号」即可直接用手机号验证码登录并绑定收件号码。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    for (acc in xiaomiAccounts) {
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = acc.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "账号 ID: ${acc.accountId} · 已绑手机号 (${acc.phones.size} 个)：${acc.maskedPhonesSummary}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                XPeachStatusBadge(
                                    text = if (acc.phones.isNotEmpty()) "已绑 ${acc.phones.size} 个号码" else "待绑手机号",
                                    containerColor = if (acc.phones.isNotEmpty()) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    },
                                    contentColor = if (acc.phones.isNotEmpty()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { phoneManageAccount = acc },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("绑定 / 管理收件手机号")
                                }
                                FilledTonalButton(
                                    onClick = {
                                        scope.launch {
                                            syncingXiaomi = true
                                            repository.syncXiaomiExpressPackages()
                                                .onSuccess { res ->
                                                    feedbackBanner = res.message
                                                    selectedTab = 0
                                                }
                                                .onFailure { err ->
                                                    feedbackBanner = "同步失败：${err.message}"
                                                }
                                            syncingXiaomi = false
                                        }
                                    },
                                    enabled = !syncingXiaomi,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("同步该账号快递")
                                }
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            repository.removeXiaomiAccount(acc.id)
                                            reloadXiaomiAccounts()
                                            feedbackBanner = "已移除 ${acc.label}"
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("退出账号", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                else -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "按驿站集中查看取件码",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val byStation = readyParcels.groupBy { it.stationName ?: "默认校园驿站" }
                        if (byStation.isEmpty()) {
                            Text(
                                text = "当前没有待取快递，另有 ${inTransitParcels.size} 件在途、${pickedUpParcels.size} 件已取件。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            for ((station, list) in byStation) {
                                XPeachWell(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "📍 $station（共 ${list.size} 件待取）",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "取件码汇总：" + list.mapNotNull { it.pickupCode }.joinToString(" 、 "),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        if (list.isNotEmpty() && list.first().stationName != null) {
                                            FilledTonalButton(
                                                onClick = {
                                                    scope.launch {
                                                        val count = repository.markAllStationParcelsPickedUp(station)
                                                        feedbackBanner = "已将「$station」的 $count 件快递全部标记为已取件"
                                                    }
                                                },
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("本驿站全取")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "智能取件路线建议",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "开启后可根据你的下课地点与宿舍位置，推荐顺路取快递的驿站顺序。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = aiConsentGranted,
                                onCheckedChange = { aiConsentGranted = it }
                            )
                        }
                    }

                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "校园常用快递驿站指南",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "📍 莲花街校区：北苑菜鸟驿站、南苑菜鸟驿站、西区快递中心（日常营业 08:30–21:00）\n" +
                                    "📍 嵩山路校区：校区菜鸟驿站与丰巢智能柜\n" +
                                    "💡 小贴士：你还可以把「4×2 快递取件码」小组件添加到手机桌面，站在货架前无需打开 App 即可一眼看到取件码。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showXiaomiLoginDialog) {
        XiaomiLoginWebViewDialog(
            onDismiss = { showXiaomiLoginDialog = false },
            onLoginSuccess = { token, cUserId, accountId, detectedPhones ->
                scope.launch {
                    repository.saveXiaomiAccountFromWebLogin(
                        token = token,
                        cUserId = cUserId,
                        accountId = accountId,
                        detectedPhones = detectedPhones
                    ).onSuccess { saved ->
                        reloadXiaomiAccounts()
                        showXiaomiLoginDialog = false
                        if (saved.phones.isNotEmpty()) {
                            feedbackBanner = "已登录 ${saved.label} 并自动识别手机号 ${saved.maskedPhonesSummary}，正在同步快递…"
                            syncingXiaomi = true
                            repository.syncXiaomiExpressPackages()
                                .onSuccess { sum -> feedbackBanner = sum.message }
                                .onFailure { err -> feedbackBanner = "已登录 ${saved.label}，自动同步提示：${err.message}" }
                            syncingXiaomi = false
                        } else {
                            feedbackBanner = "小米账号登录成功！请继续在弹出面板中绑定您的收件手机号。"
                            phoneManageAccount = saved
                        }
                    }.onFailure { err ->
                        feedbackBanner = "保存小米登录凭证失败：${err.message}"
                    }
                }
            }
        )
    }

    phoneManageAccount?.let { targetAcc ->
        XiaomiPhoneBindDialog(
            account = targetAcc,
            repository = repository,
            onDismiss = {
                reloadXiaomiAccounts()
                phoneManageAccount = null
            },
            onAccountUpdated = { updated ->
                reloadXiaomiAccounts()
                phoneManageAccount = updated
            },
            onMessage = { msg -> feedbackBanner = msg }
        )
    }

    detailParcel?.let { item ->
        ParcelTimelineDialog(
            parcel = item,
            repository = repository,
            onDismiss = { detailParcel = null },
            onMessage = { msg -> feedbackBanner = msg }
        )
    }

    if (showAddDialog) {
        var trackingInput by remember { mutableStateOf("") }
        var carrierInput by remember { mutableStateOf("顺丰速运") }
        var titleInput by remember { mutableStateOf("") }
        var smsInput by remember { mutableStateOf("") }

        val previewExtracted = remember(smsInput) {
            ParcelHubEngine.resolvePickupCodeFromText(smsInput)
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加快递 / 粘贴取件短信") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = smsInput,
                        onValueChange = { smsInput = it },
                        label = { Text("粘贴取件短信全文（推荐，自动识别取件码与驿站）") },
                        placeholder = { Text("例如：【菜鸟驿站】您的包裹已到莲花街北苑菜鸟驿站，请凭取件码 3-2-1048 取件") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (smsInput.isNotBlank()) {
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            if (previewExtracted != null) {
                                Text(
                                    text = "✅ 已识别取件码：${previewExtracted.pickupCode} · 驿站：${previewExtracted.stationName ?: "校园驿站"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "未在短信中识别到取件码（可直接在下方填写单号与备注）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = trackingInput,
                        onValueChange = { trackingInput = it },
                        label = { Text("快递单号（粘贴短信后可留空自动生成）") },
                        placeholder = { Text("如 SF1234567890") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = carrierInput,
                        onValueChange = { carrierInput = it },
                        label = { Text("快递公司") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("物品备注（选填，如：教材 / 耳机）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = trackingInput.isNotBlank() || previewExtracted != null,
                    onClick = {
                        scope.launch {
                            val resolvedTracking = trackingInput.trim().ifBlank {
                                val codePart = previewExtracted?.pickupCode?.replace(Regex("[^A-Za-z0-9]"), "") ?: "PKG"
                                "SMS-$codePart-${System.currentTimeMillis() % 10000}"
                            }
                            repository.upsertParcelItemFromInput(
                                trackingNumberRaw = resolvedTracking,
                                carrierName = carrierInput.trim().ifBlank { "校园快递" },
                                providerId = if (smsInput.isNotBlank()) "parcel.notification-parser" else "parcel.manual",
                                itemTitleInput = titleInput,
                                rawNotificationText = smsInput
                            ).onSuccess { saved ->
                                showAddDialog = false
                                feedbackBanner = "已保存快递" +
                                    (saved.pickupCode?.let { " · 取件码 $it" } ?: " ${saved.trackingNumber}")
                            }.onFailure { err ->
                                feedbackBanner = err.message ?: "保存失败"
                            }
                        }
                    }
                ) {
                    Text("保存快递")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun XiaomiQuickConnectCard(
    xiaomiAccounts: List<XiaomiAccountCredential>,
    syncing: Boolean,
    onOpenLogin: () -> Unit,
    onManagePhones: (XiaomiAccountCredential) -> Unit,
    onSyncNow: () -> Unit
) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "小米智能助理 · 手机号链接快递同步",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (xiaomiAccounts.isEmpty()) {
                    Text(
                        text = "直接通过手机号+短信验证码登录小米账号，并绑定收件手机号获取全网快递（菜鸟/顺丰/京东/小米商城）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val first = xiaomiAccounts.first()
                    Text(
                        text = "已连接 ${xiaomiAccounts.size} 个小米账号 · 当前绑定号码：${first.maskedPhonesSummary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (xiaomiAccounts.isEmpty()) {
                Button(
                    onClick = onOpenLogin,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("手机号 / 扫码登录小米账号")
                }
            } else {
                Button(
                    onClick = onSyncNow,
                    enabled = !syncing,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (syncing) "正在从小米同步快递…" else "立即从小米同步快递")
                }
                FilledTonalButton(
                    onClick = { onManagePhones(xiaomiAccounts.first()) },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("绑定 / 管理收件手机号")
                }
                OutlinedButton(
                    onClick = onOpenLogin,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("切换/新增小米账号")
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun XiaomiLoginWebViewDialog(
    onDismiss: () -> Unit,
    onLoginSuccess: (token: String, cUserId: String, accountId: String, detectedPhones: List<String>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var statusText by remember {
        mutableStateOf("正在加载小米官方登录页…\n支持直接在页面内输入【手机号 + 短信验证码/密码】登录，或使用手机小米账号扫码登录。")
    }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var lastUrl by remember { mutableStateOf("") }
    var phoneFromUrl by remember { mutableStateOf("") }

    suspend fun checkAndCompleteLoginIfReady(explicitTrigger: Boolean = false) {
        if (working) return
        val accountCookies = CookieManager.getInstance().getCookie("https://account.xiaomi.com").orEmpty()
        val stsCookies = CookieManager.getInstance().getCookie("https://api.assistant.miui.com").orEmpty()
        val hasPassToken = accountCookies.contains("passToken=")
        val hasServiceToken = stsCookies.contains("serviceToken=") || stsCookies.contains("assistant_serviceToken=")
        if (!hasPassToken && !hasServiceToken) {
            if (explicitTrigger) {
                statusText = "尚未检测到完成登录的 Cookie，请先在下方网页完成手机号验证码或密码登录。"
            }
            return
        }
        working = true
        statusText = "检测到小米登录态，正在签发小米智能助理令牌并探测绑定手机号…"
        withContext(Dispatchers.IO) {
            runCatching {
                val map = XiaomiPassportClient.parseCookies(accountCookies)
                val stsMap = XiaomiPassportClient.parseCookies(stsCookies)
                val passToken = map["passToken"]
                val userId = map["userId"] ?: stsMap["userId"]
                var cUserId = map["cUserId"] ?: stsMap["cUserId"]
                var token = stsMap["assistant_serviceToken"] ?: stsMap["serviceToken"]
                var finalAccountId = userId.orEmpty()

                if (token.isNullOrBlank()) {
                    val minted = XiaomiPassportClient.mint(map, finalAccountId)
                    token = minted.token
                    cUserId = minted.cUserId
                    finalAccountId = minted.accountId
                } else if (cUserId.isNullOrBlank() && !passToken.isNullOrBlank() && finalAccountId.isNotBlank()) {
                    runCatching {
                        val minted = XiaomiPassportClient.mint(map, finalAccountId)
                        if (cUserId.isNullOrBlank()) cUserId = minted.cUserId
                    }
                }
                if (finalAccountId.isBlank()) {
                    finalAccountId = cUserId.orEmpty()
                }
                val phones = XiaomiPassportClient.autoDetectPhones(
                    phoneFromUrl = phoneFromUrl,
                    lastUrl = lastUrl,
                    passToken = passToken,
                    userId = finalAccountId,
                    cUserId = cUserId
                )
                Triple(token!!, cUserId.orEmpty(), finalAccountId) to phones
            }.onSuccess { (triple, phones) ->
                withContext(Dispatchers.Main) {
                    onLoginSuccess(triple.first, triple.second, triple.third, phones)
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    statusText = "令牌签发失败：${err.message}\n请继续完成网页安全验证或重试。"
                    working = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            runCatching { XiaomiPassportClient.getLoginUrl() }
                .onSuccess { loc ->
                    withContext(Dispatchers.Main) {
                        loginUrl = loc
                        statusText = "请在下方页面输入【手机号 + 验证码/密码】登录小米账号（登录完成后会自动识别并跳转）："
                    }
                }
                .onFailure { err ->
                    withContext(Dispatchers.Main) {
                        loginUrl = "https://account.xiaomi.com/pass/serviceLogin?sid=assistant"
                        statusText = "已打开备用登录入口（${err.message}）"
                    }
                }
        }
    }

    LaunchedEffect(loginUrl) {
        if (loginUrl == null) return@LaunchedEffect
        while (isActive) {
            delay(1800L)
            checkAndCompleteLoginIfReady(explicitTrigger = false)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "登录小米账号（支持手机号验证码/密码）",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalButton(
                            onClick = { scope.launch { checkAndCompleteLoginIfReady(explicitTrigger = true) } },
                            enabled = !working
                        ) {
                            Text("我已登录")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }
                HorizontalDivider()
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val targetUrl = loginUrl
                    if (targetUrl == null) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        var webViewInstance by remember { mutableStateOf<WebView?>(null) }
                        DisposableEffect(Unit) {
                            onDispose {
                                webViewInstance?.destroy()
                            }
                        }
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                    val cm = CookieManager.getInstance()
                                    cm.removeAllCookies(null)
                                    cm.flush()
                                    cm.setAcceptCookie(true)
                                    cm.setAcceptThirdPartyCookies(this, true)

                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    settings.userAgentString = XiaomiPassportClient.DESKTOP_UA

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            val currentUrl = url.orEmpty()
                                            lastUrl = currentUrl
                                            XiaomiPassportClient.extractPhoneFromUrl(currentUrl)?.let {
                                                phoneFromUrl = it
                                            }
                                            if (!working && (
                                                    currentUrl.contains("fe/service/account") ||
                                                        currentUrl.contains("passInfo=login-end") ||
                                                        currentUrl.startsWith("https://api.assistant.miui.com/sts")
                                                )
                                            ) {
                                                scope.launch {
                                                    checkAndCompleteLoginIfReady(explicitTrigger = false)
                                                }
                                            }
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onCreateWindow(
                                            view: WebView?,
                                            isDialog: Boolean,
                                            isUserGesture: Boolean,
                                            resultMsg: Message?
                                        ): Boolean {
                                            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                            transport.webView = this@apply
                                            resultMsg.sendToTarget()
                                            return true
                                        }

                                        override fun onJsAlert(
                                            view: WebView?,
                                            url: String?,
                                            message: String?,
                                            result: JsResult?
                                        ): Boolean {
                                            result?.confirm()
                                            return true
                                        }
                                    }
                                    loadUrl(targetUrl)
                                    webViewInstance = this
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun XiaomiPhoneBindDialog(
    account: XiaomiAccountCredential,
    repository: CampusRepository,
    onDismiss: () -> Unit,
    onAccountUpdated: (XiaomiAccountCredential) -> Unit,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var phoneInput by remember { mutableStateOf("") }
    var codeInput by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var dialogHint by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手机号管理 · ${account.label}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "已绑定的收件手机号（${account.phones.size} 个，同步时自动查询这些号码名下的全部快递）：",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (account.phones.isEmpty()) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "当前暂未绑定手机号。请在下方输入 11 位收件手机号并发送验证码完成绑定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    for (phone in account.phones) {
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${XiaomiPassportClient.maskPhone(phone)} ($phone)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            working = true
                                            repository.unbindXiaomiPhone(account.id, phone)
                                                .onSuccess { updated ->
                                                    onAccountUpdated(updated)
                                                    dialogHint = "已解绑手机号 ${XiaomiPassportClient.maskPhone(phone)}"
                                                }
                                                .onFailure { err ->
                                                    dialogHint = "解绑失败：${err.message}"
                                                }
                                            working = false
                                        }
                                    },
                                    enabled = !working
                                ) {
                                    Text("解绑", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Text(
                    text = "新增绑定收件手机号（小米智能助理短信验证）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedTextField(
                    value = phoneInput,
                    onValueChange = { phoneInput = it.filter { ch -> ch.isDigit() }.take(11) },
                    label = { Text("11 位收件手机号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                working = true
                                repository.sendXiaomiPhoneVerificationCode(account.id, phoneInput)
                                    .onSuccess { res ->
                                        dialogHint = if (res.ok) {
                                            "✅ 验证码已发送至 $phoneInput，请查收短信"
                                        } else {
                                            "发送提示：${res.message}（若该号码已是当前小米账号绑定号，可直接点下方「直接添加已验证号码」）"
                                        }
                                    }
                                    .onFailure { err ->
                                        dialogHint = "发送验证码失败：${err.message}"
                                    }
                                working = false
                            }
                        },
                        enabled = !working && phoneInput.length == 11,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("发送短信验证码")
                    }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                working = true
                                repository.addXiaomiPhoneDirectly(account.id, phoneInput)
                                    .onSuccess { updated ->
                                        onAccountUpdated(updated)
                                        phoneInput = ""
                                        dialogHint = "✅ 已直接添加手机号，可立即同步快递"
                                        onMessage("已绑定手机号 ${updated.maskedPhonesSummary}")
                                    }
                                    .onFailure { err ->
                                        dialogHint = "添加失败：${err.message}"
                                    }
                                working = false
                            }
                        },
                        enabled = !working && phoneInput.length == 11,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("直接添加本机号")
                    }
                }
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { codeInput = it.trim() },
                    label = { Text("短信验证码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        scope.launch {
                            working = true
                            repository.verifyAndBindXiaomiPhone(account.id, phoneInput, codeInput)
                                .onSuccess { updated ->
                                    onAccountUpdated(updated)
                                    phoneInput = ""
                                    codeInput = ""
                                    dialogHint = "✅ 短信验证并绑定手机号成功！"
                                    onMessage("已通过短信验证成功绑定手机号 ${updated.maskedPhonesSummary}")
                                }
                                .onFailure { err ->
                                    dialogHint = "验证绑定失败：${err.message}"
                                }
                            working = false
                        }
                    },
                    enabled = !working && phoneInput.length == 11 && codeInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("确认验证并绑定手机号")
                }

                dialogHint?.let { hint ->
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        }
    )
}

@Composable
private fun ParcelTimelineDialog(
    parcel: ParcelItemEntity,
    repository: CampusRepository,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var points by remember(parcel.checkpointsJson) {
        mutableStateOf(parseStoredCheckpoints(parcel.checkpointsJson))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${parcel.carrierName} · ${parcel.trackingNumber}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!parcel.pickupCode.isNullOrBlank()) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "取件码：${parcel.pickupCode} · ${parcel.stationName ?: "校园驿站"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            refreshing = true
                            repository.fetchXiaomiParcelDetail(parcel.id)
                                .onSuccess { remotePoints ->
                                    points = remotePoints
                                    onMessage("已从小米智能助理刷新 ${parcel.trackingNumber} 完整物流轨迹（共 ${remotePoints.size} 条）")
                                }
                                .onFailure { err ->
                                    onMessage("刷新详情提示：${err.message}")
                                }
                            refreshing = false
                        }
                    },
                    enabled = !refreshing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (refreshing) "正在向小米智能助理查询实时轨迹…" else "从小米智能助理刷新完整轨迹")
                }

                if (points.isEmpty()) {
                    Text(
                        text = "当前摘要：${parcel.estimatedDeliveryText}\n（若为小米智能助理同步包裹，可点击上方按钮拉取完整节点）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    for (pt in points) {
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            if (pt.time.isNotBlank()) {
                                Text(
                                    text = pt.time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = pt.desc,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

private fun parseStoredCheckpoints(rawJson: String): List<XiaomiTrackPoint> {
    return runCatching {
        val arr = JSONArray(rawJson)
        val out = ArrayList<XiaomiTrackPoint>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val desc = o.optString("desc").trim()
            val time = o.optString("time").trim()
            if (desc.isNotEmpty()) {
                out.add(XiaomiTrackPoint(desc = desc, time = time))
            }
        }
        out
    }.getOrDefault(emptyList())
}

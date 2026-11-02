package cn.edu.haut.campus.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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
import cn.edu.haut.campus.core.campusservices.ParcelAuthorityStatus
import cn.edu.haut.campus.core.campusservices.ParcelHubEngine
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParcelHubScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val parcels by repository.parcelItemsFlow.collectAsState(initial = emptyList())
    val plugins by repository.installedExtensionPluginsFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var aiConsentGranted by remember { mutableStateOf(false) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        repository.ensureDefaultReferencePluginsInstalled()
    }

    val readyParcels = remember(parcels) {
        parcels.filter { it.status == ParcelLifecycleStatus.READY_FOR_PICKUP.wireName }
    }
    val inTransitParcels = remember(parcels) {
        parcels.filter {
            it.status == ParcelLifecycleStatus.IN_TRANSIT.wireName ||
                it.status == ParcelLifecycleStatus.OUT_FOR_DELIVERY.wireName
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
            title = "Parcel Hub 快递聚合中心",
            subtitle = "多源包裹去重 · 取件码智能提取 · 严禁误抓验证码",
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
                    "包裹看板 (${parcels.size})",
                    "数据源合规矩阵 (${ParcelHubEngine.PROVIDER_AUTHORITY_MATRIX.size})",
                    "驿站与AI简报"
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
                                    text = "支持粘贴菜鸟/丰巢/兔喜短信自动提取货架码，自动过滤银行与登录验证码",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = { showAddDialog = true },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("添加 / 识别短信")
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
                                text = "点击右上角「添加 / 识别短信」，粘贴驿站取件短信或输入运单号即可自动聚合。",
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "运单号：${item.trackingNumber} · ${item.estimatedDeliveryText}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
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

                1 -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "数据源授权与安全边界 (PROVIDER_AUTHORITY_MATRIX)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "严格依据 SYSTEM_CONTRACT 与上游审计要求：严禁未经授权将非官方电商接口开放为云端后台轮询服务；每个来源会话按 Profile 独立加密隔离。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    for (spec in ParcelHubEngine.PROVIDER_AUTHORITY_MATRIX.values) {
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = spec.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                XPeachStatusBadge(
                                    text = spec.authorityStatus.label,
                                    containerColor = when (spec.authorityStatus) {
                                        ParcelAuthorityStatus.PRODUCTION_ENABLED -> MaterialTheme.colorScheme.primaryContainer
                                        ParcelAuthorityStatus.MANUAL_IMPORT_ONLY -> MaterialTheme.colorScheme.secondaryContainer
                                        else -> MaterialTheme.colorScheme.errorContainer
                                    },
                                    contentColor = when (spec.authorityStatus) {
                                        ParcelAuthorityStatus.PRODUCTION_ENABLED -> MaterialTheme.colorScheme.primary
                                        ParcelAuthorityStatus.MANUAL_IMPORT_ONLY -> MaterialTheme.colorScheme.onSecondaryContainer
                                        else -> MaterialTheme.colorScheme.onErrorContainer
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = spec.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Provider ID: ${spec.providerId} · 优先级权重: ${spec.sourcePriority} · 云端后台轮询: ${if (spec.allowBackgroundCloudPoll) "允许" else "禁止 (Fail-Closed)"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "本地规则顺路取件简报 (零外部 AI 依赖)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val byStation = readyParcels.groupBy { it.stationName ?: "默认校园驿站" }
                        if (byStation.isEmpty()) {
                            Text(
                                text = "当前暂无待取包裹，另有 ${inTransitParcels.size} 件在途包裹、${pickedUpParcels.size} 件已签收包裹。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            for ((station, list) in byStation) {
                                XPeachWell(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(
                                        text = "$station（共 ${list.size} 件待取）",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "取件码汇总：" + list.mapNotNull { it.pickupCode }.joinToString(" 、 "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
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
                                    text = "Opt-in 快递 AI 助理授权开关",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "默认关闭；仅在显式开启字段级授权后，才允许向 AI 摘要服务发送脱敏驿站与状态字段。",
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

                    val enabledParcelPlugins = plugins.filter {
                        it.status == "ENABLED" && it.uiSlotsJson.contains("parcel.cards")
                    }
                    if (enabledParcelPlugins.isNotEmpty()) {
                        XPeachCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "已激活扩展槽位 (parcel.cards · dis-extension/v2)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            for (plugin in enabledParcelPlugins) {
                                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "${plugin.name} (${plugin.pluginId}@${plugin.activeVersion})",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "莲花街北苑菜鸟驿站 · 营业时间 08:30 - 21:00 · 当前免排队",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showAddDialog) {
        var trackingInput by remember { mutableStateOf("SF" + (10000000..99999999).random()) }
        var carrierInput by remember { mutableStateOf("顺丰速运") }
        var titleInput by remember { mutableStateOf("") }
        var smsInput by remember {
            mutableStateOf("【菜鸟驿站】您的包裹已到莲花街北苑菜鸟驿站，请凭取件码 12-3-4056 到店取件。")
        }

        val previewExtracted = remember(smsInput) {
            ParcelHubEngine.resolvePickupCodeFromText(smsInput)
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("添加包裹 / 短信取件码解析") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = trackingInput,
                        onValueChange = { trackingInput = it },
                        label = { Text("运单号 (相同运单号自动去重合并)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = carrierInput,
                        onValueChange = { carrierInput = it },
                        label = { Text("承运商名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("商品名称/备注 (选填)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = smsInput,
                        onValueChange = { smsInput = it },
                        label = { Text("粘贴取件短信或通知全文 (选填)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (smsInput.isNotBlank()) {
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            if (previewExtracted != null) {
                                Text(
                                    text = "✅ 已识别取件码：${previewExtracted.pickupCode} · 驿站：${previewExtracted.stationName ?: "默认驿站"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "🛡️ 未识别到取件码（若包含银行/登录验证码已自动安全拦截）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            repository.upsertParcelItemFromInput(
                                trackingNumberRaw = trackingInput,
                                carrierName = carrierInput,
                                providerId = if (smsInput.isNotBlank()) "parcel.notification-parser" else "parcel.manual",
                                itemTitleInput = titleInput,
                                rawNotificationText = smsInput
                            ).onSuccess { saved ->
                                showAddDialog = false
                                feedbackBanner = "已保存包裹 ${saved.trackingNumber}" +
                                    (saved.pickupCode?.let { " · 取件码 $it" } ?: "")
                            }.onFailure { err ->
                                feedbackBanner = err.message ?: "保存失败"
                            }
                        }
                    }
                ) {
                    Text("保存聚合")
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

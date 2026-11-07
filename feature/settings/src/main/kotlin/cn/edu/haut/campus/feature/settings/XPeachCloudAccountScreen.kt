package cn.edu.haut.campus.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import org.json.JSONArray
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.MobileAuthTransactionStart
import cn.edu.haut.campus.core.campusservices.NativeOidcLoginResult
import cn.edu.haut.campus.core.campusservices.XpeachCloudAccountProfile
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.InstalledExtensionPluginEntity
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachGlassSurface
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun XPeachCloudAccountScreen(
    repository: CampusRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cloudBinding by repository.cloudIdentityBindingFlow.collectAsState(initial = null)
    val bookingPlans by repository.libraryBookingPlansFlow.collectAsState(initial = emptyList())
    val plugins by repository.installedExtensionPluginsFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) }
    var showBindDialog by remember { mutableStateOf(false) }
    var showInstallPluginDialog by remember { mutableStateOf(false) }
    var isSyncingSnapshot by remember { mutableStateOf(false) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, showBindDialog) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !showBindDialog) {
                val txn = CampusCloudOidcHelper.getLatestBrowserTxn() ?: return@LifecycleEventObserver
                if (System.currentTimeMillis() > txn.expiresAtEpochMillis) {
                    CampusCloudOidcHelper.clearLatestBrowserTxn()
                    return@LifecycleEventObserver
                }
                val deepLinkPair = CampusCloudOidcHelper.consumeDeepLinkCallback(txn.loginTxn)
                scope.launch {
                    val ticket = deepLinkPair?.first ?: withContext(Dispatchers.IO) {
                        CampusCloudOidcHelper.pollMobileAuthTransactionTicket(txn.loginTxn)
                    }
                    if (!ticket.isNullOrBlank()) {
                        CampusCloudOidcHelper.clearLatestBrowserTxn()
                        val res = withContext(Dispatchers.IO) {
                            runCatching {
                                CampusCloudOidcHelper.exchangeMobileTicketForAccount(
                                    loginTxn = txn.loginTxn,
                                    appTicket = ticket,
                                    codeVerifier = txn.pkce.codeVerifier
                                )
                            }
                        }
                        res.onSuccess { profile ->
                            val saved = repository.saveVerifiedXpeachCloudAccount(profile)
                            runCatching { repository.syncWithCampusCloud() }
                            feedbackBanner = "已登录并同步内测账户：${saved.displayName}（内测配额 🍑 %.2f）".format(
                                saved.peachBalanceMicropeach / 10_000.0
                            )
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(repository) {
        repository.ensureDefaultReferencePluginsInstalled()
        runCatching { repository.syncWithCampusCloud() }
        repository.refreshXpeachWalletBalance()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "云账号与扩展",
            subtitle = "云端定时选座与扩展管理",
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
                items = listOf("我的云账号", "扩展功能 (${plugins.size})"),
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it }
            )

            feedbackBanner?.let { msg ->
                XPeachCard(containerColor = XPeachTheme.secondaryAccentCardColor) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { feedbackBanner = null }) {
                            Text("知道了")
                        }
                    }
                }
            }

            if (selectedTab == 0) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "服务说明",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "课表、成绩、电费与快递等基础功能无需登录云账号。云端选座处于测试阶段，配额仅用于限制高频请求，不支持充值或提现。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val binding = cloudBinding
                if (binding == null) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "未登录云账号",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "登录后可使用云端定时选座（测试版）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { showBindDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("登录账号")
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://campus.yellowpeachxgp.com/")).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("打开网页端")
                            }
                        }
                    }
                } else {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = binding.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "ID: ${binding.ssoSubject}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            XPeachStatusDotText(
                                text = "已登录",
                                dotColor = XPeachTheme.readablePrimary
                            )
                        }

                        XPeachInsetDivider(verticalPadding = 10.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "测试配额",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "%.2f 🍑".format(
                                    binding.peachBalanceMicropeach / 10_000.0
                                ),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "每次预约成功消耗 1 配额，未成功或取消不扣减。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            enabled = !isSyncingSnapshot,
                            onClick = {
                                scope.launch {
                                    isSyncingSnapshot = true
                                    val snap = repository.syncWithCampusCloud().getOrNull()
                                    val refreshed = repository.refreshXpeachWalletBalance()
                                    isSyncingSnapshot = false
                                    if (snap != null || refreshed != null) {
                                        val bal = snap?.peachBalance ?: refreshed ?: (binding.peachBalanceMicropeach / 10_000.0)
                                        feedbackBanner = "已完成云端同步（内测配额 %.2f 🍑）".format(bal)
                                    } else {
                                        feedbackBanner = "云端繁忙，已保留本地数据（内测配额 %.2f 🍑）".format(
                                            binding.peachBalanceMicropeach / 10_000.0
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isSyncingSnapshot) "正在同步云端数据…" else "同步云端数据")
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        val refreshed = repository.refreshXpeachWalletBalance()
                                        if (refreshed != null) {
                                            feedbackBanner = "已刷新内测配额：%.2f 🍑".format(refreshed)
                                        } else {
                                            feedbackBanner = "当前内测配额：%.2f 🍑".format(
                                                binding.peachBalanceMicropeach / 10_000.0
                                            )
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("刷新配额")
                            }
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse("https://campus.yellowpeachxgp.com/")).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("网页端")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        runCatching { repository.unlinkXpeachCloudAccountAndRevokeCloudPlans() }.fold(
                                            onSuccess = { feedbackBanner = "已撤回云端凭据授权并退出账号；执行中或结果不确定的预约请到学校核对" },
                                            onFailure = { feedbackBanner = "未能确认撤回全部云端授权，已保留登录以便重试：${it.message.orEmpty()}" }
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("退出账号", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                val cloudPlans = bookingPlans.filter { it.executionMode == "CLOUD_HOSTED" }
                if (cloudPlans.isNotEmpty()) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "云端代抢记录 · 测试版 (${cloudPlans.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        cloudPlans.forEachIndexed { idx, plan ->
                            if (idx > 0) {
                                XPeachInsetDivider(verticalPadding = 8.dp)
                            } else {
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            val friendlyStatus = when (plan.status) {
                                "CONFIRMED" -> "抢座成功"
                                "FINAL_CHECK_OK" -> "就绪检查通过，等待准点抢座"
                                "PRECHECK_OK" -> "已完成登录保活，等待开抢"
                                "ARMED" -> "定时任务待命（未命中不消耗配额）"
                                "CANCELLED" -> "已取消，费用以订单为准"
                                else -> plan.lastDiagnosticMessage ?: "处理中"
                            }
                            val payLabel = when (plan.paymentState) {
                                "PAID" -> if (plan.priceMicropeach >= 0) "已消耗配额 (${plan.priceMicropeach / 10_000.0} 🍑)" else "已付款，金额待核实"
                                "REFUNDED" -> "已退回配额 🍑"
                                "DEBIT_PENDING" -> if (plan.priceMicropeach >= 0) "待扣 ${plan.priceMicropeach / 10_000.0} 🍑" else "费用待核实"
                                "PAYMENT_DUE" -> "内测配额不足"
                                "NOT_REQUIRED" -> "无需付费"
                                else -> "付款状态待核实"
                            }
                            val areaText = plan.preferredAreaName.trim().takeIf {
                                it.isNotEmpty() && !it.equals("null", ignoreCase = true)
                            } ?: "图书馆自习区"
                            val seatText = plan.preferredSeatLabel.trim().takeIf {
                                it.isNotEmpty() && !it.equals("null", ignoreCase = true)
                            } ?: "自动优选"
                            val dotColor = when (plan.status) {
                                "CONFIRMED" -> MaterialTheme.colorScheme.tertiary
                                "CANCELLED" -> MaterialTheme.colorScheme.outline
                                else -> XPeachTheme.readablePrimary
                            }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val repeatTag = if (plan.repeatDays > 1) {
                                        " · 连续${plan.repeatDays}天(剩${plan.remainingDays}天)"
                                    } else {
                                        ""
                                    }
                                    Text(
                                        text = "${plan.targetLocalDate} ${plan.targetLocalTime}$repeatTag",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 8.dp)
                                    )
                                    Text(
                                        text = payLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = XPeachTheme.readablePrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = "$areaText · ${seatText}号座",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                XPeachStatusDotText(
                                    text = friendlyStatus,
                                    dotColor = dotColor
                                )
                            }
                        }
                    }
                }
            } else {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "校园扩展功能",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 10.dp)
                        )
                        Button(
                            onClick = { showInstallPluginDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("添加扩展")
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "按需开启或关闭驿站卡片、图书馆座位快捷提示等附加功能。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                for (plugin in plugins) {
                    val slotSummary = remember(plugin.uiSlotsJson) {
                        parsePluginSlotSummary(plugin.uiSlotsJson)
                    }
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = plugin.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "标识：${plugin.pluginId} · 版本 ${plugin.activeVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (plugin.status == "ENABLED") {
                                XPeachStatusDotText(
                                    text = "已启用",
                                    dotColor = XPeachTheme.readablePrimary
                                )
                            } else {
                                XPeachStatusDotText(
                                    text = "已停用",
                                    dotColor = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        if (slotSummary != null) {
                            XPeachInsetDivider(verticalPadding = 8.dp)
                            Text(
                                text = slotSummary.first,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (slotSummary.second.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = slotSummary.second,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        val next = if (plugin.status == "ENABLED") "DISABLED" else "ENABLED"
                                        repository.upsertExtensionPlugin(
                                            plugin.copy(
                                                status = next,
                                                updatedAtEpochMillis = System.currentTimeMillis()
                                            )
                                        )
                                        feedbackBanner = if (next == "ENABLED") "已启用「${plugin.name}」" else "已停用「${plugin.name}」"
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (plugin.status == "ENABLED") "停用扩展" else "启用扩展")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        repository.removeExtensionPlugin(plugin.pluginId)
                                        feedbackBanner = "已移除「${plugin.name}」"
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("移除扩展", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showBindDialog) {
        XpeachNativeOidcLoginDialog(
            installEphemeralId = "eph_${repository.profileId}",
            onDismiss = { showBindDialog = false },
            onBound = { profile ->
                scope.launch {
                    val saved = repository.saveVerifiedXpeachCloudAccount(profile)
                    runCatching { repository.syncWithCampusCloud() }
                    showBindDialog = false
                    feedbackBanner = "已登录并同步内测账户：${saved.displayName}（内测配额 🍑 %.2f）".format(
                        saved.peachBalanceMicropeach / 10_000.0
                    )
                }
            }
        )
    }

    if (showInstallPluginDialog) {
        var pluginIdInput by remember { mutableStateOf("campus.custom.card") }
        var pluginNameInput by remember { mutableStateOf("自定义校园服务卡片") }
        var versionInput by remember { mutableStateOf("1.0.0") }

        XPeachAlertDialog(
            onDismissRequest = { showInstallPluginDialog = false },
            title = { Text("添加扩展功能") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pluginNameInput,
                        onValueChange = { pluginNameInput = it },
                        label = { Text("扩展名称") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pluginIdInput,
                        onValueChange = { pluginIdInput = it },
                        label = { Text("扩展标识") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = versionInput,
                        onValueChange = { versionInput = it },
                        label = { Text("版本号") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    shape = RoundedCornerShape(12.dp),
                    onClick = {
                        scope.launch {
                            repository.upsertExtensionPlugin(
                                InstalledExtensionPluginEntity(
                                    pluginId = pluginIdInput.trim(),
                                    name = pluginNameInput.trim(),
                                    activeVersion = versionInput.trim(),
                                    previousVersion = null,
                                    schemaFormat = "dis-extension/v2",
                                    status = "ENABLED",
                                    grantedPermissionsJson = """["ui.slot.render","storage.scoped"]""",
                                    uiSlotsJson = """[{"slot_id":"campus.services","title":"${pluginNameInput.trim()}"}]""",
                                    updatedAtEpochMillis = System.currentTimeMillis()
                                )
                            )
                            showInstallPluginDialog = false
                            feedbackBanner = "已添加「${pluginNameInput.trim()}」"
                        }
                    }
                ) {
                    Text("确认添加")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallPluginDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun XpeachNativeOidcLoginDialog(
    installEphemeralId: String,
    onDismiss: () -> Unit,
    onBound: (XpeachCloudAccountProfile) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var identifierInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var totpInput by remember { mutableStateOf("") }
    var needsTotp by remember { mutableStateOf(false) }

    var isSubmitting by remember { mutableStateOf(false) }
    var statusHint by remember { mutableStateOf("输入账号密码登录后将自动同步内测配额") }
    var errorHint by remember { mutableStateOf<String?>(null) }

    var activeBrowserTxn by remember { mutableStateOf<MobileAuthTransactionStart?>(null) }

    fun exchangeTicketAndBind(ticket: String, txn: MobileAuthTransactionStart) {
        if (isSubmitting) return
        isSubmitting = true
        errorHint = null
        statusHint = "授权成功，正在同步你的昵称与 🍑 内测配额…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CampusCloudOidcHelper.exchangeMobileTicketForAccount(
                        loginTxn = txn.loginTxn,
                        appTicket = ticket.trim(),
                        codeVerifier = txn.pkce.codeVerifier
                    )
                }
            }
            isSubmitting = false
            result.fold(
                onSuccess = { profile ->
                    CampusCloudOidcHelper.clearLatestBrowserTxn()
                    onBound(profile)
                },
                onFailure = { err ->
                    errorHint = "登录同步失败：${err.message}"
                }
            )
        }
    }

    DisposableEffect(lifecycleOwner, activeBrowserTxn) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val deepLinkPair = CampusCloudOidcHelper.consumeDeepLinkCallback()
                val currentTxn = activeBrowserTxn
                if (deepLinkPair != null && currentTxn != null) {
                    exchangeTicketAndBind(deepLinkPair.first, currentTxn)
                } else if (currentTxn != null && !isSubmitting) {
                    scope.launch {
                        val polledTicket = withContext(Dispatchers.IO) {
                            CampusCloudOidcHelper.pollMobileAuthTransactionTicket(currentTxn.loginTxn)
                        }
                        if (!polledTicket.isNullOrBlank()) {
                            exchangeTicketAndBind(polledTicket, currentTxn)
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun submitNativeLogin() {
        if (isSubmitting) return
        if (identifierInput.isBlank() || passwordInput.isBlank()) {
            errorHint = "请输入 XPeach 用户名（或邮箱）与登录密码"
            return
        }
        if (needsTotp && totpInput.trim().length < 6) {
            errorHint = "请输入 6 位两步验证码（或 2FA 恢复码）"
            return
        }
        isSubmitting = true
        errorHint = null
        statusHint = "正在验证账号并同步内测配额…"
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                CampusCloudOidcHelper.loginWithNativeOidcCredentials(
                    identifier = identifierInput.trim(),
                    password = passwordInput,
                    totpCode = totpInput.trim().takeIf { it.isNotEmpty() },
                    installEphemeralId = installEphemeralId
                )
            }
            isSubmitting = false
            when (res) {
                is NativeOidcLoginResult.Success -> {
                    onBound(res.profile)
                }
                is NativeOidcLoginResult.TotpRequired -> {
                    needsTotp = true
                    statusHint = "密码验证通过，请继续输入 6 位两步验证码"
                    errorHint = res.message
                }
                is NativeOidcLoginResult.Failure -> {
                    errorHint = res.message
                    statusHint = "请核对账号信息后重试"
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        XPeachGlassSurface(
            modifier = Modifier
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(22.dp),
            containerColor = XPeachTheme.dialogSurfaceColor
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                            text = "登录云账号",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        enabled = !isSubmitting,
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("关闭")
                    }
                }

                if (isSubmitting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                errorHint?.let { err ->
                    XPeachCard(containerColor = XPeachTheme.errorAccentCardColor) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                OutlinedTextField(
                    value = identifierInput,
                    onValueChange = { identifierInput = it },
                    label = { Text("用户名或邮箱") },
                    placeholder = { Text("例如 yellowpeach 或注册邮箱") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSubmitting,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("登录密码") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isSubmitting,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        TextButton(
                            onClick = { showPassword = !showPassword },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(if (showPassword) "隐藏" else "显示")
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (needsTotp) ImeAction.Next else ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (needsTotp) {
                    OutlinedTextField(
                        value = totpInput,
                        onValueChange = { totpInput = it.trim() },
                        label = { Text("两步验证码 (6 位动态码或恢复码)") },
                        placeholder = { Text("请输入身份验证器中的 6 位动态码") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSubmitting,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { needsTotp = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("开启了两步验证？点击填写验证码")
                        }
                    }
                }

                Button(
                    enabled = !isSubmitting && identifierInput.isNotBlank() && passwordInput.isNotBlank(),
                    onClick = { submitNativeLogin() },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isSubmitting) "正在登录…" else "登录")
                }

                XPeachInsetDivider(verticalPadding = 4.dp)

                OutlinedButton(
                    enabled = !isSubmitting,
                    onClick = {
                        scope.launch {
                            isSubmitting = true
                            errorHint = null
                            statusHint = "正在打开浏览器授权…"
                            val res = withContext(Dispatchers.IO) {
                                runCatching {
                                    CampusCloudOidcHelper.startRemoteMobileAuthTransaction(
                                        installEphemeralId = installEphemeralId
                                    )
                                }
                            }
                            isSubmitting = false
                            res.fold(
                                onSuccess = { txn ->
                                    activeBrowserTxn = txn
                                    statusHint = "请在系统浏览器完成授权，完成后将自动跳回 App"
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(txn.authorizeUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                },
                                onFailure = { err ->
                                    errorHint = "无法发起浏览器授权：${err.message}"
                                }
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("通行密钥 / 浏览器授权登录")
                }
            }
        }
    }
}

private fun parsePluginSlotSummary(uiSlotsJson: String): Pair<String, String>? {
    return runCatching {
        val arr = JSONArray(uiSlotsJson)
        if (arr.length() == 0) return null
        val obj = arr.optJSONObject(0) ?: return null
        val title = obj.optString("title").trim()
        val subtitle = obj.optString("subtitle").trim()
        if (title.isEmpty() && subtitle.isEmpty()) null else (title to subtitle)
    }.getOrNull()
}

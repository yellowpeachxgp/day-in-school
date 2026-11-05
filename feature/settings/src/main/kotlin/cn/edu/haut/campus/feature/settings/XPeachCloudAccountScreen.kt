package cn.edu.haut.campus.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
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
                            feedbackBanner = "已登录并同步 XPeach 账户：${saved.displayName}（余额 🍑 %.2f）".format(
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
            title = "云账号与扩展功能",
            subtitle = "用于关机自动代抢图书馆座位",
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

            if (selectedTab == 0) {
                XPeachCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "关于云账号",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "课表、成绩、电费、空教室、本机自动抢座与查快递均无需登录云账号。仅在使用「云端关机代抢图书馆座位」时需要登录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val binding = cloudBinding
                if (binding == null) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "尚未登录云账号",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "登录后可同步昵称与账户余额，开启云端关机代抢。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showBindDialog = true },
                                shape = RoundedCornerShape(12.dp)
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
                                shape = RoundedCornerShape(12.dp)
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
                            Column(modifier = Modifier.weight(1f)) {
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
                            XPeachStatusBadge(
                                text = "已登录",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "可用余额：%.2f 🍑".format(
                                    binding.peachBalanceMicropeach / 10_000.0
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "仅在云端成功抢到座位后结算 0.10 🍑；未抢到或提前取消不扣费。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
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
                                        feedbackBanner = "已完成云端同步（余额 %.2f 🍑）".format(bal)
                                    } else {
                                        feedbackBanner = "云端繁忙，已保留本地数据（%.2f 🍑）".format(
                                            binding.peachBalanceMicropeach / 10_000.0
                                        )
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
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
                                            feedbackBanner = "已刷新最新余额：%.2f 🍑".format(refreshed)
                                        } else {
                                            feedbackBanner = "当前余额：%.2f 🍑".format(
                                                binding.peachBalanceMicropeach / 10_000.0
                                            )
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("刷新余额", maxLines = 1, softWrap = false)
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
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("网页端", maxLines = 1, softWrap = false)
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        repository.unlinkXpeachCloudAccountAndRevokeCloudPlans()
                                        feedbackBanner = "已退出账号，并取消未执行的云端代抢任务"
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("退出账号", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }

                val cloudPlans = bookingPlans.filter { it.executionMode == "CLOUD_HOSTED" }
                if (cloudPlans.isNotEmpty()) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "云端代抢记录 (${cloudPlans.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        for (plan in cloudPlans) {
                            val friendlyStatus = when (plan.status) {
                                "CONFIRMED" -> "抢座成功"
                                "FINAL_CHECK_OK" -> "就绪检查通过，等待准点抢座"
                                "PRECHECK_OK" -> "已完成登录保活，等待开抢"
                                "ARMED" -> "定时任务待命（未抢成不扣费）"
                                "CANCELLED" -> "已取消（未扣费）"
                                else -> plan.lastDiagnosticMessage ?: "处理中"
                            }
                            val payLabel = when (plan.paymentState) {
                                "PAID" -> "已结算 (${plan.priceMicropeach / 10_000.0} 🍑)"
                                "REFUNDED" -> "已退回 🍑"
                                "DEBIT_PENDING" -> "抢成后扣 0.1 🍑"
                                "PAYMENT_DUE" -> "余额不足待补缴"
                                else -> "免费"
                            }
                            XPeachWell(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${plan.targetLocalDate} ${plan.targetLocalTime} · ${plan.preferredAreaName} ${plan.preferredSeatLabel}号",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    XPeachStatusBadge(text = payLabel)
                                }
                                Text(
                                    text = friendlyStatus,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "校园扩展功能",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "按需开启或关闭驿站卡片、图书馆座位快捷提示等附加功能。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { showInstallPluginDialog = true },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("添加扩展")
                        }
                    }
                }

                for (plugin in plugins) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = plugin.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "版本 ${plugin.activeVersion}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            XPeachStatusBadge(
                                text = if (plugin.status == "ENABLED") "已启用" else "已停用",
                                containerColor = if (plugin.status == "ENABLED") {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                contentColor = if (plugin.status == "ENABLED") {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(if (plugin.status == "ENABLED") "停用" else "启用")
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        repository.removeExtensionPlugin(plugin.pluginId)
                                        feedbackBanner = "已移除「${plugin.name}」"
                                    }
                                }
                            ) {
                                Text("移除", color = MaterialTheme.colorScheme.error)
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
                    feedbackBanner = "已登录并同步 XPeach 账户：${saved.displayName}（余额 🍑 %.2f）".format(
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

        AlertDialog(
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
    var statusHint by remember { mutableStateOf("输入账号密码登录后将自动同步余额") }
    var errorHint by remember { mutableStateOf<String?>(null) }

    var activeBrowserTxn by remember { mutableStateOf<MobileAuthTransactionStart?>(null) }

    fun exchangeTicketAndBind(ticket: String, txn: MobileAuthTransactionStart) {
        if (isSubmitting) return
        isSubmitting = true
        errorHint = null
        statusHint = "授权成功，正在同步你的昵称与 🍑 余额…"
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
        statusHint = "正在验证账号并同步余额…"
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
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
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
                    Column(modifier = Modifier.weight(1f)) {
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
                        onClick = onDismiss
                    ) {
                        Text("关闭")
                    }
                }

                if (isSubmitting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                errorHint?.let { err ->
                    XPeachCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
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
                        TextButton(onClick = { showPassword = !showPassword }) {
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
                        TextButton(onClick = { needsTotp = true }) {
                            Text("开启了两步验证？点击填写验证码")
                        }
                    }
                }

                Button(
                    enabled = !isSubmitting && identifierInput.isNotBlank() && passwordInput.isNotBlank(),
                    onClick = { submitNativeLogin() },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isSubmitting) "正在登录…" else "登录")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("通行密钥 / 浏览器授权登录")
                }
            }
        }
    }
}

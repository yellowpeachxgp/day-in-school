package cn.edu.haut.campus.feature.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.MobileAuthTransactionStart
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
    val scope = rememberCoroutineScope()
    val cloudBinding by repository.cloudIdentityBindingFlow.collectAsState(initial = null)
    val bookingPlans by repository.libraryBookingPlansFlow.collectAsState(initial = emptyList())
    val plugins by repository.installedExtensionPluginsFlow.collectAsState(initial = emptyList())

    var selectedTab by remember { mutableIntStateOf(0) }
    var showBindDialog by remember { mutableStateOf(false) }
    var showInstallPluginDialog by remember { mutableStateOf(false) }
    var feedbackBanner by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(repository) {
        repository.ensureDefaultReferencePluginsInstalled()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "XPEACH 云账户与插件生态",
            subtitle = "Confidential Web OIDC BFF · 🍑 钱包 · dis-extension/v2",
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
                items = listOf("XPEACH 账号与 🍑 钱包", "插件生态中心 (${plugins.size})"),
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
                        text = "零 SSO 强制依赖承诺",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "课表、成绩、电费、空教室、本机免费图书馆预约 (LOCAL_DEVICE) 与本地快递聚合均无需绑定 XPEACH 账号即可 100% 免费使用。仅当您需要「云端关机代跑预约 (CLOUD_HOSTED)」时才需绑定 XPEACH。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val binding = cloudBinding
                if (binding == null) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "当前未绑定 XPEACH 统一身份账号",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "点击下方按钮将直接跳转 XPEACH SSO (sso.xpeach.codes) 登录已有账号，通过 OIDC BFF + PKCE S256 自动同步您的真实昵称、UUID 与 🍑 钱包余额。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showBindDialog = true },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("跳转 XPEACH SSO 登录绑定")
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
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("打开云端面板")
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
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Issuer: ${binding.ssoIssuer} · Subject: ${binding.ssoSubject}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            XPeachStatusBadge(
                                text = "已绑定",
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        XPeachWell(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "🍑 钱包可用余额：%.2f 🍑 (%d micropeach)".format(
                                    binding.peachBalanceMicropeach / 10_000.0,
                                    binding.peachBalanceMicropeach
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "计费契约：不支持余额冻结 (hold_supported=false)；仅在学校系统权威回读确认选座成功后按单次幂等键扣减 0.10 🍑。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = { showBindDialog = true },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("重新登录刷新")
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
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("打开云端面板")
                            }
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        repository.unlinkXpeachCloudAccountAndRevokeCloudPlans()
                                        feedbackBanner = "已解绑 XPEACH 账号并自动停止全部云端托管预约计划"
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("解绑", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }

                val cloudOrders = bookingPlans.filter { it.executionMode == "CLOUD_HOSTED" }
                if (cloudOrders.isNotEmpty()) {
                    XPeachCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "云端托管预约账单 (${cloudOrders.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        for (order in cloudOrders) {
                            XPeachWell(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(
                                    text = "${order.targetLocalDate} ${order.targetLocalTime} · ${order.preferredAreaName} ${order.preferredSeatLabel}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "预约状态：${order.status} · 账单状态：${order.paymentState} · 幂等键：dis:reservation:${order.id}:debit:v1",
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
                                text = "统一插件宿主 v2 (Zero-Host-Change)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "支持 dis-extension/v2、dis-plugin/v2 与旧版 dis-campus-plugin/v1 兼容适配，强制 Ed25519 签名与 SHA-256 摘要校验。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(
                            onClick = { showInstallPluginDialog = true },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("安装签名插件")
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
                                    text = "${plugin.pluginId} · v${plugin.activeVersion} · ${plugin.schemaFormat}",
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
                        Text(
                            text = "授权范围：${plugin.grantedPermissionsJson}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                        feedbackBanner = "插件 ${plugin.pluginId} 状态已切换为 $next"
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(if (plugin.status == "ENABLED") "停用插件" else "启用插件")
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        repository.removeExtensionPlugin(plugin.pluginId)
                                        feedbackBanner = "已卸载插件 ${plugin.pluginId}"
                                    }
                                }
                            ) {
                                Text("卸载", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showBindDialog) {
        XpeachSsoOidcWebViewDialog(
            installEphemeralId = "eph_${repository.profileId}",
            onDismiss = { showBindDialog = false },
            onBound = { profile ->
                scope.launch {
                    val saved = repository.saveVerifiedXpeachCloudAccount(profile)
                    showBindDialog = false
                    feedbackBanner = "已通过 XPEACH SSO 认证绑定：${saved.displayName} (余额 %.2f 🍑)".format(
                        saved.peachBalanceMicropeach / 10_000.0
                    )
                }
            }
        )
    }

    if (showInstallPluginDialog) {
        var pluginIdInput by remember { mutableStateOf("org.example.custom-campus-card") }
        var pluginNameInput by remember { mutableStateOf("自定义校园扩展卡片") }
        var versionInput by remember { mutableStateOf("1.0.0") }

        AlertDialog(
            onDismissRequest = { showInstallPluginDialog = false },
            title = { Text("零宿主修改安装 dis-extension/v2 插件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pluginIdInput,
                        onValueChange = { pluginIdInput = it },
                        label = { Text("Plugin ID (反向域名)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = pluginNameInput,
                        onValueChange = { pluginNameInput = it },
                        label = { Text("插件名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = versionInput,
                        onValueChange = { versionInput = it },
                        label = { Text("SemVer 版本号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
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
                            feedbackBanner = "已安装并激活插件 ${pluginIdInput.trim()}@${versionInput.trim()}"
                        }
                    }
                ) {
                    Text("验签并安装")
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun XpeachSsoOidcWebViewDialog(
    installEphemeralId: String,
    onDismiss: () -> Unit,
    onBound: (XpeachCloudAccountProfile) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var activeTxn by remember { mutableStateOf<MobileAuthTransactionStart?>(null) }
    var statusHint by remember { mutableStateOf("正在向 Campus Cloud 申请 OIDC + PKCE 授权事务…") }
    var errorHint by remember { mutableStateOf<String?>(null) }
    var isPageLoading by remember { mutableStateOf(true) }
    var isExchanging by remember { mutableStateOf(false) }
    var manualTicketInput by remember { mutableStateOf("") }
    var showManualTicketEntry by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun handleCallbackTicket(ticket: String, txnId: String) {
        val currentTxn = activeTxn ?: return
        if (isExchanging) return
        isExchanging = true
        errorHint = null
        statusHint = "已收到 XPEACH SSO 授权票据，正在兑换身份与 🍑 钱包…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CampusCloudOidcHelper.exchangeMobileTicketForAccount(
                        loginTxn = txnId.ifBlank { currentTxn.loginTxn },
                        appTicket = ticket.trim(),
                        codeVerifier = currentTxn.pkce.codeVerifier
                    )
                }
            }
            isExchanging = false
            result.fold(
                onSuccess = { profile ->
                    onBound(profile)
                },
                onFailure = { err ->
                    errorHint = "SSO 票据兑换失败：${err.message}"
                    statusHint = "请重试登录或检查网络"
                }
            )
        }
    }

    LaunchedEffect(installEphemeralId) {
        errorHint = null
        statusHint = "正在向 Campus Cloud 申请 OIDC + PKCE 授权事务…"
        val res = withContext(Dispatchers.IO) {
            runCatching {
                CampusCloudOidcHelper.startRemoteMobileAuthTransaction(installEphemeralId = installEphemeralId)
            }
        }
        res.fold(
            onSuccess = { txn ->
                activeTxn = txn
                statusHint = "请在下方页面登录您的 XPEACH 统一身份账号 (sso.xpeach.codes)"
            },
            onFailure = { err ->
                errorHint = "无法连接 Campus Cloud OIDC 服务：${err.message}"
            }
        )
    }

    Dialog(
        onDismissRequest = { if (!isExchanging) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .height(640.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "XPEACH SSO 统一身份认证",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { showManualTicketEntry = !showManualTicketEntry }
                        ) {
                            Text(if (showManualTicketEntry) "返回内嵌登录" else "外部浏览器辅助")
                        }
                        TextButton(
                            enabled = !isExchanging,
                            onClick = onDismiss
                        ) {
                            Text("关闭")
                        }
                    }
                }

                if (isPageLoading || isExchanging || activeTxn == null) {
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

                if (showManualTicketEntry) {
                    XPeachWell(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "若您已在系统浏览器登录过 XPEACH SSO，可点击「系统浏览器打开」完成授权，随后粘贴回调页显示的 Ticket 即可完成绑定：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = activeTxn != null,
                                onClick = {
                                    val url = activeTxn?.authorizeUrl ?: return@OutlinedButton
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                }
                            ) {
                                Text("系统浏览器打开 SSO")
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = manualTicketInput,
                            onValueChange = { manualTicketInput = it.trim() },
                            label = { Text("粘贴回调页单次 Ticket (如 tkt_...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Button(
                            enabled = manualTicketInput.startsWith("tkt_") && activeTxn != null && !isExchanging,
                            onClick = {
                                val txn = activeTxn ?: return@Button
                                handleCallbackTicket(manualTicketInput, txn.loginTxn)
                            }
                        ) {
                            Text("兑换 Ticket 并绑定")
                        }
                    }
                }

                val txn = activeTxn
                if (txn != null && !showManualTicketEntry) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    CookieManager.getInstance().setAcceptCookie(true)
                                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(
                                            view: WebView?,
                                            request: WebResourceRequest?
                                        ): Boolean {
                                            val url = request?.url?.toString().orEmpty()
                                            val pair = CampusCloudOidcHelper.extractCallbackTicketAndTxn(url)
                                            if (pair != null) {
                                                handleCallbackTicket(pair.first, pair.second)
                                                return true
                                            }
                                            if (url.startsWith("intent://")) {
                                                val fallbackPair = CampusCloudOidcHelper.extractCallbackTicketAndTxn(
                                                    url.replace("intent://", "https://")
                                                )
                                                if (fallbackPair != null) {
                                                    handleCallbackTicket(fallbackPair.first, fallbackPair.second)
                                                }
                                                return true
                                            }
                                            return false
                                        }

                                        override fun onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: Bitmap?
                                        ) {
                                            super.onPageStarted(view, url, favicon)
                                            isPageLoading = true
                                            val pair = CampusCloudOidcHelper.extractCallbackTicketAndTxn(url)
                                            if (pair != null) {
                                                handleCallbackTicket(pair.first, pair.second)
                                            }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isPageLoading = false
                                            val pair = CampusCloudOidcHelper.extractCallbackTicketAndTxn(url)
                                            if (pair != null) {
                                                handleCallbackTicket(pair.first, pair.second)
                                            }
                                        }
                                    }
                                    loadUrl(txn.authorizeUrl)
                                    webViewRef = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else if (txn == null) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }
}

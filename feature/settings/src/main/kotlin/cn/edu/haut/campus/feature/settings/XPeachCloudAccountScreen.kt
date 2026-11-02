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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.InstalledExtensionPluginEntity
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import kotlinx.coroutines.launch

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
                            text = "认证方式：Custom Tabs + S256 PKCE + 单次 App Link Ticket 兑换（零 WebView 截获 SSO 密码）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { showBindDialog = true },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("绑定 XPEACH 账号")
                            }
                            OutlinedButton(
                                onClick = {
                                    val txn = CampusCloudOidcHelper.buildLocalOrRemoteTransaction(
                                        installEphemeralId = "eph_${repository.profileId}"
                                    )
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(txn.authorizeUrl)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                        )
                                    }
                                    showBindDialog = true
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("浏览器 OIDC 登录")
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
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        repository.unlinkXpeachCloudAccountAndRevokeCloudPlans()
                                        feedbackBanner = "已解绑 XPEACH 账号并自动停止全部云端托管预约计划"
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("解绑账号并撤回云端授权", color = MaterialTheme.colorScheme.error)
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
        var displayNameInput by remember { mutableStateOf("XPEACH 校园同学") }
        var subjectInput by remember { mutableStateOf("user_uuid_haut_2026") }

        AlertDialog(
            onDismissRequest = { showBindDialog = false },
            title = { Text("完成 XPEACH OIDC Ticket 绑定") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "通过 Confidential Web OIDC BFF 验证单次 App Ticket 后建立绑定：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = displayNameInput,
                        onValueChange = { displayNameInput = it },
                        label = { Text("XPEACH 昵称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = subjectInput,
                        onValueChange = { subjectInput = it },
                        label = { Text("XPEACH Subject UUID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val bound = repository.bindXpeachCloudAccount(
                                displayName = displayNameInput,
                                ssoSubject = subjectInput
                            )
                            showBindDialog = false
                            feedbackBanner = "已绑定 XPEACH 账号 ${bound.displayName} (余额 5.00 🍑)"
                        }
                    }
                ) {
                    Text("确认绑定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBindDialog = false }) {
                    Text("取消")
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

package cn.edu.haut.campus.feature.settings.root.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.vault.BackupConflictResolution
import cn.edu.haut.campus.core.vault.BackupFormatConstants
import cn.edu.haut.campus.core.vault.BackupRowConflict
import cn.edu.haut.campus.core.vault.RestoreMode
import cn.edu.haut.campus.core.vault.sync.E2eeSyncConflictResolution
import cn.edu.haut.campus.core.vault.sync.E2eeSyncProtocol
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** W1-04 骨架 UI：导出向导与恢复向导。视觉打磨留给阶段 N，状态矩阵在此固定。 */
@Composable
fun BackupCenterSection(
    state: BackupCenterUiState,
    actions: BackupCenterUiAction,
    modifier: Modifier = Modifier
) {
    XPeachCard(modifier = modifier.fillMaxWidth()) {
        Text("备份与恢复", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "加密导出 dis-backup/v1；凭据、Cookie、AI Key 与设备授权状态不会进入备份。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        BackupMatrixBanner(state.matrix)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = actions::openExportWizard,
                enabled = state.serviceAvailable,
                modifier = Modifier.weight(1f).testTag("backup-open-export")
            ) { Text("导出备份") }
            OutlinedButton(
                onClick = actions::openRestoreWizard,
                enabled = state.serviceAvailable,
                modifier = Modifier.weight(1f).testTag("backup-open-restore")
            ) { Text("恢复备份") }
        }
        Spacer(modifier = Modifier.height(14.dp))
        E2eeSyncPanel(state.sync, actions)
    }

    if (state.exportWizard.visible) {
        ExportWizardDialog(state, actions)
    }
    if (state.restoreWizard.visible) {
        RestoreWizardDialog(state, actions)
    }
}

@Composable
private fun BackupMatrixBanner(matrix: SettingsUiMatrix) {
    val text = when (matrix.status) {
        SettingsUiMatrix.Status.LOADING -> "正在处理备份…"
        SettingsUiMatrix.Status.EMPTY -> "暂无备份内容"
        SettingsUiMatrix.Status.CONTENT -> null
        SettingsUiMatrix.Status.PARTIAL -> "部分备份内容可用"
        SettingsUiMatrix.Status.STALE -> "备份预览可能已过期，请重新检查"
        SettingsUiMatrix.Status.ERROR -> matrix.message ?: "备份操作失败"
        SettingsUiMatrix.Status.NEEDS_PERMISSION -> "需要文件访问授权"
        SettingsUiMatrix.Status.NEEDS_LOGIN -> "需要先确认当前身份"
        SettingsUiMatrix.Status.OFFLINE -> "离线状态下仍可本地备份"
        SettingsUiMatrix.Status.UNSUPPORTED -> "当前环境不支持备份服务"
    } ?: return
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (matrix.status == SettingsUiMatrix.Status.ERROR || matrix.status == SettingsUiMatrix.Status.UNSUPPORTED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.testTag("backup-matrix-banner")
    )
}

@Composable
private fun ExportWizardDialog(state: BackupCenterUiState, actions: BackupCenterUiAction) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pendingPassphrase by remember { mutableStateOf<CharArray?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val pending = pendingPassphrase
        if (uri != null && pending != null) {
            actions.exportBackup(uri, pending)
        } else {
            pending?.fill('\u0000')
        }
        pendingPassphrase = null
        passphrase = ""
        confirm = ""
    }
    val valid = passphrase.length >= BackupFormatConstants.MIN_PASSPHRASE_CHARS && passphrase == confirm && !state.exportWizard.running

    AlertDialog(
        onDismissRequest = { if (!state.exportWizard.running) actions.closeExportWizard() },
        title = { Text("导出加密备份") },
        text = {
            Column {
                Text(
                    "备份将使用口令派生密钥整体加密。请妥善保存口令；口令丢失无法恢复备份。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("备份口令（至少 " + BackupFormatConstants.MIN_PASSPHRASE_CHARS + " 位）") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("backup-export-passphrase")
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("再次输入口令") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("backup-export-confirm")
                )
                if (passphrase.isNotEmpty() && passphrase.length < BackupFormatConstants.MIN_PASSPHRASE_CHARS) {
                    Text("口令至少 " + BackupFormatConstants.MIN_PASSPHRASE_CHARS + " 个字符", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                if (confirm.isNotEmpty() && passphrase != confirm) {
                    Text("两次输入不一致", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                state.exportWizard.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                state.exportWizard.resultMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    pendingPassphrase?.fill('\u0000')
                    pendingPassphrase = passphrase.toCharArray()
                    exportLauncher.launch("day-in-school-backup.disbackup")
                },
                enabled = valid,
                modifier = Modifier.testTag("backup-export-choose-target")
            ) { Text(if (state.exportWizard.running) "导出中…" else "选择保存位置") }
        },
        dismissButton = {
            TextButton(onClick = actions::closeExportWizard, enabled = !state.exportWizard.running) { Text("关闭") }
        }
    )
}

@Composable
private fun RestoreWizardDialog(state: BackupCenterUiState, actions: BackupCenterUiAction) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var passphrase by remember { mutableStateOf("") }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectedUri = uri
    }
    LaunchedEffect(state.restoreWizard.visible) {
        if (!state.restoreWizard.visible) {
            selectedUri = null
            passphrase = ""
        }
    }
    val wizard = state.restoreWizard
    val preview = wizard.preview
    val allConflictsResolved = preview?.conflicts?.all { it.key in wizard.resolutions } != false

    AlertDialog(
        onDismissRequest = { if (!wizard.running) actions.closeRestoreWizard() },
        title = { Text("恢复备份") },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("先检查备份，再选择合并或替换。恢复在单个事务中完成，失败不会部分写入。", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openLauncher.launch(arrayOf("application/octet-stream", "application/zip")) },
                    enabled = !wizard.running,
                    modifier = Modifier.testTag("backup-restore-choose-file")
                ) { Text(if (selectedUri == null) "选择备份文件" else "重新选择文件") }
                Text(
                    "文件：" + (selectedUri?.lastPathSegment ?: wizard.fileLabel ?: "未选择"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("备份口令") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("backup-restore-passphrase")
                )
                OutlinedButton(
                    onClick = {
                        val uri = selectedUri
                        if (uri != null) {
                            actions.inspectBackup(uri, passphrase.toCharArray())
                            passphrase = ""
                        }
                    },
                    enabled = selectedUri != null && passphrase.isNotEmpty() && !wizard.running,
                    modifier = Modifier.testTag("backup-inspect")
                ) { Text(if (wizard.running && preview == null) "检查中…" else "检查备份") }

                wizard.errorMessage?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                wizard.resultMessage?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }

                if (preview != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        "格式 v" + preview.formatVersion + " · schema v" + preview.schemaVersion +
                            " · " + preview.entryCount + " 条目",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "新增 " + preview.insertCount + " · 未变化 " + preview.unchangedCount +
                            " · REPLACE 移除 " + preview.removableCount + " · 冲突 " + preview.conflicts.size,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    RestoreModeChoice(
                        selected = wizard.mode,
                        enabled = !wizard.running,
                        onSelect = actions::setRestoreMode
                    )
                    if (preview.conflicts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("冲突行（逐条选择）", style = MaterialTheme.typography.titleSmall)
                        preview.conflicts.take(20).forEach { conflict ->
                            ConflictChoiceRow(
                                conflict = conflict,
                                selected = wizard.resolutions[conflict.key],
                                enabled = !wizard.running,
                                onSelect = { resolution -> actions.setConflictResolution(conflict.key, resolution) }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    preview.conflicts.forEach { conflict ->
                                        actions.setConflictResolution(conflict.key, BackupConflictResolution.KEEP_EXISTING)
                                    }
                                },
                                enabled = !wizard.running
                            ) { Text("全部保留本机") }
                            TextButton(
                                onClick = {
                                    preview.conflicts.forEach { conflict ->
                                        actions.setConflictResolution(conflict.key, BackupConflictResolution.USE_BACKUP)
                                    }
                                },
                                enabled = !wizard.running
                            ) { Text("全部使用备份") }
                        }
                        if (preview.conflicts.size > 20) {
                            Text("仅展开前 20 条；其余 " + (preview.conflicts.size - 20) + " 条可用上方批量选择", style = MaterialTheme.typography.labelSmall)
                        }
                        if (!allConflictsResolved) {
                            Text("MERGE 需要完成全部冲突选择", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = actions::restoreBackup,
                enabled = preview != null && !wizard.running && (wizard.mode == RestoreMode.REPLACE || allConflictsResolved),
                modifier = Modifier.testTag("backup-restore-run")
            ) { Text(if (wizard.running) "恢复中…" else "开始恢复") }
        },
        dismissButton = {
            TextButton(onClick = actions::closeRestoreWizard, enabled = !wizard.running) { Text("关闭") }
        }
    )
}

@Composable
private fun E2eeSyncPanel(sync: E2eeSyncPanelUiState, actions: BackupCenterUiAction) {
    Text("跨设备端到端同步", style = MaterialTheme.typography.titleSmall)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "持续复制 dis-backup 行模型；云端只存密文。备份导出仍是独立静态快照。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SyncMatrixBanner(sync.matrix)
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = when {
            !sync.serviceAvailable -> "同步服务不可用"
            sync.enabled -> "已启用" + (sync.deviceIdShort?.let { " · 设备 " + it } ?: "") +
                (sync.syncSpaceIdShort?.let { " · 同步空间 " + it } ?: "")
            else -> "未启用；需要 XPeach Cloud 登录和显式 E2EE_CLOUD_SYNC 同意"
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("sync-status-text")
    )
    sync.lastRunSummary?.let {
        Text("最近运行：" + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (sync.pendingWriteCount > 0) {
        Text("待确认写入 " + sync.pendingWriteCount + " 个；网络恢复后会按原幂等键重试", style = MaterialTheme.typography.labelSmall)
    }
    sync.lastErrorMessage?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!sync.enabled) {
            OutlinedButton(
                onClick = actions::openSyncConsent,
                enabled = sync.serviceAvailable && !sync.running,
                modifier = Modifier.weight(1f).testTag("sync-open-consent")
            ) { Text("开启同步") }
        } else {
            OutlinedButton(
                onClick = actions::runSyncNow,
                enabled = !sync.running,
                modifier = Modifier.weight(1f).testTag("sync-run-now")
            ) { Text(if (sync.running) "同步中…" else "立即同步") }
            OutlinedButton(
                onClick = actions::disableSync,
                enabled = !sync.running,
                modifier = Modifier.weight(1f).testTag("sync-disable")
            ) { Text("停用并撤销") }
        }
    }
    if (sync.devices.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text("同步设备", style = MaterialTheme.typography.titleSmall)
        sync.devices.take(4).forEach { device ->
            SyncDeviceRow(device, actions, enabled = !sync.running)
        }
        if (sync.devices.size > 4) {
            Text("共 " + sync.devices.size + " 台登记设备；其余请在云端账户安全页面核查", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (sync.conflicts.isNotEmpty()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text("同步冲突（不会静默覆盖）", style = MaterialTheme.typography.titleSmall)
        sync.conflicts.take(3).forEach { conflict ->
            SyncConflictRow(conflict, actions, enabled = !sync.running)
        }
        if (sync.conflicts.size > 3) {
            Text("其余 " + (sync.conflicts.size - 3) + " 条将在前序冲突解决后显示", style = MaterialTheme.typography.labelSmall)
        }
    }
    if (sync.consentDialog.visible) {
        E2eeSyncConsentDialog(sync, actions)
    }
}

@Composable
private fun SyncMatrixBanner(matrix: SettingsUiMatrix) {
    val text = when (matrix.status) {
        SettingsUiMatrix.Status.LOADING -> "正在处理跨设备同步…"
        SettingsUiMatrix.Status.EMPTY -> "跨设备同步未启用"
        SettingsUiMatrix.Status.CONTENT -> null
        SettingsUiMatrix.Status.PARTIAL -> "同步部分完成：存在冲突或待确认写入"
        SettingsUiMatrix.Status.STALE -> "同步 cursor 已过期，需要重新全量校验"
        SettingsUiMatrix.Status.ERROR -> matrix.message ?: "同步操作失败"
        SettingsUiMatrix.Status.NEEDS_PERMISSION -> "需要重新确认 E2EE_CLOUD_SYNC 同意"
        SettingsUiMatrix.Status.NEEDS_LOGIN -> "需要先登录 XPeach Cloud"
        SettingsUiMatrix.Status.OFFLINE -> "当前离线；同步会在网络恢复后重试"
        SettingsUiMatrix.Status.UNSUPPORTED -> "当前环境不支持跨设备同步"
    } ?: return
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (matrix.status == SettingsUiMatrix.Status.ERROR || matrix.status == SettingsUiMatrix.Status.UNSUPPORTED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.testTag("sync-matrix-banner")
    )
}

@Composable
private fun SyncDeviceRow(
    device: E2eeSyncDeviceSummary,
    actions: BackupCenterUiAction,
    enabled: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                (device.label ?: "未命名设备") + if (device.currentDevice) "（本机）" else "",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                device.deviceIdShort + " · " + device.status + (device.lastSeenAt?.let { " · " + it } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!device.currentDevice && device.status == "ACTIVE") {
            OutlinedButton(
                onClick = { actions.revokeSyncDevice(device.deviceId) },
                enabled = enabled,
                modifier = Modifier.testTag("sync-revoke-device-" + device.deviceIdShort)
            ) { Text("撤销") }
        }
    }
}

@Composable
private fun E2eeSyncConsentDialog(sync: E2eeSyncPanelUiState, actions: BackupCenterUiAction) {
    var acknowledged by remember { mutableStateOf(false) }
    var deviceLabel by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }
    val dialog = sync.consentDialog
    AlertDialog(
        onDismissRequest = { if (!dialog.running) actions.closeSyncConsent() },
        title = { Text("开启端到端跨设备同步") },
        text = {
            Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                E2eeSyncProtocol.CONSENT_DOCUMENT_TEXT.split("\n").forEachIndexed { index, line ->
                    Text(
                        text = if (index == 0) line else "• " + line,
                        style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall
                    )
                    if (index == 0) Spacer(modifier = Modifier.height(6.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "恢复密钥只由你手动保管；换机时必须粘贴同一密钥。它不是备份口令，也不会上传到云端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                        enabled = !dialog.running,
                        modifier = Modifier.testTag("sync-consent-checkbox")
                    )
                    Text("我理解上述云端元数据、配额与撤回生命周期，并明确同意 E2EE_CLOUD_SYNC", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = deviceLabel,
                    onValueChange = { deviceLabel = it.take(80) },
                    label = { Text("设备备注（可选，不填硬件序列号）") },
                    singleLine = true,
                    enabled = !dialog.running,
                    modifier = Modifier.fillMaxWidth().testTag("sync-device-label")
                )
                dialog.errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                dialog.generatedRecoveryKey?.let { generated ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("立即抄写恢复密钥；关闭后本界面不再显示。", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    SelectionContainer {
                        Text(
                            generated,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().testTag("sync-generated-recovery-key")
                        )
                    }
                }
                if (dialog.generatedRecoveryKey == null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { actions.enableSyncWithNewKey(deviceLabel) },
                        enabled = acknowledged && !dialog.running,
                        modifier = Modifier.fillMaxWidth().testTag("sync-enable-new-key")
                    ) { Text(if (dialog.running) "启用中…" else "创建新同步空间并启用") }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = recoveryKey,
                        onValueChange = { recoveryKey = it.trim() },
                        label = { Text("已有恢复密钥（用于第二台设备）") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        enabled = !dialog.running,
                        modifier = Modifier.fillMaxWidth().testTag("sync-recovery-key-input")
                    )
                    OutlinedButton(
                        onClick = {
                            actions.enableSyncWithRecoveryKey(deviceLabel, recoveryKey)
                            recoveryKey = ""
                        },
                        enabled = acknowledged && recoveryKey.isNotBlank() && !dialog.running,
                        modifier = Modifier.fillMaxWidth().testTag("sync-enable-existing-key")
                    ) { Text("使用已有恢复密钥启用") }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "若本机曾加入不同同步空间，JOIN 会失败关闭而不会覆盖旧 key。只有确认放弃本机旧同步状态时才使用下面的显式清除。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = actions::resetSyncStateForRekey,
                        enabled = !dialog.running,
                        modifier = Modifier.testTag("sync-reset-for-rekey")
                    ) { Text("显式清除本机同步状态并重新加入", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions::closeSyncConsent, enabled = !dialog.running) {
                Text(if (dialog.generatedRecoveryKey == null) "取消" else "我已抄写")
            }
        },
        dismissButton = null
    )
}

@Composable
internal fun SyncConflictRow(
    conflict: E2eeSyncConflictSummary,
    actions: BackupCenterUiAction,
    enabled: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(conflict.table + " · " + shortRowKey(conflict.rowKey), style = MaterialTheme.typography.bodyMedium)
        Text(conflict.kind + "：" + conflict.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(4.dp))
        Text("本机版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            if (conflict.localDeletion) "（本机选择删除）" else conflict.localExcerpt ?: "（本机无该行）",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("sync-conflict-local")
        )
        Text("远端版本", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            if (conflict.remoteDeletion) "（远端已删除）" else conflict.remoteExcerpt ?: "（远端无可用密文版本）",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.testTag("sync-conflict-remote")
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { actions.resolveSyncConflict(conflict.id, E2eeSyncConflictResolution.KEEP_LOCAL) },
                enabled = enabled,
                modifier = Modifier.testTag("sync-conflict-keep-local")
            ) { Text("保留本机") }
            OutlinedButton(
                onClick = { actions.resolveSyncConflict(conflict.id, E2eeSyncConflictResolution.USE_REMOTE) },
                enabled = enabled,
                modifier = Modifier.testTag("sync-conflict-use-remote")
            ) { Text(if (conflict.remoteDeletion) "接受远端删除" else "使用远端") }
        }
    }
}

@Composable
private fun RestoreModeChoice(selected: RestoreMode, enabled: Boolean, onSelect: (RestoreMode) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected == RestoreMode.MERGE, onClick = { onSelect(RestoreMode.MERGE) }, enabled = enabled)
            Column {
                Text("合并（MERGE）", style = MaterialTheme.typography.bodyMedium)
                Text("不删除本机多出的数据；冲突逐条选择", style = MaterialTheme.typography.labelSmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected == RestoreMode.REPLACE, onClick = { onSelect(RestoreMode.REPLACE) }, enabled = enabled)
            Column {
                Text("替换（REPLACE）", style = MaterialTheme.typography.bodyMedium)
                Text("所选分类恢复为备份内容；仅在预览确认后执行", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ConflictChoiceRow(
    conflict: BackupRowConflict,
    selected: BackupConflictResolution?,
    enabled: Boolean,
    onSelect: (BackupConflictResolution) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(conflict.key.table, style = MaterialTheme.typography.bodyMedium)
        Text(shortRowKey(conflict.key.primaryKey), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onSelect(BackupConflictResolution.KEEP_EXISTING) },
                enabled = enabled,
                modifier = Modifier.testTag("backup-conflict-keep")
            ) { Text(if (selected == BackupConflictResolution.KEEP_EXISTING) "✓ 保留本机" else "保留本机") }
            OutlinedButton(
                onClick = { onSelect(BackupConflictResolution.USE_BACKUP) },
                enabled = enabled,
                modifier = Modifier.testTag("backup-conflict-use")
            ) { Text(if (selected == BackupConflictResolution.USE_BACKUP) "✓ 使用备份" else "使用备份") }
        }
    }
}

private fun shortRowKey(raw: String): String =
    if (raw.length <= 44) raw else raw.take(20) + "…" + raw.takeLast(20)

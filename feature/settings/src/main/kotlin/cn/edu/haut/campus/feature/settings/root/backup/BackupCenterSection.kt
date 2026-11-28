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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.vault.BackupConflictResolution
import cn.edu.haut.campus.core.vault.RestoreMode
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix
import cn.edu.haut.campus.core.designsystem.XPeachCard

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
    val valid = passphrase.length >= 8 && passphrase == confirm && !state.exportWizard.running

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
                    label = { Text("备份口令（至少 8 位）") },
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
                if (passphrase.isNotEmpty() && passphrase.length < 8) {
                    Text("口令至少 8 个字符", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
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
                    enabled = selectedUri != null && passphrase.length >= 8 && !wizard.running,
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
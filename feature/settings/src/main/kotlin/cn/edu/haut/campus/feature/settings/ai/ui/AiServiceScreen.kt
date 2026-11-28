package cn.edu.haut.campus.feature.settings.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.ai.AiKeyStatus
import cn.edu.haut.campus.core.ai.AiMessage
import cn.edu.haut.campus.core.ai.AiMessageRole
import cn.edu.haut.campus.core.ai.AiSendPreview
import cn.edu.haut.campus.core.ai.RedactionKind
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.feature.settings.ai.state.AiConnectivityStage
import cn.edu.haut.campus.feature.settings.ai.state.AiServiceUiAction
import cn.edu.haut.campus.feature.settings.ai.state.AiServiceUiState
import cn.edu.haut.campus.feature.settings.state.SettingsUiMatrix

/** W2-06 BYOK 骨架配置页。视觉打磨留阶段 N；隐私与预览状态在这里固定。 */
@Composable
fun AiServiceScreenContent(
    state: AiServiceUiState,
    onBack: () -> Unit,
    actions: AiServiceUiAction,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "AI 服务",
            subtitle = "用户自带 Key · 默认关闭 · 仅 HTTPS",
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
            AiMatrixBanner(state.matrix)
            state.statusMessage?.let { message ->
                XPeachCard {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("ai-status-message")
                    )
                }
            }

            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("隐私边界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Key 只在本机 Android Keystore AES-GCM 加密保存，按当前 Profile 隔离；不进入备份、日志、诊断或云端。每次发送前必须预览脱敏后的完整内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("BYOK AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            keyStatusText(state.keyStatus) + if (state.enabled) " · 已开启" else " · 默认关闭",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("ai-key-status")
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = actions::setEnabled,
                        enabled = state.keyStatus == AiKeyStatus.CONFIGURED,
                        modifier = Modifier.testTag("ai-enabled-switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                if (state.endpointHost == null) {
                    Text(
                        "尚未配置 AI Provider。你可以使用任意 OpenAI 兼容 HTTPS endpoint；云端不会代理或保存请求。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("endpoint：" + state.endpointHost, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "baseUrl：" + state.endpointBaseUrl.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "model：" + state.model.orEmpty() + " · vision：" + if (state.supportsVision) "支持" else "未声明",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = actions::openEditor,
                        modifier = Modifier.weight(1f).testTag("ai-open-editor")
                    ) {
                        Text(if (state.endpointHost == null) "配置 Provider" else "更新配置")
                    }
                    OutlinedButton(
                        onClick = actions::openClearConfirmation,
                        enabled = state.keyStatus != AiKeyStatus.NONE,
                        modifier = Modifier.weight(1f).testTag("ai-open-clear")
                    ) { Text("清除 Key") }
                }
            }

            XPeachCard(modifier = Modifier.fillMaxWidth()) {
                Text("连通性测试", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "先本地生成固定测试请求的预览；只有你确认发送后，才会生成单次 consent 并访问 Provider。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = actions::startConnectivityTest,
                    enabled = state.enabled && state.keyStatus == AiKeyStatus.CONFIGURED &&
                        state.connectivity.stage != AiConnectivityStage.PREPARING &&
                        state.connectivity.stage != AiConnectivityStage.SENDING,
                    modifier = Modifier.testTag("ai-connectivity-start")
                ) {
                    Text(
                        when (state.connectivity.stage) {
                            AiConnectivityStage.PREPARING -> "生成预览中…"
                            AiConnectivityStage.SENDING -> "发送中…"
                            else -> "生成发送预览"
                        }
                    )
                }
                state.connectivity.resultSummary?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai-connectivity-result"))
                }
                state.connectivity.errorMessage?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ai-connectivity-error"))
                }
            }
        }
    }

    if (state.editorVisible) {
        AiConfigurationDialog(state = state, actions = actions)
    }
    if (state.confirmClearVisible) {
        AiClearConfirmationDialog(actions = actions)
    }
    val preview = state.connectivity.preview
    if (preview != null && state.connectivity.stage in setOf(AiConnectivityStage.PREVIEW_READY, AiConnectivityStage.SENDING)) {
        AiSendPreviewDialog(
            preview = preview,
            sending = state.connectivity.stage == AiConnectivityStage.SENDING,
            onCancel = actions::cancelConnectivityTest,
            onSend = actions::confirmConnectivitySend
        )
    }
}

@Composable
private fun AiMatrixBanner(matrix: SettingsUiMatrix) {
    val text = when (matrix.status) {
        SettingsUiMatrix.Status.LOADING -> "正在读取或保存 AI 配置…"
        SettingsUiMatrix.Status.EMPTY -> "AI 服务未配置，默认关闭"
        SettingsUiMatrix.Status.CONTENT -> null
        SettingsUiMatrix.Status.PARTIAL -> "AI 配置部分可用"
        SettingsUiMatrix.Status.STALE -> "AI 配置状态可能已过期"
        SettingsUiMatrix.Status.ERROR -> matrix.message ?: "AI 配置操作失败"
        SettingsUiMatrix.Status.NEEDS_PERMISSION -> "需要本机安全存储能力"
        SettingsUiMatrix.Status.NEEDS_LOGIN -> "需要先确认当前 Profile"
        SettingsUiMatrix.Status.OFFLINE -> "离线时可配置，连通性测试不可用"
        SettingsUiMatrix.Status.UNSUPPORTED -> "当前环境不支持 AI 服务"
    } ?: return
    XPeachCard {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (matrix.status == SettingsUiMatrix.Status.ERROR || matrix.status == SettingsUiMatrix.Status.UNSUPPORTED) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("ai-matrix-banner")
        )
    }
}

@Composable
private fun AiConfigurationDialog(state: AiServiceUiState, actions: AiServiceUiAction) {
    var baseUrl by remember(state.editorVisible) { mutableStateOf(state.endpointBaseUrl.orEmpty()) }
    var model by remember(state.editorVisible) { mutableStateOf(state.model.orEmpty()) }
    var supportsVision by remember(state.editorVisible) { mutableStateOf(state.supportsVision) }
    var keyText by remember(state.editorVisible) { mutableStateOf("") }
    val canSave = baseUrl.isNotBlank() && model.isNotBlank() && keyText.isNotBlank()

    AlertDialog(
        onDismissRequest = actions::closeEditor,
        title = { Text("配置 OpenAI 兼容 Provider") },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "baseUrl 必须是 HTTPS，例如 https://provider.example/v1。更新配置时需重新输入 Key；Key 只写入本机安全存储。",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("baseUrl（仅 HTTPS）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("ai-input-baseurl")
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("ai-input-model")
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text("API Key（不进备份/日志/诊断）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("ai-input-key")
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { supportsVision = !supportsVision }.testTag("ai-input-vision-row")
                ) {
                    Checkbox(
                        checked = supportsVision,
                        onCheckedChange = { supportsVision = it },
                        modifier = Modifier.testTag("ai-input-vision")
                    )
                    Text("该模型支持图片理解（vision）", style = MaterialTheme.typography.bodyMedium)
                }
                if (baseUrl.isNotBlank() && !baseUrl.trim().startsWith("https://")) {
                    Text("只允许 HTTPS baseUrl", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val key = keyText.toCharArray()
                    keyText = ""
                    actions.saveConfiguration(baseUrl, model, supportsVision, key)
                },
                enabled = canSave,
                modifier = Modifier.testTag("ai-save-config")
            ) { Text("安全保存") }
        },
        dismissButton = {
            TextButton(onClick = actions::closeEditor, modifier = Modifier.testTag("ai-cancel-editor")) { Text("取消") }
        }
    )
}

@Composable
private fun AiClearConfirmationDialog(actions: AiServiceUiAction) {
    AlertDialog(
        onDismissRequest = actions::closeClearConfirmation,
        title = { Text("清除 AI Key 与配置？") },
        text = {
            Text("将删除当前 Profile 的 endpoint、Key、enabled 标记和 Keystore 密钥材料；此操作不会影响其它 Profile。备份中本来就不包含 AI Key。")
        },
        confirmButton = {
            Button(onClick = actions::clearConfiguration, modifier = Modifier.testTag("ai-confirm-clear")) { Text("确认清除") }
        },
        dismissButton = {
            TextButton(onClick = actions::closeClearConfirmation, modifier = Modifier.testTag("ai-cancel-clear")) { Text("取消") }
        }
    )
}

@Composable
private fun AiSendPreviewDialog(
    preview: AiSendPreview,
    sending: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!sending) onCancel() },
        title = { Text("发送前预览") },
        text = {
            Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text("目标：" + preview.endpointHost + " · 模型：" + preview.model, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "文本 " + preview.textChars + " 字 · 附件 " + preview.attachmentCount +
                        " · maxTokens " + preview.maxTokens + " · 约 " + preview.estimatedRequestBytes + " 字节",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (preview.redactions.isEmpty()) {
                    Text("本地脱敏：未命中规则", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("本地脱敏命中（只显示类别和次数）：", style = MaterialTheme.typography.bodySmall)
                    preview.redactions.forEach { redaction ->
                        Text(
                            "· " + redactionLabel(redaction.kind) + " × " + redaction.count,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                XPeachInsetDivider(verticalPadding = 8.dp)
                Text("完整出站内容", style = MaterialTheme.typography.titleSmall)
                preview.outboundMessages.forEachIndexed { index, message ->
                    Spacer(modifier = Modifier.height(6.dp))
                    OutboundMessageCard(index = index, message = message)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSend, enabled = !sending, modifier = Modifier.testTag("ai-preview-send")) {
                Text(if (sending) "发送中…" else "确认发送")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !sending, modifier = Modifier.testTag("ai-preview-cancel")) { Text("取消") }
        }
    )
}

@Composable
private fun OutboundMessageCard(index: Int, message: AiMessage) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag("ai-preview-message-" + index)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                when (message.role) {
                    AiMessageRole.SYSTEM -> "system"
                    AiMessageRole.USER -> "user"
                    AiMessageRole.ASSISTANT -> "assistant"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(message.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun keyStatusText(status: AiKeyStatus): String = when (status) {
    AiKeyStatus.NONE -> "未配置"
    AiKeyStatus.CONFIGURED -> "已配置"
    AiKeyStatus.INVALID -> "配置损坏，请重新保存"
}

private fun redactionLabel(kind: RedactionKind): String = when (kind) {
    RedactionKind.STUDENT_ID -> "学号"
    RedactionKind.PHONE_NUMBER -> "手机号"
    RedactionKind.NATIONAL_ID -> "身份证号"
    RedactionKind.DORM_ROOM -> "宿舍号"
}

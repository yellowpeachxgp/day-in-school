package cn.edu.haut.campus.feature.settings.root.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.model.ImportAudit
import androidx.compose.material3.MaterialTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SYNC_AUDIT_TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"))

/** 根壳与首页分区共用的教务/课表同步记录弹窗；内容与拆分前一致。 */
@Composable
internal fun SyncHistoryDialog(
    importAudits: List<ImportAudit>,
    onDismiss: () -> Unit
) {
    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("教务与课表同步记录") },
        text = {
            if (importAudits.isEmpty()) {
                Text(
                    text = "暂无同步记录。点击右上角同步图标可从正方教务系统导入课表与成绩。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val audits = importAudits.take(8)
                Column {
                    audits.forEachIndexed { index, audit ->
                        if (index > 0) {
                            XPeachInsetDivider(verticalPadding = 6.dp)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = SYNC_AUDIT_TIME_FMT.format(audit.importedAt) + " · " + audit.termId,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = audit.outcome,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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

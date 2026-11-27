package cn.edu.haut.campus.feature.grades.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.feature.grades.state.GradesScreenStatus
import cn.edu.haut.campus.feature.grades.state.GradesUiAction

private data class GradesStatusCopy(
    val title: String,
    val subtitle: String,
    val actionLabel: String? = null
)

private fun gradesStatusCopy(status: GradesScreenStatus): GradesStatusCopy = when (status) {
    GradesScreenStatus.LOADING -> GradesStatusCopy(
        title = "正在加载学业数据",
        subtitle = "正在读取本地成绩、考试与学习计划。"
    )
    GradesScreenStatus.ERROR_RETRYABLE -> GradesStatusCopy(
        title = "学业数据暂时不可用",
        subtitle = "本地读取或同步失败；可前往同步入口重试。",
        actionLabel = "去同步"
    )
    GradesScreenStatus.ERROR_TERMINAL -> GradesStatusCopy(
        title = "学业数据不可用",
        subtitle = "当前数据无法恢复展示；请稍后重新进入或重新同步。"
    )
    GradesScreenStatus.NEEDS_PERMISSION -> GradesStatusCopy(
        title = "需要授权后查看",
        subtitle = "学业数据仅在用户本人授权的本机账号范围内展示。"
    )
    GradesScreenStatus.NEEDS_LOGIN -> GradesStatusCopy(
        title = "需要登录后查看",
        subtitle = "请先通过同步入口登录本人教务账号。",
        actionLabel = "去登录"
    )
    GradesScreenStatus.UNSUPPORTED -> GradesStatusCopy(
        title = "本校暂不支持学业数据",
        subtitle = "当前学校数据包未声明可用的成绩与考试能力。"
    )
    else -> GradesStatusCopy(
        title = "学业状态未知",
        subtitle = "该状态只用于预览矩阵占位，不影响现有内容页。"
    )
}

/** 非内容态的最小骨架页；运行期默认 CONTENT，不改变既有用户路径。 */
@Composable
internal fun GradesStatusScaffold(
    status: GradesScreenStatus,
    onAction: (GradesUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val copy = gradesStatusCopy(status)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(title = "学业", subtitle = copy.title)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (status == GradesScreenStatus.LOADING) {
                    CircularProgressIndicator()
                }
                XPeachEmptyState(
                    title = copy.title,
                    subtitle = copy.subtitle,
                    actionLabel = copy.actionLabel,
                    onActionClick = copy.actionLabel?.let {
                        { onAction(GradesUiAction.OpenImporter) }
                    }
                )
            }
        }
    }
}

/** PARTIAL / STALE / OFFLINE 的轻量提示条；CONTENT 与 EMPTY 不输出任何节点。 */
@Composable
internal fun GradesStatusBanner(
    status: GradesScreenStatus,
    modifier: Modifier = Modifier
) {
    val copy = when (status) {
        GradesScreenStatus.PARTIAL -> GradesStatusCopy(
            title = "部分学业数据可用",
            subtitle = "考试、档案或计划数据尚未完整同步，当前内容可能不完整。"
        )
        GradesScreenStatus.STALE -> GradesStatusCopy(
            title = "学业数据可能过期",
            subtitle = "当前展示本地缓存；重新同步后会自动更新。"
        )
        GradesScreenStatus.OFFLINE -> GradesStatusCopy(
            title = "当前处于离线状态",
            subtitle = "正在展示本地缓存；网络恢复后可重新同步。"
        )
        else -> return
    }
    XPeachWell(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = copy.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = copy.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

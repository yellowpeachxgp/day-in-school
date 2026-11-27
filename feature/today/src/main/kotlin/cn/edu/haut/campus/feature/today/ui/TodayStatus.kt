package cn.edu.haut.campus.feature.today.ui

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
import cn.edu.haut.campus.feature.today.state.TodayScreenStatus
import cn.edu.haut.campus.feature.today.state.TodayUiAction

private data class TodayStatusCopy(
    val title: String,
    val subtitle: String,
    val actionLabel: String? = null
)

private fun todayStatusCopy(status: TodayScreenStatus): TodayStatusCopy = when (status) {
    TodayScreenStatus.LOADING -> TodayStatusCopy(
        title = "正在加载今日数据",
        subtitle = "正在读取本地课表、提醒与校园服务缓存。"
    )
    TodayScreenStatus.ERROR_RETRYABLE -> TodayStatusCopy(
        title = "今日数据暂时不可用",
        subtitle = "本地读取或同步失败；可前往同步入口重试。",
        actionLabel = "去同步"
    )
    TodayScreenStatus.ERROR_TERMINAL -> TodayStatusCopy(
        title = "今日数据不可用",
        subtitle = "当前数据无法恢复展示；请稍后重新进入或重新同步。"
    )
    TodayScreenStatus.NEEDS_PERMISSION -> TodayStatusCopy(
        title = "需要授权后查看",
        subtitle = "今日课表与校园服务仅在用户本人授权的本机账号范围内展示。"
    )
    TodayScreenStatus.NEEDS_LOGIN -> TodayStatusCopy(
        title = "需要登录后查看",
        subtitle = "请先通过同步入口登录本人教务账号。",
        actionLabel = "去登录"
    )
    TodayScreenStatus.UNSUPPORTED -> TodayStatusCopy(
        title = "本校暂不支持今日视图",
        subtitle = "当前学校数据包未声明可用的课表与校园服务能力。"
    )
    else -> TodayStatusCopy(
        title = "今日状态未知",
        subtitle = "该状态只用于预览矩阵占位，不影响现有内容页。"
    )
}

/** 非内容态的最小骨架页；运行期默认 CONTENT，不改变既有用户路径。 */
@Composable
internal fun TodayStatusScaffold(
    status: TodayScreenStatus,
    onAction: (TodayUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val copy = todayStatusCopy(status)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(title = "今天", subtitle = copy.title)
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
                if (status == TodayScreenStatus.LOADING) {
                    // 截图门禁必须让 Robolectric looper 静止：Loading 骨架使用确定性进度，
                    // 不把 indeterminate infinite animation 带进 Roborazzi 基线。
                    CircularProgressIndicator(progress = { 0.62f })
                }
                XPeachEmptyState(
                    title = copy.title,
                    subtitle = copy.subtitle,
                    actionLabel = copy.actionLabel,
                    onActionClick = copy.actionLabel?.let {
                        { onAction(TodayUiAction.OpenImporter) }
                    }
                )
            }
        }
    }
}

/** PARTIAL / STALE / OFFLINE 的轻量提示条；CONTENT 与 EMPTY 不输出任何节点。 */
@Composable
internal fun TodayStatusBanner(
    status: TodayScreenStatus,
    modifier: Modifier = Modifier
) {
    val copy = when (status) {
        TodayScreenStatus.PARTIAL -> TodayStatusCopy(
            title = "部分今日数据可用",
            subtitle = "课表、提醒或校园服务数据尚未完整同步，当前内容可能不完整。"
        )
        TodayScreenStatus.STALE -> TodayStatusCopy(
            title = "今日数据可能过期",
            subtitle = "当前展示本地缓存；重新同步后会自动更新。"
        )
        TodayScreenStatus.OFFLINE -> TodayStatusCopy(
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

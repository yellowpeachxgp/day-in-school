package cn.edu.haut.campus.feature.knowledge

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachSearchField
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachTopBar

/** W3-04 课程空间骨架页：只做状态完整渲染与动作回调，文件选择/预览导航由后续集成接入。 */
@Composable
fun CourseSpaceScreen(
    state: CourseSpaceUiState,
    onAction: (CourseSpaceAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        XPeachTopBar(
            title = "课程空间",
            subtitle = state.courseName + " · " + state.termLabel,
            badgeText = "本地 Vault",
            actions = {
                TextButton(
                    enabled = state.exportEnabled && state.status != CourseSpaceStatus.LOADING,
                    onClick = { onAction(CourseSpaceAction.ExportClicked) }
                ) {
                    Text("导出")
                }
            }
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            state.notice?.let { NoticeCard(it) }
            when (state.status) {
                CourseSpaceStatus.LOADING -> LoadingBlock()
                CourseSpaceStatus.EMPTY -> EmptyBlock()
                CourseSpaceStatus.ERROR_RETRYABLE -> ErrorBlock(
                    message = state.errorMessage ?: "课程资料加载失败",
                    retryable = true,
                    onAction = onAction
                )
                CourseSpaceStatus.ERROR_TERMINAL -> ErrorBlock(
                    message = state.errorMessage ?: "课程资料不可用",
                    retryable = false,
                    onAction = onAction
                )
                CourseSpaceStatus.NEEDS_PERMISSION -> PermissionBlock(onAction)
                CourseSpaceStatus.NEEDS_LOGIN -> LoginBlock(onAction)
                CourseSpaceStatus.UNSUPPORTED -> XPeachEmptyState(
                    title = "当前学校暂未接入课程空间",
                    subtitle = "仍可使用本地时间流；不会上传校园账号或资料。"
                )
                CourseSpaceStatus.CONTENT,
                CourseSpaceStatus.PARTIAL,
                CourseSpaceStatus.STALE,
                CourseSpaceStatus.OFFLINE -> CourseContent(state, onAction)
            }
            StorageTruthBlock()
        }
    }
}

@Composable
private fun CourseContent(state: CourseSpaceUiState, onAction: (CourseSpaceAction) -> Unit) {
    if (state.status == CourseSpaceStatus.PARTIAL) {
        NoticeCard("部分资料尚未完成文本索引，搜索结果可能不完整。")
    }
    if (state.status == CourseSpaceStatus.STALE) {
        NoticeCard("课程资料索引不是最新；重新整理前请以原文为准。")
    }
    if (state.status == CourseSpaceStatus.OFFLINE) {
        NoticeCard("离线模式：仅显示本机 Vault 中已归档资料。")
    }
    XPeachSearchField(
        value = state.query,
        onValueChange = { onAction(CourseSpaceAction.QueryChanged(it)) },
        placeholder = "搜索课件、PDF、照片或笔记正文",
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        XPeachFilterPill(
            label = "全部周",
            selected = state.selectedWeek == null,
            onClick = { onAction(CourseSpaceAction.WeekSelected(null)) },
            compact = true
        )
        state.availableWeeks.forEach { week ->
            XPeachFilterPill(
                label = "第 " + week + " 周",
                selected = state.selectedWeek == week,
                onClick = { onAction(CourseSpaceAction.WeekSelected(week)) },
                compact = true
            )
        }
    }
    if (state.deadlines.isNotEmpty()) {
        DeadlineCard(state.deadlines)
    }
    if (state.reviewTasks.isNotEmpty()) {
        ReviewTaskCard(state.reviewTasks)
    }
    if (state.query.isNotBlank()) {
        SearchResultsBlock(state, onAction)
    } else {
        MaterialListBlock(state, onAction)
    }
}

@Composable
private fun LoadingBlock() {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
            Text("正在整理本机课程资料…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyBlock() {
    XPeachEmptyState(
        title = "还没有课程资料",
        subtitle = "导入课件、PDF、照片或笔记后，可按课程与教学周归档并全文搜索。"
    )
}

@Composable
private fun ErrorBlock(message: String, retryable: Boolean, onAction: (CourseSpaceAction) -> Unit) {
    XPeachEmptyState(
        title = if (retryable) "资料加载失败" else "资料暂不可用",
        subtitle = message,
        actionLabel = if (retryable) "重试" else null,
        onAction = if (retryable) ({ onAction(CourseSpaceAction.RetryClicked) }) else null
    )
}

@Composable
private fun PermissionBlock(onAction: (CourseSpaceAction) -> Unit) {
    XPeachEmptyState(
        title = "需要文件访问授权",
        subtitle = "只在用户选择资料后读取本机文件；不会联网上传。",
        actionLabel = "继续授权",
        onAction = { onAction(CourseSpaceAction.PermissionRequested) }
    )
}

@Composable
private fun LoginBlock(onAction: (CourseSpaceAction) -> Unit) {
    XPeachEmptyState(
        title = "需要先选择学校身份",
        subtitle = "课程空间按学校账号与 Profile 隔离。",
        actionLabel = "去选择身份",
        onAction = { onAction(CourseSpaceAction.LoginRequested) }
    )
}

@Composable
private fun NoticeCard(text: String) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeadlineCard(deadlines: List<CourseDeadlineUi>) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("作业截止", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            deadlines.forEach { deadline ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(deadline.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(deadline.dueLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (deadline.inTimeline) XPeachStatusBadge("已入时间流")
                }
            }
        }
    }
}

@Composable
private fun ReviewTaskCard(tasks: List<CourseReviewTaskUi>) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("手动复习计划", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            tasks.forEach { task ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(task.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(task.dueLabel + " · " + task.estimatedLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    XPeachStatusBadge(task.stateLabel)
                }
            }
        }
    }
}

@Composable
private fun MaterialListBlock(state: CourseSpaceUiState, onAction: (CourseSpaceAction) -> Unit) {
    if (state.materials.isEmpty()) {
        XPeachEmptyState(title = "当前教学周没有资料", subtitle = "切换教学周或导入新资料。")
        return
    }
    state.materials.forEach { material ->
        XPeachCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(CourseSpaceAction.MaterialOpened(material.id)) }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        material.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    XPeachStatusBadge(material.kindLabel)
                }
                Text(
                    "第 " + material.teachingWeek + " 周 · " + material.fileLabel + " · " + material.updatedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                XPeachStatusBadge(
                    text = material.extractionLabel,
                    containerColor = if (material.extractionNeedsConfirmation) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer
                )
            }
        }
    }
}

@Composable
private fun SearchResultsBlock(state: CourseSpaceUiState, onAction: (CourseSpaceAction) -> Unit) {
    if (state.searchHits.isEmpty()) {
        XPeachEmptyState(title = "没有命中", subtitle = "可尝试课程名、章节名或 PDF/照片中的连续中文词。")
        return
    }
    state.searchHits.forEach { hit ->
        XPeachCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = { onAction(CourseSpaceAction.MaterialOpened(hit.materialId)) }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(hit.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    XPeachStatusBadge(hit.locationLabel)
                }
                Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (hit.needsConfirmation) {
                    Text("抽取结果待确认：打开原文核对后再作为事实使用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun StorageTruthBlock() {
    Text(
        "存储说明：本骨架使用既有 Vault blob/FTS/时间流表；Room v16 结构化 course_materials/review_tasks 待 v13–v15 按序合入后迁移。个人资料擦除会覆盖当前 Vault 数据。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

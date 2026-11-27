package cn.edu.haut.campus.feature.settings.moments.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.ThumbUpOffAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.feature.settings.moments.state.CampusMomentsUiAction
import cn.edu.haut.campus.feature.settings.moments.state.CampusMomentsUiState
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachEmptyState
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackBanner
import cn.edu.haut.campus.core.designsystem.XPeachFeedbackTone
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachSearchField
import cn.edu.haut.campus.core.designsystem.XPeachSegmentedTabs
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.model.AntiLostTagItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.MomentsItemCategory
import cn.edu.haut.campus.core.model.MomentsPostItem
import cn.edu.haut.campus.core.model.MomentsPostType
import cn.edu.haut.campus.core.model.MomentsTextbookListing
import cn.edu.haut.campus.core.model.PresetCampusMomentsCatalog
import cn.edu.haut.campus.feature.settings.moments.state.ContactComposerAudience
import cn.edu.haut.campus.feature.settings.moments.state.MomentsContactComposerStateMachine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
@Composable
internal fun MomentsPostCard(
    post: MomentsPostItem,
    matchedCluesCount: Int,
    isFavorited: Boolean,
    isMine: Boolean,
    onOpenDetail: () -> Unit,
    onLike: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopyContact: () -> Unit
) {
    XPeachCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                XPeachStatusBadge(
                    text = if (post.isResolved) "已找回" else post.type.badgeLabel,
                    containerColor = when {
                        post.isResolved -> MaterialTheme.colorScheme.surfaceVariant
                        post.type == MomentsPostType.FOUND -> XPeachTheme.successContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = when {
                        post.isResolved -> MaterialTheme.colorScheme.onSurfaceVariant
                        post.type == MomentsPostType.FOUND -> XPeachTheme.onSuccessContainer
                        else -> XPeachTheme.readablePrimary
                    }
                )
                XPeachStatusBadge(
                    text = post.category,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (matchedCluesCount > 0 && !post.isResolved) {
                    XPeachStatusBadge(
                        text = "匹配 $matchedCluesCount",
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (isMine && post.pendingContactRequestsCount > 0) {
                    XPeachStatusBadge(
                        text = "待处理私信 ${post.pendingContactRequestsCount}",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary
                    )
                } else if (!isMine && post.hasMyContactRequest) {
                    XPeachStatusBadge(
                        text = if (post.myContactRequestStatus == "APPROVED") "私信已获批" else "私信沟通中",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary
                    )
                }
            }
            if (post.createdAt.isNotBlank()) {
                Text(
                    text = post.createdAt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = post.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textDecoration = if (post.isResolved) TextDecoration.LineThrough else null
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "${post.campus} · ${post.location}",
            style = MaterialTheme.typography.labelMedium,
            color = XPeachTheme.readablePrimary,
            fontWeight = FontWeight.Medium
        )

        if (post.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = post.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        XPeachInsetDivider(verticalPadding = 8.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = buildString {
                    if (post.authorName.isNotBlank()) append(post.authorName)
                    if (isMine) append(" (我)")
                    if (post.comments.isNotEmpty()) append(" · ${post.comments.size} 留言")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onLike,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ThumbUpOffAlt,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("${post.likesCount}", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = onToggleFavorite,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorited) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = null,
                        tint = if (isFavorited) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                FilledTonalButton(
                    onClick = onOpenDetail,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    val actionLabel = when {
                        isMine && post.pendingContactRequestsCount > 0 -> "处理私信(${post.pendingContactRequestsCount})"
                        isMine -> "管理/私信"
                        post.hasMyContactRequest -> "继续私信"
                        else -> "向发起人发消息"
                    }
                    Text(actionLabel, style = MaterialTheme.typography.labelSmall)
                }
                if (post.canViewerReadRealContact) {
                    OutlinedButton(
                        onClick = onCopyContact,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("复制联系", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MomentsTextbookCard(
    book: MomentsTextbookListing,
    isFavorited: Boolean,
    isMine: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleSold: () -> Unit,
    onDelete: () -> Unit,
    onCopyContact: () -> Unit
) {
    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XPeachStatusBadge(
                        text = if (book.isSold) "已出" else book.conditionLabel,
                        containerColor = if (book.isSold) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        contentColor = if (book.isSold) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            XPeachTheme.readablePrimary
                        }
                    )
                    if (book.courseName.isNotBlank()) {
                        XPeachStatusBadge(
                            text = book.courseName,
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (book.isSold) TextDecoration.LineThrough else null
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "¥${book.priceYuan}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (book.isSold) MaterialTheme.colorScheme.outline else XPeachTheme.readablePrimary
                )
                if (book.originalPriceYuan > book.priceYuan) {
                    Text(
                        text = "¥${book.originalPriceYuan}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
            }
        }

        if (book.authorPublisher.isNotBlank() || book.isbn.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    book.authorPublisher.takeIf { it.isNotBlank() },
                    book.isbn.takeIf { it.isNotBlank() }?.let { "ISBN $it" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = "${book.campus} · ${book.deliveryMode}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        XPeachInsetDivider(verticalPadding = 8.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onToggleFavorite,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorited) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                        contentDescription = null,
                        tint = if (isFavorited) XPeachTheme.readablePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(if (isFavorited) "已收藏" else "收藏", style = MaterialTheme.typography.labelSmall)
                }
                if (isMine) {
                    TextButton(
                        onClick = onToggleSold,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text(if (book.isSold) "上架" else "已出", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = onDelete,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (book.contact.isNotBlank() && !book.isSold) {
                FilledTonalButton(
                    onClick = onCopyContact,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("联系", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun AntiLostTagCard(
    tag: AntiLostTagItem,
    claimUrl: String,
    onCopyLink: () -> Unit,
    onOpenWebPage: () -> Unit,
    onToggleLostStatus: () -> Unit,
    onReportFound: () -> Unit
) {
    val matrix = remember(tag.tagCode, tag.itemName) { tag.generateVisualMatrix(21) }
    val primaryColor = XPeachTheme.readablePrimary

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val gridCount = matrix.size
                    val cellW = size.width / gridCount
                    val cellH = size.height / gridCount
                    for (r in 0 until gridCount) {
                        for (c in 0 until gridCount) {
                            if (matrix[r][c]) {
                                drawRect(
                                    color = Color(0xFF0F172A),
                                    topLeft = Offset(c * cellW, r * cellH),
                                    size = Size(cellW + 0.4f, cellH + 0.4f)
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    XPeachStatusBadge(
                        text = when (tag.status) {
                            "FOUND_REPORTED" -> "已上报 (${tag.reports.size})"
                            "LOST" -> "挂失中"
                            else -> "正常"
                        },
                        containerColor = when (tag.status) {
                            "FOUND_REPORTED" -> XPeachTheme.successContainer
                            "LOST" -> XPeachTheme.dangerContainer
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        },
                        contentColor = when (tag.status) {
                            "FOUND_REPORTED" -> XPeachTheme.onSuccessContainer
                            "LOST" -> XPeachTheme.onDangerContainer
                            else -> MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                    Text(
                        text = tag.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = tag.itemName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = tag.tagCode,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = primaryColor
                )
            }
        }

        if (tag.returnNote.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = tag.returnNote,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (tag.reports.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            for (rep in tag.reports.take(2)) {
                XPeachWell(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "位置：${rep.foundLocation}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )
                    if (rep.message.isNotBlank()) {
                        Text(
                            text = rep.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        XPeachInsetDivider(verticalPadding = 8.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onCopyLink,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("复制链接", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onOpenWebPage,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("认领页", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(
                onClick = onToggleLostStatus,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text(
                    if (tag.status == "LOST") "取消挂失" else "挂失",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            TextButton(
                onClick = onReportFound,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Text("上报拾得", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

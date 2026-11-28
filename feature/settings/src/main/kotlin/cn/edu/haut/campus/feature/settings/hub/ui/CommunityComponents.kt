package cn.edu.haut.campus.feature.settings.hub.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.edu.haut.campus.core.database.AppPreferencesState
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
import cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog
import cn.edu.haut.campus.core.domain.CampusStudyRoomCatalog
import cn.edu.haut.campus.core.domain.ChineseLabel
import cn.edu.haut.campus.core.domain.HospitalMedicineCategory
import cn.edu.haut.campus.core.domain.TeachingWeekCalculator
import cn.edu.haut.campus.core.model.CampusClubCategory
import cn.edu.haut.campus.core.model.CampusClubItem
import cn.edu.haut.campus.core.model.CampusCompetitionCategory
import cn.edu.haut.campus.core.model.CampusCompetitionItem
import cn.edu.haut.campus.core.model.CampusMajorTrack
import cn.edu.haut.campus.core.model.CompetitionCareerGoal
import cn.edu.haut.campus.core.model.CompetitionCompassEngine
import cn.edu.haut.campus.core.model.CompetitionGradeStage
import cn.edu.haut.campus.core.model.CompetitionSeasonWindow
import cn.edu.haut.campus.core.model.CompetitionSkillPreference
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.PresetQqChannelItem
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MajorInterestCompassCard(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    gradeStage: CompetitionGradeStage,
    onSelectGradeStage: (CompetitionGradeStage) -> Unit,
    careerGoal: CompetitionCareerGoal,
    onSelectCareerGoal: (CompetitionCareerGoal) -> Unit,
    skillPref: CompetitionSkillPreference,
    onSelectSkillPref: (CompetitionSkillPreference) -> Unit,
    compassResult: cn.edu.haut.campus.core.model.CompetitionCompassResult,
    onInspectCompetition: (CampusCompetitionItem) -> Unit,
    onJoinClub: (CampusClubItem) -> Unit,
    onApplyToFilter: () -> Unit
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.primaryAccentCardColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "参赛方向筛选",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "按年级、目标和兴趣推荐适合的竞赛",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            XPeachFilterPill(
                label = if (isExpanded) "收起" else "展开",
                selected = isExpanded,
                compact = true,
                onClick = onToggleExpand
            )
        }

        if (isExpanded) {
            Spacer(modifier = Modifier.height(12.dp))
            XPeachInsetDivider(verticalPadding = 2.dp)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "1. 年级阶段",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (st in CompetitionGradeStage.entries) {
                    XPeachFilterPill(
                        label = st.shortLabel,
                        selected = gradeStage == st,
                        compact = true,
                        onClick = { onSelectGradeStage(st) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "2. 参赛目标",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (goal in CompetitionCareerGoal.entries) {
                    XPeachFilterPill(
                        label = goal.shortLabel,
                        selected = careerGoal == goal,
                        compact = true,
                        onClick = { onSelectCareerGoal(goal) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "3. 兴趣方向",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (pref in CompetitionSkillPreference.entries) {
                    XPeachFilterPill(
                        label = pref.shortLabel,
                        selected = skillPref == pref,
                        compact = true,
                        onClick = { onSelectSkillPref(pref) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            XPeachWell(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "推荐竞赛",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary
                    )
                    Text(
                        text = compassResult.stageSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    for ((stepTitle, item) in compassResult.entryStepOrder) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
                                .clickable { onInspectCompetition(item) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = stepTitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${item.tierBadge} · ${item.registrationWindow}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (compassResult.recommendedClubs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "相关社团",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (club in compassResult.recommendedClubs) {
                                XPeachFilterPill(
                                    label = "加群 · ${club.name} (${club.qqGroupNumber})",
                                    selected = false,
                                    compact = true,
                                    onClick = { onJoinClub(club) }
                                )
                            }
                            XPeachFilterPill(
                                label = "应用到列表筛选",
                                selected = true,
                                compact = true,
                                onClick = onApplyToFilter
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommunityBulletinHeroCard(
    title: String,
    content: String,
    tag: String,
    version: Long,
    clubsCount: Int,
    competitionsCount: Int
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = XPeachTheme.secondaryAccentCardColor
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Campaign,
                    contentDescription = null,
                    tint = XPeachTheme.readablePrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (tag.isNotBlank()) {
                        XPeachStatusDotText(
                            text = tag,
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }
                    XPeachStatusBadge(
                        text = "${clubsCount} 个社团 · ${competitionsCount} 项竞赛",
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary
                    )
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (content.isNotBlank()) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

internal data class ClubCategoryVisualMeta(
    val icon: ImageVector,
    val accentColor: Color
)

@Composable
internal fun resolveClubCategoryVisual(category: CampusClubCategory): ClubCategoryVisualMeta {
    val isDark = XPeachTheme.isDark
    return when (category) {
        CampusClubCategory.TECH_INNOVATION -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Memory,
            accentColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
        )
        CampusClubCategory.ART_PERFORMANCE -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Palette,
            accentColor = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777)
        )
        CampusClubCategory.ACADEMIC_CULTURE -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.AutoAwesome,
            accentColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
        )
        CampusClubCategory.SPORTS_FITNESS -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.EmojiEvents,
            accentColor = if (isDark) Color(0xFF34D399) else Color(0xFF059669)
        )
        CampusClubCategory.VOLUNTEER_ORG -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.VolunteerActivism,
            accentColor = if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampusClubCard(
    club: CampusClubItem,
    effectiveGroupNo: String,
    isGroupCustomized: Boolean,
    onEditGroupNo: () -> Unit,
    onCopyGroupNo: () -> Unit,
    onJoinGroup: () -> Unit
) {
    val visual = resolveClubCategoryVisual(club.category)

    XPeachCard(modifier = Modifier.fillMaxWidth()) {
        // 1. 顶部身份区：左侧分类徽章图标 + 右侧独占一行的完整社团名称 + 下方置顶/类别/挂靠学院标签流
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(visual.accentColor.copy(alpha = if (XPeachTheme.isDark) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = club.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (club.pinned) {
                        XPeachStatusBadge(
                            text = "合作社团",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    }
                    XPeachStatusBadge(
                        text = club.category.label,
                        containerColor = visual.accentColor.copy(alpha = if (XPeachTheme.isDark) 0.22f else 0.12f),
                        contentColor = visual.accentColor
                    )
                    if (club.affiliatedUnit.isNotBlank()) {
                        XPeachStatusBadge(
                            text = club.affiliatedUnit,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 2. 社团简介（独占完整宽度，不与任何徽章或标题挤压）
        if (club.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = club.summary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3. 特色亮点标签流
        if (club.highlights.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (tag in club.highlights) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (XPeachTheme.isDark) 0.26f else 0.45f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. 场地与官方 QQ 群信息凹槽
        XPeachWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "活动场地：${club.activityVenue}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "QQ 群：$effectiveGroupNo",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = XPeachTheme.readablePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isGroupCustomized) {
                        XPeachStatusDotText(
                            text = "已修改",
                            dotColor = XPeachTheme.readablePrimary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. 独立底部操作栏（不挤压左侧群号与场地文字）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            XPeachFilterPill(
                label = "修改群号",
                selected = false,
                compact = true,
                onClick = onEditGroupNo
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                XPeachFilterPill(
                    label = "复制群号",
                    selected = false,
                    compact = true,
                    onClick = onCopyGroupNo
                )
                XPeachFilterPill(
                    label = "加入群聊",
                    selected = true,
                    compact = false,
                    onClick = onJoinGroup
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampusChannelCard(
    channel: PresetQqChannelItem,
    effectiveUrl: String,
    isCustomized: Boolean,
    onEditUrl: () -> Unit,
    onOpenChannel: () -> Unit
) {
    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenChannel
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Forum,
                    contentDescription = null,
                    tint = XPeachTheme.readablePrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (channel.pinned) {
                        XPeachStatusBadge(
                            text = "置顶频道",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    }
                    XPeachStatusBadge(
                        text = if (isCustomized) "已自定义" else channel.tagLabel,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        if (channel.subtitle.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = channel.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        XPeachWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = effectiveUrl,
                style = MaterialTheme.typography.labelSmall,
                color = XPeachTheme.readablePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                XPeachFilterPill(
                    label = "修改链接",
                    selected = false,
                    compact = true,
                    onClick = onEditUrl
                )
                XPeachFilterPill(
                    label = "进入频道",
                    selected = true,
                    compact = false,
                    onClick = onOpenChannel
                )
            }
        }
    }
}

@Composable
internal fun resolveCompetitionCategoryVisual(category: CampusCompetitionCategory): ClubCategoryVisualMeta {
    val isDark = XPeachTheme.isDark
    return when (category) {
        CampusCompetitionCategory.ALGO_PROGRAMMING -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Code,
            accentColor = if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB)
        )
        CampusCompetitionCategory.ELECTRONICS_ROBO -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Memory,
            accentColor = if (isDark) Color(0xFF38BDF8) else Color(0xFF0284C7)
        )
        CampusCompetitionCategory.MATH_MODELING -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Calculate,
            accentColor = if (isDark) Color(0xFFA78BFA) else Color(0xFF7C3AED)
        )
        CampusCompetitionCategory.INNOVATION_ENTREPRENEURSHIP -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.EmojiEvents,
            accentColor = if (isDark) Color(0xFFFB923C) else Color(0xFFEA580C)
        )
        CampusCompetitionCategory.CYBER_SECURITY_AI -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Security,
            accentColor = if (isDark) Color(0xFF34D399) else Color(0xFF059669)
        )
        CampusCompetitionCategory.DIGITAL_MEDIA_ART -> ClubCategoryVisualMeta(
            icon = Icons.Rounded.Palette,
            accentColor = if (isDark) Color(0xFFF472B6) else Color(0xFFDB2777)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CampusCompetitionCard(
    comp: CampusCompetitionItem,
    isCoreForSelectedMajor: Boolean,
    effectivePartnerQq: String,
    onOpenDetail: () -> Unit,
    onCopyWebsite: () -> Unit,
    onOpenWebsite: () -> Unit,
    onJoinPartnerClub: () -> Unit
) {
    val visual = resolveCompetitionCategoryVisual(comp.category)

    XPeachCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenDetail
    ) {
        // 1. 顶部身份区：44dp 赛事方向图标 + 独占首行完整赛事名称 + 自动换行徽标流
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(visual.accentColor.copy(alpha = if (XPeachTheme.isDark) 0.20f else 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = visual.icon,
                    contentDescription = null,
                    tint = visual.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = comp.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isCoreForSelectedMajor) {
                        XPeachStatusBadge(
                            text = "本专业推荐",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    } else if (comp.pinned) {
                        XPeachStatusBadge(
                            text = "推荐",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = XPeachTheme.readablePrimary
                        )
                    }
                    XPeachStatusBadge(
                        text = comp.tierBadge,
                        containerColor = visual.accentColor.copy(alpha = if (XPeachTheme.isDark) 0.22f else 0.12f),
                        contentColor = visual.accentColor
                    )
                    XPeachStatusBadge(
                        text = comp.difficultyLabel,
                        containerColor = if (comp.beginnerFriendly) {
                            XPeachTheme.successContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                        },
                        contentColor = if (comp.beginnerFriendly) {
                            XPeachTheme.onSuccessContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // 2. 赛事核心简介（独占整宽）
        if (comp.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = comp.summary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 3. 能力与兴趣标签流
        if (comp.interestTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                for (tag in comp.interestTags) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (XPeachTheme.isDark) 0.26f else 0.45f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "#$tag",
                            style = MaterialTheme.typography.labelSmall,
                            color = XPeachTheme.readablePrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 4. 专业适配、备赛路线与合作社团组队凹槽
        XPeachWell(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.School,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(16.dp).padding(top = 2.dp)
                    )
                    Text(
                        text = "适合专业：${comp.recommendedMajorTracks.joinToString(" · ") { it.label }}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (comp.skillRoadmap.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AutoAwesome,
                            contentDescription = null,
                            tint = visual.accentColor,
                            modifier = Modifier.size(15.dp).padding(top = 2.dp)
                        )
                        Text(
                            text = "备赛建议：${comp.skillRoadmap}",
                            style = MaterialTheme.typography.labelSmall,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Text(
                    text = "赛程与赛制：${comp.registrationWindow} · ${comp.teamMode}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "承办与主办：${comp.hautHostCollege}（${comp.organizer}）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Groups,
                            contentDescription = null,
                            tint = XPeachTheme.readablePrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "关联社团：${comp.partnerClubName}（QQ 群：$effectivePartnerQq）",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = XPeachTheme.readablePrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        tint = XPeachTheme.readablePrimary,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "官方网站：${comp.officialWebsiteUrl}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = XPeachTheme.readablePrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5. 独立底部操作栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                XPeachFilterPill(
                    label = "赛事详情",
                    selected = false,
                    compact = true,
                    onClick = onOpenDetail
                )
                XPeachFilterPill(
                    label = "复制链接",
                    selected = false,
                    compact = true,
                    onClick = onCopyWebsite
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                    XPeachFilterPill(
                        label = "加入社团群",
                        selected = false,
                        compact = true,
                        onClick = onJoinPartnerClub
                    )
                }
                XPeachFilterPill(
                    label = "打开官网",
                    selected = true,
                    compact = false,
                    onClick = onOpenWebsite
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompetitionDetailDialog(
    comp: CampusCompetitionItem,
    effectivePartnerQq: String,
    onDismiss: () -> Unit,
    onCopyWebsite: () -> Unit,
    onOpenWebsite: () -> Unit,
    onJoinPartnerClub: () -> Unit
) {
    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = comp.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    XPeachStatusBadge(
                        text = comp.tierBadge,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = XPeachTheme.readablePrimary
                    )
                    XPeachStatusBadge(
                        text = comp.difficultyLabel,
                        containerColor = if (comp.beginnerFriendly) {
                            XPeachTheme.successContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (comp.beginnerFriendly) {
                            XPeachTheme.onSuccessContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "赛事级别与认可度",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = XPeachTheme.readablePrimary
                        )
                        Text(
                            text = comp.recognitionValueText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "适合专业与对象",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = comp.targetPersonaText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "分阶段备赛建议",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        for ((stageTitle, stageDesc) in comp.stagedProgressionRoadmap()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stageTitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = stageDesc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                XPeachWell(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "组织单位与官网",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "校内承办：${comp.hautHostCollege}\n主办单位：${comp.organizer}\n赛程窗口：${comp.registrationWindow}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                            Text(
                                text = "关联社团：${comp.partnerClubName}（QQ 群：$effectivePartnerQq）",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                        Text(
                            text = "官方网站：${comp.officialWebsiteUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = XPeachTheme.readablePrimary
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onOpenWebsite,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("打开官网")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (comp.partnerClubName.isNotBlank() && effectivePartnerQq.isNotBlank()) {
                    TextButton(onClick = onJoinPartnerClub) {
                        Text("加社团群")
                    }
                } else {
                    TextButton(onClick = onCopyWebsite) {
                        Text("复制链接")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}

internal fun openCompetitionWebsite(
    context: Context,
    title: String,
    url: String
): String {
    val cleanUrl = url.trim()
    copyCommunityText(context, "$title 官网", cleanUrl)
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "已复制链接，正在打开「$title」官网…"
    }.getOrElse {
        "已复制「$title」官网链接到剪贴板：$cleanUrl"
    }
}

internal fun copyCommunityText(context: Context, label: String, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

internal fun openOrCopyClubQqGroup(
    context: Context,
    clubName: String,
    groupNumber: String
): String {
    val cleanNo = groupNumber.trim()
    copyCommunityText(context, "$clubName QQ群号", cleanNo)
    val qqCardUri = Uri.parse(
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=$cleanNo&card_type=group&source=qrcode"
    )
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, qqCardUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "已复制群号 $cleanNo，正在打开手机 QQ..."
    }.getOrElse {
        "已复制群号 $cleanNo 到剪贴板"
    }
}

internal fun openPresetQqChannel(
    context: Context,
    channelTitle: String,
    inviteUrl: String
): String {
    val cleanUrl = inviteUrl.trim().ifBlank { "https://pd.qq.com" }
    copyCommunityText(context, channelTitle, cleanUrl)
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        "正在打开「$channelTitle」..."
    }.getOrElse {
        "已复制「$channelTitle」链接：$cleanUrl"
    }
}

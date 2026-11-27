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
fun CampusHospitalPharmacyDialog(
    onDismiss: () -> Unit,
    onOpenUrl: (String, String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<HospitalMedicineCategory?>(null) }
    var showPolicyRules by remember { mutableStateOf(false) }

    val filteredItems = remember(searchQuery, selectedCategory) {
        CampusHospitalPharmacyCatalog.search(
            query = searchQuery,
            categoryFilter = selectedCategory
        )
    }

    XPeachAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "校医院药品价格公示目录",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = "共收录 ${CampusHospitalPharmacyCatalog.ITEMS.size} 项西药/中成药 · 采集于 ${CampusHospitalPharmacyCatalog.CAPTURED_DATE_ISO}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                            text = "重要说明：本表为校医院现场价格公示册离线转录（医保报销前基准单价），不代表当前实时在售或库存，也不提供在线买药或处方指导。\n${CampusHospitalPharmacyCatalog.OUTPATIENT_REIMBURSEMENT_REFERENCE_SUMMARY}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "资料贡献者：@${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = XPeachTheme.readablePrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        onOpenUrl(
                                            "@${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE} GitHub",
                                            CampusHospitalPharmacyCatalog.CONTRIBUTOR_GITHUB_URL
                                        )
                                    }
                                    .padding(vertical = 2.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                XPeachFilterPill(
                                    label = "贡献者主页",
                                    selected = false,
                                    compact = true,
                                    onClick = {
                                        onOpenUrl(
                                            "@${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE} GitHub",
                                            CampusHospitalPharmacyCatalog.CONTRIBUTOR_GITHUB_URL
                                        )
                                    }
                                )
                                XPeachFilterPill(
                                    label = if (showPolicyRules) "收起医保说明" else "医保规则说明",
                                    selected = showPolicyRules,
                                    compact = true,
                                    onClick = { showPolicyRules = !showPolicyRules }
                                )
                            }
                        }
                    }
                }

                if (showPolicyRules) {
                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((title, desc) in CampusHospitalPharmacyCatalog.MEDICAL_INSURANCE_NOTES) {
                                Text(
                                    text = "• $title：$desc",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            XPeachFilterPill(
                                label = "查看学校医保政策原文",
                                selected = true,
                                compact = true,
                                onClick = {
                                    onOpenUrl("大学生医保政策说明", CampusHospitalPharmacyCatalog.POLICY_WECHAT_URL)
                                }
                            )
                        }
                    }
                }

                XPeachSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "搜索药名、规格、剂型、厂家或准字号"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    XPeachFilterPill(
                        label = "全部 (${CampusHospitalPharmacyCatalog.ITEMS.size})",
                        selected = selectedCategory == null,
                        compact = true,
                        onClick = { selectedCategory = null }
                    )
                    for (cat in HospitalMedicineCategory.entries) {
                        XPeachFilterPill(
                            label = cat.label,
                            selected = selectedCategory == cat,
                            compact = true,
                            onClick = {
                                selectedCategory = if (selectedCategory == cat) null else cat
                            }
                        )
                    }
                }

                Text(
                    text = "匹配结果：共 ${filteredItems.size} 项（可输入关键词按药名、规格、厂家或准字号检索）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                for (item in filteredItems) {
                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    XPeachStatusDotText(text = item.category.label)
                                    XPeachStatusDotText(
                                        text = item.provenanceBadgeText,
                                        dotColor = if (item.hasRegistryDiscrepancy) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        }
                                    )
                                }
                                Text(
                                    text = "规格：${item.specification} · 剂型：${item.dosageForm}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "${item.manufacturer} · ${item.approvalNumber} · 公示第${item.sourcePage}页",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                item.verificationNote?.let { vNote ->
                                    Text(
                                        text = vNote,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "¥${item.disclosedPriceYuan}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = XPeachTheme.readablePrimary
                                )
                                Text(
                                    text = "每${item.unit} · 医保前",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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

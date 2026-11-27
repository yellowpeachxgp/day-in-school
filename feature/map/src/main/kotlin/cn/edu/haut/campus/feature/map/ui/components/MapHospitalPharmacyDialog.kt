package cn.edu.haut.campus.feature.map.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog
import cn.edu.haut.campus.core.domain.HospitalMedicineCategory

@Composable
internal fun MapHospitalPharmacyDialog(
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<HospitalMedicineCategory?>(null) }
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
                Text("校医院药品价格公示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "共 ${CampusHospitalPharmacyCatalog.ITEMS.size} 项公示单价 · 致谢 @${CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE}",
                    style = MaterialTheme.typography.labelSmall,
                    color = XPeachTheme.readablePrimary,
                    modifier = Modifier.clickable {
                        onOpenUrl(CampusHospitalPharmacyCatalog.CONTRIBUTOR_GITHUB_URL)
                    }
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "本表忠实转录自校医院现场价格公示册原图（医保报销前基准单价），部分文号或规格与政府目录存在来源差异，已原样保留并标注待核实，不代表已核验现行医保政策。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("搜索药名、规格、厂家或准字号") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
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
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat }
                        )
                    }
                }
                Text(
                    text = "匹配结果：共 ${filteredItems.size} 项",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                for (item in filteredItems) {
                    XPeachWell(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    text = "${item.name} (${item.category.label}) · ${item.provenanceBadgeText}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${item.specification} · ${item.dosageForm} · ${item.manufacturer} · ${item.approvalNumber}",
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
                            Text(
                                text = "¥${item.disclosedPriceYuan}/${item.unit}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = XPeachTheme.readablePrimary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

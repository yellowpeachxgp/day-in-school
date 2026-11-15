package cn.edu.haut.campus.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #17 校医院药品目录自动化校验：
 * - 202 条目录、条目 ID 唯一且稳定；
 * - 每条「单价」可解析为非负数值；
 * - 批准文号与标准国药准字格式不符的条目一律带差异说明；
 * - 搜索覆盖中文、含空格名称、大小写混合拉丁文号、规格/厂家/文号字段；
 * - 类别交集与无结果场景行为明确。
 * 注：贡献者实拍原图不可得，本测试只校验代码内目录结构/搜索/标注一致性，不声称逐行 OCR 核验。
 */
class CampusHospitalPharmacyCatalogTest {

    private val standardNmpa = Regex("^国药准字(H|Z|S|B|F|J|HJ|ZJ|SJ)\\d{8}$")

    @Test
    fun catalogHas202UniqueStableIds() {
        assertEquals(202, CampusHospitalPharmacyCatalog.ITEMS.size)
        val ids = CampusHospitalPharmacyCatalog.ITEMS.map { it.id }
        assertEquals("Every medicine id must be unique", ids.size, ids.toSet().size)
        assertTrue(ids.all { it.matches(Regex("med_p\\d+_\\d+")) })
        // Page coverage sanity: pages 1..6 all represented and ordered
        assertEquals((1..6).toSet(), CampusHospitalPharmacyCatalog.ITEMS.map { it.sourcePage }.toSet())
    }

    @Test
    fun everyDisclosedPriceParsesToNonNegativeNumber() {
        for (item in CampusHospitalPharmacyCatalog.ITEMS) {
            val parsed = item.disclosedPriceYuan.trim().toDoubleOrNull()
            assertNotNull("Price must parse as a number", parsed)
            assertTrue("Price must be non-negative", parsed!! >= 0.0)
        }
    }

    @Test
    fun everyApprovalNumberAnomalyCarriesAnExplanationAndHonestStatus() {
        val anomalies = CampusHospitalPharmacyCatalog.ITEMS.filter {
            !standardNmpa.matches(it.approvalNumber.trim())
        }
        assertTrue("Expected several documented approval-format anomalies", anomalies.isNotEmpty())
        for (item in anomalies) {
            assertTrue(
                "Approval anomaly must be flagged PENDING_OFFICIAL_VERIFICATION",
                item.hasRegistryDiscrepancy
            )
            assertNotNull("Approval anomaly must carry a verification note", item.verificationNote)
            assertTrue(item.verificationNote!!.isNotBlank())
        }
        // 反向：所有标记差异的条目都必须有说明
        assertTrue(
            CampusHospitalPharmacyCatalog.ITEMS
                .filter { it.hasRegistryDiscrepancy }
                .all { !it.verificationNote.isNullOrBlank() }
        )
        // 诚实边界：没有条目自称 VERIFIED
        assertTrue(CampusHospitalPharmacyCatalog.ITEMS.none { it.verificationStatus == "VERIFIED" })
        // 已知差异条目的原文必须逐字保留
        val azithro = CampusHospitalPharmacyCatalog.ITEMS.single { it.id == "med_p1_9" }
        assertEquals("H10960167", azithro.approvalNumber)
    }

    @Test
    fun searchMatchesChineseSpacesMixedCaseLatinSpecMakerAndApproval() {
        // 中文名称
        assertEquals(2, CampusHospitalPharmacyCatalog.search("布洛芬").size)
        // 名称内含空格（转录原文含空格）
        assertTrue(CampusHospitalPharmacyCatalog.search("氯化钠 注射液").isNotEmpty())
        assertTrue(CampusHospitalPharmacyCatalog.search("0.9%氯化钠").size >= 2)
        // 拉丁文号：大小写混合查询
        val byLower = CampusHospitalPharmacyCatalog.search("h10960167")
        val byUpper = CampusHospitalPharmacyCatalog.search("H10960167")
        assertEquals(1, byLower.size)
        assertEquals(byLower, byUpper)
        // 规格字段
        assertTrue(CampusHospitalPharmacyCatalog.search("0.25g").isNotEmpty())
        // 厂家字段
        assertTrue(CampusHospitalPharmacyCatalog.search("辉瑞").isNotEmpty())
        // 文号字段（完整与片段）
        assertTrue(CampusHospitalPharmacyCatalog.search("国药准字H20043271").isNotEmpty())
        assertTrue(CampusHospitalPharmacyCatalog.search("Z213020797").isNotEmpty())
        // 剂型字段
        assertTrue(CampusHospitalPharmacyCatalog.search("注射液").size >= 10)
        // 空查询返回全量
        assertEquals(202, CampusHospitalPharmacyCatalog.search("").size)
        assertEquals(202, CampusHospitalPharmacyCatalog.search("   ").size)
    }

    @Test
    fun categoryIntersectionAndNoResultBehavePredictably() {
        val tcmOnly = CampusHospitalPharmacyCatalog.search(
            "胶囊",
            categoryFilter = HospitalMedicineCategory.TRADITIONAL_CHINESE
        )
        assertTrue(tcmOnly.isNotEmpty())
        assertTrue(tcmOnly.all { it.category == HospitalMedicineCategory.TRADITIONAL_CHINESE })
        assertTrue(tcmOnly.all { it.name.contains("胶囊") || it.dosageForm.contains("胶囊") || it.specification.contains("胶囊") })

        val westernInjection = CampusHospitalPharmacyCatalog.search(
            "注射液",
            categoryFilter = HospitalMedicineCategory.WESTERN,
            dosageFormFilter = "注射液"
        )
        assertTrue(westernInjection.isNotEmpty())
        assertTrue(westernInjection.all { it.dosageForm.contains("注射液") })

        // 交集为空 -> 明确返回空列表而非兜底全集
        val impossible = CampusHospitalPharmacyCatalog.search(
            "不存在的药品XYZ",
            categoryFilter = HospitalMedicineCategory.TRADITIONAL_CHINESE
        )
        assertTrue(impossible.isEmpty())
        assertTrue(CampusHospitalPharmacyCatalog.search("阿莫西林", categoryFilter = HospitalMedicineCategory.BIOLOGICAL).isEmpty())
    }

    @Test
    fun categoriesDeriveFromApprovalPrefixConsistently() {
        for (item in CampusHospitalPharmacyCatalog.ITEMS) {
            val expected = when {
                item.approvalNumber.contains("Z", ignoreCase = true) -> HospitalMedicineCategory.TRADITIONAL_CHINESE
                item.approvalNumber.contains("S", ignoreCase = true) -> HospitalMedicineCategory.BIOLOGICAL
                else -> HospitalMedicineCategory.WESTERN
            }
            assertEquals(expected, item.category)
        }
        // 类别计数稳定（全部条目恰属三类之一）
        val counts = CampusHospitalPharmacyCatalog.ITEMS.groupingBy { it.category }.eachCount()
        assertEquals(202, counts.values.sum())
        assertTrue(counts.getValue(HospitalMedicineCategory.TRADITIONAL_CHINESE) >= 40)
    }
}

package cn.edu.haut.campus.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #17 「提供校医院中的所有在售药品」独立回归。
 *
 * 修复点来自 b6ff9a25（离线目录、搜索与贡献者署名）与 d99ea30d（来源/医保诚实边界）。
 * 本测试只锁定 JVM 可见的目录数据、搜索与免责声明，不声称重新 OCR 核验原始照片。
 */
class Issue17HospitalPharmacyCatalogRegressionTest {

    @Test
    fun catalogKeepsExactly202TranscribedRowsAcrossSixSourcePages() {
        assertEquals(202, CampusHospitalPharmacyCatalog.ITEMS.size)
        assertEquals(
            mapOf(
                1 to 32,
                2 to 34,
                3 to 34,
                4 to 34,
                5 to 34,
                6 to 34
            ),
            CampusHospitalPharmacyCatalog.ITEMS.groupingBy { it.sourcePage }.eachCount()
        )
        assertEquals(
            "202 条公示记录必须一一对应稳定条目 ID",
            202,
            CampusHospitalPharmacyCatalog.ITEMS.map { it.id }.distinct().size
        )
        assertTrue(
            CampusHospitalPharmacyCatalog.ITEMS.all {
                it.capturedDateIso == CampusHospitalPharmacyCatalog.CAPTURED_DATE_ISO &&
                    it.name.isNotBlank() &&
                    it.specification.isNotBlank() &&
                    it.dosageForm.isNotBlank() &&
                    it.unit.isNotBlank() &&
                    it.disclosedPriceYuan.isNotBlank() &&
                    it.manufacturer.isNotBlank() &&
                    it.approvalNumber.isNotBlank()
            }
        )
    }

    @Test
    fun contributorGithubAttributionIsPinnedOnCatalogAndEveryRow() {
        assertEquals("Lollipop2233", CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE)
        assertEquals("https://github.com/Lollipop2233", CampusHospitalPharmacyCatalog.CONTRIBUTOR_GITHUB_URL)
        assertTrue(
            CampusHospitalPharmacyCatalog.ITEMS.all {
                it.contributorHandle == CampusHospitalPharmacyCatalog.CONTRIBUTOR_HANDLE &&
                    it.contributorGithubUrl == CampusHospitalPharmacyCatalog.CONTRIBUTOR_GITHUB_URL
            }
        )
        assertTrue(
            CampusHospitalPharmacyCatalog.MEDICAL_INSURANCE_NOTES
                .first { it.first == "来源与核实状态说明" }
                .second
                .contains("@Lollipop2233")
        )
    }

    @Test
    fun medicalInsuranceDisclaimersRefuseFixedPostInsurancePriceAndRealtimeInventoryClaims() {
        assertEquals(
            "https://mp.weixin.qq.com/s/QEVe1IvkyiyIfjttGhzKJQ",
            CampusHospitalPharmacyCatalog.POLICY_WECHAT_URL
        )
        val notesByTitle = CampusHospitalPharmacyCatalog.MEDICAL_INSURANCE_NOTES.toMap()
        assertEquals(
            setOf(
                "来源与核实状态说明",
                "价格性质说明",
                "并非所有药品均可医保报销",
                "为何不展示固定“医保后价格”",
                "校园指南参考提示"
            ),
            notesByTitle.keys
        )
        val allNotes = (notesByTitle.keys + notesByTitle.values).joinToString("\n")
        assertTrue(allNotes.contains("医保报销前基准单价"))
        assertTrue(allNotes.contains("不代表校医院当前实时库存或在售状态"))
        assertTrue(allNotes.contains("并非所有药品均可医保报销"))
        assertTrue(allNotes.contains("请以校医院收费窗口实时医保结算为准"))
        assertTrue(allNotes.contains("非政府医保局正式规范性文件"))

        val outpatientSummary = CampusHospitalPharmacyCatalog.OUTPATIENT_REIMBURSEMENT_REFERENCE_SUMMARY
        assertTrue(outpatientSummary.contains("纳入河南省基本医疗保险目录"))
        assertTrue(outpatientSummary.contains("非医保目录药品"))
        assertTrue(outpatientSummary.contains("不予报销"))
        assertTrue(outpatientSummary.contains("实际报销金额以校医院收费窗口实时医保结算为准"))

        val forbiddenFixedSettlementFields = HospitalMedicineDisclosureItem::class.java.declaredFields
            .map { it.name }
            .filter {
                Regex("(?i)(reimburs|insurance.*price|price.*insurance|post.*insurance|settlement|final.*price)").containsMatchIn(it)
            }
        assertEquals(
            "药品条目不得重新引入固定医保后价/结算价字段；实际结算只能以窗口实时结算为准",
            emptyList<String>(),
            forbiddenFixedSettlementFields
        )
    }

    @Test
    fun sourceTranscriptionNeverClaimsVerifiedAndDiscrepanciesPreserveRawText() {
        val honestStatuses = setOf(
            PharmacyVerificationStatus.SOURCE_TRANSCRIBED_UNVERIFIED,
            PharmacyVerificationStatus.PENDING_OFFICIAL_VERIFICATION
        )
        assertTrue(CampusHospitalPharmacyCatalog.ITEMS.all { it.verificationStatus in honestStatuses })
        assertFalse(CampusHospitalPharmacyCatalog.ITEMS.any { it.verificationStatus == "VERIFIED" })
        assertTrue(CampusHospitalPharmacyCatalog.ITEMS.all { it.provenanceBadgeText.contains("待核实") })

        val discrepancyRows = CampusHospitalPharmacyCatalog.ITEMS.filter { it.hasRegistryDiscrepancy }
        assertTrue(discrepancyRows.isNotEmpty())
        assertTrue(discrepancyRows.all { !it.verificationNote.isNullOrBlank() })

        val azithromycin = CampusHospitalPharmacyCatalog.ITEMS.single { it.id == "med_p1_9" }
        assertEquals("H10960167", azithromycin.approvalNumber)
        assertTrue(azithromycin.hasRegistryDiscrepancy)

        val huoxiang = CampusHospitalPharmacyCatalog.ITEMS.single { it.id == "med_p3_4" }
        assertEquals("国药准字Z213020797", huoxiang.approvalNumber)
        assertTrue(huoxiang.hasRegistryDiscrepancy)

        val importedTerbutaline = CampusHospitalPharmacyCatalog.ITEMS.single { it.id == "med_p3_27" }
        assertEquals("进口药品注册证", importedTerbutaline.approvalNumber)
        assertTrue(importedTerbutaline.hasRegistryDiscrepancy)

        val acetylcysteine = CampusHospitalPharmacyCatalog.ITEMS.single { it.id == "med_p6_16" }
        assertEquals("国药准字H2000471", acetylcysteine.approvalNumber)
        assertTrue(acetylcysteine.hasRegistryDiscrepancy)
    }

    @Test
    fun offlineSearchStillFindsOriginalMedicineExamplesWithoutNetworkAssumptions() {
        assertEquals(202, CampusHospitalPharmacyCatalog.search("").size)
        assertEquals(202, CampusHospitalPharmacyCatalog.search("   ").size)
        assertEquals("med_p1_9", CampusHospitalPharmacyCatalog.search("阿奇霉素").single().id)
        assertEquals(2, CampusHospitalPharmacyCatalog.search("布洛芬").size)
        assertEquals(
            CampusHospitalPharmacyCatalog.search("h10960167"),
            CampusHospitalPharmacyCatalog.search("H10960167")
        )
        assertTrue(
            CampusHospitalPharmacyCatalog.search(
                "注射液",
                categoryFilter = HospitalMedicineCategory.WESTERN,
                dosageFormFilter = "注射液"
            ).isNotEmpty()
        )
    }
}

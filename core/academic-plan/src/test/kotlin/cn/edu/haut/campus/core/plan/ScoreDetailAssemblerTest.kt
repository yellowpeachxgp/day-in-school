package cn.edu.haut.campus.core.plan

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩分项明细测试。
 *
 * UNVERIFIED：权重与得分是合成夹具，不代表任何真实学校的评分结构；
 * 这里验证「官方缺失时明确 Unavailable、缺少权重时不归一化、总分来源必须标注」的契约。
 */
class ScoreDetailAssemblerTest {

    @Test
    fun missingComponentsProduceUnavailableWithReason() {
        val detail = ScoreDetailAssembler.assemble("MATH101", "高等数学", emptyList())
        val unavailable = detail as? ScoreDetail.Unavailable
            ?: throw AssertionError("UNVERIFIED：没有分项时必须返回 Unavailable，实际为 $detail")
        assertTrue("UNVERIFIED：原因必须说明官方未提供分项，实际为 ${unavailable.reason}", unavailable.reason.contains("未提供"))
        assertNull("UNVERIFIED：没有数据时不得给出总分", unavailable.total)
        assertEquals("UNVERIFIED：不可用结论属于本地推导", Provenance.DERIVED, unavailable.provenance)
    }

    @Test
    fun officialTotalWithoutComponentsStaysUnavailableButKeepsOfficialTotal() {
        val detail = ScoreDetailAssembler.assemble("MATH101", "高等数学", emptyList(), officialTotal = BigDecimal("82"))
        val unavailable = detail as? ScoreDetail.Unavailable
            ?: throw AssertionError("UNVERIFIED：没有分项时仍应返回 Unavailable，实际为 $detail")
        PlanFixtures.assertAmount("82", unavailable.total!!.value, "UNVERIFIED：官方总分必须保留")
        assertEquals("UNVERIFIED：官方总分必须标记为 OFFICIAL", Provenance.OFFICIAL, unavailable.total!!.provenance)
        assertTrue("UNVERIFIED：原因应说明只有总分", unavailable.reason.contains("只返回了课程总分"))
    }

    @Test
    fun completeWeightsDeriveTotalAsDerived() {
        val detail = ScoreDetailAssembler.assemble(
            "MATH101",
            "高等数学",
            listOf(
                GradeComponent(ScoreComponentKind.USUAL, BigDecimal("30"), BigDecimal("90")),
                GradeComponent(ScoreComponentKind.FINAL, BigDecimal("70"), BigDecimal("80"))
            )
        )
        val available = detail as? ScoreDetail.Available
            ?: throw AssertionError("UNVERIFIED：有分项时必须返回 Available，实际为 $detail")
        PlanFixtures.assertAmount("83", available.total!!.value, "UNVERIFIED：30×90 + 70×80 = 8300，应得 83")
        assertEquals("UNVERIFIED：本地累加的总分只能标记为 DERIVED", Provenance.DERIVED, available.total!!.provenance)
        assertEquals("UNVERIFIED：整体可信级别应为 DERIVED", Provenance.DERIVED, available.provenance)
        assertTrue("UNVERIFIED：完整权重不应产生异常说明，实际为 ${available.notes}", available.notes.isEmpty())
    }

    @Test
    fun officialTotalWinsAndDiscrepancyIsNoted() {
        val detail = ScoreDetailAssembler.assemble(
            "MATH101",
            "高等数学",
            listOf(
                GradeComponent(ScoreComponentKind.USUAL, BigDecimal("30"), BigDecimal("90")),
                GradeComponent(ScoreComponentKind.FINAL, BigDecimal("70"), BigDecimal("80"))
            ),
            officialTotal = BigDecimal("85")
        )
        val available = detail as? ScoreDetail.Available
            ?: throw AssertionError("UNVERIFIED：有分项时必须返回 Available，实际为 $detail")
        PlanFixtures.assertAmount("85", available.total!!.value, "UNVERIFIED：必须采用官方总分")
        assertEquals("UNVERIFIED：官方总分必须标记为 OFFICIAL", Provenance.OFFICIAL, available.total!!.provenance)
        assertTrue(
            "UNVERIFIED：推算值与官方不一致必须写进说明，实际为 ${available.notes}",
            available.notes.any { it.contains("不一致") }
        )
    }

    @Test
    fun partialWeightsDoNotNormalize() {
        val detail = ScoreDetailAssembler.assemble(
            "MATH101",
            "高等数学",
            listOf(
                GradeComponent(ScoreComponentKind.USUAL, null, BigDecimal("90")),
                GradeComponent(ScoreComponentKind.FINAL, BigDecimal("70"), BigDecimal("80"))
            )
        )
        val available = detail as? ScoreDetail.Available
            ?: throw AssertionError("UNVERIFIED：有分项时必须返回 Available，实际为 $detail")
        assertNull("UNVERIFIED：缺少权重时不得推算总分", available.total)
        assertTrue(
            "UNVERIFIED：必须说明缺少官方权重且未归一化，实际为 ${available.notes}",
            available.notes.any { it.contains("缺少官方权重") && it.contains("未做归一化") }
        )
    }

    @Test
    fun weightsNotSummingToHundredAreNotNormalized() {
        val detail = ScoreDetailAssembler.assemble(
            "MATH101",
            "高等数学",
            listOf(
                GradeComponent(ScoreComponentKind.USUAL, BigDecimal("30"), BigDecimal("90")),
                GradeComponent(ScoreComponentKind.FINAL, BigDecimal("60"), BigDecimal("80"))
            )
        )
        val available = detail as? ScoreDetail.Available
            ?: throw AssertionError("UNVERIFIED：有分项时必须返回 Available，实际为 $detail")
        assertNull("UNVERIFIED：权重合计不是 100% 时不得强行归一化", available.total)
        assertTrue(
            "UNVERIFIED：必须给出权重合计说明，实际为 ${available.notes}",
            available.notes.any { it.contains("90%") }
        )
    }

    @Test
    fun missingComponentScoreBlocksDerivedTotal() {
        val detail = ScoreDetailAssembler.assemble(
            "MATH101",
            "高等数学",
            listOf(
                GradeComponent(ScoreComponentKind.USUAL, BigDecimal("50"), BigDecimal("90")),
                GradeComponent(ScoreComponentKind.FINAL, BigDecimal("50"), null)
            )
        )
        val available = detail as? ScoreDetail.Available
            ?: throw AssertionError("UNVERIFIED：有分项时必须返回 Available，实际为 $detail")
        assertNull("UNVERIFIED：缺少分项得分时不得推算总分", available.total)
        assertTrue(
            "UNVERIFIED：必须说明缺少官方得分，实际为 ${available.notes}",
            available.notes.any { it.contains("缺少官方得分") }
        )
    }

    @Test
    fun componentRejectsScoreAboveMax() {
        var thrown: IllegalArgumentException? = null
        try {
            GradeComponent(ScoreComponentKind.FINAL, BigDecimal("100"), BigDecimal("105"), BigDecimal("100"))
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：分项得分超过满分必须被拒绝", thrown != null)
    }

    @Test
    fun componentRejectsWeightAboveHundred() {
        var thrown: IllegalArgumentException? = null
        try {
            GradeComponent(ScoreComponentKind.USUAL, BigDecimal("120"), BigDecimal("90"))
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：分项权重超过 100% 必须被拒绝", thrown != null)
    }

    @Test
    fun componentSourcePropagatesToProvenance() {
        val detail = ScoreDetailAssembler.assemble(
            "MATH101",
            "高等数学",
            listOf(GradeComponent(ScoreComponentKind.USUAL, BigDecimal("100"), BigDecimal("90"))),
            officialTotal = BigDecimal("90"),
            componentSource = Provenance.ESTIMATED
        )
        assertEquals(
            "UNVERIFIED：手工录入的分项只能算估算，整体可信级别必须取最弱一环",
            Provenance.ESTIMATED,
            detail.provenance
        )
    }

    @Test
    fun blankCourseIdentityIsRejected() {
        var thrown: IllegalArgumentException? = null
        try {
            ScoreDetailAssembler.assemble("", "高等数学", emptyList())
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：空课程标识必须被拒绝", thrown != null)
    }
}

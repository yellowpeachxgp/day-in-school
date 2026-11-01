package cn.edu.haut.campus.core.plan

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩换算政策层测试。
 *
 * UNVERIFIED：档位与规则是按公开成绩制式构造的合成夹具，未在任何真实学校验证；
 * 本测试只验证「政策未确认时不得数值化」的行为契约，不构成任何学校换算办法的结论。
 */
class GradeScaleConverterTest {

    private val officialFiveLevel = GradeConversionPolicy(
        id = "fixture-five-level-official",
        label = "合成五级制政策（UNVERIFIED）",
        confirmation = PolicyConfirmation.OFFICIAL_SOURCE,
        fiveLevelRules = listOf(
            GradeConversionRule("优秀", BigDecimal("95"), BigDecimal("4.5")),
            GradeConversionRule("良好", BigDecimal("85"), BigDecimal("3.5"))
        )
    )

    @Test
    fun unconfirmedFiveLevelPolicyMustNotProduceNumbers() {
        val outcome = GradeScaleConverter.convert("优秀", GradeSystem.FIVE_LEVEL)
        assertTrue("UNVERIFIED：未确认政策的五级制成绩必须拒绝换算，实际为 $outcome", outcome is ConversionOutcome.NotConvertible)
        val reason = (outcome as ConversionOutcome.NotConvertible).reason
        assertTrue("UNVERIFIED：拒绝原因必须说明政策未确认，实际为 $reason", reason.contains("UNVERIFIED"))
    }

    @Test
    fun unconfirmedPassFailPolicyMustNotProduceNumbers() {
        val outcome = GradeScaleConverter.convert("通过", GradeSystem.PASS_FAIL)
        assertTrue("UNVERIFIED：合格制在政策未确认时不得猜测为分数或绩点，实际为 $outcome", outcome is ConversionOutcome.NotConvertible)
    }

    @Test
    fun confirmedOfficialFiveLevelPolicyConvertsAsDerived() {
        val outcome = GradeScaleConverter.convert("优秀", GradeSystem.FIVE_LEVEL, officialFiveLevel)
        val converted = outcome as? ConversionOutcome.Converted
            ?: throw AssertionError("UNVERIFIED：已确认政策应可换算，实际为 $outcome")
        PlanFixtures.assertAmount("95", converted.score!!, "UNVERIFIED：分数应按政策档位给出")
        PlanFixtures.assertAmount("4.5", converted.gradePoint!!, "UNVERIFIED：绩点应按政策档位给出")
        assertEquals("UNVERIFIED：官方口径政策换算结果应是本地推导（DERIVED）", Provenance.DERIVED, converted.provenance)
    }

    @Test
    fun userConfirmedPolicyIsMarkedEstimated() {
        val policy = officialFiveLevel.copy(
            id = "fixture-five-level-user",
            confirmation = PolicyConfirmation.USER_CONFIRMED
        )
        val outcome = GradeScaleConverter.convert("良好", GradeSystem.FIVE_LEVEL, policy)
        val converted = outcome as? ConversionOutcome.Converted
            ?: throw AssertionError("UNVERIFIED：用户已确认政策应可换算，实际为 $outcome")
        assertEquals("UNVERIFIED：用户确认而非官方来源，结果只能是估算（ESTIMATED）", Provenance.ESTIMATED, converted.provenance)
    }

    @Test
    fun confirmedPolicyWithUndefinedLevelIsStillRejected() {
        val outcome = GradeScaleConverter.convert("中等", GradeSystem.FIVE_LEVEL, officialFiveLevel)
        assertTrue("UNVERIFIED：政策未定义的档位必须拒绝，不得套用相邻档位，实际为 $outcome", outcome is ConversionOutcome.NotConvertible)
    }

    @Test
    fun percentageGradeKeepsOfficialValueAndDoesNotInventGradePoint() {
        val outcome = GradeScaleConverter.convert("95", GradeSystem.PERCENTAGE)
        val converted = outcome as? ConversionOutcome.Converted
            ?: throw AssertionError("UNVERIFIED：百分制数值应直接可用，实际为 $outcome")
        PlanFixtures.assertAmount("95", converted.score!!)
        assertEquals("UNVERIFIED：百分制不得凭空给出课程绩点", null, converted.gradePoint)
        assertEquals("UNVERIFIED：百分制原值属于官方数据", Provenance.OFFICIAL, converted.provenance)
    }

    @Test
    fun percentageOutOfRangeIsRejected() {
        listOf("120", "-1").forEach { raw ->
            val outcome = GradeScaleConverter.convert(raw, GradeSystem.PERCENTAGE)
            assertTrue("UNVERIFIED：超出 0..100 的百分制成绩必须拒绝，实际为 $outcome", outcome is ConversionOutcome.NotConvertible)
        }
    }

    @Test
    fun nonNumericPercentageTextIsRejected() {
        val outcome = GradeScaleConverter.convert("九十分", GradeSystem.PERCENTAGE)
        assertTrue("UNVERIFIED：非数值文本不得被当作百分制，实际为 $outcome", outcome is ConversionOutcome.NotConvertible)
    }

    @Test
    fun blankGradeTextIsRejected() {
        val outcome = GradeScaleConverter.convert("   ", GradeSystem.PERCENTAGE)
        assertTrue("UNVERIFIED：空成绩文本必须拒绝，实际为 $outcome", outcome is ConversionOutcome.NotConvertible)
    }

    @Test
    fun unknownGradeSystemIsRejected() {
        val outcome = GradeScaleConverter.convert("90", GradeSystem.UNKNOWN)
        assertTrue("UNVERIFIED：未知成绩制式不得数值化，实际为 $outcome", outcome is ConversionOutcome.NotConvertible)
    }

    @Test
    fun duplicateFiveLevelTokensAreRejected() {
        var thrown: IllegalArgumentException? = null
        try {
            GradeConversionPolicy(
                id = "dup",
                label = "重复档位",
                confirmation = PolicyConfirmation.OFFICIAL_SOURCE,
                fiveLevelRules = listOf(
                    GradeConversionRule("优秀", BigDecimal("95")),
                    GradeConversionRule("优秀", BigDecimal("90"))
                )
            )
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：同一档位重复定义必须被拒绝", thrown != null)
    }

    @Test
    fun ruleWithoutAnyNumericFieldIsRejected() {
        var thrown: IllegalArgumentException? = null
        try {
            GradeConversionRule("优秀")
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：既无分数也无绩点的规则没有意义，必须拒绝", thrown != null)
    }
}

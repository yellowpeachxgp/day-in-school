package cn.edu.haut.campus.core.plan

import java.math.BigDecimal

/** 成绩制式；教务未给出可识别口径时保持 [UNKNOWN]，不做任何推断。 */
enum class GradeSystem { PERCENTAGE, FIVE_LEVEL, PASS_FAIL, UNKNOWN }

/** 换算政策的确认来源；只有前两者允许把非百分制成绩数值化。 */
enum class PolicyConfirmation { OFFICIAL_SOURCE, USER_CONFIRMED, UNCONFIRMED }

/**
 * 一条换算规则：某个原始成绩文本（如「优秀」「通过」）对应的百分制分数与绩点。
 * 两个数值字段都可以为空，表示该校政策只定义了其中一项；领域层绝不补全另一项。
 */
data class GradeConversionRule(
    val token: String,
    val score: BigDecimal? = null,
    val gradePoint: BigDecimal? = null
) {
    init {
        require(token.isNotBlank()) { "换算规则的原始成绩文本不能为空" }
        require(score != null || gradePoint != null) { "换算规则至少要给出分数或绩点之一" }
        require(score == null || score in BigDecimal.ZERO..BigDecimal("100")) { "百分制分数必须在 0..100 之间" }
        require(gradePoint == null || gradePoint >= BigDecimal.ZERO) { "绩点不能为负数" }
    }
}

/**
 * 成绩换算政策。
 *
 * 默认值 [UNVERIFIED] 表示「政策未确认」：五级制与合格制一律不得数值化，
 * 避免把「优秀」猜成 95 分或 4.5 绩点。任何真实学校口径都必须由使用方显式注入。
 */
data class GradeConversionPolicy(
    val id: String,
    val label: String,
    val confirmation: PolicyConfirmation = PolicyConfirmation.UNCONFIRMED,
    val fiveLevelRules: List<GradeConversionRule> = emptyList(),
    val passFailRules: List<GradeConversionRule> = emptyList()
) {
    init {
        require(id.isNotBlank() && label.isNotBlank()) { "换算政策 ID 与名称不能为空" }
        require(fiveLevelRules.map { it.token }.distinct().size == fiveLevelRules.size) { "五级制规则存在重复档位" }
        require(passFailRules.map { it.token }.distinct().size == passFailRules.size) { "合格制规则存在重复档位" }
    }

    val confirmed: Boolean get() = confirmation != PolicyConfirmation.UNCONFIRMED

    fun rulesFor(system: GradeSystem): List<GradeConversionRule> = when (system) {
        GradeSystem.FIVE_LEVEL -> fiveLevelRules
        GradeSystem.PASS_FAIL -> passFailRules
        GradeSystem.PERCENTAGE, GradeSystem.UNKNOWN -> emptyList()
    }

    companion object {
        /** 未确认政策：任何非百分制成绩都不可换算（UNVERIFIED）。 */
        val UNVERIFIED = GradeConversionPolicy(
            id = "unverified-default",
            label = "成绩换算政策未确认（UNVERIFIED）",
            confirmation = PolicyConfirmation.UNCONFIRMED
        )
    }
}

/** 一次换算的结果：要么给出可解释的数值，要么明确拒绝。 */
sealed interface ConversionOutcome {
    data class Converted(
        val score: BigDecimal?,
        val gradePoint: BigDecimal?,
        val matchedToken: String,
        val policyId: String?,
        val provenance: Provenance
    ) : ConversionOutcome

    data class NotConvertible(val reason: String) : ConversionOutcome
}

/**
 * 成绩换算政策层。只做「政策怎么写就怎么算」，不内置任何学校的档位表：
 * 未经确认的五级制/合格制一律返回 [ConversionOutcome.NotConvertible]。
 */
object GradeScaleConverter {

    fun convert(
        rawGrade: String,
        system: GradeSystem,
        policy: GradeConversionPolicy = GradeConversionPolicy.UNVERIFIED
    ): ConversionOutcome {
        val token = rawGrade.trim()
        if (token.isEmpty()) return ConversionOutcome.NotConvertible("成绩文本为空，无法换算")
        return when (system) {
            GradeSystem.PERCENTAGE -> convertPercentage(token)
            GradeSystem.FIVE_LEVEL, GradeSystem.PASS_FAIL -> convertCategorical(token, system, policy)
            GradeSystem.UNKNOWN -> ConversionOutcome.NotConvertible("成绩制式未知（UNVERIFIED），不进行数值化")
        }
    }

    /** 百分制原值本身就是官方分数，原样返回；不从分数反推绩点（不猜公式）。 */
    private fun convertPercentage(token: String): ConversionOutcome {
        val score = token.toBigDecimalOrNull()
            ?: return ConversionOutcome.NotConvertible("百分制成绩「${token.take(16)}」不是数值，不进行换算")
        if (score < BigDecimal.ZERO || score > BigDecimal("100")) {
            return ConversionOutcome.NotConvertible("百分制成绩「${token.take(16)}」超出 0..100，拒绝采用")
        }
        return ConversionOutcome.Converted(
            score = score,
            gradePoint = null,
            matchedToken = token,
            policyId = null,
            provenance = Provenance.OFFICIAL
        )
    }

    private fun convertCategorical(
        token: String,
        system: GradeSystem,
        policy: GradeConversionPolicy
    ): ConversionOutcome {
        if (!policy.confirmed) {
            return ConversionOutcome.NotConvertible(
                "${system.name} 成绩「${token.take(16)}」没有已确认的换算政策（UNVERIFIED），不进行数值化猜测"
            )
        }
        val rule = policy.rulesFor(system).firstOrNull { it.token == token }
            ?: return ConversionOutcome.NotConvertible(
                "政策「${policy.id}」未定义「${token.take(16)}」的换算规则，不猜测相近档位"
            )
        val provenance = when (policy.confirmation) {
            PolicyConfirmation.OFFICIAL_SOURCE -> Provenance.DERIVED
            PolicyConfirmation.USER_CONFIRMED -> Provenance.ESTIMATED
            PolicyConfirmation.UNCONFIRMED -> error("未确认政策不应进入换算分支")
        }
        return ConversionOutcome.Converted(rule.score, rule.gradePoint, rule.token, policy.id, provenance)
    }
}

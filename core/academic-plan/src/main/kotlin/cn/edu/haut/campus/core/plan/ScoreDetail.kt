package cn.edu.haut.campus.core.plan

import java.math.BigDecimal
import java.math.RoundingMode

/** 成绩分项类型；与教务常见的「平时/期中/期末/实验」一一对应，其他归入 [OTHER]。 */
enum class ScoreComponentKind(val label: String) {
    USUAL("平时"),
    MIDTERM("期中"),
    FINAL("期末"),
    LAB("实验"),
    PROJECT("大作业"),
    OTHER("其他")
}

/**
 * 一个成绩分项。权重、得分、满分都可能缺失：官方没给就保持 null，
 * 绝不用 0 或「猜一个权重」的方式补齐。
 */
data class GradeComponent(
    val kind: ScoreComponentKind,
    val weightPercent: BigDecimal? = null,
    val score: BigDecimal? = null,
    val maxScore: BigDecimal? = null,
    val note: String? = null
) {
    init {
        require(weightPercent == null || weightPercent in BigDecimal.ZERO..BigDecimal("100")) {
            "分项权重必须在 0..100 之间"
        }
        require(score == null || score >= BigDecimal.ZERO) { "分项得分不能为负数" }
        require(maxScore == null || maxScore > BigDecimal.ZERO) { "分项满分必须为正数" }
        require(score == null || maxScore == null || score <= maxScore) { "分项得分不能超过满分" }
    }
}

/**
 * 一门课程的成绩分项明细。
 *
 * 官方没有提供分项时返回 [Unavailable] 并说明原因，不用「按总分倒推」之类的估算冒充明细；
 * 官方只给了总分时，总分仍然以 OFFICIAL 保留在 [total] 上。
 */
sealed interface ScoreDetail {
    val courseKey: String
    val courseName: String
    val provenance: Provenance
    val total: ProvenValue<BigDecimal>?

    data class Available(
        override val courseKey: String,
        override val courseName: String,
        val components: List<GradeComponent>,
        val componentSource: Provenance,
        override val total: ProvenValue<BigDecimal>?,
        val basis: String,
        val notes: List<String>
    ) : ScoreDetail {
        init {
            require(components.isNotEmpty()) { "明细至少包含一个分项" }
            require(basis.isNotBlank()) { "计算依据不能为空" }
        }

        /** 整体可信级别取决于最弱的一环：分项来自官方、总分是本地推算时，整体为 DERIVED。 */
        override val provenance: Provenance
            get() = Provenance.weakest(listOf(componentSource) + listOfNotNull(total?.provenance))
    }

    data class Unavailable(
        override val courseKey: String,
        override val courseName: String,
        val reason: String,
        override val total: ProvenValue<BigDecimal>? = null
    ) : ScoreDetail {
        init {
            require(reason.isNotBlank()) { "不可用的原因不能为空" }
        }

        /** 「官方没有分项」是从官方数据缺失推导出的事实，因此标记 DERIVED。 */
        override val provenance: Provenance
            get() = Provenance.weakest(listOf(Provenance.DERIVED) + listOfNotNull(total?.provenance))
    }
}

/**
 * 把官方分项成绩组装成可展示的明细，并在每个结论上标注可信级别。
 * 分项权重合计不等于 100% 时只记录说明，不做归一化；缺少权重时绝不推算总分。
 */
object ScoreDetailAssembler {

    private val HUNDRED = BigDecimal("100")

    fun assemble(
        courseKey: String,
        courseName: String,
        components: List<GradeComponent>,
        officialTotal: BigDecimal? = null,
        componentSource: Provenance = Provenance.OFFICIAL
    ): ScoreDetail {
        require(courseKey.isNotBlank() && courseName.isNotBlank()) { "课程标识与名称不能为空" }
        require(officialTotal == null || officialTotal >= BigDecimal.ZERO) { "官方总分不能为负数" }
        val official = officialTotal?.let {
            ProvenValue.official(it, "教务返回的课程总分（原样透传）")
        }

        if (components.isEmpty()) {
            return ScoreDetail.Unavailable(
                courseKey = courseKey,
                courseName = courseName,
                reason = if (official != null) "教务只返回了课程总分，没有分项明细" else "教务未提供该课程的分项成绩",
                total = official
            )
        }

        val notes = mutableListOf<String>()
        val weights = components.mapNotNull { it.weightPercent }
        val allWeighted = weights.size == components.size
        val weightSum = weights.fold(BigDecimal.ZERO) { acc, weight -> acc.add(weight) }
        val weightBalanced = allWeighted && weightSum.compareTo(HUNDRED) == 0
        if (!allWeighted) {
            notes += "部分分项缺少官方权重，未做归一化，也不据此推算总分"
        } else if (!weightBalanced) {
            notes += "分项权重合计 ${weightSum.toPlainString()}%，与 100% 不一致，未做归一化（不猜测缺失权重）"
        }
        if (allWeighted && components.any { it.score == null }) {
            notes += "部分分项缺少官方得分，未推算总分"
        }

        val derivedTotal = if (weightBalanced && components.all { it.score != null }) {
            val weighted = components.fold(BigDecimal.ZERO) { acc, component ->
                acc.add(component.weightPercent!!.multiply(component.score!!))
            }
            weighted.divide(HUNDRED, 2, RoundingMode.HALF_UP)
        } else null

        val resolvedTotal: ProvenValue<BigDecimal>? = when {
            official != null -> {
                if (derivedTotal != null && derivedTotal.compareTo(official.value) != 0) {
                    notes += "本地按权重推算总分 ${derivedTotal.toPlainString()} 与官方总分 " +
                        "${official.value.toPlainString()} 不一致，以官方为准"
                }
                official
            }
            derivedTotal != null -> ProvenValue.derived(
                derivedTotal,
                "按官方分项权重×分项得分累加得到，仅用于展示，不替换官方记录"
            )
            else -> null
        }

        return ScoreDetail.Available(
            courseKey = courseKey,
            courseName = courseName,
            components = components,
            componentSource = componentSource,
            total = resolvedTotal,
            basis = "官方分项成绩 ${components.size} 项（来源 ${componentSource.name}）",
            notes = notes
        )
    }
}

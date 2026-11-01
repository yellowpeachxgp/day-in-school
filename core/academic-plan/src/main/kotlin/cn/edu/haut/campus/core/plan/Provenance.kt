package cn.edu.haut.campus.core.plan

/**
 * 计算结果的可信级别；所有对外结论都必须携带它，界面据此区分「官方」与「本地推算」。
 *
 * - [OFFICIAL]：学校教务直接返回的字段，原样透传，不做任何加工；
 * - [DERIVED]：基于官方数据做的确定性推导（如按官方课程绩点加权、按官方方案统计学分）；
 * - [ESTIMATED]：依赖未确认政策、模拟假设或缺失信息的推断结果，只用于参考。
 *
 * 领域层约定：ESTIMATED 结果不得写回或冒充 OFFICIAL 记录（GPA 模拟只读取官方记录）。
 */
enum class Provenance {
    OFFICIAL,
    DERIVED,
    ESTIMATED;

    companion object {
        /**
         * 取最弱的可信级别：链路上任一环节是估算，整体结论就不能标为官方。
         * 空集合按最保守的 ESTIMATED 处理。
         */
        fun weakest(levels: Iterable<Provenance>): Provenance =
            levels.maxByOrNull { it.ordinal } ?: ESTIMATED
    }
}

/**
 * 带可信级别、计算依据与附加说明的值。
 * [basis] 是给人看的一句话说明「这个数字是怎么来的」，不允许为空或只写「计算得出」。
 */
data class ProvenValue<out T>(
    val value: T,
    val provenance: Provenance,
    val basis: String,
    val notes: List<String> = emptyList()
) {
    init {
        require(basis.isNotBlank()) { "计算依据不能为空" }
    }

    companion object {
        fun <T> official(value: T, basis: String, notes: List<String> = emptyList()): ProvenValue<T> =
            ProvenValue(value, Provenance.OFFICIAL, basis, notes)

        fun <T> derived(value: T, basis: String, notes: List<String> = emptyList()): ProvenValue<T> =
            ProvenValue(value, Provenance.DERIVED, basis, notes)

        fun <T> estimated(value: T, basis: String, notes: List<String> = emptyList()): ProvenValue<T> =
            ProvenValue(value, Provenance.ESTIMATED, basis, notes)
    }
}

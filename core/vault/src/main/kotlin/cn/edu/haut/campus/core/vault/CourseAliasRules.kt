package cn.edu.haut.campus.core.vault

/** R01 §6.4 的保守课程显式别名表；不做任意跳字缩写生成。 */
object CourseAliasRules {
    private val rules: List<Pair<String, List<String>>> = listOf(
        "高等数学" to listOf("高数"),
        "线性代数" to listOf("线代"),
        "大学英语" to listOf("大英"),
        "大学物理" to listOf("大物"),
        "概率论与数理统计" to listOf("概率", "概率统计"),
        "马克思主义基本原理" to listOf("马原"),
        "中国近现代史纲要" to listOf("史纲")
    )

    fun aliasesFor(courseName: String): List<String> {
        val normalized = SearchTextNormalizer.normalizePlain(courseName)
        return rules.flatMap { (fullName, aliases) ->
            if (normalized.contains(SearchTextNormalizer.normalizePlain(fullName))) aliases else emptyList()
        }.distinct()
    }
}

package cn.edu.haut.campus.core.protocol.eams

import java.math.BigDecimal

/**
 * 培养方案的只读视图：只保存页面上能直接读到的条目。
 * 不做学分核算、不做毕业判定，也不进入任何 UI 流程；字段缺失一律保持 null。
 *
 * UNVERIFIED：条目划分按合成夹具推导，未与任何真实学校核对。
 */
data class EamsTrainingPlan(
    val programId: String,
    val title: String?,
    val requiredCredits: BigDecimal?,
    val entries: List<EamsTrainingPlanEntry>
) {
    init { require(programId.isNotBlank()) { "培养方案 ID 不能为空" } }
}

/** 培养方案里的一门课程要求；课程名缺失的行不会被解析成条目。 */
data class EamsTrainingPlanEntry(
    val courseCode: String?,
    val courseName: String,
    val category: String?,
    val credits: BigDecimal?,
    val suggestedTerm: String?
) {
    init { require(courseName.isNotBlank()) { "培养方案条目缺少课程名称" } }
}

internal object EamsTrainingPlanParser {

    private val REQUIRED_CREDITS = Regex("总学分\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)")

    fun parse(programId: String, html: String): EamsTrainingPlan {
        val tables = EamsDocument.tables(html)
        val table = tables.firstOrNull { it.id == "planTable" } ?: tables.firstOrNull { it.header.isNotEmpty() }
        val entries = if (table == null) {
            emptyList()
        } else {
            table.rows.mapNotNull { row ->
                val name = table.cell(row, "课程名称") ?: return@mapNotNull null
                EamsTrainingPlanEntry(
                    courseCode = table.cell(row, "课程代码"),
                    courseName = name,
                    category = table.cell(row, "课程性质"),
                    credits = EamsParsers.decimal(table.cell(row, "学分")),
                    suggestedTerm = table.cell(row, "建议学期")
                )
            }
        }
        return EamsTrainingPlan(
            programId = programId,
            title = EamsDocument.firstText(html, "h2"),
            requiredCredits = REQUIRED_CREDITS.find(html)?.groupValues?.get(1)?.let { EamsParsers.decimal(it) },
            entries = entries
        )
    }
}

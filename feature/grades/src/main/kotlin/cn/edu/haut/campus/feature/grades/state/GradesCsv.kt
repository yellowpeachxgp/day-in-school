package cn.edu.haut.campus.feature.grades.state

import cn.edu.haut.campus.core.model.GradeAttempt

/** CSV 纯转换，保持搬迁前逐行格式不变。 */
fun buildGradesCsv(attempts: List<GradeAttempt>): String {
    val sb = StringBuilder()
    sb.appendLine("学期,课程代码,课程名称,课程性质,学分,原始成绩,教务课程绩点,修读方式")
    for (a in attempts) {
        sb.appendLine(
            "${a.termId},${a.courseKey},${a.name},${a.category.label},${a.credits},${a.rawGrade},${a.gradePoint ?: ""},${a.attemptType.label}"
        )
    }
    return sb.toString()
}

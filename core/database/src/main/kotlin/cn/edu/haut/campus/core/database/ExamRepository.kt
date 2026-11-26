package cn.edu.haut.campus.core.database

/** W0-05a 考试安排与考试学期修复领域 Repository；本提交先建立拆分外壳，后续提交迁入实现。 */
class ExamRepository internal constructor(
    private val environment: CampusRepositoryEnvironment
)

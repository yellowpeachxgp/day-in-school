package cn.edu.haut.campus.core.database

/** W0-05a 学期/校历领域 Repository；本提交先建立拆分外壳，后续提交迁入实现。 */
class TermRepository internal constructor(
    private val environment: CampusRepositoryEnvironment,
    private val examRepository: ExamRepository
)

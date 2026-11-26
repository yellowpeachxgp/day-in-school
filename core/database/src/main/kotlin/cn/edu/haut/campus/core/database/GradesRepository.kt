package cn.edu.haut.campus.core.database

/** W0-05a 成绩与学生资料领域 Repository；本提交先建立拆分外壳，后续提交迁入实现。 */
class GradesRepository internal constructor(
    private val environment: CampusRepositoryEnvironment
)

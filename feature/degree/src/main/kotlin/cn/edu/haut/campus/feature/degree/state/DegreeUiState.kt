package cn.edu.haut.campus.feature.degree.state

import cn.edu.haut.campus.core.degree.TrainingPlanImportRecord
import cn.edu.haut.campus.core.plan.AuditReport

/** 毕业进度看板状态机：覆盖加载、空、就绪与导入错误全矩阵。 */
enum class DegreeStatus {
    LOADING,
    EMPTY,
    READY,
    ERROR
}

/** 人工认定草稿：从一门未归类课程发起，选择归组或排除并填写依据。 */
data class OverrideDraft(
    val attemptId: String,
    val courseName: String,
    /** 打开草稿时绑定的方案导入；切换方案后提交必须失败而不是串用到新方案。 */
    val importId: String?,
    val targetGroupId: String?,
    val exclude: Boolean,
    val reason: String
)

data class DegreeUiState(
    val status: DegreeStatus = DegreeStatus.LOADING,
    val imports: List<TrainingPlanImportRecord> = emptyList(),
    val activeImportId: String? = null,
    val report: AuditReport? = null,
    val gradeAttemptCount: Int = 0,
    val expandedGroupIds: Set<String> = emptySet(),
    val showImportDialog: Boolean = false,
    val importText: String = "",
    val importError: String? = null,
    val overrideDraft: OverrideDraft? = null,
    val busy: Boolean = false,
    val errorMessage: String? = null,
    val notice: String? = null
) {
    val disclaimer: String
        get() = report?.personalPlanningDisclaimer ?: "本报告是个人规划参考，不代表学校毕业审核结论"
}

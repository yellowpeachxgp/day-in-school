package cn.edu.haut.campus.core.degree

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.plan.AuditContext
import cn.edu.haut.campus.core.plan.AuditOverride
import cn.edu.haut.campus.core.plan.AuditReport
import cn.edu.haut.campus.core.plan.ImportInput
import cn.edu.haut.campus.core.plan.OverrideAction
import cn.edu.haut.campus.core.plan.PlanSource
import cn.edu.haut.campus.core.plan.TrainingPlan
import java.time.Instant
import java.time.LocalDate

/**
 * 一次培养方案导入的元数据记录；方案本体以 univ-training-plan/v1 canonical JSON
 * 随版本化文档保存在 blob_store（AUTO-042 过渡，Room v15 显式迁移窗口前不建新表）。
 */
data class TrainingPlanImportRecord(
    val importId: String,
    val planId: String,
    val title: String,
    val planVersion: String,
    val format: String,
    val contentDigest: String,
    val warnings: List<String>,
    val skippedRowCount: Int,
    val source: PlanSource,
    val confirmedForStudent: Boolean,
    val importedAt: Instant,
    val importedBy: Actor,
    val active: Boolean
)

/** 录入人工认定的输入；[reason] 必填，是审批痕迹的一部分。 */
data class OverrideInput(
    val attemptId: String,
    val action: OverrideAction,
    val mapToGroupId: String?,
    val reason: String,
    /** 调用方显式指定时必须与当前激活导入一致；存储层总会把认定绑定到该导入。 */
    val importId: String? = null
) {
    init {
        require(attemptId.isNotBlank()) { "人工认定必须引用成绩记录" }
    }
}

/**
 * W3-01 毕业审计存储端口。
 *
 * 读取一律按本 profile scope 隔离；写操作经 Vault EntityWriter 进 journal，
 * 人工认定的创建与撤销可在变更历史中展开并撤销。擦除链路随 profile 覆盖 blob_store，
 * 不新增擦除面。
 */
interface DegreeRepository {
    /** 导入培养方案；内容摘要去重，同一文件重复导入不产生重复记录。 */
    suspend fun importPlan(input: ImportInput, actor: Actor): TrainingPlanImportRecord

    suspend fun listImports(): List<TrainingPlanImportRecord>

    /** 读取某次导入的完整方案；不存在或被撤销时返回 null。 */
    suspend fun loadPlan(importId: String): TrainingPlan?

    suspend fun setConfirmedForStudent(importId: String, confirmed: Boolean, actor: Actor)

    /** 切换当前审计使用的方案；传 null 表示取消激活。 */
    suspend fun setActiveImport(importId: String?, actor: Actor)

    suspend fun activeImport(): TrainingPlanImportRecord?

    /** 记录人工认定并写 journal；返回带稳定 ID 的认定实体。 */
    suspend fun recordOverride(input: OverrideInput, actor: Actor): AuditOverride

    suspend fun listOverrides(): List<AuditOverride>

    /** 用当前激活方案、全部成绩与生效人工认定执行审计；无激活方案时返回 null。 */
    suspend fun auditActivePlan(
        attempts: List<GradeAttempt>,
        now: LocalDate,
        context: AuditContext = AuditContext()
    ): AuditReport?

    fun close()
}

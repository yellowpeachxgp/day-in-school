package cn.edu.haut.campus.core.degree

import cn.edu.haut.campus.core.plan.GatewayPlanEntry
import cn.edu.haut.campus.core.plan.ImportInput
import cn.edu.haut.campus.core.protocol.eams.EamsTrainingPlan

/**
 * 教务网关只读视图 → 导入器中性输入的适配。
 *
 * EamsGateway.trainingPlan 需要已登录会话，抓取编排属应用层职责（不在本切片）；
 * 本适配只负责字段映射，缺失字段保持 null 由导入器按保守规则记诊断。
 */
fun EamsTrainingPlan.toGatewayViewInput(): ImportInput.GatewayView = ImportInput.GatewayView(
    programId = programId,
    title = title,
    requiredCreditsHint = requiredCredits,
    entries = entries.map { entry ->
        GatewayPlanEntry(
            courseCode = entry.courseCode,
            courseName = entry.courseName,
            category = entry.category,
            credits = entry.credits,
            suggestedTerm = entry.suggestedTerm
        )
    }
)

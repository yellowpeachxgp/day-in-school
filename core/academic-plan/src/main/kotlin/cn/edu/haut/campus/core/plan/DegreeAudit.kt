package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.CourseSource
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.GradeAttempt
import cn.edu.haut.campus.core.model.RetakeInclusionStrategy
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** 人工认定的动作类型：归入指定组、明确排除（不计入任何组）、撤销此前认定。 */
enum class OverrideAction(val label: String) {
    ASSIGN("人工归入"),
    EXCLUDE("人工排除"),
    RETRACT("撤销认定")
}

/**
 * MASTER_PLAN §6.6 的人工认定（进 journal）。
 *
 * [attemptRef] 引用一条成绩实体作为证据锚点；认定按逻辑课程维度生效——同一课程的补考/重修
 * 以及声明改名后的旧码/新码记录共享同一归属结论。[reason] 是审批痕迹的一部分，不允许空白；
 * [actor] 与 [at] 记录是谁在什么时候作出的决定。RETRACT 不再携带 [mapToGroupId]。
 *
 * 新写入的认定必须绑定产生它时的培养方案导入（[planImportId]）以及 planId/version 快照；
 * 切换方案或版本后不得把旧认定串用到同名 groupId 上。字段可空只为兼容纯领域层构造的
 * 历史/测试对象，存储层写入一律填齐。
 */
data class AuditOverride(
    val id: String,
    val attemptRef: EntityRef,
    val action: OverrideAction,
    val mapToGroupId: String?,
    val reason: String,
    val actor: Actor,
    val at: Instant,
    val planImportId: String? = null,
    val planId: String? = null,
    val planVersion: String? = null
) {
    init {
        require(id.isNotBlank()) { "认定 ID 不能为空" }
        require(reason.isNotBlank()) { "人工认定必须填写依据" }
        require(reason.length <= 512) { "人工认定依据过长" }
        if (action == OverrideAction.ASSIGN) {
            require(!mapToGroupId.isNullOrBlank()) { "ASSIGN 必须指定目标要求组" }
        }
        if (action != OverrideAction.ASSIGN) {
            require(mapToGroupId == null) { "EXCLUDE/RETRACT 不携带目标组" }
        }
    }
}

/** 一条审计轨迹：哪条规则、对哪门课、多少学分、得出什么结论、依据是什么。「查看依据」逐条展开它。 */
data class AuditStep(
    val sequence: Int,
    val rule: String,
    val courseKey: String?,
    val courseName: String?,
    val credits: BigDecimal?,
    val decision: String,
    val basis: String,
    /** 产生该结论的代表成绩实体；规则级汇总步骤为 null。 */
    val attemptId: String? = null,
    /** 代表成绩所在学年与学期，便于用户核对教务原文。 */
    val academicYear: String? = null,
    val termNumber: Int? = null,
    /** 成绩来源（教务、手工、CSV/ICS、OCR），防止把推导痕迹误认为官方事实。 */
    val gradeSource: CourseSource? = null
) {
    init {
        require(rule.isNotBlank() && decision.isNotBlank() && basis.isNotBlank()) {
            "审计轨迹的规则、结论与依据不能为空"
        }
    }
}

/** 一个要求组的缺口：还差多少学分、哪些方案课程未修。 */
data class RequirementGap(
    val groupId: String,
    val title: String,
    val kind: RequirementKind,
    val missingCredits: BigDecimal,
    val pendingCourses: List<PlanCourseRef>,
    val note: String?
)

enum class PlanRiskKind(val label: String) {
    SEASONAL_OFFERING("开课学期受限"),
    PREREQUISITE_UNMET("先修未完成"),
    GRADUATION_TIMELINE("影响毕业时间")
}

/** 一条毕业风险；[detail] 必须写清推断链条，不允许只写结论。 */
data class PlanRisk(
    val kind: PlanRiskKind,
    val title: String,
    val detail: String,
    val courseNames: List<String>
)

/** 学生侧上下文；缺失时审计不做与时间相关的臆测。 */
data class AuditContext(
    val gradeYear: Int? = null,
    val maxCreditsPerTerm: BigDecimal? = null,
    val retakeStrategy: RetakeInclusionStrategy = RetakeInclusionStrategy.HIGHEST_ATTEMPT
)

/** 一项在当前方案中实际生效的人工认定，供 UI 提供改派/撤销入口。 */
data class OverrideApplication(
    val override: AuditOverride,
    val courseKey: String,
    val courseName: String,
    val representativeAttemptId: String?,
    val targetGroupId: String?
)

/**
 * MASTER_PLAN §6.6 的毕业审计报告。
 *
 * 全部数字来自「培养方案 + 成绩 + 人工认定」的本地推导，只作个人规划参考，
 * 不代表学校毕业审核结论；[trace] 记录每一步的规则与依据，界面「查看依据」逐条展开。
 */
data class AuditReport(
    val planId: String,
    val planVersion: String,
    val generatedAt: LocalDate,
    val groups: List<GroupProgress>,
    val gaps: List<RequirementGap>,
    val risks: List<PlanRisk>,
    val trace: List<AuditStep>,
    val trust: Provenance,
    val unclassified: List<CompletedCourse>,
    val totalRequiredCredits: BigDecimal,
    val totalEarnedCredits: BigDecimal,
    val totalRemainingCredits: BigDecimal,
    val notes: List<String>,
    val basis: String,
    val representativeAttemptIds: Map<String, String> = emptyMap(),
    val overrideApplications: List<OverrideApplication> = emptyList()
) {
    val personalPlanningDisclaimer: String
        get() = "本报告是个人规划参考，不代表学校毕业审核结论"

    fun groupById(id: String): GroupProgress? = groups.firstOrNull { it.groupId == id }
}

/**
 * MASTER_PLAN §6.6 的 DegreeAuditor。
 *
 * 审计顺序（每一步都进 [AuditReport.trace]）：
 * 1. 先按方案声明的改名关系解析逻辑课程身份，再做重修/补考去重；
 * 2. 人工认定：ASSIGN 直接归组、EXCLUDE 排除、RETRACT 撤销；认定优先于一切自动匹配，
 *    且只在绑定的 plan/import 维度内生效；
 * 3. 替代课程：方案 substitutions 声明的抵免关系在直接匹配失败时兜底，被替代课程不再留 pending；
 * 4. 自动匹配：课程代码优先于名称，同课不多组重复计分；混合父组的直接课程参与匹配；
 * 5. 跨专业选课等未映射课程进入未归类，不参与缺口也不估算补齐；
 * 6. 层次汇总：子组已修计入父组汇总约束，但总计入学分仍按逻辑课程只计一次。
 */
object DegreeAuditor {

    fun audit(
        plan: TrainingPlan,
        attempts: List<GradeAttempt>,
        overrides: List<AuditOverride>,
        now: LocalDate,
        context: AuditContext = AuditContext(),
        planImportId: String? = null
    ): AuditReport {
        val trace = mutableListOf<AuditStep>()
        val notes = mutableListOf<String>()
        val overrideApplications = mutableListOf<OverrideApplication>()
        fun step(
            rule: String,
            courseKey: String?,
            courseName: String?,
            credits: BigDecimal?,
            decision: String,
            basis: String,
            attempt: GradeAttempt? = null
        ) {
            trace += AuditStep(
                sequence = trace.size + 1,
                rule = rule,
                courseKey = courseKey,
                courseName = courseName,
                credits = credits,
                decision = decision,
                basis = basis,
                attemptId = attempt?.id,
                academicYear = attempt?.academicYear,
                termNumber = attempt?.termNumber,
                gradeSource = attempt?.source
            )
        }

        // 1. 先解析逻辑课程身份，再在逻辑课程内做重修/补考去重。
        data class Identity(
            val logicalKey: String,
            val logicalName: String,
            val renamed: Boolean,
            val rename: CourseRename?
        )

        fun identityFor(attempt: GradeAttempt): Identity {
            val rename = plan.renames.firstOrNull { rule ->
                matchesCourseRef(rule.was, attempt.courseKey, attempt.name)
            }
            if (rename == null) {
                return Identity(attempt.courseKey.trim(), attempt.name.trim(), renamed = false, rename = null)
            }
            val key = rename.now.code?.trim()?.takeIf { it.isNotEmpty() }
                ?: "rename:" + normalize(rename.now.name)
            return Identity(key, rename.now.name.trim(), renamed = true, rename = rename)
        }

        val identityByAttemptId = attempts.associate { it.id to identityFor(it) }
        val logicalGroups = attempts
            .groupBy { normalize(identityByAttemptId.getValue(it.id).logicalKey) }
            .toSortedMap()
            .map { (_, records) ->
                val canonicalIdentity = records.map { identityByAttemptId.getValue(it.id) }
                    .firstOrNull { it.renamed }
                    ?: identityByAttemptId.getValue(records.first().id)
                LogicalAttemptGroup(
                    courseKey = canonicalIdentity.logicalKey,
                    courseName = canonicalIdentity.logicalName,
                    attempts = records
                )
            }

        logicalGroups.filter { group ->
            group.attempts.any { identityByAttemptId.getValue(it.id).renamed }
        }.forEach { group ->
            val first = group.attempts.firstOrNull { identityByAttemptId.getValue(it.id).renamed }
                ?: group.attempts.first()
            val rename = checkNotNull(identityByAttemptId.getValue(first.id).rename)
            val rawKeys = group.attempts.map { it.courseKey }.distinct().joinToString("/")
            step(
                rule = "rename",
                courseKey = first.courseKey,
                courseName = first.name,
                credits = null,
                decision = "课程改名：按方案声明以「" + group.courseName + "」参与逻辑课程聚合",
                basis = "方案 renames 声明「" + rename.was.name + "」更名为「" + rename.now.name +
                    "」；原始代码 " + rawKeys + " 共享同一逻辑课程身份",
                attempt = first
            )
        }

        val aggregation = AttemptAggregator.aggregateLogicalGroups(logicalGroups, context.retakeStrategy)
        aggregation.decisions.filter { it.attempts.size > 1 }.forEach { decision ->
            step(
                rule = "retake-dedupe",
                courseKey = decision.courseKey,
                courseName = decision.courseName,
                credits = decision.creditedCredits,
                decision = if (decision.selected != null) {
                    "按 " + context.retakeStrategy.name + " 策略选用「" + describeAttempt(decision.selected) + "」，学分只入账一次"
                } else {
                    "没有可用修读记录，学分不入账"
                },
                basis = decision.reason,
                attempt = decision.selected ?: decision.attempts.firstOrNull()
            )
        }
        notes += aggregation.notes

        // 2. 覆盖解析：先按 plan/import 维度过滤，再按逻辑课程取最新有效认定。
        val applicableOverrides = mutableListOf<AuditOverride>()
        overrides.forEach { override ->
            if (overrideApplies(override, plan, planImportId)) {
                applicableOverrides += override
            } else {
                step(
                    rule = "override-plan-mismatch",
                    courseKey = null,
                    courseName = null,
                    credits = null,
                    decision = "认定「" + override.id + "」绑定其他培养方案/导入，未在当前审计中生效",
                    basis = "override.planImportId=" + (override.planImportId ?: "缺失") +
                        ", planId=" + (override.planId ?: "缺失") +
                        ", planVersion=" + (override.planVersion ?: "缺失")
                )
            }
        }
        val overridesByCourse = LinkedHashMap<String, MutableList<AuditOverride>>()
        val attemptLogicalKeys = attempts.associate { attempt ->
            attempt.id to normalize(identityByAttemptId.getValue(attempt.id).logicalKey)
        }
        val logicalKeys = aggregation.decisions.associate { it.courseKey to normalize(it.courseKey) }
        applicableOverrides.forEach { override ->
            val normalizedKey = attemptLogicalKeys[override.attemptRef.id]
            if (normalizedKey == null) {
                step(
                    rule = "override-dangling",
                    courseKey = null,
                    courseName = null,
                    credits = null,
                    decision = "认定「" + override.id + "」引用的成绩不存在，未生效",
                    basis = "attemptRef=" + override.attemptRef.id + " 不在本次审计的成绩集合中"
                )
            } else {
                overridesByCourse.getOrPut(normalizedKey) { mutableListOf() } += override
            }
        }
        val effectiveOverrides = mutableMapOf<String, AuditOverride>()
        overridesByCourse.forEach { (normalizedKey, list) ->
            val ordered = list.sortedWith(compareBy({ it.at }, { it.id }))
            val latest = ordered.last()
            if (latest.action == OverrideAction.RETRACT) {
                step(
                    rule = "override-retract",
                    courseKey = logicalKeys.entries.firstOrNull { it.value == normalizedKey }?.key,
                    courseName = null,
                    credits = null,
                    decision = "最新认定为撤销，本课程回到自动匹配",
                    basis = "撤销依据：" + latest.reason
                )
            } else {
                effectiveOverrides[normalizedKey] = latest
            }
        }

        // 3. 课程级归组。所有组都可承接人工认定；自动匹配扫描显式列出课程的组（含混合父组）。
        val allGroups = plan.allGroups()
        val assignmentsByGroup = LinkedHashMap<String, MutableList<CourseAssignment>>()
        allGroups.forEach { assignmentsByGroup[it.id] = mutableListOf() }
        val courseBearingGroups = allGroups.filter { it.courses.isNotEmpty() }
        val unclassified = mutableListOf<CompletedCourse>()
        val passedKeys = mutableSetOf<String>()
        val passedNames = mutableSetOf<String>()

        aggregation.decisions.forEach { decision ->
            val normalizedKey = normalize(decision.courseKey)
            val representativeAttempt = decision.selected ?: decision.attempts.firstOrNull()
            if (!decision.passed || decision.creditedCredits <= BigDecimal.ZERO) {
                if (decision.attempts.isNotEmpty()) {
                    step(
                        rule = "not-passed",
                        courseKey = decision.courseKey,
                        courseName = decision.courseName,
                        credits = decision.creditedCredits,
                        decision = "未通过或无入账学分，不计入任何要求组",
                        basis = decision.reason,
                        attempt = representativeAttempt
                    )
                }
                return@forEach
            }
            passedKeys += normalize(decision.courseKey)
            passedNames += normalize(decision.courseName)
            val course = CompletedCourse(
                courseKey = decision.courseKey,
                courseName = decision.courseName,
                credits = decision.creditedCredits,
                representativeAttemptId = representativeAttempt?.id
            )

            val override = effectiveOverrides[normalizedKey]
            if (override != null) {
                when (override.action) {
                    OverrideAction.ASSIGN -> {
                        val target = plan.groupById(override.mapToGroupId!!)
                        if (target == null) {
                            step(
                                rule = "override-invalid",
                                courseKey = decision.courseKey,
                                courseName = decision.courseName,
                                credits = course.credits,
                                decision = "认定目标组不存在，按未生效处理并回到自动匹配",
                                basis = "mapToGroupId=" + override.mapToGroupId,
                                attempt = representativeAttempt
                            )
                        } else {
                            assignmentsByGroup.getValue(target.id) += CourseAssignment(
                                course = course,
                                groupId = target.id,
                                matchedBy = CourseMatchKind.NAME,
                                otherCandidateGroupIds = emptyList(),
                                overrideId = override.id
                            )
                            overrideApplications += OverrideApplication(
                                override = override,
                                courseKey = decision.courseKey,
                                courseName = decision.courseName,
                                representativeAttemptId = representativeAttempt?.id,
                                targetGroupId = target.id
                            )
                            step(
                                rule = "override-assign",
                                courseKey = decision.courseKey,
                                courseName = decision.courseName,
                                credits = course.credits,
                                decision = "人工认定归入「" + target.title + "」，不再参与自动匹配",
                                basis = "认定依据：" + override.reason + "（" + override.actor + " @ " + override.at + "）",
                                attempt = representativeAttempt
                            )
                            return@forEach
                        }
                    }
                    OverrideAction.EXCLUDE -> {
                        overrideApplications += OverrideApplication(
                            override = override,
                            courseKey = decision.courseKey,
                            courseName = decision.courseName,
                            representativeAttemptId = representativeAttempt?.id,
                            targetGroupId = null
                        )
                        step(
                            rule = "override-exclude",
                            courseKey = decision.courseKey,
                            courseName = decision.courseName,
                            credits = course.credits,
                            decision = "人工排除：不计入任何要求组，也不进未归类",
                            basis = "认定依据：" + override.reason + "（" + override.actor + " @ " + override.at + "）",
                            attempt = representativeAttempt
                        )
                        return@forEach
                    }
                    OverrideAction.RETRACT -> Unit // 不会到这里：RETRACT 已在 effectiveOverrides 过滤
                }
            }

            val candidates = candidateGroups(decision.courseKey, decision.courseName, courseBearingGroups)
            if (candidates.isNotEmpty()) {
                val chosen = candidates.first()
                assignmentsByGroup.getValue(chosen.first.id) += CourseAssignment(
                    course = course,
                    groupId = chosen.first.id,
                    matchedBy = chosen.second,
                    otherCandidateGroupIds = candidates.drop(1).map { it.first.id }
                )
                step(
                    rule = if (chosen.second == CourseMatchKind.CODE) "match-code" else "match-name",
                    courseKey = decision.courseKey,
                    courseName = decision.courseName,
                    credits = course.credits,
                    decision = "按" + (if (chosen.second == CourseMatchKind.CODE) "课程代码" else "课程名称") +
                        "匹配归入「" + chosen.first.title + "」" +
                        if (candidates.size > 1) "；其他候选组不重复计分" else "",
                    basis = "方案要求组「" + chosen.first.title + "」列有该课程" +
                        if (!chosen.first.isLeaf) "（混合组直接课程）" else "",
                    attempt = representativeAttempt
                )
            } else {
                val substitution = plan.substitutions.firstOrNull { rule ->
                    matchesCourseRef(rule.course, decision.courseKey, decision.courseName)
                }
                val substituteTarget = substitution?.let { rule ->
                    courseBearingGroups.firstOrNull { group -> group.courses.any { ref -> refsEqual(ref, rule.replaces) } }
                }
                if (substitution != null && substituteTarget != null) {
                    assignmentsByGroup.getValue(substituteTarget.id) += CourseAssignment(
                        course = course,
                        groupId = substituteTarget.id,
                        matchedBy = CourseMatchKind.NAME,
                        otherCandidateGroupIds = emptyList(),
                        fulfilledCourse = substitution.replaces
                    )
                    step(
                        rule = "substitute",
                        courseKey = decision.courseKey,
                        courseName = decision.courseName,
                        credits = course.credits,
                        decision = "替代课程：按方案声明抵「" + substitution.replaces.name + "」归入「" + substituteTarget.title + "」",
                        basis = "方案 substitutions 声明" + (substitution.note?.let { "：$it" } ?: ""),
                        attempt = representativeAttempt
                    )
                } else {
                    unclassified += course
                    step(
                        rule = "unclassified",
                        courseKey = decision.courseKey,
                        courseName = decision.courseName,
                        credits = course.credits,
                        decision = "未映射到任何要求组（可能为跨专业选课或方案外课程），单列未归类",
                        basis = "方案全部课程清单均无此课程的代码或名称，且无人工作出认定",
                        attempt = representativeAttempt
                    )
                }
            }
        }

        // 4. 层次组进度与缺口。父组聚合子组入账，但总入学分仍只按最直接归属汇总一次。
        val directEarnedByGroup = allGroups.associate { group ->
            group.id to assignmentsByGroup.getValue(group.id).fold(BigDecimal.ZERO) { acc, assignment ->
                acc.add(assignment.course.credits)
            }
        }
        val totals = RequirementTreeMath.analyze(plan, directEarnedByGroup)
        val visibleGroups = allGroups.filter(RequirementTreeMath::isProgressVisible)
        val gaps = mutableListOf<RequirementGap>()
        val groups = visibleGroups.map { group ->
            val assignments = assignmentsByGroup.getValue(group.id)
            val groupTotals = totals.getValue(group.id)
            val earned = groupTotals.aggregateEarnedCredits
            val remaining = (group.requiredCredits - earned).max(BigDecimal.ZERO)
            val surplus = (earned - group.requiredCredits).max(BigDecimal.ZERO)
            val assignedKeys = assignments.flatMap { listOf(normalize(it.course.courseName), normalize(it.course.courseKey)) }.toSet()
            val fulfilledBySubstitution = assignments.mapNotNull { it.fulfilledCourse }
            val pending = group.courses.filter { ref ->
                val directlyAssigned = normalize(ref.name) in assignedKeys ||
                    (ref.code != null && normalize(ref.code) in assignedKeys)
                val fulfilled = fulfilledBySubstitution.any { substitute -> refsEqual(substitute, ref) }
                !directlyAssigned && !fulfilled
            }
            val incrementalNeed = groupTotals.incrementalNeededCredits
            if (incrementalNeed > BigDecimal.ZERO) {
                gaps += RequirementGap(
                    groupId = group.id,
                    title = group.title,
                    kind = group.kind,
                    missingCredits = incrementalNeed,
                    pendingCourses = pending,
                    note = when {
                        !group.isLeaf -> "该组是汇总要求；此缺口是扣除子组缺口后仍需在本组子树内补齐的学分"
                        group.courses.isEmpty() -> "该组未列出具体课程（开放选修或任选课），缺口按学分统计"
                        else -> null
                    }
                )
            }
            GroupProgress(
                groupId = group.id,
                title = group.title,
                kind = group.kind,
                requiredCredits = group.requiredCredits,
                earnedCredits = earned,
                remainingCredits = remaining,
                surplusCredits = surplus,
                assignedCourses = assignments,
                pendingCourses = pending
            )
        }
        val totalRequired = plan.totalRequiredCredits
        val totalEarned = assignmentsByGroup.values.flatten().fold(BigDecimal.ZERO) { acc, assignment ->
            acc.add(assignment.course.credits)
        }
        val totalRemaining = RequirementTreeMath.totalAdditionalNeeded(plan, totals)
        val totalSurplus = visibleGroups.filter { it.isLeaf }.fold(BigDecimal.ZERO) { acc, group ->
            acc.add(group.surplusCredits)
        }
        step(
            rule = "group-total",
            courseKey = null,
            courseName = null,
            credits = totalEarned,
            decision = "层次要求树汇总完成：总要求 " + totalRequired.stripTrailingZeros().toPlainString() +
                "，已入账 " + totalEarned.stripTrailingZeros().toPlainString() +
                "，剩余缺口 " + totalRemaining.stripTrailingZeros().toPlainString(),
            basis = "子组成绩计入父组汇总约束；一门逻辑课程只在一个最直接归属组入账，学分不重复计算"
        )

        // 5. 风险
        val risks = mutableListOf<PlanRisk>()
        groups.forEach { group ->
            group.pendingCourses.forEach { ref ->
                if (ref.offering == CourseOffering.SPRING_ONLY || ref.offering == CourseOffering.AUTUMN_ONLY) {
                    risks += PlanRisk(
                        kind = PlanRiskKind.SEASONAL_OFFERING,
                        title = "「" + ref.name + "」" + ref.offering.label,
                        detail = "该课是「" + group.title + "」的未修要求且" + ref.offering.label +
                            "，错过开课学期需等待下一学年，可能延后毕业时间",
                        courseNames = listOf(ref.name)
                    )
                }
                val unmetPrereqs = ref.prerequisites.filter { prereq ->
                    normalize(prereq) !in passedNames && normalize(prereq) !in passedKeys
                }
                if (unmetPrereqs.isNotEmpty()) {
                    risks += PlanRisk(
                        kind = PlanRiskKind.PREREQUISITE_UNMET,
                        title = "「" + ref.name + "」先修未完成",
                        detail = "方案声明先修：" + unmetPrereqs.joinToString("、") +
                            "；这些先修课当前没有通过记录，直接修读可能影响毕业进度",
                        courseNames = listOf(ref.name) + unmetPrereqs
                    )
                }
            }
        }
        val contextGradeYear = context.gradeYear
        val contextMax = context.maxCreditsPerTerm
        if (contextGradeYear != null && contextMax != null && contextMax > BigDecimal.ZERO) {
            val remainingTerms = (plan.applicableGradeYears.maxOrNull() ?: 4) * 2 - (contextGradeYear - 1) * 2
            if (remainingTerms in 1..8) {
                val capacity = contextMax.multiply(BigDecimal(remainingTerms))
                if (totalRemaining > capacity) {
                    risks += PlanRisk(
                        kind = PlanRiskKind.GRADUATION_TIMELINE,
                        title = "剩余学分超出常规修读容量",
                        detail = "剩余缺口 " + totalRemaining.stripTrailingZeros().toPlainString() +
                            " 学分，按每学期至多 " + contextMax.stripTrailingZeros().toPlainString() +
                            " 学分、估计剩余 " + remainingTerms + " 个学期无法修完，可能影响毕业时间",
                        courseNames = emptyList()
                    )
                }
            }
        }

        // 6. 信任级别与说明
        val trust = when {
            plan.source == PlanSource.UNKNOWN -> Provenance.ESTIMATED
            !plan.confirmedForStudent -> Provenance.ESTIMATED
            else -> Provenance.DERIVED
        }
        if (plan.source == PlanSource.UNKNOWN) {
            notes += "培养方案来源未知（UNVERIFIED），结论仅供估算，不代表官方审核"
        } else if (!plan.confirmedForStudent) {
            notes += "培养方案尚未确认适用于本人（年级/专业），结论为估算"
        }
        if (overrides.isNotEmpty()) {
            notes += "本报告含 " + overrideApplications.size + " 项生效人工认定，每项依据可在审计轨迹中展开"
        }
        if (unclassified.isNotEmpty()) {
            val credits = unclassified.fold(BigDecimal.ZERO) { acc, course -> acc.add(course.credits) }
            notes += "有 " + unclassified.size + " 门已修课程（合计 " + credits.stripTrailingZeros().toPlainString() +
                " 学分）未归类，可能是跨专业选课或方案外课程，可用人工认定归组"
        }
        if (totalSurplus > BigDecimal.ZERO) {
            notes += "存在超额修读 " + totalSurplus.stripTrailingZeros().toPlainString() + " 学分，超额部分不抵扣其他组缺口"
        }

        return AuditReport(
            planId = plan.id,
            planVersion = plan.version,
            generatedAt = now,
            groups = groups,
            gaps = gaps.sortedWith(compareBy({ it.kind.ordinal }, { it.groupId })),
            risks = risks,
            trace = trace,
            trust = trust,
            unclassified = unclassified,
            totalRequiredCredits = totalRequired,
            totalEarnedCredits = totalEarned,
            totalRemainingCredits = totalRemaining,
            notes = notes,
            basis = "按培养方案「" + plan.title + "」" + plan.version + " 的层次要求树审计 " +
                attempts.size + " 条成绩记录与 " + overrides.size + " 项人工认定",
            representativeAttemptIds = aggregation.decisions.mapNotNull { decision ->
                val attemptId = decision.selected?.id ?: decision.attempts.firstOrNull()?.id
                if (attemptId == null) null else decision.courseKey to attemptId
            }.toMap(),
            overrideApplications = overrideApplications
        )
    }

    private fun overrideApplies(override: AuditOverride, plan: TrainingPlan, planImportId: String?): Boolean {
        val hasBinding = override.planImportId != null || override.planId != null || override.planVersion != null
        if (!hasBinding) return true
        if (override.planId != null && override.planId != plan.id) return false
        if (override.planVersion != null && override.planVersion != plan.version) return false
        if (override.planImportId != null) {
            if (planImportId == null || override.planImportId != planImportId) return false
        }
        return true
    }

    private fun describeAttempt(attempt: GradeAttempt): String =
        attempt.academicYear + " 第" + attempt.termNumber + "学期 " + attempt.attemptType.label +
            " " + attempt.rawGrade

    private fun candidateGroups(
        courseKey: String,
        courseName: String,
        groups: List<RequirementGroup>
    ): List<Pair<RequirementGroup, CourseMatchKind>> {
        val byCode = groups.mapNotNull { group ->
            val hit = group.courses.any { ref -> ref.code != null && normalize(ref.code) == normalize(courseKey) }
            if (hit) group to CourseMatchKind.CODE else null
        }
        if (byCode.isNotEmpty()) return byCode
        return groups.mapNotNull { group ->
            val hit = group.courses.any { ref -> normalize(ref.name) == normalize(courseName) }
            if (hit) group to CourseMatchKind.NAME else null
        }
    }

    private fun matchesCourseRef(ref: PlanCourseRef, courseKey: String, courseName: String): Boolean {
        val byCode = ref.code != null && normalize(ref.code) == normalize(courseKey)
        return byCode || normalize(ref.name) == normalize(courseName)
    }

    private fun refsEqual(a: PlanCourseRef, b: PlanCourseRef): Boolean {
        if (a.code != null && b.code != null) return normalize(a.code) == normalize(b.code)
        return normalize(a.name) == normalize(b.name)
    }

    private fun normalize(text: String): String = text.trim().lowercase(Locale.ROOT)
}

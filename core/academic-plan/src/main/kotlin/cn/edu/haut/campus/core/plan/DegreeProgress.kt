package cn.edu.haut.campus.core.plan

import java.math.BigDecimal
import java.util.Locale

/**
 * 一条已入账的修读记录，通常由 [AttemptAggregator] 的结论投影而来。
 * 领域层不读取数据库，也不访问网络；调用方负责把官方成绩整理成这个形状。
 */
data class CompletedCourse(
    val courseKey: String,
    val courseName: String,
    val credits: BigDecimal
) {
    init {
        require(courseKey.isNotBlank() && courseName.isNotBlank()) { "课程标识与名称不能为空" }
        require(credits >= BigDecimal.ZERO) { "已修学分不能为负数" }
    }
}

/** 课程与要求组的匹配方式；代码匹配优先于名称匹配。 */
enum class CourseMatchKind { CODE, NAME }

/** 一门课程被归入某个组的记录；[otherCandidateGroupIds] 是被排除的重复计分机会。 */
data class CourseAssignment(
    val course: CompletedCourse,
    val groupId: String,
    val matchedBy: CourseMatchKind,
    val otherCandidateGroupIds: List<String>
)

/** 单个要求组的完成情况；[remainingCredits] 即该组缺口。 */
data class GroupProgress(
    val groupId: String,
    val title: String,
    val kind: RequirementKind,
    val requiredCredits: BigDecimal,
    val earnedCredits: BigDecimal,
    val remainingCredits: BigDecimal,
    val surplusCredits: BigDecimal,
    val assignedCourses: List<CourseAssignment>,
    val pendingCourses: List<PlanCourseRef>
)

/**
 * 毕业进度报告。
 *
 * 数字来自「官方培养方案 + 官方已修记录」的本地推导（DERIVED）；
 * 方案来源未知或未确认适用于本人时降级为 ESTIMATED。
 * 未映射到任何组的课程单列在 [unclassified]，不计入任何组，也不参与缺口计算。
 */
data class DegreeProgress(
    val planId: String,
    val planVersion: String,
    val provenance: Provenance,
    val basis: String,
    val groups: List<GroupProgress>,
    val unclassified: List<CompletedCourse>,
    val totalRequiredCredits: BigDecimal,
    val totalEarnedCredits: BigDecimal,
    val totalRemainingCredits: BigDecimal,
    val totalSurplusCredits: BigDecimal,
    val notes: List<String>
) {
    fun groupById(id: String): GroupProgress? = groups.firstOrNull { it.groupId == id }

    val unclassifiedCredits: BigDecimal
        get() = unclassified.fold(BigDecimal.ZERO) { acc, course -> acc.add(course.credits) }
}

/**
 * 按培养方案统计毕业进度。规则（本地推导，UNVERIFIED，不代表任何学校的真实认定口径）：
 *
 * 1. 只有叶子组参与统计，父组学分不重复累加；
 * 2. 一门课程只归入一个组：先按课程代码匹配，再按课程名称匹配，同级按方案先序取第一个；
 * 3. 同一课程出现多条已修记录时按课程去重，学分只算一次；
 * 4. 未匹配到任何组的课程进入「未归类」，不计入学分与缺口，也不做估算补齐；
 * 5. 总缺口是各组缺口之和：某组超额不能抵扣其他组的缺口。
 */
object DegreeProgressCalculator {

    fun evaluate(plan: TrainingPlan, completed: List<CompletedCourse>): DegreeProgress {
        val notes = mutableListOf<String>()
        val deduped = dedupe(completed, notes)
        val leaves = plan.leafGroups()

        val assignmentsByGroup = LinkedHashMap<String, MutableList<CourseAssignment>>()
        leaves.forEach { assignmentsByGroup[it.id] = mutableListOf() }
        val unclassified = mutableListOf<CompletedCourse>()

        deduped.sortedWith(compareBy({ it.courseKey }, { it.courseName })).forEach { course ->
            val candidates = candidateGroups(course, leaves)
            if (candidates.isEmpty()) {
                unclassified += course
            } else {
                val chosen = candidates.first()
                val others = candidates.drop(1).map { it.first.id }
                if (others.isNotEmpty()) {
                    notes += "课程「${course.courseName}」可归入 ${candidates.size} 个要求组，" +
                        "按方案先序归入「${chosen.first.title}」，其他组不重复计分"
                }
                assignmentsByGroup.getValue(chosen.first.id) += CourseAssignment(
                    course = course,
                    groupId = chosen.first.id,
                    matchedBy = chosen.second,
                    otherCandidateGroupIds = others
                )
            }
        }

        val groups = leaves.map { group ->
            val assignments = assignmentsByGroup.getValue(group.id)
            val earned = assignments.fold(BigDecimal.ZERO) { acc, assignment -> acc.add(assignment.course.credits) }
            val remaining = (group.requiredCredits - earned).max(BigDecimal.ZERO)
            val surplus = (earned - group.requiredCredits).max(BigDecimal.ZERO)
            val pending = group.courses.filter { ref -> assignments.none { matchesRef(it.course, ref) } }
            if (group.courses.isEmpty() && group.requiredCredits > BigDecimal.ZERO) {
                notes += "要求组「${group.title}」未列出具体课程（开放选修或任选课），缺口按学分统计，不推断具体待修课程"
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

        val totalRequired = groups.fold(BigDecimal.ZERO) { acc, group -> acc.add(group.requiredCredits) }
        val totalEarned = groups.fold(BigDecimal.ZERO) { acc, group -> acc.add(group.earnedCredits) }
        val totalRemaining = groups.fold(BigDecimal.ZERO) { acc, group -> acc.add(group.remainingCredits) }
        val totalSurplus = groups.fold(BigDecimal.ZERO) { acc, group -> acc.add(group.surplusCredits) }

        val provenance = when {
            plan.source == PlanSource.UNKNOWN -> Provenance.ESTIMATED
            !plan.confirmedForStudent -> Provenance.ESTIMATED
            else -> Provenance.DERIVED
        }
        if (plan.source == PlanSource.UNKNOWN) {
            notes += "培养方案来源未知（UNVERIFIED），进度仅供估算，不代表官方结论"
        } else if (!plan.confirmedForStudent) {
            notes += "培养方案尚未确认适用于本人（年级/专业），进度为估算"
        }
        if (unclassified.isNotEmpty()) {
            val credits = unclassified.fold(BigDecimal.ZERO) { acc, course -> acc.add(course.credits) }
            notes += "有 ${unclassified.size} 门已修课程（合计 ${credits.stripTrailingZeros().toPlainString()} 学分）" +
                "未在方案中映射，单列为未归类，不计入任何组的已修学分"
        }

        return DegreeProgress(
            planId = plan.id,
            planVersion = plan.version,
            provenance = provenance,
            basis = "按培养方案「${plan.title}」${plan.version} 的 ${leaves.size} 个叶子要求组统计已修记录",
            groups = groups,
            unclassified = unclassified,
            totalRequiredCredits = totalRequired,
            totalEarnedCredits = totalEarned,
            totalRemainingCredits = totalRemaining,
            totalSurplusCredits = totalSurplus,
            notes = notes
        )
    }

    /** 代码匹配优先：只要方案里有课程代码命中，就不再退化为名称匹配，避免同名课程串组。 */
    private fun candidateGroups(
        course: CompletedCourse,
        leaves: List<RequirementGroup>
    ): List<Pair<RequirementGroup, CourseMatchKind>> {
        val byCode = leaves.mapNotNull { group ->
            val hit = group.courses.any { ref -> ref.code != null && normalize(ref.code) == normalize(course.courseKey) }
            if (hit) group to CourseMatchKind.CODE else null
        }
        if (byCode.isNotEmpty()) return byCode
        return leaves.mapNotNull { group ->
            val hit = group.courses.any { ref -> normalize(ref.name) == normalize(course.courseName) }
            if (hit) group to CourseMatchKind.NAME else null
        }
    }

    private fun matchesRef(course: CompletedCourse, ref: PlanCourseRef): Boolean {
        val byCode = ref.code != null && normalize(ref.code) == normalize(course.courseKey)
        return byCode || normalize(ref.name) == normalize(course.courseName)
    }

    /** 同一课程的多条记录按课程去重，保留学分最高的一条，保证学分只入账一次。 */
    private fun dedupe(completed: List<CompletedCourse>, notes: MutableList<String>): List<CompletedCourse> {
        val byKey = LinkedHashMap<String, CompletedCourse>()
        var duplicates = 0
        completed.sortedWith(compareBy({ it.courseKey }, { it.credits })).forEach { course ->
            val existing = byKey[course.courseKey]
            if (existing == null) {
                byKey[course.courseKey] = course
            } else {
                duplicates++
                if (course.credits > existing.credits) byKey[course.courseKey] = course
            }
        }
        if (duplicates > 0) {
            notes += "检测到 $duplicates 条重复课程记录（同一课程多次入账），按课程去重后学分只计一次"
        }
        return byKey.values.toList()
    }

    private fun normalize(text: String): String = text.trim().lowercase(Locale.ROOT)
}

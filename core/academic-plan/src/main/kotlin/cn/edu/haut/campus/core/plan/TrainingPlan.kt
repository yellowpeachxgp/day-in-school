package cn.edu.haut.campus.core.plan

import java.math.BigDecimal
import java.util.Locale

/** 培养方案数据来源；[UNKNOWN] 表示来源不明，下游进度只能按估算展示。 */
enum class PlanSource(val label: String) {
    OFFICIAL_IMPORT("官方导入"),
    USER_CONFIRMED("用户确认"),
    UNKNOWN("来源未知")
}

/** 要求组性质；一个方案通常同时包含必修、选修、实践与通识四类要求。 */
enum class RequirementKind(val label: String) {
    REQUIRED("必修"),
    ELECTIVE("选修"),
    PRACTICE("实践"),
    GENERAL("通识")
}

/** 课程在方案中声明的开课学期；[UNKNOWN] 表示方案没有给出，风险判断不得臆测。 */
enum class CourseOffering(val label: String) {
    SPRING_ONLY("仅春季开设"),
    AUTUMN_ONLY("仅秋季开设"),
    BOTH("春秋均开设"),
    UNKNOWN("开课学期未知")
}

/**
 * 方案里的一门可归属课程。教务的课程代码可能缺失，此时只按名称匹配。
 * 匹配一律精确比较（忽略大小写与首尾空白），不做模糊匹配，避免把相近课程算成同一门。
 *
 * [offering] 与 [prerequisites] 是方案可能声明的教学安排信息，只用于风险提示，
 * 不参与学分核算；[prerequisites] 的元素是同一方案内其他课程的代码或名称原文。
 */
data class PlanCourseRef(
    val code: String?,
    val name: String,
    val offering: CourseOffering = CourseOffering.UNKNOWN,
    val prerequisites: List<String> = emptyList()
) {
    init {
        require(name.isNotBlank()) { "方案课程名称不能为空" }
        require(code == null || code.isNotBlank()) { "课程代码为空时应传 null" }
        require(prerequisites.all { it.isNotBlank() }) { "先修课程引用不能为空字符串" }
    }
}

/**
 * 方案声明的替代课程规则：修完 [course] 可抵 [replaces] 的要求。
 * 只在方案显式声明时生效；学校实际认定口径以人工认定（AuditOverride）为准。
 */
data class SubstitutionRule(
    val course: PlanCourseRef,
    val replaces: PlanCourseRef,
    val note: String? = null
) {
    init {
        require(note == null || note.length <= 256) { "替代规则说明过长" }
    }
}

/**
 * 方案声明的课程改名映射：成绩里的 [was] 按 [now] 参与匹配。
 * 用于学校课程改名后老成绩归属；只在方案显式声明时生效，不做模糊猜测。
 */
data class CourseRename(
    val was: PlanCourseRef,
    val now: PlanCourseRef
)

/**
 * 培养方案要求组，可嵌套成树。父组学分是「整块要求」的展示值，
 * 真正承载课程与统计的是叶子组；两级学分一起累加会让总数虚高。
 */
data class RequirementGroup(
    val id: String,
    val title: String,
    val kind: RequirementKind,
    val requiredCredits: BigDecimal,
    val courses: List<PlanCourseRef> = emptyList(),
    val children: List<RequirementGroup> = emptyList()
) {
    init {
        require(id.isNotBlank() && title.isNotBlank()) { "要求组 ID 与名称不能为空" }
        require(requiredCredits >= BigDecimal.ZERO) { "要求学分不能为负数" }
        require(children.map { it.id }.distinct().size == children.size) { "同一层要求组 ID 重复" }
    }

    val isLeaf: Boolean get() = children.isEmpty()

    /** 先序遍历本组及其全部子组；顺序即毕业进度的归组优先级。 */
    fun traverse(): List<RequirementGroup> = buildList {
        add(this@RequirementGroup)
        children.forEach { addAll(it.traverse()) }
    }
}

/**
 * 培养方案：版本、适用年级/专业、要求组树与来源。
 * [confirmedForStudent] 表示用户已确认该方案适用于本人；未确认时进度只能是估算。
 */
data class TrainingPlan(
    val id: String,
    val title: String,
    val version: String,
    val applicableGradeYears: Set<Int>,
    val majors: Set<String> = emptySet(),
    val source: PlanSource,
    val confirmedForStudent: Boolean,
    val groups: List<RequirementGroup>,
    val substitutions: List<SubstitutionRule> = emptyList(),
    val renames: List<CourseRename> = emptyList()
) {
    init {
        require(id.isNotBlank() && title.isNotBlank() && version.isNotBlank()) {
            "培养方案的 ID、名称与版本不能为空"
        }
        require(applicableGradeYears.isNotEmpty()) { "培养方案必须声明适用年级" }
        require(applicableGradeYears.all { it in 1..8 }) { "适用年级必须在 1..8 之间" }
        require(majors.all { it.isNotBlank() }) { "适用专业名称不能为空" }
        require(groups.isNotEmpty()) { "培养方案至少要有一个要求组" }
        val ids = allGroups().map { it.id }
        require(ids.distinct().size == ids.size) { "培养方案存在重复要求组 ID" }
    }

    fun allGroups(): List<RequirementGroup> = groups.flatMap { it.traverse() }

    /** 只有叶子组承载实际课程要求，学分合计只累加叶子组。 */
    fun leafGroups(): List<RequirementGroup> = allGroups().filter { it.isLeaf }

    fun groupById(id: String): RequirementGroup? = allGroups().firstOrNull { it.id == id }

    val totalRequiredCredits: BigDecimal
        get() = leafGroups().fold(BigDecimal.ZERO) { acc, group -> acc.add(group.requiredCredits) }

    /** 年级与专业是否适用于该方案；专业集合为空表示方案未限定专业。 */
    fun matches(gradeYear: Int, major: String?): Boolean {
        if (gradeYear !in applicableGradeYears) return false
        if (majors.isEmpty()) return true
        val normalized = major?.trim()?.lowercase(Locale.ROOT) ?: return false
        return majors.any { it.trim().lowercase(Locale.ROOT) == normalized }
    }
}

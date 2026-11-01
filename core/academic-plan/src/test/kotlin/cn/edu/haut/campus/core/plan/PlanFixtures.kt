package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.model.AttemptType
import cn.edu.haut.campus.core.model.GradeAttempt
import java.math.BigDecimal
import java.time.Instant
import org.junit.Assert.assertTrue

/**
 * 测试夹具构造器。
 *
 * UNVERIFIED：所有成绩、学分、档位与培养方案均为合成数据，按公开的教务数据格式推导，
 * 不代表任何真实学校的口径，也没有在任何学校验证过。
 */
internal object PlanFixtures {

    fun attempt(
        courseKey: String,
        name: String = "课程-$courseKey",
        credits: String = "3.0",
        academicYear: String = "2024-2025",
        termNumber: Int = 1,
        rawGrade: String = "80",
        numericScore: String? = "80",
        gradePoint: String? = "3.0",
        attemptType: AttemptType = AttemptType.NORMAL,
        passed: Boolean = true,
        id: String = "$courseKey-$academicYear-$termNumber-${attemptType.name}"
    ): GradeAttempt = GradeAttempt(
        id = id,
        termId = academicYear + "-" + termNumber,
        academicYear = academicYear,
        termNumber = termNumber,
        courseKey = courseKey,
        name = name,
        credits = BigDecimal(credits),
        rawGrade = rawGrade,
        numericScore = numericScore?.let { BigDecimal(it) },
        gradePoint = gradePoint?.let { BigDecimal(it) },
        attemptType = attemptType,
        passed = passed,
        syncedAt = Instant.parse("2026-01-01T00:00:00Z")
    )

    fun ref(code: String?, name: String): PlanCourseRef = PlanCourseRef(code, name)

    fun group(
        id: String,
        credits: String,
        courses: List<PlanCourseRef> = emptyList(),
        kind: RequirementKind = RequirementKind.REQUIRED,
        children: List<RequirementGroup> = emptyList(),
        title: String = "组-$id"
    ): RequirementGroup = RequirementGroup(
        id = id,
        title = title,
        kind = kind,
        requiredCredits = BigDecimal(credits),
        courses = courses,
        children = children
    )

    fun completed(
        courseKey: String,
        name: String = "课程-$courseKey",
        credits: String = "3.0"
    ): CompletedCourse = CompletedCourse(courseKey, name, BigDecimal(credits))

    fun plan(
        groups: List<RequirementGroup>,
        source: PlanSource = PlanSource.OFFICIAL_IMPORT,
        confirmed: Boolean = true,
        gradeYears: Set<Int> = setOf(1, 2, 3, 4),
        majors: Set<String> = emptySet()
    ): TrainingPlan = TrainingPlan(
        id = "plan-fixture",
        title = "合成培养方案",
        version = "2026.1",
        applicableGradeYears = gradeYears,
        majors = majors,
        source = source,
        confirmedForStudent = confirmed,
        groups = groups
    )

    /** BigDecimal 比较按数值而非标度，避免 3.0 与 3 被判为不相等。 */
    fun assertAmount(expected: String, actual: BigDecimal, message: String = "") {
        val same = BigDecimal(expected).compareTo(actual) == 0
        assertTrue("$message（期望 $expected，实际 ${actual.toPlainString()}）", same)
    }
}

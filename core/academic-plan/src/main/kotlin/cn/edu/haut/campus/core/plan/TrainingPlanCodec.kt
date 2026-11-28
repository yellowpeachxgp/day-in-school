package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * TrainingPlan 与 univ-training-plan/v1 canonical JSON 的对称编码。
 * 反序列化统一走 [TrainingPlanImporter]，保证存储与手工导入使用同一份解析规则；
 * source 与 confirmedForStudent 是运行时状态，不在文档格式内，由存储信封单独携带。
 */
object TrainingPlanCodec {

    fun toJson(plan: TrainingPlan): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>()
        fields["format"] = JsonValue.Str(TrainingPlanImporter.JSON_FORMAT)
        fields["planId"] = JsonValue.Str(plan.id)
        fields["title"] = JsonValue.Str(plan.title)
        fields["version"] = JsonValue.Str(plan.version)
        fields["applicableGradeYears"] = JsonValue.Arr(plan.applicableGradeYears.sorted().map { JsonValue.Num(it.toString()) })
        fields["majors"] = JsonValue.Arr(plan.majors.sorted().map { JsonValue.Str(it) })
        fields["groups"] = JsonValue.Arr(plan.groups.map(::groupToJson))
        if (plan.substitutions.isNotEmpty()) {
            fields["substitutions"] = JsonValue.Arr(plan.substitutions.map { rule ->
                jsonObj(
                    "course" to courseToJson(rule.course),
                    "replaces" to courseToJson(rule.replaces),
                    "note" to rule.note?.let { JsonValue.Str(it) }
                )
            })
        }
        if (plan.renames.isNotEmpty()) {
            fields["renames"] = JsonValue.Arr(plan.renames.map { rename ->
                jsonObj(
                    "was" to courseToJson(rename.was),
                    "now" to courseToJson(rename.now)
                )
            })
        }
        return JsonValue.Obj(fields)
    }

    fun toCanonicalText(plan: TrainingPlan): String = Json.canonical(toJson(plan))

    fun fromJson(text: String): TrainingPlan =
        TrainingPlanImporter.import(ImportInput.JsonDocument(text)).plan

    private fun groupToJson(group: RequirementGroup): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>()
        fields["id"] = JsonValue.Str(group.id)
        fields["title"] = JsonValue.Str(group.title)
        fields["kind"] = JsonValue.Str(group.kind.name.lowercase())
        fields["requiredCredits"] = JsonValue.Num(group.requiredCredits.stripTrailingZeros().toPlainString())
        if (group.courses.isNotEmpty()) {
            fields["courses"] = JsonValue.Arr(group.courses.map(::courseToJson))
        }
        if (group.children.isNotEmpty()) {
            fields["children"] = JsonValue.Arr(group.children.map(::groupToJson))
        }
        return JsonValue.Obj(fields)
    }

    private fun courseToJson(course: PlanCourseRef): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>()
        course.code?.let { fields["code"] = JsonValue.Str(it) }
        fields["name"] = JsonValue.Str(course.name)
        if (course.offering != CourseOffering.UNKNOWN) {
            fields["offering"] = JsonValue.Str(
                when (course.offering) {
                    CourseOffering.SPRING_ONLY -> "spring"
                    CourseOffering.AUTUMN_ONLY -> "autumn"
                    CourseOffering.BOTH -> "both"
                    CourseOffering.UNKNOWN -> "unknown"
                }
            )
        }
        if (course.prerequisites.isNotEmpty()) {
            fields["prerequisites"] = JsonValue.Arr(course.prerequisites.map { JsonValue.Str(it) })
        }
        return JsonValue.Obj(fields)
    }

    private fun jsonObj(vararg entries: Pair<String, JsonValue?>): JsonValue.Obj {
        val fields = linkedMapOf<String, JsonValue>()
        entries.forEach { (key, value) -> if (value != null) fields[key] = value }
        return JsonValue.Obj(fields)
    }
}

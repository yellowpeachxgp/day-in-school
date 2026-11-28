package cn.edu.haut.campus.core.plan

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.math.BigDecimal

/**
 * univ-training-plan/v1 的导入输入。
 *
 * - [JsonDocument]：符合 univ-training-plan/v1 的 JSON 文本（手工编写或工具导出）；
 * - [CsvDocument]：扁平 CSV 文本，列见 [TrainingPlanImporter.csvFormatDocumentation]；
 * - [GatewayView]：教务网关只读页面解析出的中性视图（由调用方从具体网关适配而来），
 *   导入器本身不做网络访问。
 */
sealed interface ImportInput {
    data class JsonDocument(val text: String) : ImportInput
    data class CsvDocument(val text: String) : ImportInput
    data class GatewayView(
        val programId: String,
        val title: String?,
        val requiredCreditsHint: BigDecimal?,
        val entries: List<GatewayPlanEntry>
    ) : ImportInput
}

/** 教务网关培养方案页的一条中性条目；字段缺失保持 null，导入器按保守规则归组。 */
data class GatewayPlanEntry(
    val courseCode: String?,
    val courseName: String,
    val category: String?,
    val credits: BigDecimal?,
    val suggestedTerm: String?
)

/** 一次导入的结果：解析出的方案、格式、内容摘要与人读诊断。诊断不产生异常，导入永远返回明确结论。 */
data class TrainingPlanImport(
    val plan: TrainingPlan,
    val format: String,
    val contentDigest: String,
    val warnings: List<String>,
    val skippedRowCount: Int
)

/**
 * univ-training-plan/v1 JSON 与扁平 CSV 的培养方案导入器。
 *
 * 解析规则（本地推导，UNVERIFIED，不代表任何学校的真实认定口径）：
 * 1. JSON 必须带 format="univ-training-plan/v1"，缺字段一律报错而不是猜测；
 * 2. CSV 按 group_path 用「/」分层，同路径的 kind/required_credits 必须一致，否则该行跳过并记诊断；
 * 3. 教务网关视图按课程性质归入单个默认根组下的四个性质子组，未识别性质进「其他」；
 * 4. 学分用 BigDecimal 精确解析，非法数字跳过该行并记诊断；
 * 5. 文件导入的来源标 UNKNOWN，由用户在界面确认适用后才升级为 USER_CONFIRMED；
 *    网关视图标 OFFICIAL_IMPORT，但 confirmedForStudent 仍为 false。
 */
object TrainingPlanImporter {

    const val JSON_FORMAT = "univ-training-plan/v1"

    val csvFormatDocumentation: String =
        "CSV 列：group_path,kind,required_credits,course_code,course_name,offering,prerequisites。" +
            "group_path 用 / 分层；kind 取 required|elective|practice|general；" +
            "offering 取 spring|autumn|both|unknown；prerequisites 用英文分号分隔；首行表头可选"

    fun import(input: ImportInput): TrainingPlanImport = when (input) {
        is ImportInput.JsonDocument -> importJson(input.text)
        is ImportInput.CsvDocument -> importCsv(input.text)
        is ImportInput.GatewayView -> importGateway(input)
    }

    private fun importJson(text: String): TrainingPlanImport {
        val root = Json.parse(text).asObjOrNull
            ?: throw IllegalArgumentException("univ-training-plan/v1 顶层必须是 JSON object")
        val format = root.str("format")
        require(format == JSON_FORMAT) { "不支持的培养方案格式: " + (format ?: "缺失 format 字段") }
        val planId = requiredNonBlank(root, "planId")
        val title = requiredNonBlank(root, "title")
        val version = requiredNonBlank(root, "version")
        val gradeYears = (root.arr("applicableGradeYears") ?: throw IllegalArgumentException("缺少 applicableGradeYears"))
            .mapNotNull { it.intOrNull() }.toSet()
        require(gradeYears.isNotEmpty()) { "applicableGradeYears 不能为空" }
        val majors = (root.arr("majors") ?: emptyList()).mapNotNull { it.stringOrNull() }.toSet()
        val warnings = mutableListOf<String>()
        val groups = (root.arr("groups") ?: throw IllegalArgumentException("缺少 groups"))
            .mapIndexed { index, value -> parseGroup(value, "groups[" + index + "]", warnings) }
        val substitutions = (root.arr("substitutions") ?: emptyList()).mapIndexed { index, value ->
            val obj = value.asObjOrNull ?: throw IllegalArgumentException("substitutions[" + index + "] 必须是 object")
            SubstitutionRule(
                course = parseCourseRef(obj.obj("course"), "substitutions[" + index + "].course"),
                replaces = parseCourseRef(obj.obj("replaces"), "substitutions[" + index + "].replaces"),
                note = obj.str("note")
            )
        }
        val renames = (root.arr("renames") ?: emptyList()).mapIndexed { index, value ->
            val obj = value.asObjOrNull ?: throw IllegalArgumentException("renames[" + index + "] 必须是 object")
            CourseRename(
                was = parseCourseRef(obj.obj("was"), "renames[" + index + "].was"),
                now = parseCourseRef(obj.obj("now"), "renames[" + index + "].now")
            )
        }
        val plan = TrainingPlan(
            id = planId,
            title = title,
            version = version,
            applicableGradeYears = gradeYears,
            majors = majors,
            source = PlanSource.UNKNOWN,
            confirmedForStudent = false,
            groups = groups,
            substitutions = substitutions,
            renames = renames
        )
        return TrainingPlanImport(
            plan = plan,
            format = JSON_FORMAT,
            contentDigest = Digests.sha256Hex(text),
            warnings = warnings,
            skippedRowCount = 0
        )
    }

    private fun parseGroup(value: JsonValue, path: String, warnings: MutableList<String>): RequirementGroup {
        val obj = value.asObjOrNull ?: throw IllegalArgumentException(path + " 必须是 object")
        val id = requiredNonBlank(obj, "id")
        val title = requiredNonBlank(obj, "title")
        val kind = parseKind(requiredNonBlank(obj, "kind"), path)
        val credits = parseDecimal(requiredNumericText(obj, "requiredCredits", path), path + ".requiredCredits")
        val courses = (obj.arr("courses") ?: emptyList()).mapIndexed { index, courseValue ->
            parseCourseRef(courseValue, path + ".courses[" + index + "]")
        }
        val children = (obj.arr("children") ?: emptyList()).mapIndexed { index, childValue ->
            parseGroup(childValue, path + ".children[" + index + "]", warnings)
        }
        if (courses.isNotEmpty() && children.isNotEmpty()) {
            warnings += "要求组「" + title + "」同时列有课程与子组，课程将视为本组叶子要求之外的直接归属"
        }
        return RequirementGroup(
            id = id,
            title = title,
            kind = kind,
            requiredCredits = credits,
            courses = courses,
            children = children
        )
    }

    private fun parseCourseRef(value: JsonValue?, path: String): PlanCourseRef {
        val obj = value?.asObjOrNull ?: throw IllegalArgumentException(path + " 必须是 object")
        val name = obj.str("name")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(path + " 缺少课程名称 name")
        val offering = when (obj.str("offering")?.trim()?.lowercase()) {
            null, "", "unknown" -> CourseOffering.UNKNOWN
            "spring" -> CourseOffering.SPRING_ONLY
            "autumn" -> CourseOffering.AUTUMN_ONLY
            "both" -> CourseOffering.BOTH
            else -> throw IllegalArgumentException(path + ".offering 只接受 spring|autumn|both|unknown")
        }
        val prerequisites = (obj.arr("prerequisites") ?: emptyList()).mapNotNull { it.stringOrNull() }
        return PlanCourseRef(
            code = obj.str("code")?.takeIf { it.isNotBlank() },
            name = name,
            offering = offering,
            prerequisites = prerequisites
        )
    }

    private fun importCsv(text: String): TrainingPlanImport {
        val warnings = mutableListOf<String>()
        var skipped = 0
        val rows = parseCsvRows(text)
        if (rows.isEmpty()) throw IllegalArgumentException("CSV 没有任何数据行")
        val header = rows.first().map { it.trim().lowercase() }
        val hasHeader = header.contains("group_path") && header.contains("course_name")
        val dataRows = if (hasHeader) rows.drop(1) else rows
        val columns = if (hasHeader) header else listOf(
            "group_path", "kind", "required_credits", "course_code", "course_name", "offering", "prerequisites"
        )
        dataRows.forEach { row ->
            if (row.size != columns.size) {
                skipped++
                warnings += "跳过列数不符的行（" + row.size + " 列，期望 " + columns.size + " 列）: " + row.joinToString(",").take(80)
            }
        }
        val valid = dataRows.filter { it.size == columns.size }
        if (valid.isEmpty()) throw IllegalArgumentException("CSV 没有列数正确的数据行")

        data class RowInfo(
            val path: List<String>,
            val kind: RequirementKind,
            val requiredCredits: BigDecimal,
            val course: PlanCourseRef?
        )

        val infos = valid.mapNotNull { row ->
            val byColumn = columns.zip(row).toMap()
            val rawPath = byColumn["group_path"].orEmpty().trim()
            val courseName = byColumn["course_name"].orEmpty().trim()
            if (rawPath.isEmpty() || courseName.isEmpty()) {
                skipped++
                warnings += "跳过缺少 group_path 或 course_name 的行: " + row.joinToString(",").take(80)
                return@mapNotNull null
            }
            val kindText = byColumn["kind"].orEmpty().trim().ifEmpty { "required" }
            val kind = try {
                parseKind(kindText, "csv")
            } catch (error: IllegalArgumentException) {
                skipped++
                warnings += "跳过 kind 非法的行: " + kindText
                return@mapNotNull null
            }
            val credits = try {
                parseDecimal(byColumn["required_credits"].orEmpty().trim().ifEmpty { "0" }, "csv.required_credits")
            } catch (error: IllegalArgumentException) {
                skipped++
                warnings += "跳过 required_credits 非法的行: " + row.joinToString(",").take(80)
                return@mapNotNull null
            }
            val offering = when (byColumn["offering"].orEmpty().trim().lowercase()) {
                "", "unknown" -> CourseOffering.UNKNOWN
                "spring" -> CourseOffering.SPRING_ONLY
                "autumn" -> CourseOffering.AUTUMN_ONLY
                "both" -> CourseOffering.BOTH
                else -> {
                    warnings += "行内 offering 无法识别，按 unknown 处理: " + byColumn["offering"].orEmpty()
                    CourseOffering.UNKNOWN
                }
            }
            val prerequisites = byColumn["prerequisites"].orEmpty()
                .split(';').map { it.trim() }.filter { it.isNotEmpty() }
            RowInfo(
                path = rawPath.split('/').map { it.trim() }.filter { it.isNotEmpty() },
                kind = kind,
                requiredCredits = credits,
                course = PlanCourseRef(
                    code = byColumn["course_code"].orEmpty().trim().takeIf { it.isNotEmpty() },
                    name = courseName,
                    offering = offering,
                    prerequisites = prerequisites
                )
            )
        }
        if (infos.isEmpty()) throw IllegalArgumentException("CSV 没有可用的课程行")

        // 按路径分层建树；同层同路径段的组合并，kind/requiredCredits 取该行在组内首次出现的值。
        val rootId = "csv-root"
        val rootChildren = mutableListOf<RequirementGroup>()
        val groupIndex = mutableMapOf<List<String>, RequirementGroup>()
        fun ensureGroup(path: List<String>, kind: RequirementKind, credits: BigDecimal): RequirementGroup {
            val existing = groupIndex[path]
            if (existing != null) {
                if (existing.kind != kind || existing.requiredCredits.stripTrailingZeros() != credits.stripTrailingZeros()) {
                    warnings += "路径 " + path.joinToString("/") + " 的 kind/required_credits 在多行间不一致，按首次出现值归组"
                }
                return existing
            }
            val group = RequirementGroup(
                id = "g-" + Digests.sha256Hex(path.joinToString("/")).take(12),
                title = path.last(),
                kind = kind,
                requiredCredits = credits
            )
            groupIndex[path] = group
            return group
        }
        infos.forEach { info ->
            val group = ensureGroup(info.path, info.kind, info.requiredCredits)
            if (info.course != null) {
                val updated = group.copy(courses = group.courses + info.course)
                groupIndex[info.path] = updated
            }
        }
        // 组路径长度 1 的是根组的直接子组；更长路径挂到父路径组下。
        val orderedPaths = groupIndex.keys.sortedBy { it.size }
        val childLinks = mutableMapOf<List<String>, MutableList<RequirementGroup>>()
        val topLevel = mutableListOf<RequirementGroup>()
        orderedPaths.forEach { path ->
            val group = groupIndex.getValue(path)
            if (path.size == 1) {
                topLevel += group
            } else {
                val parent = path.dropLast(1)
                if (groupIndex.containsKey(parent)) {
                    childLinks.getOrPut(parent) { mutableListOf() } += group
                } else {
                    warnings += "路径 " + path.joinToString("/") + " 缺少父组，提升为顶层组"
                    topLevel += group
                }
            }
        }
        fun assemble(path: List<String>): RequirementGroup {
            val group = groupIndex.getValue(path)
            val children = childLinks[path]?.map { assemble(path + it.title) } ?: emptyList()
            return if (children.isEmpty()) group else group.copy(children = children)
        }
        val assembledRoots = orderedPaths.filter { it.size == 1 || !groupIndex.containsKey(it.dropLast(1)) }
            .map { assemble(it) }
        rootChildren += assembledRoots

        val totalRequired = rootChildren.fold(BigDecimal.ZERO) { acc, group -> acc.add(group.requiredCredits) }
        val plan = TrainingPlan(
            id = "csv-" + Digests.sha256Hex(text).take(12),
            title = "CSV 导入的培养方案",
            version = "csv-1",
            applicableGradeYears = (1..4).toSet(),
            majors = emptySet(),
            source = PlanSource.UNKNOWN,
            confirmedForStudent = false,
            groups = listOf(
                RequirementGroup(
                    id = rootId,
                    title = "全部要求",
                    kind = RequirementKind.REQUIRED,
                    requiredCredits = totalRequired,
                    children = rootChildren
                )
            )
        )
        warnings += "CSV 导入的方案适用年级默认 1-4 且不限专业，请在界面确认或修正"
        return TrainingPlanImport(
            plan = plan,
            format = "csv-flat/v1",
            contentDigest = Digests.sha256Hex(text),
            warnings = warnings,
            skippedRowCount = skipped
        )
    }

    private fun importGateway(view: ImportInput.GatewayView): TrainingPlanImport {
        require(view.programId.isNotBlank()) { "网关视图缺少 programId" }
        val warnings = mutableListOf<String>()
        var skipped = 0
        val buckets = linkedMapOf<RequirementKind, MutableList<PlanCourseRef>>(
            RequirementKind.REQUIRED to mutableListOf(),
            RequirementKind.ELECTIVE to mutableListOf(),
            RequirementKind.PRACTICE to mutableListOf(),
            RequirementKind.GENERAL to mutableListOf()
        )
        val others = mutableListOf<PlanCourseRef>()
        view.entries.forEach { entry ->
            if (entry.courseName.isBlank()) {
                skipped++
                warnings += "跳过缺课程名称的网关条目"
                return@forEach
            }
            if (entry.credits == null) {
                warnings += "课程「" + entry.courseName + "」缺少学分，按 0 学分条目录入，缺口统计不受影响"
            }
            val ref = PlanCourseRef(
                code = entry.courseCode?.takeIf { it.isNotBlank() },
                name = entry.courseName.trim(),
                offering = offeringFromSuggestedTerm(entry.suggestedTerm)
            )
            when (categoryKind(entry.category)) {
                null -> others += ref
                else -> buckets.getValue(categoryKind(entry.category)!!) += ref
            }
        }
        val creditSum = { refs: List<PlanCourseRef> ->
            refs.fold(BigDecimal.ZERO) { acc, ref ->
                acc.add(view.entries.firstOrNull { it.courseName.trim() == ref.name }?.credits ?: BigDecimal.ZERO)
            }
        }
        val children = mutableListOf<RequirementGroup>()
        buckets.forEach { (kind, refs) ->
            if (refs.isNotEmpty()) {
                children += RequirementGroup(
                    id = "gw-" + kind.name.lowercase(),
                    title = kind.label,
                    kind = kind,
                    requiredCredits = BigDecimal.ZERO,
                    courses = refs
                )
            }
        }
        if (others.isNotEmpty()) {
            children += RequirementGroup(
                id = "gw-other",
                title = "其他（性质未识别）",
                kind = RequirementKind.ELECTIVE,
                requiredCredits = BigDecimal.ZERO,
                courses = others
            )
            warnings += "有 " + others.size + " 门课程性质未识别，归入「其他」组，需人工核对归属"
        }
        if (children.isEmpty()) throw IllegalArgumentException("网关视图没有任何可用课程条目")
        val requiredTotal = view.requiredCreditsHint ?: BigDecimal.ZERO
        if (view.requiredCreditsHint == null) {
            warnings += "网关页面未给出总学分要求，各组要求学分为 0，缺口按组开放统计，请人工补录要求学分"
        } else {
            warnings += "网关视图不区分各组要求学分，总要求 " + requiredTotal.stripTrailingZeros().toPlainString() +
                " 学分挂在根组展示；请按学校原文核对后用人工认定修正"
        }
        val plan = TrainingPlan(
            id = view.programId,
            title = view.title?.takeIf { it.isNotBlank() } ?: "网关导入的培养方案",
            version = "gateway-1",
            applicableGradeYears = (1..4).toSet(),
            majors = emptySet(),
            source = PlanSource.OFFICIAL_IMPORT,
            confirmedForStudent = false,
            groups = listOf(
                RequirementGroup(
                    id = "gw-root",
                    title = "全部要求",
                    kind = RequirementKind.REQUIRED,
                    requiredCredits = requiredTotal,
                    children = children
                )
            )
        )
        return TrainingPlanImport(
            plan = plan,
            format = "gateway-view/v1",
            contentDigest = Digests.sha256Hex(view.programId + "|" + view.entries.size + "|" + view.entries.hashCode()),
            warnings = warnings,
            skippedRowCount = skipped
        )
    }

    private fun categoryKind(category: String?): RequirementKind? {
        val text = category?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            text.contains("必修") && !text.contains("选修") -> RequirementKind.REQUIRED
            text.contains("实践") -> RequirementKind.PRACTICE
            text.contains("通识") || text.contains("公选") -> RequirementKind.GENERAL
            text.contains("选修") -> RequirementKind.ELECTIVE
            else -> null
        }
    }

    private fun offeringFromSuggestedTerm(suggestedTerm: String?): CourseOffering {
        val text = suggestedTerm?.trim()?.takeIf { it.isNotEmpty() } ?: return CourseOffering.UNKNOWN
        val number = text.filter { it.isDigit() }.toIntOrNull() ?: return CourseOffering.UNKNOWN
        return if (number % 2 == 0) CourseOffering.SPRING_ONLY else CourseOffering.AUTUMN_ONLY
    }

    private fun parseKind(text: String, path: String): RequirementKind = when (text.trim().lowercase()) {
        "required" -> RequirementKind.REQUIRED
        "elective" -> RequirementKind.ELECTIVE
        "practice" -> RequirementKind.PRACTICE
        "general" -> RequirementKind.GENERAL
        else -> throw IllegalArgumentException(path + " 的 kind 只接受 required|elective|practice|general: " + text)
    }

    private fun parseDecimal(text: String, path: String): BigDecimal = try {
        BigDecimal(text.trim())
    } catch (error: NumberFormatException) {
        throw IllegalArgumentException(path + " 不是合法数字: " + text)
    }

    private fun requiredNonBlank(obj: JsonValue.Obj, key: String): String =
        obj.str(key)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("缺少必填字段 " + key)

    /** requiredCredits 等数字字段同时接受 JSON number 与数字字符串，避免手写与编码往返不一。 */
    private fun requiredNumericText(obj: JsonValue.Obj, key: String, path: String): String {
        val raw = obj.obj(key) ?: throw IllegalArgumentException(path + " 缺少必填字段 " + key)
        return when (raw) {
            is JsonValue.Num -> raw.literal
            is JsonValue.Str -> raw.value.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException(path + "." + key + " 不能为空")
            else -> throw IllegalArgumentException(path + "." + key + " 必须是数字或数字字符串")
        }
    }

    /** 最小 CSV 解析：支持引号包裹与双引号转义，不支持内嵌换行。 */
    internal fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val current = StringBuilder()
        val row = mutableListOf<String>()
        var inQuotes = false
        var index = 0
        fun endRow() {
            row += current.toString()
            current.clear()
            if (row.any { it.isNotBlank() }) rows += row.toList()
            row.clear()
        }
        while (index < text.length) {
            val ch = text[index]
            when {
                inQuotes && ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    current.append('"'); index++
                }
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && ch == ',' -> { row += current.toString(); current.clear() }
                !inQuotes && (ch == '\u000a' || ch == '\u000d') -> {
                    if (ch == '\u000d' && index + 1 < text.length && text[index + 1] == '\u000a') index++
                    endRow()
                }
                else -> current.append(ch)
            }
            index++
        }
        if (current.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}

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
    const val CSV_MAX_INPUT_CHARS = 1_000_000
    const val CSV_MAX_ROWS = 5_000
    const val CSV_MAX_COLUMNS = 16
    const val CSV_MAX_FIELD_CHARS = 4_096
    const val CSV_MAX_GROUP_DEPTH = 8

    val csvFormatDocumentation: String =
        "CSV 列：group_path,kind,required_credits,course_code,course_name,offering,prerequisites。" +
            "group_path 用 / 分层；kind 取 required|elective|practice|general；" +
            "offering 取 spring|autumn|both|unknown；prerequisites 用英文分号分隔；首行表头可选。" +
            "硬上限：输入 1,000,000 字符、5,000 行、16 列、字段 4,096 字符、路径 8 层；引号字段必须闭合"

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
            val rawPathSegments = rawPath.split('/')
            if (rawPathSegments.size > CSV_MAX_GROUP_DEPTH) {
                throw IllegalArgumentException("CSV group_path 深度超过硬上限 " + CSV_MAX_GROUP_DEPTH + " 层")
            }
            val pathSegments = rawPathSegments.map { it.trim() }.filter { it.isNotEmpty() }
            if (rawPath.isEmpty() || courseName.isEmpty() || pathSegments.isEmpty()) {
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
                path = pathSegments,
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

        // 按路径分层建树；同路径的 kind/required_credits 必须一致。冲突行按文档拒绝：
        // 不加入课程、不参与层级物化，并计入 skippedRowCount，避免冲突数据仍能入账。
        val acceptedInfos = mutableListOf<RowInfo>()
        val groupMetadata = mutableMapOf<List<String>, Pair<RequirementKind, BigDecimal>>()
        infos.forEach { info ->
            val existing = groupMetadata[info.path]
            if (existing == null) {
                groupMetadata[info.path] = info.kind to info.requiredCredits
                acceptedInfos += info
            } else if (existing.first != info.kind ||
                existing.second.stripTrailingZeros() != info.requiredCredits.stripTrailingZeros()
            ) {
                skipped++
                warnings += "跳过冲突行：路径 " + info.path.joinToString("/") +
                    " 的 kind/required_credits 与首次声明不一致，课程「" + (info.course?.name ?: "未知") + "」未导入"
            } else {
                acceptedInfos += info
            }
        }

        val rootId = "csv-root"
        val rootChildren = mutableListOf<RequirementGroup>()
        val groupIndex = mutableMapOf<List<String>, RequirementGroup>()
        fun ensureGroup(path: List<String>, kind: RequirementKind, credits: BigDecimal): RequirementGroup {
            val existing = groupIndex[path]
            if (existing != null) return existing
            val group = RequirementGroup(
                id = "g-" + Digests.sha256Hex(path.joinToString("/")).take(12),
                title = path.last(),
                kind = kind,
                requiredCredits = credits
            )
            groupIndex[path] = group
            return group
        }
        acceptedInfos.forEach { info ->
            val group = ensureGroup(info.path, info.kind, info.requiredCredits)
            if (info.course != null) {
                val updated = group.copy(courses = group.courses + info.course)
                groupIndex[info.path] = updated
            }
        }
        // 中间路径物化：只写叶子行的 CSV 缺少显式父组时，按学分 0 的中间组补齐层级；
        // 被拒绝的冲突行不参与物化，显式声明过的路径不会被覆盖。
        val materialized = mutableSetOf<List<String>>()
        acceptedInfos.forEach { info ->
            (1 until info.path.size).forEach { len ->
                val prefix = info.path.take(len)
                if (!groupIndex.containsKey(prefix) && prefix !in materialized) {
                    materialized += prefix
                    groupIndex[prefix] = RequirementGroup(
                        id = "g-" + Digests.sha256Hex(prefix.joinToString("/")).take(12),
                        title = prefix.last(),
                        kind = info.kind,
                        requiredCredits = BigDecimal.ZERO
                    )
                    warnings += "路径 " + prefix.joinToString("/") + " 未显式声明，已按中间组自动创建（要求学分 0）"
                }
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
                " 学分作为根组汇总约束参与缺口统计；请按学校原文核对后用人工认定修正"
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
            contentDigest = gatewayContentDigest(view),
            warnings = warnings,
            skippedRowCount = skipped
        )
    }

    private fun categoryKind(category: String?): RequirementKind? {
        val text = category?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            // 「实践必修」「通识必修」必须先命中更具体的 PRACTICE/GENERAL，
            // 否则会被宽泛的“必修”文本错误吞进 REQUIRED。
            text.contains("实践") -> RequirementKind.PRACTICE
            text.contains("通识") || text.contains("公选") -> RequirementKind.GENERAL
            text.contains("必修") && !text.contains("选修") -> RequirementKind.REQUIRED
            text.contains("选修") -> RequirementKind.ELECTIVE
            else -> null
        }
    }

    /** 网关视图摘要必须覆盖标题、总要求与逐条课程字段；使用 SHA-256/canonical JSON，禁止依赖 hashCode。 */
    private fun gatewayContentDigest(view: ImportInput.GatewayView): String {
        fun nullableText(value: String?): JsonValue = value?.let { JsonValue.Str(it) } ?: JsonValue.Null
        fun nullableCredits(value: BigDecimal?): JsonValue = value?.let { JsonValue.Str(it.stripTrailingZeros().toPlainString()) } ?: JsonValue.Null
        val entryValues = view.entries.map { entry ->
            JsonValue.Obj(linkedMapOf(
                "courseCode" to nullableText(entry.courseCode),
                "courseName" to JsonValue.Str(entry.courseName),
                "category" to nullableText(entry.category),
                "credits" to nullableCredits(entry.credits),
                "suggestedTerm" to nullableText(entry.suggestedTerm)
            ))
        }
        val digestObject = JsonValue.Obj(linkedMapOf(
            "programId" to JsonValue.Str(view.programId),
            "title" to nullableText(view.title),
            "requiredCreditsHint" to nullableCredits(view.requiredCreditsHint),
            "entries" to JsonValue.Arr(entryValues)
        ))
        return Digests.sha256Hex(Json.canonical(digestObject))
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

    /**
     * 最小 CSV 解析：支持引号包裹与双引号转义，不支持内嵌换行。
     * 尺寸、行列、字段长度与引号闭合全部是硬上限/硬校验，防止粘贴大文本时无界消耗内存。
     */
    internal fun parseCsvRows(text: String): List<List<String>> {
        require(text.length <= CSV_MAX_INPUT_CHARS) {
            "CSV 输入超过大小硬上限 " + CSV_MAX_INPUT_CHARS + " 字符"
        }
        val rows = mutableListOf<List<String>>()
        val current = StringBuilder()
        val row = mutableListOf<String>()
        var inQuotes = false
        var index = 0

        fun appendFieldChar(ch: Char) {
            if (current.length >= CSV_MAX_FIELD_CHARS) {
                throw IllegalArgumentException("CSV 字段超过硬上限 " + CSV_MAX_FIELD_CHARS + " 字符")
            }
            current.append(ch)
        }

        fun endField() {
            if (row.size >= CSV_MAX_COLUMNS) {
                throw IllegalArgumentException("CSV 行列数超过硬上限 " + CSV_MAX_COLUMNS + " 列")
            }
            row += current.toString()
            current.clear()
        }

        fun endRow() {
            endField()
            if (row.any { it.isNotBlank() }) {
                rows += row.toList()
                if (rows.size > CSV_MAX_ROWS) {
                    throw IllegalArgumentException("CSV 行数超过硬上限 " + CSV_MAX_ROWS + " 行")
                }
            }
            row.clear()
        }

        while (index < text.length) {
            val ch = text[index]
            when {
                inQuotes && ch == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                    appendFieldChar('"')
                    index++
                }
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && ch == ',' -> endField()
                !inQuotes && (ch == '\u000a' || ch == '\u000d') -> {
                    if (ch == '\u000d' && index + 1 < text.length && text[index + 1] == '\u000a') index++
                    endRow()
                }
                inQuotes && (ch == '\u000a' || ch == '\u000d') ->
                    throw IllegalArgumentException("CSV 不支持引号字段内嵌换行")
                else -> appendFieldChar(ch)
            }
            index++
        }
        if (inQuotes) throw IllegalArgumentException("CSV 引号字段未闭合")
        if (current.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}

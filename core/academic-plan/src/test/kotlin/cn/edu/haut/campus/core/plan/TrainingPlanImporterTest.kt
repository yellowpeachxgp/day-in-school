package cn.edu.haut.campus.core.plan

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * univ-training-plan/v1 JSON、扁平 CSV 与教务网关视图的导入测试。
 * 全部为合成数据（UNVERIFIED），不代表任何真实学校。
 */
class TrainingPlanImporterTest {

    private fun sampleJson(): String = """
        {
          "format": "univ-training-plan/v1",
          "planId": "demo-cs-2023",
          "title": "计算机示范方案",
          "version": "2023.1",
          "applicableGradeYears": [1, 2, 3, 4],
          "majors": ["计算机科学与技术"],
          "groups": [
            {
              "id": "basic", "title": "学科基础", "kind": "required", "requiredCredits": 10,
              "courses": [
                {"code": "MATH1", "name": "高等数学A", "offering": "autumn"},
                {"code": "CS101", "name": "程序设计基础", "offering": "spring", "prerequisites": ["高等数学A"]}
              ]
            },
            {
              "id": "elective", "title": "专业选修", "kind": "elective", "requiredCredits": "6",
              "courses": [{"code": "CS201", "name": "数据结构"}],
              "children": [
                {"id": "elective-ai", "title": "人工智能方向", "kind": "elective", "requiredCredits": 4,
                 "courses": [{"code": "CS301", "name": "机器学习", "offering": "spring"}]}
              ]
            }
          ],
          "substitutions": [
            {"course": {"code": "CS202", "name": "数据结构与算法"}, "replaces": {"code": "CS201", "name": "数据结构"}, "note": "新版课程替代"}
          ],
          "renames": [
            {"was": {"name": "思想道德修养"}, "now": {"name": "思想道德与法治"}}
          ]
        }
    """.trimIndent()

    @Test
    fun jsonImportParsesGroupsCoursesSubstitutionsAndRenames() {
        val result = TrainingPlanImporter.import(ImportInput.JsonDocument(sampleJson()))
        val plan = result.plan
        assertEquals("demo-cs-2023", plan.id)
        assertEquals("计算机示范方案", plan.title)
        assertEquals(setOf(1, 2, 3, 4), plan.applicableGradeYears)
        assertEquals(setOf("计算机科学与技术"), plan.majors)
        assertEquals(PlanSource.UNKNOWN, plan.source)
        assertFalse(plan.confirmedForStudent)
        assertEquals(1, plan.substitutions.size)
        assertEquals("数据结构与算法", plan.substitutions.first().course.name)
        assertEquals(1, plan.renames.size)
        assertEquals("思想道德与法治", plan.renames.first().now.name)
        val basic = plan.groupById("basic")
        assertNotNull(basic)
        assertEquals(2, basic!!.courses.size)
        assertEquals(CourseOffering.SPRING_ONLY, basic.courses[1].offering)
        assertEquals(listOf("高等数学A"), basic.courses[1].prerequisites)
        val ai = plan.groupById("elective-ai")
        assertNotNull(ai)
        assertTrue(ai!!.isLeaf)
        assertEquals("univ-training-plan/v1", result.format)
        assertTrue(result.contentDigest.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun jsonImportRejectsWrongFormatAndMissingFields() {
        val wrongFormat = sampleJson().replace("univ-training-plan/v1", "univ-training-plan/v2")
        try {
            TrainingPlanImporter.import(ImportInput.JsonDocument(wrongFormat))
            throw AssertionError("应当拒绝未知格式")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("格式"))
        }
        val missingTitle = sampleJson().replace("\"title\": \"计算机示范方案\",", "")
        try {
            TrainingPlanImporter.import(ImportInput.JsonDocument(missingTitle))
            throw AssertionError("应当拒绝缺字段")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("title"))
        }
    }

    @Test
    fun codecRoundtripPreservesPlanStructure() {
        val imported = TrainingPlanImporter.import(ImportInput.JsonDocument(sampleJson())).plan
        val canonical = TrainingPlanCodec.toCanonicalText(imported)
        val restored = TrainingPlanCodec.fromJson(canonical)
        assertEquals(imported.id, restored.id)
        assertEquals(imported.allGroups().map { it.id }, restored.allGroups().map { it.id })
        assertEquals(imported.substitutions, restored.substitutions)
        assertEquals(imported.renames, restored.renames)
        val basicOrig = imported.groupById("basic")!!
        val basicRestored = restored.groupById("basic")!!
        assertEquals(basicOrig.courses, basicRestored.courses)
        assertEquals(canonical, TrainingPlanCodec.toCanonicalText(restored))
    }

    @Test
    fun csvImportBuildsTreeAndSkipsInvalidRows() {
        val csv = """
            group_path,kind,required_credits,course_code,course_name,offering,prerequisites
            学科基础,required,10,MATH1,高等数学A,autumn,
            学科基础,required,10,CS101,程序设计基础,spring,高等数学A
            专业选修/人工智能,elective,4,CS301,机器学习,spring,
            bad-row,onlytwo
            ,required,1,X,缺路径,,
        """.trimIndent()
        val result = TrainingPlanImporter.import(ImportInput.CsvDocument(csv))
        assertEquals(2, result.skippedRowCount)
        val plan = result.plan
        val basic = plan.allGroups().firstOrNull { it.title == "学科基础" }
        assertNotNull(basic)
        assertEquals(2, basic!!.courses.size)
        assertEquals(CourseOffering.SPRING_ONLY, basic.courses[1].offering)
        val ai = plan.allGroups().firstOrNull { it.title == "人工智能" }
        assertNotNull(ai)
        assertEquals("机器学习", ai!!.courses.first().name)
        val elective = plan.allGroups().firstOrNull { it.title == "专业选修" }
        assertNotNull(elective)
        assertTrue(elective!!.children.any { it.title == "人工智能" })
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun csvImportAcceptsRowsWithoutHeader() {
        val csv = """
            学科基础,required,10,MATH1,高等数学A,,
            学科基础,required,10,CS101,程序设计基础,,
        """.trimIndent()
        val result = TrainingPlanImporter.import(ImportInput.CsvDocument(csv))
        val basic = result.plan.allGroups().firstOrNull { it.title == "学科基础" }
        assertNotNull(basic)
        assertEquals(2, basic!!.courses.size)
    }

    @Test
    fun gatewayViewImportGroupsByCategoryAndFlagsMissingCredits() {
        val view = ImportInput.GatewayView(
            programId = "gw-demo",
            title = "网关示范方案",
            requiredCreditsHint = BigDecimal("140"),
            entries = listOf(
                GatewayPlanEntry("MATH1", "高等数学A", "学科必修", BigDecimal("5"), "1"),
                GatewayPlanEntry("CS101", "程序设计基础", "专业必修", BigDecimal("4"), "2"),
                GatewayPlanEntry("PE101", "大学体育", null, BigDecimal("1"), "1"),
                GatewayPlanEntry("CS999", "无学分课", "专业选修", null, null)
            )
        )
        val result = TrainingPlanImporter.import(view)
        val plan = result.plan
        assertEquals(PlanSource.OFFICIAL_IMPORT, plan.source)
        assertFalse(plan.confirmedForStudent)
        val required = plan.groupById("gw-required")
        assertNotNull(required)
        assertTrue(required!!.courses.any { it.name == "高等数学A" })
        val other = plan.groupById("gw-other")
        assertNotNull(other)
        assertTrue(other!!.courses.any { it.name == "大学体育" })
        assertTrue(result.warnings.any { it.contains("未识别") || it.contains("其他") })
        assertTrue(result.warnings.any { it.contains("无学分课") })
        assertEquals(CourseOffering.AUTUMN_ONLY, required!!.courses.first { it.name == "高等数学A" }.offering)
        assertEquals(CourseOffering.SPRING_ONLY, required.courses.first { it.name == "程序设计基础" }.offering)
    }
}

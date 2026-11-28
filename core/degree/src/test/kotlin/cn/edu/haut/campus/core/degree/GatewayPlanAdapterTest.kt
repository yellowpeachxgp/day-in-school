package cn.edu.haut.campus.core.degree

import cn.edu.haut.campus.core.protocol.eams.EamsTrainingPlan
import cn.edu.haut.campus.core.protocol.eams.EamsTrainingPlanEntry
import cn.edu.haut.campus.core.plan.TrainingPlanImporter
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 教务网关视图适配测试：字段逐条映射，缺失保持 null。合成数据（UNVERIFIED）。 */
class GatewayPlanAdapterTest {

    @Test
    fun eamsViewMapsToImporterInput() {
        val plan = EamsTrainingPlan(
            programId = "eams-demo",
            title = "EAMS 示范方案",
            requiredCredits = BigDecimal("140"),
            entries = listOf(
                EamsTrainingPlanEntry("MATH1", "高等数学A", "学科必修", BigDecimal("5"), "1"),
                EamsTrainingPlanEntry(null, "大学体育", null, null, null)
            )
        )
        val input = plan.toGatewayViewInput()
        assertEquals("eams-demo", input.programId)
        assertEquals("EAMS 示范方案", input.title)
        assertEquals(0, BigDecimal("140").compareTo(input.requiredCreditsHint!!))
        assertEquals(2, input.entries.size)
        assertEquals("高等数学A", input.entries.first().courseName)
        assertEquals(null, input.entries[1].courseCode)

        // 端到端：适配结果可直接导入
        val result = TrainingPlanImporter.import(input)
        assertEquals("eams-demo", result.plan.id)
        val required = result.plan.groupById("gw-required")
        assertNotNull(required)
        assertTrue(result.plan.groupById("gw-other") != null)
    }
}

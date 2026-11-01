package cn.edu.haut.campus.core.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 可信级别与计算依据测试。
 *
 * UNVERIFIED：本测试只验证模块内部的标注规则，不代表任何学校的数据可信度结论。
 */
class ProvenanceValueTest {

    @Test
    fun weakestLevelWins() {
        assertEquals(
            "UNVERIFIED：只要有一环是估算，整体就不能是官方",
            Provenance.ESTIMATED,
            Provenance.weakest(listOf(Provenance.OFFICIAL, Provenance.ESTIMATED, Provenance.DERIVED))
        )
        assertEquals(
            "UNVERIFIED：官方与推导混合取推导",
            Provenance.DERIVED,
            Provenance.weakest(listOf(Provenance.OFFICIAL, Provenance.DERIVED))
        )
    }

    @Test
    fun emptyLevelsAreTreatedAsEstimated() {
        assertEquals("UNVERIFIED：没有证据时按最保守的估算处理", Provenance.ESTIMATED, Provenance.weakest(emptyList()))
    }

    @Test
    fun provenValueRequiresBasis() {
        var thrown: IllegalArgumentException? = null
        try {
            ProvenValue(BigDecimalValueHolder.one, Provenance.DERIVED, "  ")
        } catch (error: IllegalArgumentException) {
            thrown = error
        }
        assertTrue("UNVERIFIED：没有计算依据的值不允许构造", thrown != null)
    }

    private object BigDecimalValueHolder {
        val one = java.math.BigDecimal.ONE
    }
}

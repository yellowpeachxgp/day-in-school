package cn.edu.haut.campus.core.selection

import cn.edu.haut.campus.core.model.SnapshotCompleteness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNVERIFIED：官方回读内容是合成的，只验证比对规则，
 * 不代表任何真实学校的选课回读格式已经验证通过。
 */
class SelectionReadbackUnverifiedTest {

    private val scope = SelectionFixtures.scope()
    private val expectedEnroll = ExpectedSelection("off-1", "class-1", SelectionAction.ENROLL)
    private val expectedDrop = ExpectedSelection("off-1", "class-1", SelectionAction.DROP)

    @Test
    fun `选课已生效时回读一致`() {
        val comparison = SelectionReadback.compare(
            expectedEnroll,
            readbackOf(scope, AcceptedClass("off-1", "class-1", "高等数学（合成）"))
        )
        assertTrue(comparison.conclusive)
        assertTrue(comparison.matched)
        assertTrue(comparison.differences.isEmpty())
        assertNull(comparison.note)
        assertEquals("class-1", comparison.actual!!.teachingClassId)
    }

    @Test
    fun `选课未生效时报告缺失差异`() {
        val comparison = SelectionReadback.compare(expectedEnroll, readbackOf(scope))
        assertTrue(comparison.conclusive)
        assertFalse(comparison.matched)
        val difference = comparison.differences.single()
        assertEquals(ReadbackDifferenceKind.MISSING_IN_OFFICIAL, difference.kind)
        assertEquals("off-1", difference.offeringId)
        assertEquals("class-1", difference.expectedTeachingClassId)
        assertNull(difference.actualTeachingClassId)
        assertTrue(difference.message.contains("没有生效"))
    }

    @Test
    fun `官方换成另一个教学班时报告教学班不一致`() {
        val comparison = SelectionReadback.compare(
            expectedEnroll,
            readbackOf(scope, AcceptedClass("off-1", "class-2", "高等数学（合成）"))
        )
        assertFalse(comparison.matched)
        val difference = comparison.differences.single()
        assertEquals(ReadbackDifferenceKind.CLASS_MISMATCH, difference.kind)
        assertEquals("class-1", difference.expectedTeachingClassId)
        assertEquals("class-2", difference.actualTeachingClassId)
        assertTrue(difference.message.contains("class-2"))
    }

    @Test
    fun `退课生效时回读一致`() {
        val comparison = SelectionReadback.compare(expectedDrop, readbackOf(scope))
        assertTrue(comparison.conclusive)
        assertTrue(comparison.matched)
        assertNull(comparison.actual)
    }

    @Test
    fun `退课未生效时报告仍然已选`() {
        val comparison = SelectionReadback.compare(
            expectedDrop,
            readbackOf(scope, AcceptedClass("off-1", "class-1", "高等数学（合成）"))
        )
        assertFalse(comparison.matched)
        val difference = comparison.differences.single()
        assertEquals(ReadbackDifferenceKind.STILL_ENROLLED, difference.kind)
        assertTrue(difference.message.contains("没有生效"))
    }

    @Test
    fun `回读不完整时缺失不能当作失败证据`() {
        val comparison = SelectionReadback.compare(
            expectedEnroll,
            readbackOf(scope, completeness = SnapshotCompleteness.PARTIAL)
        )
        assertFalse("不完整回读不得给出结论", comparison.conclusive)
        assertFalse(comparison.matched)
        assertTrue("差异列表为空代表‘无法判定’而不是‘失败’", comparison.differences.isEmpty())
        assertTrue(comparison.note!!.contains("回读不完整"))
        assertTrue(comparison.note!!.contains("PARTIAL"))
    }

    @Test
    fun `回读不完整但明确出现另一教学班时仍报告差异`() {
        val comparison = SelectionReadback.compare(
            expectedEnroll,
            readbackOf(
                scope,
                AcceptedClass("off-1", "class-2", "高等数学（合成）"),
                completeness = SnapshotCompleteness.UNKNOWN
            )
        )
        assertFalse(comparison.conclusive)
        assertFalse(comparison.matched)
        assertEquals(ReadbackDifferenceKind.CLASS_MISMATCH, comparison.differences.single().kind)
    }

    @Test
    fun `权威空快照可以作为选课未生效的证据`() {
        val comparison = SelectionReadback.compare(
            expectedEnroll,
            readbackOf(scope, completeness = SnapshotCompleteness.EMPTY_CONFIRMED)
        )
        assertTrue(comparison.conclusive)
        assertFalse(comparison.matched)
        assertEquals(ReadbackDifferenceKind.MISSING_IN_OFFICIAL, comparison.differences.single().kind)
    }

    @Test
    fun `账号里的其它课程不算本次写入的差异`() {
        val comparison = SelectionReadback.compare(
            expectedEnroll,
            readbackOf(
                scope,
                AcceptedClass("off-1", "class-1", "高等数学（合成）"),
                AcceptedClass("off-9", "class-9", "体育（合成）")
            )
        )
        assertTrue("无关课程不得误报差异", comparison.matched)
    }

    @Test
    fun `批量比对保持输入顺序`() {
        val comparisons = SelectionReadback.compare(
            listOf(expectedEnroll, ExpectedSelection("off-2", "class-2", SelectionAction.ENROLL)),
            readbackOf(scope, AcceptedClass("off-1", "class-1", "高等数学（合成）"))
        )
        assertEquals(2, comparisons.size)
        assertEquals("off-1", comparisons[0].expected.offeringId)
        assertTrue(comparisons[0].matched)
        assertFalse(comparisons[1].matched)
        assertEquals(ReadbackDifferenceKind.MISSING_IN_OFFICIAL, comparisons[1].differences.single().kind)
    }

    @Test
    fun `同一课程出现两个已选教学班被拒绝`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            AcceptedSelectionSnapshot(
                scope,
                listOf(
                    AcceptedClass("off-1", "class-1", "高等数学（合成）"),
                    AcceptedClass("off-1", "class-2", "高等数学（合成）")
                ),
                SnapshotCompleteness.COMPLETE
            )
        }
        assertTrue(error.message!!.contains("多个已选教学班"))
    }

    @Test
    fun `回读快照按课程查找教学班`() {
        val snapshot = readbackOf(scope, AcceptedClass("off-1", "class-1", "高等数学（合成）"))
        assertEquals("class-1", snapshot.findClass("off-1")!!.teachingClassId)
        assertNull(snapshot.findClass("off-x"))
        assertSame(snapshot.classes.single(), snapshot.findClass("off-1"))
    }
}

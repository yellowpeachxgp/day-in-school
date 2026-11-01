package cn.edu.haut.campus.core.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNVERIFIED：课程、教学班与账号均为合成数据；本节验证草稿的幂等性与作用域隔离，
 * 不涉及任何真实学校的选课数据。
 */
class SelectionDraftIsolationUnverifiedTest {

    private val scope = SelectionFixtures.scope()

    private fun entry(
        offeringId: String = "off-1",
        classId: String = "class-1",
        action: SelectionAction = SelectionAction.ENROLL
    ): DraftEntry = DraftEntry(
        offeringId = offeringId,
        courseName = "课程 " + offeringId + "（合成）",
        teachingClassId = classId,
        action = action,
        slots = listOf(ClassSlot(1, 1, 2, TeachingWeeks.range(1, 16), "A101"))
    )

    @Test
    fun `重复添加同一条目是幂等的`() {
        val empty = SelectionDraft.empty(scope)
        assertEquals(0L, empty.revision)
        assertTrue(empty.isEmpty)

        val first = empty.add(entry())
        assertTrue(first.changed)
        assertEquals(1L, first.draft.revision)

        val second = first.draft.add(entry())
        assertFalse("重复添加同一条目不得产生新版本", second.changed)
        assertEquals(1L, second.draft.revision)
        assertSame("幂等时必须返回同一个草稿实例", first.draft, second.draft)
        assertEquals(1, second.draft.entries.size)
    }

    @Test
    fun `同一课程更换教学班视为修改而不是新增`() {
        val draft = SelectionDraft.empty(scope).add(entry()).draft
        val changed = draft.add(entry(classId = "class-2"))
        assertTrue(changed.changed)
        assertEquals(2L, changed.draft.revision)
        assertEquals("同一门课程只能有一个草稿条目", 1, changed.draft.entries.size)
        assertEquals("class-2", changed.draft.entry("off-1")!!.teachingClassId)
    }

    @Test
    fun `删除不存在的课程不改变版本`() {
        val draft = SelectionDraft.empty(scope).add(entry()).draft
        val removed = draft.remove("off-missing")
        assertFalse(removed.changed)
        assertEquals(1L, removed.draft.revision)
    }

    @Test
    fun `删除后版本递增且条目消失`() {
        val draft = SelectionDraft.empty(scope).add(entry()).draft
        val removed = draft.remove("off-1")
        assertTrue(removed.changed)
        assertEquals(2L, removed.draft.revision)
        assertNull(removed.draft.entry("off-1"))
        assertTrue(removed.draft.isEmpty)
    }

    @Test
    fun `清空空草稿是幂等的`() {
        val empty = SelectionDraft.empty(scope)
        assertFalse(empty.clear().changed)
        val withEntry = SelectionDraft.empty(scope).add(entry()).draft
        val cleared = withEntry.clear()
        assertTrue(cleared.changed)
        assertEquals(2L, cleared.draft.revision)
        assertTrue(cleared.draft.isEmpty)
    }

    @Test
    fun `保存后可以按作用域读回`() {
        val store = InMemorySelectionDraftStore()
        val draft = SelectionDraft.empty(scope).add(entry()).draft
        store.save(draft)
        assertEquals(draft, store.load(scope))
        store.save(draft)
        assertEquals("重复保存同一草稿必须是幂等的", draft, store.load(scope))
    }

    @Test
    fun `旧版本草稿不能覆盖新版本`() {
        val store = InMemorySelectionDraftStore()
        val old = SelectionDraft.empty(scope).add(entry()).draft
        val new = old.add(entry(offeringId = "off-2")).draft
        store.save(new)
        val error = assertThrows(IllegalArgumentException::class.java) { store.save(old) }
        assertTrue(error.message!!.contains("旧版本"))
        assertEquals(new, store.load(scope))
    }

    @Test
    fun `同一版本号但内容不一致被拒绝`() {
        val store = InMemorySelectionDraftStore()
        val saved = SelectionDraft.empty(scope).add(entry()).draft
        store.save(saved)
        val forged = saved.copy(entries = listOf(entry(classId = "class-9")))
        val error = assertThrows(IllegalArgumentException::class.java) { store.save(forged) }
        assertTrue(error.message!!.contains("版本号"))
    }

    @Test
    fun `不同账号与学期的草稿互不影响`() {
        val store = InMemorySelectionDraftStore()
        val accountA = SelectionFixtures.scope()
        val accountB = SelectionFixtures.scope(account = SelectionFixtures.OTHER_ACCOUNT)
        val otherTerm = SelectionFixtures.scope(termId = SelectionFixtures.OTHER_TERM)

        store.save(SelectionDraft.empty(accountA).add(entry(offeringId = "off-1")).draft)
        store.save(SelectionDraft.empty(accountB).add(entry(offeringId = "off-2")).draft)
        store.save(SelectionDraft.empty(otherTerm).add(entry(offeringId = "off-3")).draft)

        assertEquals("off-1", store.load(accountA).entries.single().offeringId)
        assertEquals("off-2", store.load(accountB).entries.single().offeringId)
        assertEquals("off-3", store.load(otherTerm).entries.single().offeringId)
        assertEquals(1L, store.load(accountA).revision)
        assertEquals(3, store.scopeCount())
        assertTrue("未写入的作用域必须是空草稿", store.load(SelectionFixtures.scope(account = SelectionFixtures.OTHER_ACCOUNT, termId = SelectionFixtures.OTHER_TERM)).isEmpty)
    }

    @Test
    fun `草稿增删改不触发任何网络写`() {
        val port = SelectionWritePort { throw AssertionError("草稿不得触发写端口（UNVERIFIED 合成断言）") }
        val ledger = SelectionIdempotencyLedger()
        SelectionSubmissionCoordinator(port, ledger) { SelectionFixtures.NOW }

        val store = InMemorySelectionDraftStore()
        val draft = SelectionDraft.empty(scope).add(entry()).draft
        val updated = draft.add(entry(classId = "class-2")).draft
        store.save(updated)
        val removed = updated.remove("off-1").draft
        store.save(removed)

        assertTrue(removed.isEmpty)
        assertEquals("草稿流程不应占用任何幂等键", 0, ledger.size())
    }

    @Test
    fun `草稿条目可投影为冲突分析占用时段`() {
        val draft = SelectionDraft.empty(scope).add(entry()).draft
        val occupied = draft.occupiedSlots()
        val slot = occupied.single()
        assertEquals(OccupiedSource.DRAFT, slot.source)
        assertEquals(SelectionFixtures.TERM, slot.termId)
        assertEquals("课程 off-1（合成）", slot.courseName)
        assertEquals("class-1", slot.teachingClassId)
        assertEquals(1, slot.slot.weekday)
        assertEquals(TeachingWeeks.range(1, 16), slot.slot.weeks)
    }

    @Test
    fun `重复课程条目被拒绝`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SelectionDraft(scope, 1, listOf(entry(), entry(classId = "class-2")))
        }
        assertTrue(error.message!!.contains("重复"))
    }

    @Test
    fun `草稿版本号不能为负数`() {
        assertThrows(IllegalArgumentException::class.java) { SelectionDraft(scope, -1, emptyList()) }
    }
}

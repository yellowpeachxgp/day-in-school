package cn.edu.haut.campus.core.selection

/**
 * 草稿条目。草稿只记录用户意图，不代表教务已受理；
 * 任何提交都必须显式经过确认快照与幂等键，草稿本身不触发网络写。
 */
data class DraftEntry(
    val offeringId: String,
    val courseName: String,
    val teachingClassId: String,
    val action: SelectionAction = SelectionAction.ENROLL,
    val slots: List<ClassSlot> = emptyList()
) {

    init {
        require(offeringId.isNotBlank() && courseName.isNotBlank() && teachingClassId.isNotBlank()) {
            "草稿条目标识不完整"
        }
    }
}

/** 草稿变更结果：内容未变时不产生新版本，便于调用方做幂等写入与 UI 去抖。 */
data class DraftChange(val draft: SelectionDraft, val changed: Boolean)

/**
 * 某个作用域内的选课草稿（不可变值对象）。
 *
 * 同一门课程最多出现一次：再次 [add] 同一门课程视为“修改教学班/动作”，
 * 而不是堆叠出两个互相矛盾的条目。
 */
data class SelectionDraft(
    val scope: SelectionScope,
    val revision: Long,
    val entries: List<DraftEntry>
) {

    init {
        require(revision >= 0) { "草稿版本号不能为负数" }
        require(entries.map { it.offeringId }.distinct().size == entries.size) { "同一门课程不能重复出现在草稿中" }
    }

    val isEmpty: Boolean get() = entries.isEmpty()

    fun entry(offeringId: String): DraftEntry? = entries.firstOrNull { it.offeringId == offeringId }

    /** 新增或修改：内容一致时不产生新版本（幂等）。 */
    fun add(entry: DraftEntry): DraftChange {
        val existing = entry(entry.offeringId)
        if (existing == entry) return DraftChange(this, false)
        val updated = if (existing == null) {
            entries + entry
        } else {
            entries.map { if (it.offeringId == entry.offeringId) entry else it }
        }
        return DraftChange(copy(revision = revision + 1, entries = updated), true)
    }

    /** 删除：条目本就不存在时不产生新版本（幂等）。 */
    fun remove(offeringId: String): DraftChange {
        if (entries.none { it.offeringId == offeringId }) return DraftChange(this, false)
        return DraftChange(copy(revision = revision + 1, entries = entries.filterNot { it.offeringId == offeringId }), true)
    }

    /** 清空：已经为空时不产生新版本（幂等）。 */
    fun clear(): DraftChange {
        if (entries.isEmpty()) return DraftChange(this, false)
        return DraftChange(copy(revision = revision + 1, entries = emptyList()), true)
    }

    /** 把草稿条目投影成冲突分析用的占用时段，来源固定为 [OccupiedSource.DRAFT]。 */
    fun occupiedSlots(): List<OccupiedSlot> = entries.flatMap { entry ->
        entry.slots.map { slot ->
            OccupiedSlot(
                source = OccupiedSource.DRAFT,
                termId = scope.termId,
                courseName = entry.courseName,
                slot = slot,
                teachingClassId = entry.teachingClassId
            )
        }
    }

    companion object {

        fun empty(scope: SelectionScope): SelectionDraft = SelectionDraft(scope, 0, emptyList())
    }
}

/**
 * 草稿持久化边界。实现方只能写本地存储，不得在这里发起任何教务请求；
 * 作用域隔离由 [SelectionScope] 作为键保证，不同账号/学期互不影响。
 */
interface SelectionDraftStore {
    fun load(scope: SelectionScope): SelectionDraft
    fun save(draft: SelectionDraft)
}

/**
 * 内存实现：按作用域分别保存草稿，拒绝用旧版本覆盖新版本，
 * 防止并行编辑或多进程场景把用户的后续修改冲掉。
 */
class InMemorySelectionDraftStore : SelectionDraftStore {

    private val drafts = linkedMapOf<SelectionScope, SelectionDraft>()

    override fun load(scope: SelectionScope): SelectionDraft = drafts[scope] ?: SelectionDraft.empty(scope)

    override fun save(draft: SelectionDraft) {
        val existing = drafts[draft.scope]
        if (existing != null) {
            require(draft.revision >= existing.revision) { "拒绝用旧版本草稿覆盖新版本" }
            require(draft.revision > existing.revision || draft.entries == existing.entries) {
                "同一版本号的草稿内容不一致，拒绝覆盖"
            }
        }
        drafts[draft.scope] = draft
    }

    /** 已保存的作用域数量，便于诊断与测试断言，不暴露草稿内容。 */
    fun scopeCount(): Int = drafts.size
}

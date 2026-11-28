package cn.edu.haut.campus.core.inbox

import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * ProposedAction 的稳定语义指纹。
 *
 * 指纹只包含影响应用结果的字段；confidence 与 sourceSpans 属于证据展示，不参与身份。
 * scope 总是进入指纹。CreateTask 自身没有 scope 字段，因此必须由 Scoped/InMemory 实现把
 * 当前 Profile scope 绑定进来；跨 Profile 即使标题和截止完全相同也不会复用去重结果。
 */
object NoticeFingerprintFactory {
    const val VERSION: String = "notice-fp-v1"

    fun fingerprint(scope: SchoolAccountScope, action: ProposedAction): String {
        validateActionScope(scope, action)
        val out = StringBuilder(512)
        appendField(out, "version", VERSION)
        appendScope(out, scope)
        when (action) {
            is ProposedAction.CreateEvent -> appendCreateEvent(out, action)
            is ProposedAction.CreateTask -> appendCreateTask(out, action)
            is ProposedAction.ModifyCourseOccurrence -> appendModifyCourseOccurrence(out, action)
            is ProposedAction.UpdateExisting -> appendUpdateExisting(out, action)
        }
        return Digests.sha256Hex(out.toString())
    }

    private fun appendCreateEvent(out: StringBuilder, action: ProposedAction.CreateEvent) {
        val draft = action.draft
        appendField(out, "action", "create-event")
        appendField(out, "event.kind", draft.kind.name)
        appendField(out, "event.title", normalizeText(draft.title))
        appendField(out, "event.note", draft.note?.let(::normalizeText))
        appendField(out, "event.start", draft.start.toString())
        appendField(out, "event.end", draft.end?.toString())
        appendField(out, "event.timeSemantics", draft.timeSemantics.name)
        appendField(out, "event.zone", draft.zone.id)
        appendField(out, "event.placeText", draft.placeText?.let(::normalizeText))
        appendField(out, "event.place", draft.place?.let { place -> place.id })
        appendField(out, "event.status", draft.status.name)
        appendField(out, "event.busy", draft.busy.name)
        appendField(out, "event.termId", draft.termId)
        appendField(out, "event.links", draft.links.map(::entityRefToken).sorted().joinToString("|"))
    }

    private fun appendCreateTask(out: StringBuilder, action: ProposedAction.CreateTask) {
        appendField(out, "action", "create-task")
        appendField(out, "task.title", normalizeText(action.title))
        appendField(out, "task.due", action.due?.toString())
        appendField(out, "task.checklist", action.checklist.map(::normalizeText).joinToString("|"))
    }

    private fun appendModifyCourseOccurrence(out: StringBuilder, action: ProposedAction.ModifyCourseOccurrence) {
        appendField(out, "action", "modify-course-occurrence")
        appendField(out, "course", entityRefToken(action.courseRef))
        appendField(out, "date", action.date.toString())
        when (val change = action.change) {
            is OccurrenceChange.Cancel -> {
                appendField(out, "change", "cancel")
                appendField(out, "cancel.reason", change.reason?.let(::normalizeText))
            }
            is OccurrenceChange.Reschedule -> {
                appendField(out, "change", "reschedule")
                appendField(out, "reschedule.targetDate", change.targetDate.toString())
                appendField(out, "reschedule.startTime", change.startTime?.toString())
                appendField(out, "reschedule.endTime", change.endTime?.toString())
                appendField(out, "reschedule.startPeriod", change.startPeriod?.toString())
                appendField(out, "reschedule.endPeriod", change.endPeriod?.toString())
                appendField(out, "reschedule.room", change.room?.let(::normalizeToken))
            }
            is OccurrenceChange.ChangeRoom -> {
                appendField(out, "change", "change-room")
                appendField(out, "room", normalizeToken(change.room))
                appendField(out, "room.buildingId", change.buildingId)
            }
        }
    }

    private fun appendUpdateExisting(out: StringBuilder, action: ProposedAction.UpdateExisting) {
        appendField(out, "action", "update-existing")
        appendField(out, "target", entityRefToken(action.target))
        val changes = action.diff.changes.sortedBy { it.path }
        appendField(out, "diff.count", changes.size.toString())
        changes.forEachIndexed { index, change ->
            appendField(out, "diff." + index + ".path", change.path)
            appendField(out, "diff." + index + ".kind", change.kind.name)
            appendField(out, "diff." + index + ".before", canonicalJsonOrAbsent(change))
            appendField(out, "diff." + index + ".after", canonicalJsonOrAbsent(change, before = false))
        }
    }

    private fun canonicalJsonOrAbsent(change: FieldChange, before: Boolean = true): String {
        val value = if (before) change.before else change.after
        return if (value == null) "<absent>" else Json.canonical(value)
    }

    private fun appendScope(out: StringBuilder, scope: SchoolAccountScope) {
        appendField(out, "scope.school", scope.schoolId.value)
        appendField(out, "scope.account", scope.accountId.value)
        appendField(out, "scope.profile", scope.profileId.value)
        appendField(out, "scope.campus", scope.campusId?.value)
    }

    private fun entityRefToken(ref: EntityRef): String {
        val out = StringBuilder(160)
        appendField(out, "type", ref.type.name)
        appendField(out, "id", ref.id)
        appendScope(out, ref.scope)
        return out.toString()
    }

    /** length-prefix 编码，避免字段值中的分隔符造成拼接歧义；null 有独立类型位。 */
    private fun appendField(out: StringBuilder, name: String, value: String?) {
        out.append(name).append('|')
        if (value == null) {
            out.append('0').append('
')
        } else {
            out.append('1').append(value.length).append(':').append(value).append('
')
        }
    }

    /** 标题/原因规范化：小写、压缩空白、去掉常见标点；不删除文字或数字。 */
    private fun normalizeText(raw: String): String {
        val lowered = raw.lowercase(Locale.ROOT)
        val punctuation = setOf(
            '，', '。', '；', '：', '、', '！', '？', '【', '】', '（', '）', '[', ']', '(', ')',
            ',', '.', ';', ':', '!', '?', '「', '」', '『', '』', '《', '》'
        )
        val out = StringBuilder(lowered.length)
        var pendingWhitespace = false
        for (ch in lowered) {
            when {
                ch.isWhitespace() -> pendingWhitespace = out.isNotEmpty()
                punctuation.contains(ch) -> Unit
                else -> {
                    if (pendingWhitespace) out.append(' ')
                    pendingWhitespace = false
                    out.append(ch)
                }
            }
        }
        return out.toString().trim()
    }

    /** 教室等短标识规范化：忽略大小写、空白和常见连接符。 */
    private fun normalizeToken(raw: String): String {
        return raw.lowercase(Locale.ROOT).filterNot {
            it.isWhitespace() || it == '-' || it == '—' || it == '_' || it == '·'
        }
    }

    private fun validateActionScope(scope: SchoolAccountScope, action: ProposedAction) {
        when (action) {
            is ProposedAction.CreateEvent -> require(action.draft.scope == scope) {
                "CreateEvent scope 与 fingerprinter scope 不一致"
            }
            is ProposedAction.ModifyCourseOccurrence -> require(action.courseRef.scope == scope) {
                "ModifyCourseOccurrence scope 与 fingerprinter scope 不一致"
            }
            is ProposedAction.UpdateExisting -> require(action.target.scope == scope) {
                "UpdateExisting scope 与 fingerprinter scope 不一致"
            }
            is ProposedAction.CreateTask -> Unit
        }
    }
}

/**
 * 纯 JVM 进程内指纹索引，供单测、预览会话和未来 Room 适配器做语义参照。
 * 它不是持久存储；生产适配器必须按同一 scope 查询 inbox_proposals，不能把 fp 当全局键。
 */
class InMemoryNoticeFingerprinter(
    private val scope: SchoolAccountScope
) : NoticeFingerprinter {
    private val lock = Any()
    private val existingByFingerprint = LinkedHashMap<String, EntityRef>()

    override fun fingerprint(a: ProposedAction): String = NoticeFingerprintFactory.fingerprint(scope, a)

    override suspend fun findExisting(fp: String): EntityRef? {
        requireValidFingerprint(fp)
        return synchronized(lock) { existingByFingerprint[fp] }
    }

    /** 记录已确认目标。若同一指纹已映射到不同实体，fail-closed，避免规范化过度合并造成误更新。 */
    fun remember(action: ProposedAction, target: EntityRef): String {
        val fp = fingerprint(action)
        rememberFingerprint(fp, target)
        return fp
    }

    fun rememberFingerprint(fp: String, target: EntityRef) {
        requireValidFingerprint(fp)
        require(target.scope == scope) { "指纹目标实体必须与 fingerprinter 同 scope" }
        synchronized(lock) {
            val previous = existingByFingerprint[fp]
            require(previous == null || previous == target) { "同一指纹映射到不同实体，拒绝覆盖" }
            existingByFingerprint[fp] = target
        }
    }

    private fun requireValidFingerprint(fp: String) {
        require(fp.length == 64 && fp.all { it in '0'..'9' || it in 'a'..'f' }) {
            "通知指纹必须是 64 位小写 SHA-256 hex"
        }
    }
}

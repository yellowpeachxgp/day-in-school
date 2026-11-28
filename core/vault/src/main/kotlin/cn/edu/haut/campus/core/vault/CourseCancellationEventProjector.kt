package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * 从课程聚合 journal 投影最小 CourseChanged producer。
 *
 * 首期只投影 occurrenceOverrides[].canceled 的结构化增删改：这是临时停课/复课自动化所需的
 * 最小真实事件语义，不用 substring 或自由文本猜测。VaultEntityChanged 仍保留完整审计路径；
 * 这里不复制全量课程快照，避免把大型聚合 diff 塞进 16KB 领域事件上限。
 */
internal object CourseCancellationEventProjector {
    fun project(change: EntityChange): DomainEvent.CourseChanged? {
        if (change.ref.type != EntityType.COURSE) return null
        val before = change.before?.let(::parseObjectOrNull)
        val after = change.after?.let(::parseObjectOrNull)
        if (before == null && after == null) return null
        val beforeOverrides = overridesById(before)
        val afterOverrides = overridesById(after)
        val fieldChanges = ArrayList<FieldChange>()
        val ids = (beforeOverrides.keys + afterOverrides.keys).sorted()
        for (id in ids) {
            val old = beforeOverrides[id]
            val new = afterOverrides[id]
            val oldValue = old?.second?.obj("canceled") as? JsonValue.Bool
            val newValue = new?.second?.obj("canceled") as? JsonValue.Bool
            when {
                old == null && new != null && newValue != null -> fieldChanges += FieldChange(
                    path = cancellationPath(new.first),
                    kind = FieldChangeKind.ADDED,
                    before = null,
                    after = newValue
                )
                old != null && new == null && oldValue != null -> fieldChanges += FieldChange(
                    path = cancellationPath(old.first),
                    kind = FieldChangeKind.REMOVED,
                    before = oldValue,
                    after = null
                )
                oldValue != null && newValue != null && oldValue.value != newValue.value -> fieldChanges += FieldChange(
                    path = cancellationPath(new!!.first),
                    kind = FieldChangeKind.MODIFIED,
                    before = oldValue,
                    after = newValue
                )
            }
        }
        if (fieldChanges.isEmpty()) return null
        return DomainEvent.CourseChanged(
            eventId = checkNotNull(change.eventId) { "课程 journal 事件缺少 eventId" } + ":course-cancellation",
            scope = change.ref.scope,
            occurredAt = change.at,
            courseRef = change.ref,
            change = change.op,
            diff = FieldDiff(fieldChanges)
        )
    }

    private fun parseObjectOrNull(snapshot: CanonicalJson): JsonValue.Obj? =
        runCatching { Json.parse(snapshot.text) as? JsonValue.Obj }.getOrNull()

    private fun overridesById(root: JsonValue.Obj?): Map<String, Pair<Int, JsonValue.Obj>> {
        val items = root?.arr("occurrenceOverrides") ?: return emptyMap()
        val out = LinkedHashMap<String, Pair<Int, JsonValue.Obj>>()
        items.forEachIndexed { index, value ->
            val obj = value as? JsonValue.Obj ?: return@forEachIndexed
            val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return@forEachIndexed
            out[id] = index to obj
        }
        return out
    }

    private fun cancellationPath(index: Int): String = "/occurrenceOverrides/" + index + "/canceled"
}

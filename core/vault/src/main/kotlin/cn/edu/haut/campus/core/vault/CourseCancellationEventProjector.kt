package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.events.DomainEvent
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.FieldChange
import cn.edu.haut.campus.core.model.FieldChangeKind
import cn.edu.haut.campus.core.model.FieldDiff
import cn.edu.haut.campus.core.model.OccurrenceCancellationSemantics
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
            val oldValue = old?.let(::effectiveCancellationValue)
            val newValue = new?.let(::effectiveCancellationValue)
            when {
                oldValue == null && newValue != null -> fieldChanges += FieldChange(
                    path = cancellationPath(id),
                    kind = FieldChangeKind.ADDED,
                    before = null,
                    after = newValue
                )
                oldValue != null && newValue == null -> fieldChanges += FieldChange(
                    path = cancellationPath(id),
                    kind = FieldChangeKind.REMOVED,
                    before = oldValue,
                    after = null
                )
                oldValue != null && newValue != null && oldValue.value != newValue.value -> fieldChanges += FieldChange(
                    path = cancellationPath(id),
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

    /** 结构性调课/连堂抑制行没有“独立停课”值；只有共同语义认可的独立行才参与投影。 */
    private fun effectiveCancellationValue(row: JsonValue.Obj): JsonValue.Bool? {
        val canceled = row.obj("canceled") as? JsonValue.Bool ?: return null
        return if (
            OccurrenceCancellationSemantics.isIndependentCancellation(
                canceled = canceled.value,
                moveId = row.str("moveId"),
                moveRole = row.str("moveRole"),
                reason = row.str("reason")
            )
        ) {
            canceled
        } else {
            null
        }
    }

    private fun parseObjectOrNull(snapshot: CanonicalJson): JsonValue.Obj? =
        runCatching { Json.parse(snapshot.text) as? JsonValue.Obj }.getOrNull()

    private fun overridesById(root: JsonValue.Obj?): Map<String, JsonValue.Obj> {
        val items = root?.arr("occurrenceOverrides") ?: return emptyMap()
        val out = LinkedHashMap<String, JsonValue.Obj>()
        items.forEach { value ->
            val obj = value as? JsonValue.Obj ?: return@forEach
            val id = obj.str("id")?.takeIf { it.isNotBlank() } ?: return@forEach
            out[id] = obj
        }
        return out
    }

    /**
     * 逻辑稳定身份路径。occurrenceOverrides 在线格式中是数组；数组下标在“先删旧行、再插新行”
     * 的真实停课路径中会复用，不能作为 diff 身份。byId 段是 projection 的稳定逻辑寻址，
     * 不伪装成原始数组下标；其本身仍是合法 JSON Pointer 段序列。
     */
    private fun cancellationPath(overrideId: String): String =
        "/occurrenceOverrides/byId/" + escapeJsonPointerSegment(overrideId) + "/canceled"

    private fun escapeJsonPointerSegment(value: String): String =
        value.replace("~", "~0").replace("/", "~1")
}

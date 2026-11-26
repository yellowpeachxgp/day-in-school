package cn.edu.haut.campus.core.model

import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue

/**
 * 内核可追踪实体的受控类型。
 *
 * 这里没有 UNKNOWN / OTHER：新增领域必须先明确自己的实体类型，避免事件、时间流和
 * Vault 把无法追踪的数据混入同一审计面。后续领域可以追加枚举值，但不得复用或改变
 * 既有值的语义。
 */
enum class EntityType {
    COURSE,
    COURSE_SCHEDULE_RULE,
    COURSE_OCCURRENCE,
    EXAM,
    GRADE_ATTEMPT,
    TERM,
    PERSONAL_EVENT,
    TIMELINE_EVENT,
    UTILITY_METER,
    PLACE,
    INBOX_ITEM,
    GROUP,
    GROUP_EVENT,
    PROCEDURE,
    OPPORTUNITY,
    REVIEW_ITEM,
    AUTOMATION_RULE,
    BLOB,
    PROFILE
}

/**
 * 对学校账号作用域内一个领域实体的稳定引用。
 *
 * 该类型只表达身份，不表达内容版本或可信等级；后者由事件本身的
 * [DataProvenance]、包裹它的 [SourceRef] 或 Vault journal 记录。这样实体在多次同步、
 * 内容修订后仍保持同一引用，适合作为 journal、搜索和事件关联键。
 *
 * [scope] 没有默认值：跨校、跨账号数据绝不能因为调用方省略参数而混用。
 */
data class EntityRef(
    val type: EntityType,
    val id: String,
    val scope: SchoolAccountScope
) {
    init {
        requireStableReferenceId(id, "实体稳定 ID")
    }
}

/**
 * 指向 Campus Graph 中**学校/校区级图实体**的引用。
 *
 * 这不是账号内本地图行引用：地点身份只由 MASTER_PLAN §4.3 的稳定图 ID
 * `<schoolId>/<campusId>/<type>/<slug>` 及拆出的 schoolId/campusId 组成，刻意不包含
 * accountId/profileId。账号或 Profile 对地点的覆盖、收藏和审计应另建账号内实体引用，
 * 不得污染跨账号共享的图实体身份。
 */
data class PlaceRef(
    val id: String,
    val schoolId: SchoolId,
    val campusId: CampusId
) {
    init {
        requireStableReferenceId(id, "地点图稳定 ID")
        val segments = id.split('/')
        require(segments.size == 4) { "地点图稳定 ID 必须是 <schoolId>/<campusId>/<type>/<slug>" }
        require(segments[0] == schoolId.value) { "地点图稳定 ID 的 schoolId 与引用不一致" }
        require(segments[1] == campusId.value) { "地点图稳定 ID 的 campusId 与引用不一致" }
        require(segments[2].isNotEmpty() && segments[3].isNotEmpty()) { "地点图 type/slug 不能为空" }
    }
}

/**
 * 回溯到产生当前投影、变更或审计记录的领域实体。
 *
 * [entity] 是稳定身份；[provenance] 是该来源实体的证据与快照来源，二者都必须显式
 * 提供。provenance 的 school/account 必须与 entity.scope 一致，防止把某个账号的数据
 * 回溯到另一个账号的来源快照。
 */
data class SourceRef(
    val entity: EntityRef,
    val provenance: DataProvenance
) {
    init {
        require(provenance.schoolId == entity.scope.schoolId) { "来源证据的学校与实体作用域不一致" }
        require(provenance.accountId == entity.scope.accountId) { "来源证据的账号与实体作用域不一致" }
        requireValidProvenancePart(provenance.providerId, "providerId", 128)
        requireValidProvenancePart(provenance.sourceSystem, "sourceSystem", 256)
        provenance.sourceRecordId?.let { recordId ->
            require(recordId.isNotBlank() && recordId.length <= 512 && recordId.none { it.isISOControl() }) {
                "来源记录 ID 为空、过长或含控制字符"
            }
        }
    }
}

/** 字段差异使用 RFC 6901 JSON Pointer 作为路径，避免点号路径无法表达任意 JSON 键。 */
enum class FieldChangeKind {
    ADDED,
    REMOVED,
    MODIFIED
}

/**
 * 单个字段的不可缺省差异。
 *
 * before/after 使用可空外层类型表达「字段不存在」，用 [JsonValue.Null] 表达「字段存在
 * 且值为 JSON null」。调用方必须显式传入两个槽位，不能依靠默认值静默丢失变更方向。
 */
data class FieldChange(
    val path: String,
    val kind: FieldChangeKind,
    val before: JsonValue?,
    val after: JsonValue?
) {
    init {
        requireJsonPointer(path)
        when (kind) {
            FieldChangeKind.ADDED -> {
                require(before == null) { "新增字段不能有 before" }
                require(after != null) { "新增字段必须有 after" }
            }
            FieldChangeKind.REMOVED -> {
                require(before != null) { "移除字段必须有 before" }
                require(after == null) { "移除字段不能有 after" }
            }
            FieldChangeKind.MODIFIED -> {
                require(before != null) { "修改字段必须有 before" }
                require(after != null) { "修改字段必须有 after" }
                require(!sameCanonicalJson(before, after)) { "修改字段的前后值在 CanonicalJson 语义下相同" }
            }
        }
    }
}

/**
 * 一次实体变更的字段级差异集合。
 *
 * 至少一个字段且路径不得重复。创建/删除事件也应尽可能携带完整字段差异；实在无法
 * 还原字段时，应由上层使用对应 ChangeKind 并在 journal 的 before/after 快照中说明，
 * 而不是悄悄传入空 diff。
 */
data class FieldDiff(
    val changes: List<FieldChange>
) {
    init {
        require(changes.isNotEmpty()) { "字段差异至少要包含一个字段" }
        require(changes.map { it.path }.distinct().size == changes.size) { "字段差异包含重复路径" }
    }

    val paths: Set<String>
        get() = changes.mapTo(linkedSetOf()) { it.path }
}

/** 领域实体已发生的变更种类；事件消费者不得把 RESTORED 当作普通 CREATED 处理。 */
enum class ChangeKind {
    CREATED,
    UPDATED,
    DELETED,
    RESTORED
}

/** MASTER_PLAN §6.2 的完整写操作者集合；故意不提供 OTHER，防止审计归因被弱化。 */
enum class Actor {
    SYNC,
    USER,
    AUTOMATION,
    PLUGIN,
    RESTORE,
    INBOX
}

private fun requireStableReferenceId(value: String, label: String) {
    require(value.length in 1..256) { "$label 为空或过长" }
    require(value.none { it.isISOControl() || it.isWhitespace() }) { "$label 不能包含空白或控制字符" }
    require(value.all { it.isLetterOrDigit() || it in "_-./:" }) { "$label 包含非法字符" }
}

private fun requireValidProvenancePart(value: String, label: String, maxLength: Int) {
    require(value.isNotBlank() && value.length <= maxLength) { "来源证据缺少有效的 $label" }
    require(value.none { it.isISOControl() || it.isWhitespace() }) { "来源证据 $label 不能包含空白或控制字符" }
}

private fun sameCanonicalJson(first: JsonValue?, second: JsonValue?): Boolean {
    if (first == null || second == null) return false
    return Json.canonical(first) == Json.canonical(second)
}

private fun requireJsonPointer(path: String) {
    // RFC 6901 中 "/" 是空对象键的合法引用；这里不做应用层子集裁剪。
    require(path.length in 1..512 && path.startsWith('/')) { "字段路径必须是 JSON Pointer" }
    require(path.none { it.isISOControl() }) { "字段路径不能包含控制字符" }
    var index = 0
    while (index < path.length) {
        if (path[index] == '~') {
            require(index + 1 < path.length && (path[index + 1] == '0' || path[index + 1] == '1')) {
                "字段路径中的 ~ 必须按 JSON Pointer 转义为 ~0 或 ~1"
            }
            index++
        }
        index++
    }
}

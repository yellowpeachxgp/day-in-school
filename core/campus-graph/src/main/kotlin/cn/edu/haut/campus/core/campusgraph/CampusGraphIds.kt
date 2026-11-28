package cn.edu.haut.campus.core.campusgraph

import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolId

/** Campus Graph 支持的实体类型；wireId 是稳定 ID 的第三段，不得改名破坏兼容性。 */
enum class CampusGraphEntityType(val wireId: String) {
    CAMPUS("campus"),
    BUILDING("building"),
    FLOOR("floor"),
    ROOM("room"),
    FACILITY("facility"),
    ENTRANCE("entrance"),
    INDOOR_NODE("indoor-node"),
    SERVICE("service"),
    PROCEDURE("procedure");

    companion object {
        fun fromWireId(raw: String): CampusGraphEntityType? = entries.firstOrNull { it.wireId == raw }
    }
}

/** 稳定图 ID 构造与解析。所有外部输入必须先经这里校验，避免路径式 ID 混入空白或控制字符。 */
object CampusGraphIds {

    fun build(
        schoolId: SchoolId,
        campusId: CampusId,
        type: CampusGraphEntityType,
        slug: String
    ): PlaceRef {
        val normalizedSlug = normalizeSlug(slug)
        return PlaceRef(
            schoolId.value + "/" + campusId.value + "/" + type.wireId + "/" + normalizedSlug,
            schoolId,
            campusId
        )
    }

    /** 把楼宇、房间或流程名称转成稳定 slug；Unicode 字母/数字保留，其余空白与标点折叠为短横线。 */
    fun normalizeSlug(raw: String): String {
        require(raw.isNotBlank()) { "图实体 slug 不能为空" }
        require(raw.length <= 128) { "图实体 slug 过长" }
        require(raw.none { it.isISOControl() }) { "图实体 slug 不能包含控制字符" }
        val out = StringBuilder()
        var pendingDash = false
        for (ch in raw.trim()) {
            when {
                ch.isLetterOrDigit() -> {
                    if (pendingDash && out.isNotEmpty()) out.append('-')
                    pendingDash = false
                    out.append(ch.lowercaseChar())
                }
                ch == '-' || ch == '_' -> {
                    if (out.isNotEmpty()) out.append(ch)
                    pendingDash = false
                }
                else -> pendingDash = out.isNotEmpty()
            }
        }
        val normalized = out.toString().trim('-')
        require(normalized.isNotBlank()) { "图实体 slug 规范化后为空" }
        require(normalized.length <= 96) { "图实体 slug 规范化后过长" }
        return normalized
    }

    fun typeOf(ref: PlaceRef): CampusGraphEntityType {
        val segments = ref.id.split('/')
        require(segments.size == 4) { "地点图稳定 ID 必须是 <schoolId>/<campusId>/<type>/<slug>" }
        return CampusGraphEntityType.fromWireId(segments[2])
            ?: throw IllegalArgumentException("未知 Campus Graph 实体类型：" + segments[2])
    }

    fun slugOf(ref: PlaceRef): String = ref.id.split('/')[3]
}

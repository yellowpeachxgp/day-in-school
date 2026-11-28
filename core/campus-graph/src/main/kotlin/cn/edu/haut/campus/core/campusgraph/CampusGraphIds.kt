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

    /**
     * dis-datapack item ID 的防碰撞图 slug。
     *
     * datapack item 字符集是 lowercase alphanumeric + . _ / -。这里不用面向显示名的
     * normalizeSlug 折叠规则：. _ - 在稳定 ID 中本来合法所以逐字保留，唯一会切开
     * 四段图 ID 的斜线映射为输入字符集不可能包含的冒号；输出再进入 dp: 专用命名空间。
     * normalizeSlug 永远不会生成冒号，因此即使 slashless item 也不能与人工/legacy slug
     * 相撞；该映射不依赖大小写，逐字符可逆且 injective，aid/forms 与 aid-forms 不会折叠。
     */
    fun buildDatapackEntity(
        schoolId: SchoolId,
        campusId: CampusId,
        type: CampusGraphEntityType,
        itemId: String
    ): PlaceRef {
        val encoded = encodeDatapackItemId(itemId)
        require(itemId.length <= 120) { "datapack item ID 过长" }
        val slug = "dp:" + encoded
        val ref = PlaceRef(
            schoolId.value + "/" + campusId.value + "/" + type.wireId + "/" + slug,
            schoolId,
            campusId
        )
        return ref
    }

    /** Visible for regression tests; not a general display-name normalizer. */
    fun encodeDatapackItemId(itemId: String): String {
        require(itemId.isNotEmpty() && itemId.length <= 120) { "datapack item ID 为空或过长" }
        return buildString(itemId.length) {
            for (ch in itemId) {
                when (ch) {
                    in 'a'..'z', in '0'..'9', '.', '-', '_' -> append(ch)
                    '/' -> append(':')
                    else -> throw IllegalArgumentException("datapack item ID 含契约外字符，不能安全投影：" + itemId)
                }
            }
        }
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

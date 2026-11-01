package cn.edu.haut.campus.core.packages.catalog

import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.core.packages.manifest.PackageVersion

/**
 * 离线目录条目：学校档案，以及（可选）提供该校声明的学校包版本。
 * 目录只描述"有什么"，不表示该校已经通过设备或账号验证。
 */
data class CatalogEntry(
    val school: SchoolProfile,
    val packageId: String? = null,
    val packageVersion: PackageVersion? = null
) {
    init {
        require(packageId == null || packageId.isNotBlank()) { "包 ID 不能为空" }
        require(packageId != null || packageVersion == null) { "没有包 ID 时不能声明包版本" }
    }

    val id: SchoolId get() = school.id
}

/**
 * 用户本地覆盖：改名、补充别名、隐藏、备注。
 *
 * 只按稳定 schoolId 关联——不用校名、不用教务域名、不用数组下标，
 * 因此目录升级、学校改名或换学校包都不会让用户自定义串到别的学校。
 * UNVERIFIED：合并策略为本仓库自定，未与真实用户升级场景核对。
 */
data class SchoolOverride(
    val schoolId: SchoolId,
    val displayName: String? = null,
    val additionalAliases: List<String> = emptyList(),
    val note: String? = null,
    val hidden: Boolean = false
) {
    init {
        require(displayName == null || displayName.isNotBlank()) { "覆盖校名不能为空字符串" }
        require(note == null || note.length <= 500) { "覆盖备注过长" }
        require(additionalAliases.all { it.isNotBlank() }) { "覆盖别名不能为空字符串" }
    }

    fun apply(profile: SchoolProfile): SchoolProfile = profile.copy(
        displayName = displayName ?: profile.displayName,
        aliases = (profile.aliases + additionalAliases).filter { it.isNotBlank() }.distinct()
    )
}

/** 只读目录；实现必须离线可用。 */
interface SchoolCatalog {
    /** 可见条目（隐藏项不出现），顺序与目录一致。 */
    fun entries(): List<CatalogEntry>

    /** 按稳定 ID 查找，包含被用户隐藏的学校。 */
    fun find(id: SchoolId): CatalogEntry?

    fun overrides(): List<SchoolOverride>
}

/**
 * 目录 + 本地覆盖的合并视图。
 *
 * 合并规则：
 * 1. 目录条目按稳定 schoolId 建索引，重复 ID 直接拒绝（不靠顺序决定谁是"真的"）；
 * 2. 覆盖只修改展示字段（名称/别名），不修改能力、教务实例与验证等级；
 * 3. [upgrade] 返回应用了同一批覆盖的新视图，覆盖本身不随目录升级丢失；
 * 4. 目录里暂时不存在的学校，其覆盖保留在 [orphanOverrides] 中，重新出现时自动再生效。
 */
class OverlaySchoolCatalog(
    catalog: List<CatalogEntry>,
    private val overrides: List<SchoolOverride> = emptyList()
) : SchoolCatalog {

    private val catalogById: LinkedHashMap<SchoolId, CatalogEntry>
    private val overrideById: LinkedHashMap<SchoolId, SchoolOverride>

    init {
        val duplicatedCatalog = catalog.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicatedCatalog.isEmpty()) {
            "目录包含重复学校 ID：" + duplicatedCatalog.joinToString { it.value }
        }
        val duplicatedOverrides = overrides.groupBy { it.schoolId }.filterValues { it.size > 1 }.keys
        require(duplicatedOverrides.isEmpty()) {
            "本地覆盖包含重复学校 ID：" + duplicatedOverrides.joinToString { it.value }
        }
        catalogById = LinkedHashMap<SchoolId, CatalogEntry>().apply { catalog.forEach { put(it.id, it) } }
        overrideById = LinkedHashMap<SchoolId, SchoolOverride>().apply { overrides.forEach { put(it.schoolId, it) } }
    }

    override fun entries(): List<CatalogEntry> = catalogById.values.mapNotNull { entry ->
        if (overrideById[entry.id]?.hidden == true) null else resolve(entry)
    }

    override fun find(id: SchoolId): CatalogEntry? = catalogById[id]?.let { resolve(it) }

    override fun overrides(): List<SchoolOverride> = overrideById.values.toList()

    /** 目录升级：保留全部覆盖，包括当前目录里找不到学校的"孤儿覆盖"。 */
    fun upgrade(upgradedCatalog: List<CatalogEntry>): OverlaySchoolCatalog =
        OverlaySchoolCatalog(upgradedCatalog, overrideById.values.toList())

    /** 用户编辑覆盖：同一学校的旧覆盖被替换而不是叠加，避免别名无限增长。 */
    fun withOverride(override: SchoolOverride): OverlaySchoolCatalog {
        val merged = overrideById.values.filterNot { it.schoolId == override.schoolId } + override
        return OverlaySchoolCatalog(catalogById.values.toList(), merged)
    }

    fun orphanOverrides(): List<SchoolOverride> =
        overrideById.values.filter { it.schoolId !in catalogById }

    private fun resolve(entry: CatalogEntry): CatalogEntry {
        val override = overrideById[entry.id] ?: return entry
        return entry.copy(school = override.apply(entry.school))
    }
}

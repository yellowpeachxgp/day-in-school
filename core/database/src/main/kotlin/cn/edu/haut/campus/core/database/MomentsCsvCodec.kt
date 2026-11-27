package cn.edu.haut.campus.core.database

/** Moments 本地 viewer/收藏/归属集合的 CSV 持久化编解码；语义与原私有实现完全一致。 */
internal fun decodeCsvSet(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

internal fun encodeCsvSet(set: Set<String>): String =
    set.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")

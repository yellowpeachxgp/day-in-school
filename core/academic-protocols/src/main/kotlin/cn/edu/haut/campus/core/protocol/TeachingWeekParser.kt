package cn.edu.haut.campus.core.protocol

/** Bounded, whole-expression parsing. Unsupported input remains unknown, never a partial guess. */
internal object TeachingWeekParser {
    fun parse(raw: String?): Set<Int> {
        if (raw == null || raw.length > 512) return emptySet()
        val odd = raw.contains("单")
        val even = raw.contains("双")
        if (odd && even) return emptySet()
        val value = raw.replace(Regex("""[\s周()（）单双]"""), "")
        if (value.isEmpty()) return emptySet()
        val result = sortedSetOf<Int>()
        for (token in value.split(',', '，', '、')) {
            val match = Regex("""(\d{1,2})(?:[-~～至—](\d{1,2}))?""").matchEntire(token) ?: return emptySet()
            val start = match.groupValues[1].toIntOrNull() ?: return emptySet()
            val end = match.groupValues[2].ifEmpty { match.groupValues[1] }.toIntOrNull() ?: return emptySet()
            if (start !in 1..80 || end !in start..80) return emptySet()
            result.addAll(start..end)
        }
        return result.filter { (!odd || it % 2 == 1) && (!even || it % 2 == 0) }.toSortedSet()
    }
}

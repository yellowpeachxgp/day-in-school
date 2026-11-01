package cn.edu.haut.campus.core.protocol.zf

/**
 * 教学周字符串解析（UNVERIFIED）。
 *
 * 按公开协议里常见的写法推导：`1-16周`、`1-16周(单)`、`1-16周(双)`、`1-8,10-16周`、
 * `第1-4周`、`1,3,5`。解析不出来时返回 null，由调用方决定“保持空集”而不是猜测周次。
 * 未与任何真实学校返回核对。
 */
internal object ZfWeeks {

    /** 与 RemoteMeeting 的允许范围保持一致，超出即视为无法识别。 */
    private const val MAX_WEEK = 80

    private val RANGE = Regex("""^(\d{1,2})(?:-(\d{1,2}))?$""")

    /** 宽松版本：无法识别时返回空集，对应“课表没给出周次”的合法形态。 */
    fun parse(raw: String?): Set<Int> = parseStrict(raw).orEmpty()

    /** 严格版本：无法识别时返回 null；空白输入同样返回 null（表示没有周次信息）。 */
    fun parseStrict(raw: String?): Set<Int>? {
        val text = normalize(raw) ?: return null
        if (text.isEmpty()) return null
        val weeks = LinkedHashSet<Int>()
        for (segment in text.split(',')) {
            val token = segment.trim()
            if (token.isEmpty()) return null
            val hasOdd = token.contains('单')
            val hasEven = token.contains('双')
            // 同时出现“单”“双”按“单双周”理解，即整段全周
            val parity = when {
                hasOdd && hasEven -> null
                hasOdd -> 1
                hasEven -> 0
                else -> null
            }
            val numbers = token
                .replace("单", "")
                .replace("双", "")
                .replace("周", "")
                .replace("第", "")
                .trim()
            // 只有“单周/双周”而没有区间，无法确定范围，不猜
            if (numbers.isEmpty()) return null
            val match = RANGE.matchEntire(numbers) ?: return null
            val start = match.groupValues[1].toIntOrNull() ?: return null
            val end = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: start
            if (start < 1 || end > MAX_WEEK || start > end) return null
            for (week in start..end) {
                if (parity == null || week % 2 == parity) weeks += week
            }
        }
        return weeks
    }

    /** 判断一行文本是否像周次（旧正方课表单元格里没有语义标签，只能靠形态识别）。 */
    fun looksLikeWeeks(raw: String): Boolean {
        val text = normalize(raw) ?: return false
        if (text.isEmpty()) return false
        val marker = text.contains('周') || text.contains('单') || text.contains('双')
        return marker && text.any { it.isDigit() }
    }

    private fun normalize(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val builder = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            when (ch) {
                in '０'..'９' -> builder.append('0' + (ch - '０'))
                '，', '、', '；', ';' -> builder.append(',')
                '－', '—', '–', '~', '～' -> builder.append('-')
                '（', '）', '(', ')', ' ', '\t', '\u3000' -> Unit
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }
}

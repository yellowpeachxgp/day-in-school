package cn.edu.haut.campus.core.ai

/** 发送前本地脱敏。输出只允许占位符与命中计数，绝不保留原始 PII 或可复制片段。 */
object AiLocalRedactor {
    private const val PLACEHOLDER_NATIONAL_ID = "[已脱敏:身份证号]"
    private const val PLACEHOLDER_PHONE = "[已脱敏:手机号]"
    private const val PLACEHOLDER_STUDENT = "[已脱敏:学号]"
    private const val PLACEHOLDER_DORM = "[已脱敏:宿舍号]"

    private val nationalIdPattern = Regex(
        """(?<![0-9A-Za-z])\d{6}(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx](?![0-9A-Za-z])"""
    )
    private val phonePattern = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")
    private val contextualStudentPattern = Regex(
        """(?i)(?:学号|student(?:\s*id)?|sid)\s*[:：#=]?\s*([A-Za-z0-9_-]{6,24})"""
    )
    private val bareStudentPattern = Regex("""(?<!\d)\d{8,12}(?!\d)""")
    private val contextualDormPattern = Regex(
        """(?i)(?:宿舍|寝室|房间|room)\s*[:：#=]?\s*([A-Za-z0-9\u4e00-\u9fff]{1,10}(?:栋|号楼|楼|舍|苑)?[-—]?[0-9A-Za-z]{2,5}室?)"""
    )
    private val hyphenDormPattern = Regex("""(?<![0-9A-Za-z])[A-Za-z]?\d{1,2}[-—][1-9]\d{2}(?![0-9A-Za-z])""")
    private val buildingDormPattern = Regex("""(?<![0-9A-Za-z])\d{1,2}栋[1-9]\d{2}室?(?![0-9A-Za-z])""")

    fun redact(messages: List<AiMessage>): RedactedAiMessages {
        val counts = sortedMapOf<RedactionKind, Int>()
        val outbound = messages.map { message ->
            var text = message.text
            text = replaceWhole(text, nationalIdPattern, PLACEHOLDER_NATIONAL_ID, counts, RedactionKind.NATIONAL_ID)
            text = replaceWhole(text, phonePattern, PLACEHOLDER_PHONE, counts, RedactionKind.PHONE_NUMBER)
            text = replaceGroup(text, contextualStudentPattern, PLACEHOLDER_STUDENT, counts, RedactionKind.STUDENT_ID)
            text = replaceWhole(text, bareStudentPattern, PLACEHOLDER_STUDENT, counts, RedactionKind.STUDENT_ID)
            text = replaceGroup(text, contextualDormPattern, PLACEHOLDER_DORM, counts, RedactionKind.DORM_ROOM)
            text = replaceWhole(text, hyphenDormPattern, PLACEHOLDER_DORM, counts, RedactionKind.DORM_ROOM)
            text = replaceWhole(text, buildingDormPattern, PLACEHOLDER_DORM, counts, RedactionKind.DORM_ROOM)
            message.copy(text = text)
        }
        return RedactedAiMessages(
            messages = outbound,
            redactions = counts.map { (kind, count) -> Redaction(kind, count) }
        )
    }

    private fun replaceWhole(
        text: String,
        pattern: Regex,
        placeholder: String,
        counts: MutableMap<RedactionKind, Int>,
        kind: RedactionKind
    ): String {
        var hits = 0
        val replaced = pattern.replace(text) {
            hits += 1
            placeholder
        }
        if (hits > 0) counts[kind] = (counts[kind] ?: 0) + hits
        return replaced
    }

    /** 对“学号：xxx”这类上下文只替换捕获组，保留标签文本，方便用户在预览中理解字段含义。 */
    private fun replaceGroup(
        text: String,
        pattern: Regex,
        placeholder: String,
        counts: MutableMap<RedactionKind, Int>,
        kind: RedactionKind
    ): String {
        var hits = 0
        val replaced = pattern.replace(text) { match ->
            val secret = match.groupValues.getOrNull(1).orEmpty()
            if (secret.isEmpty()) {
                match.value
            } else {
                hits += 1
                match.value.replaceFirst(secret, placeholder)
            }
        }
        if (hits > 0) counts[kind] = (counts[kind] ?: 0) + hits
        return replaced
    }
}

data class RedactedAiMessages(
    val messages: List<AiMessage>,
    val redactions: List<Redaction>
)

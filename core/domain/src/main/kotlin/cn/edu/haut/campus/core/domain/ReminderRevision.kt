package cn.edu.haut.campus.core.domain

import cn.edu.haut.campus.core.model.EffectiveReminderPolicy
import cn.edu.haut.campus.core.model.HAUT_ZONE_ID
import cn.edu.haut.campus.core.model.ReminderEvent
import cn.edu.haut.campus.core.model.ReminderEventType
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

object ReminderRevision {

    /**
     * 严格使用河南工业大学校区时区（Asia/Shanghai）将本地课程/考试日期与时间转换为绝对 Instant，
     * 不受手机系统时区更改影响。
     */
    fun schoolInstant(date: LocalDate, time: LocalTime): Instant =
        ZonedDateTime.of(date, time, HAUT_ZONE_ID).toInstant()

    /**
     * 规范化教室/地点文本：去除多余空白、统一全角字母数字为半角，避免无意义的格式抖动触发假变更。
     */
    fun normalizeText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val sb = StringBuilder(raw.length)
        var lastWasSpace = false
        for (ch in raw.trim()) {
            val normalizedChar = when (ch.code) {
                0x3000 -> ' '
                in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            if (normalizedChar.isWhitespace()) {
                if (!lastWasSpace) {
                    sb.append(' ')
                    lastWasSpace = true
                }
            } else {
                sb.append(normalizedChar)
                lastWasSpace = false
            }
        }
        return sb.toString().trim()
    }

    /**
     * 计算事件内容稳定指纹（SHA-256）：
     * 仅纳入实际影响提醒语义的字段（开始/结束时间、规范化教室、建筑ID、座位号、取消状态），
     * 不包含教务同步时间戳等易变字段。
     */
    fun computeEventRevision(
        eventKey: String,
        type: ReminderEventType,
        startAt: Instant,
        endAt: Instant?,
        locationText: String?,
        buildingId: String?,
        seatNumber: String? = null,
        isCancelled: Boolean = false
    ): String {
        val canonical = buildString {
            append("event-v1|")
            append(eventKey).append('|')
            append(type.name).append('|')
            append(startAt.toEpochMilli()).append('|')
            append(endAt?.toEpochMilli() ?: -1L).append('|')
            append(normalizeText(locationText)).append('|')
            append(normalizeText(buildingId)).append('|')
            append(normalizeText(seatNumber)).append('|')
            append(isCancelled)
        }
        return sha256Hex(canonical)
    }

    fun computeEventRevision(event: ReminderEvent): String = computeEventRevision(
        eventKey = event.eventKey,
        type = event.type,
        startAt = event.startAt,
        endAt = event.endAt,
        locationText = event.locationText,
        buildingId = event.buildingId,
        seatNumber = event.seatNumber,
        isCancelled = event.isCancelled
    )

    fun computePolicyRevision(
        enabled: Boolean,
        classLeadMinutes: Int,
        maxSnoozes: Int,
        cutoffMinutesBeforeStart: Int,
        examDayEnabled: Boolean,
        examHourEnabled: Boolean
    ): String {
        val canonical = buildString {
            append("policy-v1|")
            append(enabled).append('|')
            append(classLeadMinutes).append('|')
            append(maxSnoozes).append('|')
            append(cutoffMinutesBeforeStart).append('|')
            append(examDayEnabled).append('|')
            append(examHourEnabled)
        }
        return sha256Hex(canonical)
    }

    fun computePolicyRevision(policy: EffectiveReminderPolicy): String = computePolicyRevision(
        enabled = policy.enabled,
        classLeadMinutes = policy.classLeadMinutes,
        maxSnoozes = policy.maxSnoozes,
        cutoffMinutesBeforeStart = policy.cutoffMinutesBeforeStart,
        examDayEnabled = policy.examDayEnabled,
        examHourEnabled = policy.examHourEnabled
    )

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

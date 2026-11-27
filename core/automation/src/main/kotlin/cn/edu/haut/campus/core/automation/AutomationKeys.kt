package cn.edu.haut.campus.core.automation

import java.security.MessageDigest
import java.time.Instant

/** 自动化幂等键工具。键是可读调试格式，runId 则使用 SHA-256 派生，避免直接拼接造成碰撞。 */
object AutomationKeys {
    fun dedupeKey(
        ruleId: String,
        triggerInstanceKey: String,
        policy: DedupePolicy,
        firedAt: Instant
    ): String = when (policy) {
        DedupePolicy.OncePerTriggerInstance ->
            "once-per-trigger/v1/" + lengthPrefixed(ruleId) + "/" + lengthPrefixed(triggerInstanceKey)
    }

    fun policyFingerprint(policy: DedupePolicy): String = when (policy) {
        DedupePolicy.OncePerTriggerInstance -> "once-per-trigger-instance"
    }

    /**
     * runId 只由触发实例身份派生，不包含投递时间。崩溃恢复或 outbox 重投即使 firedAt 不同，
     * ActionExecutor 仍能用同一个 runId/dedupeKey 对外部副作用做幂等去重。
     */
    fun runId(ruleId: String, triggerInstanceKey: String): String {
        val material = "automation-run/v1\n" +
            lengthPrefixed(ruleId) + "\n" +
            lengthPrefixed(triggerInstanceKey)
        return "run-" + sha256Hex(material).substring(0, 40)
    }

    fun dryRunTriggerInstanceKey(ruleId: String, at: Instant): String {
        val material = "automation-dry-run/v1\n" + lengthPrefixed(ruleId) + "\n" + at.epochSecond + "\n" + at.nano
        return "dry-run:" + sha256Hex(material).substring(0, 32)
    }

    fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val hex = (byte.toInt() and 0xff).toString(16)
            if (hex.length == 1) out.append('0')
            out.append(hex)
        }
        return out.toString()
    }

    private fun lengthPrefixed(value: String): String = value.length.toString() + "#" + value
}

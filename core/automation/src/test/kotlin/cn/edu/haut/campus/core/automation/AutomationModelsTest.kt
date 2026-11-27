package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.timeline.TimelineKind
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AutomationModelsTest {
    @Test
    fun beforeEventUsesTimelineKindsAndRejectsInvalidInput() {
        assertEquals(
            TriggerSpec.BeforeEvent.TYPE,
            TriggerSpec.BeforeEvent(setOf(TimelineKind.COURSE), Duration.ofMinutes(10)).type
        )
        assertThrows(IllegalArgumentException::class.java) {
            TriggerSpec.BeforeEvent(emptySet(), Duration.ofMinutes(10))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TriggerSpec.BeforeEvent(setOf(TimelineKind.COURSE), Duration.ZERO)
        }
    }

    @Test
    fun dedupeKeysDoNotCollideAndRemainStableAcrossReDeliveryTime() {
        val at = Instant.parse("2026-03-02T08:00:00Z")
        val first = AutomationKeys.dedupeKey("ab", "c", DedupePolicy.OncePerTriggerInstance, at)
        val second = AutomationKeys.dedupeKey("a", "bc", DedupePolicy.OncePerTriggerInstance, at)
        assertNotEquals(first, second)

        assertEquals(
            AutomationKeys.dedupeKey("rule", "trigger-a", DedupePolicy.OncePerTriggerInstance, at),
            AutomationKeys.dedupeKey("rule", "trigger-a", DedupePolicy.OncePerTriggerInstance, at.plusSeconds(60))
        )
    }

    @Test
    fun ruleRequiresActionAndPluginTriggerTypeIsNamespaced() {
        assertThrows(IllegalArgumentException::class.java) {
            AutomationTestFixtures.rule(actions = emptyList())
        }
        val plugin = TriggerSpec.Plugin(pluginId = "weather-pack", triggerId = "rain")
        assertEquals("plugin.weather-pack.rain", plugin.type)
    }

    @Test
    fun sensitiveFieldMatrixRejectsDirectAndCompoundSecretNames() {
        val sensitiveNames = listOf(
            // 与 core/events 相同的五类代表：口令、凭据、会话/Cookie、令牌、秘密值
            "Password",
            "user_credential",
            "session-cookie",
            "access.token",
            "CLIENT_SECRET",
            // 自动化长生命周期数据额外拒绝的直接秘密字段名
            "key",
            "session",
            "auth",
            "passcode",
            // 明确秘密语义的密钥材料复合词
            "api_key",
            "privateKey",
            "encryptionKey"
        )

        sensitiveNames.forEach { sensitiveName ->
            val config = JsonValue.Obj(mapOf(sensitiveName to JsonValue.Str("must-not-persist")))
            assertThrows(SensitiveAutomationDataException::class.java) {
                ActionSpec(type = "test.action", config = config)
            }
        }
    }

    @Test
    fun sensitiveKeysCannotEnterNestedEvidenceFilterOrPluginConfig() {
        val nestedSensitive = JsonValue.Obj(
            mapOf(
                "safeContainer" to JsonValue.Arr(
                    listOf(
                        JsonValue.Obj(mapOf("auth" to JsonValue.Str("must-not-persist")))
                    )
                )
            )
        )

        assertThrows(SensitiveAutomationDataException::class.java) {
            Evidence(source = "test.source", detail = "证据", data = nestedSensitive)
        }
        assertThrows(SensitiveAutomationDataException::class.java) {
            TriggerSpec.OnEvent(eventType = "data.stale", filter = nestedSensitive)
        }
        assertThrows(SensitiveAutomationDataException::class.java) {
            TriggerSpec.Plugin(pluginId = "weather-pack", triggerId = "rain", config = nestedSensitive)
        }
    }

    @Test
    fun benignKeyLikeFieldNamesAreNotFalsePositives() {
        val benign = JsonValue.Obj(
            mapOf(
                "publicKey" to JsonValue.Str("public-material"),
                "author" to JsonValue.Str("teacher"),
                "monkey" to JsonValue.Str("ordinary-word")
            )
        )

        val action = ActionSpec(type = "test.action", config = benign)
        val evidence = Evidence(source = "test.source", detail = "证据", data = benign)

        assertEquals(benign, action.config)
        assertEquals(benign, evidence.data)
    }

    @Test
    fun sensitiveKeysCannotEnterConfigOrEvidence() {
        val sensitive = JsonValue.Obj(mapOf("apiToken" to JsonValue.Str("dummy")))

        assertThrows(SensitiveAutomationDataException::class.java) {
            ActionSpec(type = "test.action", config = sensitive)
        }
        assertThrows(SensitiveAutomationDataException::class.java) {
            Evidence(source = "test.source", detail = "证据", data = sensitive)
        }
    }
}

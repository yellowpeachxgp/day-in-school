package cn.edu.haut.campus.core.automation

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
    fun dedupeKeysDoNotCollideAndWindowPolicySharesBucket() {
        val at = Instant.parse("2026-03-02T08:00:00Z")
        val first = AutomationKeys.dedupeKey("ab", "c", DedupePolicy.OncePerTriggerInstance, at)
        val second = AutomationKeys.dedupeKey("a", "bc", DedupePolicy.OncePerTriggerInstance, at)
        assertNotEquals(first, second)

        val window = DedupePolicy.OncePerRuleWindow(Duration.ofHours(1))
        val keyA = AutomationKeys.dedupeKey("rule", "trigger-a", window, at)
        val keyB = AutomationKeys.dedupeKey("rule", "trigger-b", window, at.plusSeconds(60))
        assertEquals(keyA, keyB)
    }

    @Test
    fun ruleRequiresActionAndPluginTriggerTypeIsNamespaced() {
        assertThrows(IllegalArgumentException::class.java) {
            AutomationTestFixtures.rule(actions = emptyList())
        }
        val plugin = TriggerSpec.Plugin(pluginId = "weather-pack", triggerId = "rain")
        assertEquals("plugin.weather-pack.rain", plugin.type)
    }
}

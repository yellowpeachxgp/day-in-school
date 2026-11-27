package cn.edu.haut.campus.core.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRegistryTest {
    @Test
    fun externalWriteForbiddenActionCannotRegister() {
        val registry = AutomationExtensionRegistry()
        val executor = AutomationTestFixtures.RecordingActionExecutor(
            type = "test.external-write",
            risk = ActionRisk.EXTERNAL_WRITE_FORBIDDEN
        )

        val error = assertThrows(ExternalWriteForbiddenActionException::class.java) {
            registry.registerActionExecutor(executor)
        }

        assertFalse(registry.actionTypes().contains("test.external-write"))
        assertTrue(error.message?.contains("EXTERNAL_WRITE_FORBIDDEN") == true)
    }

    @Test
    fun duplicateTypeAndIllegalTypeFailRegistration() {
        val registry = AutomationExtensionRegistry()
        registry.registerConditionEvaluator(AutomationTestFixtures.RecordingConditionEvaluator())

        assertThrows(DuplicateAutomationExtensionException::class.java) {
            registry.registerConditionEvaluator(AutomationTestFixtures.RecordingConditionEvaluator())
        }
        assertThrows(IllegalArgumentException::class.java) {
            registry.registerConditionEvaluator(AutomationTestFixtures.RecordingConditionEvaluator(type = "BadType"))
        }
    }
}

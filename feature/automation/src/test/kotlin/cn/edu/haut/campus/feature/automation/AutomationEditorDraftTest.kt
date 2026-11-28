package cn.edu.haut.campus.feature.automation

import cn.edu.haut.campus.core.automation.BuiltinAutomationTypes
import cn.edu.haut.campus.core.automation.TriggerSpec
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEditorDraftTest {
    @Test
    fun defaultDraftCreatesSafeLocalRule() {
        val rule = AutomationEditorDraft().toRule(AutomationPreviewFixtures.scope)

        assertTrue(rule.trigger is TriggerSpec.AtTime)
        assertEquals(1, rule.conditions.size)
        assertEquals(BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY, rule.conditions.single().type)
        assertEquals(2, rule.actions.size)
        assertEquals(BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION, rule.actions[0].type)
        assertEquals(BuiltinAutomationTypes.ACTION_REFRESH_HOME, rule.actions[1].type)
    }

    @Test
    fun utilityThresholdConvertsYuanToCents() {
        val draft = AutomationEditorDraft(
            triggerChoice = AutomationTriggerChoice.THRESHOLD,
            thresholdYuanText = "12.34"
        )

        val trigger = draft.toRule(AutomationPreviewFixtures.scope).trigger as TriggerSpec.Threshold

        assertEquals(BigDecimal("1234"), trigger.value)
    }

    @Test
    fun emptyActionSetIsRejected() {
        val draft = AutomationEditorDraft(notify = false, createTask = false, createEvent = false, refreshHome = false)

        assertEquals("至少选择一个动作", draft.validate())
    }

    @Test
    fun personalEventActionIsAvailableAlongsideTask() {
        val draft = AutomationEditorDraft(createEvent = true, eventDurationMinutesText = "45")

        val actions = draft.toRule(AutomationPreviewFixtures.scope).actions
        val createEvent = actions.first { it.type == BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT }

        assertEquals("PERSONAL", createEvent.config.str("kind"))
        assertEquals("45", createEvent.config.obj("durationMinutes")?.intOrNull()?.toString())
    }
}

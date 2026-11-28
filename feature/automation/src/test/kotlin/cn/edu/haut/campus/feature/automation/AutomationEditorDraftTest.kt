package cn.edu.haut.campus.feature.automation

import cn.edu.haut.campus.core.automation.BuiltinAutomationTemplates
import cn.edu.haut.campus.core.automation.BuiltinAutomationTypes
import cn.edu.haut.campus.core.automation.TriggerSpec
import cn.edu.haut.campus.feature.automation.preview.AutomationPreviewFixtures
import cn.edu.haut.campus.feature.automation.state.AutomationEditorDraft
import cn.edu.haut.campus.feature.automation.state.AutomationScreenStatus
import cn.edu.haut.campus.feature.automation.state.AutomationTriggerChoice
import cn.edu.haut.campus.feature.automation.state.triggerSummary
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationEditorDraftTest {
    @Test
    fun defaultDraftCreatesSafeDisabledLocalRule() {
        val rule = AutomationEditorDraft().toRule(AutomationPreviewFixtures.scope)

        assertTrue(rule.trigger is TriggerSpec.AtTime)
        assertFalse("编辑器新建规则也必须默认关闭", rule.enabled)
        assertEquals(1, rule.conditions.size)
        assertEquals(BuiltinAutomationTypes.CONDITION_HAS_CLASS_TODAY, rule.conditions.single().type)
        assertEquals(2, rule.actions.size)
        assertEquals(BuiltinAutomationTypes.ACTION_LOCAL_NOTIFICATION, rule.actions[0].type)
        assertEquals(BuiltinAutomationTypes.ACTION_REFRESH_HOME, rule.actions[1].type)
    }

    @Test
    fun utilityThresholdConvertsNativeUnitsToCentiunits() {
        val draft = AutomationEditorDraft(
            triggerChoice = AutomationTriggerChoice.THRESHOLD,
            thresholdValueText = "12.34"
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
    fun thresholdSummaryUsesNativeUnitWithoutCentiunitScalingError() {
        val rule = AutomationEditorDraft(
            triggerChoice = AutomationTriggerChoice.THRESHOLD,
            thresholdValueText = "20"
        ).toRule(AutomationPreviewFixtures.scope)

        assertTrue(triggerSummary(rule).contains("20 度"))
        assertFalse(triggerSummary(rule).contains("2000 分"))
    }

    @Test
    fun commuteConditionIncludesExplicitOriginCampusAndMinimum() {
        val draft = AutomationEditorDraft(
            commuteTime = true,
            commuteFromCampusText = "haut-lianhuajie",
            commuteMinMinutesText = "1"
        )

        val condition = draft.toRule(AutomationPreviewFixtures.scope).conditions.first {
            it.type == BuiltinAutomationTypes.CONDITION_COMMUTE_TIME
        }

        assertEquals("haut-lianhuajie", condition.config.str("fromCampus"))
        assertEquals(1, condition.config.obj("minMinutes")?.intOrNull())
    }

    @Test
    fun personalEventActionIsAvailableAlongsideTask() {
        val draft = AutomationEditorDraft(createEvent = true, eventDurationMinutesText = "45")

        val actions = draft.toRule(AutomationPreviewFixtures.scope).actions
        val createEvent = actions.first { it.type == BuiltinAutomationTypes.ACTION_CREATE_PERSONAL_EVENT }

        assertEquals("PERSONAL", createEvent.config.str("kind"))
        assertEquals("45", createEvent.config.obj("durationMinutes")?.intOrNull()?.toString())
    }

    @Test
    fun weatherTemplateRoundTripKeepsTermsConsentExplicitlyFalse() {
        val templateRule = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_EARLY_RAIN_UMBRELLA)!!
            .instantiate(AutomationPreviewFixtures.scope)

        val draft = draftFromRule(templateRule)
        val rebuilt = draft.toRule(AutomationPreviewFixtures.scope)
        val weather = rebuilt.conditions.single { it.type == BuiltinAutomationTypes.CONDITION_WEATHER_PRECIPITATION }

        assertEquals(BuiltinAutomationTemplates.TEMPLATE_EARLY_RAIN_UMBRELLA, rebuilt.templateId)
        assertFalse(rebuilt.enabled)
        assertTrue(draft.weatherPrecipitation)
        assertFalse(draft.weatherTermsAccepted)
        assertEquals(false, weather.config.obj("termsAccepted")?.boolOrNull())
    }

    @Test
    fun utilityTemplateRoundTripKeepsLowBalanceConditionEditable() {
        val templateRule = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_FRIDAY_UTILITY_LOW_BALANCE)!!
            .instantiate(AutomationPreviewFixtures.scope)

        val draft = draftFromRule(templateRule)
        val rebuilt = draft.toRule(AutomationPreviewFixtures.scope)
        val utility = rebuilt.conditions.single { it.type == BuiltinAutomationTypes.CONDITION_UTILITY_BALANCE }

        assertTrue(draft.utilityBalance)
        assertEquals("20", draft.utilityBalanceValueText)
        assertEquals("2000", utility.config.obj("value")?.literalOrNull())
    }

    @Test
    fun cancellationTemplateRoundTripKeepsEventFilterAndCondition() {
        val templateRule = BuiltinAutomationTemplates.byId(BuiltinAutomationTemplates.TEMPLATE_COURSE_CANCELLED_STUDY)!!
            .instantiate(AutomationPreviewFixtures.scope)

        val draft = draftFromRule(templateRule)
        val rebuilt = draft.toRule(AutomationPreviewFixtures.scope)

        assertTrue(draft.courseCancellation)
        assertTrue(rebuilt.trigger is TriggerSpec.OnEvent)
        assertEquals("UPDATED", (rebuilt.trigger as TriggerSpec.OnEvent).filter?.str("change"))
        assertTrue(rebuilt.conditions.any { it.type == BuiltinAutomationTypes.CONDITION_COURSE_CANCELLATION })
    }

    @Test
    fun previewFixturesCoverCompleteElevenStateMatrix() {
        assertEquals(11, AutomationPreviewFixtures.stateMatrix.size)
        assertEquals(AutomationScreenStatus.entries.toSet(), AutomationPreviewFixtures.stateMatrix.keys)
    }
}

private fun cn.edu.haut.campus.core.model.json.JsonValue.literalOrNull(): String? =
    (this as? cn.edu.haut.campus.core.model.json.JsonValue.Num)?.literal

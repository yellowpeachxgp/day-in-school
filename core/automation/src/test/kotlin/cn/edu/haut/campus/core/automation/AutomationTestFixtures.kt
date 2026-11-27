package cn.edu.haut.campus.core.automation

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

internal object AutomationTestFixtures {
    val scope = SchoolAccountScope(
        schoolId = SchoolId("haut"),
        accountId = AccountId("account"),
        profileId = ProfileId("profile"),
        campusId = CampusId("campus")
    )

    fun rule(
        id: String = "rule-1",
        enabled: Boolean = true,
        trigger: TriggerSpec = TriggerSpec.AtTime(
            local = LocalTime.of(12, 0),
            days = setOf(DayOfWeek.MONDAY)
        ),
        conditions: List<ConditionSpec> = listOf(ConditionSpec("test.condition")),
        actions: List<ActionSpec> = listOf(ActionSpec("test.action")),
        dedupe: DedupePolicy = DedupePolicy.OncePerTriggerInstance,
        quietHours: QuietHours? = null
    ): AutomationRule = AutomationRule(
        id = id,
        scope = scope,
        name = "测试规则",
        enabled = enabled,
        trigger = trigger,
        conditions = conditions,
        actions = actions,
        dedupe = dedupe,
        quietHours = quietHours
    )

    fun registryWith(
        triggerTypes: Set<String> = setOf(TriggerSpec.AtTime.TYPE),
        conditionEvaluator: ConditionEvaluator? = RecordingConditionEvaluator(),
        actionExecutor: ActionExecutor? = RecordingActionExecutor()
    ): AutomationExtensionRegistry {
        val registry = AutomationExtensionRegistry()
        triggerTypes.forEach { registry.registerTriggerProvider(NoopTriggerProvider(it)) }
        conditionEvaluator?.let(registry::registerConditionEvaluator)
        actionExecutor?.let(registry::registerActionExecutor)
        return registry
    }

    class NoopTriggerProvider(
        override val type: String
    ) : TriggerProvider {
        override val configSchema: JsonSchema = JsonSchema.EMPTY_OBJECT

        override fun arm(rule: AutomationRule, ctx: ArmContext): ArmHandle = object : ArmHandle {
            override val id: String = "noop-arm-" + rule.id
            override suspend fun disarm() = Unit
        }
    }

    class RecordingConditionEvaluator(
        override val type: String = "test.condition",
        results: List<ConditionResult> = listOf(
            ConditionResult(conditionType = type, passed = true)
        ),
        override val configSchema: JsonSchema = JsonSchema.EMPTY_OBJECT
    ) : ConditionEvaluator {
        private val remaining = ConcurrentLinkedQueue(results)
        val calls = CopyOnWriteArrayList<ConditionSpec>()
        val contexts = CopyOnWriteArrayList<RunContext>()

        override suspend fun evaluate(spec: ConditionSpec, ctx: RunContext): ConditionResult {
            calls += spec
            contexts += ctx
            return remaining.poll() ?: ConditionResult(conditionType = type, passed = true)
        }
    }

    class RecordingActionExecutor(
        override val type: String = "test.action",
        override val risk: ActionRisk = ActionRisk.LOCAL_SILENT,
        private val outcome: ActionOutcome? = null,
        override val configSchema: JsonSchema = JsonSchema.EMPTY_OBJECT
    ) : ActionExecutor {
        val calls = CopyOnWriteArrayList<ActionSpec>()
        val contexts = CopyOnWriteArrayList<RunContext>()

        override suspend fun execute(spec: ActionSpec, ctx: RunContext): ActionOutcome {
            calls += spec
            contexts += ctx
            return outcome ?: ActionOutcome(
                actionType = type,
                risk = risk,
                status = ActionOutcomeStatus.SUCCESS,
                detail = "记录动作执行"
            )
        }
    }
}

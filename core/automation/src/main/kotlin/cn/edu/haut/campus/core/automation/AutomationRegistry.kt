package cn.edu.haut.campus.core.automation

import java.util.concurrent.ConcurrentHashMap

class DuplicateAutomationExtensionException(kind: String, type: String) :
    IllegalArgumentException("重复注册自动化" + kind + ": " + type)

class UnknownAutomationExtensionException(kind: String, type: String) :
    IllegalArgumentException("未注册的自动化" + kind + ": " + type)

class ExternalWriteForbiddenActionException(type: String) :
    IllegalArgumentException("EXTERNAL_WRITE_FORBIDDEN 动作禁止注册到自动化: " + type)

/**
 * 自动化 SPI 注册表。
 *
 * 注册表是安全边界：type 必须带命名空间且唯一；ActionRisk.EXTERNAL_WRITE_FORBIDDEN 一律拒绝注册。
 * ConcurrentHashMap 快照语义允许插件在运行期间注册/卸载，引擎每次运行读取当前快照，不缓存执行器。
 */
class AutomationExtensionRegistry(
    initialTriggerProviders: List<TriggerProvider> = emptyList(),
    initialConditionEvaluators: List<ConditionEvaluator> = emptyList(),
    initialActionExecutors: List<ActionExecutor> = emptyList()
) {
    private val triggerProviders = ConcurrentHashMap<String, TriggerProvider>()
    private val conditionEvaluators = ConcurrentHashMap<String, ConditionEvaluator>()
    private val actionExecutors = ConcurrentHashMap<String, ActionExecutor>()

    init {
        initialTriggerProviders.forEach(::registerTriggerProvider)
        initialConditionEvaluators.forEach(::registerConditionEvaluator)
        initialActionExecutors.forEach(::registerActionExecutor)
    }

    fun registerTriggerProvider(provider: TriggerProvider) {
        requireAutomationType(provider.type, "trigger type")
        if (triggerProviders.putIfAbsent(provider.type, provider) != null) {
            throw DuplicateAutomationExtensionException("触发器", provider.type)
        }
    }

    fun unregisterTriggerProvider(type: String): Boolean = triggerProviders.remove(type) != null

    fun triggerProvider(type: String): TriggerProvider? = triggerProviders[type]

    fun requireTriggerProvider(type: String): TriggerProvider =
        triggerProvider(type) ?: throw UnknownAutomationExtensionException("触发器", type)

    fun triggerTypes(): Set<String> = triggerProviders.keys.toSet()

    fun registerConditionEvaluator(evaluator: ConditionEvaluator) {
        requireAutomationType(evaluator.type, "condition type")
        if (conditionEvaluators.putIfAbsent(evaluator.type, evaluator) != null) {
            throw DuplicateAutomationExtensionException("条件", evaluator.type)
        }
    }

    fun unregisterConditionEvaluator(type: String): Boolean = conditionEvaluators.remove(type) != null

    fun conditionEvaluator(type: String): ConditionEvaluator? = conditionEvaluators[type]

    fun requireConditionEvaluator(type: String): ConditionEvaluator =
        conditionEvaluator(type) ?: throw UnknownAutomationExtensionException("条件", type)

    fun conditionTypes(): Set<String> = conditionEvaluators.keys.toSet()

    fun registerActionExecutor(executor: ActionExecutor) {
        requireAutomationType(executor.type, "action type")
        if (executor.risk == ActionRisk.EXTERNAL_WRITE_FORBIDDEN) {
            throw ExternalWriteForbiddenActionException(executor.type)
        }
        if (actionExecutors.putIfAbsent(executor.type, executor) != null) {
            throw DuplicateAutomationExtensionException("动作", executor.type)
        }
    }

    fun unregisterActionExecutor(type: String): Boolean = actionExecutors.remove(type) != null

    fun actionExecutor(type: String): ActionExecutor? = actionExecutors[type]

    fun requireActionExecutor(type: String): ActionExecutor =
        actionExecutor(type) ?: throw UnknownAutomationExtensionException("动作", type)

    fun actionTypes(): Set<String> = actionExecutors.keys.toSet()
}

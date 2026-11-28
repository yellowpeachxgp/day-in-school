package cn.edu.haut.campus.feature.settings.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * 高隐私设置页 ViewModel 的账号串扰防线。
 *
 * 组合作用域 Owner 是第一道生命周期边界；本类作为独立防线继续监听
 * AccountSessionCoordinator：一旦发生切换或当前 Ready 会话不再是本实例绑定的
 * profile/generation，立即取消全部收集协程并把状态清空；竞态中晚到的
 * Flow 发射也会先对照当前会话快照再丢弃。
 *
 * 兼容 facade 构造仍做 repository 身份校验；新代码可使用标量会话构造，
 * 不再为了 profileId/schoolId 这两个值反向依赖完整 CampusRepository。
 */
abstract class ProfileSessionScopedViewModel private constructor(
    private val expectedProfileId: String,
    private val expectedGeneration: Long?,
    private val sessionOwner: Any?,
    private val isSessionActiveProvider: () -> Boolean,
    private val currentSessionGenerationProvider: () -> Long?,
    private val sessionStates: StateFlow<AccountSessionState>
) : ViewModel() {
    constructor(
        repository: CampusRepository,
        sessionStates: StateFlow<AccountSessionState>
    ) : this(
        expectedProfileId = repository.profileId,
        expectedGeneration = repository.sessionGeneration,
        sessionOwner = repository,
        isSessionActiveProvider = { repository.isSessionActive },
        currentSessionGenerationProvider = { repository.sessionGeneration },
        sessionStates = sessionStates
    )

    protected constructor(
        profileId: String,
        sessionGeneration: Long?,
        isSessionActive: () -> Boolean,
        sessionStates: StateFlow<AccountSessionState>
    ) : this(
        expectedProfileId = profileId,
        expectedGeneration = sessionGeneration,
        sessionOwner = null,
        isSessionActiveProvider = isSessionActive,
        currentSessionGenerationProvider = { sessionGeneration },
        sessionStates = sessionStates
    )

    private val sessionOwnedJob: Job = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val sessionOwnedScope: CoroutineScope = CoroutineScope(viewModelScope.coroutineContext + sessionOwnedJob)

    @Volatile
    private var sessionInvalidated: Boolean = false

    init {
        viewModelScope.launch {
            // 避免在基类构造期间同步回调尚未完成初始化的子类；同时仍能在下一次调度点尽快失配。
            yield()
            sessionStates.first { state -> shouldInvalidateFor(state) }
            invalidateSession()
        }
    }

    protected fun <T> launchSessionCollect(
        flow: Flow<T>,
        collector: (T) -> Unit
    ): Job = sessionOwnedScope.launch {
        flow.collectLatest { value ->
            if (isSessionValidForEmission()) {
                collector(value)
            }
        }
    }

    protected fun launchSessionJob(
        block: suspend CoroutineScope.() -> Unit
    ): Job = sessionOwnedScope.launch(block = block)

    protected fun isSessionValidForEmission(): Boolean =
        !sessionInvalidated &&
            !shouldInvalidateFor(sessionStates.value) &&
            isSessionActiveProvider() &&
            expectedGeneration != null &&
            currentSessionGenerationProvider() == expectedGeneration

    private fun shouldInvalidateFor(state: AccountSessionState): Boolean = when (state) {
        AccountSessionState.Loading -> false
        is AccountSessionState.Switching -> true
        is AccountSessionState.Failure -> true
        is AccountSessionState.Ready -> !matchesOwnedSession(state)
    }

    private fun matchesOwnedSession(state: AccountSessionState.Ready): Boolean {
        val active = state.active
        val identityMatches = expectedGeneration != null &&
            active.profileId == expectedProfileId &&
            active.generation == expectedGeneration
        return identityMatches && (sessionOwner == null || active.repository === sessionOwner)
    }

    private fun invalidateSession() {
        if (sessionInvalidated) return
        sessionInvalidated = true
        sessionOwnedJob.cancel()
        resetStateForInvalidSession()
    }

    /** 子类必须把含账号数据的快照恢复到无敏感内容初始值。 */
    protected abstract fun resetStateForInvalidSession()

    override fun onCleared() {
        sessionOwnedJob.cancel()
        super.onCleared()
    }
}

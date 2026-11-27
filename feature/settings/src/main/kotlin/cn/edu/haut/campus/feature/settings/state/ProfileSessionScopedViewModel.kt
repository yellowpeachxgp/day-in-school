package cn.edu.haut.campus.feature.settings.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.core.database.CampusRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 高隐私设置页 ViewModel 的账号串扰防线。
 *
 * ViewModel key 会让新 Profile/generation 得到新实例，但 AndroidViewModelStore 不会在
 * 底栏重组时主动清空旧实例。这里监听 AccountSessionCoordinator：一旦发生切换或当前
 * Ready 会话不再是本实例绑定的 repository/profileId/generation，立即取消全部收集协程
 * 并把状态清空；竞态中晚到的 Flow 发射也会被丢弃。
 */
internal abstract class ProfileSessionScopedViewModel(
    private val repository: CampusRepository,
    sessionStates: Flow<AccountSessionState>
) : ViewModel() {
    private val expectedProfileId: String = repository.profileId
    private val expectedGeneration: Long? = repository.sessionGeneration
    private val sessionOwnedJob: Job = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val sessionOwnedScope: CoroutineScope = CoroutineScope(viewModelScope.coroutineContext + sessionOwnedJob)

    @Volatile
    private var sessionInvalidated: Boolean = false

    init {
        viewModelScope.launch {
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
            repository.isSessionActive &&
            expectedGeneration != null &&
            repository.sessionGeneration == expectedGeneration

    private fun shouldInvalidateFor(state: AccountSessionState): Boolean = when (state) {
        AccountSessionState.Loading -> false
        is AccountSessionState.Switching -> true
        is AccountSessionState.Failure -> true
        is AccountSessionState.Ready -> !matchesOwnedSession(state)
    }

    private fun matchesOwnedSession(state: AccountSessionState.Ready): Boolean {
        val active = state.active
        return expectedGeneration != null &&
            active.profileId == expectedProfileId &&
            active.generation == expectedGeneration &&
            active.repository === repository
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

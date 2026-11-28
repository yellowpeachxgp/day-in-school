package cn.edu.haut.campus.feature.today.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import cn.edu.haut.campus.feature.today.state.TodayWriteCompletion
import cn.edu.haut.campus.feature.today.state.TodayWriteKind
import java.util.UUID

/**
 * 一个对话框/一行操作自己的写入等待状态。
 * kind 只作诊断；完成结果必须用 begin() 返回的一次性 token 关联，不能把同 kind 的旧结果当成本次。
 */
internal class TodayWriteLifecycle {
    var activeToken by mutableStateOf<String?>(null)
        private set
    var activeKind by mutableStateOf<TodayWriteKind?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val pending: Boolean get() = activeToken != null

    /** 快速重复点击复用同一 token；ViewModel 会按 token 去重，绝不把重复点击当两次写。 */
    fun begin(kind: TodayWriteKind): String {
        activeToken?.let { return it }
        val token = kind.name + ":" + UUID.randomUUID().toString()
        activeToken = token
        activeKind = kind
        errorMessage = null
        return token
    }

    fun finishSuccess() {
        activeToken = null
        activeKind = null
        errorMessage = null
    }

    fun finishFailure(message: String) {
        activeToken = null
        activeKind = null
        errorMessage = message
    }
}

@Composable
internal fun rememberTodayWriteLifecycle(
    key: Any?,
    writeCompletions: Map<String, TodayWriteCompletion>,
    onWriteSuccess: () -> Unit = {},
    onConsumeCompletion: (String) -> Unit = {}
): TodayWriteLifecycle {
    val lifecycle = remember(key) { TodayWriteLifecycle() }
    val currentOnWriteSuccess by rememberUpdatedState(onWriteSuccess)
    val currentOnConsumeCompletion by rememberUpdatedState(onConsumeCompletion)
    val activeCompletion = lifecycle.activeToken?.let { token -> writeCompletions[token] }
        ?.takeIf { it.token == lifecycle.activeToken }
    val completionToken = activeCompletion?.token
    LaunchedEffect(completionToken, key) {
        val completion = activeCompletion ?: return@LaunchedEffect
        if (lifecycle.activeToken != completion.token) return@LaunchedEffect
        currentOnConsumeCompletion(completion.token)
        if (completion.isSuccess) {
            lifecycle.finishSuccess()
            currentOnWriteSuccess()
        } else {
            lifecycle.finishFailure(completion.errorMessage ?: "写入失败，请重试")
        }
    }
    return lifecycle
}

package cn.edu.haut.campus.feature.timetable

import android.content.Context
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.widget.common.WidgetRefreshCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 在账号会话锁内执行写入；返回 null 表示 profileId/generation 已不是当前会话。 */
internal interface TimetableSessionWriteRunner {
    suspend fun <T : Any> withActiveSessionWrite(
        profileId: String,
        sessionGeneration: Long,
        write: suspend () -> T
    ): T?
}

internal class TimetableSessionMismatchWriteException : IllegalStateException(
    "账号已切换，写入未执行；请在当前账号重新操作"
)

/**
 * 课表页用户写入的耐用边界。
 *
 * 写入 job 不属于组合作用域 ViewModel：对话框关闭、底栏切换或 ViewModelStore 清理都不会取消
 * 已经接收的写入。真正执行前仍经 AccountSessionCoordinator.withActiveSession 校验，
 * 该校验与账号切换共用互斥锁，因此不会把旧会话写入跨进新会话。
 */
internal class TimetableWriteExecutor(
    private val writeScope: CoroutineScope,
    private val runner: TimetableSessionWriteRunner,
    private val afterSuccessfulWrite: () -> Unit = {}
) {
    fun <T : Any> enqueue(
        profileId: String,
        sessionGeneration: Long,
        write: suspend () -> T,
        onComplete: (Result<T>) -> Unit
    ): Job? {
        if (profileId.isBlank() || sessionGeneration <= 0L) return null
        return writeScope.launch {
            val result = try {
                val written = runner.withActiveSessionWrite(profileId, sessionGeneration, write)
                if (written == null) {
                    Result.failure<T>(TimetableSessionMismatchWriteException())
                } else {
                    Result.success(written)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Result.failure<T>(error)
            }
            if (result.isSuccess) {
                runCatching { afterSuccessfulWrite() }
            }
            onComplete(result)
        }
    }

    companion object {
        /** 只承载有限用户写 job；不保留 UI/ViewModel，作用域本身不随组合销毁。 */
        private val processWriteScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun android(context: Context): TimetableWriteExecutor {
            val appContext = context.applicationContext
            val sessions = AccountSessionCoordinator.getInstance(appContext)
            return TimetableWriteExecutor(
                writeScope = processWriteScope,
                runner = object : TimetableSessionWriteRunner {
                    override suspend fun <T : Any> withActiveSessionWrite(
                        profileId: String,
                        sessionGeneration: Long,
                        write: suspend () -> T
                    ): T? = sessions.withActiveSession(profileId, sessionGeneration) {
                        write()
                    }
                },
                afterSuccessfulWrite = {
                    WidgetRefreshCoordinator.requestImmediateRefreshAll(appContext)
                }
            )
        }
    }
}

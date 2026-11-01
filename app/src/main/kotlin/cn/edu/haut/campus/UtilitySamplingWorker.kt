package cn.edu.haut.campus

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.ActiveSession
import cn.edu.haut.campus.core.reminder.AccountBackgroundScope
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * 宿舍水电后台低频周期采样任务，按账号 profile/generation 隔离。
 *
 * 采样窗口与幂等跳过规则由 [cn.edu.haut.campus.core.campusservices.UtilitySamplingPolicy] 严格控制：
 * - 每日 06:00 前不采样
 * - 距上次采样不足 45 分钟不重复请求
 * - 跨日 00:00–00:05 基准漂移丢弃
 */
class UtilitySamplingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val profileId = inputData.getString(AccountBackgroundScope.EXTRA_PROFILE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val epoch = inputData.getLong(AccountBackgroundScope.EXTRA_EPOCH, -1L)
            .takeIf { it > 0L }
            ?: return Result.success()
        val sessions = AccountSessionCoordinator.getInstance(applicationContext)
        return try {
            sessions.initialize()
            sessions.withActiveSession(profileId, epoch) { active ->
                active.repository.performPeriodicUtilitySampleIfEligible()
            }
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val UNIQUE_WORK_NAME_PREFIX = "dayinschool.utility.sampling.v2"
        private const val LEGACY_UNIQUE_WORK_NAME = "dayinschool.utility.sampling.v1"

        private fun uniqueWorkName(accountScope: AccountBackgroundScope): String =
            "$UNIQUE_WORK_NAME_PREFIX:${accountScope.profileId}:${accountScope.epoch}"

        fun schedulePeriodicWork(context: Context, session: ActiveSession) {
            val appContext = context.applicationContext
            val accountScope = AccountBackgroundScope.from(session)
            session.repository.registerBeforeWipeHook("utility_sampling_worker_hook") {
                cancelPeriodicWork(appContext, accountScope)
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<UtilitySamplingWorker>(
                60L,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(
                    workDataOf(
                        AccountBackgroundScope.EXTRA_PROFILE_ID to accountScope.profileId,
                        AccountBackgroundScope.EXTRA_EPOCH to accountScope.epoch
                    )
                )
                .build()
            runCatching {
                val workManager = WorkManager.getInstance(appContext)
                workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK_NAME)
                workManager.enqueueUniquePeriodicWork(
                    uniqueWorkName(accountScope),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
            }
        }

        suspend fun cancelPeriodicWork(context: Context, session: ActiveSession) {
            cancelPeriodicWork(context, AccountBackgroundScope.from(session))
        }

        private suspend fun cancelPeriodicWork(context: Context, accountScope: AccountBackgroundScope) {
            val workManager = WorkManager.getInstance(context.applicationContext)
            runCatching { workManager.cancelUniqueWork(uniqueWorkName(accountScope)) }
            runCatching { workManager.cancelUniqueWork(LEGACY_UNIQUE_WORK_NAME) }
        }
    }
}

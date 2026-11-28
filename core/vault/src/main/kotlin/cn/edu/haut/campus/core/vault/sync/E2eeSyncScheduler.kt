package cn.edu.haut.campus.core.vault.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cn.edu.haut.campus.core.database.AccountSessionCoordinator
import cn.edu.haut.campus.core.database.ActiveSession
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** 周期补处理 Worker：只在用户显式启用 E2EE 同步后运行；不是通知或保活机制。 */
class E2eeSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val profileId = inputData.getString(KEY_PROFILE_ID)?.takeIf { it.isNotBlank() } ?: return Result.success()
        val generation = inputData.getLong(KEY_GENERATION, -1L).takeIf { it > 0L } ?: return Result.success()
        return try {
            val sessions = AccountSessionCoordinator.getInstance(applicationContext)
            sessions.initialize()
            val outcome = sessions.withActiveSession(profileId, generation) { active ->
                val client = E2eeSyncClientFactory.create(applicationContext, active.repository)
                    ?: return@withActiveSession "unsupported"
                if (!client.status().enabled) return@withActiveSession "disabled"
                client.runOnce()
                "ok"
            } ?: "stale-account"
            when (outcome) {
                "ok", "disabled", "unsupported", "stale-account" -> Result.success()
                else -> Result.success()
            }
        } catch (error: Throwable) {
            if (error is CancellationException || error is Error) throw error
            val retryable = error is IOException ||
                (error as? E2eeSyncApiException)?.uncertainOutcome == true ||
                error !is E2eeSyncApiException
            if (retryable && runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        internal const val KEY_PROFILE_ID = "e2ee_sync_profile_id"
        internal const val KEY_GENERATION = "e2ee_sync_generation"
    }
}

object E2eeSyncScheduler {
    private const val PERIODIC_PREFIX = "dayinschool.e2ee-sync.periodic.v1"
    private const val IMMEDIATE_PREFIX = "dayinschool.e2ee-sync.immediate.v1"

    suspend fun rescheduleForActiveSession(context: Context, active: ActiveSession) {
        cancel(context, active.profileId, active.generation)
        val client = E2eeSyncClientFactory.create(context, active.repository) ?: return
        if (!runCatching { client.status().enabled }.getOrDefault(false)) return
        schedulePeriodicEnabled(context, active.profileId, active.generation)
    }

    /** 启用成功后的即时周期登记；调用方必须先确认 enabled，避免未经同意产生云端复制。 */
    fun schedulePeriodicEnabled(context: Context, profileId: String, generation: Long) {
        val request = PeriodicWorkRequestBuilder<E2eeSyncWorker>(60L, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.MINUTES)
            .setInputData(workDataOf(E2eeSyncWorker.KEY_PROFILE_ID to profileId, E2eeSyncWorker.KEY_GENERATION to generation))
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                periodicName(profileId, generation),
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }

    fun enqueueImmediate(context: Context, active: ActiveSession) =
        enqueueImmediate(context, active.profileId, active.generation)

    fun enqueueImmediate(context: Context, profileId: String, generation: Long) {
        val request = OneTimeWorkRequestBuilder<E2eeSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(E2eeSyncWorker.KEY_PROFILE_ID to profileId, E2eeSyncWorker.KEY_GENERATION to generation))
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                immediateName(profileId, generation),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun cancel(context: Context, profileId: String, generation: Long? = null) {
        val manager = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull() ?: return
        if (generation != null) {
            runCatching { manager.cancelUniqueWork(periodicName(profileId, generation)) }
            runCatching { manager.cancelUniqueWork(immediateName(profileId, generation)) }
        } else {
            // WorkManager 不提供按前缀批量取消；账号世代在注册处总是已知。这里取消当前世代外，profileId 摘要目录擦除仍保证旧任务 fail-closed。
        }
    }

    private fun periodicName(profileId: String, generation: Long) = PERIODIC_PREFIX + ":" + profileId + ":" + generation
    private fun immediateName(profileId: String, generation: Long) = IMMEDIATE_PREFIX + ":" + profileId + ":" + generation
}

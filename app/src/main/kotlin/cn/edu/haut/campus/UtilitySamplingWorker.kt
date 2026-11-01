package cn.edu.haut.campus

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.edu.haut.campus.core.database.CampusRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 宿舍水电后台低频周期采样任务（uniqueWorkName = dayinschool.utility.sampling.v1）。
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
        return runCatching {
            val repository = CampusRepository.getInstance(applicationContext)
            repository.performPeriodicUtilitySampleIfEligible()
            Result.success()
        }.getOrElse {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "dayinschool.utility.sampling.v1"
        private val wipeHookRegistered = AtomicBoolean(false)

        fun schedulePeriodicWork(context: Context) {
            val appContext = context.applicationContext
            if (wipeHookRegistered.compareAndSet(false, true)) {
                CampusRepository.getInstance(appContext).registerBeforeWipeHook("utility_sampling_worker_hook") {
                    runCatching {
                        WorkManager.getInstance(appContext).cancelUniqueWork(UNIQUE_WORK_NAME)
                    }
                }
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<UtilitySamplingWorker>(
                60L,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()
            runCatching {
                WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            }
        }
    }
}

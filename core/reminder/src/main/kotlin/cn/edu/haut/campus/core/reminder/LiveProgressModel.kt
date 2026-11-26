package cn.edu.haut.campus.core.reminder

import java.math.BigInteger
import java.time.Duration
import java.time.Instant

/**
 * 课程实况进度的纯时间模型。
 *
 * 输出只依赖传入时间，不读取系统时钟、Android 状态或通知 extras；课前与课中是两个独立阶段：
 * - 课前窗口 [startAt - leadWindow, startAt)：进度表示等待进入上课的完成度；
 * - 课中窗口 [startAt, endAt)：进度表示本节课已经进行的完成度；
 * - endAt 是半开区间端点，到达或越过 endAt 后不再存在可展示的实况进度。
 */
object LiveProgressModel {
    const val PROGRESS_SCALE: Int = 10_000

    enum class Phase {
        BEFORE_CLASS,
        IN_CLASS
    }

    data class ProgressSnapshot(
        val phase: Phase,
        val elapsedInPhaseMs: Long,
        val totalInPhaseMs: Long,
        val remainingMs: Long,
        val progressUnits: Int,
        val progressMax: Int = PROGRESS_SCALE,
        val countdownTargetEpochMs: Long
    )

    fun compute(
        now: Instant,
        startAt: Instant,
        endAt: Instant,
        leadWindow: Duration
    ): ProgressSnapshot? {
        if (leadWindow.isNegative) return null
        val classDurationMs = Duration.between(startAt, endAt).toMillis()
        if (classDurationMs <= 0L) return null

        val windowStart = startAt.minus(leadWindow)
        if (now.isBefore(windowStart) || !now.isBefore(endAt)) return null

        return if (now.isBefore(startAt)) {
            val leadMs = leadWindow.toMillis()
            if (leadMs <= 0L) {
                null
            } else {
                val elapsedMs = Duration.between(windowStart, now).toMillis()
                ProgressSnapshot(
                    phase = Phase.BEFORE_CLASS,
                    elapsedInPhaseMs = elapsedMs,
                    totalInPhaseMs = leadMs,
                    remainingMs = Duration.between(now, startAt).toMillis(),
                    progressUnits = scaledProgress(elapsedMs, leadMs),
                    countdownTargetEpochMs = startAt.toEpochMilli()
                )
            }
        } else {
            val elapsedMs = Duration.between(startAt, now).toMillis()
            ProgressSnapshot(
                phase = Phase.IN_CLASS,
                elapsedInPhaseMs = elapsedMs,
                totalInPhaseMs = classDurationMs,
                remainingMs = Duration.between(now, endAt).toMillis(),
                progressUnits = scaledProgress(elapsedMs, classDurationMs),
                countdownTargetEpochMs = endAt.toEpochMilli()
            )
        }
    }

    /** 以毫秒真实比例折算到通知进度上限，避免旧实现按整分钟截断造成长时间不动。 */
    private fun scaledProgress(elapsedMs: Long, totalMs: Long): Int {
        if (elapsedMs <= 0L) return 0
        if (elapsedMs >= totalMs) return PROGRESS_SCALE
        val numerator = BigInteger.valueOf(elapsedMs).multiply(BigInteger.valueOf(PROGRESS_SCALE.toLong()))
        return numerator.divide(BigInteger.valueOf(totalMs)).toInt()
    }
}

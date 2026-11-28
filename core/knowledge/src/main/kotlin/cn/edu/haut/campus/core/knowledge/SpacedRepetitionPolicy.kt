package cn.edu.haut.campus.core.knowledge

import java.time.Duration
import java.time.Instant

/**
 * dis-spaced-repetition/v1：本地、确定性的间隔重复策略。
 *
 * 掌握度越低，复习间隔越短、轮次越多；每次漏复习通过 lapseCount 额外降低 10 点有效掌握度。
 * estimatedMinutes 表示该任务本轮总复习投入，策略会按实际轮次均分，并按求解网格 30 分钟向上取整。
 * 该算法不联网、不调用 AI，也不把漏做任务静默删除。
 */
object SpacedRepetitionPolicy {
    const val ALGORITHM_ID: String = "dis-spaced-repetition/v1"
    const val MAX_SESSIONS_PER_TASK: Int = 6
    const val DEFAULT_TOTAL_MINUTES: Int = 60
    val QUANTUM: Duration = Duration.ofMinutes(30)

    fun effectiveMasteryPercent(task: ReviewTask): Int =
        (task.masteryPercent - task.lapseCount * 10).coerceIn(0, 100)

    /** 从首次学习开始的理想间隔阶梯。 */
    fun intervalLadder(effectiveMasteryPercent: Int): List<Duration> {
        require(effectiveMasteryPercent in 0..100) { "有效掌握度必须在 0..100" }
        return when {
            effectiveMasteryPercent < 30 -> listOf(
                Duration.ZERO,
                Duration.ofDays(1),
                Duration.ofDays(3),
                Duration.ofDays(7)
            )
            effectiveMasteryPercent < 60 -> listOf(
                Duration.ZERO,
                Duration.ofDays(2),
                Duration.ofDays(6)
            )
            effectiveMasteryPercent < 85 -> listOf(
                Duration.ZERO,
                Duration.ofDays(3),
                Duration.ofDays(8)
            )
            else -> listOf(
                Duration.ZERO,
                Duration.ofDays(7)
            )
        }
    }

    /**
     * 计算 deadline 之前的理想复习时间。已经成功复习过的任务从 lastReviewedAt 继续推进；
     * 阶梯用完后按最后间隔翻倍延续，最多保留 MAX_SESSIONS_PER_TASK 次，避免无限任务扩张。
     */
    fun targetInstants(task: ReviewTask, start: Instant, deadline: Instant): List<Instant> {
        require(deadline.isAfter(start)) { "复习排程截止必须晚于排程起点" }
        val ladder = intervalLadder(effectiveMasteryPercent(task))
        val targets = ArrayList<Instant>()
        if (task.successfulReviewCount <= 0 || task.lastReviewedAt == null) {
            for (offset in ladder) {
                val target = start.plus(offset)
                if (target.isBefore(deadline)) targets += target
            }
            if (targets.isEmpty()) targets += start
            return targets.take(MAX_SESSIONS_PER_TASK)
        }

        var nextIndex = task.successfulReviewCount.coerceAtMost(ladder.size)
        var previousTarget = checkNotNull(task.lastReviewedAt) { "有成功复习次数时 lastReviewedAt 不能为空" }
        var extensionGap = if (ladder.size >= 2) ladder.last().minus(ladder[ladder.lastIndex - 1]) else Duration.ofDays(1)
        if (extensionGap.isZero || extensionGap.isNegative) extensionGap = Duration.ofDays(1)
        while (targets.size < MAX_SESSIONS_PER_TASK) {
            val gap = if (nextIndex < ladder.size) {
                if (nextIndex == 0) ladder[0] else ladder[nextIndex].minus(ladder[nextIndex - 1])
            } else {
                extensionGap
            }
            var target = previousTarget.plus(gap)
            if (target.isBefore(start)) target = start
            if (!target.isBefore(deadline)) break
            if (targets.lastOrNull() != target) {
                targets += target
                previousTarget = target
            }
            if (nextIndex >= ladder.size) {
                extensionGap = extensionGap.multipliedBy(2)
            }
            nextIndex++
            if (gap.isZero && target == start && targets.isNotEmpty()) break
        }
        if (targets.isEmpty()) targets += start
        return targets
    }

    /** 将总估时均分到本轮实际次数，并按 30 分钟求解量子向上取整。 */
    fun roundedSessionMinutes(estimatedMinutes: Int?, sessionCount: Int): Int {
        require(sessionCount in 1..MAX_SESSIONS_PER_TASK) { "复习轮次非法" }
        val total = estimatedMinutes ?: DEFAULT_TOTAL_MINUTES
        require(total in 1..24 * 60) { "复习估时必须在 1 分钟到 24 小时" }
        val quantumMinutes = QUANTUM.toMinutes().toInt()
        val share = ceilDivInt(total, sessionCount)
        return ceilDivInt(share, quantumMinutes) * quantumMinutes
    }

    /** 实际间隔至少保留理想间隔的一半，最长要求 12 小时；零理想间隔只禁止重叠。 */
    fun minimumGapAfter(idealGap: Duration): Duration {
        if (idealGap.isZero || idealGap.isNegative) return Duration.ZERO
        val half = Duration.ofSeconds(idealGap.seconds / 2)
        return if (half > Duration.ofHours(12)) Duration.ofHours(12) else half
    }

    private fun ceilDivInt(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
}

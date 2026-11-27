package cn.edu.haut.campus.core.solver

/**
 * 楼宇间通勤估计。UNKNOWN 与 NOT_WITHIN_BREAK 都不能被求解器当作可达分钟数。
 */
sealed interface TravelTimeEstimate {
    data class Reachable(val minutes: Int) : TravelTimeEstimate {
        init { require(minutes >= 0) { "通勤分钟数不能为负" } }
    }

    /** 明确无法在普通课间完成；跨校区实现应优先返回该值或较大的 Reachable 分钟数。 */
    data object NotWithinBreak : TravelTimeEstimate

    /** 证据不足；默认保守拒绝，只有用户显式允许时才降级为警告。 */
    data object Unknown : TravelTimeEstimate
}

data class TravelPair(
    val from: SolverLocation,
    val to: SolverLocation
)

/**
 * 通勤矩阵端口。真实实现应由校园路网 A* 预计算并按楼宇对缓存；
 * 求解器只依赖该纯 JVM 接口，不直接依赖 Android、地图或网络实现。
 */
fun interface TravelTimeMatrix {
    fun estimate(from: SolverLocation, to: SolverLocation): TravelTimeEstimate
}

/** 确定性预计算矩阵；缺失楼宇对返回 [missing]，绝不猜测。 */
class PrecomputedTravelTimeMatrix(
    private val entries: Map<TravelPair, TravelTimeEstimate>,
    private val missing: TravelTimeEstimate = TravelTimeEstimate.Unknown
) : TravelTimeMatrix {
    override fun estimate(from: SolverLocation, to: SolverLocation): TravelTimeEstimate {
        if (from.campusId != null && from.buildingId != null && from == to) {
            return TravelTimeEstimate.Reachable(0)
        }
        return entries[TravelPair(from, to)] ?: missing
    }
}

/** 单线程求解过程内使用的楼宇对缓存包装；不跨 Profile 或学校共享。 */
class CachingTravelTimeMatrix(
    private val delegate: TravelTimeMatrix
) : TravelTimeMatrix {
    private val cache = HashMap<TravelPair, TravelTimeEstimate>()

    override fun estimate(from: SolverLocation, to: SolverLocation): TravelTimeEstimate {
        val key = TravelPair(from, to)
        return cache.getOrPut(key) { delegate.estimate(from, to) }
    }

    val cachedPairCount: Int get() = cache.size
}

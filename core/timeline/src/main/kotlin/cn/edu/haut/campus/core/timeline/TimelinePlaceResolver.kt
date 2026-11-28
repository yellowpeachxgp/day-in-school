package cn.edu.haut.campus.core.timeline

import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolAccountScope

/**
 * 将领域数据中的教室/楼号线索解析为 Campus Graph 地点引用。
 *
 * W1-07 的图数据尚未落地前，默认实现必须返回 null：Timeline 不伪造看似可寻址、
 * 实际没有图实体承诺的 PlaceRef。适配层或后续 campus-graph 模块可以注入真实实现。
 */
fun interface TimelinePlaceResolver {
    fun resolve(
        scope: SchoolAccountScope,
        rawRoom: String?,
        buildingId: String?,
        campusName: String?
    ): PlaceRef?

    companion object {
        val NONE: TimelinePlaceResolver = TimelinePlaceResolver { _, _, _, _ -> null }
    }
}

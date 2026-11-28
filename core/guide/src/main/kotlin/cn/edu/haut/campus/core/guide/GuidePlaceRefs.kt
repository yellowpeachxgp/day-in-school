package cn.edu.haut.campus.core.guide

import cn.edu.haut.campus.core.campusgraph.CampusGraphIds
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.PlaceRef
import cn.edu.haut.campus.core.model.SchoolId

internal object GuidePlaceRefs {
    fun parse(raw: String, schoolId: SchoolId, fieldLabel: String): PlaceRef {
        val segments = raw.split('/')
        if (segments.size != 4 || segments.any { it.isEmpty() }) {
            throw GuideDefinitionException(fieldLabel + " 必须是 Campus Graph 稳定 ID：" + raw)
        }
        if (segments[0] != schoolId.value) {
            throw GuideDefinitionException(fieldLabel + " 学校与数据包不一致：" + raw)
        }
        val ref = try {
            PlaceRef(raw, SchoolId(segments[0]), CampusId(segments[1]))
        } catch (error: IllegalArgumentException) {
            throw GuideDefinitionException(fieldLabel + " 不是合法 Campus Graph 稳定 ID：" + raw)
        }
        try {
            CampusGraphIds.typeOf(ref)
        } catch (error: IllegalArgumentException) {
            throw GuideDefinitionException(fieldLabel + " 使用未知 Campus Graph 类型：" + raw)
        }
        return ref
    }
}

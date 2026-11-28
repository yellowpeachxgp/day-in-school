package cn.edu.haut.campus.core.automation.android

import cn.edu.haut.campus.core.automation.CourseCancellationStatePort
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.OccurrenceCancellationSemantics
import cn.edu.haut.campus.core.model.SchoolAccountScope

/** Room 当前课程聚合停课状态；只读，不把历史触发事件当作当前事实。 */
class RoomCourseCancellationStatePort(
    private val database: HautDatabase,
    private val scope: SchoolAccountScope
) : CourseCancellationStatePort {
    override suspend fun hasActiveCancellation(
        scope: SchoolAccountScope,
        courseRef: EntityRef,
        overrideIds: Set<String>
    ): Boolean? {
        if (scope != this.scope || courseRef.scope != this.scope || courseRef.type != EntityType.COURSE) return null
        val dao = database.campusDao()
        val ruleIds = dao.getRulesSnapshot()
            .filter { it.courseId == courseRef.id }
            .mapTo(mutableSetOf()) { it.id }
        val courseRows = dao.getOccurrenceOverridesSnapshot().filter { row ->
            row.courseId == courseRef.id || row.courseRuleId in ruleIds
        }
        return if (overrideIds.isEmpty()) {
            courseRows.any(::isIndependentCancellation)
        } else {
            courseRows.any { it.id in overrideIds && isIndependentCancellation(it) }
        }
    }

    private fun isIndependentCancellation(row: cn.edu.haut.campus.core.database.OccurrenceOverrideEntity): Boolean =
        OccurrenceCancellationSemantics.isIndependentCancellation(
            canceled = row.canceled,
            moveId = row.moveId,
            moveRole = row.moveRole,
            reason = row.reason
        )
}

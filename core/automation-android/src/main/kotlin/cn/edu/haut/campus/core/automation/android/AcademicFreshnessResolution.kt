package cn.edu.haut.campus.core.automation.android

import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.model.ImportAudit

/** 当前学期新鲜度 = 同学期专用时间戳与同学期最新成功导入记录的较新者；其他学期一律不可用。 */
internal fun resolveAcademicDataFreshnessEpochMs(
    prefs: AppPreferencesState,
    currentTermId: String,
    importAudits: List<ImportAudit>
): Long? {
    val preferenceMs = if (prefs.lastAcademicDataSyncTermId == currentTermId) {
        prefs.lastAcademicDataSyncEpochMillis
    } else {
        null
    }
    val auditMs = importAudits
        .filter { it.termId == currentTermId }
        .maxOfOrNull { it.importedAt.toEpochMilli() }
    return listOfNotNull(preferenceMs, auditMs).maxOrNull()
}

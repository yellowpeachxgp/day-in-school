package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.events.CanonicalJson
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.json.Digests

/** dis-backup 行到 Vault EntityRef 的确定映射；id 使用 rowKey 摘要，避免把超长/敏感主键直接变成审计主键。 */
internal object BackupSyncEntityRefs {
    data class Target(
        val ref: EntityRef,
        val policy: BackupTablePolicy,
        val primaryKeyCells: Map<String, BackupCell>,
        val rowKey: String
    )

    fun target(policy: BackupTablePolicy, allCells: Map<String, BackupCell>, scope: SchoolAccountScope): Target {
        val primaryKeyCells = policy.primaryKeys.associateWith { column ->
            allCells[column] ?: throw BackupValidationException("同步行缺少主键列: " + policy.table + "." + column)
        }
        val rowKey = BackupRow(primaryKeyCells).primaryKey(policy)
        val rawSingleTextId = policy.primaryKeys.singleOrNull()?.let { column ->
            (primaryKeyCells[column] as? BackupCell.TextValue)?.value?.takeIf { it.isNotBlank() }
        }
        val id = if (policy.entityType != null && rawSingleTextId != null && isStableRefId(rawSingleTextId)) {
            rawSingleTextId
        } else {
            policy.table + ":" + Digests.sha256Hex(rowKey).take(40)
        }
        return Target(
            ref = EntityRef(typeFor(policy.table), id, scope),
            policy = policy,
            primaryKeyCells = primaryKeyCells,
            rowKey = rowKey
        )
    }

    fun canonicalSnapshot(row: BackupRow?): CanonicalJson? =
        row?.let { canonicalJsonOf(it.canonicalValue()) }

    private fun isStableRefId(value: String): Boolean =
        value.length in 1..256 &&
            value.none { it.isISOControl() || it.isWhitespace() } &&
            value.all { it.isLetterOrDigit() || it in "_-./:" }

    fun typeFor(table: String): EntityType = when (table) {
        "student_profile" -> EntityType.PROFILE
        "terms" -> EntityType.TERM
        "period_time_schemes" -> EntityType.PERIOD_TIME_SCHEME
        "period_times" -> EntityType.PERIOD_TIME
        "calendar_overrides" -> EntityType.CALENDAR_OVERRIDE
        "courses" -> EntityType.COURSE
        "course_schedule_rules" -> EntityType.COURSE_SCHEDULE_RULE
        "occurrence_overrides" -> EntityType.COURSE_OCCURRENCE
        "grade_attempts" -> EntityType.GRADE_ATTEMPT
        "gpa_policies" -> EntityType.GPA_POLICY
        "import_audits" -> EntityType.ACADEMIC_IMPORT_AUDIT
        "exam_schedules" -> EntityType.EXAM
        "exam_term_repair_audits" -> EntityType.EXAM_TERM_REPAIR_AUDIT
        "unscheduled_academic_courses" -> EntityType.UNSCHEDULED_ACADEMIC_COURSE
        "buildings" -> EntityType.PLACE
        "building_aliases" -> EntityType.BUILDING_ALIAS
        "reminder_event_prefs" -> EntityType.REMINDER_PREFERENCE
        "utility_daily_samples" -> EntityType.UTILITY_SAMPLE
        "utility_online_rooms" -> EntityType.UTILITY_ROOM
        "utility_meters" -> EntityType.UTILITY_METER
        "utility_readings" -> EntityType.UTILITY_READING
        "focus_sessions" -> EntityType.AUTOMATION_RULE
        "library_booking_plans" -> EntityType.AUTOMATION_RULE
        "parcel_items" -> EntityType.PARCEL_ITEM
        "personal_events" -> EntityType.PERSONAL_EVENT
        "blob_store" -> EntityType.BLOB
        else -> throw BackupValidationException("同步表缺少受控 EntityType 映射: " + table)
    }
}

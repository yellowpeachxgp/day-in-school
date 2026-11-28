package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.EntityType

internal enum class GeneratedColumnKind {
    OCCURRENCE_MUTATION_TOKEN
}

internal data class ScopeColumnNames(
    val school: String = "scopeSchoolId",
    val account: String = "scopeAccountId",
    val profile: String = "scopeProfileId",
    val campus: String = "scopeCampusId"
)

internal data class BackupTablePolicy(
    val table: String,
    val category: BackupCategory,
    val primaryKeys: List<String>,
    val entityType: EntityType? = null,
    val omittedColumns: Set<String> = emptySet(),
    val generatedColumns: Map<String, GeneratedColumnKind> = emptyMap(),
    val scopeColumns: ScopeColumnNames? = null
) {
    init {
        require(table.matches(Regex("[a-z][a-z0-9_]*"))) { "备份表名非法: " + table }
        require(primaryKeys.isNotEmpty() && primaryKeys.distinct().size == primaryKeys.size) { "备份主键非法: " + table }
        require(primaryKeys.none { it in omittedColumns }) { "备份主键不能被省略: " + table }
        require(generatedColumns.keys.all { it in omittedColumns }) { "生成列必须先省略: " + table }
    }
}

/**
 * dis-backup/v1 的可移植表目录。
 *
 * 显式排除：
 * - cloud_identity_binding、parcel_source_connections：跨设备授权/登录 linkage；Cookie、Token、凭据绝不进入备份，
 *   恢复授权状态而没有授权材料会伪造可用性，因此必须重新登录/授权。
 * - widget_instance_configs：appWidgetId 是设备局部序号，跨设备恢复会错绑或覆盖其它 widget。
 * - reminder_tasks：调度运行时态，由 source-of-truth 课程/计划重新 reconcile。
 * - installed_extension_plugins：插件包、签名和授权文件在库外，恢复登记行会制造悬挂信任状态。
 * - entity_journal/domain_outbox/search_docs/search_fts：审计/投递/索引由恢复事务按 Actor.RESTORE 重新生成；
 *   业务行本身才是可移植事实，恢复操作必须留下自己的 RESTORE 审计而不是伪装成旧写入。
 */
internal object BackupTableCatalog {
    val policies: List<BackupTablePolicy> = listOf(
        BackupTablePolicy("student_profile", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy("terms", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy("period_time_schemes", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy("period_times", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy("calendar_overrides", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy("courses", BackupCategory.ACADEMIC, listOf("id"), entityType = EntityType.COURSE),
        BackupTablePolicy("course_schedule_rules", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy(
            table = "occurrence_overrides",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            omittedColumns = setOf("mutationToken"),
            generatedColumns = mapOf("mutationToken" to GeneratedColumnKind.OCCURRENCE_MUTATION_TOKEN)
        ),
        BackupTablePolicy("grade_attempts", BackupCategory.ACADEMIC, listOf("id"), entityType = EntityType.GRADE_ATTEMPT),
        BackupTablePolicy("gpa_policies", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy("import_audits", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy("exam_schedules", BackupCategory.ACADEMIC, listOf("id"), entityType = EntityType.EXAM),
        BackupTablePolicy("exam_term_repair_audits", BackupCategory.ACADEMIC, listOf("id")),
        BackupTablePolicy("unscheduled_academic_courses", BackupCategory.ACADEMIC, listOf("termId", "sourceKey")),

        BackupTablePolicy("buildings", BackupCategory.CAMPUS_REFERENCE, listOf("id")),
        BackupTablePolicy("building_aliases", BackupCategory.CAMPUS_REFERENCE, listOf("id")),

        BackupTablePolicy("reminder_event_prefs", BackupCategory.REMINDER_PREFERENCES, listOf("eventScopeKey")),

        BackupTablePolicy("utility_daily_samples", BackupCategory.UTILITY_FOCUS, listOf("id")),
        BackupTablePolicy("utility_online_rooms", BackupCategory.UTILITY_FOCUS, listOf("roomId")),
        BackupTablePolicy("utility_meters", BackupCategory.UTILITY_FOCUS, listOf("id")),
        BackupTablePolicy("utility_readings", BackupCategory.UTILITY_FOCUS, listOf("id")),
        BackupTablePolicy("focus_sessions", BackupCategory.UTILITY_FOCUS, listOf("id")),

        BackupTablePolicy("library_booking_plans", BackupCategory.LIBRARY_AUTOMATION, listOf("id")),
        BackupTablePolicy("parcel_items", BackupCategory.PARCELS, listOf("id")),

        BackupTablePolicy(
            table = "personal_events",
            category = BackupCategory.PERSONAL_EVENTS,
            primaryKeys = listOf("id"),
            entityType = EntityType.PERSONAL_EVENT,
            scopeColumns = ScopeColumnNames()
        ),
        BackupTablePolicy(
            table = "blob_store",
            category = BackupCategory.BLOBS,
            primaryKeys = listOf("blobId"),
            scopeColumns = ScopeColumnNames()
        )
    )

    private val byTable = policies.associateBy { it.table }

    fun policy(table: String): BackupTablePolicy? = byTable[table]

    fun selected(selection: BackupSelection): List<BackupTablePolicy> =
        policies.filter { it.category in selection.categories }
}

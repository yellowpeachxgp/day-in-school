package cn.edu.haut.campus.core.vault

import cn.edu.haut.campus.core.model.EntityType

internal enum class GeneratedColumnKind {
    OCCURRENCE_MUTATION_TOKEN,
    NULL_ON_RESTORE,
    EMPTY_JSON_OBJECT_ON_RESTORE,
    EMPTY_JSON_ARRAY_ON_RESTORE,
    RESTORED_PERSONAL_EVENT_PROVENANCE
}

internal data class ScopeColumnNames(
    val school: String = "scopeSchoolId",
    val account: String = "scopeAccountId",
    val profile: String = "scopeProfileId",
    val campus: String = "scopeCampusId"
)

/**
 * 一张可移植表的显式列白名单。exportedColumns 必须逐列枚举；schema 新增列不会自动进入备份，
 * 未导出列必须在 generatedColumns 中声明恢复期安全默认值，否则 readTableSchema fail-closed。
 */
internal data class BackupTablePolicy(
    val table: String,
    val category: BackupCategory,
    val primaryKeys: List<String>,
    val exportedColumns: Set<String>,
    val entityType: EntityType? = null,
    val generatedColumns: Map<String, GeneratedColumnKind> = emptyMap(),
    val scopeColumns: ScopeColumnNames? = null
) {
    init {
        require(table.matches(Regex("[a-z][a-z0-9_]*"))) { "备份表名非法: " + table }
        require(primaryKeys.isNotEmpty() && primaryKeys.distinct().size == primaryKeys.size) { "备份主键非法: " + table }
        require(exportedColumns.isNotEmpty()) { "备份列白名单不能为空: " + table }
        require(exportedColumns.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*")) }) { "备份列名非法: " + table }
        require(primaryKeys.all { it in exportedColumns }) { "备份主键必须显式导出: " + table }
        require(generatedColumns.keys.none { it in exportedColumns }) { "生成列不能同时导出: " + table }
    }
}

/**
 * dis-backup/v1 的可移植表目录。
 *
 * 隐私边界采用「逐表显式列白名单」，不再从 Room schema 反推“全部未省略列”。
 * runtime diagnostic 与上游 provenance/原始投递细节不作为可移植事实：library_booking_plans.lastDiagnosticMessage、
 * parcel_items.sourcesJson/fieldProvenanceJson/checkpointsJson、personal_events.provenanceJson 在 schema 层缺席，
 * 恢复时写入空值、空 JSON 或本地 RESTORE provenance，避免把上游文本、账号痕迹和诊断段落夹带进备份。
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
        BackupTablePolicy(
            table = "student_profile",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf("id", "schoolId", "displayName", "major", "enrollmentYear")
        ),
        BackupTablePolicy(
            table = "terms",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "academicYear", "termNumber", "startDateIso", "weekStartDayIso",
                "weekCount", "timeTableId", "current"
            )
        ),
        BackupTablePolicy(
            table = "period_time_schemes",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "termId", "timeTableId", "name", "startDateIso", "endDateIso",
                "userModified", "createdAtEpochMilli", "updatedAtEpochMilli"
            )
        ),
        BackupTablePolicy(
            table = "period_times",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "timeTableId", "periodNo", "startLocalTimeIso", "endLocalTimeIso",
                "sectionLabel", "schemeId", "termId", "validStartDateIso", "validEndDateIso",
                "userModified", "updatedAtEpochMillis"
            )
        ),
        BackupTablePolicy(
            table = "calendar_overrides",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "termId", "dateIso", "overrideType", "followDateIso", "customTeachingWeek", "title"
            )
        ),
        BackupTablePolicy(
            table = "courses",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            entityType = EntityType.COURSE,
            exportedColumns = setOf(
                "id", "externalId", "name", "teacher", "colorArgb", "creditPlain",
                "source", "userModified", "lastSyncEpochMilli"
            )
        ),
        BackupTablePolicy(
            table = "course_schedule_rules",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "courseId", "termId", "weekdayIso", "startPeriod", "endPeriod",
                "weeksCsv", "rawRoom", "buildingId", "customStartTimeIso", "customEndTimeIso", "userModified"
            )
        ),
        BackupTablePolicy(
            table = "occurrence_overrides",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "termId", "courseRuleId", "courseId", "dateIso", "startPeriod", "endPeriod",
                "startAtIso", "endAtIso", "room", "buildingId", "canceled", "reason", "moveId", "moveRole"
            ),
            generatedColumns = mapOf("mutationToken" to GeneratedColumnKind.OCCURRENCE_MUTATION_TOKEN)
        ),
        BackupTablePolicy(
            table = "grade_attempts",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            entityType = EntityType.GRADE_ATTEMPT,
            exportedColumns = setOf(
                "id", "termId", "academicYear", "termNumber", "courseKey", "name", "category",
                "creditsPlain", "rawGrade", "numericScorePlain", "gradePointPlain", "attemptType",
                "passed", "isCourseReplacement", "source", "syncedAtEpochMilli"
            )
        ),
        BackupTablePolicy(
            table = "gpa_policies",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "source", "appliesTo", "policyVersion", "includeRulesJson", "scoreToPointRulesJson"
            )
        ),
        BackupTablePolicy(
            table = "import_audits",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "termId", "importSource", "importedAtEpochMilli", "rowCount", "conflictCount", "outcome"
            )
        ),
        BackupTablePolicy(
            table = "exam_schedules",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            entityType = EntityType.EXAM,
            exportedColumns = setOf(
                "id", "termId", "courseCode", "courseName", "examBatchName", "examStageLabel",
                "examFormat", "rawTimeText", "examDateIso", "startTimeIso", "endTimeIso", "rawRoom",
                "seatNumber", "campusName", "isRetake", "syncedAtEpochMilli", "originTermId"
            )
        ),
        BackupTablePolicy(
            table = "exam_term_repair_audits",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "examRowId", "previousTermId", "newTermId", "evidenceRef",
                "ruleVersion", "disposition", "repairedAtEpochMilli"
            )
        ),
        BackupTablePolicy(
            table = "unscheduled_academic_courses",
            category = BackupCategory.ACADEMIC,
            primaryKeys = listOf("termId", "sourceKey"),
            exportedColumns = setOf(
                "termId", "sourceKey", "sourceId", "name", "teacher", "creditPlain",
                "rawWeeks", "category", "syncedAtEpochMilli"
            )
        ),

        BackupTablePolicy(
            table = "buildings",
            category = BackupCategory.CAMPUS_REFERENCE,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "campusId", "name", "shortName", "latitude", "longitude", "category",
                "entrancesCsv", "description", "verifiedAtIso"
            )
        ),
        BackupTablePolicy(
            table = "building_aliases",
            category = BackupCategory.CAMPUS_REFERENCE,
            primaryKeys = listOf("id"),
            exportedColumns = setOf("id", "buildingId", "rawNameOrPattern", "confidence", "verified")
        ),

        BackupTablePolicy(
            table = "reminder_event_prefs",
            category = BackupCategory.REMINDER_PREFERENCES,
            primaryKeys = listOf("eventScopeKey"),
            exportedColumns = setOf(
                "eventScopeKey", "enabledOverride", "leadMinutesOverride", "maxSnoozesOverride", "updatedAtEpochMs"
            )
        ),

        BackupTablePolicy(
            table = "utility_daily_samples",
            category = BackupCategory.UTILITY_FOCUS,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "roomId", "roomName", "deviceName", "dateText", "usedValue", "unit", "sampledAtEpochMs"
            )
        ),
        BackupTablePolicy(
            table = "utility_online_rooms",
            category = BackupCategory.UTILITY_FOCUS,
            primaryKeys = listOf("roomId"),
            exportedColumns = setOf("roomId", "roomName", "campusCode", "devicesJson", "updatedAtEpochMs", "failed")
        ),
        BackupTablePolicy(
            table = "utility_meters",
            category = BackupCategory.UTILITY_FOCUS,
            primaryKeys = listOf("id"),
            exportedColumns = setOf("id", "name", "campusName", "roomName", "unit", "lowThreshold", "createdAtEpochMs")
        ),
        BackupTablePolicy(
            table = "utility_readings",
            category = BackupCategory.UTILITY_FOCUS,
            primaryKeys = listOf("id"),
            exportedColumns = setOf("id", "meterId", "value", "topUpValue", "recordedAtEpochMs", "note")
        ),
        BackupTablePolicy(
            table = "focus_sessions",
            category = BackupCategory.UTILITY_FOCUS,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "generation", "durationMinutes", "startedAtEpochMs", "expectedEndAtEpochMs",
                "status", "scheduleMode", "completedAtEpochMs", "cancelledAtEpochMs", "completionSource",
                "notified", "cancelReason", "updatedAtEpochMs"
            )
        ),

        BackupTablePolicy(
            table = "library_booking_plans",
            category = BackupCategory.LIBRARY_AUTOMATION,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "schoolId", "executionMode", "targetLocalDate", "targetLocalTime", "timezoneId",
                "precheckAtEpochMillis", "finalCheckAtEpochMillis", "fireAtEpochMillis", "precheckStatus",
                "finalCheckStatus", "fireStatus", "preferredAreaName", "preferredSeatLabel", "allowAlternativeSeat",
                "cloudJobId", "externalBookingId", "evidenceSha256", "priceMicropeach", "paymentState", "status",
                "stateVersion", "generation", "createdAtEpochMillis", "updatedAtEpochMillis", "repeatDays",
                "remainingDays", "ownerCloudUserId", "ownerStudentId"
            ),
            generatedColumns = mapOf("lastDiagnosticMessage" to GeneratedColumnKind.NULL_ON_RESTORE)
        ),
        BackupTablePolicy(
            table = "parcel_items",
            category = BackupCategory.PARCELS,
            primaryKeys = listOf("id"),
            exportedColumns = setOf(
                "id", "trackingNumber", "carrierCode", "carrierName", "status", "pickupCode",
                "stationName", "itemTitle", "estimatedDeliveryText", "createdAtEpochMillis", "updatedAtEpochMillis"
            ),
            generatedColumns = mapOf(
                "sourcesJson" to GeneratedColumnKind.EMPTY_JSON_ARRAY_ON_RESTORE,
                "fieldProvenanceJson" to GeneratedColumnKind.EMPTY_JSON_OBJECT_ON_RESTORE,
                "checkpointsJson" to GeneratedColumnKind.EMPTY_JSON_ARRAY_ON_RESTORE
            )
        ),

        BackupTablePolicy(
            table = "personal_events",
            category = BackupCategory.PERSONAL_EVENTS,
            primaryKeys = listOf("id"),
            entityType = EntityType.PERSONAL_EVENT,
            exportedColumns = setOf(
                "id", "scopeSchoolId", "scopeAccountId", "scopeProfileId", "scopeCampusId", "kind",
                "title", "note", "startAtEpochMs", "endAtEpochMs", "timeSemantics", "zoneId", "placeText",
                "placeRefId", "status", "busy", "termId", "revision", "sourceEntityType", "sourceEntityId",
                "linksJson", "createdAtEpochMs", "updatedAtEpochMs"
            ),
            generatedColumns = mapOf("provenanceJson" to GeneratedColumnKind.RESTORED_PERSONAL_EVENT_PROVENANCE),
            scopeColumns = ScopeColumnNames()
        ),
        BackupTablePolicy(
            table = "blob_store",
            category = BackupCategory.BLOBS,
            primaryKeys = listOf("blobId"),
            exportedColumns = setOf(
                "blobId", "scopeSchoolId", "scopeAccountId", "scopeProfileId", "scopeCampusId",
                "sha256", "mimeType", "byteCount", "content", "ownerEntityType", "ownerEntityId", "createdAtEpochMs"
            ),
            scopeColumns = ScopeColumnNames()
        )
    )

    private val byTable = policies.associateBy { it.table }

    init {
        require(policies.map { it.table }.distinct().size == policies.size) { "备份表目录重复" }
    }

    fun policy(table: String): BackupTablePolicy? = byTable[table]

    fun selected(selection: BackupSelection): List<BackupTablePolicy> =
        policies.filter { it.category in selection.categories }
}

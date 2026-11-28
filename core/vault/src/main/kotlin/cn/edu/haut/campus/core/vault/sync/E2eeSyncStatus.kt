package cn.edu.haut.campus.core.vault.sync

data class E2eeSyncRunReport(
    val pulledRows: Int = 0,
    val appliedRemoteRows: Int = 0,
    val uploadedRows: Int = 0,
    val deletedRows: Int = 0,
    val conflictsCreated: Int = 0,
    val pendingWrites: Int = 0,
    val oversizedRows: List<String> = emptyList(),
    val terminalCursorPresent: Boolean = false
)

data class E2eeSyncStatus(
    val enabled: Boolean,
    val deviceId: String?,
    val consentId: String?,
    val syncSpaceId: String?,
    val cursorPresent: Boolean,
    val objectCount: Int,
    val pendingWriteCount: Int,
    val conflicts: List<E2eeSyncConflict>,
    val lastRunAtEpochMs: Long,
    val lastRunSummary: String?
)

enum class E2eeSyncConflictResolution {
    KEEP_LOCAL,
    USE_REMOTE
}

data class E2eeSyncDisableReport(
    val deviceRevoked: Boolean,
    val consentWithdrawn: Boolean
)

enum class E2eeSyncEnableMode {
    CREATE_NEW_SYNC_SPACE,
    JOIN_EXISTING_SYNC_SPACE
}

data class E2eeSyncEnableResult(
    val deviceId: String,
    val consentId: String,
    val syncSpaceId: String,
    /** 仅 CREATE_NEW_SYNC_SPACE 或导入流程即时返回；它是秘密，调用方不得写日志或持久化。 */
    val recoveryKeyForUserCopy: String?
)

internal data class E2eeSyncRunStats(
    var pulledRows: Int = 0,
    var appliedRemoteRows: Int = 0,
    var uploadedRows: Int = 0,
    var deletedRows: Int = 0,
    var conflictsCreated: Int = 0,
    val oversizedRows: MutableList<String> = mutableListOf()
) {
    fun toReport(state: E2eeSyncState): E2eeSyncRunReport = E2eeSyncRunReport(
        pulledRows = pulledRows,
        appliedRemoteRows = appliedRemoteRows,
        uploadedRows = uploadedRows,
        deletedRows = deletedRows,
        conflictsCreated = conflictsCreated,
        pendingWrites = state.pendingWrites.size,
        oversizedRows = oversizedRows.toList(),
        terminalCursorPresent = !state.cursor.isNullOrBlank()
    )
}

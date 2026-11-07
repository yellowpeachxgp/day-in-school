package cn.edu.haut.campus.core.database

/** Fail closed: only an existing, pending future cloud job needs renewed credentials. */
internal object CloudCredentialSyncPolicy {
    private val pendingStates = setOf("ARMED", "PRECHECK_OK", "FINAL_CHECK_OK")

    fun requiresCredentials(
        executionMode: String,
        status: String,
        remainingDays: Int,
        fireAtEpochMillis: Long,
        cloudJobId: String?,
        nowEpochMillis: Long
    ): Boolean = executionMode == "CLOUD_HOSTED" &&
        status in pendingStates &&
        remainingDays > 0 &&
        fireAtEpochMillis > nowEpochMillis &&
        !cloudJobId.isNullOrBlank()
}

package cn.edu.haut.campus.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** W2-02 自动化规则持久化行；trigger/conditions/actions 使用规范化 JSON，scope 列独立用于隔离与擦除核对。 */
@Entity(
    tableName = "automation_rules",
    indices = [Index(value = ["scopeSchoolId", "scopeAccountId", "scopeProfileId", "enabled"])]
)
data class AutomationRuleEntity(
    @PrimaryKey val id: String,
    val scopeSchoolId: String,
    val scopeAccountId: String,
    val scopeProfileId: String,
    val scopeCampusId: String?,
    val name: String,
    val enabled: Boolean,
    val triggerJson: String,
    val conditionsJson: String,
    val actionsJson: String,
    val dedupe: String,
    val quietHoursJson: String?,
    val templateId: String?,
    val schemaVersion: Int,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/** 自动化运行记录；evaluations/outcomes 是小型脱敏事实 JSON，不包含凭据或原文 blob。 */
@Entity(
    tableName = "automation_runs",
    indices = [
        Index(value = ["ruleId", "firedAtEpochMs"]),
        Index(value = ["dedupeKey"], unique = true)
    ]
)
data class AutomationRunEntity(
    @PrimaryKey val runId: String,
    val ruleId: String,
    val triggerInstanceKey: String,
    val dedupeKey: String,
    val firedAtEpochMs: Long,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long,
    val status: String,
    val dryRun: Boolean,
    val evaluationsJson: String,
    val outcomesJson: String,
    val statusReason: String?,
    val schemaVersion: Int
)

/** PENDING lease + RUN_RECORDED 终态；executionOwner 是 W2-02 fencing token，不参与触发实例身份。 */
@Entity(
    tableName = "automation_dedupe",
    indices = [Index(value = ["runId"], unique = true)]
)
data class AutomationDedupeEntity(
    @PrimaryKey val dedupeKey: String,
    val ruleId: String,
    val triggerInstanceKey: String,
    val runId: String,
    val executionOwner: String,
    val acquiredAtEpochMs: Long,
    val policyFingerprint: String,
    val leaseExpiresAtEpochMs: Long,
    val state: String,
    val completedAtEpochMs: Long?
)

/** 动作级 durable checkpoint；W2-02 硬验收所需的实现表，保证 run 写失败后能对账动作与一次性确认。 */
@Entity(
    tableName = "automation_action_checkpoints",
    indices = [
        Index(value = ["runId", "actionIndex"], unique = true),
        Index(value = ["dedupeKey"])
    ]
)
data class AutomationActionCheckpointEntity(
    @PrimaryKey val checkpointKey: String,
    val dedupeKey: String,
    val runId: String,
    val ruleId: String,
    val triggerInstanceKey: String,
    val actionIndex: Int,
    val actionType: String,
    val configDigest: String,
    val executionOwner: String,
    val attempt: Long,
    val state: String,
    val outcomeJson: String?,
    val confirmationJson: String?,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long?
)

@Dao
interface AutomationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRule(entity: AutomationRuleEntity)

    @Query("SELECT * FROM automation_rules WHERE id = :ruleId LIMIT 1")
    suspend fun getRule(ruleId: String): AutomationRuleEntity?

    @Query("SELECT * FROM automation_rules ORDER BY updatedAtEpochMs DESC, id ASC")
    suspend fun getRulesSnapshot(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules ORDER BY updatedAtEpochMs DESC, id ASC")
    fun observeRules(): Flow<List<AutomationRuleEntity>>

    @Query("DELETE FROM automation_rules WHERE id = :ruleId")
    suspend fun deleteRule(ruleId: String): Int

    @Query("DELETE FROM automation_rules")
    suspend fun clearRules(): Int

    @Query("SELECT COUNT(*) FROM automation_rules")
    suspend fun countRules(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRun(entity: AutomationRunEntity)

    @Query("SELECT * FROM automation_runs WHERE runId = :runId LIMIT 1")
    suspend fun getRun(runId: String): AutomationRunEntity?

    @Query("SELECT * FROM automation_runs WHERE ruleId = :ruleId ORDER BY firedAtEpochMs DESC, runId ASC")
    fun observeRuns(ruleId: String): Flow<List<AutomationRunEntity>>

    @Query("SELECT * FROM automation_runs ORDER BY firedAtEpochMs DESC, runId ASC")
    suspend fun getRunsSnapshot(): List<AutomationRunEntity>

    @Query("DELETE FROM automation_runs WHERE ruleId = :ruleId")
    suspend fun deleteRunsForRule(ruleId: String): Int

    @Query("DELETE FROM automation_runs")
    suspend fun clearRuns(): Int

    @Query("SELECT COUNT(*) FROM automation_runs")
    suspend fun countRuns(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDedupe(entity: AutomationDedupeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceDedupe(entity: AutomationDedupeEntity)

    @Query("SELECT * FROM automation_dedupe WHERE dedupeKey = :dedupeKey LIMIT 1")
    suspend fun getDedupe(dedupeKey: String): AutomationDedupeEntity?

    @Query(
        "UPDATE automation_dedupe SET leaseExpiresAtEpochMs = :renewedUntilEpochMs " +
            "WHERE dedupeKey = :dedupeKey AND state = 'PENDING' AND executionOwner = :executionOwner " +
            "AND runId = :runId AND triggerInstanceKey = :triggerInstanceKey AND policyFingerprint = :policyFingerprint " +
            "AND acquiredAtEpochMs = :acquiredAtEpochMs AND leaseExpiresAtEpochMs > :renewedAtEpochMs"
    )
    suspend fun renewDedupeLease(
        dedupeKey: String,
        executionOwner: String,
        runId: String,
        triggerInstanceKey: String,
        policyFingerprint: String,
        acquiredAtEpochMs: Long,
        renewedAtEpochMs: Long,
        renewedUntilEpochMs: Long
    ): Int

    @Query(
        "UPDATE automation_dedupe SET state = 'RUN_RECORDED', completedAtEpochMs = :completedAtEpochMs " +
            "WHERE dedupeKey = :dedupeKey AND state = 'PENDING' AND executionOwner = :executionOwner " +
            "AND runId = :runId AND triggerInstanceKey = :triggerInstanceKey AND policyFingerprint = :policyFingerprint " +
            "AND acquiredAtEpochMs = :acquiredAtEpochMs"
    )
    suspend fun markDedupeRunRecorded(
        dedupeKey: String,
        executionOwner: String,
        runId: String,
        triggerInstanceKey: String,
        policyFingerprint: String,
        acquiredAtEpochMs: Long,
        completedAtEpochMs: Long
    ): Int

    @Query("DELETE FROM automation_dedupe WHERE ruleId = :ruleId")
    suspend fun deleteDedupeForRule(ruleId: String): Int

    @Query("DELETE FROM automation_dedupe")
    suspend fun clearDedupe(): Int

    @Query("SELECT COUNT(*) FROM automation_dedupe")
    suspend fun countDedupe(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCheckpoint(entity: AutomationActionCheckpointEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceCheckpoint(entity: AutomationActionCheckpointEntity)

    @Query("SELECT * FROM automation_action_checkpoints WHERE checkpointKey = :checkpointKey LIMIT 1")
    suspend fun getCheckpoint(checkpointKey: String): AutomationActionCheckpointEntity?

    @Query("SELECT * FROM automation_action_checkpoints WHERE runId = :runId ORDER BY actionIndex ASC")
    suspend fun getCheckpointsForRun(runId: String): List<AutomationActionCheckpointEntity>

    @Query(
        "UPDATE automation_action_checkpoints SET confirmationJson = :confirmationJson, updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE checkpointKey = :checkpointKey AND state = 'STARTED' AND executionOwner = :executionOwner AND attempt = :attempt"
    )
    suspend fun markCheckpointConfirmation(
        checkpointKey: String,
        executionOwner: String,
        attempt: Long,
        confirmationJson: String,
        updatedAtEpochMs: Long
    ): Int

    @Query(
        "UPDATE automation_action_checkpoints SET state = 'COMPLETED', outcomeJson = :outcomeJson, " +
            "updatedAtEpochMs = :completedAtEpochMs, completedAtEpochMs = :completedAtEpochMs " +
            "WHERE checkpointKey = :checkpointKey AND state = 'STARTED' AND executionOwner = :executionOwner AND attempt = :attempt"
    )
    suspend fun completeCheckpoint(
        checkpointKey: String,
        executionOwner: String,
        attempt: Long,
        outcomeJson: String,
        completedAtEpochMs: Long
    ): Int

    @Query("DELETE FROM automation_action_checkpoints WHERE ruleId = :ruleId")
    suspend fun deleteCheckpointsForRule(ruleId: String): Int

    @Query("DELETE FROM automation_action_checkpoints")
    suspend fun clearCheckpoints(): Int

    @Query("SELECT COUNT(*) FROM automation_action_checkpoints")
    suspend fun countCheckpoints(): Int
}

/** W2-02 分配的 Room v13：新增自动化规则/运行/去重与动作 checkpoint 表，不修改 v12 既有行。 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS automation_rules (
                id TEXT NOT NULL,
                scopeSchoolId TEXT NOT NULL,
                scopeAccountId TEXT NOT NULL,
                scopeProfileId TEXT NOT NULL,
                scopeCampusId TEXT,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                triggerJson TEXT NOT NULL,
                conditionsJson TEXT NOT NULL,
                actionsJson TEXT NOT NULL,
                dedupe TEXT NOT NULL,
                quietHoursJson TEXT,
                templateId TEXT,
                schemaVersion INTEGER NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_rules_scopeSchoolId_scopeAccountId_scopeProfileId_enabled ON automation_rules (scopeSchoolId, scopeAccountId, scopeProfileId, enabled)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS automation_runs (
                runId TEXT NOT NULL,
                ruleId TEXT NOT NULL,
                triggerInstanceKey TEXT NOT NULL,
                dedupeKey TEXT NOT NULL,
                firedAtEpochMs INTEGER NOT NULL,
                startedAtEpochMs INTEGER NOT NULL,
                finishedAtEpochMs INTEGER NOT NULL,
                status TEXT NOT NULL,
                dryRun INTEGER NOT NULL,
                evaluationsJson TEXT NOT NULL,
                outcomesJson TEXT NOT NULL,
                statusReason TEXT,
                schemaVersion INTEGER NOT NULL,
                PRIMARY KEY(runId)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_runs_ruleId_firedAtEpochMs ON automation_runs (ruleId, firedAtEpochMs)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_automation_runs_dedupeKey ON automation_runs (dedupeKey)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS automation_dedupe (
                dedupeKey TEXT NOT NULL,
                ruleId TEXT NOT NULL,
                triggerInstanceKey TEXT NOT NULL,
                runId TEXT NOT NULL,
                executionOwner TEXT NOT NULL,
                acquiredAtEpochMs INTEGER NOT NULL,
                policyFingerprint TEXT NOT NULL,
                leaseExpiresAtEpochMs INTEGER NOT NULL,
                state TEXT NOT NULL,
                completedAtEpochMs INTEGER,
                PRIMARY KEY(dedupeKey)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_automation_dedupe_runId ON automation_dedupe (runId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS automation_action_checkpoints (
                checkpointKey TEXT NOT NULL,
                dedupeKey TEXT NOT NULL,
                runId TEXT NOT NULL,
                ruleId TEXT NOT NULL,
                triggerInstanceKey TEXT NOT NULL,
                actionIndex INTEGER NOT NULL,
                actionType TEXT NOT NULL,
                configDigest TEXT NOT NULL,
                executionOwner TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                state TEXT NOT NULL,
                outcomeJson TEXT,
                confirmationJson TEXT,
                startedAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                completedAtEpochMs INTEGER,
                PRIMARY KEY(checkpointKey)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_automation_action_checkpoints_runId_actionIndex ON automation_action_checkpoints (runId, actionIndex)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_automation_action_checkpoints_dedupeKey ON automation_action_checkpoints (dedupeKey)")
    }
}

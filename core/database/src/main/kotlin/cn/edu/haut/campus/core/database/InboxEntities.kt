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

/** W2-05 收件箱原始导入行；原文二进制本体只放 blob_store，本表保留可回溯引用与解析状态。 */
@Entity(
    tableName = "inbox_items",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index(value = ["receivedAtEpochMs"]),
        Index(value = ["status"]),
        Index(value = ["scopeSchoolId", "scopeAccountId", "scopeProfileId", "scopeCampusId"])
    ]
)
data class InboxItemEntity(
    @PrimaryKey val id: String,
    val scopeSchoolId: String,
    val scopeAccountId: String,
    val scopeProfileId: String,
    val scopeCampusId: String?,
    val receivedAtEpochMs: Long,
    val referenceDateIso: String?,
    val origin: String,
    val status: String,
    val contentHash: String,
    val originalBlobIdsJson: String,
    val extractionBlobId: String?,
    val extractedTextPreview: String?,
    val parserError: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/** W2-05 结构化候选；每条候选必须先预览并由用户确认，appliedRefsJson 记录真实写入引用。 */
@Entity(
    tableName = "inbox_proposals",
    indices = [
        Index(value = ["inboxItemId", "parserId", "fingerprint"], unique = true),
        Index(value = ["inboxItemId"]),
        Index(value = ["fingerprint"]),
        Index(value = ["status"]),
        Index(value = ["scopeSchoolId", "scopeAccountId", "scopeProfileId", "scopeCampusId"])
    ]
)
data class InboxProposalEntity(
    @PrimaryKey val id: String,
    val inboxItemId: String,
    val scopeSchoolId: String,
    val scopeAccountId: String,
    val scopeProfileId: String,
    val scopeCampusId: String?,
    val parserId: String,
    val actionType: String,
    val actionJson: String,
    val fingerprint: String,
    val confidenceJson: String,
    val sourceSpansJson: String,
    val status: String,
    val appliedRefsJson: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val appliedAtEpochMs: Long?
)

@Dao
interface InboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItemOrAbort(entity: InboxItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(entity: InboxItemEntity)

    @Query("SELECT * FROM inbox_items WHERE id = :itemId LIMIT 1")
    suspend fun getItem(itemId: String): InboxItemEntity?

    @Query("SELECT * FROM inbox_items WHERE contentHash = :contentHash LIMIT 1")
    suspend fun getItemByContentHash(contentHash: String): InboxItemEntity?

    @Query("SELECT * FROM inbox_items ORDER BY receivedAtEpochMs DESC, id ASC")
    fun observeItems(): Flow<List<InboxItemEntity>>

    @Query("SELECT * FROM inbox_items ORDER BY receivedAtEpochMs DESC, id ASC")
    suspend fun getItemsSnapshot(): List<InboxItemEntity>

    @Query("UPDATE inbox_items SET status = :status, parserError = :parserError, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :itemId")
    suspend fun updateItemStatus(itemId: String, status: String, parserError: String?, updatedAtEpochMs: Long): Int

    @Query("UPDATE inbox_items SET extractionBlobId = :extractionBlobId, extractedTextPreview = :extractedTextPreview, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :itemId")
    suspend fun updateItemExtraction(itemId: String, extractionBlobId: String?, extractedTextPreview: String?, updatedAtEpochMs: Long): Int

    @Query("UPDATE inbox_items SET referenceDateIso = :referenceDateIso, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :itemId")
    suspend fun updateItemReferenceDate(itemId: String, referenceDateIso: String?, updatedAtEpochMs: Long): Int

    @Query("DELETE FROM inbox_items WHERE id = :itemId")
    suspend fun deleteItem(itemId: String): Int

    @Query("DELETE FROM inbox_items")
    suspend fun clearItems(): Int

    @Query("SELECT COUNT(*) FROM inbox_items")
    suspend fun countItems(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProposal(entity: InboxProposalEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProposalOrAbort(entity: InboxProposalEntity)

    @Query("SELECT * FROM inbox_proposals WHERE id = :proposalId LIMIT 1")
    suspend fun getProposal(proposalId: String): InboxProposalEntity?

    @Query("SELECT * FROM inbox_proposals WHERE inboxItemId = :itemId ORDER BY createdAtEpochMs ASC, id ASC")
    suspend fun getProposalsForItem(itemId: String): List<InboxProposalEntity>

    @Query("SELECT * FROM inbox_proposals WHERE inboxItemId = :itemId ORDER BY createdAtEpochMs ASC, id ASC")
    fun observeProposalsForItem(itemId: String): Flow<List<InboxProposalEntity>>

    @Query("SELECT * FROM inbox_proposals WHERE fingerprint = :fingerprint ORDER BY updatedAtEpochMs DESC, id ASC")
    suspend fun getProposalsByFingerprint(fingerprint: String): List<InboxProposalEntity>

    @Query("UPDATE inbox_proposals SET status = :status, appliedRefsJson = :appliedRefsJson, appliedAtEpochMs = :appliedAtEpochMs, updatedAtEpochMs = :updatedAtEpochMs WHERE id = :proposalId")
    suspend fun updateProposalOutcome(
        proposalId: String,
        status: String,
        appliedRefsJson: String?,
        appliedAtEpochMs: Long?,
        updatedAtEpochMs: Long
    ): Int

    @Query("DELETE FROM inbox_proposals WHERE inboxItemId = :itemId")
    suspend fun deleteProposalsForItem(itemId: String): Int

    @Query("DELETE FROM inbox_proposals")
    suspend fun clearProposals(): Int

    @Query("SELECT COUNT(*) FROM inbox_proposals")
    suspend fun countProposals(): Int
}

/** W2-05 分配的 Room v14：新增收件箱条目与结构化候选表，不修改 v13 既有行。 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS inbox_items (
                id TEXT NOT NULL,
                scopeSchoolId TEXT NOT NULL,
                scopeAccountId TEXT NOT NULL,
                scopeProfileId TEXT NOT NULL,
                scopeCampusId TEXT,
                receivedAtEpochMs INTEGER NOT NULL,
                referenceDateIso TEXT,
                origin TEXT NOT NULL,
                status TEXT NOT NULL,
                contentHash TEXT NOT NULL,
                originalBlobIdsJson TEXT NOT NULL,
                extractionBlobId TEXT,
                extractedTextPreview TEXT,
                parserError TEXT,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_inbox_items_contentHash ON inbox_items (contentHash)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_items_receivedAtEpochMs ON inbox_items (receivedAtEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_items_status ON inbox_items (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_items_scopeSchoolId_scopeAccountId_scopeProfileId_scopeCampusId ON inbox_items (scopeSchoolId, scopeAccountId, scopeProfileId, scopeCampusId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS inbox_proposals (
                id TEXT NOT NULL,
                inboxItemId TEXT NOT NULL,
                scopeSchoolId TEXT NOT NULL,
                scopeAccountId TEXT NOT NULL,
                scopeProfileId TEXT NOT NULL,
                scopeCampusId TEXT,
                parserId TEXT NOT NULL,
                actionType TEXT NOT NULL,
                actionJson TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                confidenceJson TEXT NOT NULL,
                sourceSpansJson TEXT NOT NULL,
                status TEXT NOT NULL,
                appliedRefsJson TEXT,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                appliedAtEpochMs INTEGER,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_inbox_proposals_inboxItemId_parserId_fingerprint ON inbox_proposals (inboxItemId, parserId, fingerprint)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_proposals_inboxItemId ON inbox_proposals (inboxItemId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_proposals_fingerprint ON inbox_proposals (fingerprint)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_proposals_status ON inbox_proposals (status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_inbox_proposals_scopeSchoolId_scopeAccountId_scopeProfileId_scopeCampusId ON inbox_proposals (scopeSchoolId, scopeAccountId, scopeProfileId, scopeCampusId)")
    }
}

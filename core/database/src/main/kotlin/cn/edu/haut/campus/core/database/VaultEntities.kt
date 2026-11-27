package cn.edu.haut.campus.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** W1-03 个人日程/待办/群组会议/复习块的持久化行；领域枚举以受控字符串落地。 */
@Entity(
    tableName = "personal_events",
    indices = [
        Index(value = ["startAtEpochMs"]),
        Index(value = ["updatedAtEpochMs"]),
        Index(value = ["termId"])
    ]
)
data class PersonalEventEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val note: String?,
    val startAtEpochMs: Long,
    val endAtEpochMs: Long?,
    val zoneId: String,
    val placeText: String?,
    val placeRefId: String?,
    val status: String,
    val busy: String,
    val termId: String?,
    val revision: Long,
    val provenanceJson: String,
    val sourceEntityType: String?,
    val sourceEntityId: String?,
    val linksJson: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

/** 一次实体字段级/行级变更；writeGroupId 把同一业务写事务内的多行变更聚合成可撤销单元。 */
@Entity(
    tableName = "entity_journal",
    indices = [
        Index(value = ["entityType", "entityId", "occurredAtEpochMs"]),
        Index(value = ["scopeSchoolId", "scopeAccountId", "scopeProfileId", "occurredAtEpochMs"]),
        Index(value = ["writeGroupId"])
    ]
)
data class EntityJournalEntity(
    @PrimaryKey(autoGenerate = true) val changeId: Long = 0,
    val writeGroupId: String,
    val entityType: String,
    val entityId: String,
    val scopeSchoolId: String,
    val scopeAccountId: String,
    val scopeProfileId: String,
    val scopeCampusId: String?,
    val op: String,
    val beforeJson: String?,
    val afterJson: String?,
    val actor: String,
    val actorDetail: String?,
    val occurredAtEpochMs: Long,
    val sourceJson: String?,
    val revertedByChangeId: Long?,
    val eventId: String?
)

/** Room 持久化 outbox；ack 按 consumer group 以 JSON array 记录，事件本体保持 envelope CanonicalJson。 */
@Entity(
    tableName = "domain_outbox",
    indices = [Index(value = ["eventId"], unique = true)]
)
data class DomainOutboxEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val eventId: String,
    val eventType: String,
    val schemaVersion: Int,
    val occurredAtIso: String,
    val scopeKey: String?,
    val envelopeJson: String,
    val ackedConsumerGroupsJson: String,
    val createdAtEpochMs: Long
)

/** FTS 的权威文档表；tokenStream 是写入时由 R01 normalizer 生成的可迁移 token 流。 */
@Entity(
    tableName = "search_docs",
    indices = [
        Index(value = ["entityType", "entityId", "scopeSchoolId", "scopeAccountId", "scopeProfileId"], unique = true),
        Index(value = ["termId"]),
        Index(value = ["atEpochMs"]),
        Index(value = ["updatedAtEpochMs"])
    ]
)
data class SearchDocEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val entityType: String,
    val entityId: String,
    val scopeSchoolId: String,
    val scopeAccountId: String,
    val scopeProfileId: String,
    val scopeCampusId: String?,
    val title: String,
    val body: String,
    val code: String?,
    val aliasesJson: String,
    val normalizedText: String,
    val tokenStream: String,
    val tokenStreamTitle: String,
    val tokenStreamBody: String,
    val tokenStreamCode: String,
    val tokenStreamAlias: String,
    val termId: String?,
    val atEpochMs: Long?,
    val deeplink: String?,
    val facetsJson: String,
    val updatedAtEpochMs: Long,
    val deleted: Boolean
)

/** external-content FTS4 索引；内容权威仍在 search_docs，避免在 FTS 中重复存原文/token 流。 */
@Fts4(
    contentEntity = SearchDocEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["tokenchars=_"]
)
@Entity(tableName = "search_fts")
data class SearchFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val tokenStream: String
)

/** 收件箱/知识资料的原始二进制对象；只存字节与摘要，不存任何凭据。 */
@Entity(tableName = "blob_store")
data class BlobStoreEntity(
    @PrimaryKey val blobId: String,
    val sha256: String,
    val mimeType: String,
    val byteCount: Long,
    val content: ByteArray,
    val ownerEntityType: String?,
    val ownerEntityId: String?,
    val createdAtEpochMs: Long
)

@Dao
interface VaultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPersonalEvent(entity: PersonalEventEntity)

    @Query("SELECT * FROM personal_events WHERE id = :id LIMIT 1")
    suspend fun getPersonalEvent(id: String): PersonalEventEntity?

    @Query("SELECT * FROM personal_events ORDER BY startAtEpochMs ASC, id ASC")
    fun observePersonalEvents(): Flow<List<PersonalEventEntity>>

    @Query("SELECT * FROM personal_events ORDER BY startAtEpochMs ASC, id ASC")
    suspend fun getPersonalEventsSnapshot(): List<PersonalEventEntity>

    @Delete
    suspend fun deletePersonalEvent(entity: PersonalEventEntity)

    @Query("DELETE FROM personal_events WHERE id = :id")
    suspend fun deletePersonalEventById(id: String): Int

    @Query("DELETE FROM personal_events")
    suspend fun clearPersonalEvents(): Int

    @Query("SELECT COUNT(*) FROM personal_events")
    suspend fun countPersonalEvents(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertJournal(entity: EntityJournalEntity): Long

    @Query("SELECT * FROM entity_journal WHERE changeId = :changeId LIMIT 1")
    suspend fun getJournalEntry(changeId: Long): EntityJournalEntity?

    @Query("SELECT * FROM entity_journal WHERE writeGroupId = :writeGroupId ORDER BY changeId ASC")
    suspend fun getJournalGroup(writeGroupId: String): List<EntityJournalEntity>

    @Query("SELECT * FROM entity_journal WHERE entityType = :entityType AND entityId = :entityId ORDER BY occurredAtEpochMs ASC, changeId ASC")
    fun observeJournalForEntity(entityType: String, entityId: String): Flow<List<EntityJournalEntity>>

    @Query("SELECT * FROM entity_journal WHERE scopeSchoolId = :schoolId AND scopeAccountId = :accountId AND scopeProfileId = :profileId AND entityType IN (:types) ORDER BY occurredAtEpochMs DESC, changeId DESC LIMIT :limit")
    fun observeRecentJournal(schoolId: String, accountId: String, profileId: String, types: List<String>, limit: Int): Flow<List<EntityJournalEntity>>

    @Query("UPDATE entity_journal SET revertedByChangeId = :revertedByChangeId WHERE changeId = :changeId")
    suspend fun markJournalReverted(changeId: Long, revertedByChangeId: Long): Int

    @Query("DELETE FROM entity_journal")
    suspend fun clearEntityJournal(): Int

    @Query("SELECT COUNT(*) FROM entity_journal")
    suspend fun countEntityJournal(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(entity: DomainOutboxEntity): Long

    @Query("SELECT * FROM domain_outbox WHERE eventId = :eventId LIMIT 1")
    suspend fun getOutboxByEventId(eventId: String): DomainOutboxEntity?

    @Query("SELECT * FROM domain_outbox ORDER BY sequence ASC")
    suspend fun getOutboxSnapshot(): List<DomainOutboxEntity>

    @Query("UPDATE domain_outbox SET ackedConsumerGroupsJson = :ackedConsumerGroupsJson WHERE eventId = :eventId")
    suspend fun updateOutboxAcks(eventId: String, ackedConsumerGroupsJson: String): Int

    @Query("DELETE FROM domain_outbox")
    suspend fun clearDomainOutbox(): Int

    @Query("SELECT COUNT(*) FROM domain_outbox")
    suspend fun countDomainOutbox(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchDoc(entity: SearchDocEntity): Long

    @Query("SELECT * FROM search_docs WHERE entityType = :entityType AND entityId = :entityId AND scopeSchoolId = :schoolId AND scopeAccountId = :accountId AND scopeProfileId = :profileId LIMIT 1")
    suspend fun getSearchDoc(entityType: String, entityId: String, schoolId: String, accountId: String, profileId: String): SearchDocEntity?

    @Query("SELECT * FROM search_docs ORDER BY updatedAtEpochMs DESC, rowid DESC")
    suspend fun getSearchDocsSnapshot(): List<SearchDocEntity>

    @Query("DELETE FROM search_docs WHERE entityType = :entityType AND entityId = :entityId AND scopeSchoolId = :schoolId AND scopeAccountId = :accountId AND scopeProfileId = :profileId")
    suspend fun deleteSearchDoc(entityType: String, entityId: String, schoolId: String, accountId: String, profileId: String): Int

    @RawQuery
    suspend fun searchDocsRaw(query: SupportSQLiteQuery): List<SearchDocEntity>

    @Query("INSERT INTO search_fts(search_fts) VALUES('rebuild')")
    suspend fun rebuildSearchFts()

    @Query("DELETE FROM search_fts")
    suspend fun clearSearchFts()

    @Query("SELECT COUNT(*) FROM search_docs")
    suspend fun countSearchDocs(): Int

    @Query("SELECT COUNT(*) FROM search_fts")
    suspend fun countSearchFtsRows(): Int

    @Query("DELETE FROM search_docs")
    suspend fun clearSearchDocs(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBlob(entity: BlobStoreEntity)

    @Query("SELECT * FROM blob_store WHERE blobId = :blobId LIMIT 1")
    suspend fun getBlob(blobId: String): BlobStoreEntity?

    @Query("DELETE FROM blob_store WHERE blobId = :blobId")
    suspend fun deleteBlob(blobId: String): Int

    @Query("SELECT * FROM blob_store ORDER BY createdAtEpochMs ASC, blobId ASC")
    suspend fun getBlobsSnapshot(): List<BlobStoreEntity>

    @Query("DELETE FROM blob_store")
    suspend fun clearBlobStore(): Int

    @Query("SELECT COUNT(*) FROM blob_store")
    suspend fun countBlobStore(): Int

    @Query("SELECT COALESCE(SUM(byteCount), 0) FROM blob_store")
    suspend fun totalBlobBytes(): Long
}

/**
 * W1-03 分配的 Room v12：只新增 vault 表与索引，不改写 v11 既有行。
 * v11 归 W0-04；本迁移严格按 v11→v12 注册，缺失 v11 时不得抢跑合入。
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS personal_events (
                id TEXT NOT NULL,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                note TEXT,
                startAtEpochMs INTEGER NOT NULL,
                endAtEpochMs INTEGER,
                zoneId TEXT NOT NULL,
                placeText TEXT,
                placeRefId TEXT,
                status TEXT NOT NULL,
                busy TEXT NOT NULL,
                termId TEXT,
                revision INTEGER NOT NULL,
                provenanceJson TEXT NOT NULL,
                sourceEntityType TEXT,
                sourceEntityId TEXT,
                linksJson TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_events_startAtEpochMs ON personal_events (startAtEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_events_updatedAtEpochMs ON personal_events (updatedAtEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_personal_events_termId ON personal_events (termId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS entity_journal (
                changeId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                writeGroupId TEXT NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                scopeSchoolId TEXT NOT NULL,
                scopeAccountId TEXT NOT NULL,
                scopeProfileId TEXT NOT NULL,
                scopeCampusId TEXT,
                op TEXT NOT NULL,
                beforeJson TEXT,
                afterJson TEXT,
                actor TEXT NOT NULL,
                actorDetail TEXT,
                occurredAtEpochMs INTEGER NOT NULL,
                sourceJson TEXT,
                revertedByChangeId INTEGER,
                eventId TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_journal_entityType_entityId_occurredAtEpochMs ON entity_journal (entityType, entityId, occurredAtEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_journal_scopeSchoolId_scopeAccountId_scopeProfileId_occurredAtEpochMs ON entity_journal (scopeSchoolId, scopeAccountId, scopeProfileId, occurredAtEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_entity_journal_writeGroupId ON entity_journal (writeGroupId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS domain_outbox (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                eventId TEXT NOT NULL,
                eventType TEXT NOT NULL,
                schemaVersion INTEGER NOT NULL,
                occurredAtIso TEXT NOT NULL,
                scopeKey TEXT,
                envelopeJson TEXT NOT NULL,
                ackedConsumerGroupsJson TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_domain_outbox_eventId ON domain_outbox (eventId)")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS search_docs (
                rowid INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                entityType TEXT NOT NULL,
                entityId TEXT NOT NULL,
                scopeSchoolId TEXT NOT NULL,
                scopeAccountId TEXT NOT NULL,
                scopeProfileId TEXT NOT NULL,
                scopeCampusId TEXT,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                code TEXT,
                aliasesJson TEXT NOT NULL,
                normalizedText TEXT NOT NULL,
                tokenStream TEXT NOT NULL,
                tokenStreamTitle TEXT NOT NULL,
                tokenStreamBody TEXT NOT NULL,
                tokenStreamCode TEXT NOT NULL,
                tokenStreamAlias TEXT NOT NULL,
                termId TEXT,
                atEpochMs INTEGER,
                deeplink TEXT,
                facetsJson TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL,
                deleted INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_search_docs_entityType_entityId_scopeSchoolId_scopeAccountId_scopeProfileId ON search_docs (entityType, entityId, scopeSchoolId, scopeAccountId, scopeProfileId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_search_docs_termId ON search_docs (termId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_search_docs_atEpochMs ON search_docs (atEpochMs)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_search_docs_updatedAtEpochMs ON search_docs (updatedAtEpochMs)")

        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS search_fts USING FTS4(tokenStream, tokenize=unicode61 tokenchars=_, content=search_docs)")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_fts_BEFORE_UPDATE BEFORE UPDATE ON search_docs BEGIN INSERT INTO search_fts(search_fts, docid, tokenStream) VALUES('delete', OLD.rowid, OLD.tokenStream); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_fts_BEFORE_DELETE BEFORE DELETE ON search_docs BEGIN INSERT INTO search_fts(search_fts, docid, tokenStream) VALUES('delete', OLD.rowid, OLD.tokenStream); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_fts_AFTER_UPDATE AFTER UPDATE ON search_docs BEGIN INSERT INTO search_fts(docid, tokenStream) VALUES(NEW.rowid, NEW.tokenStream); END")
        db.execSQL("CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_search_fts_AFTER_INSERT AFTER INSERT ON search_docs BEGIN INSERT INTO search_fts(docid, tokenStream) VALUES(NEW.rowid, NEW.tokenStream); END")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS blob_store (
                blobId TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                mimeType TEXT NOT NULL,
                byteCount INTEGER NOT NULL,
                content BLOB NOT NULL,
                ownerEntityType TEXT,
                ownerEntityId TEXT,
                createdAtEpochMs INTEGER NOT NULL,
                PRIMARY KEY(blobId)
            )
            """.trimIndent()
        )
    }
}

package cn.edu.haut.campus.core.database

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** C27/W1-03：六张 vault 新表（含 FTS external-content 索引）都必须进入全量擦除链路。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultWipeTest {
    private lateinit var fixture: RepairTestFixture

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_vault_wipe")
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun wipeAllPersonalDataLeavesEveryVaultTableAndFtsIndexAtZero() = runBlocking {
        val dao = fixture.database.vaultDao()
        dao.upsertPersonalEvent(
            PersonalEventEntity(
                id = "personal:wipe-1",
                scopeSchoolId = "haut",
                scopeAccountId = "acct_prof_vault_wipe",
                scopeProfileId = "prof_vault_wipe",
                scopeCampusId = "lianhuajie",
                kind = "PERSONAL",
                title = "擦除测试",
                note = null,
                startAtEpochMs = 1L,
                endAtEpochMs = null,
                timeSemantics = "DEADLINE",
                zoneId = "Asia/Shanghai",
                placeText = null,
                placeRefId = null,
                status = "SCHEDULED",
                busy = "BUSY",
                termId = null,
                revision = 1L,
                provenanceJson = "{}",
                sourceEntityType = null,
                sourceEntityId = null,
                linksJson = "[]",
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L
            )
        )
        dao.upsertJournal(
            EntityJournalEntity(
                writeGroupId = "wipe-group",
                entityType = "PERSONAL_EVENT",
                entityId = "personal:wipe-1",
                scopeSchoolId = "haut",
                scopeAccountId = "acct_prof_vault_wipe",
                scopeProfileId = "prof_vault_wipe",
                scopeCampusId = "lianhuajie",
                op = "CREATED",
                beforeJson = null,
                afterJson = "{}",
                actor = "USER",
                actorDetail = null,
                occurredAtEpochMs = 1L,
                sourceJson = null,
                revertedByChangeId = null,
                eventId = "vault-wipe-event"
            )
        )
        dao.insertOutbox(
            DomainOutboxEntity(
                eventId = "vault-wipe-event",
                eventType = "vault.entity.changed",
                schemaVersion = 1,
                occurredAtIso = "2026-01-02T03:04:05Z",
                scopeKey = "haut/acct_prof_vault_wipe/prof_vault_wipe/lianhuajie",
                envelopeJson = "{}",
                ackedConsumerGroupsJson = "[]",
                createdAtEpochMs = 1L
            )
        )
        dao.upsertSearchDoc(
            SearchDocEntity(
                entityType = "PERSONAL_EVENT",
                entityId = "personal:wipe-1",
                scopeSchoolId = "haut",
                scopeAccountId = "acct_prof_vault_wipe",
                scopeProfileId = "prof_vault_wipe",
                scopeCampusId = "lianhuajie",
                title = "擦除测试",
                body = "正文",
                code = null,
                aliasesJson = "[]",
                normalizedText = "擦除测试 正文",
                tokenStream = "擦除 除测 测试 正文",
                tokenStreamTitle = "擦除 除测 测试",
                tokenStreamBody = "正文",
                tokenStreamCode = "",
                tokenStreamAlias = "",
                termId = null,
                atEpochMs = 1L,
                deeplink = null,
                facetsJson = "{}",
                updatedAtEpochMs = 1L,
                deleted = false
            )
        )
        dao.insertBlobOrAbort(
            BlobStoreEntity(
                blobId = "blob-wipe",
                scopeSchoolId = "haut",
                scopeAccountId = "acct_prof_vault_wipe",
                scopeProfileId = "prof_vault_wipe",
                scopeCampusId = "lianhuajie",
                sha256 = "0".repeat(64),
                mimeType = "text/plain",
                byteCount = 1L,
                content = byteArrayOf(1),
                ownerEntityType = null,
                ownerEntityId = null,
                createdAtEpochMs = 1L
            )
        )

        fixture.repository.wipeAllPersonalData()

        assertEquals(0, dao.countPersonalEvents())
        assertEquals(0, dao.countEntityJournal())
        assertEquals(0, dao.countDomainOutbox())
        assertEquals(0, dao.countSearchDocs())
        assertEquals(0, dao.countSearchFtsRows())
        assertEquals(0, dao.countBlobStore())
    }
}

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

/** C09/W2-05：inbox_items 与 inbox_proposals 必须进入全量擦除链路，擦除后为 0。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class InboxWipeTest {
    private lateinit var fixture: RepairTestFixture

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_inbox_wipe")
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun wipeAllPersonalDataLeavesEveryInboxTableAtZero() = runBlocking {
        val dao = fixture.database.inboxDao()
        dao.upsertItem(
            InboxItemEntity(
                id = "inbox-item-wipe",
                scopeSchoolId = "haut",
                scopeAccountId = "acct_prof_inbox_wipe",
                scopeProfileId = "prof_inbox_wipe",
                scopeCampusId = "lianhuajie",
                receivedAtEpochMs = 1L,
                referenceDateIso = "2026-11-25",
                origin = "CLIPBOARD",
                status = "RECEIVED",
                contentHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                originalBlobIdsJson = "[]",
                extractionBlobId = null,
                extractedTextPreview = null,
                parserError = null,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L
            )
        )
        dao.upsertProposal(
            InboxProposalEntity(
                id = "inbox-proposal-wipe",
                inboxItemId = "inbox-item-wipe",
                scopeSchoolId = "haut",
                scopeAccountId = "acct_prof_inbox_wipe",
                scopeProfileId = "prof_inbox_wipe",
                scopeCampusId = "lianhuajie",
                parserId = "rules-v1",
                actionType = "modify-course-occurrence",
                actionJson = "{}",
                fingerprint = "0".repeat(64),
                confidenceJson = "{}",
                sourceSpansJson = "[]",
                status = "PENDING",
                appliedRefsJson = null,
                createdAtEpochMs = 1L,
                updatedAtEpochMs = 1L,
                appliedAtEpochMs = null
            )
        )

        fixture.repository.wipeAllPersonalData()

        assertEquals(0, dao.countItems())
        assertEquals(0, dao.countProposals())
    }
}

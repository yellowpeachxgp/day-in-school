package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.model.AccountId
import cn.edu.haut.campus.core.model.CampusId
import cn.edu.haut.campus.core.model.ProfileId
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SchoolId
import cn.edu.haut.campus.core.vault.BackupCell
import cn.edu.haut.campus.core.vault.BackupRow
import cn.edu.haut.campus.core.vault.BackupTableCatalog
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class E2eeSyncPayloadAndStateTest {
    @Test
    fun scopedRowsAreNormalizedForSyncAndLocalizedBeforeApply() {
        val policy = BackupTableCatalog.policy("personal_events") ?: error("personal_events policy missing")
        val local = personalEventRow(policy, school = "haut", account = "acct-local", profile = "prof-local", campus = "lianhuajie")
        val normalized = SyncBackupScopeNormalizer.normalize(policy, local)
        assertEquals("sync", (normalized.cells["scopeSchoolId"] as BackupCell.TextValue).value)
        assertEquals("sync", (normalized.cells["scopeAccountId"] as BackupCell.TextValue).value)
        assertEquals("sync", (normalized.cells["scopeProfileId"] as BackupCell.TextValue).value)
        assertEquals(BackupCell.NullValue, normalized.cells["scopeCampusId"])

        val snapshot = SyncBackupRowSnapshot(policy, normalized, emptyMap())
        val space = "test-space"
        val payload = E2eeSyncPayloadCodec.payloadFromSnapshot(snapshot, 13, space, 3L, UUID.randomUUID().toString())
        val encoded = E2eeSyncPayloadCodec.encode(payload)
        assertFalse(encoded.contains("prof-local"))
        val decoded = E2eeSyncPayloadCodec.decode(encoded, space, 13)
        assertEquals(snapshot.rowSha256, decoded.rowSha256)

        val localized = SyncBackupScopeNormalizer.localize(
            policy,
            decoded.row,
            SchoolAccountScope(SchoolId("haut"), AccountId("acct-other"), ProfileId("prof-other"), CampusId("songcun"))
        )
        assertEquals("acct-other", (localized.cells["scopeAccountId"] as BackupCell.TextValue).value)
        assertEquals("prof-other", (localized.cells["scopeProfileId"] as BackupCell.TextValue).value)
        assertEquals("songcun", (localized.cells["scopeCampusId"] as BackupCell.TextValue).value)
    }

    @Test
    fun payloadRejectsWrongSyncSpaceAndNonNormalizedScope() {
        val policy = BackupTableCatalog.policy("personal_events") ?: error("personal_events policy missing")
        val normalized = SyncBackupScopeNormalizer.normalize(policy, personalEventRow(policy, "haut", "acct", "prof", null))
        val snapshot = SyncBackupRowSnapshot(policy, normalized, emptyMap())
        val payload = E2eeSyncPayloadCodec.payloadFromSnapshot(snapshot, 13, "space-a", 1L, UUID.randomUUID().toString())
        val encoded = E2eeSyncPayloadCodec.encode(payload)
        try {
            E2eeSyncPayloadCodec.decode(encoded, "space-b", 13)
            fail("不同 sync space 必须拒绝")
        } catch (expected: E2eeSyncCryptoException) {
            // expected
        }
        val rawSnapshot = SyncBackupRowSnapshot(policy, personalEventRow(policy, "haut", "acct", "prof", null), emptyMap())
        val rawPayload = E2eeSyncPayloadCodec.payloadFromSnapshot(rawSnapshot, 13, "space-a", 1L, UUID.randomUUID().toString())
        try {
            E2eeSyncPayloadCodec.encode(rawPayload)
            fail("未规范化 scope 必须拒绝编码")
        } catch (expected: E2eeSyncCryptoException) {
            // expected
        }
    }

    @Test
    fun fileStateStorePersistsThenWipeRemovesCursorAndPendingState() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val profile = "prof-sync-wipe-test"
        val store = FileE2eeSyncStateStore(context, profile)
        store.wipe()
        val state = E2eeSyncState(
            enabled = true,
            deviceId = UUID.randomUUID().toString(),
            consentId = UUID.randomUUID().toString(),
            syncSpaceId = "space",
            keyId = "key-id",
            cursor = "cursor-for-wipe-test"
        )
        state.pendingWrites["pending"] = E2eeSyncPendingWrite(
            logicalKey = "pending",
            kind = "DELETE",
            blobId = UUID.randomUUID().toString(),
            payloadJson = null,
            encryption = null,
            ciphertextBase64 = null,
            ciphertextSha256 = null,
            baseVector = emptyList(),
            writerCounter = 1,
            idempotencyKey = "pending-test",
            logicalClock = 1L,
            rowSha256 = null
        )
        store.save(state)
        assertEquals("cursor-for-wipe-test", store.load().cursor)
        assertEquals(1, store.load().pendingWrites.size)

        store.wipe()

        val after = store.load()
        assertFalse(after.enabled)
        assertNull(after.cursor)
        assertTrue(after.pendingWrites.isEmpty())
    }

    @Test
    fun inMemoryKeyStoreWipeRemovesSealedMasterMaterial() = runBlocking {
        val keys = InMemoryE2eeSyncKeyStore()
        keys.createNew()
        assertTrue(keys.exists())
        keys.wipe()
        assertFalse(keys.exists())
        try {
            keys.load()
            fail("擦除后不得再读取同步密钥")
        } catch (expected: E2eeSyncCryptoException) {
            // expected
        }
    }

    private fun personalEventRow(
        policy: cn.edu.haut.campus.core.vault.BackupTablePolicy,
        school: String,
        account: String,
        profile: String,
        campus: String?
    ): BackupRow {
        val cells = linkedMapOf<String, BackupCell>(
            "id" to BackupCell.TextValue("personal:test"),
            "scopeSchoolId" to BackupCell.TextValue(school),
            "scopeAccountId" to BackupCell.TextValue(account),
            "scopeProfileId" to BackupCell.TextValue(profile),
            "scopeCampusId" to (campus?.let { BackupCell.TextValue(it) } ?: BackupCell.NullValue),
            "kind" to BackupCell.TextValue("PERSONAL"),
            "title" to BackupCell.TextValue("个人日程"),
            "note" to BackupCell.NullValue,
            "startAtEpochMs" to BackupCell.LongValue(1000L),
            "endAtEpochMs" to BackupCell.NullValue,
            "timeSemantics" to BackupCell.TextValue("DEADLINE"),
            "zoneId" to BackupCell.TextValue("Asia/Shanghai"),
            "placeText" to BackupCell.NullValue,
            "placeRefId" to BackupCell.NullValue,
            "status" to BackupCell.TextValue("SCHEDULED"),
            "busy" to BackupCell.TextValue("BUSY"),
            "termId" to BackupCell.NullValue,
            "revision" to BackupCell.LongValue(1L),
            "sourceEntityType" to BackupCell.NullValue,
            "sourceEntityId" to BackupCell.NullValue,
            "linksJson" to BackupCell.TextValue("[]"),
            "createdAtEpochMs" to BackupCell.LongValue(1000L),
            "updatedAtEpochMs" to BackupCell.LongValue(1000L)
        )
        require(cells.keys == policy.exportedColumns) { "fixture columns mismatch" }
        return BackupRow(cells)
    }
}

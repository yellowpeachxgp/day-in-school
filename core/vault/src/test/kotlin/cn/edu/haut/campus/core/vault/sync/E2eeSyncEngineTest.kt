package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.vault.BackupCell
import cn.edu.haut.campus.core.vault.BackupRow
import cn.edu.haut.campus.core.vault.BackupTableCatalog
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class E2eeSyncEngineTest {
    @Test
    fun twoDevicesReplicateRowsAndCloudNeverSeesPlaintext() = runBlocking {
        val env = TwoDeviceEnv()
        val initial = courseSnapshot("course-sync-1", "微积分 A", teacher = "张老师")
        env.storeA.applyUpserts(listOf(initial))

        val uploadReport = env.engineA.runOnce()
        assertEquals(1, uploadReport.uploadedRows)
        assertEquals(1, env.cloud.blobCount())
        val cloudBytes = env.cloud.allCiphertextBytes()
        assertFalse(String(cloudBytes, Charsets.UTF_8).contains("微积分"))
        assertFalse(env.cloud.allCiphertextBase64().contains(Base64.getEncoder().encodeToString("微积分 A".toByteArray())))

        val pullReport = env.engineB.runOnce()
        assertEquals(1, pullReport.appliedRemoteRows)
        val replicated = env.storeB.readRow(initial.table, initial.rowKey)
        assertNotNull(replicated)
        assertEquals(initial.rowSha256, replicated?.rowSha256)

        val changedOnB = courseSnapshot("course-sync-1", "线性代数", teacher = "李老师")
        env.storeB.applyUpserts(listOf(changedOnB))
        val uploadBack = env.engineB.runOnce()
        assertEquals(1, uploadBack.uploadedRows)
        val pullBack = env.engineA.runOnce()
        assertEquals(1, pullBack.appliedRemoteRows)
        assertEquals(changedOnB.rowSha256, env.storeA.readRow(initial.table, initial.rowKey)?.rowSha256)
    }

    @Test
    fun concurrentUpdateCreatesConflictAndUseRemoteDoesNotKeepStaleLocal() = runBlocking {
        val env = TwoDeviceEnv()
        env.storeA.applyUpserts(listOf(courseSnapshot("course-conflict-remote", "初始版本")))
        env.engineA.runOnce()
        env.engineB.runOnce()

        env.storeA.applyUpserts(listOf(courseSnapshot("course-conflict-remote", "远端版本")))
        env.storeB.applyUpserts(listOf(courseSnapshot("course-conflict-remote", "本机版本")))
        env.engineA.runOnce()
        val report = env.engineB.runOnce()
        assertEquals(1, report.conflictsCreated)
        assertEquals("本机版本", courseName(env.storeB, "course-conflict-remote"))

        val conflict = env.engineB.status().conflicts.single()
        assertEquals("CONCURRENT_UPDATE", conflict.kind)
        assertNotNull(conflict.localPayloadJson)
        assertNotNull(conflict.remotePayloadJson)
        env.engineB.resolveConflict(conflict.id, E2eeSyncConflictResolution.USE_REMOTE)
        assertEquals("远端版本", courseName(env.storeB, "course-conflict-remote"))
        assertTrue(env.engineB.status().conflicts.isEmpty())
    }

    @Test
    fun concurrentUpdateKeepLocalFastForwardsVectorAndReplicatesBack() = runBlocking {
        val env = TwoDeviceEnv()
        env.storeA.applyUpserts(listOf(courseSnapshot("course-conflict-local", "初始版本")))
        env.engineA.runOnce()
        env.engineB.runOnce()

        env.storeA.applyUpserts(listOf(courseSnapshot("course-conflict-local", "远端版本")))
        env.storeB.applyUpserts(listOf(courseSnapshot("course-conflict-local", "本机版本")))
        env.engineA.runOnce()
        val report = env.engineB.runOnce()
        assertEquals(1, report.conflictsCreated)
        val conflict = env.engineB.status().conflicts.single()

        val resolution = env.engineB.resolveConflict(conflict.id, E2eeSyncConflictResolution.KEEP_LOCAL)
        assertEquals("conflicts=" + env.engineB.status().conflicts + " report=" + resolution, 1, resolution.uploadedRows)
        assertEquals("本机版本", courseName(env.storeB, "course-conflict-local"))
        val pullA = env.engineA.runOnce()
        assertEquals(1, pullA.appliedRemoteRows)
        assertEquals("本机版本", courseName(env.storeA, "course-conflict-local"))
    }

    @Test
    fun plaintextLogicalRollbackWithFastForwardedVectorIsConflictNotApplied() = runBlocking {
        val env = TwoDeviceEnv()
        env.storeA.applyUpserts(listOf(courseSnapshot("course-rollback", "第一版")))
        env.engineA.runOnce()
        env.engineB.runOnce()
        env.storeA.applyUpserts(listOf(courseSnapshot("course-rollback", "第二版")))
        env.engineA.runOnce()
        env.engineB.runOnce()
        assertEquals("第二版", courseName(env.storeB, "course-rollback"))

        val material = env.keyA.load()
        try {
            val current = env.cloud.currentMetadataForOnlyBlob()
            val stalePayload = E2eeSyncPayloadCodec.payloadFromSnapshot(
                snapshot = courseSnapshot("course-rollback", "第一版"),
                schemaVersion = env.storeA.schemaVersion,
                syncSpaceId = material.syncSpaceId,
                logicalClock = 1L,
                writerDeviceId = env.deviceA
            )
            val payloadJson = E2eeSyncPayloadCodec.encode(stalePayload)
            val plaintext = payloadJson.toByteArray(Charsets.UTF_8)
            val writerCounter = SyncVectors.nextWriterCounter(current.versionVector, env.deviceA)
            val encrypted = E2eeSyncBlockCipher.encrypt(plaintext, material.masterKey, material.keyId, current.blobId, writerCounter)
            plaintext.fill(0)
            env.cloud.injectPut(
                blobId = current.blobId,
                deviceId = env.deviceA,
                input = SyncBlobPutInput(
                    encryption = encrypted.encryption,
                    ciphertextBase64 = encrypted.ciphertextBase64,
                    ciphertextSha256 = encrypted.ciphertextSha256,
                    baseVector = current.versionVector,
                    writerCounter = writerCounter,
                    idempotencyKey = "rollback-injection-123456"
                )
            )
        } finally {
            material.zero()
        }

        val report = env.engineB.runOnce()
        assertEquals(1, report.conflictsCreated)
        val conflict = env.engineB.status().conflicts.single()
        assertEquals("ROLLBACK_DETECTED", conflict.kind)
        assertEquals("第二版", courseName(env.storeB, "course-rollback"))
    }

    @Test
    fun tombstonePropagatesDeleteToSecondDevice() = runBlocking {
        val env = TwoDeviceEnv()
        env.storeA.applyUpserts(listOf(courseSnapshot("course-delete", "待删除")))
        env.engineA.runOnce()
        env.engineB.runOnce()
        assertNotNull(env.storeB.readRow("courses", rowKeyFor("course-delete")))

        env.storeA.applyDeletes(listOf("courses" to rowKeyFor("course-delete")))
        val deleteReport = env.engineA.runOnce()
        assertEquals(1, deleteReport.deletedRows)
        val pull = env.engineB.runOnce()
        assertEquals(1, pull.appliedRemoteRows)
        assertNull(env.storeB.readRow("courses", rowKeyFor("course-delete")))
    }

    @Test
    fun uncertainPutIsRetriedWithSameIdempotencyKeyAndCanonicalBytes() = runBlocking {
        val env = TwoDeviceEnv()
        env.storeA.applyUpserts(listOf(courseSnapshot("course-pending", "幂等重试")))
        env.cloud.failNextPutAfterCommit = true
        try {
            env.engineA.runOnce()
            fail("模拟提交后网络失败必须让首轮失败")
        } catch (expected: IOException) {
            // expected: pending write 已持久化，下一轮必须原样重试
        }
        val pendingBefore = env.stateStoreA.load().pendingWrites.values.single()
        assertEquals(1, env.cloud.mutationCount())

        val report = env.engineA.runOnce()
        assertEquals(1, report.uploadedRows)
        assertEquals(1, env.cloud.mutationCount())
        assertEquals(0, env.engineA.status().pendingWriteCount)
        assertEquals(2, env.cloud.putAttempts(pendingBefore.blobId))
        assertTrue(env.cloud.replayedIdempotencyKeys.contains(pendingBefore.idempotencyKey))
    }

    @Test
    fun expiredCursorOnlyDiscardsCursorAndNeverDeletesDirtyLocalRow() = runBlocking {
        val env = TwoDeviceEnv()
        env.storeA.applyUpserts(listOf(courseSnapshot("course-cursor", "云端版本")))
        env.engineA.runOnce()
        env.engineB.runOnce()
        env.storeB.applyUpserts(listOf(courseSnapshot("course-cursor", "本机 dirty")))
        env.cloud.expireCursors = true

        val report = env.engineB.runOnce()
        assertTrue(report.terminalCursorPresent)
        assertEquals("本机 dirty", courseName(env.storeB, "course-cursor"))
        assertTrue(env.engineB.status().cursorPresent)
    }

    @Test
    fun cloudAccountChangeFreezesStoredSyncStateBeforeAnyPullOrPush() = runBlocking {
        val env = TwoDeviceEnv()
        env.storeA.applyUpserts(listOf(courseSnapshot("course-cloud-switch", "不得上传")))
        val state = env.stateStoreA.load()
        stateStoreASaveWithCloud(env, state, "cloud-a")
        val engine = E2eeSyncEngine(
            env.stateStoreA,
            env.keyA,
            env.cloud.api { env.deviceA },
            env.storeA,
            expectedCloudUserId = "cloud-b"
        )
        try {
            engine.runOnce()
            fail("云账号变化必须 fail-closed")
        } catch (expected: E2eeSyncConsentException) {
            // expected
        }
        assertEquals(0, env.cloud.mutationCount())
        assertEquals("不得上传", courseName(env.storeA, "course-cloud-switch"))
    }

    private suspend fun stateStoreASaveWithCloud(env: TwoDeviceEnv, state: E2eeSyncState, cloudUserId: String) {
        env.stateStoreA.save(
            E2eeSyncState(
                enabled = state.enabled,
                deviceId = state.deviceId,
                consentId = state.consentId,
                syncSpaceId = state.syncSpaceId,
                keyId = state.keyId,
                cloudUserId = cloudUserId,
                deviceLabel = state.deviceLabel,
                cursor = state.cursor,
                objects = state.objects,
                pendingWrites = state.pendingWrites,
                conflicts = state.conflicts,
                lastRunAtEpochMs = state.lastRunAtEpochMs,
                lastRunSummary = state.lastRunSummary
            )
        )
    }

    private class TwoDeviceEnv {
        val deviceA = UUID.randomUUID().toString()
        val deviceB = UUID.randomUUID().toString()
        val cloud = FakeE2eeCloud()
        val keyA = InMemoryE2eeSyncKeyStore()
        val keyB = InMemoryE2eeSyncKeyStore()
        val stateStoreA = InMemoryE2eeSyncStateStore()
        val stateStoreB = InMemoryE2eeSyncStateStore()
        val storeA = InMemoryBackupStore()
        val storeB = InMemoryBackupStore()
        lateinit var engineA: E2eeSyncEngine
        lateinit var engineB: E2eeSyncEngine

        init {
            runBlocking {
                val recovery = keyA.createNew()
                keyB.importRecoveryKey(recovery)
                seedState(keyA, stateStoreA, deviceA)
                seedState(keyB, stateStoreB, deviceB)
                engineA = E2eeSyncEngine(stateStoreA, keyA, cloud.api { deviceA }, storeA)
                engineB = E2eeSyncEngine(stateStoreB, keyB, cloud.api { deviceB }, storeB)
            }
        }

        private suspend fun seedState(keyStore: E2eeSyncKeyStore, store: E2eeSyncStateStore, deviceId: String) {
            val material = keyStore.load()
            try {
                store.save(
                    E2eeSyncState(
                        enabled = true,
                        deviceId = deviceId,
                        consentId = UUID.randomUUID().toString(),
                        syncSpaceId = material.syncSpaceId,
                        keyId = material.keyId
                    )
                )
            } finally {
                material.zero()
            }
        }
    }

    private class InMemoryBackupStore : SyncBackupStore {
        override val schemaVersion: Int = 13
        private val rows = linkedMapOf<String, SyncBackupRowSnapshot>()

        override suspend fun captureRows(): List<SyncBackupRowSnapshot> = rows.values.toList()

        override suspend fun readRow(table: String, rowKey: String): SyncBackupRowSnapshot? =
            rows[SyncBackupObjectKeys.logicalKey(table, rowKey)]

        override suspend fun applyUpserts(rows: List<SyncBackupRowSnapshot>) {
            rows.forEach { this.rows[it.logicalKey] = it }
        }

        override suspend fun applyDeletes(deletes: List<Pair<String, String>>) {
            deletes.forEach { (table, rowKey) -> rows.remove(SyncBackupObjectKeys.logicalKey(table, rowKey)) }
        }
    }

    private class FakeE2eeCloud {
        private data class BlobRecord(
            var ciphertextBase64: String?,
            var metadata: SyncBlobMetadata
        )

        private val blobs = linkedMapOf<String, BlobRecord>()
        private val mutations = mutableListOf<SyncBlobMetadata>()
        private val idempotency = mutableMapOf<String, SyncBlobMetadata>()
        val replayedIdempotencyKeys = linkedSetOf<String>()
        private val putAttemptCounts = mutableMapOf<String, Int>()
        private var sequence = 0L
        var failNextPutAfterCommit = false
        var expireCursors = false
        var pageSize = 2

        fun blobCount(): Int = blobs.values.count { it.metadata.changeKind == "UPSERT" }
        fun mutationCount(): Int = mutations.size
        fun putAttempts(blobId: String): Int = putAttemptCounts[blobId] ?: 0
        fun currentMetadataForOnlyBlob(): SyncBlobMetadata = blobs.values.single().metadata
        fun allCiphertextBase64(): String = blobs.values.mapNotNull { it.ciphertextBase64 }.joinToString("")
        fun allCiphertextBytes(): ByteArray = blobs.values.mapNotNull { it.ciphertextBase64 }
            .fold(ByteArray(0)) { acc, encoded -> acc + Base64.getDecoder().decode(encoded) }

        fun api(deviceId: () -> String): E2eeSyncApi = object : E2eeSyncApi {
            override suspend fun grantConsent(): SyncConsentRecord = SyncConsentRecord(
                id = UUID.randomUUID().toString(),
                purpose = E2eeSyncProtocol.CONSENT_PURPOSE,
                documentVersion = E2eeSyncProtocol.CONSENT_DOCUMENT_VERSION,
                documentSha256 = E2eeSyncProtocol.CONSENT_DOCUMENT_SHA256
            )

            override suspend fun withdrawConsent(consentId: String) = Unit

            override suspend fun listDevices(): List<SyncDevice> = emptyList()

            override suspend fun registerDevice(deviceId: String, consentId: String, deviceLabel: String?, idempotencyKey: String): SyncDevice =
                SyncDevice(deviceId, "ACTIVE", deviceLabel, "ANDROID", 1, "2026-01-01T00:00:00Z", null, null, null)

            override suspend fun revokeDevice(deviceId: String, reason: String): SyncDevice =
                SyncDevice(deviceId, "REVOKED", null, "ANDROID", 1, "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", reason, null)

            override suspend fun putBlob(blobId: String, request: SyncBlobPutInput): SyncBlobMetadata =
                putInternal(blobId, deviceId(), request)

            override suspend fun downloadBlob(blobId: String): Pair<SyncBlobMetadata, String> = downloadInternal(blobId)

            override suspend fun deleteBlob(blobId: String, request: SyncBlobDeleteInput): SyncBlobMetadata =
                deleteInternal(blobId, deviceId(), request)

            override suspend fun listChanges(cursor: String?, limit: Int): SyncChangePage = changesInternal(deviceId(), cursor, limit)
        }

        fun injectPut(blobId: String, deviceId: String, input: SyncBlobPutInput): SyncBlobMetadata =
            putInternal(blobId, deviceId, input)

        private fun putInternal(blobId: String, deviceId: String, request: SyncBlobPutInput): SyncBlobMetadata {
            putAttemptCounts[blobId] = (putAttemptCounts[blobId] ?: 0) + 1
            val idemKey = "put|" + blobId + "|" + request.idempotencyKey
            idempotency[idemKey]?.let {
                replayedIdempotencyKeys += request.idempotencyKey
                return it
            }
            val decoded = Base64.getDecoder().decode(request.ciphertextBase64)
            if (decoded.size !in E2eeSyncProtocol.BUCKET_SIZES || Digests.sha256Hex(decoded) != request.ciphertextSha256) {
                throw E2eeSyncApiException(400, "SYNC_ENVELOPE_INVALID", "invalid ciphertext envelope")
            }
            val existing = blobs[blobId]
            if (existing?.metadata?.changeKind == "TOMBSTONE") {
                throw E2eeSyncApiException(410, "SYNC_BLOB_DELETED", "blob is permanently tombstoned")
            }
            val currentVector = existing?.metadata?.versionVector ?: emptyList()
            if (SyncVectors.compare(request.baseVector, currentVector) != VectorOrdering.EQUAL) {
                throw E2eeSyncApiException(409, "SYNC_VECTOR_CONFLICT", "base vector mismatch")
            }
            val baseMap = SyncVectors.toMap(request.baseVector).toMutableMap()
            val expectedCounter = (baseMap[deviceId.lowercase()] ?: 0) + 1
            if (request.writerCounter != expectedCounter) {
                throw E2eeSyncApiException(409, "SYNC_VECTOR_CONFLICT", "writer counter mismatch")
            }
            baseMap[deviceId.lowercase()] = request.writerCounter
            val metadata = SyncBlobMetadata(
                blobId = blobId,
                changeKind = "UPSERT",
                versionVector = SyncVectors.fromMap(baseMap),
                authorDeviceId = deviceId,
                changeSequence = ++sequence,
                encryption = request.encryption,
                ciphertextSha256 = request.ciphertextSha256,
                ciphertextSizeBytes = decoded.size,
                committedAt = "2026-01-01T00:00:00Z"
            )
            blobs[blobId] = BlobRecord(request.ciphertextBase64, metadata)
            mutations += metadata
            idempotency[idemKey] = metadata
            if (failNextPutAfterCommit) {
                failNextPutAfterCommit = false
                throw IOException("simulated post-commit network loss")
            }
            return metadata
        }

        private fun downloadInternal(blobId: String): Pair<SyncBlobMetadata, String> {
            val record = blobs[blobId] ?: throw E2eeSyncApiException(404, "SYNC_BLOB_NOT_FOUND", "missing blob")
            if (record.metadata.changeKind == "TOMBSTONE") throw E2eeSyncApiException(410, "SYNC_BLOB_DELETED", "blob tombstoned")
            return record.metadata to record.ciphertextBase64.orEmpty()
        }

        private fun deleteInternal(blobId: String, deviceId: String, request: SyncBlobDeleteInput): SyncBlobMetadata {
            val idemKey = "delete|" + blobId + "|" + request.idempotencyKey
            idempotency[idemKey]?.let {
                replayedIdempotencyKeys += request.idempotencyKey
                return it
            }
            val existing = blobs[blobId] ?: throw E2eeSyncApiException(404, "SYNC_BLOB_NOT_FOUND", "missing blob")
            val currentVector = existing.metadata.versionVector
            if (SyncVectors.compare(request.baseVector, currentVector) != VectorOrdering.EQUAL) {
                throw E2eeSyncApiException(409, "SYNC_VECTOR_CONFLICT", "base vector mismatch")
            }
            val baseMap = SyncVectors.toMap(request.baseVector).toMutableMap()
            val expectedCounter = (baseMap[deviceId.lowercase()] ?: 0) + 1
            if (request.writerCounter != expectedCounter) {
                throw E2eeSyncApiException(409, "SYNC_VECTOR_CONFLICT", "writer counter mismatch")
            }
            baseMap[deviceId.lowercase()] = request.writerCounter
            val metadata = SyncBlobMetadata(
                blobId = blobId,
                changeKind = "TOMBSTONE",
                versionVector = SyncVectors.fromMap(baseMap),
                authorDeviceId = deviceId,
                changeSequence = ++sequence,
                encryption = null,
                ciphertextSha256 = null,
                ciphertextSizeBytes = null,
                committedAt = "2026-01-01T00:00:00Z"
            )
            blobs[blobId] = BlobRecord(null, metadata)
            mutations += metadata
            idempotency[idemKey] = metadata
            return metadata
        }

        private fun changesInternal(deviceId: String, cursor: String?, limit: Int): SyncChangePage {
            if (expireCursors && cursor != null) throw E2eeSyncApiException(410, "SYNC_CURSOR_EXPIRED", "cursor expired")
            val position = if (cursor == null) 0L else parseCursor(deviceId, cursor)
            val epoch = sequence
            val scanRows = if (cursor == null) {
                val latestByBlob = mutations.groupBy { it.blobId }.mapValues { entry -> entry.value.maxOf { it.changeSequence } }
                mutations.filter { latestByBlob[it.blobId] == it.changeSequence }
            } else {
                mutations.filter { it.changeSequence > position }
            }.filter { it.changeSequence <= epoch }.sortedBy { it.changeSequence }
            val effectiveLimit = minOf(limit, pageSize)
            val rows = scanRows.take(effectiveLimit)
            val hasMore = scanRows.size > rows.size
            val nextPosition = rows.lastOrNull()?.changeSequence ?: epoch
            val nextCursor = makeCursor(deviceId, if (hasMore) nextPosition else epoch)
            return SyncChangePage(rows, hasMore, nextCursor, epoch, 0L)
        }

        private fun makeCursor(deviceId: String, position: Long): String = "cursor-v1|" + deviceId + "|" + position

        private fun parseCursor(deviceId: String, cursor: String): Long {
            val prefix = "cursor-v1|" + deviceId + "|"
            if (!cursor.startsWith(prefix)) throw E2eeSyncApiException(410, "SYNC_CURSOR_INVALID", "cross-device cursor")
            return cursor.removePrefix(prefix).toLongOrNull()
                ?: throw E2eeSyncApiException(410, "SYNC_CURSOR_INVALID", "malformed cursor")
        }
    }

    companion object {
        private fun courseSnapshot(id: String, name: String, teacher: String = "教师"): SyncBackupRowSnapshot {
            val policy = BackupTableCatalog.policy("courses") ?: error("courses policy missing")
            val cells = linkedMapOf<String, BackupCell>(
                "id" to BackupCell.TextValue(id),
                "externalId" to BackupCell.TextValue("ext-" + id),
                "name" to BackupCell.TextValue(name),
                "teacher" to BackupCell.TextValue(teacher),
                "colorArgb" to BackupCell.LongValue(123456L),
                "creditPlain" to BackupCell.TextValue("3.0"),
                "source" to BackupCell.TextValue("USER"),
                "userModified" to BackupCell.LongValue(1L),
                "lastSyncEpochMilli" to BackupCell.LongValue(1000L)
            )
            return SyncBackupRowSnapshot(policy, BackupRow(cells), emptyMap())
        }

        private fun rowKeyFor(id: String): String = courseSnapshot(id, "x").rowKey

        private suspend fun courseName(store: InMemoryBackupStore, id: String): String? {
            val row = store.readRow("courses", rowKeyFor(id)) ?: return null
            return (row.row.cells["name"] as? BackupCell.TextValue)?.value
        }
    }
}

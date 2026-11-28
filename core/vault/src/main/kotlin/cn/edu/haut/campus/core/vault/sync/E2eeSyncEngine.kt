package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.model.json.Digests
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 行级 E2EE 同步引擎。它只操作 SyncBackupStore 暴露的 dis-backup 行模型；
 * 任何明文冲突、CAS 冲突或 logicalClock 回滚都必须进入用户可解决的 pending conflict。
 */
internal class E2eeSyncEngine(
    private val stateStore: E2eeSyncStateStore,
    private val keyStore: E2eeSyncKeyStore,
    private val api: E2eeSyncApi,
    private val backupStore: SyncBackupStore,
    private val expectedCloudUserId: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val uuid: () -> String = { UUID.randomUUID().toString() }
) {
    private val runMutex: Mutex get() = mutexFor(stateStore)

    suspend fun status(): E2eeSyncStatus {
        val state = stateStore.load()
        return E2eeSyncStatus(
            enabled = state.enabled,
            deviceId = state.deviceId,
            consentId = state.consentId,
            syncSpaceId = state.syncSpaceId,
            cursorPresent = !state.cursor.isNullOrBlank(),
            objectCount = state.objects.size,
            pendingWriteCount = state.pendingWrites.size,
            conflicts = state.conflicts.values.sortedBy { it.createdAtEpochMs },
            lastRunAtEpochMs = state.lastRunAtEpochMs,
            lastRunSummary = state.lastRunSummary
        )
    }

    suspend fun runOnce(): E2eeSyncRunReport = runMutex.withLock {
        val state = stateStore.load()
        val material = keyStore.load()
        try {
            validateRuntimeState(state, material)
            val stats = E2eeSyncRunStats()
            retryPendingWrites(state, material, stats)
            pullWithCursorRecovery(state, material, stats)
            pushLocalChanges(state, material, stats)
            state.lastRunAtEpochMs = clock()
            state.lastRunSummary = "pulled=" + stats.pulledRows + ",applied=" + stats.appliedRemoteRows +
                ",uploaded=" + stats.uploadedRows + ",deleted=" + stats.deletedRows +
                ",conflicts=" + stats.conflictsCreated + ",oversized=" + stats.oversizedRows.size
            stateStore.save(state)
            stats.toReport(state)
        } finally {
            material.zero()
        }
    }

    suspend fun resolveConflict(conflictId: String, resolution: E2eeSyncConflictResolution): E2eeSyncRunReport = runMutex.withLock {
        val state = stateStore.load()
        val material = keyStore.load()
        try {
            validateRuntimeState(state, material)
            val conflict = state.conflicts[conflictId]
                ?: throw E2eeSyncConflictException("同步冲突不存在或已解决")
            val stats = E2eeSyncRunStats()
            when (resolution) {
                E2eeSyncConflictResolution.USE_REMOTE -> applyConflictRemote(state, material, conflict, stats)
                E2eeSyncConflictResolution.KEEP_LOCAL -> keepConflictLocal(state, material, conflict, stats)
            }
            state.conflicts.remove(conflictId)
            stateStore.save(state)
            state.lastRunAtEpochMs = clock()
            state.lastRunSummary = "conflict-resolution=" + resolution.name + ",applied=" + stats.appliedRemoteRows +
                ",uploaded=" + stats.uploadedRows + ",deleted=" + stats.deletedRows
            stateStore.save(state)
            stats.toReport(state)
        } finally {
            material.zero()
        }
    }

    private fun validateRuntimeState(state: E2eeSyncState, material: E2eeSyncKeyMaterial) {
        if (!state.enabled) throw E2eeSyncConsentException("E2EE 云同步尚未启用")
        val deviceId = state.deviceId ?: throw E2eeSyncConsentException("本机尚未注册 E2EE 同步设备")
        E2eeSyncProtocol.requireValidUuid(deviceId, "deviceId")
        if (state.consentId.isNullOrBlank()) throw E2eeSyncConsentException("缺少 E2EE_CLOUD_SYNC consent id")
        if (!expectedCloudUserId.isNullOrBlank() && state.cloudUserId != expectedCloudUserId) {
            throw E2eeSyncConsentException("云账号已变化，原同步状态已冻结；不得把另一云账号的 cursor/向量用于本账号")
        }
        if (state.syncSpaceId != material.syncSpaceId) {
            throw E2eeSyncCryptoException("本机同步状态与恢复密钥的 sync space 不一致")
        }
        if (state.keyId != material.keyId) throw E2eeSyncCryptoException("本机同步状态与 key_id 不一致")
    }

    private suspend fun retryPendingWrites(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        stats: E2eeSyncRunStats
    ) {
        state.pendingWrites.values.sortedBy { it.logicalKey }.forEach { pending ->
            commitPendingWrite(state, material, pending, stats)
        }
    }

    private suspend fun pullWithCursorRecovery(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        stats: E2eeSyncRunStats
    ) {
        try {
            pullChanges(state, material, stats)
        } catch (error: E2eeSyncApiException) {
            if (error.errorCode != "SYNC_CURSOR_EXPIRED" && error.errorCode != "SYNC_CURSOR_INVALID") throw error
            // 契约明确要求：cursor 失效只丢弃 cursor 并全量重扫；本地 dirty 数据不得因此删除。
            state.cursor = null
            stateStore.save(state)
            pullChanges(state, material, stats)
        }
    }

    private suspend fun pullChanges(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        stats: E2eeSyncRunStats
    ) {
        val cursorlessScan = state.cursor.isNullOrBlank()
        val liveBlobIds = if (cursorlessScan) linkedSetOf<String>() else null
        var cursor = state.cursor
        var hasMore: Boolean
        do {
            val page = api.listChanges(cursor, E2eeSyncProtocol.MAX_CHANGE_PAGE_LIMIT)
            stats.pulledRows += page.rows.size
            for (metadata in page.rows) {
                if (metadata.changeKind == "UPSERT") liveBlobIds?.add(metadata.blobId)
                processRemoteMetadata(state, material, metadata, stats)
            }
            state.cursor = page.nextCursor
            stateStore.save(state)
            cursor = page.nextCursor
            hasMore = page.hasMore
        } while (hasMore)
        if (cursorlessScan && liveBlobIds != null) {
            reconcileCursorlessAbsences(state, liveBlobIds, stats)
            stateStore.save(state)
        }
    }

    private suspend fun processRemoteMetadata(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        metadata: SyncBlobMetadata,
        stats: E2eeSyncRunStats
    ) {
        if (metadata.changeKind == "TOMBSTONE") {
            processRemoteTombstone(state, material, metadata, stats)
            return
        }
        val known = state.objects.values.firstOrNull { it.blobId == metadata.blobId && !it.deleted }
        if (known != null && SyncVectors.compare(metadata.versionVector, known.versionVector) == VectorOrdering.EQUAL &&
            known.lastChangeSequence >= metadata.changeSequence
        ) {
            return
        }
        val (downloadMetadata, ciphertextBase64) = api.downloadBlob(metadata.blobId)
        val plaintext = E2eeSyncBlockCipher.decrypt(downloadMetadata, ciphertextBase64, material.masterKey)
        val payload = E2eeSyncPayloadCodec.decode(
            String(plaintext, Charsets.UTF_8),
            expectedSyncSpaceId = material.syncSpaceId,
            currentSchemaVersion = backupStore.schemaVersion
        )
        plaintext.fill(0)
        processRemoteUpsertPayload(state, material, downloadMetadata, payload, stats)
    }

    private suspend fun processRemoteUpsertPayload(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        metadata: SyncBlobMetadata,
        payload: E2eeSyncRowPayload,
        stats: E2eeSyncRunStats
    ) {
        val logicalKey = payload.logicalKey
        if (state.conflicts.values.any { it.logicalKey == logicalKey }) return
        val byBlob = state.objects.values.firstOrNull { it.blobId == metadata.blobId }
        if (byBlob != null && byBlob.logicalKey != logicalKey) {
            throw E2eeSyncCryptoException("同一 blob_id 的 plaintext logical identity 发生变化，已 fail-closed")
        }
        val existing = state.objects[logicalKey]
        val localSnapshot = backupStore.readRow(payload.table, payload.rowKey)
        val localHash = localSnapshot?.rowSha256
        if (localHash == payload.rowSha256) {
            state.objects[logicalKey] = E2eeSyncObjectState(
                logicalKey = logicalKey,
                table = payload.table,
                rowKey = payload.rowKey,
                blobId = metadata.blobId,
                logicalClock = maxOf(payload.logicalClock, existing?.logicalClock ?: 0L),
                syncedRowSha256 = payload.rowSha256,
                versionVector = metadata.versionVector,
                deleted = false,
                lastChangeSequence = metadata.changeSequence
            )
            return
        }
        if (existing != null && !existing.deleted && existing.blobId != metadata.blobId) {
            addConflict(
                state, stats,
                kind = "DUPLICATE_IDENTITY",
                reason = "同一逻辑行存在两个不同步 blob identity，已停止自动合并",
                logicalKey = logicalKey,
                table = payload.table,
                rowKey = payload.rowKey,
                blobId = metadata.blobId,
                localPayloadJson = localPayloadJson(state, material, localSnapshot),
                remotePayloadJson = E2eeSyncPayloadCodec.encode(payload),
                localVector = existing.versionVector,
                remoteVector = metadata.versionVector,
                remoteDeletion = false
            )
            return
        }
        if (existing != null && !existing.deleted) {
            val ordering = SyncVectors.compare(metadata.versionVector, existing.versionVector)
            if (ordering != VectorOrdering.DOMINATES && ordering != VectorOrdering.EQUAL) {
                addConflict(
                    state, stats, "CAS_CONFLICT", "远端 version vector 不回溯本机已知因果位置",
                    logicalKey, payload.table, payload.rowKey, metadata.blobId,
                    localPayloadJson(state, material, localSnapshot), E2eeSyncPayloadCodec.encode(payload),
                    existing.versionVector, metadata.versionVector, remoteDeletion = false
                )
                return
            }
            if (payload.logicalClock < existing.logicalClock) {
                addConflict(
                    state, stats, "ROLLBACK_DETECTED", "远端 plaintext logicalClock 倒退；version-vector fast-forward 不能证明内容较新",
                    logicalKey, payload.table, payload.rowKey, metadata.blobId,
                    localPayloadJson(state, material, localSnapshot), E2eeSyncPayloadCodec.encode(payload),
                    existing.versionVector, metadata.versionVector, remoteDeletion = false
                )
                return
            }
            if (payload.logicalClock == existing.logicalClock && payload.rowSha256 != existing.syncedRowSha256) {
                addConflict(
                    state, stats, "DIVERGENT_CLOCK", "相同 plaintext logicalClock 出现不同内容",
                    logicalKey, payload.table, payload.rowKey, metadata.blobId,
                    localPayloadJson(state, material, localSnapshot), E2eeSyncPayloadCodec.encode(payload),
                    existing.versionVector, metadata.versionVector, remoteDeletion = false
                )
                return
            }
            val localDirty = when {
                localHash == null -> existing.syncedRowSha256 != null
                existing.syncedRowSha256 == null -> true
                else -> localHash != existing.syncedRowSha256
            }
            if (localDirty) {
                addConflict(
                    state, stats, "CONCURRENT_UPDATE", "本机与远端同时修改同一逻辑行，未静默覆盖",
                    logicalKey, payload.table, payload.rowKey, metadata.blobId,
                    localPayloadJson(state, material, localSnapshot), E2eeSyncPayloadCodec.encode(payload),
                    existing.versionVector, metadata.versionVector, remoteDeletion = false
                )
                return
            }
        } else if (localSnapshot != null) {
            addConflict(
                state, stats, "CONCURRENT_UPDATE", "远端对象与本机尚未同步的同名行内容不同",
                logicalKey, payload.table, payload.rowKey, metadata.blobId,
                localPayloadJson(state, material, localSnapshot), E2eeSyncPayloadCodec.encode(payload),
                emptyList(), metadata.versionVector, remoteDeletion = false
            )
            return
        }

        backupStore.applyUpserts(listOf(SyncBackupRowSnapshot(payload.policy, payload.row, payload.blobs)))
        stats.appliedRemoteRows++
        state.objects[logicalKey] = E2eeSyncObjectState(
            logicalKey = logicalKey,
            table = payload.table,
            rowKey = payload.rowKey,
            blobId = metadata.blobId,
            logicalClock = payload.logicalClock,
            syncedRowSha256 = payload.rowSha256,
            versionVector = metadata.versionVector,
            deleted = false,
            lastChangeSequence = metadata.changeSequence
        )
    }

    private suspend fun processRemoteTombstone(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        metadata: SyncBlobMetadata,
        stats: E2eeSyncRunStats
    ) {
        val existing = state.objects.values.firstOrNull { it.blobId == metadata.blobId } ?: return
        if (state.conflicts.values.any { it.logicalKey == existing.logicalKey }) return
        val localSnapshot = backupStore.readRow(existing.table, existing.rowKey)
        val localDirty = when {
            localSnapshot == null -> false
            existing.syncedRowSha256 == null -> true
            else -> localSnapshot.rowSha256 != existing.syncedRowSha256
        }
        if (localDirty) {
            addConflict(
                state, stats, "DELETE_VS_LOCAL", "远端删除遇上本机未同步修改，未静默覆盖",
                existing.logicalKey, existing.table, existing.rowKey, metadata.blobId,
                localPayloadJson(state, material, localSnapshot), null,
                existing.versionVector, metadata.versionVector, remoteDeletion = true
            )
            return
        }
        if (localSnapshot != null) {
            backupStore.applyDeletes(listOf(existing.table to existing.rowKey))
            stats.appliedRemoteRows++
        }
        state.objects[existing.logicalKey] = existing.copy(
            logicalClock = existing.logicalClock + 1,
            syncedRowSha256 = null,
            versionVector = metadata.versionVector,
            deleted = true,
            lastChangeSequence = metadata.changeSequence
        )
    }

    private suspend fun reconcileCursorlessAbsences(
        state: E2eeSyncState,
        liveBlobIds: Set<String>,
        stats: E2eeSyncRunStats
    ) {
        val missing = state.objects.values.filter { !it.deleted && it.blobId !in liveBlobIds }
        for (item in missing) {
            if (state.conflicts.values.any { it.logicalKey == item.logicalKey } || state.pendingWrites.containsKey(item.logicalKey)) continue
            val local = backupStore.readRow(item.table, item.rowKey)
            val clean = local != null && local.rowSha256 == item.syncedRowSha256
            if (local != null && clean) {
                backupStore.applyDeletes(listOf(item.table to item.rowKey))
                stats.appliedRemoteRows++
            }
            // 无论本地是否 dirty，旧 blob_id 在云端都不可再写；dirty 行会在 push 阶段用新随机 blob_id 重建。
            state.objects[item.logicalKey] = item.copy(
                logicalClock = item.logicalClock + 1,
                syncedRowSha256 = null,
                deleted = true
            )
        }
    }

    private suspend fun pushLocalChanges(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        stats: E2eeSyncRunStats
    ) {
        val snapshots = backupStore.captureRows()
        val currentByKey = snapshots.associateBy { it.logicalKey }
        for (snapshot in snapshots) {
            if (state.conflicts.values.any { it.logicalKey == snapshot.logicalKey }) continue
            if (state.pendingWrites.containsKey(snapshot.logicalKey)) continue
            val existing = state.objects[snapshot.logicalKey]
            if (existing != null && !existing.deleted && existing.syncedRowSha256 == snapshot.rowSha256) continue
            try {
                enqueueAndCommitUpsert(state, material, snapshot, existing, stats)
            } catch (error: E2eeSyncOversizeException) {
                stats.oversizedRows += snapshot.table + "/" + snapshot.rowKey.take(48)
            }
        }
        val deletedCandidates = state.objects.values
            .filter { !it.deleted && it.logicalKey !in currentByKey }
            .sortedBy { it.logicalKey }
        for (item in deletedCandidates) {
            if (state.conflicts.values.any { it.logicalKey == item.logicalKey }) continue
            if (state.pendingWrites.containsKey(item.logicalKey)) continue
            enqueueAndCommitDelete(state, material, item, stats)
        }
    }

    private suspend fun enqueueAndCommitUpsert(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        snapshot: SyncBackupRowSnapshot,
        existing: E2eeSyncObjectState?,
        stats: E2eeSyncRunStats
    ) {
        val freshBlob = existing == null || existing.deleted
        val blobId = if (freshBlob) uuid() else existing.blobId
        E2eeSyncProtocol.requireValidUuid(blobId, "blob_id")
        val baseVector = if (freshBlob) emptyList() else existing.versionVector
        val deviceId = state.deviceId ?: throw E2eeSyncConsentException("本机尚未注册同步设备")
        val writerCounter = SyncVectors.nextWriterCounter(baseVector, deviceId)
        val logicalClock = (existing?.logicalClock ?: 0L) + 1L
        val payload = E2eeSyncPayloadCodec.payloadFromSnapshot(
            snapshot = snapshot,
            schemaVersion = backupStore.schemaVersion,
            syncSpaceId = material.syncSpaceId,
            logicalClock = logicalClock,
            writerDeviceId = deviceId
        )
        val payloadJson = E2eeSyncPayloadCodec.encode(payload)
        val plaintext = payloadJson.toByteArray(Charsets.UTF_8)
        val encrypted = try {
            E2eeSyncBlockCipher.encrypt(plaintext, material.masterKey, material.keyId, blobId, writerCounter)
        } finally {
            plaintext.fill(0)
        }
        val key = idempotencyKey("put", deviceId, blobId, writerCounter, encrypted.ciphertextSha256)
        val pending = E2eeSyncPendingWrite(
            logicalKey = snapshot.logicalKey,
            kind = "UPSERT",
            blobId = blobId,
            payloadJson = payloadJson,
            encryption = encrypted.encryption,
            ciphertextBase64 = encrypted.ciphertextBase64,
            ciphertextSha256 = encrypted.ciphertextSha256,
            baseVector = baseVector,
            writerCounter = writerCounter,
            idempotencyKey = key,
            logicalClock = logicalClock,
            rowSha256 = snapshot.rowSha256
        )
        state.pendingWrites[snapshot.logicalKey] = pending
        stateStore.save(state)
        commitPendingWrite(state, material, pending, stats)
    }

    private suspend fun enqueueAndCommitDelete(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        item: E2eeSyncObjectState,
        stats: E2eeSyncRunStats
    ) {
        val deviceId = state.deviceId ?: throw E2eeSyncConsentException("本机尚未注册同步设备")
        val writerCounter = SyncVectors.nextWriterCounter(item.versionVector, deviceId)
        val logicalClock = item.logicalClock + 1L
        val key = idempotencyKey("delete", deviceId, item.blobId, writerCounter, item.logicalKey)
        val pending = E2eeSyncPendingWrite(
            logicalKey = item.logicalKey,
            kind = "DELETE",
            blobId = item.blobId,
            payloadJson = null,
            encryption = null,
            ciphertextBase64 = null,
            ciphertextSha256 = null,
            baseVector = item.versionVector,
            writerCounter = writerCounter,
            idempotencyKey = key,
            logicalClock = logicalClock,
            rowSha256 = null
        )
        state.pendingWrites[item.logicalKey] = pending
        stateStore.save(state)
        commitPendingWrite(state, material, pending, stats)
    }

    private suspend fun commitPendingWrite(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        pending: E2eeSyncPendingWrite,
        stats: E2eeSyncRunStats
    ) {
        try {
            val metadata = if (pending.kind == "UPSERT") {
                api.putBlob(
                    pending.blobId,
                    SyncBlobPutInput(
                        encryption = pending.encryption ?: throw E2eeSyncCryptoException("pending UPSERT 缺少 encryption"),
                        ciphertextBase64 = pending.ciphertextBase64 ?: throw E2eeSyncCryptoException("pending UPSERT 缺少 ciphertext"),
                        ciphertextSha256 = pending.ciphertextSha256 ?: throw E2eeSyncCryptoException("pending UPSERT 缺少 ciphertext digest"),
                        baseVector = pending.baseVector,
                        writerCounter = pending.writerCounter,
                        idempotencyKey = pending.idempotencyKey
                    )
                )
            } else {
                api.deleteBlob(
                    pending.blobId,
                    SyncBlobDeleteInput(
                        baseVector = pending.baseVector,
                        writerCounter = pending.writerCounter,
                        idempotencyKey = pending.idempotencyKey
                    )
                )
            }
            state.pendingWrites.remove(pending.logicalKey)
            val table = pending.logicalKey.substringBefore(':')
            val rowKey = SyncBackupObjectKeys.rowKeyFromLogical(table, pending.logicalKey)
            if (!pending.cleanupOnly) {
                state.objects[pending.logicalKey] = E2eeSyncObjectState(
                    logicalKey = pending.logicalKey,
                    table = table,
                    rowKey = rowKey,
                    blobId = pending.blobId,
                    logicalClock = pending.logicalClock,
                    syncedRowSha256 = if (pending.kind == "UPSERT") pending.rowSha256 else null,
                    versionVector = metadata.versionVector,
                    deleted = pending.kind == "DELETE",
                    lastChangeSequence = metadata.changeSequence
                )
            }
            if (pending.kind == "UPSERT") stats.uploadedRows++ else stats.deletedRows++
            stateStore.save(state)
        } catch (error: E2eeSyncApiException) {
            when (error.errorCode) {
                "SYNC_VECTOR_CONFLICT" -> {
                    state.pendingWrites.remove(pending.logicalKey)
                    stateStore.save(state)
                    registerCasConflict(state, material, pending, stats)
                }
                "SYNC_BLOB_DELETED" -> {
                    if (pending.kind == "DELETE") {
                        state.pendingWrites.remove(pending.logicalKey)
                        if (!pending.cleanupOnly) {
                            markDeletedWithoutServerMetadata(state, pending)
                        }
                        stateStore.save(state)
                    } else {
                        state.pendingWrites.remove(pending.logicalKey)
                        stateStore.save(state)
                        registerRemoteDeletionConflict(state, material, pending, stats)
                    }
                }
                else -> throw error
            }
        }
    }

    private suspend fun registerCasConflict(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        pending: E2eeSyncPendingWrite,
        stats: E2eeSyncRunStats
    ) {
        val table = pending.logicalKey.substringBefore(':')
        val rowKey = SyncBackupObjectKeys.rowKeyFromLogical(table, pending.logicalKey)
        try {
            val (metadata, ciphertext) = api.downloadBlob(pending.blobId)
            val plaintext = E2eeSyncBlockCipher.decrypt(metadata, ciphertext, material.masterKey)
            val payload = E2eeSyncPayloadCodec.decode(
                String(plaintext, Charsets.UTF_8),
                material.syncSpaceId,
                backupStore.schemaVersion
            )
            plaintext.fill(0)
            addConflict(
                state, stats, "CAS_CONFLICT", "version-vector CAS 被拒绝；已下载远端版本等待用户选择",
                pending.logicalKey, table, rowKey, pending.blobId,
                pending.payloadJson, E2eeSyncPayloadCodec.encode(payload),
                pending.baseVector, metadata.versionVector,
                remoteDeletion = false,
                localDeletion = pending.kind == "DELETE"
            )
        } catch (error: E2eeSyncApiException) {
            if (error.errorCode == "SYNC_BLOB_DELETED" || error.errorCode == "SYNC_BLOB_NOT_FOUND") {
                registerRemoteDeletionConflict(state, material, pending, stats)
            } else {
                throw error
            }
        }
    }

    private fun registerRemoteDeletionConflict(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        pending: E2eeSyncPendingWrite,
        stats: E2eeSyncRunStats
    ) {
        val table = pending.logicalKey.substringBefore(':')
        val rowKey = SyncBackupObjectKeys.rowKeyFromLogical(table, pending.logicalKey)
        addConflict(
            state, stats, "DELETE_VS_LOCAL", "远端已 tombstone 该 blob_id；保留本机必须使用全新 blob_id 重建",
            pending.logicalKey, table, rowKey, pending.blobId,
            pending.payloadJson, null,
            pending.baseVector, pending.baseVector,
            remoteDeletion = true,
            localDeletion = pending.kind == "DELETE"
        )
    }

    private fun markDeletedWithoutServerMetadata(state: E2eeSyncState, pending: E2eeSyncPendingWrite) {
        val table = pending.logicalKey.substringBefore(':')
        val rowKey = SyncBackupObjectKeys.rowKeyFromLogical(table, pending.logicalKey)
        state.objects[pending.logicalKey] = E2eeSyncObjectState(
            logicalKey = pending.logicalKey,
            table = table,
            rowKey = rowKey,
            blobId = pending.blobId,
            logicalClock = pending.logicalClock,
            syncedRowSha256 = null,
            versionVector = pending.baseVector,
            deleted = true,
            lastChangeSequence = 0L
        )
    }

    /** 用户明确选择 duplicate identity 的胜出版本后，用严格 CAS tombstone 失败 blob；cleanup 不覆盖胜出对象状态。 */
    private suspend fun tombstoneDuplicateLoser(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        logicalKey: String,
        loserBlobId: String,
        loserVector: SyncVersionVector,
        stats: E2eeSyncRunStats
    ) {
        val deviceId = state.deviceId ?: throw E2eeSyncConsentException("本机尚未注册同步设备")
        val writerCounter = SyncVectors.nextWriterCounter(loserVector, deviceId)
        val key = idempotencyKey("duplicate-cleanup", deviceId, loserBlobId, writerCounter, logicalKey)
        val pending = E2eeSyncPendingWrite(
            logicalKey = logicalKey,
            kind = "DELETE",
            blobId = loserBlobId,
            payloadJson = null,
            encryption = null,
            ciphertextBase64 = null,
            ciphertextSha256 = null,
            baseVector = loserVector,
            writerCounter = writerCounter,
            idempotencyKey = key,
            logicalClock = (state.objects[logicalKey]?.logicalClock ?: 0L) + 1L,
            rowSha256 = null,
            cleanupOnly = true
        )
        state.pendingWrites[logicalKey] = pending
        stateStore.save(state)
        commitPendingWrite(state, material, pending, stats)
    }

    private suspend fun applyConflictRemote(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        conflict: E2eeSyncConflict,
        stats: E2eeSyncRunStats
    ) {
        if (conflict.kind == "DUPLICATE_IDENTITY") {
            val loser = state.objects[conflict.logicalKey]
                ?.takeIf { !it.deleted && it.blobId != conflict.blobId }
            if (loser != null) {
                tombstoneDuplicateLoser(state, material, conflict.logicalKey, loser.blobId, conflict.localVector, stats)
            }
        }
        if (conflict.remoteDeletion) {
            backupStore.applyDeletes(listOf(conflict.table to conflict.rowKey))
            stats.appliedRemoteRows++
            state.objects[conflict.logicalKey] = E2eeSyncObjectState(
                logicalKey = conflict.logicalKey,
                table = conflict.table,
                rowKey = conflict.rowKey,
                blobId = conflict.blobId ?: uuid(),
                logicalClock = (state.objects[conflict.logicalKey]?.logicalClock ?: 0L) + 1L,
                syncedRowSha256 = null,
                versionVector = conflict.remoteVector,
                deleted = true,
                lastChangeSequence = 0L
            )
            return
        }
        val payloadJson = conflict.remotePayloadJson
            ?: throw E2eeSyncConflictException("冲突缺少远端版本，无法选择使用远端")
        val payload = E2eeSyncPayloadCodec.decode(payloadJson, material.syncSpaceId, backupStore.schemaVersion)
        backupStore.applyUpserts(listOf(SyncBackupRowSnapshot(payload.policy, payload.row, payload.blobs)))
        stats.appliedRemoteRows++
        state.objects[conflict.logicalKey] = E2eeSyncObjectState(
            logicalKey = conflict.logicalKey,
            table = conflict.table,
            rowKey = conflict.rowKey,
            blobId = conflict.blobId ?: uuid(),
            logicalClock = payload.logicalClock,
            syncedRowSha256 = payload.rowSha256,
            versionVector = conflict.remoteVector,
            deleted = false,
            lastChangeSequence = 0L
        )
    }

    private suspend fun keepConflictLocal(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        conflict: E2eeSyncConflict,
        stats: E2eeSyncRunStats
    ) {
        if (conflict.kind == "DUPLICATE_IDENTITY" && !conflict.blobId.isNullOrBlank()) {
            tombstoneDuplicateLoser(state, material, conflict.logicalKey, conflict.blobId, conflict.remoteVector, stats)
        }
        if (conflict.localDeletion) {
            val existing = state.objects[conflict.logicalKey]
            val blobId = existing?.takeIf { !it.deleted }?.blobId ?: conflict.blobId ?: uuid()
            val base = when {
                conflict.kind == "CAS_CONFLICT" -> conflict.remoteVector
                existing != null && !existing.deleted -> existing.versionVector
                else -> emptyList()
            }
            val seed = E2eeSyncObjectState(
                logicalKey = conflict.logicalKey,
                table = conflict.table,
                rowKey = conflict.rowKey,
                blobId = blobId,
                logicalClock = existing?.logicalClock ?: 0L,
                syncedRowSha256 = null,
                versionVector = base,
                deleted = false,
                lastChangeSequence = 0L
            )
            state.objects[conflict.logicalKey] = seed
            enqueueAndCommitDelete(state, material, seed, stats)
            return
        }
        val payloadJson = conflict.localPayloadJson
            ?: throw E2eeSyncConflictException("冲突缺少本机版本，无法选择保留本机")
        val payload = E2eeSyncPayloadCodec.decode(payloadJson, material.syncSpaceId, backupStore.schemaVersion)
        // 远端版本仅在用户选择后才覆盖本地；这里把用户刚选择的版本重新写入本机，保证外部并发改动不混入选择结果。
        backupStore.applyUpserts(listOf(SyncBackupRowSnapshot(payload.policy, payload.row, payload.blobs)))
        val existing = state.objects[conflict.logicalKey]
        val mustUseFreshBlob = conflict.remoteDeletion || existing == null || existing.deleted ||
            (conflict.kind == "DUPLICATE_IDENTITY" && existing.blobId == conflict.blobId)
        val blobId = if (mustUseFreshBlob) uuid() else existing.blobId
        val baseVector = when {
            mustUseFreshBlob -> emptyList()
            conflict.blobId == blobId -> conflict.remoteVector
            else -> existing.versionVector
        }
        val remoteLogicalClock = conflict.remotePayloadJson?.let {
            runCatching { E2eeSyncPayloadCodec.decode(it, material.syncSpaceId, backupStore.schemaVersion).logicalClock }.getOrDefault(0L)
        } ?: 0L
        state.objects[conflict.logicalKey] = E2eeSyncObjectState(
            logicalKey = conflict.logicalKey,
            table = conflict.table,
            rowKey = conflict.rowKey,
            blobId = blobId,
            logicalClock = maxOf(maxOf(existing?.logicalClock ?: 0L, payload.logicalClock), remoteLogicalClock),
            syncedRowSha256 = null,
            versionVector = baseVector,
            deleted = false,
            lastChangeSequence = 0L
        )
        stateStore.save(state)
        val snapshot = backupStore.readRow(conflict.table, conflict.rowKey)
            ?: throw E2eeSyncConflictException("本机版本在解决冲突期间消失，已停止避免误写")
        enqueueAndCommitUpsert(state, material, snapshot, state.objects[conflict.logicalKey], stats)
    }

    private fun addConflict(
        state: E2eeSyncState,
        stats: E2eeSyncRunStats,
        kind: String,
        reason: String,
        logicalKey: String,
        table: String,
        rowKey: String,
        blobId: String?,
        localPayloadJson: String?,
        remotePayloadJson: String?,
        localVector: SyncVersionVector,
        remoteVector: SyncVersionVector,
        remoteDeletion: Boolean,
        localDeletion: Boolean = false
    ) {
        if (state.conflicts.values.any { it.logicalKey == logicalKey }) return
        val id = "sc-" + Digests.sha256Hex(kind + "|" + logicalKey).take(24)
        state.conflicts[id] = E2eeSyncConflict(
            id = id,
            logicalKey = logicalKey,
            table = table,
            rowKey = rowKey,
            kind = kind,
            reason = reason,
            blobId = blobId,
            localPayloadJson = localPayloadJson,
            remotePayloadJson = remotePayloadJson,
            localVector = localVector,
            remoteVector = remoteVector,
            remoteDeletion = remoteDeletion,
            localDeletion = localDeletion,
            createdAtEpochMs = clock()
        )
        stats.conflictsCreated++
    }

    private fun localPayloadJson(
        state: E2eeSyncState,
        material: E2eeSyncKeyMaterial,
        snapshot: SyncBackupRowSnapshot?
    ): String? {
        if (snapshot == null) return null
        val deviceId = state.deviceId ?: return null
        val existingClock = state.objects[snapshot.logicalKey]?.logicalClock ?: 0L
        val payload = E2eeSyncPayloadCodec.payloadFromSnapshot(
            snapshot = snapshot,
            schemaVersion = backupStore.schemaVersion,
            syncSpaceId = material.syncSpaceId,
            logicalClock = maxOf(1L, existingClock),
            writerDeviceId = deviceId
        )
        return E2eeSyncPayloadCodec.encode(payload)
    }

    private fun idempotencyKey(operation: String, deviceId: String, blobId: String, writerCounter: Int, fingerprint: String): String =
        "e2ee-" + Digests.sha256Hex(operation, deviceId, blobId, writerCounter.toString(), fingerprint).take(72)

    companion object {
        private val runMutexes = ConcurrentHashMap<String, Mutex>()

        private fun mutexFor(stateStore: E2eeSyncStateStore): Mutex =
            runMutexes.getOrPut(stateStore.lockKey) { Mutex() }
    }
}

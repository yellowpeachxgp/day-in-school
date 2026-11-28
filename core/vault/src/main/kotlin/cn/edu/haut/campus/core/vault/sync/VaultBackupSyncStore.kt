package cn.edu.haut.campus.core.vault.sync

import cn.edu.haut.campus.core.vault.BackupCell
import cn.edu.haut.campus.core.vault.BackupRow
import cn.edu.haut.campus.core.vault.BackupSyncRowDelete
import cn.edu.haut.campus.core.vault.BackupSyncRowUpsert
import cn.edu.haut.campus.core.vault.BackupTableCatalog
import cn.edu.haut.campus.core.vault.BackupValidationException
import cn.edu.haut.campus.core.vault.VaultBackupService

/** dis-backup Room 实现与同步引擎之间的适配器：同步层只见 normalized rows，落库前恢复本机 scope。 */
class VaultBackupSyncStore(
    private val backupService: VaultBackupService
) : SyncBackupStore {
    override val schemaVersion: Int
        get() = backupService.currentSchemaVersionForSync()

    override suspend fun captureRows(): List<SyncBackupRowSnapshot> {
        val archive = backupService.captureArchiveForSync()
        return archive.tables.flatMap { table ->
            table.rows.map { rawRow ->
                val normalized = SyncBackupScopeNormalizer.normalize(table.policy, rawRow)
                SyncBackupRowSnapshot(
                    policy = table.policy,
                    row = normalized,
                    blobs = referencedBlobs(normalized, archive.blobs)
                )
            }
        }
    }

    override suspend fun readRow(table: String, rowKey: String): SyncBackupRowSnapshot? {
        val policy = BackupTableCatalog.policy(table) ?: return null
        val primaryKeys = SyncBackupPrimaryKeyCodec.decode(policy, rowKey)
        val found = backupService.readRowForSync(policy, primaryKeys) ?: return null
        val normalized = SyncBackupScopeNormalizer.normalize(policy, found.first)
        return SyncBackupRowSnapshot(
            policy = policy,
            row = normalized,
            blobs = referencedBlobs(normalized, found.second)
        )
    }

    override suspend fun applyUpserts(rows: List<SyncBackupRowSnapshot>) {
        if (rows.isEmpty()) return
        val localized = rows.map { snapshot ->
            SyncBackupScopeNormalizer.validateNormalized(snapshot.policy, snapshot.row)
            BackupSyncRowUpsert(
                policy = snapshot.policy,
                row = SyncBackupScopeNormalizer.localize(snapshot.policy, snapshot.row, backupService.scope),
                blobs = snapshot.blobs
            )
        }
        backupService.writeRowsForE2eeSync(upserts = localized, deletes = emptyList())
    }

    override suspend fun applyDeletes(deletes: List<Pair<String, String>>) {
        if (deletes.isEmpty()) return
        val rows = deletes.map { (table, rowKey) ->
            val policy = BackupTableCatalog.policy(table)
                ?: throw BackupValidationException("同步删除表不在 dis-backup 白名单: " + table)
            BackupSyncRowDelete(
                policy = policy,
                primaryKeyCells = SyncBackupPrimaryKeyCodec.decode(policy, rowKey)
            )
        }
        backupService.writeRowsForE2eeSync(upserts = emptyList(), deletes = rows)
    }

    private fun referencedBlobs(row: BackupRow, allBlobs: Map<String, ByteArray>): Map<String, ByteArray> {
        val refs = row.cells.values.filterIsInstance<BackupCell.BlobValue>()
        val out = LinkedHashMap<String, ByteArray>()
        refs.forEach { ref ->
            val bytes = allBlobs[ref.sha256] ?: throw BackupValidationException("同步行缺少 blob 内容: " + rowShaContext(row))
            if (bytes.size.toLong() != ref.byteCount) throw BackupValidationException("同步行 blob 大小不一致")
            out[ref.sha256] = bytes
        }
        return out
    }

    private fun rowShaContext(row: BackupRow): String = row.sha256().take(12)
}

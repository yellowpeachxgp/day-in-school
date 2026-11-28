package cn.edu.haut.campus.core.vault

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.edu.haut.campus.core.database.HautDatabase
import cn.edu.haut.campus.core.model.Actor
import cn.edu.haut.campus.core.model.DataProvenance
import cn.edu.haut.campus.core.model.EntityRef
import cn.edu.haut.campus.core.model.EntityType
import cn.edu.haut.campus.core.model.SchoolAccountScope
import cn.edu.haut.campus.core.model.SnapshotCompleteness
import cn.edu.haut.campus.core.model.json.Digests
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

private const val BACKUP_BLOB_READ_CHUNK_BYTES = 512 * 1024

/** Room 实现的 dis-backup/v1 服务：导出一致性快照、口令整体加密、预览后原子恢复。 */
class VaultBackupService(
    private val database: HautDatabase,
    override val scope: SchoolAccountScope,
    private val journal: RoomVaultJournal,
    private val appVersion: String,
    private val clock: () -> Instant = Instant::now,
    private val pbkdf2Iterations: Int = BackupFormatConstants.DEFAULT_PBKDF2_ITERATIONS,
    private val mutationTokenGenerator: () -> String = { UUID.randomUUID().toString() }
) : BackupService {
    private val sqlite: SupportSQLiteDatabase get() = database.openHelper.writableDatabase

    init {
        require(pbkdf2Iterations in BackupFormatConstants.MIN_PBKDF2_ITERATIONS..BackupFormatConstants.MAX_PBKDF2_ITERATIONS) {
            "新导出 PBKDF2 迭代次数必须达到 600k 基线且不超出 v1 上限"
        }
    }

    override suspend fun export(
        scope: SchoolAccountScope,
        selection: BackupSelection,
        passphrase: CharArray,
        out: OutputStream
    ): BackupManifest {
        require(scope == this.scope) { "BackupService scope 与调用 scope 不一致" }
        if (passphrase.size < BackupFormatConstants.MIN_PASSPHRASE_CHARS) throw BackupWeakPassphraseException()
        val archive = database.withTransaction {
            captureArchive(BackupTableCatalog.selected(selection))
        }
        val dataText = BackupWireCodec.encodeData(archive)
        val payloadSha256 = Digests.sha256Hex(dataText)
        val createdAt = clock()
        val fingerprint = backupProfileFingerprint(scope)
        val manifestText = BackupWireCodec.encodeManifest(
            createdAt = createdAt,
            appVersion = appVersion,
            schemaVersion = currentSchemaVersion(),
            profileFingerprint = fingerprint,
            archive = archive,
            payloadSha256 = payloadSha256
        )
        val plaintextZip = BackupZip.create(manifestText, dataText, archive.blobs)
        try {
            val encrypted = BackupEnvelopeCrypto.encrypt(plaintextZip, passphrase, pbkdf2Iterations)
            out.write(encrypted)
            out.flush()
        } finally {
            plaintextZip.fill(0)
        }
        return BackupManifest(
            format = BackupFormatConstants.FORMAT,
            formatVersion = BackupFormatConstants.FORMAT_VERSION,
            createdAt = createdAt,
            appVersion = appVersion,
            schemaVersion = currentSchemaVersion(),
            profileFingerprint = fingerprint,
            entryCount = archive.entryCount,
            payloadSha256 = payloadSha256,
            tables = archive.tables.map {
                BackupTableManifest(it.policy.table, it.rows.size, it.sha256())
            }
        )
    }

    override suspend fun inspect(input: InputStream, passphrase: CharArray): RestorePreview {
        val container = BackupZip.readInputLimited(input)
        val plaintextZip = BackupEnvelopeCrypto.decrypt(container, passphrase)
        val entries = try {
            BackupZip.read(plaintextZip)
        } finally {
            plaintextZip.fill(0)
        }
        val manifestBytes = entries.getValue(BackupFormatConstants.MANIFEST_ENTRY)
        val dataBytes = entries.getValue(BackupFormatConstants.DATA_ENTRY)
        val manifest = BackupWireCodec.parseManifest(String(manifestBytes, Charsets.UTF_8))
        if (manifest.formatVersion > BackupFormatConstants.FORMAT_VERSION) {
            throw BackupVersionTooNewException("format", manifest.formatVersion, BackupFormatConstants.FORMAT_VERSION)
        }
        if (manifest.formatVersion != BackupFormatConstants.FORMAT_VERSION) {
            throw BackupCorruptedException("备份格式版本不受支持")
        }
        val currentSchema = currentSchemaVersion()
        if (manifest.schemaVersion > currentSchema) {
            throw BackupVersionTooNewException("schema", manifest.schemaVersion, currentSchema)
        }
        val actualPayloadSha = Digests.sha256Hex(dataBytes)
        if (actualPayloadSha != manifest.payloadSha256) {
            throw BackupCorruptedException("data.json SHA-256 与 manifest 不一致")
        }

        val parsed = BackupWireCodec.parseData(String(dataBytes, Charsets.UTF_8))
        val backupScope = parsed.first
        val parsedTables = parsed.second
        val declaredBlobEntries = parsed.third
        val backupFingerprint = backupProfileFingerprint(backupScope)
        if (backupFingerprint != manifest.profileFingerprint) {
            throw BackupCorruptedException("data.json scope 与 manifest Profile 指纹不一致")
        }
        if (backupFingerprint != backupProfileFingerprint(scope)) {
            throw BackupIdentityMismatchException()
        }

        val catalogIndexes = parsedTables.map { BackupTableCatalog.policies.indexOf(it.policy) }
        if (catalogIndexes != catalogIndexes.sorted()) {
            throw BackupCorruptedException("备份表顺序不是 dis-backup/v1 目录顺序")
        }
        val validatedTables = parsedTables.map(::validateTableAgainstCurrentSchema)
        validateManifestTables(manifest, validatedTables)
        val blobs = validateBlobEntries(entries, declaredBlobEntries, validatedTables)
        if (manifest.entryCount != validatedTables.sumOf { it.rows.size } + blobs.size) {
            throw BackupCorruptedException("manifest entryCount 与实际条目数不一致")
        }
        val prepared = PreparedBackupArchive(
            scope = backupScope,
            tables = validatedTables,
            blobs = blobs
        )
        return buildPreview(manifest, prepared)
    }

    override suspend fun restore(
        preview: RestorePreview,
        mode: RestoreMode,
        resolutions: Map<BackupRowKey, BackupConflictResolution>
    ): RestoreReport {
        if (preview.scope != scope || backupProfileFingerprint(preview.scope) != backupProfileFingerprint(scope)) {
            throw BackupIdentityMismatchException()
        }
        val conflictKeys = preview.conflicts.map { it.key }.toSet()
        val unknownResolution = resolutions.keys.firstOrNull { it !in conflictKeys }
        if (unknownResolution != null) {
            throw BackupValidationException("冲突选择指向不存在的备份行: " + unknownResolution.table)
        }
        if (mode == RestoreMode.MERGE) {
            val missing = preview.conflicts.map { it.key }.filter { it !in resolutions.keys }
            if (missing.isNotEmpty()) throw BackupRestoreConflictException(missing)
        }
        val journalBefore = database.vaultDao().countEntityJournal()
        val report = journal.writeMany(
            refsProvider = { collectRestoreRefs(preview.preparedArchive) },
            actor = Actor.RESTORE,
            actorDetail = "dis-backup/v1:" + mode.name.lowercase(),
            source = null,
            writeGroupId = null
        ) {
            restoreInsideTransaction(preview, mode, resolutions)
        }
        val journalAfter = database.vaultDao().countEntityJournal()
        return report.copy(journaledEntities = (journalAfter - journalBefore).coerceAtLeast(0))
    }

    private suspend fun buildPreview(manifest: BackupManifest, prepared: PreparedBackupArchive): RestorePreview {
        val policies = prepared.tables.map { it.policy }
        val currentArchive = database.withTransaction { captureArchive(policies) }
        val currentTables = currentArchive.tables.associateBy { it.policy.table }
        val fingerprints = currentArchive.tables.associate { it.policy.table to it.sha256() }
        val tablePreviews = mutableListOf<BackupTablePreview>()
        val conflicts = mutableListOf<BackupRowConflict>()
        var insertCount = 0
        var unchangedCount = 0
        var removableCount = 0

        prepared.tables.forEach { incomingTable ->
            val currentTable = currentTables[incomingTable.policy.table]
                ?: throw BackupValidationException("当前库缺少备份表: " + incomingTable.policy.table)
            val currentRows = currentTable.rows.associateBy { it.primaryKey(currentTable.policy) }
            val incomingRows = incomingTable.rows.associateBy { it.primaryKey(incomingTable.policy) }
            var tableNew = 0
            var tableUnchanged = 0
            var tableConflict = 0
            incomingTable.rows.forEach { incomingRow ->
                val pk = incomingRow.primaryKey(incomingTable.policy)
                val currentRow = currentRows[pk]
                when {
                    currentRow == null -> {
                        tableNew++
                        insertCount++
                    }
                    currentRow.sha256() == incomingRow.sha256() -> {
                        tableUnchanged++
                        unchangedCount++
                    }
                    else -> {
                        tableConflict++
                        conflicts += BackupRowConflict(
                            key = BackupRowKey(incomingTable.policy.table, pk),
                            existingSha256 = currentRow.sha256(),
                            incomingSha256 = incomingRow.sha256()
                        )
                    }
                }
            }
            val removable = (currentRows.keys - incomingRows.keys).size
            removableCount += removable
            tablePreviews += BackupTablePreview(
                table = incomingTable.policy.table,
                incomingRows = incomingTable.rows.size,
                unchangedRows = tableUnchanged,
                newRows = tableNew,
                conflictRows = tableConflict,
                removableRowsInReplace = removable
            )
        }
        return RestorePreview(
            manifest = manifest,
            scope = prepared.scope,
            tablePreviews = tablePreviews,
            conflicts = conflicts,
            insertCount = insertCount,
            unchangedCount = unchangedCount,
            removableCount = removableCount,
            preparedArchive = prepared,
            currentTableFingerprints = fingerprints
        )
    }

    private suspend fun restoreInsideTransaction(
        preview: RestorePreview,
        mode: RestoreMode,
        resolutions: Map<BackupRowKey, BackupConflictResolution>
    ): RestoreReport {
        val prepared = preview.preparedArchive
        val policies = prepared.tables.map { it.policy }
        val currentArchive = captureArchive(policies)
        val currentFingerprints = currentArchive.tables.associate { it.policy.table to it.sha256() }
        if (currentFingerprints != preview.currentTableFingerprints) {
            throw BackupRestoreStaleException()
        }
        val schemas = prepared.tables.associate { it.policy.table to readTableSchema(it.policy) }
        val currentTables = currentArchive.tables.associateBy { it.policy.table }
        val currentRowsByTable = currentTables.mapValues { (table, data) ->
            data.rows.associateBy { it.primaryKey(data.policy) }
        }
        var inserted = 0
        var updated = 0
        var unchanged = 0
        var kept = 0
        var removed = 0

        if (mode == RestoreMode.REPLACE) {
            prepared.tables.asReversed().forEach { table ->
                val currentRows = currentRowsByTable[table.policy.table].orEmpty()
                val incomingKeys = table.rows.mapTo(mutableSetOf()) { it.primaryKey(table.policy) }
                removed += (currentRows.keys - incomingKeys).size
                sqlite.execSQL("DELETE FROM " + quoteIdentifier(table.policy.table))
            }
        }

        prepared.tables.forEach { table ->
            val currentRows = currentRowsByTable[table.policy.table].orEmpty()
            table.rows.forEach { row ->
                val pk = row.primaryKey(table.policy)
                val key = BackupRowKey(table.policy.table, pk)
                val existing = currentRows[pk]
                val existingHash = existing?.sha256()
                val incomingHash = row.sha256()
                when {
                    mode == RestoreMode.MERGE && existingHash == incomingHash -> {
                        unchanged++
                        return@forEach
                    }
                    mode == RestoreMode.MERGE && existingHash != null &&
                        resolutions[key] == BackupConflictResolution.KEEP_EXISTING -> {
                        kept++
                        return@forEach
                    }
                }
                if (existingHash == incomingHash) unchanged++ else if (existingHash == null) inserted++ else updated++
                upsertRow(table, row, schemas.getValue(table.policy.table), prepared.blobs)
            }
        }

        if (mode == RestoreMode.REPLACE) {
            prepared.tables.forEach { table ->
                val actual = countRows(table.policy.table)
                if (actual != table.rows.size.toLong()) {
                    throw BackupValidationException("REPLACE 后行数不一致: " + table.policy.table)
                }
            }
        }
        val searchReport = RoomSearchIndex(database).rebuild(
            scope = scope,
            contributors = listOf(
                CourseSearchIndexContributor(database),
                GradeAttemptSearchIndexContributor(database),
                ExamScheduleSearchIndexContributor(database),
                PersonalEventSearchIndexContributor(database)
            )
        )
        return RestoreReport(
            mode = mode,
            insertedRows = inserted,
            updatedRows = updated,
            unchangedRows = unchanged,
            keptExistingRows = kept,
            removedRows = removed,
            journaledEntities = 0,
            searchIndexedDocuments = searchReport.indexedDocCount
        )
    }

    /** W4-12 专用一致性快照入口；仍是 dis-backup 表目录，不改成云同步专用数据模型。 */
    internal suspend fun captureArchiveForSync(): PreparedBackupArchive = database.withTransaction {
        captureArchive(BackupTableCatalog.policies)
    }

    internal fun currentSchemaVersionForSync(): Int = currentSchemaVersion()

    /** 按 typed primary key 精准读取一行；返回值仍含本机 scope，sync 层负责规范化/本地化。 */
    internal fun readRowForSync(
        policy: BackupTablePolicy,
        primaryKeyCells: Map<String, BackupCell>
    ): Pair<BackupRow, Map<String, ByteArray>>? {
        if (primaryKeyCells.keys != policy.primaryKeys.toSet()) {
            throw BackupValidationException("同步读取主键列集合不一致: " + policy.table)
        }
        val schema = readTableSchema(policy)
        val selectColumns = schema.backupColumns.joinToString(",") { quoteIdentifier(it.name) }
        val where = policy.primaryKeys.joinToString(" AND ") { quoteIdentifier(it) + " = ?" }
        val args = policy.primaryKeys.map { column ->
            when (val cell = primaryKeyCells.getValue(column)) {
                BackupCell.NullValue -> null
                is BackupCell.LongValue -> cell.value
                is BackupCell.DoubleValue -> cell.value
                is BackupCell.TextValue -> cell.value
                is BackupCell.BlobValue -> throw BackupValidationException("blob 不能作为同步读取主键")
            }
        }.toTypedArray()
        val blobs = LinkedHashMap<String, ByteArray>()
        sqlite.query(
            "SELECT " + selectColumns + " FROM " + quoteIdentifier(policy.table) + " WHERE " + where + " LIMIT 1",
            args
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val cells = LinkedHashMap<String, BackupCell>()
            schema.backupColumns.forEachIndexed { index, column ->
                cells[column.name] = readCell(cursor, index, blobs)
            }
            return BackupRow(cells) to blobs
        }
    }

    private suspend fun collectRestoreRefs(prepared: PreparedBackupArchive): List<EntityRef> {
        val courseIds = linkedSetOf<String>()
        val ruleToCourse = linkedMapOf<String, String>()
        fun addCellText(row: BackupRow, column: String, target: MutableSet<String>) {
            (row.cells[column] as? BackupCell.TextValue)?.value?.takeIf { it.isNotBlank() }?.let(target::add)
        }
        if (prepared.tables.any { it.policy.category == BackupCategory.ACADEMIC }) {
            courseIds += queryTextColumn("courses", "id")
            queryTextPair("course_schedule_rules", "id", "courseId").forEach { (rule, course) ->
                if (course != null) {
                    ruleToCourse[rule] = course
                    courseIds += course
                }
            }
            queryTextPair("occurrence_overrides", "id", "courseId").values.filterNotNull().forEach(courseIds::add)
            prepared.tables.forEach { table ->
                when (table.policy.table) {
                    "courses" -> table.rows.forEach { row -> addCellText(row, "id", courseIds) }
                    "course_schedule_rules" -> table.rows.forEach { row ->
                        addCellText(row, "courseId", courseIds)
                        val ruleId = (row.cells["id"] as? BackupCell.TextValue)?.value
                        val courseId = (row.cells["courseId"] as? BackupCell.TextValue)?.value
                        if (ruleId != null && courseId != null) ruleToCourse[ruleId] = courseId
                    }
                    "occurrence_overrides" -> table.rows.forEach { row ->
                        addCellText(row, "courseId", courseIds)
                        val ruleId = (row.cells["courseRuleId"] as? BackupCell.TextValue)?.value
                        ruleId?.let(ruleToCourse::get)?.let(courseIds::add)
                    }
                }
            }
        }
        val idsByType = linkedMapOf<EntityType, MutableSet<String>>()
        if (courseIds.isNotEmpty()) idsByType[EntityType.COURSE] = courseIds
        prepared.tables.forEach { table ->
            val type = table.policy.entityType
            if (type != null && type != EntityType.COURSE) {
                val target = idsByType.getOrPut(type) { linkedSetOf() }
                table.rows.forEach { row -> addCellText(row, table.policy.primaryKeys.single(), target) }
                queryTextColumn(table.policy.table, table.policy.primaryKeys.single()).forEach(target::add)
            }
        }
        return idsByType.flatMap { (type, ids) -> ids.sorted().map { EntityRef(type, it, scope) } }.distinct()
    }

    private fun captureArchive(policies: List<BackupTablePolicy>): PreparedBackupArchive {
        require(policies.isNotEmpty()) { "备份表选择不能为空" }
        val blobs = LinkedHashMap<String, ByteArray>()
        val tables = policies.map { exportTable(it, blobs) }
        return PreparedBackupArchive(scope = scope, tables = tables, blobs = blobs)
    }

    private data class CurrentTableSchema(
        val allColumns: List<BackupColumnSchema>,
        val backupColumns: List<BackupColumnSchema>
    )

    private fun readTableSchema(policy: BackupTablePolicy): CurrentTableSchema {
        val columns = mutableListOf<BackupColumnSchema>()
        sqlite.query("PRAGMA table_info(" + quoteIdentifier(policy.table) + ")").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
            val pkIndex = cursor.getColumnIndexOrThrow("pk")
            while (cursor.moveToNext()) {
                columns += BackupColumnSchema(
                    name = cursor.getString(nameIndex),
                    declaredType = cursor.getString(typeIndex).orEmpty().uppercase(),
                    notNull = cursor.getInt(notNullIndex) != 0,
                    primaryKeyPosition = cursor.getInt(pkIndex)
                )
            }
        }
        if (columns.isEmpty()) throw BackupValidationException("当前库缺少备份表: " + policy.table)
        val actualPrimaryKeys = columns.filter { it.primaryKeyPosition > 0 }.sortedBy { it.primaryKeyPosition }.map { it.name }
        if (actualPrimaryKeys != policy.primaryKeys) {
            throw BackupValidationException("备份主键与当前 schema 不一致: " + policy.table)
        }
        val allNames = columns.map { it.name }.toSet()
        val unknownExported = policy.exportedColumns - allNames
        if (unknownExported.isNotEmpty()) {
            throw BackupValidationException("备份列白名单在当前 schema 不存在: " + policy.table + ":" + unknownExported.sorted().joinToString(","))
        }
        val unknownGenerated = policy.generatedColumns.keys - allNames
        if (unknownGenerated.isNotEmpty()) {
            throw BackupValidationException("备份生成列在当前 schema 不存在: " + policy.table + ":" + unknownGenerated.sorted().joinToString(","))
        }
        val nonExported = allNames - policy.exportedColumns
        val missingRestoreDefault = nonExported - policy.generatedColumns.keys
        if (missingRestoreDefault.isNotEmpty()) {
            throw BackupValidationException("备份省略列缺少恢复期安全默认值: " + policy.table + ":" + missingRestoreDefault.sorted().joinToString(","))
        }
        val backupColumns = columns.filter { it.name in policy.exportedColumns }
        if (backupColumns.size != policy.exportedColumns.size) {
            throw BackupValidationException("备份列白名单与当前 schema 不完全匹配: " + policy.table)
        }
        BackupSchemaSafety.validateColumns(policy, backupColumns.map { it.name })
        return CurrentTableSchema(allColumns = columns, backupColumns = backupColumns)
    }

    private fun exportTable(policy: BackupTablePolicy, blobs: MutableMap<String, ByteArray>): BackupTableData {
        val schema = readTableSchema(policy)
        val columns = schema.backupColumns
        // blob_store.content 可能达到数 MiB；不能把整行放进 Android CursorWindow。
        // 主查询用空 BLOB 占位保持列顺序，真正的内容在该行元数据读出后按 <=512KiB 分块补读。
        val selectColumns = columns.joinToString(",") { column ->
            if (policy.table == "blob_store" && column.name == "content") {
                "X'' AS " + quoteIdentifier(column.name)
            } else {
                quoteIdentifier(column.name)
            }
        }
        val orderBy = policy.primaryKeys.joinToString(",") { quoteIdentifier(it) }
        val rows = mutableListOf<BackupRow>()
        sqlite.query(
            "SELECT " + selectColumns + " FROM " + quoteIdentifier(policy.table) + " ORDER BY " + orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val cells = LinkedHashMap<String, BackupCell>()
                columns.forEachIndexed { index, column ->
                    cells[column.name] = if (policy.table == "blob_store" && column.name == "content") {
                        // 占位单元格只保持列位置；下一行元数据齐备后会替换为真实分块内容。
                        BackupCell.NullValue
                    } else {
                        readCell(cursor, index, blobs)
                    }
                }
                if (policy.table == "blob_store") {
                    val blobId = (cells["blobId"] as? BackupCell.TextValue)?.value
                        ?: throw BackupValidationException("blob_store 行缺少 blobId")
                    val byteCount = (cells["byteCount"] as? BackupCell.LongValue)?.value
                        ?: throw BackupValidationException("blob_store 行缺少 byteCount: " + blobId)
                    val content = readBlobContentInChunks(blobId, byteCount)
                    // LinkedHashMap 替换已存在的 content 键不会改变列顺序，manifest 摘要保持 schema 列序。
                    cells["content"] = registerBlobBytes(content, blobs)
                }
                val row = BackupRow(cells)
                BackupSchemaSafety.validateCellValues(row.cells, "备份表 " + policy.table)
                validateScopeRow(policy, row)
                validateBlobStoreRow(policy, row)
                rows += row
            }
        }
        return BackupTableData(policy, columns, rows)
    }

    private fun readCell(cursor: Cursor, index: Int, blobs: MutableMap<String, ByteArray>): BackupCell =
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> BackupCell.NullValue
            Cursor.FIELD_TYPE_INTEGER -> BackupCell.LongValue(cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> BackupCell.DoubleValue(cursor.getDouble(index))
            Cursor.FIELD_TYPE_STRING -> BackupCell.TextValue(cursor.getString(index))
            Cursor.FIELD_TYPE_BLOB -> registerBlobBytes(cursor.getBlob(index), blobs)
            else -> throw BackupValidationException("SQLite 单元格类型不受支持")
        }

    private fun registerBlobBytes(bytes: ByteArray, blobs: MutableMap<String, ByteArray>): BackupCell.BlobValue {
        BackupSchemaSafety.validateBlobBytes(bytes, "备份 blob 内容")
        val sha = Digests.sha256Hex(bytes)
        val existing = blobs[sha]
        if (existing != null && !existing.contentEquals(bytes)) {
            throw BackupValidationException("检测到 SHA-256 blob 冲突，已停止备份")
        }
        if (existing == null) blobs[sha] = bytes.copyOf()
        return BackupCell.BlobValue(sha, bytes.size.toLong(), BackupWireCodec.blobPathFor(sha))
    }

    /**
     * 逐块读取 blob_store.content。SQLite substr 对 BLOB 以 1-based 字节偏移计；
     * 每块 512KiB，低于 Android CursorWindow 常见 2MiB 限制，数 MiB 培养方案/附件不再阻塞全量备份。
     */
    private fun readBlobContentInChunks(blobId: String, byteCount: Long): ByteArray {
        if (byteCount < 0 || byteCount > Int.MAX_VALUE) {
            throw BackupValidationException("blob_store 行 byteCount 超出可备份范围: " + blobId)
        }
        val total = byteCount.toInt()
        if (total == 0) return ByteArray(0)
        val result = ByteArray(total)
        var offset = 0
        while (offset < total) {
            val length = minOf(BACKUP_BLOB_READ_CHUNK_BYTES, total - offset)
            sqlite.query(
                "SELECT substr(content, ?, ?) FROM blob_store WHERE blobId = ?",
                arrayOf(offset + 1, length, blobId)
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) {
                    throw BackupValidationException("blob_store 内容分块读取失败: " + blobId)
                }
                val chunk = cursor.getBlob(0)
                if (chunk.size != length) {
                    throw BackupValidationException("blob_store 内容分块长度不一致: " + blobId)
                }
                System.arraycopy(chunk, 0, result, offset, chunk.size)
            }
            offset += length
        }
        return result
    }

    private fun validateTableAgainstCurrentSchema(input: BackupTableData): BackupTableData {
        val schema = readTableSchema(input.policy)
        val expectedNames = schema.backupColumns.map { it.name }
        val inputNames = input.columns.map { it.name }
        if (inputNames != expectedNames) {
            throw BackupValidationException("备份列集合与当前 schema 不一致: " + input.policy.table)
        }
        input.rows.forEach { row ->
            BackupSchemaSafety.validateCellValues(row.cells, "恢复输入表 " + input.policy.table)
            schema.backupColumns.forEach { column -> validateCellAgainstColumn(row.cells.getValue(column.name), column, input.policy.table) }
            validateScopeRow(input.policy, row)
            validateBlobStoreRow(input.policy, row)
        }
        return BackupTableData(input.policy, schema.backupColumns, input.rows)
    }

    private fun validateCellAgainstColumn(cell: BackupCell, column: BackupColumnSchema, table: String) {
        if (cell == BackupCell.NullValue) {
            if (column.notNull) throw BackupValidationException("非空列出现 NULL: " + table + "." + column.name)
            return
        }
        val type = column.declaredType
        val valid = when {
            type.contains("INT") -> cell is BackupCell.LongValue
            type.contains("TEXT") || type.contains("CHAR") || type.contains("CLOB") -> cell is BackupCell.TextValue
            type.contains("BLOB") -> cell is BackupCell.BlobValue
            type.contains("REAL") || type.contains("FLOA") || type.contains("DOUB") || type.contains("NUM") ->
                cell is BackupCell.DoubleValue || cell is BackupCell.LongValue
            else -> true
        }
        if (!valid) throw BackupValidationException("备份单元格类型不匹配: " + table + "." + column.name)
    }

    private fun validateScopeRow(policy: BackupTablePolicy, row: BackupRow) {
        val columns = policy.scopeColumns ?: return
        fun requireText(column: String, expected: String) {
            val actual = (row.cells[column] as? BackupCell.TextValue)?.value
            if (actual != expected) throw BackupValidationException("备份行 scope 不匹配: " + policy.table + "." + column)
        }
        requireText(columns.school, scope.schoolId.value)
        requireText(columns.account, scope.accountId.value)
        requireText(columns.profile, scope.profileId.value)
        val campusCell = row.cells[columns.campus]
        val expectedCampus = scope.campusId?.value
        val campusMatches = if (expectedCampus == null) {
            campusCell == BackupCell.NullValue
        } else {
            (campusCell as? BackupCell.TextValue)?.value == expectedCampus
        }
        if (!campusMatches) throw BackupValidationException("备份行 campus scope 不匹配: " + policy.table)
    }

    private fun validateBlobStoreRow(policy: BackupTablePolicy, row: BackupRow) {
        if (policy.table != "blob_store") return
        val shaCell = (row.cells["sha256"] as? BackupCell.TextValue)?.value
        val countCell = (row.cells["byteCount"] as? BackupCell.LongValue)?.value
        val contentCell = row.cells["content"] as? BackupCell.BlobValue
        if (shaCell == null || countCell == null || contentCell == null || shaCell != contentCell.sha256 || countCell != contentCell.byteCount) {
            throw BackupValidationException("blob_store 行摘要/大小/内容引用不一致")
        }
    }

    private fun validateManifestTables(manifest: BackupManifest, tables: List<BackupTableData>) {
        if (manifest.tables.size != tables.size) throw BackupCorruptedException("manifest 表数量与 data.json 不一致")
        manifest.tables.forEachIndexed { index, tableManifest ->
            val actual = tables[index]
            if (tableManifest.table != actual.policy.table ||
                tableManifest.rowCount != actual.rows.size ||
                tableManifest.sha256 != actual.sha256()
            ) {
                throw BackupCorruptedException("manifest 表摘要与 data.json 不一致: " + tableManifest.table)
            }
        }
    }

    private fun validateBlobEntries(
        zipEntries: Map<String, ByteArray>,
        declared: List<Triple<String, Long, String>>,
        tables: List<BackupTableData>
    ): Map<String, ByteArray> {
        val actual = zipEntries.filterKeys { it.startsWith(BackupFormatConstants.BLOB_ENTRY_PREFIX) }
        if (declared.size != actual.size) throw BackupCorruptedException("blobEntries 数量与 ZIP blob 条目不一致")
        val blobs = LinkedHashMap<String, ByteArray>()
        declared.forEach { (sha, count, path) ->
            val bytes = actual[path] ?: throw BackupCorruptedException("ZIP 缺少 blob 条目: " + path)
            if (bytes.size.toLong() != count || Digests.sha256Hex(bytes) != sha) {
                throw BackupCorruptedException("blob ZIP 条目摘要或大小不一致")
            }
            BackupSchemaSafety.validateBlobBytes(bytes, "恢复 blob 内容")
            blobs[sha] = bytes
        }
        val referenced = tables.flatMap { table -> table.rows.flatMap { it.cells.values } }
            .filterIsInstance<BackupCell.BlobValue>()
            .map { it.sha256 }
            .toSet()
        if (referenced != blobs.keys) {
            throw BackupCorruptedException("blob 条目引用集合与 data.json 不一致")
        }
        return blobs
    }

    /** 应用一批已通过客户端冲突检查的远端同步行；业务行、逐行 journal、outbox 与可用搜索投影保持同事务。 */
    internal suspend fun writeRowsForE2eeSync(
        upserts: List<BackupSyncRowUpsert>,
        deletes: List<BackupSyncRowDelete>
    ): Int {
        if (upserts.isEmpty() && deletes.isEmpty()) return 0
        val targets = linkedMapOf<EntityRef, BackupSyncEntityRefs.Target>()
        upserts.forEach { input ->
            val target = BackupSyncEntityRefs.target(input.policy, input.row.cells, scope)
            targets[target.ref] = target
        }
        deletes.forEach { input ->
            val target = BackupSyncEntityRefs.target(input.policy, input.primaryKeyCells, scope)
            targets[target.ref] = target
        }
        val refs = targets.keys.toList()
        return journal.writeWithSnapshotReader(
            refsProvider = { refs },
            snapshotReader = { ref ->
                val target = targets.getValue(ref)
                val found = readRowForSync(target.policy, target.primaryKeyCells)?.first
                BackupSyncEntityRefs.canonicalSnapshot(found)
            },
            actor = Actor.SYNC,
            actorDetail = "e2ee-cloud-sync",
            source = null,
            writeGroupId = null
        ) {
            var applied = 0
            upserts.forEach { input ->
                val schema = readTableSchema(input.policy)
                val inputTable = BackupTableData(input.policy, schema.backupColumns, listOf(input.row))
                val validatedTable = validateTableAgainstCurrentSchema(inputTable)
                upsertRow(validatedTable, validatedTable.rows.single(), schema, input.blobs, useSyncProvenance = true)
                applied++
            }
            deletes.forEach { input ->
                deleteRowInternal(input.policy, input.primaryKeyCells)
                applied++
            }
            applied
        }
    }

    private fun deleteRowInternal(policy: BackupTablePolicy, primaryKeyCells: Map<String, BackupCell>) {
        if (primaryKeyCells.keys != policy.primaryKeys.toSet()) {
            throw BackupValidationException("同步删除主键列集合不一致: " + policy.table)
        }
        val where = policy.primaryKeys.joinToString(" AND ") { quoteIdentifier(it) + " = ?" }
        val statement = sqlite.compileStatement("DELETE FROM " + quoteIdentifier(policy.table) + " WHERE " + where)
        try {
            policy.primaryKeys.forEachIndexed { index, column ->
                when (val cell = primaryKeyCells.getValue(column)) {
                    BackupCell.NullValue -> statement.bindNull(index + 1)
                    is BackupCell.LongValue -> statement.bindLong(index + 1, cell.value)
                    is BackupCell.DoubleValue -> statement.bindDouble(index + 1, cell.value)
                    is BackupCell.TextValue -> statement.bindString(index + 1, cell.value)
                    is BackupCell.BlobValue -> throw BackupValidationException("blob 不能作为同步删除主键")
                }
            }
            statement.executeUpdateDelete()
        } finally {
            statement.close()
        }
    }

    private fun upsertRow(
        table: BackupTableData,
        row: BackupRow,
        schema: CurrentTableSchema,
        blobs: Map<String, ByteArray>,
        useSyncProvenance: Boolean = false
    ) {
        val valueColumns = mutableListOf<String>()
        val values = mutableListOf<BackupCell>()
        schema.allColumns.forEach { column ->
            val backupCell = row.cells[column.name]
            val generated = if (backupCell == null) generatedCell(table.policy, column.name, useSyncProvenance) else null
            if (backupCell != null || generated != null) {
                valueColumns += column.name
                values += (backupCell ?: generated!!)
            }
        }
        if (valueColumns.size != schema.allColumns.size) {
            throw BackupValidationException("恢复行缺少必填列: " + table.policy.table)
        }
        val sql = "INSERT OR REPLACE INTO " + quoteIdentifier(table.policy.table) +
            " (" + valueColumns.joinToString(",") { quoteIdentifier(it) } + ") VALUES (" +
            valueColumns.joinToString(",") { "?" } + ")"
        val statement = sqlite.compileStatement(sql)
        try {
            values.forEachIndexed { index, cell -> bindCell(statement, index + 1, cell, blobs) }
            val rowId = statement.executeInsert()
            if (rowId < 0) throw BackupValidationException("恢复行写入失败: " + table.policy.table)
        } finally {
            statement.close()
        }
    }

    private fun generatedCell(policy: BackupTablePolicy, column: String, useSyncProvenance: Boolean = false): BackupCell? =
        when (policy.generatedColumns[column]) {
            GeneratedColumnKind.OCCURRENCE_MUTATION_TOKEN -> BackupCell.TextValue(mutationTokenGenerator())
            GeneratedColumnKind.NULL_ON_RESTORE -> BackupCell.NullValue
            GeneratedColumnKind.EMPTY_JSON_OBJECT_ON_RESTORE -> BackupCell.TextValue("{}")
            GeneratedColumnKind.EMPTY_JSON_ARRAY_ON_RESTORE -> BackupCell.TextValue("[]")
            GeneratedColumnKind.RESTORED_PERSONAL_EVENT_PROVENANCE -> BackupCell.TextValue(
                if (useSyncProvenance) syncProvenanceJson() else restoredProvenanceJson()
            )
            null -> null
        }

    private fun syncProvenanceJson(): String = canonicalJsonOf(
        encodeProvenance(
            DataProvenance(
                providerId = "dis-sync-e2ee/v1",
                schoolId = scope.schoolId,
                accountId = scope.accountId,
                sourceSystem = "E2EE_CLOUD_SYNC",
                sourceRecordId = null,
                fetchedAt = null,
                completeness = SnapshotCompleteness.UNKNOWN,
                schemaVersion = 1,
                contentRevision = null
            )
        )
    ).text

    private fun restoredProvenanceJson(): String = canonicalJsonOf(
        encodeProvenance(
            DataProvenance(
                providerId = "dis-backup/v1",
                schoolId = scope.schoolId,
                accountId = scope.accountId,
                sourceSystem = "LOCAL_BACKUP_RESTORE",
                sourceRecordId = null,
                fetchedAt = null,
                completeness = SnapshotCompleteness.UNKNOWN,
                schemaVersion = 1,
                contentRevision = null
            )
        )
    ).text

    private fun bindCell(
        statement: androidx.sqlite.db.SupportSQLiteStatement,
        index: Int,
        cell: BackupCell,
        blobs: Map<String, ByteArray>
    ) {
        when (cell) {
            BackupCell.NullValue -> statement.bindNull(index)
            is BackupCell.LongValue -> statement.bindLong(index, cell.value)
            is BackupCell.DoubleValue -> statement.bindDouble(index, cell.value)
            is BackupCell.TextValue -> statement.bindString(index, cell.value)
            is BackupCell.BlobValue -> {
                val bytes = blobs[cell.sha256] ?: throw BackupValidationException("恢复 blob 内容缺失: " + cell.sha256)
                if (bytes.size.toLong() != cell.byteCount) throw BackupValidationException("恢复 blob 大小不一致")
                statement.bindBlob(index, bytes)
            }
        }
    }

    private fun queryTextColumn(table: String, column: String): Set<String> {
        val out = linkedSetOf<String>()
        sqlite.query("SELECT " + quoteIdentifier(column) + " FROM " + quoteIdentifier(table)).use { cursor ->
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) out += cursor.getString(0)
            }
        }
        return out
    }

    private fun queryTextPair(table: String, first: String, second: String): Map<String, String?> {
        val out = linkedMapOf<String, String?>()
        sqlite.query(
            "SELECT " + quoteIdentifier(first) + "," + quoteIdentifier(second) + " FROM " + quoteIdentifier(table)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                if (!cursor.isNull(0)) out[cursor.getString(0)] = if (cursor.isNull(1)) null else cursor.getString(1)
            }
        }
        return out
    }

    private fun countRows(table: String): Long =
        sqlite.query("SELECT COUNT(*) FROM " + quoteIdentifier(table)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    private fun currentSchemaVersion(): Int = sqlite.version

    private fun quoteIdentifier(identifier: String): String =
        "\"" + identifier.replace("\"", "\"\"") + "\""
}

internal data class BackupSyncRowUpsert(
    val policy: BackupTablePolicy,
    val row: BackupRow,
    val blobs: Map<String, ByteArray>
)

internal data class BackupSyncRowDelete(
    val policy: BackupTablePolicy,
    val primaryKeyCells: Map<String, BackupCell>
)

internal fun backupProfileFingerprint(scope: SchoolAccountScope): String = Digests.sha256Hex(
    "dis-backup-profile/v1",
    scope.schoolId.value,
    scope.accountId.value,
    scope.profileId.value,
    scope.campusId?.value.orEmpty()
)

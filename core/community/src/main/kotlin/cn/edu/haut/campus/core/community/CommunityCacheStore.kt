package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 当前云身份在缓存键中的最小事实。原始 cloudUserId 只参与进程内哈希键材料，不写入缓存文件。 */
data class CommunityCacheIdentity(
    val cloudUserId: String,
    val identityGeneration: Long,
) {
    init {
        require(cloudUserId.isNotBlank() && cloudUserId.length <= 256) { "cloudUserId 不能为空且不能超过 256 字符" }
        require(identityGeneration >= 0L) { "identityGeneration 不能为负" }
    }

    internal fun keyMaterial(): JsonValue.Obj = JsonValue.Obj(
        linkedMapOf(
            "cloud_user_id" to JsonValue.Str(cloudUserId),
            "identity_generation" to JsonValue.Num(identityGeneration.toString()),
        ),
    )
}

/** 读响应缓存键。绝不包含 Authorization、refresh token、cookie 或设备秘密。 */
data class CommunityCacheKey(
    val cloudIdentity: CommunityCacheIdentity,
    val apiBaseUrl: String,
    val operationId: String,
    val resolvedPath: String,
    val queryParams: Map<String, List<String>> = emptyMap(),
    val body: JsonValue.Obj? = null,
) {
    init {
        require(apiBaseUrl.isNotBlank()) { "apiBaseUrl 不能为空" }
        require(operationId.isNotBlank()) { "operationId 不能为空" }
        require(resolvedPath.startsWith("/api/v1/")) { "缓存路径必须是 /api/v1 相对路径" }
    }

    val storageId: String
        get() = Digests.sha256Hex(Json.canonical(keyMaterial()))

    internal fun keyMaterial(): JsonValue.Obj {
        val queryFields = linkedMapOf<String, JsonValue>()
        queryParams.toSortedMap().forEach { (name, values) ->
            queryFields[name] = JsonValue.Arr(values.map { JsonValue.Str(it) })
        }
        return JsonValue.Obj(
            linkedMapOf(
                "cloud_identity" to cloudIdentity.keyMaterial(),
                "api_base_url" to JsonValue.Str(apiBaseUrl),
                "operation_id" to JsonValue.Str(operationId),
                "path" to JsonValue.Str(resolvedPath),
                "query" to JsonValue.Obj(queryFields),
                "body" to (body ?: JsonValue.Null),
            ),
        )
    }
}

data class CommunityCachedEnvelope(
    val envelope: CommunityEnvelope,
    val cachedAtEpochMillis: Long,
)

interface CommunityCacheStore {
    suspend fun read(key: CommunityCacheKey): CommunityCachedEnvelope?
    suspend fun write(key: CommunityCacheKey, envelope: CommunityEnvelope, cachedAtEpochMillis: Long)

    /** 单调递增的失效代际；客户端必须在网络请求发出前捕获，并在写缓存前用 writeIfCurrent 复核。 */
    suspend fun invalidationGeneration(): Long

    /** 只有捕获代际仍当前时才写入；返回 false 表示在途响应已被 mutation/403/404/wipe 失效。 */
    suspend fun writeIfCurrent(
        key: CommunityCacheKey,
        envelope: CommunityEnvelope,
        cachedAtEpochMillis: Long,
        expectedInvalidationGeneration: Long,
    ): Boolean

    suspend fun invalidateAll()
    suspend fun wipe()
    suspend fun countEntries(): Int
}

object CommunityCacheDirectories {
    /** 路径只包含 profileId 的 SHA-256 摘要，不把学号、学校或用户命名写入文件名。 */
    fun rootForProfile(filesDir: File, profileId: String): File {
        require(profileId.isNotBlank()) { "账号 Profile 标识不能为空" }
        return File(filesDir, "community-cache/account_" + Digests.sha256Hex(profileId))
    }
}

/**
 * Room v17 尚未按 D14 顺序可用前的 Profile 隔离文件缓存。
 * 原子写入、损坏 fail-closed、条数/字节上限与彻底擦除都在本类内完成。
 */
class FileCommunityCacheStore internal constructor(
    private val rootDir: Path,
    private val maxEntries: Int,
    private val maxBytes: Long,
    private val maxEntryBytes: Int,
    private val deleteTreeImpl: (Path) -> Unit,
    private val deleteResponseFileImpl: (Path) -> Unit = { Files.deleteIfExists(it) },
) : CommunityCacheStore {
    constructor(
        rootDir: Path,
        maxEntries: Int = 128,
        maxBytes: Long = 2L * 1024L * 1024L,
        maxEntryBytes: Int = 512 * 1024,
    ) : this(
        rootDir = rootDir,
        maxEntries = maxEntries,
        maxBytes = maxBytes,
        maxEntryBytes = maxEntryBytes,
        deleteTreeImpl = ::deleteTreeRecursively,
    )
    private val responsesDir: Path = rootDir.resolve("responses")
    private val invalidationTombstone: Path = rootDir.resolve(".invalidation-poisoned")
    private val rootKey = rootDir.toAbsolutePath().normalize().toString()
    private val lock = ROOT_LOCKS.computeIfAbsent(rootKey) { Any() }
    private val instanceWipeGeneration = WIPE_GENERATIONS[rootKey] ?: 0L

    init {
        require(maxEntries > 0) { "maxEntries 必须为正数" }
        require(maxBytes > 0L) { "maxBytes 必须为正数" }
        require(maxEntryBytes > 0) { "maxEntryBytes 必须为正数" }
    }

    constructor(rootDir: File) : this(rootDir.toPath())

    override suspend fun read(key: CommunityCacheKey): CommunityCachedEnvelope? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (isCacheAccessBlocked()) return@synchronized null
            val file = responsesDir.resolve(key.storageId + ".json")
            if (!Files.isRegularFile(file)) return@synchronized null
            if (runCatching { Files.size(file) }.getOrDefault(Long.MAX_VALUE) > maxEntryBytes) {
                runCatching { Files.deleteIfExists(file) }
                return@synchronized null
            }
            val parsed = runCatching {
                val text = Files.newBufferedReader(file, Charsets.UTF_8).use { it.readText() }
                Json.parse(text).asObjOrNull
            }.getOrNull()
            val decoded = parsed?.let { decodeEntry(it, key) }
            if (decoded == null) {
                runCatching { Files.deleteIfExists(file) }
                null
            } else {
                decoded
            }
        }
    }

    override suspend fun write(key: CommunityCacheKey, envelope: CommunityEnvelope, cachedAtEpochMillis: Long) =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (!isCacheAccessBlocked()) {
                    writeEntryLocked(key, envelope, cachedAtEpochMillis)
                }
            }
        }

    override suspend fun invalidationGeneration(): Long = synchronized(lock) {
        INVALIDATION_GENERATIONS[rootKey] ?: 0L
    }

    override suspend fun writeIfCurrent(
        key: CommunityCacheKey,
        envelope: CommunityEnvelope,
        cachedAtEpochMillis: Long,
        expectedInvalidationGeneration: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (isCacheAccessBlocked()) {
                false
            } else if ((INVALIDATION_GENERATIONS[rootKey] ?: 0L) != expectedInvalidationGeneration) {
                false
            } else {
                writeEntryLocked(key, envelope, cachedAtEpochMillis)
                true
            }
        }
    }

    private fun writeEntryLocked(key: CommunityCacheKey, envelope: CommunityEnvelope, cachedAtEpochMillis: Long) {
        Files.createDirectories(responsesDir)
        val entry = encodeEntry(key, envelope, cachedAtEpochMillis)
        val bytes = Json.canonical(entry).toByteArray(Charsets.UTF_8)
        if (bytes.size > maxEntryBytes) {
            throw IOException("community cache entry exceeds " + maxEntryBytes + " bytes")
        }
        val temporary = Files.createTempFile(responsesDir, "write-", ".tmp")
        try {
            Files.write(temporary, bytes)
            val target = responsesDir.resolve(key.storageId + ".json")
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            pruneOverflow()
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    override suspend fun invalidateAll() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            // 先推进失效代际再删文件：即使删除期间有在途读响应迟到，其写前复核也必然失败。
            val nextGeneration = (INVALIDATION_GENERATIONS[rootKey] ?: 0L) + 1L
            INVALIDATION_GENERATIONS[rootKey] = nextGeneration
            try {
                // 持久 tombstone 先于清理落盘：清理中途失败或进程重启后，新实例也不得读残留快照。
                writeInvalidationTombstoneLocked(nextGeneration)
                if (Files.exists(responsesDir)) {
                    Files.list(responsesDir).use { stream ->
                        stream.filter { Files.isRegularFile(it) }.forEach { file ->
                            deleteResponseFileImpl(file)
                            if (Files.exists(file)) {
                                throw IOException("community cache invalidation did not remove " + file.fileName)
                            }
                        }
                    }
                }
                Files.createDirectories(responsesDir)
                Files.deleteIfExists(invalidationTombstone)
                if (Files.exists(invalidationTombstone)) {
                    throw IOException("community cache invalidation poison tombstone could not be removed")
                }
                INVALIDATION_FAILURES.remove(rootKey)
            } catch (error: Exception) {
                // 失效失败不得让既有失权快照或迟到写回继续使用；重试清理或 wipe 成功后才会解除。
                INVALIDATION_FAILURES[rootKey] = true
                throw error
            }
        }
        Unit
    }

    override suspend fun wipe() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            // 先推进本进程内代际再删除：擦除前已在途的旧实例写入在锁释放后必然失效，
            // 而擦除后新建的实例采用新代际，可以继续正常使用同一目录。
            WIPE_GENERATIONS[rootKey] = (WIPE_GENERATIONS[rootKey] ?: 0L) + 1L
            INVALIDATION_GENERATIONS[rootKey] = (INVALIDATION_GENERATIONS[rootKey] ?: 0L) + 1L
            try {
                deleteTreeImpl(rootDir)
                WIPE_FAILURES.remove(rootKey)
                INVALIDATION_FAILURES.remove(rootKey)
            } catch (error: Exception) {
                // 擦除失败必须同时“显式传播 + 阻断后续读写”：调用方不得宣称成功，
                // 新实例也不得把可能残留的失权快照当作可用缓存。重试 wipe 成功后才解除阻断。
                WIPE_FAILURES[rootKey] = true
                throw error
            }
            Unit
        }
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (!Files.isDirectory(responsesDir)) return@synchronized 0
            Files.list(responsesDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json") && Files.isRegularFile(it) }.count().toInt()
            }
        }
    }

    private fun isCacheAccessBlocked(): Boolean =
        WIPE_FAILURES.containsKey(rootKey) ||
            INVALIDATION_FAILURES.containsKey(rootKey) ||
            (WIPE_GENERATIONS[rootKey] ?: 0L) != instanceWipeGeneration ||
            invalidationTombstonePresentOrUnknown()

    private fun invalidationTombstonePresentOrUnknown(): Boolean {
        val exists = Files.exists(invalidationTombstone)
        val definitelyAbsent = Files.notExists(invalidationTombstone)
        // exists/notExists 同时为 false 表示 IO 状态未知；缓存隐私语义选择 fail-closed。
        return exists || !definitelyAbsent
    }

    private fun writeInvalidationTombstoneLocked(generation: Long) {
        Files.createDirectories(rootDir)
        val marker = JsonValue.Obj(
            linkedMapOf(
                "schema_version" to JsonValue.Num("1"),
                "kind" to JsonValue.Str("community-cache-invalidation-poisoned"),
                "invalidation_generation" to JsonValue.Num(generation.toString()),
            ),
        )
        Files.write(invalidationTombstone, Json.canonical(marker).toByteArray(Charsets.UTF_8))
    }

    private fun encodeEntry(key: CommunityCacheKey, envelope: CommunityEnvelope, cachedAtEpochMillis: Long): JsonValue.Obj {
        return JsonValue.Obj(
            linkedMapOf(
                "schema_version" to JsonValue.Num("1"),
                "operation_id" to JsonValue.Str(key.operationId),
                "path" to JsonValue.Str(key.resolvedPath),
                "key_sha256" to JsonValue.Str(key.storageId),
                "cached_at_epoch_ms" to JsonValue.Num(cachedAtEpochMillis.toString()),
                "envelope" to envelope.raw,
            ),
        )
    }

    private fun decodeEntry(entry: JsonValue.Obj, key: CommunityCacheKey): CommunityCachedEnvelope? {
        val version = entry.obj("schema_version")?.longOrNull() ?: return null
        if (version != 1L) return null
        if (entry.str("operation_id") != key.operationId) return null
        if (entry.str("path") != key.resolvedPath) return null
        if (entry.str("key_sha256") != key.storageId) return null
        val cachedAt = entry.obj("cached_at_epoch_ms")?.longOrNull() ?: return null
        if (cachedAt < 0L) return null
        val rawEnvelope = entry.obj("envelope")?.asObjOrNull ?: return null
        val envelope = parseCommunityEnvelope(rawEnvelope) ?: return null
        return CommunityCachedEnvelope(envelope = envelope, cachedAtEpochMillis = cachedAt)
    }

    private fun pruneOverflow() {
        val files = if (Files.isDirectory(responsesDir)) {
            Files.list(responsesDir).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .collect(Collectors.toList())
            }
        } else {
            emptyList()
        }
        var totalBytes = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        val overflowCount = files.size - maxEntries
        val deleteCount = when {
            overflowCount > 0 || totalBytes > maxBytes -> {
                var count = 0
                val sorted = files.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                for (file in sorted) {
                    if (count >= overflowCount && totalBytes <= maxBytes) break
                    val size = runCatching { Files.size(file) }.getOrDefault(0L)
                    if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) {
                        totalBytes -= size
                        count++
                    }
                }
                count
            }
            else -> 0
        }
        if (deleteCount < 0) error("unreachable")
    }

    private companion object {
        private val ROOT_LOCKS = ConcurrentHashMap<String, Any>()
        private val WIPE_GENERATIONS = ConcurrentHashMap<String, Long>()
        private val WIPE_FAILURES = ConcurrentHashMap<String, Boolean>()
        private val INVALIDATION_GENERATIONS = ConcurrentHashMap<String, Long>()
        private val INVALIDATION_FAILURES = ConcurrentHashMap<String, Boolean>()
    }

}

private fun deleteTreeRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

class InMemoryCommunityCacheStore : CommunityCacheStore {
    private val lock = Any()
    private val entries = LinkedHashMap<String, CommunityCachedEnvelope>()
    private var invalidationGenerationValue = 0L

    override suspend fun read(key: CommunityCacheKey): CommunityCachedEnvelope? = synchronized(lock) {
        entries[key.storageId]
    }

    override suspend fun write(key: CommunityCacheKey, envelope: CommunityEnvelope, cachedAtEpochMillis: Long) {
        synchronized(lock) {
            entries[key.storageId] = CommunityCachedEnvelope(envelope, cachedAtEpochMillis)
        }
    }

    override suspend fun invalidationGeneration(): Long = synchronized(lock) {
        invalidationGenerationValue
    }

    override suspend fun writeIfCurrent(
        key: CommunityCacheKey,
        envelope: CommunityEnvelope,
        cachedAtEpochMillis: Long,
        expectedInvalidationGeneration: Long,
    ): Boolean = synchronized(lock) {
        if (invalidationGenerationValue != expectedInvalidationGeneration) {
            false
        } else {
            entries[key.storageId] = CommunityCachedEnvelope(envelope, cachedAtEpochMillis)
            true
        }
    }

    override suspend fun invalidateAll() {
        synchronized(lock) {
            invalidationGenerationValue += 1L
            entries.clear()
        }
    }

    override suspend fun wipe() {
        synchronized(lock) {
            invalidationGenerationValue += 1L
            entries.clear()
        }
    }

    override suspend fun countEntries(): Int = synchronized(lock) {
        entries.size
    }
}

private fun JsonValue.longOrNull(): Long? = (this as? JsonValue.Num)?.literal?.toLongOrNull()

internal fun parseCommunityEnvelope(raw: JsonValue.Obj): CommunityEnvelope? {
    if (raw.obj("ok")?.boolOrNull() != true) return null
    val code = raw.str("code")?.takeIf { it.isNotBlank() } ?: return null
    val requestId = raw.str("request_id")?.takeIf { it.isNotBlank() } ?: return null
    val meta = raw.obj("meta")?.asObjOrNull ?: return null
    if (meta.str("request_id") != requestId) return null
    if (meta.str("api_version") != "v1") return null
    val data = if (raw.fields.containsKey("data")) raw.fields["data"] else return null
    return CommunityEnvelope(
        ok = true,
        code = code,
        requestId = requestId,
        data = data,
        meta = meta,
        raw = raw,
    )
}

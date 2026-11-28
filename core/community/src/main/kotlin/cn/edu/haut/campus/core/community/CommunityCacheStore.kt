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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 读响应缓存键。绝不包含 Authorization、refresh token、cookie 或设备秘密。 */
data class CommunityCacheKey(
    val operationId: String,
    val resolvedPath: String,
    val queryParams: Map<String, List<String>> = emptyMap(),
    val body: JsonValue.Obj? = null,
) {
    init {
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
class FileCommunityCacheStore(
    private val rootDir: Path,
    private val maxEntries: Int = 128,
    private val maxBytes: Long = 2L * 1024L * 1024L,
    private val maxEntryBytes: Int = 512 * 1024,
) : CommunityCacheStore {
    private val responsesDir: Path = rootDir.resolve("responses")
    private val lock = Any()

    init {
        require(maxEntries > 0) { "maxEntries 必须为正数" }
        require(maxBytes > 0L) { "maxBytes 必须为正数" }
        require(maxEntryBytes > 0) { "maxEntryBytes 必须为正数" }
    }

    constructor(rootDir: File) : this(rootDir.toPath())

    override suspend fun read(key: CommunityCacheKey): CommunityCachedEnvelope? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val file = responsesDir.resolve(key.storageId + ".json")
            if (!Files.isRegularFile(file)) return@synchronized null
            if (runCatching { Files.size(file) }.getOrDefault(Long.MAX_VALUE) > maxEntryBytes) {
                runCatching { Files.deleteIfExists(file) }
                return@synchronized null
            }
            val parsed = runCatching {
                val text = Files.readString(file, Charsets.UTF_8)
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
        }

    override suspend fun invalidateAll() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (Files.exists(responsesDir)) {
                Files.list(responsesDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
            Files.createDirectories(responsesDir)
        }
        Unit
    }

    override suspend fun wipe() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            deleteTree(rootDir)
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
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.toList()
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

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}

class InMemoryCommunityCacheStore : CommunityCacheStore {
    private val entries = LinkedHashMap<String, CommunityCachedEnvelope>()

    override suspend fun read(key: CommunityCacheKey): CommunityCachedEnvelope? = entries[key.storageId]

    override suspend fun write(key: CommunityCacheKey, envelope: CommunityEnvelope, cachedAtEpochMillis: Long) {
        entries[key.storageId] = CommunityCachedEnvelope(envelope, cachedAtEpochMillis)
    }

    override suspend fun invalidateAll() {
        entries.clear()
    }

    override suspend fun wipe() {
        entries.clear()
    }

    override suspend fun countEntries(): Int = entries.size
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

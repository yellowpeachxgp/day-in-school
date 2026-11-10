package cn.edu.haut.campus.core.location

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.tan

data class TileRenderLookup<T>(
    val tile: T?,
    val isPlaceholder: Boolean,
    val shouldTriggerTargetLoad: Boolean
)

/**
 * 校园地图瓦片内存缓存与目标样式异步加载协调器：
 * - 严格解耦「街道底图临时占位 (`street_*`)」与「官方 2.5D 目标图层 (`haut25d_*`)」的加载决策；
 * - 当先浏览标准地图后切换至 2.5D 时，`lookupForRender` 返回已有 `street` 作为平滑占位，同时保持
 *   `shouldTriggerTargetLoad = true`，确保立即发起官方 2.5D 瓦片拉取并在到达后热替换；
 * - 对官方 2.5D 单瓦片网络失败施加有界冷却窗口，防止 `Canvas` 逐帧死循环重试，且支持切回模式或过期后自动重试。
 */
class CampusTileCacheCoordinator<T : Any>(
    maxMemoryEntries: Int = 360,
    private val failureCooldownMs: Long = 45_000L
) {
    private val memoryCache = object : LinkedHashMap<String, T>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?): Boolean =
            size > maxMemoryEntries
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failureCooldownUntilMs = ConcurrentHashMap<String, Long>()

    fun isWithinHaut25dRasterBounds(z: Int, x: Int, y: Int): Boolean {
        if (z !in 16..18) return false
        val n = 2.0.pow(z.toDouble())
        val minX = floor((113.533 + 180.0) / 360.0 * n).toInt()
        val maxX = ceil((113.562 + 180.0) / 360.0 * n).toInt()
        val minY = floor((1.0 - asinh(tan(Math.toRadians(34.841))) / PI) / 2.0 * n).toInt()
        val maxY = ceil((1.0 - asinh(tan(Math.toRadians(34.824))) / PI) / 2.0 * n).toInt()
        return x in minX..maxX && y in minY..maxY
    }

    fun resolveEffectiveTileStyle(styleMode: GisMapStyleMode, z: Int, x: Int, y: Int): GisMapStyleMode =
        if (styleMode == GisMapStyleMode.OFFICIAL_25D_2026 && isWithinHaut25dRasterBounds(z, x, y)) {
            GisMapStyleMode.OFFICIAL_25D_2026
        } else {
            GisMapStyleMode.STREET_STANDARD
        }

    fun tileKey(styleMode: GisMapStyleMode, z: Int, x: Int, y: Int): String {
        val effective = resolveEffectiveTileStyle(styleMode, z, x, y)
        val prefix = if (effective == GisMapStyleMode.OFFICIAL_25D_2026) "haut25d" else "street"
        return "${prefix}_${z}_${x}_${y}"
    }

    fun get(key: String): T? = synchronized(memoryCache) { memoryCache.get(key) }

    fun put(key: String, tile: T) = synchronized(memoryCache) {
        memoryCache.put(key, tile)
    }

    fun clearFailureCooldowns() {
        failureCooldownUntilMs.clear()
    }

    fun evictExpiredCooldowns(nowMs: Long = System.currentTimeMillis()): Int {
        var evicted = 0
        for ((key, until) in failureCooldownUntilMs.entries) {
            if (nowMs >= until && failureCooldownUntilMs.remove(key, until)) {
                evicted += 1
            }
        }
        return evicted
    }

    fun nextCooldownExpiryEpochMs(nowMs: Long = System.currentTimeMillis()): Long? {
        evictExpiredCooldowns(nowMs)
        var earliest: Long? = null
        for (until in failureCooldownUntilMs.values) {
            if (until > nowMs && (earliest == null || until < earliest)) {
                earliest = until
            }
        }
        return earliest
    }

    /**
     * 静止视口有生命周期的冷却到期唤醒器：
     * 按最早冷却截止时刻仅挂起一次（避免逐帧重试风暴），到期后清理过期冷却并返回 `true` 以触发重绘重试。
     */
    suspend fun awaitNextCooldownBatchExpiry(
        nowMsProvider: () -> Long = System::currentTimeMillis,
        delayMillis: suspend (Long) -> Unit
    ): Boolean {
        val now0 = nowMsProvider()
        val earliest = nextCooldownExpiryEpochMs(now0) ?: return false
        val waitMs = (earliest - now0).coerceAtLeast(1L)
        delayMillis(waitMs)
        evictExpiredCooldowns(nowMsProvider())
        return true
    }

    fun clearAll() {
        synchronized(memoryCache) { memoryCache.clear() }
        inFlight.clear()
        failureCooldownUntilMs.clear()
    }

    private fun isCoolingDown(key: String, nowMs: Long): Boolean {
        val until = failureCooldownUntilMs[key] ?: return false
        if (nowMs >= until) {
            failureCooldownUntilMs.remove(key, until)
            return false
        }
        return true
    }

    fun lookupForRender(
        styleMode: GisMapStyleMode,
        z: Int,
        x: Int,
        y: Int,
        nowMs: Long = System.currentTimeMillis()
    ): TileRenderLookup<T> {
        val effectiveStyle = resolveEffectiveTileStyle(styleMode, z, x, y)
        val primaryKey = tileKey(effectiveStyle, z, x, y)
        val exactTile = get(primaryKey)
        if (exactTile != null) {
            return TileRenderLookup(
                tile = exactTile,
                isPlaceholder = false,
                shouldTriggerTargetLoad = false
            )
        }
        val placeholder = if (effectiveStyle == GisMapStyleMode.OFFICIAL_25D_2026) {
            get("street_${z}_${x}_${y}")
        } else {
            null
        }
        val canLoadTarget = primaryKey !in inFlight && !isCoolingDown(primaryKey, nowMs)
        return TileRenderLookup(
            tile = placeholder,
            isPlaceholder = placeholder != null,
            shouldTriggerTargetLoad = canLoadTarget
        )
    }

    suspend fun loadTileIfNeeded(
        styleMode: GisMapStyleMode,
        z: Int,
        x: Int,
        y: Int,
        nowMs: Long = System.currentTimeMillis(),
        readDiskTile: suspend (key: String) -> T?,
        fetchRemoteTile: suspend (targetKey: String, url: String) -> T?,
        onLoaded: () -> Unit,
        onCooldownScheduled: ((expiryEpochMs: Long) -> Unit)? = null
    ) {
        val effectiveStyle = resolveEffectiveTileStyle(styleMode, z, x, y)
        val primaryKey = tileKey(effectiveStyle, z, x, y)
        val streetKey = "street_${z}_${x}_${y}"

        if (get(primaryKey) != null) return
        if (isCoolingDown(primaryKey, nowMs)) return
        if (!inFlight.add(primaryKey)) return

        try {
            // 1. 优先命中目标样式自身的磁盘热缓存（绝不因 street 已缓存而跳过 haut25d 读取或请求！）
            val diskPrimary = readDiskTile(primaryKey)
            if (diskPrimary != null) {
                failureCooldownUntilMs.remove(primaryKey)
                put(primaryKey, diskPrimary)
                onLoaded()
                return
            }

            val subdomain = ((x + y) % 4 + 4) % 4 + 1
            if (effectiveStyle == GisMapStyleMode.OFFICIAL_25D_2026) {
                val officialUrl = "https://gis.haut.edu.cn/cmgis-server/upload/raster/3/$z/$x/$y.png"
                val officialTile = fetchRemoteTile(primaryKey, officialUrl)
                if (officialTile != null) {
                    failureCooldownUntilMs.remove(primaryKey)
                    put(primaryKey, officialTile)
                    onLoaded()
                    return
                }
                // 官方 2.5D 拉取失败：记录冷却窗口以防逐帧死循环，同时通知有生命周期的唤醒器在冷却截止时自动重绘重试
                val expiry25d = nowMs + failureCooldownMs
                failureCooldownUntilMs[primaryKey] = expiry25d
                onCooldownScheduled?.invoke(expiry25d)
                if (get(streetKey) == null) {
                    val diskStreet = readDiskTile(streetKey)
                    if (diskStreet != null) {
                        put(streetKey, diskStreet)
                        onLoaded()
                        return
                    }
                    val streetUrls = listOf(
                        "https://webrd0$subdomain.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=2&style=8&x=$x&y=$y&z=$z",
                        "https://tile.openstreetmap.org/$z/$x/$y.png"
                    )
                    for (sUrl in streetUrls) {
                        val fallbackTile = fetchRemoteTile(streetKey, sUrl)
                        if (fallbackTile != null) {
                            put(streetKey, fallbackTile)
                            onLoaded()
                            return
                        }
                    }
                }
                return
            }

            // 标准街道图层加载
            val streetUrls = listOf(
                "https://webrd0$subdomain.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=2&style=8&x=$x&y=$y&z=$z",
                "https://tile.openstreetmap.org/$z/$x/$y.png"
            )
            for (sUrl in streetUrls) {
                val tile = fetchRemoteTile(streetKey, sUrl)
                if (tile != null) {
                    failureCooldownUntilMs.remove(streetKey)
                    put(streetKey, tile)
                    onLoaded()
                    return
                }
            }
            val streetExpiry = nowMs + (failureCooldownMs / 3L).coerceAtLeast(5_000L)
            failureCooldownUntilMs[streetKey] = streetExpiry
            onCooldownScheduled?.invoke(streetExpiry)
        } finally {
            inFlight.remove(primaryKey)
        }
    }
}

package cn.edu.haut.campus.feature.map.tiles

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import cn.edu.haut.campus.core.location.CampusTileCacheCoordinator
import cn.edu.haut.campus.core.location.GisMapStyleMode
import cn.edu.haut.campus.core.location.HautOfficialGisClient
import cn.edu.haut.campus.core.location.TileRenderLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

internal object CampusTileCache {
    private val coordinator = CampusTileCacheCoordinator<ImageBitmap>(maxMemoryEntries = 360)

    /**
     * 截图测试确定性开关。生产代码从不修改它，默认 true 时保持原瓦片加载行为。
     */
    @Volatile
    internal var tileLoadingEnabled: Boolean = true

    fun clearFailureCooldowns() {
        coordinator.clearFailureCooldowns()
    }

    /** 仅测试使用：清空内存瓦片、在途标记与冷却状态，避免跨用例污染。 */
    internal fun clearAllForPreview() {
        coordinator.clearAll()
    }

    fun onForegroundResume(): Boolean =
        coordinator.onForegroundResume()

    suspend fun awaitNextCooldownBatchExpiry(delayMillis: suspend (Long) -> Unit): Boolean =
        coordinator.awaitNextCooldownBatchExpiry(delayMillis = delayMillis)

    fun lookupForRender(styleMode: GisMapStyleMode, z: Int, x: Int, y: Int): TileRenderLookup<ImageBitmap> =
        coordinator.lookupForRender(styleMode = styleMode, z = z, x = x, y = y)

    suspend fun loadTileIfNeeded(
        cacheDir: File,
        styleMode: GisMapStyleMode,
        z: Int,
        x: Int,
        y: Int,
        onLoaded: () -> Unit,
        onCooldownScheduled: ((Long) -> Unit)? = null
    ) {
        if (!tileLoadingEnabled) return
        coordinator.loadTileIfNeeded(
            styleMode = styleMode,
            z = z,
            x = x,
            y = y,
            nowMsProvider = System::currentTimeMillis,
            readDiskTile = { targetKey ->
                withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "haut_map_tiles_v3").apply { mkdirs() }
                    val diskFile = File(dir, "$targetKey.png")
                    if (diskFile.exists() && diskFile.length() > 256L) {
                        runCatching {
                            BitmapFactory.decodeFile(diskFile.absolutePath)?.asImageBitmap()
                        }.getOrNull()
                    } else null
                }
            },
            fetchRemoteTile = { targetKey, urlStr ->
                withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "haut_map_tiles_v3").apply { mkdirs() }
                    val targetDiskFile = File(dir, "$targetKey.png")
                    runCatching {
                        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 6000
                            readTimeout = 6000
                            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36")
                            setRequestProperty("Referer", HautOfficialGisClient.OFFICIAL_GIS_WEB_URL)
                        }
                        if (conn.responseCode == 200) {
                            val bytes = conn.inputStream.use { input ->
                                val maxTileBytes = 512 * 1024
                                val buf = ByteArray(8192)
                                val out = java.io.ByteArrayOutputStream()
                                var total = 0
                                while (true) {
                                    val n = input.read(buf)
                                    if (n <= 0) break
                                    total += n
                                    if (total > maxTileBytes) {
                                        throw IllegalStateException("Map tile exceeds 512KB limit")
                                    }
                                    out.write(buf, 0, n)
                                }
                                out.toByteArray()
                            }
                            if (bytes.size > 256) {
                                targetDiskFile.writeBytes(bytes)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } else null
                        } else null
                    }.getOrNull()
                }
            },
            onLoaded = onLoaded,
            onCooldownScheduled = onCooldownScheduled
        )
    }
}

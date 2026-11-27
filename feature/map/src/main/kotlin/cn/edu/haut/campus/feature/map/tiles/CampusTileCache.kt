package cn.edu.haut.campus.feature.map.tiles

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.util.LruCache
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.edu.haut.campus.core.database.AppPreferencesState
import cn.edu.haut.campus.core.database.decodeOnlineDevicesJson
import cn.edu.haut.campus.core.designsystem.XPeachAlertDialog
import cn.edu.haut.campus.core.designsystem.XPeachBrandLight
import cn.edu.haut.campus.core.designsystem.XPeachCard
import cn.edu.haut.campus.core.designsystem.XPeachFilterPill
import cn.edu.haut.campus.core.designsystem.XPeachGlassSurface
import cn.edu.haut.campus.core.designsystem.XPeachIconButton
import cn.edu.haut.campus.core.designsystem.XPeachInsetDivider
import cn.edu.haut.campus.core.designsystem.XPeachOk
import cn.edu.haut.campus.core.designsystem.XPeachStatusBadge
import cn.edu.haut.campus.core.designsystem.XPeachStatusDotText
import cn.edu.haut.campus.core.designsystem.XPeachTheme
import cn.edu.haut.campus.core.designsystem.XPeachTopBar
import cn.edu.haut.campus.core.designsystem.XPeachWell
import cn.edu.haut.campus.core.domain.CampusHospitalPharmacyCatalog
import cn.edu.haut.campus.core.domain.ClassroomBuildingResolver
import cn.edu.haut.campus.core.domain.HospitalMedicineCategory
import cn.edu.haut.campus.core.domain.SchoolDirectory
import cn.edu.haut.campus.core.location.CampusGeofenceRecognizer
import cn.edu.haut.campus.core.location.CampusMapNavigator
import cn.edu.haut.campus.core.location.CampusMapProviderRegistry
import cn.edu.haut.campus.core.location.CampusTileCacheCoordinator
import cn.edu.haut.campus.core.location.TileRenderLookup
import cn.edu.haut.campus.core.location.GisBuildingFootprint
import cn.edu.haut.campus.core.location.GisCampusVectorDataset
import cn.edu.haut.campus.core.location.GisGeoPoint
import cn.edu.haut.campus.core.location.GisIndoorFloorPlan
import cn.edu.haut.campus.core.location.GisIndoorRoom
import cn.edu.haut.campus.core.location.GisLanduseType
import cn.edu.haut.campus.core.location.GisMapStyleMode
import cn.edu.haut.campus.core.location.GisSearchResultItem
import cn.edu.haut.campus.core.location.HautOfficialGisClient
import cn.edu.haut.campus.core.location.InAppNavigationPlan
import cn.edu.haut.campus.core.location.UserGeoLocation
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingMatchStatus
import cn.edu.haut.campus.core.model.CampusCategory
import cn.edu.haut.campus.core.model.CustomCampusZone
import cn.edu.haut.campus.core.model.CustomCampusZoneCodec
import cn.edu.haut.campus.core.model.GeoPoint
import cn.edu.haut.campus.core.model.SchoolProfile
import cn.edu.haut.campus.feature.map.ui.indoor.BuildingIndoorMapDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan


internal object CampusTileCache {
    private val coordinator = CampusTileCacheCoordinator<ImageBitmap>(maxMemoryEntries = 360)

    fun clearFailureCooldowns() {
        coordinator.clearFailureCooldowns()
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

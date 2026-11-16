package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #14 工大蓝主题持久化与旧版种子迁移回归：
 * - HAUT_BLUE 默认/旧种子 #0066CC/无种子 -> 统一解析为 #053968；
 * - CUSTOM 主题（含相同数值或其他用户色）绝不迁移；
 * - 主题经 DataStore 文件级重建后依旧保持（模拟进程重启 recreate）。
 * 说明：测试内重建与生产 PrefKeys 同名的 preference key（私有对象无法直接引用）；
 * DataStore 单文件单实例约束下，通过取消旧 scope 后再建新 store 来模拟重建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class HautBlueThemePersistenceTest {

    // 与 CampusRepository.PrefKeys 中同名的 DataStore key（私有键名在此复制以便注入旧版状态）
    private val keyPalette = stringPreferencesKey("theme_palette_key_v1")
    private val keyCustomSeed = longPreferencesKey("custom_theme_seed_argb_v1")

    private lateinit var context: Context
    private lateinit var database: HautDatabase
    private lateinit var storeFile: File
    private var storeScope: CoroutineScope? = null

    private fun newProfile(seed: String): AccountProfileEntity {
        val storage = isolatedAccountStoragePartition(seed)
        return AccountProfileEntity(
            id = seed,
            accountId = "acct_" + seed,
            schoolId = "haut",
            schoolName = "河南工业大学",
            province = "河南省",
            city = "郑州市",
            campusId = "lianhuajie",
            campusName = "莲花街校区",
            timeZoneId = "Asia/Shanghai",
            label = "主题测试账号",
            isLegacy = false,
            databaseName = storage.databaseName,
            preferencesName = storage.preferencesName,
            createdAtEpochMs = 1_000L
        )
    }

    /** 释放上一个 store 的文件占用后，在同一持久文件上新建 DataStore（模拟进程重启）。 */
    private suspend fun restartStore(): DataStore<Preferences> {
        storeScope?.cancel()
        // DataStore 的单文件单实例注销发生在新实例创建前由 actor 协程完成；
        // 稍作等待避免「multiple DataStores active for the same file」竞态
        delay(80)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        storeScope = scope
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile }
        )
    }

    private val createdRepos = mutableListOf<CampusRepository>()

    private fun newRepository(
        profileSeed: String,
        store: DataStore<Preferences>
    ): CampusRepository = CampusRepository(
        appContext = context,
        database = database,
        preferences = store,
        accountProfile = newProfile(profileSeed)
    ).also { repo ->
        repo.activateSession(1L)
        createdRepos += repo
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeFile = File.createTempFile("haut_theme_test_", ".preferences_pb")
        storeFile.deleteOnExit()
    }

    @After
    fun tearDown() {
        runBlocking {
        storeScope?.cancel()
        // CampusRepository 内部 private repoScope 持有 dao.observe* 收集协程；
        // 不取消则其无效化回调与 database.close() 并发争抢 ProcessLock 形成 ABBA 死锁。
        createdRepos.forEach { repo ->
            runCatching {
                val f = CampusRepository::class.java.getDeclaredField("repoScope")
                f.isAccessible = true
                (f.get(repo) as? CoroutineScope)?.cancel()
            }
        }
        createdRepos.clear()
        delay(300)
        withTimeoutOrNull(5000) {
            withContext(Dispatchers.IO) { database.close() }
        }
        }
        runCatching { storeFile.delete() }
    }

    private suspend fun writeRawPrefs(
        store: DataStore<Preferences>,
        palette: String?,
        seed: Long?
    ) {
        store.edit { prefs ->
            if (palette == null) prefs.remove(keyPalette) else prefs[keyPalette] = palette
            if (seed == null) prefs.remove(keyCustomSeed) else prefs[keyCustomSeed] = seed
        }
    }

    @Test
    fun freshInstallDefaultsToHautBlueExactSeed() = runBlocking {
        val repo = newRepository("fresh", restartStore())
        val state = repo.preferencesFlow.first()
        assertEquals("HAUT_BLUE", state.themePaletteKey)
        assertEquals(0xFF053968L, state.customThemeSeedArgb)
        assertEquals(0xFF053968.toInt(), repo.getActiveThemeAccentArgb())
        Unit
    }

    @Test
    fun legacyOldSeedAndMissingSeedUpgradeToHautBlue() = runBlocking {
        // 旧版遗留 #0066CC 种子 + HAUT_BLUE 调色板 -> 迁移到 #053968
        var store = restartStore()
        writeRawPrefs(store, palette = "HAUT_BLUE", seed = 0xFF0066CCL)
        val upgraded = newRepository("legacy_seed", store).preferencesFlow.first()
        assertEquals("HAUT_BLUE", upgraded.themePaletteKey)
        assertEquals(
            "Legacy #0066CC seed must migrate to #053968",
            0xFF053968L, upgraded.customThemeSeedArgb
        )
        assertEquals(0xFF053968.toInt(), newRepository("legacy_seed2", store).getActiveThemeAccentArgb())

        // 旧版完全没有存过 seed（仅 palette）-> 同样落到 #053968
        store = restartStore()
        writeRawPrefs(store, palette = "HAUT_BLUE", seed = null)
        val noSeed = newRepository("no_seed", store).preferencesFlow.first()
        assertEquals(0xFF053968L, noSeed.customThemeSeedArgb)

        // 连 palette 都没有的史前安装 -> 默认 HAUT_BLUE + #053968
        store = restartStore()
        writeRawPrefs(store, palette = null, seed = null)
        val pristine = newRepository("pristine", store).preferencesFlow.first()
        assertEquals("HAUT_BLUE", pristine.themePaletteKey)
        assertEquals(0xFF053968L, pristine.customThemeSeedArgb)
        Unit
    }

    @Test
    fun customThemeNeverMigratesEvenWithSameValueOrOtherColors() = runBlocking {
        // CUSTOM + 旧蓝值：保持用户存储值，不做 HAUT_BLUE 迁移
        var store = restartStore()
        writeRawPrefs(store, palette = "CUSTOM", seed = 0xFF0066CCL)
        val customLegacy = newRepository("custom_legacy", store).preferencesFlow.first()
        assertEquals("CUSTOM", customLegacy.themePaletteKey)
        assertEquals(0xFF0066CCL, customLegacy.customThemeSeedArgb)
        assertEquals(0xFF0066CC.toInt(), newRepository("custom_legacy2", store).getActiveThemeAccentArgb())

        // CUSTOM + 其他用户色
        store = restartStore()
        writeRawPrefs(store, palette = "CUSTOM", seed = 0xFF8B5CF6L)
        val customOther = newRepository("custom_other", store).preferencesFlow.first()
        assertEquals(0xFF8B5CF6L, customOther.customThemeSeedArgb)
        assertEquals(0xFF8B5CF6.toInt(), newRepository("custom_other2", store).getActiveThemeAccentArgb())

        // 非 HAUT_BLUE 预设 + 存储 seed：seed 字段保留供 CUSTOM 切换，accent 走预设色
        store = restartStore()
        writeRawPrefs(store, palette = "MINT_BREEZE", seed = 0xFF123456L)
        val mint = newRepository("mint", store).preferencesFlow.first()
        assertEquals("MINT_BREEZE", mint.themePaletteKey)
        assertEquals(0xFF123456L, mint.customThemeSeedArgb)
        assertEquals(0xFF10B981.toInt(), newRepository("mint2", store).getActiveThemeAccentArgb())
        Unit
    }

    @Test
    fun themePersistsAcrossSimulatedRepositoryRecreate() = runBlocking {
        // 写入自定义主题 -> 释放 store 后在同一文件上重建仓储（模拟重启），读回一致
        val writer = newRepository("writer", restartStore())
        writer.setThemePalette("CUSTOM", customSeedArgb = 0xFF123456L)

        val recreated = newRepository("recreated", restartStore())
        val state = recreated.preferencesFlow.first()
        assertEquals("CUSTOM", state.themePaletteKey)
        assertEquals(0xFF123456L, state.customThemeSeedArgb)
        assertEquals(0xFF123456.toInt(), recreated.getActiveThemeAccentArgb())

        // HAUT_BLUE 路径同样跨实例保持
        val writer2 = newRepository("writer2", restartStore())
        writer2.setThemePalette("HAUT_BLUE")
        val recreatedStore = restartStore()
        val repo2 = newRepository("recreated2", recreatedStore)
        val recreated2 = repo2.preferencesFlow.first()
        assertEquals("HAUT_BLUE", recreated2.themePaletteKey)
        // 用户在 CUSTOM 下存的 seed 字段按值保留（切回 CUSTOM 可复用），
        // 但 HAUT_BLUE 调色板下的实际 accent 必须仍是校徽深蓝
        assertEquals(0xFF123456L, recreated2.customThemeSeedArgb)
        assertEquals(0xFF053968.toInt(), repo2.getActiveThemeAccentArgb())
        Unit
    }
}

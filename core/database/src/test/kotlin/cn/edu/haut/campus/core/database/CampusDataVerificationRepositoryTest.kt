package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import cn.edu.haut.campus.core.domain.CampusDiningRecommender
import cn.edu.haut.campus.core.model.Building
import cn.edu.haut.campus.core.model.BuildingAlias
import cn.edu.haut.campus.core.model.CampusCategory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/**
 * 仓储层数据核验回归：
 * - #5：已停业餐厅（b_canteen2/b_canteen3）绝不从 buildingsFlow / buildingAliasesFlow /
 *   getDirectScheduleDataSnapshot 等活跃数据路径泄漏到用户侧，且用户历史评价原样保留；
 * - #16：缓存的社团目录 JSON 中的旧版「8号/活动中心」场地在读取端被同一规范化逻辑纠正，
 *   重启后保持一致；云端下发的更新场地不被永久覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusDataVerificationRepositoryTest {

    // 与 CampusRepository.PrefKeys 同名的键（私有对象不可直接引用）
    private val keyCampusContentJson = stringPreferencesKey("cloud_campus_content_json_v2")
    private val keyCleanBaseV2 = booleanPreferencesKey("clean_base_dictionary_initialized_v3")
    private val keySeeded = booleanPreferencesKey("seeded_initial_mock_data")
    private val keyOnboarding = booleanPreferencesKey("completed_onboarding_guide_v1")
    private val keyAutoSync = booleanPreferencesKey("auto_sync_jw_enabled")
    private val keyTermMismatchV2 = booleanPreferencesKey("term_mismatch_repaired_v2")
    private val keyGisV15 = booleanPreferencesKey("official_gis_dictionary_synced_v15")
    private val keyGisV16 = booleanPreferencesKey("official_gis_dictionary_synced_v16")
    private val keyGisV17 = booleanPreferencesKey("official_gis_dictionary_synced_v17")
    private val keyGisV18 = booleanPreferencesKey("official_gis_dictionary_synced_v18")

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
            label = "数据核验测试账号",
            isLegacy = false,
            databaseName = storage.databaseName,
            preferencesName = storage.preferencesName,
            createdAtEpochMs = 1_000L
        )
    }

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

    private suspend fun markDictionaryAlreadySynced(store: DataStore<Preferences>) {
        store.edit { prefs ->
            // 跳过 ensureInitialSeedIfNeeded 内部的 V18 字典同步（它本身就会删除已停业餐厅），
            // 使本测试专门验证 buildingsFlow/getDirectScheduleDataSnapshot 的读取端过滤
            prefs[keyCleanBaseV2] = true
            prefs[keySeeded] = true
            prefs[keyOnboarding] = true
            prefs[keyAutoSync] = false
            prefs[keyTermMismatchV2] = true
            prefs[keyGisV15] = true
            prefs[keyGisV16] = true
            prefs[keyGisV17] = true
            prefs[keyGisV18] = true
        }
    }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, HautDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeFile = File.createTempFile("haut_data_verify_", ".preferences_pb")
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
        } ?: println("[tearDown] database.close() timed out — flow coroutine still holds ProcessLock")
        }
        runCatching { storeFile.delete() }
    }

    private fun closedCanteen(id: String, name: String) = Building(
        id = id, campusId = "lianhuajie", name = name, shortName = name.takeLast(3),
        latitude = 34.832, longitude = 113.540, category = CampusCategory.CANTEEN,
        entrances = emptyList(), description = "已停业",
        verifiedAt = LocalDate.of(2025, 1, 1)
    )

    // ===== #5 已停业餐厅活跃路径 =====

    @Test
    fun closedCanteensNeverLeakThroughBuildingsAliasesOrScheduleSnapshot() = runBlocking {
        val store = restartStore()
        markDictionaryAlreadySynced(store)
        val repo = newRepository("canteen", store)
        val dao = database.campusDao()

        // 模拟旧设备残留：DB 中仍存留已停业餐厅行与已删除别名
        dao.upsertBuildings(listOf(
            closedCanteen("b_canteen2", "第二餐厅"),
            closedCanteen("b_canteen3", "第三餐厅"),
            Building(
                id = "b_canteen1", campusId = "lianhuajie", name = "第一餐厅", shortName = "一餐厅",
                latitude = 34.83344, longitude = 113.54845, category = CampusCategory.CANTEEN,
                entrances = listOf("西门"), description = "营业中", verifiedAt = LocalDate.of(2026, 11, 10)
            )
        ).map { it.toEntity() })
        dao.upsertBuildingAliases(listOf(
            BuildingAlias("al_cant2_a", "b_canteen2", "第二餐厅", 1.0f, true).toEntity()
        ))

        // buildingsFlow：已停业餐厅被过滤，嵩山路与营业餐厅仍在
        val flowing = repo.buildingsFlow.first()
        assertTrue(flowing.none { it.id in CampusDiningRecommender.CLOSED_CANTEEN_BUILDING_IDS })
        assertTrue(flowing.any { it.id == "b_canteen1" })
        assertTrue(flowing.any { it.id == "b_ss_t1" })
        assertTrue(flowing.none { it.name == "第二餐厅" || it.name == "第三餐厅" })

        // buildingAliasesFlow：已删除别名不复活
        val aliases = repo.buildingAliasesFlow.first()
        assertTrue(aliases.none { it.id == "al_cant2_a" })
        assertTrue(aliases.none { it.buildingId in CampusDiningRecommender.CLOSED_CANTEEN_BUILDING_IDS })

        // 课表直接快照（小组件/提醒用）：同样不得带出已停业餐厅
        val snapshot = repo.getDirectScheduleDataSnapshot()
        assertTrue(snapshot.buildings.none { it.id in CampusDiningRecommender.CLOSED_CANTEEN_BUILDING_IDS })
        assertTrue(snapshot.buildings.none { it.name == "第二餐厅" || it.name == "第三餐厅" })
        Unit
    }

    @Test
    fun closedCanteenUserReviewHistoryStaysIntactInPrefs() = runBlocking {
        // 用户针对已停业餐厅的历史评价只是「不进入活跃聚合」，持久层保留原文
        val reviews = listOf(
            cn.edu.haut.campus.core.domain.CanteenDishReview(
                id = "r_hist_c2", canteenBuildingId = "b_canteen2",
                dishName = "旧黄焖鸡", stallLocation = "二楼", stars = 5,
                comment = "停业前常去", updatedAtEpochMs = 1_700_000_000_000L
            )
        )
        val encoded = cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.encodeList(reviews)
        val decoded = cn.edu.haut.campus.core.domain.CanteenDishReviewCodec.decodeList(encoded)
        assertEquals(1, decoded.size)
        assertEquals("b_canteen2", decoded.single().canteenBuildingId)
        assertEquals("停业前常去", decoded.single().comment)
        Unit
    }

    // ===== #16 社团场地规范化（缓存/重启路径） =====

    private fun clubJson(venue: String, campusName: String? = null, buildingName: String? = null, roomNumber: String? = null): String {
        val q = '"'
        fun kv(key: String, value: String) = "," + q + key + q + ":" + q + value + q
        val extras = StringBuilder()
        campusName?.let { extras.append(kv("campus_name", it)) }
        buildingName?.let { extras.append(kv("building_name", it)) }
        roomNumber?.let { extras.append(kv("room_number", it)) }
        return "{" + q + "school_id" + q + ":" + q + "haut" + q + "," + q + "version" + q + ":3," + q + "clubs" + q + ":[{" + q + "id" + q + ":" + q + "club_partner_computer_service" + q + "," + q + "name" + q + ":" + q + "计算机服务社" + q + "," + q + "category" + q + ":" + q + "TECH_INNOVATION" + q + "," + q + "activity_venue" + q + ":" + q + venue + q + extras.toString() + "," + q + "enabled" + q + ":true}]}"
    }
    @Test
    fun cachedLegacyClubVenueNormalizesOnReadAndAfterRestart() = runBlocking {
        val store = restartStore()
        store.edit { it[keyCampusContentJson] = clubJson(
            venue = "莲花街校区 8号教学楼 204",
            campusName = "嵩山路校区",
            buildingName = "8号教学楼",
            roomNumber = "204"
        ) }

        // 离线缓存读取路径：preferencesFlow 解码时同步纠正全部错位字段
        val firstRead = newRepository("club1", store).preferencesFlow.first()
        val club = firstRead.campusCommunityCatalog.clubs.single { it.id == "club_partner_computer_service" }
        assertEquals("莲花街校区 学生宿舍F01 W111", club.activityVenue)
        assertEquals("莲花街校区", club.campusName)
        assertEquals("学生宿舍F01", club.buildingName)
        assertEquals("W111", club.roomNumber)

        // 模拟重启：同一持久文件上重建 store+仓储，规范化结果一致
        val store2 = restartStore()
        val secondRead = newRepository("club2", store2).preferencesFlow.first()
        val clubAfterRestart = secondRead.campusCommunityCatalog.clubs.single { it.id == "club_partner_computer_service" }
        assertEquals("莲花街校区 学生宿舍F01 W111", clubAfterRestart.activityVenue)
        assertEquals("W111", clubAfterRestart.roomNumber)

        // 默认（无云端缓存）路径同样给出结构化正确值
        val store3 = restartStore()
        val defaultRead = newRepository("club3", store3).preferencesFlow.first()
        val defaultClub = defaultRead.campusCommunityCatalog.clubs.single { it.id == "club_partner_computer_service" }
        assertEquals("莲花街校区 学生宿舍F01 W111", defaultClub.activityVenue)
        assertEquals("W111", defaultClub.roomNumber)
        Unit
    }

    @Test
    fun refreshedNewerVenueIsNotPermanentlyOverwrittenByNormalizer() = runBlocking {
        // 先缓存旧版场地，读取端纠正；云端随后下发新版场地 -> 新值不被规范化器永久覆盖
        val store = restartStore()
        store.edit { it[keyCampusContentJson] = clubJson(venue = "大学生活动中心 204") }
        val stale = newRepository("stale", store).preferencesFlow.first()
        assertEquals("莲花街校区 学生宿舍F01 W111",
            stale.campusCommunityCatalog.clubs.single { it.id == "club_partner_computer_service" }.activityVenue)

        // 云端 refresh 持久化新 JSON（此处直接注入等价持久结果）
        store.edit { it[keyCampusContentJson] = clubJson(
            venue = "信息科学与工程学院楼 B205",
            campusName = "莲花街校区",
            buildingName = "信息科学与工程学院楼",
            roomNumber = "B205"
        ) }
        val refreshed = newRepository("fresh", store).preferencesFlow.first()
        val club = refreshed.campusCommunityCatalog.clubs.single { it.id == "club_partner_computer_service" }
        assertEquals("信息科学与工程学院楼 B205", club.activityVenue)
        assertEquals("信息科学与工程学院楼", club.buildingName)
        assertEquals("B205", club.roomNumber)
        Unit
    }
}

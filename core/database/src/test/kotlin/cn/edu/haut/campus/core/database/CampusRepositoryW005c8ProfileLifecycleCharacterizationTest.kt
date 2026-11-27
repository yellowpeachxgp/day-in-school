package cn.edu.haut.campus.core.database

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.campusservices.ParcelLifecycleStatus
import cn.edu.haut.campus.core.model.AntiLostTagItem
import cn.edu.haut.campus.core.model.CampusMomentsSnapshot
import cn.edu.haut.campus.core.model.HomeCardModule
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * W0-05-c8 ProfileDataWiper/启动清洗/迁移 companion/顶层模型搬迁特征冻结。
 * 只使用内存 Room/DataStore、内存 AES-GCM 凭据仓与合成账号；不访问真实学校账号或网络。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CampusRepositoryW005c8ProfileLifecycleCharacterizationTest {
    private lateinit var database: HautDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            HautDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private data class RepoFixture(
        val repository: CampusRepository,
        val dataStore: DataStore<Preferences>,
        val vault: AcademicCredentialVault,
        val storeFile: java.io.File
    ) {
        fun close() {
            runCatching { storeFile.delete() }
        }
    }

    private fun buildInMemoryVault(): AcademicCredentialVault {
        val keysMap = ConcurrentHashMap<String, SecretKey>()
        val storageMap = ConcurrentHashMap<String, ByteArray>()
        return AcademicCredentialVault(
            keys = object : AcademicCredentialKeyProvider {
                override fun existing(scope: AcademicCredentialScope) = keysMap[scope.storageKey]
                override fun getOrCreate(scope: AcademicCredentialScope) = keysMap.getOrPut(scope.storageKey) {
                    KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                }
                override fun delete(scope: AcademicCredentialScope) { keysMap.remove(scope.storageKey) }
            },
            storage = object : AcademicCredentialStorage {
                override fun read(scope: AcademicCredentialScope) = storageMap[scope.storageKey]
                override fun write(scope: AcademicCredentialScope, envelope: ByteArray) { storageMap[scope.storageKey] = envelope }
                override fun delete(scope: AcademicCredentialScope) { storageMap.remove(scope.storageKey) }
            }
        )
    }

    private suspend fun buildRepo(
        profileSeed: String,
        sanitizeHook: (suspend (String) -> Unit)? = null,
        seedPreferences: suspend (DataStore<Preferences>) -> Unit = {}
    ): RepoFixture {
        val context = RuntimeEnvironment.getApplication()
        val storeFile = java.io.File.createTempFile("w005c8_test_" + profileSeed + "_", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            produceFile = { storeFile }
        )
        seedPreferences(dataStore)
        val storage = isolatedAccountStoragePartition("prof_" + profileSeed)
        val vault = buildInMemoryVault()
        val repo = CampusRepository(
            appContext = context,
            database = database,
            preferences = dataStore,
            accountProfile = AccountProfileEntity(
                id = "prof_" + profileSeed,
                accountId = "acct_" + profileSeed,
                schoolId = "haut",
                schoolName = "河南工业大学",
                province = "河南省",
                city = "郑州市",
                campusId = "lianhuajie",
                campusName = "莲花街校区",
                timeZoneId = "Asia/Shanghai",
                label = "c8数据生命周期特征",
                isLegacy = false,
                databaseName = storage.databaseName,
                preferencesName = storage.preferencesName,
                createdAtEpochMs = 1_000L
            ),
            academicCredentialVaultOverride = vault,
            sanitizeBookingPlanWriteHookForTest = sanitizeHook
        ).also { it.activateSession(1L) }
        return RepoFixture(repo, dataStore, vault, storeFile)
    }

    private fun dirtyParcel() = ParcelItemEntity(
        id = "parcel_w005c8_dirty",
        trackingNumber = "TRACK-W005C8-DIRTY",
        carrierCode = "cainiao",
        carrierName = "null",
        status = ParcelLifecycleStatus.READY_FOR_PICKUP.wireName,
        pickupCode = "null",
        stationName = "undefined",
        itemTitle = "none",
        estimatedDeliveryText = "null",
        sourcesJson = "[]",
        fieldProvenanceJson = "{}",
        checkpointsJson = "[]",
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 2_000L
    )

    private fun bookingPlan(
        id: String,
        area: String = "五层中区",
        seat: String = "001",
        externalBookingId: String? = null,
        evidenceSha256: String? = null,
        diagnostic: String? = null
    ) = LibraryBookingPlanEntity(
        id = id,
        schoolId = "haut",
        executionMode = "LOCAL_DEVICE",
        targetLocalDate = "2099-01-04",
        targetLocalTime = "08:30:00",
        timezoneId = "Asia/Shanghai",
        precheckAtEpochMillis = 1_000L,
        finalCheckAtEpochMillis = 2_000L,
        fireAtEpochMillis = 3_000L,
        precheckStatus = "ARMED",
        finalCheckStatus = "ARMED",
        fireStatus = "ARMED",
        preferredAreaName = area,
        preferredSeatLabel = seat,
        allowAlternativeSeat = true,
        cloudJobId = null,
        externalBookingId = externalBookingId,
        evidenceSha256 = evidenceSha256,
        priceMicropeach = 0L,
        paymentState = "NOT_REQUIRED",
        status = "ARMED",
        stateVersion = 7,
        generation = 1,
        lastDiagnosticMessage = diagnostic,
        createdAtEpochMillis = 500L,
        updatedAtEpochMillis = 2_000L,
        repeatDays = 1,
        remainingDays = 1
    )

    @Test
    fun w005c8_top_level_models_and_migration_companion_shape_snapshot() {
        assertEquals(HomeCardModule.defaultOrderIds, DEFAULT_HOME_CARD_ORDER)
        val preferencesState = AppPreferencesState()
        assertFalse(preferencesState.gpaPrivacyShieldEnabled)
        assertEquals("HAUT_BLUE", preferencesState.themePaletteKey)
        assertEquals(HomeCardModule.defaultOrderIds, preferencesState.homeCardOrder)
        assertTrue(preferencesState.autoSyncEnabled)
        assertEquals("auto", preferencesState.selectedCampusId)
        assertEquals(7, DirectScheduleData::class.java.constructors.single().parameterCount)

        assertEquals(1, CampusRepository.MIGRATION_1_2.startVersion)
        assertEquals(2, CampusRepository.MIGRATION_1_2.endVersion)
        assertEquals(2, CampusRepository.MIGRATION_2_3.startVersion)
        assertEquals(3, CampusRepository.MIGRATION_2_3.endVersion)
    }

    @Test
    fun w005c8_startup_sanitize_cleans_null_literals_and_legacy_samples() = runBlocking {
        val cloudDao = database.cloudAndParcelDao()
        cloudDao.upsertParcelItem(dirtyParcel())
        cloudDao.upsertLibraryBookingPlan(
            bookingPlan(
                id = "plan_w005c8_dirty",
                area = "null",
                seat = "undefined",
                externalBookingId = "null",
                evidenceSha256 = "none",
                diagnostic = "null"
            )
        )
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO installed_extension_plugins (" +
                "pluginId, name, activeVersion, previousVersion, schemaFormat, status, grantedPermissionsJson, uiSlotsJson, updatedAtEpochMillis" +
                ") VALUES ('org.example.hello-campus', 'legacy sample', '1.0.0', NULL, 'dis-extension/v2', 'ENABLED', '[]', '[]', 1000)"
        )
        val writeEvents = Collections.synchronizedList(mutableListOf<String>())
        val afterWrite = CountDownLatch(1)
        val fixture = buildRepo(
            profileSeed = "startup_sanitize",
            sanitizeHook = { stage ->
                writeEvents += stage
                if (stage == "after_write") afterWrite.countDown()
            },
            seedPreferences = { dataStore ->
                dataStore.edit { prefs ->
                    val snapshot = CampusMomentsSnapshot(
                        updatedAt = "2026-11-28T00:00:00Z",
                        antiLostTags = listOf(
                            AntiLostTagItem(
                                id = "tag_keep_01",
                                tagCode = "HAUT-TAG-KEEP01",
                                itemName = "校园卡"
                            ),
                            AntiLostTagItem(
                                id = "tag_default_01",
                                tagCode = "HAUT-TAG-7E2A9B",
                                itemName = "历史样例物品"
                            )
                        )
                    )
                    prefs[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON] =
                        CampusCloudOidcHelper.encodeMomentsSnapshotJsonString(snapshot)
                }
            }
        )
        try {
            assertTrue("启动清洗必须到达 after_write 测试闩锁", afterWrite.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("before_write", "after_write"), writeEvents)

            val parcel = cloudDao.getParcelByTrackingNumber("TRACK-W005C8-DIRTY")!!
            assertNull(parcel.pickupCode)
            assertEquals("莲花街北苑菜鸟驿站", parcel.stationName)
            assertEquals("校园快递", parcel.carrierName)
            assertNull(parcel.itemTitle)
            assertEquals(ParcelLifecycleStatus.IN_TRANSIT.wireName, parcel.status)
            assertEquals("运输中，预计 1-2 日送达校园驿站", parcel.estimatedDeliveryText)

            val plan = cloudDao.getLibraryBookingPlanById("plan_w005c8_dirty")!!
            assertEquals("五层中区", plan.preferredAreaName)
            assertEquals("001", plan.preferredSeatLabel)
            assertNull(plan.externalBookingId)
            assertNull(plan.evidenceSha256)
            assertEquals("", plan.lastDiagnosticMessage)
            assertEquals(7, plan.stateVersion)
            assertEquals(1, plan.generation)
            withTimeout(5_000) {
                while (cloudDao.getInstalledExtensionPluginsSnapshot().any { it.pluginId == "org.example.hello-campus" }) {
                    delay(25)
                }
                var cleanedTagIds: List<String> = emptyList()
                while (cleanedTagIds != listOf("tag_keep_01")) {
                    val rawMoments = fixture.dataStore.data.first()[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
                    cleanedTagIds = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)
                        ?.antiLostTags
                        ?.map { it.id }
                        .orEmpty()
                    if (cleanedTagIds != listOf("tag_keep_01")) delay(25)
                }
            }

            val rawMoments = fixture.dataStore.data.first()[CampusRepositoryPrefKeys.CLOUD_MOMENTS_SNAPSHOT_JSON]
            val cleaned = CampusCloudOidcHelper.parseMomentsSnapshotJsonString(rawMoments)!!
            assertEquals(listOf("tag_keep_01"), cleaned.antiLostTags.map { it.id })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun w005c8_wipe_all_personal_data_preserves_hook_generation_and_store_contract() = runBlocking {
        val fixture = buildRepo("wipe_lifecycle")
        try {
            val repo = fixture.repository
            val cloudDao = database.cloudAndParcelDao()
            repo.saveAuthorizedSyncCredentials("20240001", "pw-c8", "2026", "3")
            val credentialAccountId = fixture.dataStore.data.first()[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID]
            assertNotNull(credentialAccountId)
            cloudDao.upsertCloudIdentityBinding(
                CloudIdentityBindingEntity(
                    profileId = repo.profileId,
                    cloudUserId = "cloud-user-c8",
                    ssoIssuer = "issuer-c8",
                    ssoSubject = "subject-c8",
                    displayName = "合成用户",
                    peachBalanceMicropeach = 1L,
                    linkedAtEpochMillis = 1_000L,
                    updatedAtEpochMillis = 1_000L
                )
            )
            cloudDao.upsertParcelItem(dirtyParcel().copy(id = "parcel_w005c8_wipe", trackingNumber = "TRACK-W005C8-WIPE"))
            cloudDao.upsertLibraryBookingPlan(bookingPlan("plan_w005c8_wipe"))
            fixture.dataStore.edit { prefs ->
                prefs[CampusRepositoryPrefKeys.OFFICIAL_GPA] = "3.50"
                prefs[CampusRepositoryPrefKeys.REMINDER_DIAGNOSTIC_LOGS] = "c8-diagnostic"
            }
            var legacyHookCount = 0
            var registeredHookCount = 0
            repo.onBeforeWipePersonalDataHook = { legacyHookCount += 1 }
            repo.registerBeforeWipeHook("w005c8_test_hook") { registeredHookCount += 1 }

            repo.wipeAllPersonalData()

            assertEquals(1, legacyHookCount)
            assertEquals(1, registeredHookCount)
            assertTrue(cloudDao.getParcelItemsSnapshot().isEmpty())
            assertTrue(cloudDao.getLibraryBookingPlansSnapshot().isEmpty())
            assertNull(cloudDao.getCloudIdentityBindingSnapshot())
            val prefs = fixture.dataStore.data.first()
            assertNull(prefs[CampusRepositoryPrefKeys.ACADEMIC_CREDENTIAL_ACCOUNT_ID])
            assertNull(prefs[CampusRepositoryPrefKeys.OFFICIAL_GPA])
            assertNull(prefs[CampusRepositoryPrefKeys.REMINDER_DIAGNOSTIC_LOGS])
            assertEquals(false, prefs[CampusRepositoryPrefKeys.AUTO_SYNC_ENABLED])
            assertEquals(true, prefs[CampusRepositoryPrefKeys.SEEDED])
            assertEquals(true, prefs[CampusRepositoryPrefKeys.CLEAN_BASE_V2])
            assertEquals(false, prefs[CampusRepositoryPrefKeys.ONBOARDING_COMPLETED])
            assertEquals(1L, prefs[CampusRepositoryPrefKeys.CLOUD_IDENTITY_GENERATION])
            assertTrue(
                prefs[CampusRepositoryPrefKeys.CLOUD_IDENTITY_UNLINK_TOMBSTONE]
                    ?.startsWith("cloud-user-c8:1:") == true
            )
            val scope = AcademicCredentialScope(
                schoolId = "haut",
                accountId = credentialAccountId!!,
                providerId = "haut-zf-modern"
            )
            assertTrue(fixture.vault.read(scope) is AcademicCredentialReadResult.Empty)
        } finally {
            fixture.close()
        }
    }
}

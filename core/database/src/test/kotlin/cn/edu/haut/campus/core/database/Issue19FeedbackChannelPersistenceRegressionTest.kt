package cn.edu.haut.campus.core.database

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #19 用户交流 QQ 群/频道配置入口的持久化回归（issue 原文：设立QQ群或QQ频道供用户讨论问题）。
 *
 * 界面配置对话框经 CampusRepository.saveCustomClubGroupNumber / saveCustomChannelUrl
 * 写入哨兵键 "__app_user_feedback_qq_group__" / "__app_user_feedback_channel__"，
 * 读取侧经 preferencesFlow 的 customClubGroupNumbers / customChannelUrls 消费。
 * 本测试锁定这条真实读写链路的语义：
 * - 保存 trim 后可经 UI 同一消费面读回；
 * - 覆盖写生效；
 * - 空白/纯空白保存 = 删除键（云端 officialChannels 回退值才能重新生效，这是两级解析链成立的前提）；
 * - 配置在 DataStore 文件级重建（模拟进程重启）后保留；
 * - 哨兵键与普通社团/频道键互不影响。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class Issue19FeedbackChannelPersistenceRegressionTest {

    private val qqKey = "__app_user_feedback_qq_group__"
    private val channelKey = "__app_user_feedback_channel__"

    private lateinit var context: Context
    private lateinit var database: HautDatabase
    private lateinit var storeFile: File
    private var storeScope: CoroutineScope? = null
    private val createdRepos = mutableListOf<CampusRepository>()

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
            label = "issue19 测试账号",
            isLegacy = false,
            databaseName = storage.databaseName,
            preferencesName = storage.preferencesName,
            createdAtEpochMs = 1_000L
        )
    }

    /** 与 HautBlueThemePersistenceTest 相同的重建手法：取消旧 scope 后在同一文件上新建 store。 */
    private suspend fun restartStore(): DataStore<Preferences> {
        storeScope?.cancel()
        delay(80)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        storeScope = scope
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile }
        )
    }

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
        storeFile = File.createTempFile("issue19_feedback_test_", ".preferences_pb")
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

    @Test
    fun qqGroupSaveTrimOverwriteAndBlankClearRoundTrip() = runBlocking {
        val repo = newRepository("qq_rt", restartStore())

        // 初始未配置：UI 两级解析链第一层必须拿不到值
        assertNull(repo.preferencesFlow.first().customClubGroupNumbers[qqKey])

        // 保存（含前后空白）→ trim 后可经 UI 同一消费面读回
        repo.saveCustomClubGroupNumber(qqKey, "  987654321  ")
        assertEquals(
            "987654321",
            repo.preferencesFlow.first().customClubGroupNumbers[qqKey]
        )

        // 覆盖写生效（修改群号路径）
        repo.saveCustomClubGroupNumber(qqKey, "123456789")
        assertEquals(
            "123456789",
            repo.preferencesFlow.first().customClubGroupNumbers[qqKey]
        )

        // 空白保存 = 删除键；否则云端 officialChannels 回退值永远无法重新生效（#19 两级链前提）
        repo.saveCustomClubGroupNumber(qqKey, "")
        assertFalse(
            "空白保存必须移除键以恢复云端回退",
            repo.preferencesFlow.first().customClubGroupNumbers.containsKey(qqKey)
        )

        // 纯空白同样视为清除
        repo.saveCustomClubGroupNumber(qqKey, "555666777")
        repo.saveCustomClubGroupNumber(qqKey, "   ")
        assertFalse(repo.preferencesFlow.first().customClubGroupNumbers.containsKey(qqKey))
        Unit
    }

    @Test
    fun channelUrlSaveTrimOverwriteAndBlankClearRoundTrip() = runBlocking {
        val repo = newRepository("ch_rt", restartStore())

        assertNull(repo.preferencesFlow.first().customChannelUrls[channelKey])

        repo.saveCustomChannelUrl(channelKey, "  https://pd.qq.com/s/dayinschool  ")
        assertEquals(
            "https://pd.qq.com/s/dayinschool",
            repo.preferencesFlow.first().customChannelUrls[channelKey]
        )

        repo.saveCustomChannelUrl(channelKey, "https://pd.qq.com/s/updated")
        assertEquals(
            "https://pd.qq.com/s/updated",
            repo.preferencesFlow.first().customChannelUrls[channelKey]
        )

        repo.saveCustomChannelUrl(channelKey, "")
        assertFalse(
            "空白保存必须移除频道键以恢复云端回退",
            repo.preferencesFlow.first().customChannelUrls.containsKey(channelKey)
        )
        Unit
    }

    @Test
    fun feedbackConfigSurvivesDataStoreRecreation() = runBlocking {
        // 进程重启场景：配置必须持久，不能要求用户每次重进 App 重新绑定
        var repo = newRepository("persist_a", restartStore())
        repo.saveCustomClubGroupNumber(qqKey, "987654321")
        repo.saveCustomChannelUrl(channelKey, "https://pd.qq.com/s/dayinschool")
        assertEquals("987654321", repo.preferencesFlow.first().customClubGroupNumbers[qqKey])

        repo = newRepository("persist_b", restartStore())
        assertEquals(
            "重启后 QQ 群配置必须保留",
            "987654321",
            repo.preferencesFlow.first().customClubGroupNumbers[qqKey]
        )
        assertEquals(
            "重启后频道链接配置必须保留",
            "https://pd.qq.com/s/dayinschool",
            repo.preferencesFlow.first().customChannelUrls[channelKey]
        )
        Unit
    }

    @Test
    fun sentinelKeysDoNotInterfereWithRegularClubAndChannelEntries() = runBlocking {
        val repo = newRepository("isolation", restartStore())
        val regularClub = "club_partner_computer_service"
        val regularChannel = "preset_channel_campus"

        repo.saveCustomClubGroupNumber(regularClub, "1043885481")
        repo.saveCustomChannelUrl(regularChannel, "https://pd.qq.com/s/regular")
        repo.saveCustomClubGroupNumber(qqKey, "987654321")
        repo.saveCustomChannelUrl(channelKey, "https://pd.qq.com/s/feedback")

        var prefs = repo.preferencesFlow.first()
        assertEquals("1043885481", prefs.customClubGroupNumbers[regularClub])
        assertEquals("987654321", prefs.customClubGroupNumbers[qqKey])
        assertEquals("https://pd.qq.com/s/regular", prefs.customChannelUrls[regularChannel])
        assertEquals("https://pd.qq.com/s/feedback", prefs.customChannelUrls[channelKey])

        // 清除哨兵键不得波及普通社团/频道键；反之亦然
        repo.saveCustomClubGroupNumber(qqKey, "")
        repo.saveCustomChannelUrl(channelKey, "")
        prefs = repo.preferencesFlow.first()
        assertFalse(prefs.customClubGroupNumbers.containsKey(qqKey))
        assertFalse(prefs.customChannelUrls.containsKey(channelKey))
        assertEquals(
            "清除反馈哨兵键不得波及社团招新群绑定",
            "1043885481",
            prefs.customClubGroupNumbers[regularClub]
        )
        assertEquals(
            "https://pd.qq.com/s/regular",
            prefs.customChannelUrls[regularChannel]
        )
        Unit
    }
}

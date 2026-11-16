package cn.edu.haut.campus.core.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.edu.haut.campus.core.campusservices.BookingExecutionMode
import cn.edu.haut.campus.core.campusservices.CloudTokenSecureVault
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 仅连接 adb reverse 的隔离 Cloud；真实 Android Keystore、Room 与实际仓库提交入口。 */
@RunWith(AndroidJUnit4::class)
class CloudSessionDeviceTest {
    private fun request(method: String, route: String, body: String? = null, token: String? = null, headers: Map<String, String> = emptyMap()): String {
        val connection = URL("http://127.0.0.1:18190$route").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            connection.setRequestProperty("Content-Type", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) { connection.doOutput = true; connection.outputStream.use { it.write(body.toByteArray()) } }
            return (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    @Test fun legacyUpgradeRefreshAndCreatePlanThroughRealCloud() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val seed = JSONObject(request("GET", "/fixture"))
        val id = "isolated_device_${UUID.randomUUID()}"
        val storage = isolatedAccountStoragePartition(id)
        val profile = AccountProfileEntity(id = id, accountId = id, schoolId = "haut", schoolName = "河南工业大学",
            province = "河南省", city = "郑州市", campusId = "lianhuajie", campusName = "莲花街校区",
            timeZoneId = "Asia/Shanghai", label = "隔离设备测试", isLegacy = false,
            databaseName = storage.databaseName, preferencesName = storage.preferencesName, createdAtEpochMs = 1)
        val database = Room.inMemoryDatabaseBuilder(context, HautDatabase::class.java).build()
        val file = File(context.filesDir, "$id.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val prefs = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val repos = mutableListOf<CampusRepository>()
        fun repository() = CampusRepository(context, database, prefs, profile).also { repo ->
            repo.activateSession(1); repos += repo
            repo.cloudHttpTransportForTest = { method, url, body, token, headers -> request(method, URI(url).rawPath, body, token, headers) }
        }
        try {
            database.cloudAndParcelDao().upsertCloudIdentityBinding(CloudIdentityBindingEntity(profileId = id,
                cloudUserId = seed.getString("cloud_user_id"), ssoIssuer = seed.getString("issuer"),
                ssoSubject = seed.getString("subject"), displayName = "隔离测试用户", peachBalanceMicropeach = 50000,
                linkedAtEpochMillis = 1, updatedAtEpochMillis = 1))
            prefs.edit {
                it[stringPreferencesKey("cloud_access_token_v1")] = seed.getString("access_token")
                it[stringPreferencesKey("cloud_refresh_token_v1")] = seed.getString("refresh_token")
            }
            val first = repository()
            first.saveAuthorizedSyncCredentials("2026998888", "仅供隔离测试的虚构口令", "2026", "1")
            first.syncWithCampusCloud().getOrThrow()
            val stored = prefs.data.first()
            val vault = CloudTokenSecureVault(context, scopeId = id)
            val at = stored[stringPreferencesKey("cloud_access_token_v1")]!!
            val rt = stored[stringPreferencesKey("cloud_refresh_token_v1")]!!
            assertTrue(at.startsWith(CloudTokenSecureVault.SEALED_PREFIX))
            assertFalse(vault.unseal(at).isBlank()); assertFalse(vault.unseal(rt).isBlank())
            val recreated = repository()
            recreated.syncWithCampusCloud().getOrThrow()
            val date = LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1)
            val plan = recreated.createDualEngineBookingPlan(executionMode = BookingExecutionMode.CLOUD_HOSTED,
                targetDate = date, targetTimeText = "07:00", preferredAreaName = "隔离测试自习室", preferredSeatLabel = "01桌 A座",
                expectedIdentity = recreated.snapshotBookingSubmissionIdentity()).getOrThrow()
            assertEquals(date.toString(), plan.targetLocalDate)
            assertFalse(plan.cloudJobId.isNullOrBlank())
            val evidence = JSONObject(request("GET", "/evidence"))
            assertEquals(1, evidence.getInt("refresh_calls"))
            assertEquals(1, evidence.getInt("plans")); assertEquals(1, evidence.getInt("jobs"))
            assertEquals(date.toString(), evidence.getString("target_date"))
            assertEquals(0, evidence.getInt("orders")); assertEquals(0, evidence.getInt("finance_operations"))
        } finally {
            repos.forEach { repo ->
                val field = CampusRepository::class.java.getDeclaredField("repoScope").apply { isAccessible = true }
                (field.get(repo) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin()
            }
            scope.cancel(); database.close(); file.delete()
            CloudTokenSecureVault(context, scopeId = id).wipe()
        }
    }
}

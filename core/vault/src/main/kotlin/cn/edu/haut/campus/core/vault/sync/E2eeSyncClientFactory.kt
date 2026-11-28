package cn.edu.haut.campus.core.vault.sync

import android.content.Context
import cn.edu.haut.campus.core.campusservices.CampusCloudOidcHelper
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.CloudIdentityE2eeAccessTokenProviderRegistry

/** 生产装配工厂。token 只在 HttpE2eeSyncApi 当前请求中使用；base URL 沿 repository 已验证身份快照。 */
object E2eeSyncClientFactory {
    fun create(
        context: Context,
        repository: CampusRepository,
        transport: E2eeSyncHttpTransport? = null
    ): E2eeSyncClient? {
        val backupStore = E2eeSyncBackupStoreRegistry.forProfile(repository.profileId) ?: return null
        val appContext = context.applicationContext
        val cloudTokenDelegate = CloudIdentityE2eeAccessTokenProviderRegistry.forProfile(repository.profileId) ?: return null
        val tokenProvider = E2eeSyncAccessTokenProvider { forceRefresh, failedAccessToken ->
            cloudTokenDelegate.resolve(forceRefresh, failedAccessToken)
        }
        val httpTransport = transport ?: HttpsE2eeSyncHttpTransport()
        return E2eeSyncClient(
            stateStore = FileE2eeSyncStateStore(appContext, repository.profileId),
            keyStore = AndroidE2eeSyncKeyStore(appContext, repository.profileId),
            backupStore = backupStore,
            cloudUserIdProvider = { repository.snapshotCloudIdentitySession()?.cloudUserId },
            apiFactory = { deviceProvider ->
                val baseUrl = repository.snapshotCloudIdentitySession()?.apiBaseUrl
                    ?: CampusCloudOidcHelper.DEFAULT_CLOUD_BASE_URL
                HttpE2eeSyncApi(
                    cloudBaseUrl = baseUrl,
                    tokenProvider = tokenProvider,
                    syncDeviceIdProvider = deviceProvider,
                    transport = httpTransport
                )
            }
        )
    }
}

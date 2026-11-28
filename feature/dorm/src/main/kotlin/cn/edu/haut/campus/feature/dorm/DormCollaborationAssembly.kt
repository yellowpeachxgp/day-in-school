package cn.edu.haut.campus.feature.dorm

import cn.edu.haut.campus.core.community.CommunityCacheIdentity
import cn.edu.haut.campus.core.community.CommunityClientFactory
import cn.edu.haut.campus.core.community.CommunityFeatureGate
import cn.edu.haut.campus.core.community.CommunitySessionSnapshot
import cn.edu.haut.campus.core.community.CommunityTransport
import cn.edu.haut.campus.core.community.OkHttpCommunityTransport
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.CloudIdentityCommunityAccessTokenProviderRegistry
import cn.edu.haut.campus.core.database.automationScope
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.feature.dorm.data.CommunityDormCollaborationAdapter
import cn.edu.haut.campus.feature.dorm.data.RepositoryDormUtilityStatusPort
import java.io.File
import java.time.Clock
import kotlinx.coroutines.flow.first

/** 宿舍协作运行时装配结果；access token 只留在 session provider 闭包调用期内，不进入 feature 状态。 */
class DormCollaborationRuntime(
    val collaborationPort: DormCollaborationPort,
    val utilityStatusPort: DormUtilityStatusPort,
    val currentCloudUserId: suspend () -> String?,
)

/** W4-08 生产装配：复用 W4-05 文件缓存 client 与 CloudIdentityRepository 窄令牌端口，不修改 CampusRepository facade。 */
object DormCollaborationAssembly {
    suspend fun create(
        repository: CampusRepository,
        filesDir: File,
        featureGate: CommunityFeatureGate = CommunityFeatureGate.ENABLED,
        transport: CommunityTransport = OkHttpCommunityTransport(),
        clock: Clock = Clock.systemUTC(),
    ): DormCollaborationRuntime {
        val tokenProvider = CloudIdentityCommunityAccessTokenProviderRegistry.forProfile(repository.profileId)
            ?: error("当前 Profile 的 CloudIdentity community 窄令牌端口尚未注册")
        val initialIdentity = repository.snapshotCloudIdentitySession()
        val baseUrl = initialIdentity?.apiBaseUrl ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(
            repository.preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl,
        )
        val sessionSnapshotProvider: suspend () -> CommunitySessionSnapshot = {
            val identity = repository.snapshotCloudIdentitySession()
            if (identity == null) {
                CommunitySessionSnapshot(accessToken = null, cacheIdentity = null)
            } else {
                val token = tokenProvider.resolveForIdentity(identity)
                CommunitySessionSnapshot(
                    accessToken = token.takeIf { it.isNotBlank() },
                    cacheIdentity = CommunityCacheIdentity(
                        cloudUserId = identity.cloudUserId,
                        identityGeneration = identity.generation,
                    ),
                )
            }
        }
        val clientAssembly = CommunityClientFactory.createFileBacked(
            filesDir = filesDir,
            baseUrl = baseUrl,
            scope = repository.automationScope,
            sessionSnapshotProvider = sessionSnapshotProvider,
            featureGate = featureGate,
            transport = transport,
            clock = clock,
        )
        return DormCollaborationRuntime(
            collaborationPort = CommunityDormCollaborationAdapter(clientAssembly.client),
            utilityStatusPort = RepositoryDormUtilityStatusPort(repository),
            currentCloudUserId = { repository.snapshotCloudIdentitySession()?.cloudUserId },
        )
    }
}

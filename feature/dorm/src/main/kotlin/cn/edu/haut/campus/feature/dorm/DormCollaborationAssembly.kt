@file:Suppress("DEPRECATION")

package cn.edu.haut.campus.feature.dorm

import cn.edu.haut.campus.core.community.CommunityCacheIdentity
import cn.edu.haut.campus.core.community.CommunityClientFactory
import cn.edu.haut.campus.core.community.CommunityFeatureGate
import cn.edu.haut.campus.core.community.CommunitySessionSnapshot
import cn.edu.haut.campus.core.community.CommunityTransport
import cn.edu.haut.campus.core.community.OkHttpCommunityTransport
import cn.edu.haut.campus.core.database.CampusRepository
import cn.edu.haut.campus.core.database.CloudIdentityRepository
import cn.edu.haut.campus.core.database.automationScope
import cn.edu.haut.campus.core.model.NetworkProxyConfig
import cn.edu.haut.campus.feature.dorm.data.CommunityDormCollaborationAdapter
import cn.edu.haut.campus.feature.dorm.data.RepositoryDormUtilityStatusPort
import java.io.File
import java.time.Clock
import kotlinx.coroutines.flow.first

/** feature 侧窄身份 port：只能按请求快照读取一次令牌，不能接触 vault、refresh token 或任意 facade 方法。 */
internal interface DormCloudIdentityPort {
    suspend fun snapshot(): CampusRepository.CloudIdentitySessionSnapshot?
    suspend fun resolveAccessToken(snapshot: CampusRepository.CloudIdentitySessionSnapshot): String
}

internal class CloudIdentityDormPort(
    private val repository: CloudIdentityRepository,
) : DormCloudIdentityPort {
    override suspend fun snapshot(): CampusRepository.CloudIdentitySessionSnapshot? =
        repository.snapshotCloudIdentitySession()

    override suspend fun resolveAccessToken(snapshot: CampusRepository.CloudIdentitySessionSnapshot): String =
        repository.resolveCommunityAccessToken(snapshot)
}

/** 宿舍协作运行时装配结果；access token 只留在 session provider 闭包调用期内，不进入 feature 状态。 */
class DormCollaborationRuntime(
    val collaborationPort: DormCollaborationPort,
    val utilityStatusPort: DormUtilityStatusPort,
    val currentCloudUserId: suspend () -> String?,
)

/** W4-08 生产装配：复用 W4-05 文件缓存 client；身份访问只经只读 val 暴露的 CloudIdentityRepository 窄 port。 */
internal object DormCollaborationAssembly {
    suspend fun create(
        repository: CampusRepository,
        filesDir: File,
        featureGate: CommunityFeatureGate = CommunityFeatureGate.ENABLED,
        transport: CommunityTransport = OkHttpCommunityTransport(),
        clock: Clock = Clock.systemUTC(),
        identityPort: DormCloudIdentityPort = CloudIdentityDormPort(repository.cloudIdentityRepository),
    ): DormCollaborationRuntime {
        val initialIdentity = identityPort.snapshot()
        val baseUrl = initialIdentity?.apiBaseUrl ?: NetworkProxyConfig.normalizeCloudApiBaseUrl(
            repository.preferencesFlow.first().networkProxyConfig.customCloudApiBaseUrl,
        )
        val sessionSnapshotProvider: suspend () -> CommunitySessionSnapshot = {
            val identity = identityPort.snapshot()
            if (identity == null) {
                CommunitySessionSnapshot(accessToken = null, cacheIdentity = null)
            } else {
                val token = identityPort.resolveAccessToken(identity)
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
            currentCloudUserId = { identityPort.snapshot()?.cloudUserId },
        )
    }
}

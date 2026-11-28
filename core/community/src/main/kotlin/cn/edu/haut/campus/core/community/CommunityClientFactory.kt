package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.io.File
import java.time.Clock

/** Client plus the cache partition handle, exposed so feature modules can inspect or invalidate without guessing paths. */
data class CommunityClientAssembly(
    val client: CommunityClient,
    val cacheStore: CommunityCacheStore,
)

/** Factory for the supported Android integration shape: profile-isolated file cache plus injected cloud auth, cloud identity generation, and feature gate. */
object CommunityClientFactory {
    fun createFileBacked(
        filesDir: File,
        baseUrl: String,
        scope: SchoolAccountScope,
        accessTokenProvider: suspend () -> String?,
        cacheIdentityProvider: suspend () -> CommunityCacheIdentity?,
        featureGate: CommunityFeatureGate,
        transport: CommunityTransport = OkHttpCommunityTransport(),
        clock: Clock = Clock.systemUTC(),
    ): CommunityClientAssembly {
        val cacheStore = FileCommunityCacheStore(CommunityCacheDirectories.rootForProfile(filesDir, scope.profileId.value))
        return CommunityClientAssembly(
            client = CommunityClient(
                baseUrl = baseUrl,
                scope = scope,
                accessTokenProvider = accessTokenProvider,
                featureGate = featureGate,
                cacheStore = cacheStore,
                cacheIdentityProvider = cacheIdentityProvider,
                transport = transport,
                clock = clock,
            ),
            cacheStore = cacheStore,
        )
    }
}

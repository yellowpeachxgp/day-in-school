package cn.edu.haut.campus.core.community

import cn.edu.haut.campus.core.model.SchoolAccountScope
import java.io.File
import java.time.Clock

/** Client plus the cache partition handle, exposed so feature modules can inspect or invalidate without guessing paths. */
data class CommunityClientAssembly(
    val client: CommunityClient,
    val cacheStore: CommunityCacheStore,
)

/** Factory for the supported Android integration shape: profile-isolated file cache plus atomically injected cloud session snapshot and feature gate. */
object CommunityClientFactory {
    fun createFileBacked(
        filesDir: File,
        baseUrl: String,
        scope: SchoolAccountScope,
        sessionSnapshotProvider: suspend () -> CommunitySessionSnapshot,
        featureGate: CommunityFeatureGate,
        transport: CommunityTransport = OkHttpCommunityTransport(),
        clock: Clock = Clock.systemUTC(),
    ): CommunityClientAssembly {
        val cacheStore = FileCommunityCacheStore(CommunityCacheDirectories.rootForProfile(filesDir, scope.profileId.value))
        return CommunityClientAssembly(
            client = CommunityClient(
                baseUrl = baseUrl,
                scope = scope,
                sessionSnapshotProvider = sessionSnapshotProvider,
                featureGate = featureGate,
                cacheStore = cacheStore,
                transport = transport,
                clock = clock,
            ),
            cacheStore = cacheStore,
        )
    }
}

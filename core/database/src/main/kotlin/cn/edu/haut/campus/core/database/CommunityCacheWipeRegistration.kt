package cn.edu.haut.campus.core.database

import cn.edu.haut.campus.core.community.CommunityCacheDirectories
import cn.edu.haut.campus.core.community.FileCommunityCacheStore
import java.io.File

/**
 * W4-05 community response-cache erasure registration point.
 *
 * Room v17 is intentionally not used here: D14 assigns ordered Room versions and v11/v12
 * are still ahead of this card, so the first persistent cache is the profile-scoped,
 * atomic file cache implemented by :core:community. This object is the single database-side
 * seam that connects that cache partition to ProfileDataWiper without widening the public
 * CampusRepository facade.
 */
object CommunityCacheWipeRegistration {
    fun rootForProfile(filesDir: File, profileId: String): File =
        CommunityCacheDirectories.rootForProfile(filesDir, profileId)

    suspend fun wipeProfile(filesDir: File, profileId: String) {
        FileCommunityCacheStore(rootForProfile(filesDir, profileId)).wipe()
    }
}

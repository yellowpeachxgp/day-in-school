package cn.edu.haut.campus.core.database

import android.content.Context
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginFileStore
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginInstaller
import cn.edu.haut.campus.core.packages.v2.ExtensionPublisherTrustRoots
import java.io.File

/**
 * Process-local installer registry keyed by the stable per-profile extension package partition.
 *
 * W6-02 uses this as the narrow DI seam for declarative UI and plugin management. It keeps
 * CampusRepository untouched while ensuring the W6-01a compatibility projection and feature
 * surfaces observe the same installer revision after lifecycle changes.
 */
object ExtensionPluginRuntimeRegistry {
    private val installers = HashMap<String, ExtensionPluginInstaller>()

    @Synchronized
    fun installerForProfile(filesDir: File, profileId: String): ExtensionPluginInstaller {
        val root = ExtensionPluginWipeRegistration.rootForProfile(filesDir, profileId)
        val key = root.absoluteFile.toPath().normalize().toString()
        return installers.getOrPut(key) {
            ExtensionPluginInstaller(
                store = ExtensionPluginFileStore(root.toPath()),
                keys = ExtensionPublisherTrustRoots.officialExamplesRegistry()
            )
        }
    }

    @Synchronized
    fun installerForContext(context: Context, profileId: String): ExtensionPluginInstaller =
        installerForProfile(context.applicationContext.filesDir, profileId)

    @Synchronized
    internal fun clearInstallersForTest() {
        installers.clear()
    }
}

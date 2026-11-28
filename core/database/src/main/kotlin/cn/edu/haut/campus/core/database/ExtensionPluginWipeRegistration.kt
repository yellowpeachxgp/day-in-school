package cn.edu.haut.campus.core.database

import java.io.File
import java.security.MessageDigest

/**
 * Extension plugin erasure registration point.
 *
 * CampusRepository remains the unchanged W0-05 compatibility facade. The plugin
 * owner registers one before-wipe hook here; the hook wipes the independent
 * profile-scoped plugin file partition directly and clears residual legacy Room
 * rows through the database-module seam. No W6-01a method or behavior edit is
 * added to CampusRepository.
 */
object ExtensionPluginWipeRegistration {
    const val HOOK_KEY: String = "extension_plugin_security_wipe"

    /** Profile-scoped v2 package partition derived only from the stable public profile id. */
    fun rootForProfile(filesDir: File, profileId: String): File {
        require(profileId.isNotBlank()) { "账号 Profile 标识不能为空" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(profileId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(filesDir, "extension-plugin-packages/account_" + digest)
    }

    fun register(repository: CampusRepository, pluginStoreRoot: File) {
        repository.registerBeforeWipeHook(HOOK_KEY) {
            ExtensionPluginRuntimeRegistry.installerForRoot(pluginStoreRoot).wipeAll("USER_DATA_DELETE")
            repository.databaseForAdoption().cloudAndParcelDao().clearInstalledExtensionPlugins()
        }
    }
}

package cn.edu.haut.campus.core.database

import kotlinx.coroutines.flow.first

/**
 * Extension plugin erasure registration point.
 *
 * CampusRepository remains the unchanged W0-05 compatibility facade. The plugin
 * owner registers one before-wipe hook here; the hook itself uses only the facade's
 * pre-existing projection/removal API, so plugin erasure composes with account wipe
 * without adding any W6-01a method or behavior edit to CampusRepository.
 */
object ExtensionPluginWipeRegistration {
    const val HOOK_KEY: String = "extension_plugin_security_wipe"

    fun register(repository: CampusRepository) {
        repository.registerBeforeWipeHook(HOOK_KEY) {
            val plugins = repository.installedExtensionPluginsFlow.first()
            for (plugin in plugins) {
                repository.removeExtensionPlugin(plugin.pluginId)
            }
            // Removal of every projected row performs signed-file cleanup; the DAO
            // clear is the fail-closed sweep for residual legacy rows that were not
            // visible when the projection snapshot was taken.
            repository.databaseForAdoption().cloudAndParcelDao().clearInstalledExtensionPlugins()
        }
    }
}

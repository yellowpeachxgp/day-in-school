package cn.edu.haut.campus.core.database

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** AC-23: identity data wipe must erase legacy rows and v2 file partitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ExtensionPluginWipeTest {
    private lateinit var fixture: RepairTestFixture

    @Before
    fun setUp() {
        fixture = RepairTestFixture.create(RuntimeEnvironment.getApplication(), "prof_extension_wipe")
    }

    @After
    fun tearDown() = fixture.close()

    @Test
    fun wipeLeavesPluginInstallationGrantStorageAndCacheAtZero() = runBlocking {
        val db = fixture.database.openHelper.writableDatabase
        db.execSQL(
            "INSERT INTO installed_extension_plugins (" +
                "pluginId, name, activeVersion, previousVersion, schemaFormat, status, grantedPermissionsJson, uiSlotsJson, updatedAtEpochMillis" +
                ") VALUES ('org.example.legacy', 'legacy', '1.0.0', NULL, 'dis-extension/v2', 'ENABLED', '[\"ui.slot.render\"]', '[]', 1)"
        )
        val context = RuntimeEnvironment.getApplication()
        val root = File(
            context.filesDir,
            "extension-plugin-packages/" + fixture.profile.databaseName.removeSuffix(".db")
        )
        val installFile = File(root, "installations/school/plugin.json")
        val storageFile = File(root, "storage/school/plugin/value.bin")
        val cacheFile = File(root, "cache/school/plugin/value.bin")
        listOf(installFile, storageFile, cacheFile).forEach {
            it.parentFile?.mkdirs()
            it.writeBytes(byteArrayOf(1))
        }

        fixture.repository.wipeAllPersonalData()

        db.query("SELECT COUNT(*) FROM installed_extension_plugins").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0L, cursor.getLong(0))
        }
        assertEquals(false, installFile.exists())
        assertEquals(false, storageFile.exists())
        assertEquals(false, cacheFile.exists())
    }
}

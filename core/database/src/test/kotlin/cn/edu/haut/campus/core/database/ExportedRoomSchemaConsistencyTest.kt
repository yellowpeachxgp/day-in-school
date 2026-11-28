package cn.edu.haut.campus.core.database

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 防止手工修补 exported schema：identityHash 必须与 room_master_table setupQueries 写入的哈希一致。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ExportedRoomSchemaConsistencyTest {
    @Test
    fun everyExportedSchemaIdentityHashMatchesMasterTableSetupQuery() {
        val files = exportedSchemaFiles()
        assertTrue("必须发现已导出的 Room schema", files.isNotEmpty())
        assertTrue("回归必须覆盖 v13", files.any { it.name == "13.json" })

        files.forEach { file ->
            val database = JSONObject(file.readText()).getJSONObject("database")
            val expected = database.getString("identityHash")
            val setupQueries = database.getJSONArray("setupQueries")
            val inserts = (0 until setupQueries.length())
                .map { setupQueries.getString(it) }
                .filter { it.contains("room_master_table") && it.contains("INSERT", ignoreCase = true) }
            assertEquals(file.name + " 必须只有一条 room_master_table identity insert", 1, inserts.size)

            val match = IDENTITY_INSERT_REGEX.find(inserts[0])
            assertNotNull(file.name + " 的 identity insert 无法解析", match)
            assertEquals(
                file.name + " 的 database.identityHash 与 setupQueries 不一致",
                expected,
                match?.groupValues?.get(1)
            )
        }
    }

    private fun exportedSchemaFiles(): List<File> {
        val relative = "cn.edu.haut.campus.core.database.HautDatabase"
        val candidates = listOf(
            File("schemas/" + relative),
            File("core/database/schemas/" + relative)
        )
        val direct = candidates.firstOrNull { it.isDirectory }
        val resourceDirectory = direct ?: javaClass.classLoader.getResource(relative)?.let { resource ->
            runCatching { File(resource.toURI()) }.getOrNull()
        }
        return resourceDirectory
            ?.listFiles { file -> file.isFile && file.name.matches(Regex("[0-9]+\\.json")) }
            ?.sortedBy { it.name.removeSuffix(".json").toInt() }
            .orEmpty()
    }

    private companion object {
        val IDENTITY_INSERT_REGEX = Regex(
            "INSERT\\s+OR\\s+REPLACE\\s+INTO\\s+room_master_table\\s*" +
                "\\(id,identity_hash\\)\\s*VALUES\\s*\\(\\s*42\\s*,\\s*'([0-9a-f]{32})'\\s*\\)",
            RegexOption.IGNORE_CASE
        )
    }
}

package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.packages.PackageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * W6-00 runtime-only conformance fixtures.
 *
 * 这些夹具不是 contracts/fixtures/plugins/ 共享契约夹具；CT-01 合并后的
 * 契约面微切片才负责 AC-01…AC-03 的字面契约验收。
 */
class ExtensionManifestV2RuntimeConformanceTest {

    @Test
    fun runtime夹具结论与规范错误码稳定() {
        val files = fixtureDir().listFiles { _, name -> name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: error("runtime fixture directory is empty")
        assertTrue("expected at least 30 runtime fixtures, found " + files.size, files.size >= 30)
        assertTrue(files.any { it.name.startsWith("valid-") })
        assertTrue(files.any { it.name.startsWith("invalid-") })

        files.forEach { file ->
            val text = file.readText(Charsets.UTF_8)
            if (file.name.startsWith("valid-")) {
                val parsed = ExtensionManifestV2Codec.parseOrAdapt(text)
                assertEquals(file.name, "org.example.runtime-fixture", parsed.pluginId)
                return@forEach
            }

            val expectedMatch = Regex("^invalid-(runtime_[a-z_]+)-").find(file.name)
                ?: error("fixture name does not encode a runtime validation code: " + file.name)
            val expectedCode = expectedMatch.groupValues[1]
            try {
                ExtensionManifestV2Codec.parseOrAdapt(text)
                fail(file.name + " must be rejected")
            } catch (e: PackageException) {
                assertEquals(
                    file.name + ": " + e.message,
                    expectedCode,
                    e.runtimeValidationCode
                )
            }
        }
    }

    @Test
    fun 运行时hostApi2槽位表与六个已实现槽位一致() {
        assertEquals(
            setOf(
                "home.cards",
                "campus.services",
                "library.actions",
                "parcel.cards",
                "parcel.providers",
                "settings.entries"
            ),
            ExtensionManifestV2Codec.SUPPORTED_UI_SLOTS
        )
    }

    @Test
    fun 运行时矩阵资源已随主代码打包且不是contracts副本() {
        val resource = javaClass.classLoader.getResource("runtime-host-api-matrix.json")
        assertNotNull(resource)
        val text = resource!!.readText(Charsets.UTF_8)
        assertTrue(text.contains("Runtime-only plugin host API fact source"))
        assertTrue(text.contains("must not be presented as the CT/INF contract shared fixture source"))
        assertTrue(text.contains("home.cards"))
    }

    private fun fixtureDir(): File {
        val resource = javaClass.classLoader.getResource("runtime-plugin-manifest-fixtures")
            ?: error("runtime-plugin-manifest-fixtures test resource is missing")
        return File(resource.toURI())
    }
}

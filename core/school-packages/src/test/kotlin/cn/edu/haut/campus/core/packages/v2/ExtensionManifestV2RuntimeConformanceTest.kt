package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.packages.PackageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * W6 shared contract conformance fixtures.
 *
 * contracts/fixtures/plugins/ 是云端与 Android 共同消费的权威夹具树；
 * 非 contracts 的 W6-00 运行时镜像已迁移，不再作为 AC-02 事实源。
 */
class ExtensionManifestV2RuntimeConformanceTest {

    @Test
    fun 契约夹具结论与规范错误码稳定() {
        val files = fixtureFiles()
        assertEquals("expected exactly 58 shared contract fixtures, found " + files.size, 58, files.size)
        assertTrue(files.any { it.group == "valid" })
        assertTrue(files.any { it.group == "invalid" })

        files.forEach { fixture ->
            val file = fixture.file
            assertEquals(
                file.name + " must be in the directory matching its name",
                if (file.name.startsWith("valid-")) "valid" else "invalid",
                fixture.group
            )
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
    fun parity缺口legacy枚举ECMA空白整数上限与严格词法JSON全部对齐() {
        val legacy = ExtensionManifestV2Codec.parseOrAdapt(fixtureText("valid-legacy-schema-version.json"))
        assertEquals("dis-campus-plugin/v1", legacy.legacySchemaVersion)

        val padded = ExtensionManifestV2Codec.parseOrAdapt(fixtureText("valid-display-strings-padded-nonblank.json"))
        assertEquals("  Runtime Fixture Plugin  ", padded.name)
        assertEquals("  Runtime Fixture Card  ", padded.uiSlots[0].title)

        val controlWhitespaceName = ExtensionManifestV2Codec.parseOrAdapt(fixtureText("valid-ecma-whitespace-u001e-name.json"))
        assertEquals("", controlWhitespaceName.name)
        val hostBoundary = ExtensionManifestV2Codec.parseOrAdapt(fixtureText("valid-host-api-int32-max.json"))
        assertEquals(Int.MAX_VALUE, hostBoundary.maxHostApi)
        val sizeBoundary = ExtensionManifestV2Codec.parseOrAdapt(fixtureText("valid-size-bytes-int32-max.json"))
        assertEquals(Int.MAX_VALUE.toLong(), sizeBoundary.entryArtifacts[0].sizeBytes)

        listOf(
            "invalid-runtime_schema_invalid-invalid-legacy-schema-version.json",
            "invalid-runtime_schema_invalid-blank-name.json",
            "invalid-runtime_schema_invalid-blank-ui-slot-title.json",
            "invalid-runtime_schema_invalid-decimal-integer-min-host-api.json",
            "invalid-runtime_schema_invalid-ecma-whitespace-feff-name.json",
            "invalid-runtime_schema_invalid-host-api-int32-overflow.json",
            "invalid-runtime_schema_invalid-size-bytes-int32-overflow.json",
            "invalid-runtime_schema_invalid-duplicate-top-level-key.json",
            "invalid-runtime_schema_invalid-overlong-number-literal.json"
        ).forEach { fileName ->
            try {
                ExtensionManifestV2Codec.parseOrAdapt(fixtureText(fileName))
                fail(fileName + " must be rejected")
            } catch (e: PackageException) {
                assertEquals(fileName + ": " + e.message, "runtime_schema_invalid", e.runtimeValidationCode)
            }
        }
    }

    @Test
    fun 运行时hostApi2槽位表与契约权威六个槽位一致() {
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
        assertEquals(contractHostApi2Slots().toSet(), ExtensionManifestV2Codec.SUPPORTED_UI_SLOTS)
    }

    @Test
    fun 运行时矩阵资源已随主代码打包且与contract矩阵同源对齐() {
        val resource = javaClass.classLoader.getResource("runtime-host-api-matrix.json")
        assertNotNull(resource)
        val text = resource!!.readText(Charsets.UTF_8)
        assertTrue(text.contains("home.cards"))
        assertTrue(text.contains("campus.services"))
        assertTrue(text.contains("settings.entries"))
    }

    private fun fixtureText(fileName: String): String =
        fixtureFiles().firstOrNull { it.file.name == fileName }?.file?.readText(Charsets.UTF_8)
            ?: error("shared contract fixture is missing: " + fileName)

    private data class ContractFixture(val group: String, val file: File)

    private fun fixtureFiles(): List<ContractFixture> {
        val root = contractFixtureRoot()
        return listOf("valid", "invalid").flatMap { group ->
            val directory = File(root, group)
            directory.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".json") }
                .sortedBy { it.name }
                .map { ContractFixture(group, it) }
        }.sortedBy { it.file.name }
    }

    private fun repoRoot(): File {
        val configured = System.getProperty("campus.repo.root")
            ?: error("campus.repo.root test system property is missing")
        val root = File(configured)
        require(File(root, "settings.gradle.kts").isFile) {
            "campus.repo.root does not identify the Android repository: " + root.absolutePath
        }
        return root
    }

    private fun contractFixtureRoot(): File {
        val root = File(repoRoot(), "contracts/fixtures/plugins")
        require(root.isDirectory) { "contracts/fixtures/plugins is missing: " + root.absolutePath }
        return root
    }

    private fun contractHostApi2Slots(): List<String> {
        val matrixText = File(repoRoot(), "contracts/compatibility/host-api-matrix.yaml").readText(Charsets.UTF_8)
        val host2 = matrixText.split("  - host_api: 2
").getOrNull(1)
            ?: error("contract host-api matrix is missing host_api 2")
        val slotSection = host2.split("    supported_ui_slots:
").getOrNull(1).orEmpty()
        return slotSection.lines()
            .filter { it.startsWith("      - ") }
            .map { it.removePrefix("      - ").trim() }
    }
}

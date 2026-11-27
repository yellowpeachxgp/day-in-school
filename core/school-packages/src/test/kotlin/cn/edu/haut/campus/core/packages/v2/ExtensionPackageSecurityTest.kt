package cn.edu.haut.campus.core.packages.v2

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.PackageError
import cn.edu.haut.campus.core.packages.PackageException
import cn.edu.haut.campus.core.packages.crypto.Ed25519
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** AC-21/AC-22 fail-closed v2 signed package tests; all fixtures are synthetic. */
class ExtensionPackageSecurityTest {

    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val registry = PublisherKeyRegistry(mapOf("test-publisher" to Ed25519.toRaw(keyPair.public)))

    @Test
    fun signedV2PackageInstallsAndCommandTypeParityFieldsSurvive() {
        val artifact = "declarative data".toByteArray()
        val manifest = signedManifest(artifacts = mapOf("dist/data.txt" to artifact))
        val verified = ExtensionPackageVerifier.verify(
            manifest.toByteArray(),
            mapOf("dist/data.txt" to artifact),
            registry
        )
        assertEquals("org.example.signed", verified.manifest.pluginId)
        assertEquals("show_data", verified.manifest.commands.single().type)
        assertEquals("dist/data.txt", verified.manifest.commands.single().artifactPath)
    }

    @Test
    fun cloudSignedReferencePluginVerifiesWithAndroidTrustRoot() {
        val manifestBytes = readResource("/reference-v2-plugin/manifest.json")
        val artifactBytes = readResource("/reference-v2-plugin/index.mjs")
        val verified = ExtensionPackageVerifier.verify(
            manifestBytes,
            mapOf("dist/index.mjs" to artifactBytes),
            ExtensionPublisherTrustRoots.officialExamplesRegistry()
        )
        assertEquals("org.example.hello-campus", verified.manifest.pluginId)
        assertEquals("dis-official-examples-2026", verified.publisherId)
    }

    @Test
    fun ac21RejectsTamperedManifestArtifactUnknownPublisherZipSlipHttpOriginAndHostApi() {
        val artifact = "declarative data".toByteArray()
        val valid = signedManifest(artifacts = mapOf("dist/data.txt" to artifact))
        val tamperedManifest = valid.replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.1\"")
        assertPackageError(PackageError.SIGNATURE_INVALID) {
            ExtensionPackageVerifier.verify(tamperedManifest.toByteArray(), mapOf("dist/data.txt" to artifact), registry)
        }

        assertPackageError(PackageError.FILE_HASH_MISMATCH) {
            ExtensionPackageVerifier.verify(valid.toByteArray(), mapOf("dist/data.txt" to "tampered".toByteArray()), registry)
        }

        assertPackageError(PackageError.FILE_UNLISTED) {
            ExtensionPackageVerifier.verify(
                valid.toByteArray(),
                mapOf(
                    "dist/data.txt" to artifact,
                    "dist/undeclared.txt" to "not covered by the signed manifest".toByteArray()
                ),
                registry
            )
        }

        val otherKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val unknownRegistry = PublisherKeyRegistry(mapOf("other-publisher" to Ed25519.toRaw(otherKey.public)))
        assertPackageError(PackageError.SIGNATURE_UNTRUSTED_PUBLISHER) {
            ExtensionPackageVerifier.verify(valid.toByteArray(), mapOf("dist/data.txt" to artifact), unknownRegistry)
        }

        assertPackageError(PackageError.UNSAFE_PATH) {
            ExtensionZipPackageReader.read(zipOf(mapOf("../evil" to ByteArray(1))))
        }

        val httpManifest = signedManifest(
            artifacts = mapOf("dist/data.txt" to artifact),
            allowedOrigins = listOf("http://insecure.example.com")
        )
        assertPackageError(PackageError.MANIFEST_INVALID) {
            ExtensionPackageVerifier.verify(httpManifest.toByteArray(), mapOf("dist/data.txt" to artifact), registry)
        }

        val futureHostManifest = signedManifest(
            artifacts = mapOf("dist/data.txt" to artifact),
            minHostApi = 3,
            maxHostApi = 3
        )
        assertPackageError(PackageError.HOST_API_TOO_OLD) {
            ExtensionPackageVerifier.verify(futureHostManifest.toByteArray(), mapOf("dist/data.txt" to artifact), registry)
        }

        val root = Files.createTempDirectory("w601a-ac21")
        try {
            val installer = ExtensionPluginInstaller(ExtensionPluginFileStore(root), registry)
            for ((label, block) in listOf(
                "tampered-manifest" to { _: Unit -> ExtensionPackageVerifier.verify(tamperedManifest.toByteArray(), mapOf("dist/data.txt" to artifact), registry) },
                "tampered-artifact" to { _: Unit -> ExtensionPackageVerifier.verify(valid.toByteArray(), mapOf("dist/data.txt" to "tampered".toByteArray()), registry) }
            )) {
                runCatching { block(Unit) }
                assertEquals(label, 0, installer.snapshot().size)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun ac22MidWriteFailureLeavesOldVersionActiveAndNoStagedHalfProduct() {
        val root = Files.createTempDirectory("w601a-atomic")
        try {
            var failNextInstall = false
            val store = ExtensionPluginFileStore(root) { _, writeIndex ->
                if (failNextInstall && writeIndex == 1) throw java.io.IOException("injected write failure")
            }
            val installer = ExtensionPluginInstaller(store, registry)
            val v1Artifact = "v1".toByteArray()
            val v2Artifact = "v2".toByteArray()
            val v1 = signedManifest(version = "1.0.0", artifacts = mapOf("dist/data.txt" to v1Artifact))
            val v2 = signedManifest(version = "1.1.0", artifacts = mapOf("dist/data.txt" to v2Artifact))
            installer.install(v1.toByteArray(), mapOf("dist/data.txt" to v1Artifact), "schoolx")
            assertEquals("1.0.0", installer.installation("schoolx", "org.example.signed")?.activeVersion)

            failNextInstall = true
            assertPackageError(PackageError.STAGING_FAILED) {
                installer.install(v2.toByteArray(), mapOf("dist/data.txt" to v2Artifact), "schoolx")
            }
            val installation = installer.installation("schoolx", "org.example.signed")
            assertNotNull(installation)
            assertEquals("1.0.0", installation?.activeVersion)
            assertEquals(0, Files.list(root.resolve("staging")).use { it.count() })
            assertTrue(Files.list(root.resolve("versions")).use { it.count() } >= 1)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun upgradeConfirmationRollbackShrinkAndIndividualRevocationAreFailClosed() {
        val root = Files.createTempDirectory("w601a-lifecycle")
        try {
            val installer = ExtensionPluginInstaller(ExtensionPluginFileStore(root), registry)
            val artifact = "same".toByteArray()
            val v1 = signedManifest(version = "1.0.0", permissions = listOf("ui.slot.render"), artifacts = mapOf("dist/data.txt" to artifact))
            val v2 = signedManifest(version = "1.1.0", permissions = listOf("ui.slot.render", "network.request"), artifacts = mapOf("dist/data.txt" to artifact))
            installer.install(v1.toByteArray(), mapOf("dist/data.txt" to artifact), "schoolx")
            installer.install(v2.toByteArray(), mapOf("dist/data.txt" to artifact), "schoolx")
            var inst = installer.installation("schoolx", "org.example.signed")!!
            assertEquals("PENDING_PERMISSION_REVIEW", inst.status)
            assertEquals("1.0.0", inst.activeVersion)
            assertEquals(listOf("ui.slot.render"), inst.grantedPermissions)

            installer.confirmPendingPermissions("schoolx", "org.example.signed")
            inst = installer.installation("schoolx", "org.example.signed")!!
            assertEquals("1.1.0", inst.activeVersion)
            assertEquals(setOf("ui.slot.render", "network.request"), inst.grantedPermissions.toSet())

            installer.revokePermission("schoolx", "org.example.signed", "network.request")
            inst = installer.installation("schoolx", "org.example.signed")!!
            assertEquals(listOf("ui.slot.render"), inst.grantedPermissions)

            installer.rollback("schoolx", "org.example.signed")
            inst = installer.installation("schoolx", "org.example.signed")!!
            assertEquals("1.0.0", inst.activeVersion)
            assertEquals(listOf("ui.slot.render"), inst.grantedPermissions)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rollbackReverifiesStoredSignatureAndArtifactIntegrityFailClosed() {
        val root = Files.createTempDirectory("w601a-rollback-reverify")
        try {
            val installer = ExtensionPluginInstaller(ExtensionPluginFileStore(root), registry)
            val v1Artifact = "v1".toByteArray()
            val v2Artifact = "v2".toByteArray()
            val v1 = signedManifest(version = "1.0.0", artifacts = mapOf("dist/data.txt" to v1Artifact))
            val v2 = signedManifest(version = "1.1.0", artifacts = mapOf("dist/data.txt" to v2Artifact))
            installer.install(v1.toByteArray(), mapOf("dist/data.txt" to v1Artifact), "schoolx")
            installer.install(v2.toByteArray(), mapOf("dist/data.txt" to v2Artifact), "schoolx")
            assertEquals("1.1.0", installer.installation("schoolx", "org.example.signed")?.activeVersion)

            fun encoded(value: String): String =
                Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
            val v1Dir = root.resolve("versions")
                .resolve(encoded("org.example.signed"))
                .resolve(encoded("1.0.0"))
            val artifactFile = v1Dir.resolve("artifacts").resolve("dist").resolve("data.txt")
            val originalArtifact = Files.readAllBytes(artifactFile)
            Files.write(artifactFile, "xx".toByteArray())
            assertPackageError(PackageError.FILE_HASH_MISMATCH) {
                installer.rollback("schoolx", "org.example.signed", "1.0.0")
            }
            assertEquals("1.1.0", installer.installation("schoolx", "org.example.signed")?.activeVersion)

            Files.write(artifactFile, originalArtifact)
            val manifestFile = v1Dir.resolve("manifest.json")
            val originalManifest = Files.readAllBytes(manifestFile)
            val tamperedManifest = String(originalManifest, Charsets.UTF_8)
                .replace("Signed Fixture", "Signed FixturX")
                .toByteArray(Charsets.UTF_8)
            Files.write(manifestFile, tamperedManifest)
            assertPackageError(PackageError.SIGNATURE_INVALID) {
                installer.rollback("schoolx", "org.example.signed", "1.0.0")
            }
            assertEquals("1.1.0", installer.installation("schoolx", "org.example.signed")?.activeVersion)

            Files.write(manifestFile, originalManifest)
            val rollback = installer.rollback("schoolx", "org.example.signed", "1.0.0")
            assertEquals("1.0.0", rollback.activeVersion)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun uninstallAndWipeRemoveInstallationAuthorizationStorageCacheAndWriteAudit() {
        val root = Files.createTempDirectory("w601a-cleanup")
        try {
            val installer = ExtensionPluginInstaller(ExtensionPluginFileStore(root), registry)
            val artifact = "data".toByteArray()
            val manifest = signedManifest(artifacts = mapOf("dist/data.txt" to artifact))
            installer.install(manifest.toByteArray(), mapOf("dist/data.txt" to artifact), "schoolx")
            installer.putScopedStorageForTest("schoolx", "org.example.signed", "a", ByteArray(1), cache = false)
            installer.putScopedStorageForTest("schoolx", "org.example.signed", "b", ByteArray(1), cache = true)
            assertEquals(1 to 1, installer.scopedStorageCounts("schoolx", "org.example.signed"))

            val cleanup = installer.uninstall("schoolx", "org.example.signed", "test uninstall")
            assertEquals(1, cleanup.installationFiles)
            assertEquals(1, cleanup.storageFiles)
            assertEquals(1, cleanup.cacheFiles)
            assertEquals(null, installer.installation("schoolx", "org.example.signed"))
            assertEquals(0 to 0, installer.scopedStorageCounts("schoolx", "org.example.signed"))
            assertTrue(installer.auditLines().any { it.contains("UNINSTALL_PLUGIN") })

            installer.install(manifest.toByteArray(), mapOf("dist/data.txt" to artifact), "schoolx")
            installer.putScopedStorageForTest("schoolx", "org.example.signed", "a", ByteArray(1), cache = false)
            val wipe = installer.wipeAll("identity data wipe")
            assertTrue(wipe.installationFiles >= 1)
            assertEquals(emptyList<ExtensionInstallation>(), installer.snapshot())
            assertEquals(0 to 0, installer.scopedStorageCounts("schoolx", "org.example.signed"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun signedManifest(
        pluginId: String = "org.example.signed",
        version: String = "1.0.0",
        permissions: List<String> = listOf("ui.slot.render"),
        allowedOrigins: List<String> = listOf("https://plugin.example.com"),
        artifacts: Map<String, ByteArray>,
        minHostApi: Int = 2,
        maxHostApi: Int = 2
    ): String {
        val artifactRows = artifacts.entries.sortedBy { it.key }.map { (path, bytes) ->
            JsonValue.Obj(mapOf(
                "path" to JsonValue.Str(path),
                "sha256" to JsonValue.Str(Digests.sha256Hex(bytes)),
                "size_bytes" to JsonValue.Num(bytes.size.toString())
            ))
        }
        val fields = linkedMapOf<String, JsonValue>(
            "schema_version" to JsonValue.Str("dis-extension/v2"),
            "plugin_id" to JsonValue.Str(pluginId),
            "name" to JsonValue.Str("Signed Fixture"),
            "version" to JsonValue.Str(version),
            "min_host_api" to JsonValue.Num(minHostApi.toString()),
            "max_host_api" to JsonValue.Num(maxHostApi.toString()),
            "permissions" to JsonValue.Arr(permissions.map { JsonValue.Str(it) }),
            "allowed_origins" to JsonValue.Arr(allowedOrigins.map { JsonValue.Str(it) }),
            "ui_slots" to JsonValue.Arr(emptyList()),
            "commands" to JsonValue.Arr(listOf(JsonValue.Obj(mapOf(
                "name" to JsonValue.Str("show"),
                "description" to JsonValue.Str("show signed data"),
                "required_permission" to JsonValue.Str("ui.slot.render"),
                "type" to JsonValue.Str("show_data"),
                "artifact_path" to JsonValue.Str(artifacts.keys.sorted().first())
            )))),
            "entry_artifacts" to JsonValue.Arr(artifactRows)
        )
        val unsignedCanonical = Json.canonical(JsonValue.Obj(fields))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(unsignedCanonical.toByteArray())
        val signature = signer.sign()
        fields["signature"] = JsonValue.Obj(mapOf(
            "algorithm" to JsonValue.Str("Ed25519"),
            "key_id" to JsonValue.Str("test-publisher"),
            "value_base64" to JsonValue.Str(java.util.Base64.getEncoder().encodeToString(signature))
        ))
        return Json.canonical(JsonValue.Obj(fields))
    }

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun readResource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(name)) { "missing test resource: " + name }.use { it.readBytes() }

    private fun assertPackageError(expected: PackageError, block: () -> Unit) {
        try {
            block()
            fail("expected " + expected)
        } catch (error: PackageException) {
            assertEquals(expected, error.error)
        }
    }
}

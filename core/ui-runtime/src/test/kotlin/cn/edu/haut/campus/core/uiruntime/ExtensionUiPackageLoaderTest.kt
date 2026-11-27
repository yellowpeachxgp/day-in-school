package cn.edu.haut.campus.core.uiruntime

import cn.edu.haut.campus.core.model.json.Digests
import cn.edu.haut.campus.core.model.json.Json
import cn.edu.haut.campus.core.model.json.JsonValue
import cn.edu.haut.campus.core.packages.crypto.PublisherKeyRegistry
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginFileStore
import cn.edu.haut.campus.core.packages.v2.ExtensionPluginInstaller
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loader integration tests use signed synthetic packages only; no executable plugin artifact is ever loaded. */
class ExtensionUiPackageLoaderTest {
    @Test
    fun readyContributionUsesVerifiedExplicitFeatureUiArtifact() = withInstalledPlugin(cardJson = validCardJson()) { installer, loader ->
        val contributions = loader.loadContributions()

        assertEquals(1, contributions.size)
        val contribution = contributions.single()
        assertEquals(ExtensionContributionStatus.READY, contribution.status)
        assertEquals("home.cards", contribution.slotId)
        assertEquals("demo-card", contribution.card?.componentId)
        assertTrue(contribution.card?.nodes.orEmpty().any { it is FeatureUiNode.MetricNode })
        assertTrue(installer.verifiedStoredPackage("schoolx", PLUGIN_ID).artifactMap.containsKey("cards/demo-card.json"))
    }

    @Test
    fun disabledPluginRendersDisabledPlaceholderWithoutExecutingAnything() = withInstalledPlugin(cardJson = validCardJson()) { installer, loader ->
        installer.setEnabled("schoolx", PLUGIN_ID, false)

        val contribution = loader.loadContributions().single()

        assertEquals(ExtensionContributionStatus.DISABLED, contribution.status)
        assertTrue(contribution.card?.nodes.orEmpty().single() is FeatureUiNode.ErrorStateNode)
    }

    @Test
    fun revokedUiRenderPermissionBlocksSlotContribution() = withInstalledPlugin(cardJson = validCardJson()) { installer, loader ->
        installer.revokePermission("schoolx", PLUGIN_ID, "ui.slot.render")

        val contribution = loader.loadContributions().single()

        assertEquals(ExtensionContributionStatus.PERMISSION_DENIED, contribution.status)
    }

    @Test
    fun invalidExplicitCardModelDegradesToErrorContribution() = withInstalledPlugin(cardJson = "{}") { _, loader ->
        val contribution = loader.loadContributions().single()

        assertEquals(ExtensionContributionStatus.ERROR, contribution.status)
        assertTrue(contribution.errorMessage.orEmpty().contains("卡片模型"))
    }

    @Test
    fun missingCardArtifactFallsBackToSignedManifestDeclaration() = withInstalledPlugin(cardJson = null) { _, loader ->
        val contribution = loader.loadContributions().single()

        assertEquals(ExtensionContributionStatus.READY, contribution.status)
        assertEquals("合成服务", contribution.card?.title)
        assertTrue(contribution.card?.nodes.orEmpty().any { it is FeatureUiNode.ActionButtonNode })
    }

    private fun withInstalledPlugin(
        cardJson: String?,
        block: (ExtensionPluginInstaller, ExtensionUiPackageLoader) -> Unit
    ) {
        val root = Files.createTempDirectory("ui-runtime-loader-test")
        return try {
            val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val registry = PublisherKeyRegistry(mapOf("test-publisher" to rawPublic(keyPair)))
            val installer = ExtensionPluginInstaller(ExtensionPluginFileStore(root), registry)
            val artifacts = linkedMapOf<String, ByteArray>()
            if (cardJson != null) artifacts["cards/demo-card.json"] = cardJson.toByteArray(Charsets.UTF_8)
            artifacts["assets/data.json"] = "{"items":[]}".toByteArray(Charsets.UTF_8)
            val manifest = signedManifest(keyPair, artifacts)
            installer.install(manifest.toByteArray(Charsets.UTF_8), artifacts, "schoolx")
            block(installer, ExtensionUiPackageLoader(installer, "schoolx"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun validCardJson(): String = """
        {
          "slot": "home.cards",
          "component_id": "demo-card",
          "template": "metric-card/v1",
          "title": "合成服务",
          "nodes": [
            {"kind":"Metric","label":"排队","value":"3 人"},
            {"kind":"ActionButton","label":"查看","command":"show"}
          ]
        }
    """.trimIndent()

    private fun signedManifest(keyPair: KeyPair, artifacts: Map<String, ByteArray>): String {
        val artifactRows = artifacts.entries.sortedBy { it.key }.map { (path, bytes) ->
            JsonValue.Obj(mapOf(
                "path" to JsonValue.Str(path),
                "sha256" to JsonValue.Str(Digests.sha256Hex(bytes)),
                "size_bytes" to JsonValue.Num(bytes.size.toString())
            ))
        }
        val fields = linkedMapOf<String, JsonValue>(
            "schema_version" to JsonValue.Str("dis-extension/v2"),
            "plugin_id" to JsonValue.Str(PLUGIN_ID),
            "name" to JsonValue.Str("Signed UI Fixture"),
            "version" to JsonValue.Str("1.0.0"),
            "min_host_api" to JsonValue.Num("2"),
            "max_host_api" to JsonValue.Num("2"),
            "permissions" to JsonValue.Arr(listOf(JsonValue.Str("ui.slot.render"))),
            "allowed_origins" to JsonValue.Arr(emptyList()),
            "ui_slots" to JsonValue.Arr(listOf(JsonValue.Obj(mapOf(
                "slot_id" to JsonValue.Str("home.cards"),
                "card_id" to JsonValue.Str("demo.card"),
                "title" to JsonValue.Str("合成服务"),
                "subtitle" to JsonValue.Str("W111 示例窗口"),
                "action_command" to JsonValue.Str("show")
            )))),
            "commands" to JsonValue.Arr(listOf(JsonValue.Obj(mapOf(
                "name" to JsonValue.Str("show"),
                "description" to JsonValue.Str("show synthetic data"),
                "required_permission" to JsonValue.Str("ui.slot.render"),
                "type" to JsonValue.Str("show_data"),
                "artifact_path" to JsonValue.Str("assets/data.json")
            )))),
            "entry_artifacts" to JsonValue.Arr(artifactRows)
        )
        val unsignedCanonical = Json.canonical(JsonValue.Obj(fields))
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keyPair.private)
        signer.update(unsignedCanonical.toByteArray(Charsets.UTF_8))
        fields["signature"] = JsonValue.Obj(mapOf(
            "algorithm" to JsonValue.Str("Ed25519"),
            "key_id" to JsonValue.Str("test-publisher"),
            "value_base64" to JsonValue.Str(Base64.getEncoder().encodeToString(signer.sign()))
        ))
        return Json.canonical(JsonValue.Obj(fields))
    }

    private fun rawPublic(keyPair: KeyPair): ByteArray {
        val encoded = keyPair.public.encoded
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    private companion object {
        const val PLUGIN_ID = "org.example.ui-fixture"
    }
}

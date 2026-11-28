package cn.edu.haut.campus.feature.settings.ai

import cn.edu.haut.campus.core.ai.AiConsentToken
import cn.edu.haut.campus.core.ai.AiEndpoint
import cn.edu.haut.campus.core.ai.AiKeyStatus
import cn.edu.haut.campus.core.ai.AiProviderConfiguration
import cn.edu.haut.campus.core.ai.AiRequest
import cn.edu.haut.campus.core.ai.AiResult
import cn.edu.haut.campus.core.ai.AiSendPreview
import cn.edu.haut.campus.core.ai.HttpsUrl
import cn.edu.haut.campus.core.database.AccountSessionState
import cn.edu.haut.campus.feature.settings.ai.preview.AiServicePreviewFixtures
import cn.edu.haut.campus.feature.settings.ai.state.AiConnectivityStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AiServiceViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun saveConfigurationCopiesThenWipesCallerKeyAndNeverStoresItInState() = runTest {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        val key = "viewmodel-key-canary".toCharArray()

        viewModel.saveConfiguration("https://provider.example/v1", "chat-vision-test", true, key)

        assertArrayEquals(CharArray(key.size), key)
        advanceUntilIdle()
        assertEquals("viewmodel-key-canary", gateway.savedKeyCopy)
        assertEquals(listOf("save"), gateway.events)
        assertTrue(viewModel.uiState.value.toString().contains("viewmodel-key-canary").not())
    }

    @Test
    fun connectivityRequiresPreviewBeforeIssueAndSend() = runTest {
        val gateway = FakeGateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.startConnectivityTest()
        advanceUntilIdle()

        assertEquals(listOf("preview"), gateway.events)
        assertEquals(AiConnectivityStage.PREVIEW_READY, viewModel.uiState.value.connectivity.stage)

        viewModel.confirmConnectivitySend()
        advanceUntilIdle()

        assertEquals(listOf("preview", "issue", "send"), gateway.events)
        assertEquals(AiConnectivityStage.SUCCESS, viewModel.uiState.value.connectivity.stage)
    }

    private fun viewModel(gateway: FakeGateway): AiServiceViewModel = AiServiceViewModel(
        gateway = gateway,
        profileId = "profile-test",
        sessionGeneration = 1L,
        isSessionActive = { true },
        sessionStates = MutableStateFlow<AccountSessionState>(AccountSessionState.Loading)
    )

    private class FakeGateway : AiServiceGateway {
        val endpoint = AiEndpoint(HttpsUrl("https://provider.example/v1"), "chat-vision-test", supportsVision = true)
        override val configuration = MutableStateFlow(
            AiProviderConfiguration(endpoint = endpoint, keyStatus = AiKeyStatus.CONFIGURED, enabled = true)
        )
        val events = mutableListOf<String>()
        var savedKeyCopy: String? = null

        override suspend fun saveEndpointAndKey(endpoint: AiEndpoint, key: CharArray) {
            events += "save"
            savedKeyCopy = String(key.copyOf())
        }

        override suspend fun setEnabled(enabled: Boolean) {
            events += "enabled:" + enabled
        }

        override suspend fun clear() {
            events += "clear"
        }

        override suspend fun preview(request: AiRequest): AiSendPreview {
            events += "preview"
            return AiServicePreviewFixtures.connectivityPreview
        }

        override fun issueConsent(endpoint: AiEndpoint, preview: AiSendPreview): AiConsentToken {
            events += "issue"
            return AiConsentToken(
                tokenId = "consent-" + "2".repeat(64),
                scopeFingerprint = "2".repeat(64),
                requestFingerprint = "2".repeat(64),
                expiresAt = Instant.now().plusSeconds(30)
            )
        }

        override suspend fun send(request: AiRequest, consent: AiConsentToken): AiResult {
            events += "send"
            return AiResult.Success(text = "ok", model = endpoint.model, finishReason = "stop", usage = null)
        }
    }
}

package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.agent.auth.MemoryProviderCredentialStorage
import io.github.mangi.eta.agent.auth.FakeProviderOAuthClient
import io.github.mangi.eta.agent.auth.ProviderCredential
import io.github.mangi.eta.agent.auth.ProviderCredentialSessions
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProviderRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        assertTrue(ProviderRepository.context() === context.applicationContext)
        runBlocking {
            SettingsDataStore.setSelection(providerId = null, modelId = null)
        }
    }

    @Test
    fun startupRemovesLegacyProviderAndItsCredentialsWithoutAffectingOthers() = runBlocking {
        val legacy = sampleProvider(id = "retired").copy(
            baseUrl = "https://daily-cloudcode-pa.googleapis.com",
            endpointMode = "antigravity",
            authMode = "oauth_antigravity",
        )
        val dao = EtaDatabase.get(context).providerDao()
        dao.replaceProvider(legacy.toEntity(), legacy.toModelEntities())
        val normal = ProviderRepository.addProvider(sampleProvider(id = "normal"))
        // 凭据已换成加密存储（旧 ProviderOAuthStore 的明文 preferences 已删除）；
        // 真实加密存储在 AndroidKeyStore 里，Robolectric 没有，故注入内存实现。
        val storage = MemoryProviderCredentialStorage().apply {
            write("retired", credential(ProviderAuthModes.OPENAI_CHATGPT, "test-retired"))
            write(normal.id, credential(ProviderAuthModes.OPENAI_CHATGPT, "test-codex"))
        }
        ProviderCredentialRepository.replaceSessionsForTests(
            ProviderCredentialSessions(storage, FakeProviderOAuthClient()),
        )
        try {
            SettingsDataStore.setSelection(legacy.id, legacy.models.first().id)
            assertTrue(ProviderRepository.providerById(legacy.id) == null)

            ProviderRepository.ensureBuiltInsMerged()
            ProviderRepository.ensureBuiltInsMerged() // idempotent; also used after backup import

            assertTrue(dao.providerById(legacy.id) == null)
            assertTrue(dao.models(legacy.id).isEmpty())
            // 被移除的接入的凭据跟着清掉；正常提供商的凭据必须留着。
            assertNull(storage.read("retired"))
            assertEquals("test-codex", storage.read(normal.id)?.accessToken)
            assertEquals(normal.id, SettingsDataStore.settings().selectedProviderId)
            assertTrue(ProviderRepository.providerById(normal.id) != null)
        } finally {
            ProviderCredentialRepository.replaceSessionsForTests(null)
        }
    }

    private fun credential(authMode: String, accessToken: String) = ProviderCredential(
        authMode = authMode,
        clientId = "test-client",
        subject = "test-subject",
        accessToken = accessToken,
    )

    @Test
    fun builtInProvidersAreNotSeeded() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()

        val providers = ProviderRepository.allProviders()
        assertTrue(providers.none { it.isBuiltIn })
        assertTrue(providers.none { it.id.startsWith("builtin-") })
    }

    @Test
    fun providerAndModelCustomHeadersSurviveRoomRoundTrip() = runBlocking {
        val provider = ProviderRepository.addProvider(sampleProvider())
        val updated = provider.copyForTest(
            customHeaders = listOf(CustomHeader("x-provider", "1")),
        ).let { openAi ->
            openAi.copy(
                models = openAi.models.mapIndexed { index, model ->
                    if (index == 0) {
                        model.copy(customHeaders = listOf(CustomHeader("x-model", "2")))
                    } else {
                        model
                    }
                }
            )
        }

        ProviderRepository.updateProvider(updated)
        ModelRepository.saveModel(
            provider.id,
            updated.models.first(),
        )

        val restored = ProviderRepository.providerById(provider.id)!!
        assertEquals(listOf("x-provider"), restored.customHeaders.map { it.name })
        assertEquals(
            listOf("x-model"),
            restored.models.first { it.modelId == "model-1" }.customHeaders.map { it.name },
        )
    }

    @Test
    fun selectedRuntimeConfigUsesUpdatedProviderApiKey() = runBlocking {
        val provider = (ProviderRepository.addProvider(sampleProvider()) as OpenAiCompatibleProviderSetting)
            .copy(apiKey = "sk-test-key")

        ProviderRepository.updateProvider(provider)
        RuntimeConfigRepository.setSelectedProviderId(provider.id)

        val config = RuntimeConfigRepository.currentRuntimeConfig()
        requireNotNull(config)
        assertEquals(provider.id, config.providerId)
        assertEquals("sk-test-key", config.apiKey)
    }

    @Test
    fun switchingProvidersRestoresEachProvidersSelectedModel() = runBlocking {
        val openAi = ProviderRepository.addProvider(sampleProvider(id = "custom-a", name = "A"))
        val anthropic = ProviderRepository.addProvider(sampleProvider(id = "custom-b", name = "B"))
        val openAiModel = openAi.models[1]
        val anthropicModel = anthropic.models[1]
        SettingsDataStore.clearSelectedModelIdForProvider(openAi.id)
        SettingsDataStore.clearSelectedModelIdForProvider(anthropic.id)

        RuntimeConfigRepository.setSelectedProviderId(openAi.id)
        RuntimeConfigRepository.setSelectedModelId(openAiModel.id)
        RuntimeConfigRepository.setSelectedProviderId(anthropic.id)
        RuntimeConfigRepository.setSelectedModelId(anthropicModel.id)

        RuntimeConfigRepository.setSelectedProviderId(openAi.id)
        assertEquals(openAiModel.id, SettingsDataStore.settings().selectedModelId)

        RuntimeConfigRepository.setSelectedProviderId(anthropic.id)
        assertEquals(anthropicModel.id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun repairSelectionMigratesLegacyActiveModelToProviderMemory() = runBlocking {
        val provider = ProviderRepository.addProvider(sampleProvider())
        val model = provider.models[1]
        SettingsDataStore.clearSelectedModelIdForProvider(provider.id)
        SettingsDataStore.updateSettings {
            it.copy(selectedProviderId = provider.id, selectedModelId = model.id)
        }

        ProviderRepository.repairSelection()

        assertEquals(model.id, SettingsDataStore.selectedModelIdForProvider(provider.id))
    }
}


private fun sampleProvider(
    id: String = "custom-provider",
    name: String = "Custom",
): OpenAiCompatibleProviderSetting =
    OpenAiCompatibleProviderSetting(
        id = id,
        name = name,
        baseUrl = "https://api.example.com/v1",
        models = listOf(
            io.github.mangi.eta.data.model.Model(
                id = "$id-m1",
                modelId = "model-1",
                displayName = "Model 1",
                sortOrder = 0,
            ),
            io.github.mangi.eta.data.model.Model(
                id = "$id-m2",
                modelId = "model-2",
                displayName = "Model 2",
                sortOrder = 1,
            ),
        ),
    )

private fun ProviderSetting.copyForTest(
    customHeaders: List<CustomHeader>,
): OpenAiCompatibleProviderSetting =
    (this as OpenAiCompatibleProviderSetting).copy(customHeaders = customHeaders)

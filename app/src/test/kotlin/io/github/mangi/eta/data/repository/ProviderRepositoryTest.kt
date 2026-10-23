package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.agent.model.oauth.ProviderOAuthStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSetting
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun startupRemovesLegacyProviderAndCredentialsWithoutAffectingCodex() = runBlocking {
        val legacy = sampleProvider(id = "retired").copy(
            baseUrl = "https://daily-cloudcode-pa.googleapis.com",
            endpointMode = "antigravity",
            authMode = "oauth_antigravity",
        )
        val dao = EtaDatabase.get(context).providerDao()
        dao.replaceProvider(legacy.toEntity(), legacy.toModelEntities())
        val normal = ProviderRepository.addProvider(sampleProvider(id = "normal"))
        val store = ProviderOAuthStore(context)
        store.saveString("retired", "project_id", "test-project")
        store.saveTokens("retired", org.json.JSONObject().put("access_token", "test-retired"))
        store.saveString("orphaned", "api_host", "https://daily-cloudcode-pa.googleapis.com")
        store.saveTokens("orphaned", org.json.JSONObject().put("access_token", "test-orphaned"))
        store.saveTokens("normal", org.json.JSONObject().put("access_token", "test-codex"))
        SettingsDataStore.setSelection(legacy.id, legacy.models.first().id)
        assertTrue(ProviderRepository.providerById(legacy.id) == null)

        ProviderRepository.ensureBuiltInsMerged()
        ProviderRepository.ensureBuiltInsMerged() // idempotent; also used after backup import

        assertTrue(dao.providerById(legacy.id) == null)
        assertTrue(dao.models(legacy.id).isEmpty())
        assertTrue(store.loadTokens("retired") == null)
        assertTrue(store.loadTokens("orphaned") == null)
        assertEquals("test-codex", store.loadTokens("normal")?.optString("access_token"))
        assertEquals(normal.id, SettingsDataStore.settings().selectedProviderId)
        assertTrue(ProviderRepository.providerById(normal.id) != null)
    }

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

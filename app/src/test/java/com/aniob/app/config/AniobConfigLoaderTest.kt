package com.aniob.app.config

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding #4: configs and prompts used to never reach the APK, so every prompt upgrade was dead
 * on arrival. These assertions run against the packaged assets, proving the APK actually loads
 * the shipped prompt and decode parameters rather than a hardcoded string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AniobConfigLoaderTest {

    private val loader = AniobConfigLoader(ApplicationProvider.getApplicationContext())

    @Test
    fun packagedSystemPromptIsTheShippedPrompt() {
        val prompt = loader.prompt("system_prompt.txt")
        assertNotNull("system_prompt.txt must be bundled in the APK", prompt)
        assertTrue(
            "Bundled prompt must teach the v2 schema",
            prompt!!.contains("\"target\"") && prompt.contains("som_index")
        )
        assertTrue(
            "Bundled prompt must forbid coordinates",
            prompt.contains("NOT valid fields")
        )
    }

    @Test
    fun decodeConfigsComeFromBundledModelsYaml() {
        val configs = loader.decodeConfigs()
        val executor = configs.getValue(com.aniob.core.config.AniobDecodeConfig.Role.EXECUTOR)
        assertEquals(128, executor.maxTokens)
        assertTrue(executor.jsonMode)
    }

    @Test
    fun agentLimitsComeFromBundledAgentYaml() {
        val limits = loader.agentLimits()
        assertEquals(50, limits.maxStepsPerTask)
        assertEquals(15_000L, limits.stepTimeoutMs)
    }

    @Test
    fun missingAssetFallsBackWithoutThrowing() {
        assertEquals(null, loader.prompt("does_not_exist.txt"))
    }

    @Test
    fun bundledModelsYamlDefaultsAreValidAndMatchKotlinCatalog() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val yaml = context.assets.open("aniob_config/models.yaml").bufferedReader().use { it.readText() }

        // 1. Core YAML validation passes
        val validation = com.aniob.core.config.AniobConfigParser.validateCatalogDefaults(yaml)
        assertTrue(validation.errors.joinToString("; "), validation.isValid)

        // 2. Cross-consistency with live Kotlin catalog (AniobModelDownloader.ALL_MODELS)
        val declaredYamlIds = com.aniob.core.config.AniobConfigParser.parseDeclaredModelIds(yaml)
        val kotlinCatalogIds = com.aniob.app.model.AniobModelDownloader.ALL_MODELS.map { it.id }.toSet()
        assertEquals(
            "Declared YAML model IDs must exactly match live Kotlin catalog IDs",
            kotlinCatalogIds,
            declaredYamlIds
        )

        // 3. Every default in YAML exists in the live Kotlin catalog
        val defaults = com.aniob.core.config.AniobConfigParser.parseDefaults(yaml)
        for ((key, modelId) in defaults) {
            assertTrue(
                "Default key '$key' maps to '$modelId', which must exist in live Kotlin catalog",
                kotlinCatalogIds.contains(modelId)
            )
        }
    }
}
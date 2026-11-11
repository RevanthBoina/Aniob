package com.aniob.core.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AniobAccountModelCatalogTest {
    @Test fun `gpt 6 luna is the fallback`() {
        assertEquals("gpt-6-luna", AniobAccountModelCatalog.FALLBACK_MODEL)
        assertEquals("gpt-6-luna", AniobAccountModelCatalog.parse("").defaultModelId)
    }

    @Test fun `valid typed model is preserved outside catalog`() {
        val catalog = AniobAccountModelCatalog.parse("""
            default: gpt-6-luna
            models:
              - id: gpt-6-luna
                label: Luna
        """.trimIndent())
        assertEquals("provider/future-model", catalog.resolve(" provider/future-model "))
    }

    @Test fun `models api json parser handles subscription and standard payloads`() {
        val subscription = AniobAccountModelCatalog.parseModelsApiJson(
            """
            {"models":[
              {"slug":"gpt-6-luna","display_name":"GPT-6 Luna","visibility":"list"},
              {"slug":"hidden-model","display_name":"Hidden","visibility":"hide"},
              {"slug":"gpt-6-luna","display_name":"Duplicate","visibility":"list"}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf(AniobAccountModel("gpt-6-luna", "GPT-6 Luna")), subscription)

        val standard = AniobAccountModelCatalog.parseModelsApiJson(
            """{"data":[{"id":"gpt-6.1-sol","name":"GPT-6.1 Sol"},{"id":"bad id!"}]}"""
        )
        assertEquals(listOf(AniobAccountModel("gpt-6.1-sol", "GPT-6.1 Sol")), standard)
    }
}

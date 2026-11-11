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
}

package com.aniob.core.skills

import com.aniob.core.embedding.AniobEmbeddingStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class SingleSkillIndexTest {
    @Test fun `single skill indexing leaves existing entries untouched`() {
        val dir = Files.createTempDirectory("single-skill").toFile()
        try {
            val store = AniobEmbeddingStore()
            val engine = AniobSkillEngine()
            val matcher = AniobSemanticSkillMatcher(engine, store)
            val existing = store.get("open_settings")
            val file = java.io.File(dir, "new.yaml")
            file.writeText("""
                name: "new_skill"
                version: "2.1"
                description: "Open settings"
                triggers:
                  - "new settings"
                steps:
                  - step: 1
                    action: "open_app"
                    package: "com.android.settings"
            """.trimIndent())
            assertTrue(matcher.loadAndIndexSingleSkill(file))
            assertSame(existing, store.get("open_settings"))
            assertEquals(1, engine.getLoadedSkills().count { it.name == "new_skill" })
            assertFalse(matcher.loadAndIndexSingleSkill(java.io.File(dir, "missing.yaml")))
        } finally { dir.deleteRecursively() }
    }
}

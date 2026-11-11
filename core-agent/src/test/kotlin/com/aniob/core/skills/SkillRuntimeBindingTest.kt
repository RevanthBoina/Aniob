package com.aniob.core.skills

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.SemanticTarget
import org.junit.Assert.*
import org.junit.Test

class SkillRuntimeBindingTest {
    @Test fun unresolvedSlotsCannotBeTypedLiterally() {
        val step = SkillStep(0, "input_text", target = SemanticTarget.ResourceId("compose"), text = "{message}")
        assertNull(step.toAniobAction(emptyMap()))
        assertEquals("Hello Ravi", (step.toAniobAction(mapOf("message" to "Hello Ravi")) as AniobAction.InputText).text)
    }

    @Test fun matchingPreservesInputCase() {
        val engine = AniobSkillEngine()
        engine.registerSkill(AniobSkill(name = "slot-test", description = "test", triggerKeywords = listOf("compose message"),
            slots = mapOf("message" to "string")))
        val match = AniobSkillMatcher(engine).match("compose message Hello Ravi")
        assertTrue(match.matched)
        assertEquals("Hello Ravi", match.extractedSlots["message"])
    }
}

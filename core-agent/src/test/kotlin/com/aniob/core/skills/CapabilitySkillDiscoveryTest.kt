package com.aniob.core.skills

import com.aniob.core.external.AniobCloudLlmProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CapabilitySkillDiscoveryTest {
    @Test fun modelProposalCannotActivateItself() = runBlocking {
        var supplied = ""
        val provider = object : AniobCloudLlmProvider {
            override val name = "selected"
            override suspend fun generate(prompt: String, systemPrompt: String): String {
                supplied = prompt
                return AniobSkillCodec.encode(AniobSkill(name = "review_navigation", description = "Review navigation",
                    triggerKeywords = listOf("review navigation"), steps = listOf(SkillStep(0, "wait", waitDuration = 10))))
            }
            override suspend fun generateStreaming(prompt: String, systemPrompt: String, onDelta: (String) -> Unit) = generate(prompt, systemPrompt)
        }
        val draft = CapabilitySkillDiscovery.discover("real/source.kt\tSkillStep", "Accessibility disabled", "learn navigation", provider)
        assertTrue(supplied.contains("real/source.kt"))
        assertTrue(supplied.contains("Accessibility disabled"))
        assertTrue(draft.isDraft)
        assertEquals(SkillStatus.DRAFT, draft.status)
        val engine = AniobSkillEngine()
        engine.registerSkill(draft)
        assertFalse(engine.getActiveCatalog().any { it.name == draft.name })
        assertEquals(draft, AniobSkillCodec.decode(AniobSkillCodec.encode(draft)))
    }
    @Test fun emptyDraftDoesNotInventExecutableSteps() {
        val draft = AniobSkill("unsupported", description = "Cannot ground this task", status = SkillStatus.DRAFT)
        assertTrue(AniobSkillCodec.decode(AniobSkillCodec.encode(draft)).steps.isEmpty())
        try {
            AniobSkillCodec.decode(AniobSkillCodec.encode(draft.copy(status = SkillStatus.ACTIVE, isDraft = false)))
            fail("Active empty skills must be rejected")
        } catch (_: SkillParseException) { }
    }
    @Test fun paraphraseSelectsActiveSkillAndDisabledSkillStillCannotMatch() {
        val engine = AniobSkillEngine()
        engine.registerSkill(AniobSkill("navigation_review", description = "", triggerKeywords = listOf("review navigation"),
            steps = listOf(SkillStep(0, "wait", waitDuration = 10))))
        val matcher = AniobSkillMatcher(engine)
        assertEquals("navigation_review", matcher.match("navigation review please").skill!!.name)
        assertFalse(matcher.match("navigation review please", setOf("navigation_review")).matched)
    }

    @Test fun tokenMatchingSupportsParaphraseAndRejectsUnrelatedAndAmbiguous() {
        assertTrue(SkillRelevance.score("please run quickly", "running quickly") > .55f)
        assertTrue(SkillRelevance.score("weather outside", "wether") > .55f)
        assertEquals(0f, SkillRelevance.score("bake pasta", "weather forecast"), 0f)
        val engine = AniobSkillEngine()
        engine.registerSkill(AniobSkill("one", description = "", triggerKeywords = listOf("custom timer")))
        engine.registerSkill(AniobSkill("two", description = "", triggerKeywords = listOf("custom timer")))
        assertFalse(AniobSkillMatcher(engine).match("timer custom").matched)
        assertFalse(AniobSkillMatcher(engine).match("do not custom timer").matched)
    }
}

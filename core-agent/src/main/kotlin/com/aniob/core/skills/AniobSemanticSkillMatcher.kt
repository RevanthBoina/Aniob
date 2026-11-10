package com.aniob.core.skills

import com.aniob.core.embedding.AniobEmbeddingProvider
import com.aniob.core.embedding.AniobEmbeddingStore
import com.aniob.core.embedding.AniobPseudoEmbeddingProvider

class AniobSemanticSkillMatcher(
    private val skillEngine: AniobSkillEngine = AniobSkillEngine(),
    private val embeddingStore: AniobEmbeddingStore = AniobEmbeddingStore(),
    private val embeddingProvider: AniobEmbeddingProvider = AniobPseudoEmbeddingProvider()
) {
    private val keywordMatcher = AniobSkillMatcher(skillEngine)

    init {
        indexSkills()
    }

    @Synchronized
    fun indexSkills() {
        for (skill in skillEngine.getActiveCatalog()) {
            val text = "${skill.name} ${skill.description} ${skill.triggerKeywords.joinToString(" ")}"
            val vec = if (embeddingProvider.isAvailable()) embeddingProvider.embed(text) else AniobEmbeddingStore.generatePseudoEmbedding(text)
            embeddingStore.put(skill.name, text, vec, mapOf("skillName" to skill.name))
        }
    }

    @Synchronized
    fun loadAndIndexDirectory(dir: java.io.File): Int {
        val loaded = skillEngine.loadFromDirectory(dir)
        if (loaded > 0) indexSkills()
        return loaded
    }

    @Synchronized
    fun removeSkill(name: String) {
        if (skillEngine.removeSkill(name)) {
            embeddingStore.clear()
            indexSkills()
        }
    }

    fun getLoadedSkills(): List<AniobSkill> = skillEngine.getLoadedSkills()

    fun findBestSkill(prompt: String, disabledSkills: Set<String> = emptySet()): AniobSkillMatcher.MatchResult {
        val kw = keywordMatcher.match(prompt, disabledSkills)
        if (kw.matched) return kw
        val qVec = if (embeddingProvider.isAvailable()) embeddingProvider.embed(prompt) else AniobEmbeddingStore.generatePseudoEmbedding(prompt)
        val matches = embeddingStore.search(qVec, topK = 1, threshold = 0.60f)
        if (matches.isNotEmpty()) {
            val best = matches.first()
            val skill = skillEngine.getActiveCatalog().firstOrNull { it.name == best.id && !disabledSkills.contains(it.name) }
            if (skill != null) return AniobSkillMatcher.MatchResult(
                matched = true,
                skill = skill,
                confidence = best.score,
                reason = "Semantic Vector Match: '${skill.name}' score ${"%.2f".format(best.score)}"
            )
        }
        return AniobSkillMatcher.MatchResult(false, reason = "No semantic or keyword match")
    }
}

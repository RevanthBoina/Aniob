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
    fun loadAndIndexSingleSkill(file: java.io.File): Boolean {
        val skill = skillEngine.loadSingleSkill(file) ?: return false
        if (skill.status == SkillStatus.ACTIVE && !skill.isDraft) {
            val text = "${skill.name} ${skill.description} ${skill.triggerKeywords.joinToString(" ") }"
            val vector = if (embeddingProvider.isAvailable()) embeddingProvider.embed(text)
                else AniobEmbeddingStore.generatePseudoEmbedding(text)
            embeddingStore.put(skill.name, text, vector, mapOf("skillName" to skill.name))
        }
        return true
    }

    @Synchronized
    fun loadAndIndexStream(stream: java.io.InputStream): Boolean = runCatching {
        val skill = skillEngine.loadFromStream(stream)
        indexSkills()
        true
    }.getOrDefault(false)

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
        val matches = embeddingStore.search(qVec, topK = skillEngine.getActiveCatalog().size.coerceAtLeast(1), threshold = 0.60f)
        for (best in matches) {
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

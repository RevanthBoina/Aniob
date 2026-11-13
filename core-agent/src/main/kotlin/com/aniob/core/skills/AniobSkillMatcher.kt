package com.aniob.core.skills

/**
 * Tier 1.5 Matcher between Intent and Agent Loop:
 * Matches input prompt against loaded YAML skills in <1ms without LLM invocations.
 */
class AniobSkillMatcher(
    private val skillEngine: AniobSkillEngine = AniobSkillEngine()
) {

    data class MatchResult(
        val matched: Boolean,
        val skill: AniobSkill? = null,
        val confidence: Float = 0.0f,
        val extractedSlots: Map<String, String> = emptyMap(),
        val reason: String = ""
    )

    fun match(prompt: String, disabledSkills: Set<String> = emptySet()): MatchResult {
        val cleanPrompt = prompt.trim().lowercase()
        if (cleanPrompt.isBlank() || Regex("\\b(?:not|never|cancel)\\b|don't|do not").containsMatchIn(cleanPrompt)) {
            return MatchResult(matched = false, reason = "Empty or negated prompt")
        }

        // Only active catalog skills participate in matching - draft/disabled skills are strictly skipped
        val candidates = skillEngine.getActiveCatalog().filter {
            !disabledSkills.contains(it.name) && it.status == SkillStatus.ACTIVE && !it.isDraft
        }

        // Specificity-ranked trigger matching: prefer longest matching trigger phrase first
        var bestSkill: AniobSkill? = null
        var bestTrigger = ""
        for (skill in candidates) {
            for (kw in skill.triggerKeywords) {
                val cleanKw = kw.trim().lowercase()
                if (cleanKw.isNotEmpty() &&
                    Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(cleanKw) + "(?![\\p{L}\\p{N}])").containsMatchIn(cleanPrompt)
                ) {
                    if (cleanKw.length > bestTrigger.length) {
                        bestSkill = skill
                        bestTrigger = cleanKw
                    }
                }
            }
        }
        if (bestSkill != null) {
            if (candidates.count { bestTrigger in it.triggerKeywords.map(String::lowercase) } > 1)
                return MatchResult(false, reason = "Ambiguous skill triggers")
            val slots = extractSlots(prompt, bestSkill)
            return MatchResult(
                matched = true,
                skill = bestSkill,
                confidence = 0.95f,
                extractedSlots = slots,
                reason = "Tier 1.5 YAML skill matched: '${bestSkill.name}' via trigger '$bestTrigger' (0 LLM calls)"
            )
        }

        for (skill in candidates) {
            // Check regex pattern if defined (using pre-compiled cache)
            if (skill.intentPattern.isNotBlank()) {
                try {
                    val regex = INTENT_REGEX_CACHE.computeIfAbsent(skill.intentPattern) {
                        Regex(it, RegexOption.IGNORE_CASE)
                    }
                    if (regex.containsMatchIn(cleanPrompt)) {
                        return MatchResult(
                            matched = true,
                            skill = skill,
                            confidence = 0.90f,
                            extractedSlots = extractSlots(prompt, skill),
                            reason = "Tier 1.5 YAML skill matched: '${skill.name}' via pattern (0 LLM calls)"
                        )
                    }
                } catch (e: Exception) {
                    // Malformed regex in skill skipped gracefully
                }
            }
        }

        val ranked = candidates.map { skill ->
            skill to skill.triggerKeywords.maxOfOrNull { SkillRelevance.score(prompt, it) }.orZero()
        }.sortedWith(compareByDescending<Pair<AniobSkill, Float>> { it.second }.thenBy { it.first.name })
        val top = ranked.firstOrNull()
        if (top != null && top.second >= .55f && ranked.size > 1 && top.second - ranked[1].second < .08f)
            return MatchResult(false, reason = "Ambiguous skill scores")
        if (top != null && top.second >= .55f && (ranked.size == 1 || top.second - ranked[1].second >= .08f)) {
            return MatchResult(true, top.first, top.second, extractSlots(prompt, top.first), "Token/stem/typo match: ${top.first.name}")
        }
        return MatchResult(matched = false, reason = "No unambiguous YAML skill match found")
    }

    private fun Float?.orZero(): Float = this ?: 0f

    private fun extractSlots(prompt: String, skill: AniobSkill): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for ((slotName, _) in skill.slots) {
            val regex = SLOT_REGEX_CACHE.computeIfAbsent(slotName) {
                Regex("${Regex.escape(it)}\\s+(?:is|to|as)?\\s*([a-zA-Z0-9_ -]+)", RegexOption.IGNORE_CASE)
            }
            regex.find(prompt)?.let { match ->
                val value = match.groupValues.getOrNull(1)?.trim()
                if (!value.isNullOrBlank()) {
                    result[slotName] = value
                }
            }
        }
        return result
    }

    companion object {
        private val INTENT_REGEX_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
        private val SLOT_REGEX_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    }
}

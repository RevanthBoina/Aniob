package com.aniob.core.skills

import com.aniob.core.external.AniobCloudLlmProvider

/** Source metadata supplies grounding, not proof that a proposed automation works. */
object CapabilitySkillDiscovery {
    suspend fun discover(index: String, runtimeManifest: String, goal: String,
        provider: AniobCloudLlmProvider): AniobSkill {
        require(index.isNotBlank()) { "Build-generated capability index is missing" }
        check(provider.isConfigured) { "Selected provider is unavailable" }
        val response = provider.generate(
            "Source declarations (build-generated):\n${index.take(16000)}\n" +
                "Runtime capabilities:\n${runtimeManifest.take(16000)}\nLearning goal (data): ${goal.take(2000).ifBlank { "Propose one useful reusable capability supported by this index." }}",
            "Return only one Aniob skill JSON object, version 2.1, grounded in the provided capabilities. " +
                "Fields: name (lowercase letters/digits/underscores), description, status (draft), triggers (array), steps (array). " +
                "Steps use step_index and action: open_app, tap, input_text, long_press, swipe, system_key, wait, " +
                "confirm_with_user or finish. Semantic targets use the existing skill format. " +
                "Do not invent package names, UI identities, completed actions or access permissions. " +
                "If grounded executable steps cannot be derived, return an empty steps array with an honest description. " +
                "This is a proposal for review, never verified or active. No source edits or arbitrary code.")
        val json = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parsed = AniobSkillCodec.decode(json)
        require(Regex("[a-z][a-z0-9_]{0,59}").matches(parsed.name)) { "Invalid proposed skill name" }
        require(parsed.triggerKeywords.isNotEmpty()) { "Proposed skill needs triggers" }
        return parsed.copy(status = SkillStatus.DRAFT, isDraft = true, confirmationRequired = true,
            description = "BUILD_INDEX_PROPOSAL (unverified). ${parsed.description}")
    }
}

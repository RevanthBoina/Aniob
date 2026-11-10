package com.aniob.core.skills

import java.io.InputStream

/**
 * Loads, parses, and manages declarative YAML/JSON skills (Skill Format v2.1).
 * Pure JVM (no android.* dependencies).
 */
class AniobSkillEngine {

    private val loadedSkills = mutableListOf<AniobSkill>()

    init {
        loadDefaultBuiltinSkills()
    }

    @Synchronized
    fun getLoadedSkills(): List<AniobSkill> = loadedSkills.toList()

    @Synchronized
    fun getActiveCatalog(): List<AniobSkill> = loadedSkills.filter { it.status == SkillStatus.ACTIVE && !it.isDraft }

    @Synchronized
    fun getDraftCatalog(): List<AniobSkill> = loadedSkills.filter { it.status == SkillStatus.DRAFT || it.isDraft }

    @Synchronized
    fun registerSkill(skill: AniobSkill) {
        loadedSkills.removeAll { it.name.equals(skill.name, ignoreCase = true) }
        loadedSkills.add(skill)
    }

    @Synchronized
    fun removeSkill(name: String): Boolean =
        loadedSkills.removeAll { it.name.equals(name, ignoreCase = true) }

    @Synchronized
    fun loadFromDirectory(dir: java.io.File): Int {
        if (!dir.exists() || !dir.isDirectory) return 0
        var count = 0
        dir.listFiles { f -> f.isFile && (f.extension == "yaml" || f.extension == "yml" || f.extension == "json") }
            ?.forEach { file ->
                runCatching {
                    val skill = parseYaml(file.readText())
                    registerSkill(skill)
                    count++
                }
            }
        return count
    }

    /**
     * Parses standard YAML/JSON skill v2.1 text via strict AniobSkillCodec.
     */
    fun parseYaml(yamlContent: String): AniobSkill {
        return AniobSkillCodec.decode(yamlContent)
    }

    fun loadFromStream(inputStream: InputStream): AniobSkill {
        val content = inputStream.bufferedReader().use { it.readText() }
        val skill = parseYaml(content)
        registerSkill(skill)
        return skill
    }

    private fun loadDefaultBuiltinSkills() {
        // Built-in foundational declarative skills
        registerSkill(
            AniobSkill(
                name = "open_settings",
                version = "2.1",
                description = "Directly navigates to Android system settings",
                triggerKeywords = listOf("open settings", "system settings", "settings app", "preferences"),
                riskTier = "LOW",
                confirmationRequired = false,
                targetPackage = "com.android.settings",
                steps = listOf(
                    SkillStep(1, "open_app", packageName = "com.android.settings", description = "Launch settings app"),
                    SkillStep(2, "finish", description = "Settings opened successfully")
                ),
                status = SkillStatus.ACTIVE
            )
        )

        registerSkill(
            AniobSkill(
                name = "send_quick_message",
                version = "2.1",
                description = "Compose and send a message via SMS/Chat with confirmation gate",
                triggerKeywords = listOf("send message", "send text", "message to", "sms"),
                riskTier = "HIGH",
                confirmationRequired = true,
                targetPackage = "com.google.android.apps.messaging",
                steps = listOf(
                    SkillStep(1, "open_app", packageName = "com.google.android.apps.messaging", description = "Launch messages"),
                    SkillStep(2, "confirm_with_user", description = "Confirm sending message to recipient"),
                    SkillStep(3, "finish", description = "Message sent")
                ),
                status = SkillStatus.DRAFT
            )
        )
    }
}

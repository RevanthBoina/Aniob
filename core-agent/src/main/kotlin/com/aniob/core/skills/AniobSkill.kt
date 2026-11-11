package com.aniob.core.skills

import com.aniob.core.domain.AniobAction
import com.aniob.core.domain.KeyType
import com.aniob.core.domain.SemanticTarget
import com.aniob.core.domain.SwipeDirection

enum class SkillStatus {
    ACTIVE,
    DRAFT,
    DISABLED
}

data class SkillStep(
    val stepIndex: Int,
    val actionType: String,
    val selector: String? = null,
    /** Legacy pre-v2.1 node id. Superseded by [target]; kept for replaying old skills. */
    val targetNodeId: Int? = null,
    /** v2.1 semantic target block. Preferred over [targetNodeId] when present. */
    val target: SemanticTarget? = null,
    val text: String? = null,
    val packageName: String? = null,
    val key: String? = null,
    val direction: String? = null,
    val description: String = "",
    val clearFirst: Boolean = false,
    val waitDuration: Long = 0L,
    val pressDuration: Long = 0L,
    val swipeContainer: String? = null
) {
    fun toAniobAction(): AniobAction {
        return when (actionType.lowercase()) {
            "open_app" -> {
                val pkg = packageName ?: throw IllegalArgumentException("open_app step $stepIndex requires package name")
                AniobAction.OpenApp(packageName = pkg)
            }
            "tap", "click" -> {
                val resolved = resolveTarget() ?: throw IllegalArgumentException("tap step $stepIndex requires a valid target")
                AniobAction.Tap(target = resolved, thought = description)
            }
            "input_text" -> {
                val resolved = resolveTarget() ?: throw IllegalArgumentException("input_text step $stepIndex requires a valid target")
                AniobAction.InputText(target = resolved, text = text ?: "", clearFirst = clearFirst)
            }
            "long_press" -> {
                val resolved = resolveTarget() ?: throw IllegalArgumentException("long_press step $stepIndex requires a valid target")
                AniobAction.LongPress(target = resolved, durationMs = if (pressDuration > 0) pressDuration else 1000L, thought = description)
            }
            "swipe" -> {
                val dir = when (direction?.uppercase()) {
                    "UP" -> SwipeDirection.UP
                    "DOWN" -> SwipeDirection.DOWN
                    "LEFT" -> SwipeDirection.LEFT
                    "RIGHT" -> SwipeDirection.RIGHT
                    else -> throw IllegalArgumentException("swipe step $stepIndex requires a valid direction (UP, DOWN, LEFT, RIGHT)")
                }
                val cont = swipeContainer?.let { SemanticTarget.ResourceId(it) }
                AniobAction.Swipe(direction = dir, container = cont)
            }
            "system_key" -> {
                val k = when (key?.uppercase()) {
                    "BACK" -> KeyType.BACK
                    "HOME" -> KeyType.HOME
                    "ENTER" -> KeyType.ENTER
                    else -> throw IllegalArgumentException("system_key step $stepIndex requires a valid key (BACK, HOME, ENTER)")
                }
                AniobAction.SystemKey(key = k)
            }
            "wait" -> AniobAction.Wait(durationMs = if (waitDuration > 0) waitDuration else AniobAction.Wait.DEFAULT_WAIT_MS)
            "confirm_with_user" -> AniobAction.ConfirmWithUser(message = description)
            "finish" -> AniobAction.Finish(summary = description)
            else -> throw IllegalArgumentException("Unknown skill action '$actionType' at step $stepIndex")
        }
    }

    /** Unbound slots are a routing miss, never literal text to type or send. */
    fun toAniobAction(slots: Map<String, String>): AniobAction? {
        val pattern = Regex("""\{([A-Za-z][A-Za-z0-9_]*)\}""")
        fun bind(value: String?): String? {
            if (value == null) return null
            var missing = false
            val result = pattern.replace(value) { match ->
                slots[match.groupValues[1]] ?: run { missing = true; match.value }
            }
            return if (missing) null else result
        }
        val boundText = if (text == null) null else bind(text) ?: return null
        val boundTarget = when (val t = target) {
            is SemanticTarget.Text -> t.copy(text = bind(t.text) ?: return null)
            is SemanticTarget.ContentDesc -> t.copy(desc = bind(t.desc) ?: return null)
            else -> t
        }
        return runCatching { copy(text = boundText, target = boundTarget).toAniobAction() }.getOrNull()
    }

    /**
     * Resolves the semantic target, falling back to the legacy node id. Legacy skills keep
     * working; the deprecation is logged by the loader so authors migrate to `target:`.
     */
    private fun resolveTarget(): SemanticTarget? = target
        ?: targetNodeId?.let {
            System.err.println(
                "[AniobSkill] DEPRECATED: step $stepIndex uses legacy 'target_id'. " +
                    "Migrate to a semantic 'target:' block (resource_id/text/content_desc)."
            )
            SemanticTarget.SomIndex(it)
        }
}

data class AniobSkill(
    val name: String,
    val version: String = "2.1",
    val description: String,
    val triggerKeywords: List<String> = emptyList(),
    val intentPattern: String = "",
    val riskTier: String = "LOW", // LOW, MEDIUM, HIGH
    val confirmationRequired: Boolean = false,
    val targetPackage: String? = null,
    val slots: Map<String, String> = emptyMap(),
    val steps: List<SkillStep> = emptyList(),
    val status: SkillStatus = SkillStatus.ACTIVE,
    val isDraft: Boolean = status == SkillStatus.DRAFT
)

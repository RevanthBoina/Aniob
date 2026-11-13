package com.aniob.core.skills

import com.aniob.core.domain.SemanticTarget

class SkillParseException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Strict versioned codec for AniobSkill format v2.1.
 * Supports round-trip encoding/decoding with strict validation.
 */
object AniobSkillCodec {

    private val VALID_ACTIONS = setOf(
        "open_app", "tap", "click", "input_text", "long_press",
        "swipe", "system_key", "wait", "confirm_with_user", "finish"
    )

    private val VALID_DIRECTIONS = setOf("UP", "DOWN", "LEFT", "RIGHT")
    private val VALID_KEYS = setOf("BACK", "HOME", "ENTER")

    private val TRIGGERS_BLOCK_REGEX = Regex("\"triggers\"\\s*:\\s*\\[([^\\]]*)\\]")
    private val QUOTED_STRING_REGEX = Regex("\"([^\"]*)\"")
    private val SLOTS_BLOCK_REGEX = Regex("\"slots\"\\s*:\\s*\\{([^}]*)\\}")
    private val KEY_VALUE_PAIR_REGEX = Regex("\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\"")
    private val STEPS_BLOCK_REGEX = Regex("\"steps\"\\s*:\\s*\\[(.*)\\]\\s*\\}\\s*$", RegexOption.DOT_MATCHES_ALL)
    private val STEP_OBJECT_REGEX = Regex("\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}")
    private val TARGET_OBJ_REGEX = Regex("\"target\"\\s*:\\s*(\\{[^{}]*\\})")

    private val strFieldRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private val numFieldRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()
    private val boolFieldRegexCache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun stripQuotes(s: String): String = s.trim().removeSurrounding("\"").removeSurrounding("'").trim()

    /**
     * Serializes an AniobSkill to a versioned JSON string.
     */
    fun encode(skill: AniobSkill): String = buildString {
        appendLine("{")
        appendLine("  \"name\": \"${esc(skill.name)}\",")
        appendLine("  \"version\": \"${esc(skill.version)}\",")
        appendLine("  \"description\": \"${esc(skill.description)}\",")
        appendLine("  \"status\": \"${skill.status.name.lowercase()}\",")
        appendLine("  \"is_draft\": ${skill.isDraft},")
        appendLine("  \"risk_tier\": \"${esc(skill.riskTier)}\",")
        appendLine("  \"confirmation_required\": ${skill.confirmationRequired},")
        if (skill.targetPackage != null) {
            appendLine("  \"target_package\": \"${esc(skill.targetPackage)}\",")
        }
        if (skill.intentPattern.isNotBlank()) {
            appendLine("  \"intent_pattern\": \"${esc(skill.intentPattern)}\",")
        }
        appendLine("  \"triggers\": [${skill.triggerKeywords.joinToString(", ") { "\"${esc(it)}\"" }}],")
        appendLine("  \"slots\": {${skill.slots.entries.joinToString(", ") { "\"${esc(it.key)}\": \"${esc(it.value)}\"" }}},")
        appendLine("  \"steps\": [")
        skill.steps.forEachIndexed { idx, step ->
            val isLast = idx == skill.steps.lastIndex
            appendLine("    {")
            appendLine("      \"step_index\": ${step.stepIndex},")
            appendLine("      \"action\": \"${esc(step.actionType)}\",")
            appendLine("      \"description\": \"${esc(step.description)}\",")
            if (step.packageName != null) {
                appendLine("      \"package\": \"${esc(step.packageName)}\",")
            }
            if (step.text != null) {
                appendLine("      \"text\": \"${esc(step.text)}\",")
            }
            if (step.clearFirst) {
                appendLine("      \"clear_first\": true,")
            }
            if (step.waitDuration > 0) {
                appendLine("      \"wait_duration\": ${step.waitDuration},")
            }
            if (step.pressDuration > 0) {
                appendLine("      \"press_duration\": ${step.pressDuration},")
            }
            if (step.direction != null) {
                appendLine("      \"direction\": \"${esc(step.direction)}\",")
            }
            if (step.swipeContainer != null) {
                appendLine("      \"swipe_container\": \"${esc(step.swipeContainer)}\",")
            }
            if (step.key != null) {
                appendLine("      \"key\": \"${esc(step.key)}\",")
            }
            if (step.target != null) {
                appendLine("      \"target\": ${encodeTarget(step.target)}")
            } else if (step.targetNodeId != null) {
                appendLine("      \"target_id\": ${step.targetNodeId}")
            } else {
                appendLine("      \"target\": null")
            }
            appendLine("    }${if (isLast) "" else ","}")
        }
        appendLine("  ]")
        append("}")
    }

    private fun encodeTarget(target: SemanticTarget): String = when (target) {
        is SemanticTarget.SomIndex -> """{"kind": "som_index", "value": ${target.index}}"""
        is SemanticTarget.ResourceId -> """{"kind": "resource_id", "value": "${esc(target.id)}"}"""
        is SemanticTarget.Text -> """{"kind": "text", "value": "${esc(target.text)}", "exact": ${target.exact}}"""
        is SemanticTarget.ContentDesc -> """{"kind": "content_desc", "value": "${esc(target.desc)}", "exact": ${target.exact}}"""
        is SemanticTarget.NormalizedCoordinate -> """{"kind": "coord", "x": ${target.normX}, "y": ${target.normY}}"""
        is SemanticTarget.BoundingBox -> """{"kind": "box", "left": ${target.left}, "top": ${target.top}, "right": ${target.right}, "bottom": ${target.bottom}}"""
    }

    /**
     * Decodes either JSON or YAML formatted skill text into an [AniobSkill].
     * Performs strict validation on all actions, targets, and parameters.
     */
    fun decode(content: String): AniobSkill {
        val trimmed = content.trim()
        return if (trimmed.startsWith("{")) {
            decodeJson(trimmed)
        } else {
            decodeYaml(trimmed)
        }
    }

    private fun decodeYaml(yaml: String): AniobSkill {
        var name = "unknown_skill"
        var version = "2.1"
        var description = ""
        var status = SkillStatus.ACTIVE
        val triggerKeywords = mutableListOf<String>()
        var intentPattern = ""
        var riskTier = "LOW"
        var confirmationRequired = false
        var targetPackage: String? = null
        val slots = mutableMapOf<String, String>()
        val steps = mutableListOf<SkillStep>()

        var inTriggers = false
        var inSteps = false
        var inSlots = false

        var stepHasStarted = false
        var currentStepIdx = 1
        var currentAction: String? = null
        var currentSelector: String? = null
        var currentTargetId: Int? = null
        var currentTarget: SemanticTarget? = null
        var currentText: String? = null
        var currentPkg: String? = null
        var currentKey: String? = null
        var currentDirection: String? = null
        var currentDesc = ""
        var currentClearFirst = false
        var currentWaitDuration = 0L
        var currentPressDuration = 0L
        var currentSwipeContainer: String? = null

        var inTargetBlock = false
        var targetKind: String? = null
        var targetValue: String? = null
        var targetExact = false

        fun flushTarget() {
            if (inTargetBlock && targetKind != null && targetValue != null) {
                currentTarget = when (targetKind!!.lowercase()) {
                    "som_index", "som", "index" -> targetValue!!.toIntOrNull()?.let { SemanticTarget.SomIndex(it) }
                    "resource_id", "id", "view_id" -> SemanticTarget.ResourceId(targetValue!!)
                    "text" -> SemanticTarget.Text(targetValue!!, targetExact)
                    "content_desc", "content_description", "desc" -> SemanticTarget.ContentDesc(targetValue!!, targetExact)
                    else -> throw SkillParseException("Unknown target kind '$targetKind' in step $currentStepIdx")
                }
            }
            inTargetBlock = false
            targetKind = null
            targetValue = null
            targetExact = false
        }

        fun flushStep() {
            if (!stepHasStarted) return
            flushTarget()
            val action = currentAction ?: throw SkillParseException("Step $currentStepIdx missing required 'action' field")
            val cleanAction = stripQuotes(action).lowercase()
            if (cleanAction !in VALID_ACTIONS) {
                throw SkillParseException("Unknown skill action '$cleanAction' at step $currentStepIdx. Supported: $VALID_ACTIONS")
            }

            // Validate requirements per action
            when (cleanAction) {
                "open_app" -> {
                    if (currentPkg.isNullOrBlank()) {
                        throw SkillParseException("open_app step $currentStepIdx requires a non-blank 'package' field")
                    }
                }
                "tap", "click", "input_text", "long_press" -> {
                    if (currentTarget == null && currentTargetId == null) {
                        throw SkillParseException("$cleanAction step $currentStepIdx requires a valid target (som_index, resource_id, text, content_desc)")
                    }
                }
                "swipe" -> {
                    val dir = currentDirection?.uppercase()
                    if (dir == null || dir !in VALID_DIRECTIONS) {
                        throw SkillParseException("swipe step $currentStepIdx requires a valid direction: $VALID_DIRECTIONS")
                    }
                }
                "system_key" -> {
                    val k = currentKey?.uppercase()
                    if (k == null || k !in VALID_KEYS) {
                        throw SkillParseException("system_key step $currentStepIdx requires a valid key: $VALID_KEYS")
                    }
                }
            }

            steps.add(
                SkillStep(
                    stepIndex = currentStepIdx,
                    actionType = cleanAction,
                    selector = currentSelector,
                    targetNodeId = currentTargetId,
                    target = currentTarget,
                    text = currentText,
                    packageName = currentPkg,
                    key = currentKey,
                    direction = currentDirection,
                    description = currentDesc,
                    clearFirst = currentClearFirst,
                    waitDuration = currentWaitDuration,
                    pressDuration = currentPressDuration,
                    swipeContainer = currentSwipeContainer
                )
            )

            currentStepIdx++
            currentAction = null
            currentSelector = null
            currentTargetId = null
            currentTarget = null
            currentText = null
            currentPkg = null
            currentKey = null
            currentDirection = null
            currentDesc = ""
            currentClearFirst = false
            currentWaitDuration = 0L
            currentPressDuration = 0L
            currentSwipeContainer = null
            stepHasStarted = false
        }

        yaml.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("#") || line.isBlank()) return@forEach

            when {
                line.startsWith("name:") -> name = stripQuotes(line.substringAfter("name:"))
                line.startsWith("version:") -> version = stripQuotes(line.substringAfter("version:"))
                line.startsWith("description:") -> description = stripQuotes(line.substringAfter("description:"))
                line.startsWith("status:") -> {
                    val s = stripQuotes(line.substringAfter("status:")).uppercase()
                    status = if (s == "DRAFT") SkillStatus.DRAFT else if (s == "DISABLED") SkillStatus.DISABLED else SkillStatus.ACTIVE
                }
                line.startsWith("is_draft:") || line.startsWith("draft:") -> {
                    if (stripQuotes(line.substringAfter(":")).toBoolean()) {
                        status = SkillStatus.DRAFT
                    }
                }
                line.startsWith("risk_tier:") -> riskTier = stripQuotes(line.substringAfter("risk_tier:")).uppercase()
                line.startsWith("confirmation_required:") -> confirmationRequired = stripQuotes(line.substringAfter("confirmation_required:")).toBoolean()
                line.startsWith("target_package:") -> targetPackage = stripQuotes(line.substringAfter("target_package:")).ifBlank { null }
                line.startsWith("intent_pattern:") -> intentPattern = stripQuotes(line.substringAfter("intent_pattern:"))

                line.startsWith("triggers:") -> {
                    inTriggers = true; inSteps = false; inSlots = false
                }
                line.startsWith("slots:") -> {
                    inSlots = true; inTriggers = false; inSteps = false
                }
                line.startsWith("steps:") -> {
                    inSteps = true; inTriggers = false; inSlots = false
                }

                inTriggers && line.startsWith("- ") -> {
                    triggerKeywords.add(stripQuotes(line.removePrefix("- ")).lowercase())
                }
                inSlots && line.contains(":") -> {
                    val k = stripQuotes(line.substringBefore(":"))
                    val v = stripQuotes(line.substringAfter(":"))
                    if (k.isNotBlank()) slots[k] = v
                }
                inSteps && (line.startsWith("- step") || line.startsWith("- order") || line.startsWith("- action")) -> {
                    if (stepHasStarted) {
                        flushStep()
                    }
                    stepHasStarted = true
                    if (line.startsWith("- action:")) {
                        flushTarget()
                        currentAction = stripQuotes(line.substringAfter("- action:"))
                    } else {
                        val parsedIdx = stripQuotes(line.substringAfter(":")).toIntOrNull()
                        if (parsedIdx != null) currentStepIdx = parsedIdx
                    }
                }
                inSteps && line.startsWith("action:") -> {
                    flushTarget()
                    currentAction = stripQuotes(line.substringAfter("action:"))
                }
                inSteps && line.startsWith("package:") -> currentPkg = stripQuotes(line.substringAfter("package:")).ifBlank { null }
                inSteps && line.startsWith("target_id:") -> currentTargetId = stripQuotes(line.substringAfter("target_id:")).toIntOrNull()
                inSteps && (line == "target:" || line == "- target:") -> {
                    flushTarget()
                    inTargetBlock = true
                }
                inTargetBlock && line.startsWith("kind:") -> targetKind = stripQuotes(line.substringAfter("kind:"))
                inTargetBlock && line.startsWith("value:") -> targetValue = stripQuotes(line.substringAfter("value:"))
                inTargetBlock && line.startsWith("exact:") -> targetExact = stripQuotes(line.substringAfter("exact:")).toBoolean()
                inSteps && line.startsWith("key:") -> currentKey = stripQuotes(line.substringAfter("key:"))
                inSteps && line.startsWith("direction:") -> currentDirection = stripQuotes(line.substringAfter("direction:"))
                inSteps && line.startsWith("text:") -> currentText = stripQuotes(line.substringAfter("text:"))
                inSteps && line.startsWith("description:") -> currentDesc = stripQuotes(line.substringAfter("description:"))
                inSteps && line.startsWith("clear_first:") -> currentClearFirst = stripQuotes(line.substringAfter("clear_first:")).toBoolean()
                inSteps && (line.startsWith("wait_duration:") || line.startsWith("wait_ms:")) ->
                    currentWaitDuration = stripQuotes(line.substringAfter(":")).toLongOrNull() ?: 0L
                inSteps && (line.startsWith("press_duration:") || line.startsWith("duration_ms:")) ->
                    currentPressDuration = stripQuotes(line.substringAfter(":")).toLongOrNull() ?: 0L
                inSteps && (line.startsWith("swipe_container:") || line.startsWith("container:")) ->
                    currentSwipeContainer = stripQuotes(line.substringAfter(":"))
            }
        }
        if (stepHasStarted) {
            flushStep()
        }

        if (name.isBlank() || name == "unknown_skill") throw SkillParseException("Skill name cannot be blank")
        if (version != "2.1" && version != "2.0" && version != "1" && version != "1.0") {
            throw SkillParseException("Unsupported skill version '$version'")
        }
        if (steps.isEmpty() && status != SkillStatus.DRAFT) throw SkillParseException("Skill must have at least one step")

        return AniobSkill(
            name = name,
            version = version,
            description = description,
            triggerKeywords = triggerKeywords,
            intentPattern = intentPattern,
            riskTier = riskTier,
            confirmationRequired = confirmationRequired,
            targetPackage = targetPackage,
            slots = slots,
            steps = steps,
            status = status,
            isDraft = status == SkillStatus.DRAFT
        )
    }

    private fun decodeJson(json: String): AniobSkill {
        // Fast parser using string/regex helpers
        val name = strVal(json, "name")
        if (name.isNullOrBlank()) throw SkillParseException("Skill name cannot be blank")
        val version = strVal(json, "version") ?: "2.1"
        if (version != "2.1" && version != "2.0" && version != "1" && version != "1.0") {
            throw SkillParseException("Unsupported skill version '$version'")
        }
        val description = strVal(json, "description") ?: ""
        val statusStr = strVal(json, "status")?.uppercase()
        val isDraftBool = boolVal(json, "is_draft") ?: false
        val status = if (statusStr == "DRAFT" || isDraftBool) SkillStatus.DRAFT
                     else if (statusStr == "DISABLED") SkillStatus.DISABLED
                     else SkillStatus.ACTIVE
        val riskTier = strVal(json, "risk_tier")?.uppercase() ?: "LOW"
        val confirmationRequired = boolVal(json, "confirmation_required") ?: false
        val targetPackage = strVal(json, "target_package")
        val intentPattern = strVal(json, "intent_pattern") ?: ""

        val triggers = mutableListOf<String>()
        val triggerBlock = TRIGGERS_BLOCK_REGEX.find(json)?.groupValues?.get(1).orEmpty()
        QUOTED_STRING_REGEX.findAll(triggerBlock).forEach { m ->
            triggers.add(m.groupValues[1].lowercase())
        }

        val slots = mutableMapOf<String, String>()
        val slotsBlock = SLOTS_BLOCK_REGEX.find(json)?.groupValues?.get(1).orEmpty()
        KEY_VALUE_PAIR_REGEX.findAll(slotsBlock).forEach { m ->
            slots[m.groupValues[1]] = m.groupValues[2]
        }

        val steps = mutableListOf<SkillStep>()
        val stepsBlock = STEPS_BLOCK_REGEX.find(json)?.groupValues?.get(1).orEmpty()
        val stepObjects = STEP_OBJECT_REGEX.findAll(stepsBlock)

        for (m in stepObjects) {
            val stepJson = m.value
            val idx = intVal(stepJson, "step_index") ?: (steps.size + 1)
            val action = strVal(stepJson, "action") ?: throw SkillParseException("Missing action in step $idx")
            val cleanAction = action.lowercase()
            if (cleanAction !in VALID_ACTIONS) {
                throw SkillParseException("Unknown action '$cleanAction' in step $idx")
            }

            val desc = strVal(stepJson, "description") ?: ""
            val pkg = strVal(stepJson, "package")
            val text = strVal(stepJson, "text")
            val clearFirst = boolVal(stepJson, "clear_first") ?: false
            val waitDur = longVal(stepJson, "wait_duration") ?: 0L
            val pressDur = longVal(stepJson, "press_duration") ?: 0L
            val dir = strVal(stepJson, "direction")
            val container = strVal(stepJson, "swipe_container")
            val key = strVal(stepJson, "key")
            val targetId = intVal(stepJson, "target_id")

            val targetObj = TARGET_OBJ_REGEX.find(stepJson)?.groupValues?.get(1)
            val target = targetObj?.let { parseTargetObj(it) }

            // Validate
            when (cleanAction) {
                "open_app" -> if (pkg.isNullOrBlank()) throw SkillParseException("open_app step $idx requires package")
                "tap", "click", "input_text", "long_press" -> {
                    if (target == null && targetId == null) throw SkillParseException("$cleanAction step $idx requires target")
                }
                "swipe" -> {
                    if (dir == null || dir.uppercase() !in VALID_DIRECTIONS) throw SkillParseException("swipe step $idx requires direction")
                }
                "system_key" -> {
                    if (key == null || key.uppercase() !in VALID_KEYS) throw SkillParseException("system_key step $idx requires key")
                }
            }

            steps.add(
                SkillStep(
                    stepIndex = idx,
                    actionType = cleanAction,
                    targetNodeId = targetId,
                    target = target,
                    text = text,
                    packageName = pkg,
                    key = key,
                    direction = dir,
                    description = desc,
                    clearFirst = clearFirst,
                    waitDuration = waitDur,
                    pressDuration = pressDur,
                    swipeContainer = container
                )
            )
        }

        if (steps.isEmpty() && status != SkillStatus.DRAFT) throw SkillParseException("Skill must have at least one step")

        return AniobSkill(
            name = name,
            version = version,
            description = description,
            triggerKeywords = triggers,
            intentPattern = intentPattern,
            riskTier = riskTier,
            confirmationRequired = confirmationRequired,
            targetPackage = targetPackage,
            slots = slots,
            steps = steps,
            status = status,
            isDraft = status == SkillStatus.DRAFT
        )
    }

    private fun parseTargetObj(json: String): SemanticTarget? {
        val kind = strVal(json, "kind")?.lowercase() ?: return null
        val value = strVal(json, "value") ?: intVal(json, "value")?.toString() ?: return null
        val exact = boolVal(json, "exact") ?: false
        return when (kind) {
            "som_index", "som", "index" -> value.toIntOrNull()?.let { SemanticTarget.SomIndex(it) }
            "resource_id", "id", "view_id" -> SemanticTarget.ResourceId(value)
            "text" -> SemanticTarget.Text(value, exact)
            "content_desc", "content_description", "desc" -> SemanticTarget.ContentDesc(value, exact)
            else -> null
        }
    }

    private fun strVal(json: String, field: String): String? =
        strFieldRegexCache.getOrPut(field) { Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"") }
            .find(json)?.groupValues?.get(1)?.let { unesc(it) }

    private fun intVal(json: String, field: String): Int? =
        numFieldRegexCache.getOrPut(field) { Regex("\"${Regex.escape(field)}\"\\s*:\\s*(-?\\d+)") }
            .find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun longVal(json: String, field: String): Long? =
        numFieldRegexCache.getOrPut(field) { Regex("\"${Regex.escape(field)}\"\\s*:\\s*(-?\\d+)") }
            .find(json)?.groupValues?.get(1)?.toLongOrNull()

    private fun boolVal(json: String, field: String): Boolean? =
        boolFieldRegexCache.getOrPut(field) { Regex("\"${Regex.escape(field)}\"\\s*:\\s*(true|false)") }
            .find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    private fun unesc(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
}

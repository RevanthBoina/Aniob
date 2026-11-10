package com.aniob.core.grillme

import com.aniob.core.text.AniobTextFold
import com.aniob.core.knowledge.AniobAppRegistry
import kotlin.coroutines.cancellation.CancellationException

/**
 * Grill-Me Dynamic Clarification Data Models & Engine.
 */
data class GrillQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val allowFreeText: Boolean = true,
    val defaultOption: String? = null
)

data class AniobGrillMeResult(
    val needsClarification: Boolean,
    val questions: List<GrillQuestion> = emptyList(),
    val confidence: Float = 1.0f
)

interface GrillMeLlmClient {
    suspend fun generateClarificationQuestions(taskPrompt: String): AniobGrillMeResult
}

class AniobGrillMeEngine(
    private val llmClient: GrillMeLlmClient? = null,
    private val calibratedAsking: AniobCalibratedAsking? = null
) {
    /**
     * Checks if task is ambiguous and generates structured questions + options + free text.
     * Flow: Understand -> Ask questions (if required) -> Create plan -> Select easiest path -> Proceed.
     */
    suspend fun evaluateTask(
        taskPrompt: String,
        rememberedAnswers: Map<String, String> = emptyMap()
    ): AniobGrillMeResult {
        val trimmed = taskPrompt.trim()

        // Deterministic check for simple self-contained intent commands and complete instructions
        if (isSelfContainedIntent(trimmed) || isCompleteInstruction(trimmed)) {
            return AniobGrillMeResult(needsClarification = false)
        }

        // Use the LLM only when something suggests the request is under-specified. A clear single-goal
        // command ("open youtube") must never pay a model round-trip before it starts.
        if (llmClient != null) {
            val deterministic = generateFallbackQuestions(trimmed, rememberedAnswers)
            val understood = com.aniob.core.understander.AniobPromptUnderstanding.understand(trimmed)
            val worthAsking = deterministic.needsClarification || understood.confidence < 0.75 ||
                understood.speechAct == com.aniob.core.understander.AniobPromptUnderstanding.SpeechAct.UNCLEAR
            if (!worthAsking) return AniobGrillMeResult(needsClarification = false)
            return try {
                val res = llmClient.generateClarificationQuestions(trimmed)
                val filtered = res.questions
                    .filter { q ->
                        !rememberedAnswers.containsKey(q.id) && !rememberedAnswers.containsKey("grill.${q.id}") &&
                            !canBeDefaulted(q)
                    }
                    .map(::sanitizeQuestionOptions)
                    .take(1)
                if (filtered.isEmpty()) AniobGrillMeResult(needsClarification = false)
                else res.copy(questions = filtered.take(1))
            } catch (e: CancellationException) {
                throw e // Stop must stop; swallowing it here turned a cancelled run into a "fallback questions" run
            } catch (e: Exception) {
                deterministic
            }
        }

        // Dynamic fallback generator
        return generateFallbackQuestions(trimmed, rememberedAnswers)
    }

    private val SELF_CONTAINED = setOf(
        "open settings", "turn on flashlight", "turn on the flashlight", "turn off flashlight", "turn off the flashlight",
        "check battery", "check the battery", "open calculator", "open camera"
    )
    private val POLITE = setOf("please", "pls", "can", "you", "could", "would", "hey", "aniob", "now", "for", "me")

    private fun isSelfContainedIntent(prompt: String): Boolean {
        val core = AniobTextFold.tokens(prompt).filter { it !in POLITE }.joinToString(" ")
        return core in SELF_CONTAINED
    }

    fun isCompleteInstruction(prompt: String): Boolean {
        val lower = AniobTextFold.fold(prompt)
        val words = AniobTextFold.tokens(prompt).toSet()
        val isQuestion = words.firstOrNull() in setOf("what", "why", "how", "who", "when", "where", "which", "explain", "define")
        if (isQuestion) return false

        // 1. Message instruction with a recipient and a body is complete: ask nothing.
        val isMessage = words.any { it in setOf("message", "text", "send", "sms", "whatsapp", "telegram") }
        if (isMessage) {
            val toMatch = Regex("(?:message|text|send(?:\\s+a)?\\s+message\\s+to|send\\s+to)\\s+([a-zA-Z0-9_]+)").find(lower)
            val hasExplicitRecipient = toMatch != null && toMatch.groupValues[1].trim().isNotBlank() &&
                toMatch.groupValues[1].trim() !in listOf("a", "the", "me", "my", "app")
            val hasContent = lower.contains("saying") || lower.contains("\"") || lower.contains("'") ||
                Regex("(?:text|message)\\s+[a-zA-Z0-9_]+\\s+(?:that\\s+)?(.+)").find(lower)?.groupValues?.get(1)?.isNotBlank() == true ||
                Regex("(?:tell|say\\s+to)\\s+[a-zA-Z0-9_]+\\s+(.+)").find(lower)?.groupValues?.get(1)?.isNotBlank() == true
            if (hasExplicitRecipient && hasContent) return true
        }

        // 2. Alarm or timer instruction with a time is complete: ask nothing.
        val isAlarm = words.any { it in setOf("alarm", "timer") }
        if (isAlarm) {
            val timeMatch = Regex("\\b(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?|in\\s+\\d+\\s+minutes?|for\\s+\\d+\\s+(?:minutes?|seconds?|hours?))\\b").find(lower)
            if (timeMatch != null && timeMatch.value.isNotBlank()) return true
        }

        // 3. Call instruction with a recipient is complete: ask nothing.
        val isCall = words.any { it in setOf("call", "dial", "ring", "phone") }
        if (isCall) {
            val callMatch = Regex("\\b(?:call|dial|ring|phone)\\s+([a-zA-Z0-9_]+)").find(lower)
            val hasRecipient = callMatch != null && callMatch.groupValues[1].trim().isNotBlank() &&
                callMatch.groupValues[1].trim() !in listOf("a", "the", "app")
            if (hasRecipient) return true
        }

        return false
    }

    fun canBeDefaulted(q: GrillQuestion): Boolean {
        if (q.defaultOption != null) return true
        val lowerId = q.id.lowercase(java.util.Locale.ROOT)
        val lowerQ = q.question.lowercase(java.util.Locale.ROOT)
        return lowerId in setOf("app", "provider_app", "ride_type", "confirm", "label", "which_app") ||
            lowerId.contains("app") || lowerId.contains("provider") || lowerId.contains("confirm") ||
            lowerQ.contains("which app") || lowerQ.contains("confirm") || lowerQ.contains("what label")
    }

    /**
     * Generates structured questions for common ambiguous task patterns.
     * Extracts explicit values from prompt first, then checks remembered answers.
     */
    fun generateFallbackQuestions(
        taskPrompt: String,
        rememberedAnswers: Map<String, String> = emptyMap()
    ): AniobGrillMeResult {
        if (isCompleteInstruction(taskPrompt)) {
            return AniobGrillMeResult(needsClarification = false)
        }
        val lower = AniobTextFold.fold(taskPrompt)
        val words = AniobTextFold.tokens(taskPrompt).toSet()
        val isQuestion = words.firstOrNull() in setOf("what", "why", "how", "who", "when", "where", "which", "explain", "define")
        val isRide = !isQuestion && words.any { it in setOf("cab", "ride", "taxi", "uber", "lyft") }
        val isSocial = !isQuestion && (words.any { it in setOf("linkedin", "connect", "connection", "invite", "twitter", "instagram", "facebook", "github", "follow") } || lower.contains("connection request"))
        val isMessage = !isQuestion && !isSocial && (
            words.any { it in setOf("message", "text", "sms", "whatsapp", "telegram") } ||
            (words.contains("send") && !lower.contains("connection") && !lower.contains("request") && !lower.contains("money") && !lower.contains("payment"))
        )
        val isAlarm = !isQuestion && words.any { it in setOf("alarm", "timer") }
        val questions = mutableListOf<GrillQuestion>()

        // Plan-7.7: Calibrated Asking ladder (extract -> vault -> device -> default -> ask)
        if (calibratedAsking != null) {
            val relevantSlots = mutableListOf<AniobCalibratedAsking.Slot>()
            if (isRide) {
                relevantSlots += listOf(
                    AniobCalibratedAsking.Slot(
                        id = "destination",
                        questionAsAsked = "What is your destination?",
                        options = emptyList(),
                        changesBehaviour = true,
                        default = null,
                        consequential = true,
                        extractor = { prompt ->
                            val destMatch = Regex("\\b(?:to|destination\\s+is)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:via|using|by|with)|$)").find(prompt.lowercase())
                            destMatch?.groupValues?.get(1)?.trim()?.takeIf { !it.startsWith("a ") && !it.startsWith("the ") }
                        }
                    ),
                    AniobCalibratedAsking.Slot(
                        id = "ride_type",
                        questionAsAsked = "Which ride tier do you prefer?",
                        options = listOf("Cheapest", "Standard Comfort", "XL / 6 Seats", "Fastest Arrival"),
                        changesBehaviour = true,
                        default = "Cheapest",
                        consequential = false
                    ),
                    AniobCalibratedAsking.Slot(
                        id = "provider_app",
                        questionAsAsked = "Preferred provider app?",
                        options = installedAppOptions(AppOptionCategory.RIDE),
                        changesBehaviour = true,
                        default = null,
                        consequential = false
                    )
                )
            } else if (isMessage) {
                relevantSlots += listOf(
                    AniobCalibratedAsking.Slot(
                        id = "recipient",
                        questionAsAsked = "Who would you like to message?",
                        options = emptyList(),
                        changesBehaviour = true,
                        default = null,
                        consequential = true,
                        extractor = { prompt ->
                            val toMatch = Regex("(?:message|text|send(?:\\s+a)?\\s+message\\s+to|send\\s+to)\\s+([a-zA-Z0-9_]+)").find(prompt.lowercase())
                            toMatch?.groupValues?.get(1)?.trim()?.takeIf { it !in listOf("a", "the", "me", "my", "app") }
                        }
                    ),
                    AniobCalibratedAsking.Slot(
                        id = "message_content",
                        questionAsAsked = "What message would you like to send?",
                        changesBehaviour = true,
                        default = null,
                        consequential = true,
                        extractor = { prompt ->
                            Regex("(?:text|message)\\s+[a-zA-Z0-9_]+\\s+(?:that\\s+)?(.+)").find(prompt.lowercase())?.groupValues?.get(1)
                        }
                    ),
                    AniobCalibratedAsking.Slot(
                        id = "app",
                        questionAsAsked = "Which messaging app?",
                        options = installedAppOptions(AppOptionCategory.MESSAGING),
                        changesBehaviour = true,
                        default = null,
                        consequential = false,
                        deviceFact = AniobCalibratedAsking.DeviceFact.DEFAULT_MESSAGING_APP
                    )
                )
            } else if (isAlarm) {
                relevantSlots += listOf(
                    AniobCalibratedAsking.Slot(
                        id = "time",
                        questionAsAsked = "What time should the alarm be set for?",
                        options = listOf("7:00 AM", "8:00 AM", "In 15 minutes"),
                        changesBehaviour = true,
                        default = null,
                        consequential = true,
                        extractor = { prompt ->
                            Regex("\\b(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?|in\\s+\\d+\\s+minutes?)\\b").find(prompt.lowercase())?.value
                        }
                    )
                )
            }

            if (relevantSlots.isNotEmpty()) {
                val plan = calibratedAsking.plan(relevantSlots, taskPrompt)
                val upfront = plan.upfront
                return if (upfront != null && !canBeDefaulted(GrillQuestion(upfront.id, upfront.questionAsAsked, upfront.options, defaultOption = upfront.default))) {
                    AniobGrillMeResult(
                        needsClarification = true,
                        questions = listOf(
                            GrillQuestion(
                                id = upfront.id,
                                question = upfront.questionAsAsked,
                                options = upfront.options,
                                allowFreeText = true,
                                defaultOption = upfront.default
                            )
                        )
                    )
                } else {
                    AniobGrillMeResult(needsClarification = false)
                }
            }
        }

        fun isRemembered(id: String): Boolean =
            rememberedAnswers.containsKey(id) || rememberedAnswers.containsKey("grill.$id")

        if (isRide) {
            val destMatch = Regex("\\b(?:to|destination\\s+is)\\s+([a-zA-Z0-9\\s]+?)(?:\\s+(?:via|using|by|with)|$)").find(lower)
            val hasExplicitDest = destMatch != null && destMatch.groupValues[1].trim().isNotBlank() &&
                !destMatch.groupValues[1].trim().startsWith("a ") && !destMatch.groupValues[1].trim().startsWith("the ")
            val knownDest = hasExplicitDest || isRemembered("destination")
            if (!knownDest) {
                questions.add(
                    GrillQuestion(
                        id = "destination",
                        question = "What is your destination?",
                        options = emptyList(),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
            if (!isRemembered("ride_type")) {
                questions.add(
                    GrillQuestion(
                        id = "ride_type",
                        question = "Which ride tier do you prefer?",
                        options = listOf("Cheapest", "Standard Comfort", "XL / 6 Seats", "Fastest Arrival"),
                        allowFreeText = false,
                        defaultOption = "Cheapest"
                    )
                )
            }
            if (!isRemembered("provider_app")) {
                questions.add(
                    GrillQuestion(
                        id = "provider_app",
                        question = "Preferred provider app?",
                        options = installedAppOptions(AppOptionCategory.RIDE),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
        } else if (isMessage) {
            val toMatch = Regex("(?:message|text|send(?:\\s+a)?\\s+message\\s+to|send\\s+to)\\s+([a-zA-Z0-9_]+)").find(lower)
            val hasExplicitRecipient = toMatch != null && toMatch.groupValues[1].trim().isNotBlank() &&
                toMatch.groupValues[1].trim() !in listOf("a", "the", "me", "my", "app")
            val knownRecipient = hasExplicitRecipient || isRemembered("recipient")
            if (!knownRecipient) {
                questions.add(
                    GrillQuestion(
                        id = "recipient",
                        question = "Who would you like to message?",
                        options = emptyList(),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
            val hasContent = lower.contains("saying") || lower.contains("\"") || lower.contains("'") ||
                Regex("(?:text|message)\\s+[a-zA-Z0-9_]+\\s+(?:that\\s+)?(.+)").find(lower)?.groupValues?.get(1)?.isNotBlank() == true
            if (!hasContent && !isRemembered("message_content")) {
                questions.add(
                    GrillQuestion(
                        id = "message_content",
                        question = "What message would you like to send?",
                        options = emptyList(),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
            val hasApp = lower.contains("whatsapp") || lower.contains("telegram") || lower.contains("sms") || lower.contains("messages")
            if (!hasApp && !isRemembered("app")) {
                questions.add(
                    GrillQuestion(
                        id = "app",
                        question = "Which messaging app?",
                        options = installedAppOptions(AppOptionCategory.MESSAGING),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
        } else if (isAlarm) {
            val timeMatch = Regex("\\b(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?|in\\s+\\d+\\s+minutes?)\\b").find(lower)
            val hasExplicitTime = timeMatch != null && timeMatch.value.isNotBlank()
            val knownTime = hasExplicitTime || isRemembered("time")
            if (!knownTime) {
                questions.add(
                    GrillQuestion(
                        id = "time",
                        question = "What time should the alarm be set for?",
                        options = listOf("7:00 AM", "8:00 AM", "In 15 minutes"),
                        allowFreeText = true,
                        defaultOption = null
                    )
                )
            }
        }

        val nonDefaulted = questions.filterNot(::canBeDefaulted).take(1)
        return if (nonDefaulted.isNotEmpty()) {
            AniobGrillMeResult(needsClarification = true, questions = nonDefaulted)
        } else {
            AniobGrillMeResult(needsClarification = false)
        }
    }

    private enum class AppOptionCategory { RIDE, MESSAGING }

    /**
     * Do not surface model-proposed contacts or destinations as if they came from this phone.
     * App choices are limited to launchable packages in the real registry; when none match, the
     * question remains free-text instead of fabricating a provider.
     */
    fun sanitizeQuestion(question: GrillQuestion): GrillQuestion = sanitizeQuestionOptions(question)

    private fun sanitizeQuestionOptions(question: GrillQuestion): GrillQuestion {
        val id = question.id.lowercase()
        val text = question.question.lowercase()
        val isDestinationOrContact = id.contains("destination") || id.contains("recipient") ||
            id.contains("contact") || id.contains("location") || text.contains("destination") ||
            text.contains("recipient") || text.contains("contact") || text.contains("where should i") ||
            text.contains("where do you want to go") || text.contains("who would you like to message")
        if (isDestinationOrContact) return question.copy(options = emptyList(), allowFreeText = true)

        val asksForApp = id.contains("app") || id.contains("provider") || id.contains("service") ||
            text.contains(" app") || text.contains("provider") || text.contains("service")
        if (!asksForApp) return question

        val category = when {
            id.contains("ride") || id.contains("provider") ||
                text.contains("ride") || text.contains("taxi") || text.contains("cab") -> AppOptionCategory.RIDE
            id.contains("message") || text.contains("message") || text.contains("sms") -> AppOptionCategory.MESSAGING
            else -> null
        }
        return question.copy(
            options = category?.let(::installedAppOptions).orEmpty(),
            allowFreeText = true
        )
    }

    private fun installedAppOptions(category: AppOptionCategory): List<String> {
        val cues = when (category) {
            AppOptionCategory.RIDE -> setOf("uber", "lyft", "ola", "rapido", "taxi", "cab", "ride")
            AppOptionCategory.MESSAGING -> setOf(
                "message", "messages", "messaging", "sms", "whatsapp", "telegram", "signal", "messenger", "imessage"
            )
        }
        return AniobAppRegistry.getInstance().all()
            .filter { app ->
                val searchable = (listOf(app.label, app.packageName) + app.aliases)
                    .joinToString(" ").lowercase()
                val tokens = searchable.split(Regex("[^a-z0-9]+"))
                tokens.any(cues::contains)
            }
            .map { it.label }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
            .take(8)
    }
}

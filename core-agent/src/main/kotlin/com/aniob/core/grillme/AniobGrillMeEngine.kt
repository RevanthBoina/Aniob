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

        // Deterministic check for simple self-contained intent commands
        if (isSelfContainedIntent(trimmed) || isCompleteInstruction(trimmed)) {
            return AniobGrillMeResult(needsClarification = false)
        }

        // A clarifying question is only ever asked on the model's explicit request. The keyword
        // ladder below never asks by itself: "send" in "send a connection request" used to open a
        // "What message would you like to send?" loop for a task that has no message at all. With no
        // model to judge (local-only, no cloud) nothing is asked up front; a value that turns out
        // to be missing mid-run is still requested by the executor through `FAIL: ASK_USER`.
        if (llmClient == null) return AniobGrillMeResult(needsClarification = false)

        // The ladder and the understander survive only as a cost filter: they decide whether the
        // model is consulted, so a clear single-goal command ("open youtube") never pays a model
        // round-trip before it starts. They can suppress the call; they cannot produce a question.
        val hint = generateFallbackQuestions(trimmed, rememberedAnswers)
        val understood = com.aniob.core.understander.AniobPromptUnderstanding.understand(trimmed)
        val worthAsking = hint.needsClarification || understood.confidence < 0.7 ||
            understood.speechAct == com.aniob.core.understander.AniobPromptUnderstanding.SpeechAct.UNCLEAR
        if (!worthAsking) return AniobGrillMeResult(needsClarification = false)
        return try {
            val res = llmClient.generateClarificationQuestions(trimmed)
            if (!res.needsClarification) return AniobGrillMeResult(needsClarification = false)
            val filtered = res.questions
                .filter { q ->
                    !rememberedAnswers.containsKey(q.id) && !rememberedAnswers.containsKey("grill.${q.id}")
                }
                .filter { it.defaultOption.isNullOrBlank() }
                .map(::sanitizeQuestionOptions)
                .take(1)
            if (filtered.isEmpty()) AniobGrillMeResult(needsClarification = false)
            else res.copy(questions = filtered)
        } catch (e: CancellationException) {
            throw e // Stop must stop; swallowing it here turned a cancelled run into a "questions" run
        } catch (e: Exception) {
            // No verdict from the model is not a verdict to ask: the run proceeds.
            AniobGrillMeResult(needsClarification = false)
        }
    }

    internal fun isCompleteInstruction(prompt: String): Boolean {
        val tokens = AniobTextFold.tokens(prompt).toSet()
        val quoted = Regex("""(["'])(.{1,280}?)\1""").find(prompt)
        if (tokens.any { it in setOf("send", "message", "text", "sms", "whatsapp", "telegram") } && quoted != null) {
            val after = prompt.substring(quoted.range.last + 1)
            val before = prompt.substring(0, quoted.range.first)
            if (Regex("""(?i)\bto\s+([\p{L}][\p{L}\p{N}_.@'-]*(?:\s+[\p{L}][\p{L}\p{N}_.@'-]*){0,3})\s*$""").containsMatchIn(after) ||
                Regex("""(?i)\b(?:send|text|message)\s+([\p{L}][\p{L}\p{N}_.@'-]*(?:\s+[\p{L}][\p{L}\p{N}_.@'-]*){0,3})\s+(?:saying|that)\s*$""").containsMatchIn(before)) return true
        }
        if (tokens.any { it in setOf("alarm", "timer") } &&
            Regex("""(?i)\b(?:at\s+)?\d{1,2}(?::\d{2})?\s*(?:am|pm)?\b|\bin\s+\d+\s*(?:seconds?|minutes?|hours?)\b""").containsMatchIn(prompt)) return true
        return tokens.any { it in setOf("call", "dial", "ring", "phone") } &&
            Regex("""(?i)\b(?:call|dial|ring|phone)\s+([\p{L}\p{N}][\p{L}\p{N}_.@' +()-]{0,60})\s*$""").containsMatchIn(prompt)
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

    /**
     * Keyword view of what a task *might* be missing. This is a hint for [evaluateTask] about
     * whether the model is worth consulting, never a decision to ask the user: nothing returned
     * here reaches the screen.
     */
    fun generateFallbackQuestions(
        taskPrompt: String,
        rememberedAnswers: Map<String, String> = emptyMap()
    ): AniobGrillMeResult {
        val lower = AniobTextFold.fold(taskPrompt)
        val words = AniobTextFold.tokens(taskPrompt).toSet()
        val isQuestion = words.firstOrNull() in setOf("what", "why", "how", "who", "when", "where", "which", "explain", "define")
        val isRide = !isQuestion && words.any { it in setOf("cab", "ride", "taxi", "uber", "lyft") }
        val isMessage = !isQuestion && words.any { it in setOf("message", "text", "send", "sms", "whatsapp") }
        val isAlarm = !isQuestion && words.any { it in setOf("alarm", "timer") }
        if (isCompleteInstruction(taskPrompt)) return AniobGrillMeResult(false)
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
                return if (upfront != null) {
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

        val unresolved = questions.filter { it.defaultOption.isNullOrBlank() }.take(1)
        return if (unresolved.isNotEmpty()) {
            AniobGrillMeResult(needsClarification = true, questions = unresolved)
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

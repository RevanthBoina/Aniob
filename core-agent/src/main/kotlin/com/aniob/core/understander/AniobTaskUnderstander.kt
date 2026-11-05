package com.aniob.core.understander

import com.aniob.core.domain.AniobPlan
import com.aniob.core.text.AniobTextFold

/**
 * Parses user prompt and generates execution decomposition plan.
 */
object AniobTaskUnderstander {

    data class ParsedIntent(
        val rawCommand: String,
        val targetAppPackage: String?,
        val targetCategory: String?,
        val primaryAction: String,
        val parameters: Map<String, String>
    )

    fun parse(command: String): ParsedIntent {
        val words = AniobTextFold.tokens(command).toSet()
        val lower = AniobTextFold.fold(command)

        val (pkg, category) = when {
            ("setting" in words || "settings" in words) -> "com.android.settings" to "SETTINGS"
            ("cab" in words || "uber" in words || "lyft" in words) -> null to "TRANSPORT"
            ("message" in words || "sms" in words) -> null to "COMMUNICATION"
            ("calculator" in words) -> "com.google.android.calculator" to "UTILITY"
            ("camera" in words) -> null to "CAMERA"
            else -> null to "GENERAL"
        }

        return ParsedIntent(
            rawCommand = command,
            targetAppPackage = pkg,
            targetCategory = category,
            primaryAction = if (lower.startsWith("open")) "OPEN" else "EXECUTE",
            parameters = emptyMap()
        )
    }

    fun createPlan(taskId: String, intent: ParsedIntent): AniobPlan {
        val estimatedSteps = when (intent.primaryAction) {
            "OPEN" -> 1
            else -> 4
        }
        return AniobPlan(
            taskId = taskId,
            goal = intent.rawCommand,
            estimatedSteps = estimatedSteps,
            strategySummary = "Execute intent ${intent.primaryAction} in category ${intent.targetCategory}"
        )
    }
}

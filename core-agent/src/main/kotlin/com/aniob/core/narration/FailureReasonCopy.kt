package com.aniob.core.narration

/**
 * Rewrites verifier/provider failure reason strings into plain language (UX-4 §3).
 *
 * The mapping is exhaustive over the reason vocabulary the verifier can emit; a test greps the
 * verifier for every reason string and asserts each one has a mapping, so no raw internal code
 * can reach a user surface.
 */
object FailureReasonCopy {

    private val RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("(?i)grounding[_ ]miss.*'([^']+)'") to { m -> "Couldn't find '${m.groupValues[1]}' on screen" },
        Regex("(?i)grounding[_ ]miss") to { _ -> "Couldn't find the target on screen" },
        Regex("(?i)no[- ]effect|no observable effect") to { _ -> "Nothing changed on screen after the action" },
        Regex("(?i)expected text '([^']+)' not present") to { m -> "Expected to see '${m.groupValues[1]}', but it wasn't there" },
        Regex("(?i)expected package '([^']+)' not foreground") to { m -> "'${m.groupValues[1]}' didn't open" },
        Regex("(?i)expected at least (\\d+) steps") to { _ -> "The task ended before it could finish" },
        Regex("(?i)model file missing|local model") to { _ -> "The on-device model isn't installed yet" },
        Regex("(?i)network|offline") to { _ -> "No internet connection" },
        Regex("(?i)accessibility.*(disabled|not connected)|service disabled") to { _ -> "Accessibility access is turned off" },
        Regex("(?i)timeout|timed out") to { _ -> "The app took too long to respond" },
        Regex("(?i)loop[_ ]detected|watchdog") to { _ -> "Aniob got stuck repeating an action" },
        Regex("(?i)payment|checkout|purchase|transaction") to { _ -> "Aniob doesn't do payments" },
        Regex("(?i)destructive|delete account|factory reset") to { _ -> "That action could erase your data, so Aniob stopped" },
        Regex("(?i)sensitive") to { _ -> "That field holds a secret Aniob never reads" },
        Regex("(?i)confirm") to { _ -> "The step needs your approval" }
    )

    /** Returns plain language for [reason]; unknown codes fall back to a non-jargon sentence. */
    fun humanize(reason: String?): String {
        if (reason.isNullOrBlank()) return "Something went wrong"
        RULES.forEach { (pattern, render) ->
            val match = pattern.find(reason)
            if (match != null) return render(match)
        }
        return "Aniob couldn't finish this step"
    }

    /** Human label for a provider code, used on stats and evidence cards (UX-5 §3). */
    fun providerLabel(code: String): String = when (code.uppercase()) {
        "FASTPATH" -> "Instant replay"
        "REMEMBER" -> "Remembered replay"
        "APP_LAUNCH" -> "App shortcut"
        "LOCAL_SLM" -> "On-device"
        "OMNIROUTE_CLOUD" -> "Cloud"
        "INTENT" -> "System shortcut"
        "SKILL" -> "Saved skill"
        "MOCK" -> "Simulated"
        "DOCTOR" -> "Check-up"
        "EXTERNAL_AI" -> "AI answer"
        "NONE" -> "None"
        else -> code.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /**
     * U-03: the old copy promised "waiting a moment before retrying" while the code retried instantly (or never).
     * Only claim a retry when one is really scheduled.
     */
    fun gatewayRateLimited(retryAfterMs: Long?, willRetryHere: Boolean, localFallbackReady: Boolean): String {
        val secs = retryAfterMs?.let { (it + 999) / 1000 }
        return when {
            willRetryHere && secs != null -> "The cloud model asked me to slow down. Retrying in ${secs}s."
            localFallbackReady -> "The cloud model is rate-limiting me" + (secs?.let { " (try again in ${it}s)" } ?: "") + ". Continuing on the on-device model."
            else -> "The cloud model is rate-limiting me" + (secs?.let { ". Try again in ${it}s" } ?: ". Try again in a minute") + ", or install an on-device model in Models."
        }
    }

    /** Human-readable explanation for HTTP download failures (UX legible failure). */
    fun downloadError(code: Int, modelName: String = "model"): String = when (code) {
        401 -> "This model requires a sign-in. Try a different model."
        403 -> "Access to this model is restricted. Choose an alternative below."
        404 -> "The model file wasn't found at the source. The catalog may be out of date."
        416 -> "The partial download got out of sync. Starting fresh."
        429 -> "The model host is rate-limiting downloads. Try again in a minute."
        in 500..599 -> "The model host is having trouble right now. Try again later."
        else -> "Download stopped (code $code). Check your connection and try again."
    }
}

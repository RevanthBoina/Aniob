package com.aniob.core.workflow

/**
 * Result of the workflow routing decision.
 */
sealed class WorkflowDecision {
    data class DirectAction(val action: WorkflowAction) : WorkflowDecision()
    data class NeedsChooser(
        val candidates: List<Destination>,
        val prompt: String,
        val briefText: String? = null
    ) : WorkflowDecision()
    data class NeedsClarification(val question: String) : WorkflowDecision()
    data class DelegateToExisting(val prompt: String) : WorkflowDecision()
}

/**
 * Goal & capability router for everyday workflows.
 * Zero android.* imports.
 */
object WorkflowRouter {

    /**
     * Inspects natural language prompt and makes a routing decision.
     */
    fun route(
        prompt: String,
        taskId: String = "wf_${System.currentTimeMillis()}",
        userPreferredDestination: Destination? = null,
        isAppLaunchable: (String) -> Boolean = { packageName ->
            com.aniob.core.knowledge.AniobAppRegistry.getInstance().isLaunchable(packageName)
        }
    ): WorkflowDecision {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) {
            return WorkflowDecision.DelegateToExisting(trimmed)
        }

        val normalized = trimmed.lowercase()

        // 1. Distinguish explaining vs building
        if (isExplanationRequest(normalized)) {
            // Explaining how to build a website/app is informational Q&A -> existing flow
            return WorkflowDecision.DelegateToExisting(trimmed)
        }

        // 2. Direct website URL opening or browsing
        val extractedUrl = extractUrl(trimmed)
        if (extractedUrl != null) {
            val validatedUrl = validateAndSanitizeUrl(extractedUrl)
            if (validatedUrl != null) {
                val host = extractHost(validatedUrl)
                val destination = Destination(
                    handler = host,
                    url = validatedUrl,
                    category = DestinationCategory.WEBSITE,
                    provenance = DestinationProvenance.USER_INPUT
                )
                val brief = if (isPureUrlOpen(trimmed, extractedUrl)) null else trimmed
                val action = WorkflowAction.OpenWebsite(
                    actionId = "action_${System.currentTimeMillis()}",
                    destination = destination,
                    briefText = brief
                )
                return WorkflowDecision.DirectAction(action)
            } else {
                return WorkflowDecision.NeedsClarification("The provided website link has an invalid or insecure address format.")
            }
        }

        // 2b. Named app direct open/launch -> check AppCatalog
        val openAppMatch = Regex("""^(?:open|launch|start)\s+([a-zA-Z0-9\s]+)$""", RegexOption.IGNORE_CASE)
            .find(trimmed)
        if (openAppMatch != null) {
            val candidate = openAppMatch.groupValues[1].trim()
            val candidateLower = candidate.lowercase()
            if (candidateLower != "chatgpt" && candidateLower != "notes" && candidateLower != "website" && candidateLower != "browser") {
                val appResolution = com.aniob.core.knowledge.AniobAppCatalog.getInstance().resolveApp(candidate)
                when (appResolution) {
                    is com.aniob.core.knowledge.AniobAppCatalog.AppResolution.ExactMatch -> {
                        val entry = appResolution.entry
                        if (!isAppLaunchable(entry.packageName)) {
                            return WorkflowDecision.NeedsClarification(
                                "I couldn't open '$candidate' because it is not available on this device. Which installed app or website should I use?"
                            )
                        }
                        val destination = Destination(
                            handler = entry.packageName,
                            category = DestinationCategory.APP,
                            label = entry.appName,
                            provenance = DestinationProvenance.SYSTEM_RESOLVED
                        )
                        return WorkflowDecision.DirectAction(
                            WorkflowAction.OpenApp(
                                actionId = "action_${System.currentTimeMillis()}",
                                destination = destination
                            )
                        )
                    }
                    is com.aniob.core.knowledge.AniobAppCatalog.AppResolution.Ambiguous -> {
                        val candidates = appResolution.candidates
                            .filter { isAppLaunchable(it.packageName) }
                            .map {
                                Destination(
                                    handler = it.packageName,
                                    category = DestinationCategory.APP,
                                    provenance = DestinationProvenance.SYSTEM_RESOLVED,
                                    label = it.appName
                                )
                            }
                        when (candidates.size) {
                            0 -> return WorkflowDecision.NeedsClarification(
                                "I couldn't find an installed app called '$candidate'. Which installed app or website should I use?"
                            )
                            1 -> return WorkflowDecision.DirectAction(
                                WorkflowAction.OpenApp(
                                    actionId = "action_${System.currentTimeMillis()}",
                                    destination = candidates.single()
                                )
                            )
                            else -> return WorkflowDecision.NeedsChooser(candidates = candidates, prompt = trimmed)
                        }
                    }
                    com.aniob.core.knowledge.AniobAppCatalog.AppResolution.NotFound -> {
                        return WorkflowDecision.NeedsClarification(
                            "I couldn't find an installed app called '$candidate'. Which installed app or website should I use?"
                        )
                    }
                }
            }
        }

        // 3. Hosted reasoning requests
        if (isHostedReasoningRequest(normalized)) {
            // Never silently select a hosted destination. The intent resolver has already tried
            // installed apps and supported system/web intents; absent an explicit user choice,
            // the existing task/answer path owns the request.
            val destination = userPreferredDestination ?: return WorkflowDecision.DelegateToExisting(trimmed)
            val action = WorkflowAction.OpenWebsite(
                actionId = "action_${System.currentTimeMillis()}",
                destination = destination,
                briefText = trimmed
            )
            return WorkflowDecision.DirectAction(action)
        }

        // 4. Website / app creation requests
        if (isWebsiteCreationRequest(normalized)) {
            if (userPreferredDestination != null) {
                val action = WorkflowAction.OpenWebsite(
                    actionId = "action_${System.currentTimeMillis()}",
                    destination = userPreferredDestination,
                    briefText = trimmed
                )
                return WorkflowDecision.DirectAction(action)
            }
            return WorkflowDecision.NeedsClarification(
                "Which app or website would you like me to use? I won't choose a service for you."
            )
        }

        // 5. Note taking / editing requests
        if (isNoteCreationRequest(normalized)) {
            val noteText = extractNoteContent(normalized, trimmed)
            return when (val notes = com.aniob.core.knowledge.AniobAppCatalog.getInstance().resolveApp("notes")) {
                is com.aniob.core.knowledge.AniobAppCatalog.AppResolution.ExactMatch -> {
                    if (!isAppLaunchable(notes.entry.packageName)) {
                        WorkflowDecision.NeedsClarification(
                            "I couldn't open a notes app on this device. Which installed app or website should I use?"
                        )
                    } else {
                        WorkflowDecision.DirectAction(
                            WorkflowAction.OpenApp(
                                actionId = "action_${System.currentTimeMillis()}",
                                destination = Destination(
                                    handler = notes.entry.packageName,
                                    category = DestinationCategory.APP,
                                    provenance = DestinationProvenance.SYSTEM_RESOLVED,
                                    label = notes.entry.appName
                                ),
                                payload = null,
                                draftText = noteText
                            )
                        )
                    }
                }
                is com.aniob.core.knowledge.AniobAppCatalog.AppResolution.Ambiguous -> {
                    val candidates = notes.candidates
                        .filter { isAppLaunchable(it.packageName) }
                        .map {
                            Destination(
                                handler = it.packageName,
                                category = DestinationCategory.APP,
                                provenance = DestinationProvenance.SYSTEM_RESOLVED,
                                label = it.appName
                            )
                        }
                    when (candidates.size) {
                        0 -> WorkflowDecision.NeedsClarification(
                            "I couldn't find an installed notes app. Which app or website should I use?"
                        )
                        1 -> WorkflowDecision.DirectAction(
                            WorkflowAction.OpenApp(
                                actionId = "action_${System.currentTimeMillis()}",
                                destination = candidates.single(),
                                draftText = noteText
                            )
                        )
                        else -> WorkflowDecision.NeedsChooser(candidates = candidates, prompt = trimmed)
                    }
                }
                com.aniob.core.knowledge.AniobAppCatalog.AppResolution.NotFound ->
                    WorkflowDecision.NeedsClarification(
                        "I couldn't find an installed notes app. Which app or website should I use?"
                    )
            }
        }

        // Fallback: delegate to existing pipeline (Q&A or screen automation)
        return WorkflowDecision.DelegateToExisting(trimmed)
    }

    private fun isPureUrlOpen(prompt: String, url: String): Boolean {
        val trimmed = prompt.trim()
        if (trimmed.equals(url, ignoreCase = true)) return true
        val withoutUrl = trimmed.replace(url, "").trim().lowercase()
        return withoutUrl.isEmpty() ||
            withoutUrl in setOf("open", "go to", "browse", "visit", "launch", "navigate to", "open link", "view", "open in browser")
    }

    private fun isSummarizeRequest(normalized: String): Boolean {
        return normalized.startsWith("summarize ") ||
            normalized.startsWith("summarise ") ||
            normalized.startsWith("give me a summary of ") ||
            normalized.startsWith("tldr ") ||
            normalized.contains("summarize")
    }

    private fun isExplanationRequest(normalized: String): Boolean {
        return normalized.startsWith("how to ") ||
            normalized.startsWith("explain ") ||
            normalized.startsWith("what is ") ||
            normalized.startsWith("tell me about ") ||
            normalized.contains("how do i create a website") ||
            normalized.contains("how does a website work")
    }

    private fun isWebsiteCreationRequest(normalized: String): Boolean {
        return (normalized.startsWith("create a website") ||
            normalized.startsWith("build a website") ||
            normalized.startsWith("make a website") ||
            normalized.startsWith("create website") ||
            normalized.startsWith("build website") ||
            normalized.startsWith("make website") ||
            normalized.startsWith("create a web app") ||
            normalized.startsWith("build a web app") ||
            normalized.startsWith("make a web app")) &&
            !normalized.startsWith("how to")
    }

    private fun isHostedReasoningRequest(normalized: String): Boolean {
        if (isInformationalAiQuestion(normalized)) {
            return false
        }
        return normalized.startsWith("ask chatgpt") ||
            normalized.startsWith("use chatgpt") ||
            normalized.startsWith("open chatgpt") ||
            normalized.startsWith("ask reasoning") ||
            normalized.matches(Regex("""^chatgpt\s+(?:to|please|help|can you)\b.*""")) ||
            normalized.contains("with chatgpt") ||
            normalized.contains("using chatgpt")
    }

    private fun isInformationalAiQuestion(normalized: String): Boolean {
        return normalized.startsWith("what is ") ||
            normalized.startsWith("who made ") ||
            normalized.startsWith("is chatgpt ") ||
            normalized.startsWith("how does chatgpt ") ||
            normalized.startsWith("can chatgpt ") ||
            normalized.startsWith("compare ") ||
            normalized.startsWith("which is better ") ||
            normalized.startsWith("difference between ") ||
            normalized.contains("what is chatgpt") ||
            normalized.contains("is chatgpt free") ||
            normalized.contains("chatgpt vs") ||
            normalized.contains("chatgpt versus")
    }

    private fun isNoteCreationRequest(normalized: String): Boolean {
        return normalized.startsWith("create a note") ||
            normalized.startsWith("create note") ||
            normalized.startsWith("take a note") ||
            normalized.startsWith("take note") ||
            normalized.startsWith("open notes") ||
            normalized.startsWith("write a note")
    }

    private fun extractNoteContent(normalized: String, original: String): String? {
        val prefixes = listOf(
            "create a note to ", "create note to ", "take a note to ",
            "take note to ", "write a note to ", "create a note that ",
            "create note that ", "take a note that ", "write a note that ",
            "create a note: ", "create note: ", "take a note: ", "write a note: ",
            "create a note ", "create note ", "take a note ", "take note ",
            "write a note "
        )
        for (prefix in prefixes) {
            if (normalized.startsWith(prefix)) {
                val content = original.substring(prefix.length).trim()
                if (content.isNotEmpty()) return content
            }
        }
        return null
    }

    private fun sha256(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts URL from original input to preserve case sensitivity of paths and query parameters.
     */
    fun extractUrl(originalInput: String): String? {
        val tokens = originalInput.split("\\s+".toRegex())
        for (token in tokens) {
            val clean = token.trim().trimEnd('.', ',', ';', '!', '?')
            if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
                return clean
            }
            if (clean.startsWith("www.", ignoreCase = true)) {
                return "https://$clean"
            }
        }
        return null
    }

    /**
     * Validates and sanitizes a URL.
     * Enforces HTTPS, rejects control characters, invalid schemes, and embedded credentials.
     */
    fun validateAndSanitizeUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null

        val lower = trimmed.lowercase()
        val hasAllowedScheme = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")
        if (!hasAllowedScheme) {
            return null
        }

        // Check for control characters
        for (c in trimmed) {
            if (c.code in 0..31 || c.code == 127) return null
        }

        // Scheme check
        if (lower.startsWith("javascript:") || lower.startsWith("file:") || lower.startsWith("content:") || lower.startsWith("data:")) {
            return null
        }

        val targetUrl = if (lower.startsWith("http://")) {
            "https://" + trimmed.substring(7)
        } else if (!lower.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }

        // Check for embedded credentials user:pass@ and invalid host authority
        val afterScheme = targetUrl.substring("https://".length)
        val hostPart = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (hostPart.contains('@') || hostPart.any { it.isWhitespace() } || hostPart.isBlank()) {
            return null
        }
        if (!hostPart.matches(Regex("""^[a-zA-Z0-9.\-]+(?::\d+)?$"""))) {
            return null
        }
        if (!hostPart.contains('.') && !hostPart.startsWith("localhost")) {
            return null
        }

        return targetUrl
    }

    private fun extractHost(url: String): String {
        val afterScheme = if (url.startsWith("https://", ignoreCase = true)) {
            url.substring(8)
        } else if (url.startsWith("http://", ignoreCase = true)) {
            url.substring(7)
        } else {
            url
        }
        return afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    }
}

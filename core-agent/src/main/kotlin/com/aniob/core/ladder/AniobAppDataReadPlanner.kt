package com.aniob.core.ladder

import com.aniob.core.intent.AniobAppDataRead
import com.aniob.core.intent.AppDataReadRequest
import com.aniob.core.knowledge.AniobAppRegistry
import com.aniob.core.knowledge.AppLookup
import com.aniob.core.knowledge.RegisteredApp

/**
 * Where an app-data read request has to be carried out: an **installed** app, or the web page of
 * that service when no installed app answers.
 *
 * [packageName] is only ever a package the platform said is launchable, and [webUrl] is only ever
 * the documented page of the named service. A destination is a *lookup*: nothing here calls a
 * model, guesses a package, or invents a URL.
 */
data class AppDataReadDestination(
    val label: String,
    val packageName: String? = null,
    val launchActivity: String? = null,
    val webUrl: String? = null,
    /** [SOURCE_NAMED_APP], [SOURCE_ROLE], [SOURCE_WEB]. */
    val source: String,
    val reason: String
) {
    val isWeb: Boolean get() = webUrl != null

    companion object {
        /** The user named the app ("… in Gmail"). */
        const val SOURCE_NAMED_APP = "named_app"

        /** No app was named; the platform's own role answered ("the email app"). */
        const val SOURCE_ROLE = "role"

        /** No installed app answers for this data; the service's web page is the destination. */
        const val SOURCE_WEB = "web"
    }
}

/** Answer of [AniobAppDataReadPlanner]: a destination, a question, or an honest no. */
sealed class AppDataReadPlan {
    data class Ready(val request: AppDataReadRequest, val destination: AppDataReadDestination) : AppDataReadPlan()

    /** Two live apps answer the same name: asking is better than guessing which inbox was meant. */
    data class Ambiguous(val request: AppDataReadRequest, val candidates: List<RegisteredApp>) : AppDataReadPlan()

    /** Nothing installed and no known page: the read cannot be attempted without the user's help. */
    data class Unresolved(val request: AppDataReadRequest, val reason: String) : AppDataReadPlan()
}

/**
 * Chooses the destination for a request to read data out of an app.
 *
 * The rules, in order (each one is a field failure this replaces):
 *
 *  1. **a named app wins** — "summarize my emails in Gmail" opens Gmail when it is installed,
 *     instead of a browser tab or a model-chosen app;
 *  2. **otherwise the platform's role** — "check my emails" with no app named opens the device's
 *     own email app ([AniobAppRegistry.ROLE_EMAIL]); the agent never asks the user to open it;
 *  3. **otherwise the service page** — Gmail/Outlook with no installed app is still readable in the
 *     browser, so the web route is used instead of failing. This is the same "supported website
 *     route" the launch planner already offers for an uninstalled site;
 *  4. an ambiguity, or nothing at all, is reported as such — never silently replaced by another app.
 *
 * Pure Kotlin/JVM: the registry and the launchable check are injected, so this is unit-testable
 * without a device.
 */
object AniobAppDataReadPlanner {

    /** The documented web page of a service whose app is not installed. Nothing is guessed. */
    private val KNOWN_SERVICE_PAGES: Map<String, String> = mapOf(
        "gmail" to "https://mail.google.com",
        "outlook" to "https://outlook.live.com/mail/",
        "yahoo mail" to "https://mail.yahoo.com",
        "protonmail" to "https://mail.proton.me",
        "whatsapp" to "https://web.whatsapp.com",
        "telegram" to "https://web.telegram.org",
        "slack" to "https://app.slack.com/client",
        "messenger" to "https://www.messenger.com",
        "instagram" to "https://www.instagram.com/direct/inbox/",
        "facebook" to "https://www.facebook.com/messages"
    )

    /** Default page per data kind when the user named no app at all. Keyed by the registry role. */
    private val KIND_PAGES: Map<String, String> = mapOf(
        "email" to "https://mail.google.com",
        "messages" to "https://messages.google.com/web/conversations"
    )

    /**
     * Resolves the destination for [request].
     *
     * @param isLaunchable the platform's installed-check; a stale registry entry must never dispatch.
     */
    fun plan(
        request: AppDataReadRequest,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance(),
        isLaunchable: (String) -> Boolean = { true }
    ): AppDataReadPlan {
        val hint = request.appHint

        // A registry that has not been hydrated yet cannot say whether the named app is installed.
        // Opening a browser page in its place would be a guess, so the request is left to the
        // observed step loop instead of being satisfied by a website.
        if (hint != null && registry.isEmpty()) {
            return AppDataReadPlan.Unresolved(request, "The installed-app list is still loading.")
        }

        // 1. The app the user named.
        if (hint != null) {
            when (val lookup = registry.resolve(hint)) {
                is AppLookup.Match -> {
                    val app = lookup.app
                    if (isLaunchable(app.packageName)) {
                        return AppDataReadPlan.Ready(
                            request,
                            AppDataReadDestination(
                                label = app.label,
                                packageName = app.packageName,
                                launchActivity = app.launchActivity,
                                source = AppDataReadDestination.SOURCE_NAMED_APP,
                                reason = "'${app.label}' is installed and owns ${request.noun}s (0 model calls)"
                            )
                        )
                    }
                    // Installed but not launchable: the service page is still readable.
                    servicePage(hint)?.let { url ->
                        return webPlan(request, url, "The $hint app cannot be launched; reading its web page instead")
                    }
                    return AppDataReadPlan.Unresolved(
                        request,
                        "The $hint app is installed but cannot be launched, and it has no known web page."
                    )
                }
                is AppLookup.Ambiguous -> {
                    val launchable = lookup.candidates.filter { isLaunchable(it.packageName) }
                    if (launchable.isNotEmpty()) return AppDataReadPlan.Ambiguous(request, launchable)
                }
                is AppLookup.Miss -> Unit
            }
            servicePage(hint)?.let { url ->
                return webPlan(request, url, "$hint is not installed; its web page is the supported route")
            }
        }

        // 2. The platform's role for this kind of data: "check my emails" with no app named.
        request.kind.registryRole?.let { role ->
            val app = registry.appForRole(role)
            if (app != null && isLaunchable(app.packageName)) {
                return AppDataReadPlan.Ready(
                    request,
                    AppDataReadDestination(
                        label = app.label,
                        packageName = app.packageName,
                        launchActivity = app.launchActivity,
                        source = AppDataReadDestination.SOURCE_ROLE,
                        reason = "'${app.label}' is this device's ${role} app (0 model calls)"
                    )
                )
            }
        }

        // 3. The service page for this kind of data, when it is documented.
        KIND_PAGES[request.kind.registryRole]?.let { url ->
            return webPlan(
                request, url,
                "No installed app owns ${request.noun}s; opening the service page in the browser"
            )
        }

        return AppDataReadPlan.Unresolved(
            request,
            "No installed app and no known web page can read ${request.noun}s on this device. " +
                "Install one, or tell me which app to use."
        )
    }

    private fun webPlan(request: AppDataReadRequest, url: String, reason: String) = AppDataReadPlan.Ready(
        request,
        AppDataReadDestination(
            label = request.appHint?.let(::titleCase) ?: "Web",
            webUrl = url,
            source = AppDataReadDestination.SOURCE_WEB,
            reason = reason
        )
    )

    private fun servicePage(name: String): String? = KNOWN_SERVICE_PAGES[name]

    private fun titleCase(text: String): String =
        text.split(' ').filter { it.isNotBlank() }.joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }

    /** Convenience for callers that only have the prompt. */
    fun planForPrompt(
        prompt: String,
        registry: AniobAppRegistry = AniobAppRegistry.getInstance(),
        isLaunchable: (String) -> Boolean = { true }
    ): AppDataReadPlan? = AniobAppDataRead.detect(prompt)?.let { plan(it, registry, isLaunchable) }
}

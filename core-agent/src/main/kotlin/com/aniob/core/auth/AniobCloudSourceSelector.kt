package com.aniob.core.auth

/** Which cloud the user picked in Settings. OmniRoute stays the default. */
enum class AniobCloudSource(val prefValue: String) {
    OMNIROUTE("omniroute"),
    ACCOUNT("account");

    companion object {
        fun fromPref(value: String?): AniobCloudSource =
            entries.firstOrNull { it.prefValue == value } ?: OMNIROUTE
    }
}

sealed interface AniobCloudSelection {
    data class Use(val source: AniobCloudSource) : AniobCloudSelection

    /** No usable cloud. [reason] is shown to the user as the action to take. */
    data class Unavailable(val source: AniobCloudSource, val reason: String) : AniobCloudSelection
}

/**
 * Decides, before any request, which cloud source serves a step. Pure.
 *
 * The user's explicit choice is honoured and never silently swapped: picking the account never
 * falls over to the gateway (a different data destination with a different key). If the account
 * is not signed in the cloud is *unavailable*, and the existing routing then decides, per mode,
 * whether the step can drop to the on-device model (see [allowsLocalFallback]).
 */
object AniobCloudSourceSelector {
    const val SIGN_IN_AGAIN = "Sign in again in Settings."
    const val GATEWAY_NOT_SET = "Add the gateway address in Settings."

    fun select(
        preference: AniobCloudSource,
        authState: AniobAuthState,
        accountFeatureAvailable: Boolean,
        omniRouteConfigured: Boolean
    ): AniobCloudSelection = when (preference) {
        AniobCloudSource.ACCOUNT ->
            if (accountFeatureAvailable && authState is AniobAuthState.SignedIn) {
                AniobCloudSelection.Use(AniobCloudSource.ACCOUNT)
            } else {
                AniobCloudSelection.Unavailable(AniobCloudSource.ACCOUNT, SIGN_IN_AGAIN)
            }
        AniobCloudSource.OMNIROUTE ->
            if (omniRouteConfigured) AniobCloudSelection.Use(AniobCloudSource.OMNIROUTE)
            else AniobCloudSelection.Unavailable(AniobCloudSource.OMNIROUTE, GATEWAY_NOT_SET)
    }

    /** `cloud-only` is an explicit promise never to run on-device; every other mode may degrade. */
    fun allowsLocalFallback(routerMode: String): Boolean = routerMode.lowercase() != "cloud-only"
}

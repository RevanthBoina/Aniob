package com.aniob.core.auth

/**
 * Serialises tokens and the pending sign-in record to bytes (UTF-8 JSON) for encryption at rest.
 * Pure JVM so the round trip is testable without a device. Decoding never throws: anything it
 * cannot read is null, which callers treat as "nothing stored".
 */
object AniobAuthCodec {
    private const val VERSION = 1L

    fun encode(tokens: AniobAuthTokens): ByteArray = AniobAuthJson.toJson(
        linkedMapOf(
            "v" to VERSION,
            "access" to tokens.accessToken,
            "refresh" to tokens.refreshToken,
            "id" to tokens.idToken,
            "exp" to tokens.expiresAtEpochMs,
            "account" to tokens.accountId,
            "plan" to tokens.planType,
            "email" to tokens.email
        )
    ).toByteArray(Charsets.UTF_8)

    fun decodeTokens(bytes: ByteArray): AniobAuthTokens? {
        val json = AniobAuthJson.parseObject(String(bytes, Charsets.UTF_8)) ?: return null
        if (AniobAuthJson.long(json["v"]) != VERSION) return null
        return AniobAuthTokens(
            accessToken = AniobAuthJson.str(json["access"]) ?: return null,
            refreshToken = AniobAuthJson.str(json["refresh"]) ?: return null,
            idToken = AniobAuthJson.str(json["id"]),
            expiresAtEpochMs = AniobAuthJson.long(json["exp"]) ?: return null,
            accountId = AniobAuthJson.str(json["account"]),
            planType = AniobAuthJson.str(json["plan"]),
            email = AniobAuthJson.str(json["email"])
        )
    }

    fun encode(pending: AniobPendingSignIn): ByteArray = AniobAuthJson.toJson(
        linkedMapOf("v" to VERSION, "verifier" to pending.verifier, "state" to pending.state, "at" to pending.startedAtMs)
    ).toByteArray(Charsets.UTF_8)

    fun decodePending(bytes: ByteArray): AniobPendingSignIn? {
        val json = AniobAuthJson.parseObject(String(bytes, Charsets.UTF_8)) ?: return null
        if (AniobAuthJson.long(json["v"]) != VERSION) return null
        return AniobPendingSignIn(
            verifier = AniobAuthJson.str(json["verifier"]) ?: return null,
            state = AniobAuthJson.str(json["state"]) ?: return null,
            startedAtMs = AniobAuthJson.long(json["at"]) ?: return null
        )
    }
}

/** What the account card shows. A pure function of the auth state, so every transition is testable. */
sealed interface AniobAccountScreenState {
    /** Not signed in: explain what will happen and offer the button. */
    data object Intro : AniobAccountScreenState
    data object Waiting : AniobAccountScreenState
    data class Connected(val label: String, val planType: String?) : AniobAccountScreenState

    /** [retry] is true when "Try again" makes sense. [workspaceHint] selects the account/workspace text. */
    data class Problem(val messageKey: String, val retry: Boolean, val workspaceHint: Boolean) : AniobAccountScreenState

    companion object {
        fun from(state: AniobAuthState): AniobAccountScreenState = when (state) {
            AniobAuthState.SignedOut -> Intro
            is AniobAuthState.SigningIn -> Waiting
            is AniobAuthState.SignedIn -> Connected(state.displayLabel, state.planType)
            is AniobAuthState.Expired -> Problem(state.reason.messageKey, retry = true, workspaceHint = false)
            is AniobAuthState.Failed -> Problem(
                state.error.messageKey,
                retry = state.error !is AniobAuthError.NotConfigured,
                workspaceHint = state.error is AniobAuthError.WorkspaceOrAccountDenied
            )
        }
    }
}

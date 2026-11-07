# ChatGPT account connection

Account sign-in is an **opt-in, not yet live-validated integration**. It connects the model provider; it does not grant Android phone-control permissions. USB debugging, Wireless debugging, pairing codes, root and a gateway API key are not required for this sign-in flow.

## Enable deliberately

Copy `.env.example` to the ignored `.env`. Never leave a property empty: the secrets plugin may generate invalid BuildConfig output. Use `UNSET` or `NONE` instead.

- `ACCOUNT_AUTH_CLIENT_ID=UNSET` (default): feature disabled and hidden.
- `ACCOUNT_AUTH_CLIENT_ID=dynamic_agent_client`: explicitly select dynamic registration, **only after confirming provider permission and eligibility for this app**.
- An authorized, previously registered static client ID can instead be supplied. Do not borrow another application's identity or use a confidential client secret in the APK.

Dynamic registration is not automatically enabled when configuration is missing. The source describes a registration protocol; it does not establish that the provider supports this commercial app or every account/workspace. Keep production rollout disabled until that is verified.

The default profile uses:

| Purpose | Address/value |
| --- | --- |
| Issuer | `https://auth.openai.com` |
| Browser authorization | `https://auth.openai.com/api/accounts/authorize` |
| Token service | `https://auth.openai.com/api/accounts/oauth/token` |
| Revocation | `https://auth.openai.com/api/accounts/oauth/revoke` |
| Issuer keys | `https://auth.openai.com/.well-known/jwks.json` |
| Resource / inference base | `https://api.openai.com/v1` (`/responses` appended for inference) |
| Redirect | `http://127.0.0.1:1455/auth/callback` |
| Scopes | `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct` |

Authorize/token/inference/redirect/scope fields are build-configurable using the corresponding `ACCOUNT_AUTH_*` properties. Issuer/resource are explicit core configuration fields, wired to the profile above by the Android container. Changing endpoints alone is not sufficient to define a different provider profile. HTTPS endpoints reject embedded credentials, query strings and fragments. The callback must be IPv4 loopback, have an explicit valid port, and use `/auth/callback`; a fixed-port conflict is reported, not silently worked around.

`ACCOUNT_AUTH_EXTRA_PARAMS=NONE` and `ACCOUNT_AUTH_BETA_HEADER=NONE` are the defaults. Legacy CLI-specific extras/experimental headers are not assumed necessary for the account-resource flow. Protocol fields (including state, nonce and PKCE) cannot be overridden by extra query parameters. The container supplies a stable per-install host ID; the bootstrap request uses Aniob's own application-name hint.

## Session and security contract

1. `AniobAuthRepository` owns all token and registration persistence. Other code uses short-lived `obtainAccess()` grants, not direct token-store reads.
2. Before opening the external browser, the flow saves an encrypted pending attempt containing state, PKCE verifier, nonce and requested client ID, then binds the local listener. No embedded WebView is used.
3. The listener accepts a matching-state callback. Wrong-state, duplicate-field and unrelated requests cannot consume the legitimate attempt. Header reads are bounded. A final static browser page is returned only after the outcome is known; callback secrets are never reflected.
4. First registration requires a returned client ID. A known registration rejects a conflicting ID. The issued ID is saved before exchange and used in subsequent form-encoded code exchanges, refreshes and revocation.
5. The ID token must pass RS256/JWKS signature, issuer, audience, expiry, subject and nonce checks. JWKS retrieval is bounded, cached, and rate-limited for unknown-key refresh. A missing verifier or verification failure is fail-closed. Unsigned payload decoding is not identity verification.
6. The granted `chatgpt.tokens.use.direct` scope is required, not merely requested. Session records include the issued ID and granted scopes. A refresh omitting scopes retains the previous grant; an explicit loss of plan scope ends the usable session.
7. Concurrent refreshes are serialized. A rotated refresh token is persisted before being handed to callers. Inference retries once after a 401. Only terminal token errors clear a refresh session; configuration/transient failures retain it.
8. Tokens, pending attempts and the registration ID use private Keystore-encrypted, backup-excluded files. Record format v2 deliberately rejects legacy sessions/pending attempts that cannot establish verified identity, scopes and registration; users must sign in again. No plaintext token migration is performed.
9. Initialization is idempotent. Resume/repeated clicks do not replace an active sign-in. Logout/delete blocks new starts, cancels and joins browser work, then clears state; a stale operation version cannot commit after logout.
10. Token/JWKS transports disable redirects and have bounded call timeouts. Secret redaction includes nonce and ID-token hints. Do not log request URLs/bodies or wire responses.

## User flow and recovery

- Open **Setup & permissions** or **Settings** and choose **Continue with ChatGPT**. Setup surfaces model connections before phone permissions.
- After browser consent, choose the account model and enable **Use this account for cloud tasks**. Selection is explicit; signing in does not silently replace a configured gateway.
- The optional connection test makes one real inference request and checks the requested `ok` answer. It consumes account usage. Opening a screen performs no inference test.
- Account/gateway selection and repository state update shared system readiness. A gateway key cannot make a selected, signed-out account appear ready. “Configured” is not a promise of current reachability or successful inference.
- **Sign out** clears this phone's credentials and attempts server-side refresh-token revocation. Registration is retained for another sign-in. If revocation cannot be confirmed, the UI says so; this is not account-wide logout.
- **Delete this provider**, available even when signed out or in an error state, asks for confirmation, removes the client binding and model preferences, and resets selection to the gateway. Conversations and the distinct stable per-install host ID are retained. Connect again to choose a new account/workspace.
- Chat-only use does not require Accessibility or device pairing. Current phone automation uses Accessibility. Any future privileged shell-helper setup is a separate, opt-in project.

## Remaining rollout work

This implementation supplies the auth foundation and selected-provider readiness wiring; it is not a claim that every item in the original plan is complete. Still required:

- Confirm provider support/permission for this app, then run a live browser exchange, real refresh and revocation with its own registration on a physical device.
- Run the full JVM/Robolectric/Android build and lifecycle/OEM matrix. No local Java/Gradle/Android toolchain was available during this implementation.
- Wire the stored setup progress to a full versioned welcome/model/phone step navigator and migration for existing installs. Current onboarding remains a checklist with model-first connections; it is not yet a persisted wizard.
- Add account-supported model/capability discovery. The bundled model IDs and generic effort choices remain unverified examples, with manual selection; they are not entitlements.
- Validate foreground-service startup, process-death recovery and browser behavior on supported Android versions. Pending persistence cannot keep a socket alive after process death.
- Separately approve and implement any privileged phone helper; do not add pairing UI without the authenticated helper architecture.

See [the source audit and phased implementation plan](AUTH_ONBOARDING_IMPLEMENTATION_PLAN.md) for the original findings and acceptance matrix.

## Validation in this workspace

- `git diff --check`: passed.
- Configuration/asset mirror guard: passed.
- Kotlin syntax parsing (30 changed Kotlin files) and changed XML parsing: passed; **not a compiler/type check**.
- CI preflight guards 1–6: passed. Guard 7's catalog consistency checks passed but remote model-download checks failed with network transport errors. Guards 8–9 were subsequently run separately and passed; the complete preflight is therefore still blocked, not green.
- Added regression tests for issued-ID exchange/restart/refresh, registration deletion/retention, invalid identity/grants, refresh classification, stale attempts, signed JWT/JWKS validation/rotation, callback outcomes and selected-provider readiness. These tests have **not been executed** here.
- JVM/Robolectric tests, Android lint/APK build and live sign-in were not run: no Java/Gradle/Android SDK toolchain is installed. Attempts to fetch the missing toolchain were blocked at the external release-asset host.

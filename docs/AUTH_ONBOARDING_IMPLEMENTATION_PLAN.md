# ChatGPT sign-in and onboarding: audit and implementation plan

Date: 2026-11-07  
Status: original source-audit baseline and phased plan. A first implementation now addresses registration,
identity/grants, refresh/logout/callback handling and selected-provider readiness. See
[the current implementation and remaining rollout work](ACCOUNT_SIGN_IN.md). Findings below describe
the audited baseline, not a claim that every gap remains unchanged or that every phase is complete.

## 1. Scope and evidence

Reviewed this checkout at `316d2cd02fe3e0d650ce0ea82633c4d3c1ffee89` and the supplied reference repository at `5d3c7f9c0ce521812632674776b780a1b4bbf58a`.

This is a source-level comparison, not a successful live-account or physical-device test. Provider eligibility, commercial-app permission, supported models and OEM behavior still need explicit validation. A working implementation in another app is not proof that its provider registration path is available or permitted for Aniob. In particular, the reference describes its registration path as intended for open-source, locally running applications; Aniob describes itself as commercial. Confirm applicable provider requirements before shipping, without borrowing another application's registration identity.

Reference evidence is identified by file basename and symbol below, without introducing its branding into app copy or this plan. No reference source files were copied into this repository. Any future code reuse requires a license/NOTICE and dependency-license review; keeping user-facing branding independent does not remove attribution obligations.

### Existing components worth retaining

- `AniobAuthRepository`: sole token owner, serialized refresh, persist-before-use rotation and one refresh after a rejected access token.
- `AniobPkce`, state checking, external Custom Tabs and a loopback-only callback receiver.
- Keystore-backed encrypted token/pending stores and backup exclusions.
- `AniobAccountModelClient`: implements existing cloud interfaces; execution need not know its concrete type.
- `AniobCloudSourceSelector`: explicit account vs gateway selection; no silent transfer to another cloud destination; cloud-only cannot fall back locally.
- SSE parser, secret redaction and an explicit user-triggered inference test.

Do not replace these with a second auth stack or require an API key after account sign-in.

## 2. Two different onboarding purposes

**Model connection answers: “Which model will think for me?”** ChatGPT sign-in authorizes model requests using the account's granted access. It does not grant Android permissions.

**Phone-control setup answers: “How may this app operate my phone?”** Accessibility, overlays, notifications, or an optional shell helper give different device capabilities. They do not authenticate a ChatGPT account.

The reference's first-run decision function (`Onboarding.kt`, `Onboarding.start/next`) orders **Welcome → Model → Phone → Chat**, skips already-seen/configured sections, and persists model-setup completion separately from phone-guide visibility. That separation is the important design, not a requirement to copy its exact screens. Guide-seen is not the same as currently ready; readiness must always be probed again.

### Reference account sequence

Evidence: `ChatGptAuth.kt` (`authorizeUrl`, `readCallback`, `exchange`, `refresh`, `verifyIdToken`), `ChatGptSession.kt` (`signInWithChatGpt`, `completeSignIn`, `ChatGptTokens`), `ProviderStore.kt` (registration, sign-out and removal), `ModelSetupViewModel.kt` (`signInWithChatGpt`).

1. Load a stable per-install host ID and any previously issued client ID.
2. Bind a loopback listener and use its actual redirect URI. Generate independent state, nonce and PKCE verifier/challenge for this attempt.
3. First connection: authorize using bootstrap `client_id=dynamic_agent_client`, an application-name hint, host ID, resource and requested scopes. Later connections use the issued client ID; account hints are conditional.
4. Open the external browser; keep the operation owned outside the screen's lifecycle.
5. Validate callback state. Extract both authorization code and the issued client ID. Initial registration must return an ID; a previously registered connection must reject a conflicting ID.
6. Save the registration ID before exchange so a later exchange failure does not lose the new binding. Exchange the code using the **issued ID**, matching redirect URI, verifier and resource.
7. Verify ID-token RS256 signature with issuer JWKS; check issuer, audience, expiry and attempt nonce. Require the granted `chatgpt.tokens.use.direct` scope before treating the account as usable for plan inference.
8. Save the encrypted session and granted scopes. Retrieve available models and let the user select the model used for tasks. A list failure need not erase a valid login, but must not become a fabricated successful inference test.
9. Complete the browser result page only after the bounded exchange/validation work finishes; show a safe outcome and return-to-app action.
10. Refresh with the persisted client ID, preserve rotated refresh tokens, and distinguish terminal token errors from transient/configuration failures.
11. Sign-out attempts server-side refresh-token revocation and clears local credentials while retaining registration. Provider deletion additionally forgets the registration and model bindings, enabling a new account/workspace connection.

This is the sequence observed in source, not a guarantee of present provider support for this app.

## 3. Why a pairing code appears in phone setup

### USB debugging is not the source of the six-digit code

The six-digit code comes from **Developer options → Wireless debugging → Pair device with pairing code** on supported Android devices, normally Android 11+. USB debugging is a related developer setting and the route used for the computer/cable alternative. Neither is an OAuth device code, an account verification code, or a ChatGPT login prerequisite.

The reference guide asks users to enable Developer options and USB debugging, offers optional Accessibility, then enables Wireless debugging and opens the pairing dialog. Its guide also documents OEM-specific debugging/security settings. These steps support its helper architecture; they are not universal requirements for every Android automation app.

### What pairing accomplishes

Evidence: `PairingCoordinator.kt`, `PairingCodeReader.kt`, `NsdAdbServiceFinder.kt`, `BuiltinShellManager.kt`, `BuiltinShellEngine.kt`, `HelperHandoff.kt`, and the supplied English phone-setup guide.

1. Explain shell-helper privileges and obtain explicit phone-control consent.
2. Find the device's local ADB pairing service, commonly via mDNS/NSD. Pairing and normal ADB connection services use **different ports**; never assume one fixed port or reuse the pairing port for ordinary connections.
3. While Android's pairing dialog is open, submit its six-digit code. The dialog is temporary; in the documented flow, leaving it can invalidate the window/code. Do not send users back to the app to type it.
4. If Accessibility is enabled, a narrowly scoped reader can obtain the code from that system dialog. Otherwise a notification RemoteInput action accepts it without leaving the dialog; notification permission/channel availability is checked first. Invalid input, closed windows and discovery timeouts have distinct recovery states.
5. Perform the ADB pairing handshake and retain the local ADB identity/trust material securely. The app is pairing with the same phone's debugging daemon, not necessarily a second physical device or a cloud server. The code is transient, not a credential to store or send to a model.
6. Discover/connect to the normal ADB TLS service and start the bundled helper as Android **shell UID 2000**, not unrestricted root.
7. Authenticate helper handoff using one-time nonce and app/helper identity checks. Only a verified, live helper connection counts as phone-control ready; a stored `paired=true` flag alone does not.
8. Use the helper for the reference's phone/background-display operations. Reconnect after helper death or network changes with bounded recovery; reboot recovery depends on retained pairing, available debugging service and device policy, so it must not be promised unconditionally.

The computer alternative runs an app-generated startup script through `adb shell` over USB and launches the same helper. It can bypass wireless pairing while connected; after reboot it may require running the command again. Root startup is another explicit, separately consented alternative in the reference, not a requirement for sign-in or for our first implementation.

**Security boundary:** ADB shell is more privileged than a normal app, although not root. Clearly explain and offer disconnection/forgetting trust. Do not enable debugging automatically without consent, scan arbitrary networks, send pairing codes to cloud inference, or expose the helper as an unauthenticated network service.

### Decision for Aniob

Current device execution uses `AniobAccessibilityService`, `AniobDeviceControl` and the existing safety/verification pipeline. There is no equivalent ADB pairing + authenticated shell-helper subsystem in the reviewed checkout. The bridge/gateway connection is not such a helper. In particular, `adb reverse` for a locally hosted model gateway is unrelated to phone-helper pairing.

**Recommendation: fix ChatGPT authentication and Accessibility-based onboarding first. Do not add pairing to the required path.** If independent background-display/shell control is a product requirement, treat it as a separate capability project, not as an authentication fix. Chat-only and current Accessibility operation must remain available without debugging, pairing, root, or a computer.

## 4. Findings in the current implementation

Priority: **P0** = release blocker for the intended dynamic account flow; **P1** = important correctness/UX issue; **P2** = follow-up hardening/architecture work. These are source-confirmed gaps; consequences needing live/device validation are stated as risks, not reproduced incidents.

| ID | Priority | Evidence in this repository | Problem and consequence |
| --- | --- | --- | --- |
| A1 | P0 | [`AniobAccountContainer.config`](../app/src/main/java/com/aniob/app/auth/AniobAccountContainer.kt), [`AniobCallbackResult.Code` / callback parser](../core-agent/src/main/kotlin/com/aniob/core/auth/AniobPkce.kt), [`AniobTokenClient`](../core-agent/src/main/kotlin/com/aniob/core/auth/AniobTokenClient.kt) | Unset configuration now becomes `dynamic_agent_client`, but the callback result contains only `code`; callback `client_id` is discarded. No issued registration ID is stored. Exchange and refresh always use `config.clientId`, i.e. the bootstrap ID in this path. The dynamic registration lifecycle is incomplete. |
| A2 | P0 | [`AniobPendingSignIn` / `AniobSignInFlow`](../core-agent/src/main/kotlin/com/aniob/core/auth/AniobSignInFlow.kt), [`AniobAuthorizeUrlBuilder`](../core-agent/src/main/kotlin/com/aniob/core/auth/AniobPkce.kt), [`AniobJwtClaimsReader`](../core-agent/src/main/kotlin/com/aniob/core/auth/AniobJwtClaims.kt) | No generated attempt nonce, no ID-token signature/issuer/audience/nonce validation. JWT payload decoding is explicitly not verification. An exchange success is treated as a signed-in session without the reference's identity validation. Keep unverified parsing presentation-only. |
| A3 | P0 | [`AniobAuthTokens`](../core-agent/src/main/kotlin/com/aniob/core/auth/AniobAuthModels.kt), [`AniobAuthCodec`](../core-agent/src/main/kotlin/com/aniob/core/auth/AniobAuthCodec.kt), `AniobTokenClient.parseSuccess` | Granted scopes are neither persisted nor checked. Requesting plan scope is not proof it was granted; “signed in” can be displayed without permission to perform the promised plan inference. |
| A4 | P1 | [`AniobAccountContainer`](../app/src/main/java/com/aniob/app/auth/AniobAccountContainer.kt), [`.env.example`](../.env.example), [`ACCOUNT_SIGN_IN.md`](ACCOUNT_SIGN_IN.md), [`AGENTS.md`](../AGENTS.md) | Documentation says `UNSET` hides the feature; code uses it to enable dynamic registration. Existing account docs also describe older authorize/token/inference endpoints, while current defaults use account registration endpoints and the API resource. Configuration, release policy and docs disagree. Defaults also retain older CLI-specific extras and an experimental beta header without proof this registration path needs them. |
| A5 | P1 | `AniobTokenClient.refresh`, `failureFor`; [`AniobAuthRepository.obtainAccess`](../core-agent/src/main/kotlin/com/aniob/core/auth/AniobAuthRepository.kt) | Refresh uses JSON, unlike the observed reference's form-encoded refresh. Every non-transient JSON 4xx becomes `RefreshRejected`, causing local credentials to be cleared, even for nonterminal client/scope/configuration errors. Match the selected protocol's wire format and only clear tokens for documented terminal token errors. JSON rejection by the live endpoint has not been tested here. |
| A6 | P1 | [`AniobAccountViewModel.signOut`](../app/src/main/java/com/aniob/app/auth/AniobAccountViewModel.kt), `AniobAccountContainer.signOutEverywhere/deleteProvider` | Sign-out is local only; no revocation transport. The “everywhere” operation still affects only this installation. No issued registration exists to preserve on ordinary sign-out or forget on deletion. Deletion leaves the stable host ID; host ID and issued client registration must be treated as different concepts. |
| A7 | P1 | [`AniobAccountSignInCard`](../app/src/main/java/com/aniob/app/ui/screens/AniobAccountSignInCard.kt), `AniobAccountViewModel`, `AniobAccountContainer.cloudSource` | Account use is a separate explicit switch and defaults to the gateway. Completing login alone does not select it for task execution. Keep selection explicit, but offer “Use for tasks” as a clear completion step and show the active provider. Recovery UI for a failed/expired session has retry only; it does not expose provider reset/deletion for a stale workspace binding. |
| A8 | P1 | [`AniobMainScreen` onboarding route](../app/src/main/java/com/aniob/app/ui/AniobMainScreen.kt#L269), [`AniobSystemState.isModelReady`](../app/src/main/java/com/aniob/app/system/AniobSystemState.kt), [`AniobSetupConciergeScreen`](../app/src/main/java/com/aniob/app/ui/screens/AniobSetupConciergeScreen.kt) | Setup's cloud-ready calculation only checks the gateway. System `modelReady` conflates installed local model with a nonblank gateway key and ignores account state and selected source. A connected account can still appear unconfigured; an irrelevant key can appear ready. A syntactically valid local/keyless gateway address is not proof its service is reachable. |
| A9 | P1 | [`AniobSetupStore`](../app/src/main/java/com/aniob/app/data/AniobSetupStore.kt), `AniobSetupConciergeScreen`, `AniobMainScreen` | Progress persistence exists but has no main-source consumers outside its declaration. Wizard/checklist modes change presentation text, not an actual persisted step machine. The brain step comes after permission requests and sends users to local downloads or Settings/API-key copy, rather than first offering account/local/gateway choices. |
| A10 | P1 | [`AniobLoopbackCallbackListener.handle`](../app/src/main/java/com/aniob/app/auth/AniobLoopbackCallbackListener.kt#L78), `AniobSignInFlow` | Receiver answers a neutral return-to-app page and consumes the first matching-path callback before state validation/exchange. Wrong-state traffic can terminate an attempt, and the browser cannot report the eventual exchange failure. Keep waiting for an acceptable callback; only show a final success/failure outcome after bounded validation, without reflecting secrets. |
| A11 | P1 | [`AniobSignInCoordinator`](../app/src/main/java/com/aniob/app/auth/AniobSignInPlatform.kt), `AniobAccountViewModel.init/signOut`, `AniobAuthRepository.initialize` | Coordinator cancels/replaces a running job when `resumeIfPending` launches; ordinary sign-out does not cancel sign-in; full sign-out cancels without joining cleanup before deleting stores/key. Repository initialization is asynchronous and `markSigningIn` is outside its mutex. These are lifecycle/order race risks needing deterministic tests, not claims of reproduced data loss. |
| A12 | P2 | [`account_models.yaml`](../configs/account_models.yaml), `AniobAccountViewModel.sendTest`, `AniobAccountModelClient` | Catalog explicitly calls model IDs unverified examples. No live account model discovery/capability validation exists. The test marks any successful nonempty model response OK without checking the requested `ok` response. Distinguish authenticated, model selected, inference tested and phone-control ready; do not infer supported models or effort settings from labels. |

### Tests currently miss the essential protocol

Existing tests cover static-client exchange, JSON refresh, concurrency, callback state, encryption/listener behavior and model streaming. Some deliberately use unsigned JWT fixtures and token replies without granted scopes. They are useful unit tests of the current contracts but do not establish dynamic-registration interoperability. Add new contracts/tests rather than treating their existing green assertions as proof of the intended flow.

## 5. Target user flow

```text
Welcome + privacy explanation
  → Choose model connection
      Continue with ChatGPT | On-device model | Gateway/API configuration
  → For ChatGPT: browser consent → validated account connection
      → available model / manual fallback → explicit “Use for tasks”
      → optional one-request connection test, clearly marked as consuming usage
  → Phone control (can skip for chat-only)
      Accessibility control: explanation → system permission → live service check
      Optional supporting permissions only when relevant
      [Future opt-in helper control: separate privileged setup wizard]
  → Safe first task → observed result → Chat
```

Never require Accessibility, overlays, notification-listener access, battery exemption or ADB just to sign in or chat. A sign-in foreground service may require its own platform-specific handling; that is not permission to operate the phone. Keep simulated demos separate from real task execution and mark outcomes based on actual evidence.

Readiness should be one reactive projection shared by onboarding, Settings, banners and routing:

- `ModelConnection`: source + initialization/auth status + chosen model + last test state.
- `PhoneControl`: unsupported / not configured / Accessibility connected / optional helper state.
- `SetupProgress`: current step, guide seen/skipped, completion version; no tokens or pairing codes.

Reuse `AniobCloudSourceSelector` for selected-cloud semantics; do not create a competing provider inventory. “Configured”, “signed in”, and “tested successfully” are distinct statuses. Opening setup/settings must make no inference call. Refresh, logout, provider changes and service disconnects must update all views without a manual refresh.

## 6. Implementation work packages

### Phase 0 — Confirm protocol and release contract

**Files:** `.env.example`, `ACCOUNT_SIGN_IN.md`, `AGENTS.md`, build configuration; new protocol fixtures.

- Confirm provider permission/eligibility for this app and select one coherent protocol profile: approved dynamic account registration, or an explicitly authorized static registration if dynamic access is unavailable. Do not mix older backend/CLI defaults with the new API resource flow.
- Introduce an explicit feature/mode setting instead of overloading `UNSET` as both disabled and dynamic. Misconfiguration must fail closed, with no misleading enabled sign-in action.
- Record issuer, authorize/token/revoke/JWKS/resource/inference endpoints, required scopes, redirect constraints and observed request/response shapes. Remove obsolete extras/headers only against that profile's evidence.
- For dynamic mode, use Aniob's own application-name hint; retain a stable per-install host ID for repeat registration/login.

**Exit:** reviewed configuration matrix and sanitized protocol fixtures; commercial applicability resolved before public rollout. If provider access cannot be established, do not advertise plan-backed sign-in as working.

### Phase 1 — Complete registration, verification and persistence

**Files:** core `AniobAuthModels`, `AniobPkce`, `AniobSignInFlow`, `AniobTokenClient`, `AniobAuthRepository`, `AniobAuthCodec`; Android `AniobAccountContainer`, encrypted stores and token transport adapters.

- Model registration independently from session credentials: host ID + issued client ID retained on sign-out, removed as a binding on provider reset. Repository remains the only owner of auth persistence; no new token-store readers.
- Persist attempt nonce, exact redirect URI and registration context alongside verifier/state. Reserve nonce and security-critical parameters against extra-parameter overrides.
- Return issued client ID from validated callbacks; reject missing first-registration IDs and mismatches for existing registrations. Save the ID before code exchange, but do not mark a session connected yet.
- Exchange and refresh with the issued ID and the selected profile's encoding/resource. Persist scopes and validate required grants, including refresh responses that explicitly narrow scopes.
- Add a pure-JVM ID-token verification port with bounded JWKS retrieval/cache and one key-rotation retry. Verify signature algorithm, issuer, audience, expiry and attempt nonce before committing identity/session state. Network failure must not become verification success.
- Version the encrypted records. Legacy records lack verified registration/scopes/nonce context: require reauthentication rather than fabricate those fields. Invalidate incompatible pending attempts with an actionable restart message.
- Separate terminal token rejection from network/service/configuration failures. Preserve credentials for nonterminal failures and bound retries.

**Exit:** fake-server full-flow tests prove bootstrap → issued ID → exchange → verified identity/grants → persisted session → restart → refresh using issued ID. Negative identity/grant tests never reach `SignedIn`.

### Phase 2 — Fix lifecycle, return page and account recovery

**Files:** Android auth coordinator, listener, foreground service, account ViewModel/card, strings; repository sign-out/revoke orchestration.

- Serialize initialization/start/resume/cancel/logout/delete. A resume request must attach to an existing operation, not restart it. Await cancel/cleanup before clearing storage or deleting a Keystore key. Make shutdown idempotent.
- Keep browser work process-owned, verify foreground-service behavior on supported Android/OEM versions, and restart interrupted attempts deliberately. Pending persistence alone cannot keep a callback socket alive across process death.
- Make callback validation part of listener acceptance; reject malformed/duplicate security parameters and unexpected host/method/path, ignore unrelated or wrong-state requests within bounded limits, and close every socket. Use bounded line/header reads, not an unbounded `readLine` with only an after-the-fact size check.
- If the chosen protocol supports variable loopback ports, allocate one and persist the exact URI for exchange. Otherwise honor the approved fixed URI and report a port conflict; never silently change a registered redirect.
- Render a static safe outcome page after exchange/verification completes under timeout. Preserve no-store/no-referrer headers and return-to-app action. OAuth must remain bound to `127.0.0.1`, not the wildcard binding used by browser previews/dev servers.
- Implement best-effort revocation and report when only local sign-out could be confirmed. Rename the current “everywhere” action to accurately describe local scope unless the provider actually supplies account-wide revocation.
- Make reset/delete available from disconnected/expired/workspace-error states with confirmation. Ordinary logout retains registration; delete removes the client binding, credentials and model preferences. Retaining/rotating the distinct host ID must be a documented policy.

**Exit:** background/browser round-trip, repeated clicks, screen recreation, notification cancel, process interruption and logout-during-exchange tests pass; an old completion cannot restore a session after logout/delete.

### Phase 3 — Wire the real onboarding flow and model readiness

**Files:** setup store/screen, main screen, system state, account ViewModel/card, cloud-source selection consumers and Settings.

- Connect persisted setup progress to navigation; implement step transitions, back/skip/resume and a versioned migration for existing installs. Reopening Settings must not restart first-run onboarding.
- Place model selection before phone-control permissions. Surface the existing account card/connection route directly, with local and gateway alternatives.
- Build the shared reactive readiness projection from repository state, explicit provider selection, installed model state and phone-service state. Stop treating a gateway key as a local model or an unrelated provider as the active brain.
- Keep provider selection explicit: after sign-in offer “Use for tasks”; display the selected provider and model in both setup and Settings. Account failure must not silently route data to the gateway.
- Discover models when supported using repository-issued access grants; preserve a clearly labeled manual option if listing fails. Cache only metadata, not bearer tokens. Do not make inference calls just to render a screen.
- Gate real device-task examples on required capabilities and report observed success/failure. Keep optional permission prompts contextual and allow chat-only completion.

**Exit:** clean install → account login → selected account model → chat/first task works without a gateway key; local/gateway-only users and older installs remain functional; sign-out/revocation immediately changes readiness.

### Phase 4 — Optional privileged phone-helper project (separate approval)

Only implement if shell/background-display operation is required. Pairing UI is the last part, not the first.

1. Threat model and platform spike: supported Android/API/OEM matrix, helper capabilities/limitations, background-display compatibility and distribution-policy review.
2. Define pure core ports/state machine for discovery, pairing, connection, authenticated helper lifecycle and capability readiness. Android networking/NSD/Keystore/Binder implementations stay in `app` or a dedicated Android helper module; core stays Android-free.
3. Implement vetted ADB pairing/TLS transport, secure per-install identity storage and bounded local-only discovery. Keep pairing and connect endpoints distinct. Review native dependencies/ABIs/licenses before adoption.
4. Implement the bundled least-privilege helper with authenticated launch/IPC, nonce replay rejection, caller/app/signature checks, verified shell UID, Binder death handling and protocol versioning. No unauthenticated command execution or bypass of existing safety/approval/verification.
5. Then add Developer options / Wireless debugging guidance, notification-code input, optional narrowly scoped Accessibility code reading, correct timeout/error recovery and live ready checks. Code capture must be isolated from model-visible screenshots, accessibility dumps, logs and telemetry.
6. Add consent-aware reconnect/reboot behavior, stop/forget pairing, OEM guidance and a computer/USB fallback. Do not require root or automatic secure-settings writes in the first version.

**Exit:** a verified live helper can perform one approved harmless action through the existing execution pipeline, report its result and shut down/revoke cleanly. Pairing alone is not success. Keep this behind an explicit capability flag; do not block account login or Accessibility mode on it.

## 7. Test and acceptance matrix

| Area | Required coverage |
| --- | --- |
| Registration | New issued ID; missing ID; conflicting ID; ID persisted before failed exchange; reuse after sign-out; fresh registration after deletion. |
| OAuth security | PKCE/state/nonce uniqueness; wrong-state requests do not consume valid attempt; duplicate parameters; denied callback; invalid signature/algorithm/issuer/audience/expiry/nonce; JWKS outage/rotation; missing plan grant. |
| Token lifecycle | Issued ID and correct encoding in exchange/refresh; one concurrent refresh; rotated token persisted first; transient and nonterminal 4xx preserve credentials; terminal invalid grant clears session; no secret-bearing diagnostics. |
| Android lifecycle | Listener bound before browser; background callback; service start restrictions; repeated start; resume while active; cold process interruption; browser unavailable; port conflict; cancel/logout/delete while exchange is in flight; cleanup cannot resurrect credentials. |
| Routing/readiness | Account selected and connected without gateway; account selected but expired; selected gateway unavailable; local model present/absent; cloud-only never downgraded; auth/source changes update every screen; no hidden inference test. |
| Onboarding | New install; existing install; skip phone control; back; progress after restart; return from system permission settings; permission revocation; simulated demo does not claim real success. |
| Models/inference | List success/failure; unsupported model/effort; real minimal text and screenshot-backed request; usage-limit vs eligibility errors; no unverified catalog promises. |
| Optional helper | Expired/wrong code; denied notifications; different pairing/connect ports; Wi-Fi changes; no mDNS; spoofed helper; nonce replay; wrong UID/certificate; helper death/reboot; forget trust; no code leakage into prompts/captures. |

Live acceptance must use the app's own allowed registration and the user's deliberate consent. Keep raw OAuth redirects, tokens, ID tokens, verifier, nonce, pairing codes and credential-bearing URLs out of issue reports, fixtures and test artifacts.

## 8. Validation performed for this audit

- Source inspection of both pinned checkouts and current auth/onboarding tests; no production credentials or account sign-in used.
- `bash scripts/ci-preflight.sh`: guards 1–6 passed; guard 7 confirmed catalog mirror/default consistency, then failed because this sandbox cannot reach the external model-download host (curl transport failures, HTTP 000). Later guards were not reached by that invocation. This does not establish invalid model URLs.
- No Gradle wrapper exists in this checkout; no installed `gradle` or `java` executable was found in this environment. JVM/Android tests and APK compilation were not run. Before implementation is accepted, provision the repository's documented JDK/Gradle/SDK environment and run:

```bash
bash scripts/ci-preflight.sh
gradle :core-agent:test :app:testDebugUnitTest --console=plain
gradle :app:lintDebug :app:assembleDebug --console=plain
```

**Recommended delivery order:** phases 0–1 (protocol/security) → phase 2 (lifecycle/recovery) → phase 3 (onboarding/readiness). Phase 4 is independent and requires its own product/security approval. Do not hide registration failures by adding debugging permissions, changing cloud destinations, or asking the user to supply an API key.

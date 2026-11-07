# AGENTS.md

Persistent, repo-specific knowledge for agents working in this repository.

## Build & test

Gradle wrapper is not present; use an installed Gradle. This environment uses:

```bash
export JAVA_HOME=/opt/jdk17
export ANDROID_HOME=$HOME/android-sdk
export PATH=/tmp/setup/gradle-dist/gradle-9.3.1/bin:$PATH

gradle :core-agent:test :app:testDebugUnitTest --console=plain
gradle :app:assembleDebug --console=plain
```

- `:core-agent` is pure Kotlin/JVM. Guarded by CI preflight: **zero `android.*` imports**. Keep it
  platform-free; put Android-runtime dependencies behind interfaces in `app`.
- `:app` unit tests use Robolectric (`@Config(sdk = [33])`) and run on the JVM. `robolectric-nativeruntime`
  may log a harmless "Failed to destroy temp directory" on shutdown.
- `:app:assembleDebug` requires a debug keystore that is not committed. Generate it locally if missing:
  `keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"`
- `google-services.json` is intentionally absent; `MissingGoogleServicesStrategy.WARN` tolerates it.

## CI preflight

`bash scripts/ci-preflight.sh` runs five compliance guards (no billing/payment SDKs, skill-assets sync,
core-agent purity, empty-catch scan, Prompt-4 pins). Run it before proposing changes; it must end with
`ALL PREFLIGHT CI GUARDS PASSED SUCCESSFULLY`.

## App discovery & launch

- `AniobAppRegistry` (`core-agent/knowledge`) is the **only** source of truth for "which package does
  the user mean": launchable-only (`MAIN`/`LAUNCHER`), role-aware, ambiguity is an explicit
  `AppLookup.Ambiguous`. `AniobAppCatalog` is a facade over it — never add a second inventory.
- `AniobAppBootstrap` (`app/knowledge`) is the only Android-aware piece: sync TSV load + role probe,
  background launchable scan. `AniobApplication.onCreate` installs it; the accessibility service and
  `AniobPackageReceiver` refresh it. Keeps `core-agent` free of `android.*`.
- App opening is a **lookup**, never a model call: `AniobLaunchHub.plan` (step 0) and
  `planMidTask` (a switch later in the task, remembers what this task already opened).
- An app launch is **never** a `DirectIntent`. It is an `OpenApp` action verified against the real
  foreground package (`DeterministicVerifier`), dispatched with the registry's explicit launcher
  component when known. Only reflexes (torch, battery, dial, URL) may finish on dispatch alone.
- Fuzzy matching is single-token only and typos are Levenshtein-based; see
  `docs/APP_DISCOVERY_AND_LAUNCH.md` before "improving" the matcher.

## Bundled action plans (more than one action per turn)

See `docs/ACTION_PLANS.md` for the contract; the invariants that must not be "simplified" away:

- A plan is **reduced to the screen it can be grounded on** (`BurstPlanScope`), never rejected for
  naming elements of the screen it opens, and never executed past the first action that does not
  resolve. A refused plan returns `null` so the single-action parser and its repair retry still run;
  it must not be replaced by a fixed `Wait`.
- `ActionBurstRunner` re-captures the screen and re-grounds the next member **after every action**.
  A member that no longer fits the screen closes the plan and continues from the live screen; it
  never fails the task and never fires blind.
- `is_complete` must not survive truncation: a plan with a deferred tail is not a finished task.
- Only an editable node may receive `input`, and a target-less `input` needs a single editable field
  plus the user's own words. Element ids are re-assigned on every capture, so a guessed id can name
  a different widget on the current screen.
- Every member runs through `StepPipeline.executeStep` — no safety, gating or verification shortcut,
  and only verified members are written to the Remember cache.

## Architecture invariants

- **Never fabricate an action.** A local SLM step must originate from real model output
  (`AniobGenerationResult.Ready`). Missing engine/model, generation timeout, and unparseable output all
  resolve to `AniobAction.Fail` with a user-actionable reason via `AniobLocalActionResolver`. Taps are
  validated against node ids present on the captured screen before dispatch.
- Native accelerators (`AniobLlamaCppEngine`, `AniobLiteRtEngine`) share the same
  `load`/`generate`/`release` contract so routers can swap engines.
- Gestures are gated on UI quiescence (`AniobWaitForIdle`) before dispatch; the observation policy
  deduplicates repeated action signatures to catch in-flight animations.
- The Tracker screen is a pure view over Room/EventLogger state: opening it must perform zero screen
  captures and zero model calls (`TrackerZeroCaptureTest`).
## On-Device Inference Runtime Decision (Plan-0.3)
- **Decision: Option (a) — Prebuilt Maven Runtime**
  - Adopt prebuilt Google AI Edge LiteRT (`com.google.ai.edge.litert`) runtime dependencies in `:app`.
  - `:core-agent` remains a pure Kotlin/JVM module with 0 `android.*` dependencies. All native runtime drivers live in `:app` behind domain contracts.
  - Model catalog entries are strictly aligned with unauthenticated range-request HTTP 206 endpoints and verifiable SHA-256 hashes.

## Cloud gateway resilience, reflex completion, web launch, burst replan

- **Gateway ladder.** Every OmniRoute response is classified (`AniobGatewayClassifier`, pure JVM) by status
  *and* `Content-Type` before any parsing; an HTML page is never fed to the JSON parser and never echoed to
  the user (its `<title>` is). A failure is a typed `AniobGatewayException`; the endpoint then sits in a
  cool-down (`AniobGatewayHealth.shared`) and a local gateway must accept TCP within 500 ms (the
  `adb reverse tcp:20128 tcp:20128` check) before a request is built. A skipped endpoint is not counted as
  an LLM call. `AniobViewModel.degradeAfterCloudFailure` then retries the step on the on-device model
  (`cloud-only` routing is honoured and never downgraded). The hosted `omniroute.online` is **not** an
  implicit fallback any more; it is used only when the user picks it.
- **Reflexes complete on dispatch.** `ResolvedIntentShortcut.completesOnDispatch` (alarm/timer with
  `SKIP_UI`, torch, battery, dial, URL, a settings panel that is the whole request) closes the run as
  `SUCCESS`; a bare "set alarm" that only opens the editor stays `UNVERIFIED`.
- **Web launch.** `AniobLaunchHub.planWeb` (`AniobWebLaunchPlanner`): `open <site> in chrome|browser`,
  `search [for X] in <site>`, and the web fallback for an app that is *not installed*. An installed app or an
  ambiguity always wins over the web fallback; a bare word only becomes `<word>.com` with an explicit browser cue.
- **Burst replan.** A burst target that vanished mid-burst (`BurstTargetGuard.Verdict.shouldReplan`) drops the
  rest of the burst, re-observes, and plans single actions for the rest of the run. A safety block is still
  a hard stop.

## Device capabilities (DEVICE_CONTROL)

- `DeviceCommand` (`core-agent/domain`) is the **only** list of direct device commands. Schema, prompt
  manifest, reflex resolver and the Android implementation (`app/device/AniobDeviceControl`) all derive
  from it; add a command there and let the exhaustive `when`s tell you what is missing.
- The executor system prompt always carries `AniobCapabilityManifest.render(...)`; never hand-copy the
  command list into a prompt file. `configs/`, `android-agent/configs/` and the APK assets must stay in
  sync (`scripts/config-assets-sync-check.sh`).
- Verification codes are masked (`AniobOtpExtractor.mask`) in anything a model, log or chat can read.
  They leave memory only through `otp_copy` / `otp_paste`.
- `CONFIRM`-risk commands are never reflexes. `AniobReflexResolver` ignores multi-goal sentences.
- No new broad `catch (e: Exception)`: the preflight ratchet counts them. Catch the specific exception.
- See `docs/DEVICE_CAPABILITIES.md`.

## Sign in with an account (ChatGPT)

* `AniobAuthRepository` (`core-agent/auth`) is the **only owner of tokens and sign-in state**. Nothing else reads `AniobTokenStore`; a request gets a short-lived `AniobAccessGrant` from `obtainAccess()`. Do not add a second reader, and never log, persist in plaintext or show an access token, refresh token, id token, authorization code or PKCE verifier (preflight guard 9 and `AniobSecretRedactor` enforce it).
* The router consumes the account only through `AniobAccountModelClient` (`app/auth`), which implements the same `AniobCloudLlmProvider` / `AniobActionStepProvider` interfaces as the gateway client. The choice is explicit (`AniobCloudSourceSelector`): an account choice that is not signed in is *unavailable*, never silently swapped to the gateway, and `cloud-only` is never downgraded.
* `AniobActionStepProvider` is the one cloud-step interface; `AniobOmniRouteProvider` implements it. The step loop must not name a concrete provider.
* All protocol logic (PKCE, URL, callback parse, JWT claims, token client, refresh policy, SSE parser, error mapping) is pure JVM in `core-agent/auth`. Android code (Keystore store, loopback socket, Custom Tab, UI) stays in `app/auth` behind those ports.
* The feature is off for `UNSET`/`NONE`. Dynamic registration must be explicitly selected with `ACCOUNT_AUTH_CLIENT_ID=dynamic_agent_client` and provider eligibility confirmed; otherwise use an authorized static ID (an empty `.env` value breaks the secrets plugin's BuildConfig output). See `docs/ACCOUNT_SIGN_IN.md`.

* Account registration is not the bootstrap client ID: persist the callback-issued ID before code exchange and retain it on sign-out; forget it on provider deletion. RS256/JWKS identity + issuer/audience/expiry/nonce and granted plan scope must validate before `SignedIn`. Production token clients must supply `AniobJwksIdTokenVerifier`; the default rejects identity. Tests may inject fakes only for non-crypto contracts.
* Session/pending codec v2 intentionally invalidates unverified legacy records. Logout must cancel/join sign-in before clearing state. Wrong-state callback traffic must not consume a valid attempt. Keep OAuth bound to IPv4 loopback even though browser preview servers use wildcard bindings.
* `SystemState.localModelReady` means an installed local model; `cloudReady` follows the selected provider through `AniobCloudSourceSelector`; `modelReady` is their union. Auth/source changes refresh this snapshot without inference calls. Model availability and a successful inference test remain separate concepts.

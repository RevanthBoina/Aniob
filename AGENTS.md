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

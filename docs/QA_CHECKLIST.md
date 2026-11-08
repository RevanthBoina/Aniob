# Aniob QA Checklist — UI-TARS / Ferret-UI / CogAgent / SeeClick (Prompt 4: Visual Perception SOTA)

Manual + automated verification for the "Eagle Eye + Ferret Curiosity + Human Cognition" perception stack.

## M0 Smoke Tasks (benchmark baseline)

Run each task in emulator + real device, and record: `primaryProvider`, `steps`, `success`.

| Task | Expected path | Success if |
|---|---|---|
| "Open Settings" | SKILL → settings opens | settings screen visible |
| "Check battery" | INTENT → get_device_info reflex | chat reply with battery % |
| "Turn on flashlight" | INTENT → flashlight reflex | torch toggled (needs CAMERA perm) |
| "What apps do I have?" | LOCAL_SLM / OMNIROUTE_CLOUD get_installed_apps | launcher list rendered |
| "Send message to {contact}" | compose a draft; ground the real Send control | approval appears before the Send commit point |

## Automated checks

The pull-request workflow is the source of truth; see the [README setup guide](../README.md#get-started) for prerequisites and commands. CI runs `bash scripts/ci-preflight.sh`, `gradle :core-agent:test :app:testDebugUnitTest --console=plain --stacktrace`, and `gradle :app:lintDebug --console=plain`. Debug APK packaging is a separate local check and may require a generated debug keystore.

## Perception pins (Prompt 4)

1. **UI-TARS grounding**: 60ms delta throttle, screenshot-driven streaming; every OMNIROUTE_CLOUD step carries a screenshot Base64.
2. **Ferret-UI spatial**: SoM `[id] bounds=` strings pinned in `AniobTokenOptimizer`; top/left sorted 1-based re-index (previous task).
3. **CogAgent fovea**: max 60 actionable nodes, area > 50, visible-to-user only (previous task, regression here).
4. **SeeClick online signals / reflex instant mapping**:
   - `turn_on_flashlight` → FLASHLIGHT intent (reflex, no LLM).
   - `check_battery` → telemetry answer in chat (reflex, no LLM).
5. **Human-cognition guardrails**:
   - Watchdog: 3x same action or same screen → remedial BACK + `loop_detected` reason.
   - AskPolicy: payment, secret entry/exposure, and deletion always pause for approval at OFF/BALANCED/CAREFUL; ordinary sends also pause outside OFF. UPI protocol/billing permission remain hard stops.
   - NotificationListenerService: OTP/verification-code/pin notification content never leaves the device.
   - onTrimMemory / onLowMemory: LiteRT engine released (reflex, zero LLM).
   - AppCatalog stays fresh via PackageReceiver (PACKAGE_ADDED/REMOVED/REPLACED).

## Manual checklist

- [ ] Accessibility service connects; "Building app catalog…" toast once.
- [ ] Start a task; the in-app ticker and ongoing notification show progress, with Pause/Stop controls; no floating status pill appears.
- [ ] Verified success posts one result notification without a completion Toast or automatic return to Aniob; failure/unverified completion opens chat for follow-up.
- [ ] Payment, password/OTP, and deletion actions pause for approval under every safety level; leaving the request unanswered does not deny or dispatch it, while Stop cancels the wait.
- [ ] A stale or recycled accessibility index never dispatches against a different widget; a second miss for the same target asks for clarification.
- [ ] A burst executes only grounded 2–8 action plans; the first targeted member and subsequent members are rechecked live, deferred actions remain hints, and a one-action array falls back to the ordinary parser.
- [ ] Kill a run mid-way; Watchdog screen-hash loop recovers via BACK.
- [ ] App chooser contains only launchable apps installed on this device; unknown names are clarified instead of guessed into a URL or fabricated service.
- [ ] Install/remove an APK; `app_catalog.json` + `apps/app-registry.md` updated within seconds.
- [ ] Low-battery (<15%, unplugged) routes to OMNIROUTE_CLOUD with "Battery low reflex" reason.
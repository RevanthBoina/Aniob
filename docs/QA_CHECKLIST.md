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
| "Send message to {contact}" | OMNIROUTE_CLOUD confirm_with_user | confirmation gate raised before send |

## Automated gates (CI)

- `scripts/ci-preflight.sh` — billing/payment SDK zero-reference, YAML assets sync, core-agent purity, empty-catch warning.
- `gradle :core-agent:test` — all JVM tests (router, watchdog, safety interceptor, intent resolver, token optimizer, exec report, app sessions).
- `gradle :app:assembleDebug` — app APK builds (with Gradle 9.3.1 and a local debug keystore).

## Perception pins (Prompt 4)

1. **UI-TARS grounding**: 60ms delta throttle, screenshot-driven streaming; every OMNIROUTE_CLOUD step carries a screenshot Base64.
2. **Ferret-UI spatial**: SoM `[id] bounds=` strings pinned in `AniobTokenOptimizer`; top/left sorted 1-based re-index (previous task).
3. **CogAgent fovea**: max 60 actionable nodes, area > 50, visible-to-user only (previous task, regression here).
4. **SeeClick online signals / reflex instant mapping**:
   - `turn_on_flashlight` → FLASHLIGHT intent (reflex, no LLM).
   - `check_battery` → telemetry answer in chat (reflex, no LLM).
5. **Human-cognition guardrails**:
   - Watchdog: 3x same action or same screen → remedial BACK + `loop_detected` reason.
   - SafetyInterceptor: payment blocklist; OTP/cvv/password blocked on editable nodes; confirm-with-user requires confirmation for unknown contacts.
   - NotificationListenerService: OTP/verification-code/pin notification content never leaves the device.
   - onTrimMemory / onLowMemory: LiteRT engine released (reflex, zero LLM).
   - AppCatalog stays fresh via PackageReceiver (PACKAGE_ADDED/REMOVED/REPLACED).

## Manual checklist

- [ ] Accessibility service connects; "Building app catalog…" toast once.
- [ ] Settings → Aniob → toggle overlay pill; pill shows live step count.
- [ ] Start a task; notification shows "■ Stop" action that cancels the run.
- [ ] Kill a run mid-way; Watchdog screen-hash loop recovers via BACK.
- [ ] Install/remove an APK; `app_catalog.json` + `apps/app-registry.md` updated within seconds.
- [ ] Low-battery (<15%, unplugged) routes to OMNIROUTE_CLOUD with "Battery low reflex" reason.
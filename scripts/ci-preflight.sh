#!/usr/bin/env bash
set -e

echo "=== Running Aniob CI Preflight Inspection ==="

# 1. Billing & Payment Zero-Reference Guard
echo "[Guard 1/8] Verifying zero billing or payment SDKs..."
bash scripts/check_no_billing.sh
bash scripts/test-check-no-billing.sh

# 2. Skill YAML <-> Assets Sync Guard
echo "[Guard 2/8] Verifying YAML skill library and assets sync..."
bash scripts/skill-assets-sync-check.sh

# 3. Core Agent Purity Guard (zero android.* imports in core-agent)
echo "[Guard 3/8] Verifying core-agent purity..."
BAD_CORE_IMPORTS=$(grep -rn "import android\." core-agent/src/main/kotlin/ 2>/dev/null || true)
if [ -n "$BAD_CORE_IMPORTS" ]; then
    echo "ERROR: core-agent purity violation detected:"
    echo "$BAD_CORE_IMPORTS"
    exit 1
fi
echo "Core-agent purity verified (0 android.* imports)."

# 4. Ratchet broad catches. This counts catch declarations, not necessarily silent
# catches: an exception named e may be handled. Lower the baseline as they are fixed.
echo "[Guard 4/8] Checking broad catch declarations..."
SILENT_CATCH_BASELINE=${SILENT_CATCH_BASELINE:-129}
SILENT_CATCHES=$( (grep -rEn 'catch\s*\(\s*(_|e)\s*:\s*(Exception|Throwable)\s*\)' app/src/ core-agent/src/ 2>/dev/null || true) | wc -l | tr -d ' ')
echo "Broad catch declarations: ${SILENT_CATCHES} (baseline ${SILENT_CATCH_BASELINE})"
if [ "${SILENT_CATCHES}" -gt "${SILENT_CATCH_BASELINE}" ]; then
    echo "ERROR: broad catch count rose above the baseline. Handle or rethrow the new catch."
    exit 1
fi

# 5. Prompt 4 Master-Spec smoke M0 (UI-TARS / Ferret-UI / CogAgent / SeeClick)
echo "[Guard 5/8] Verifying Prompt-4 visual-perception smoke M0 pins..."
SMOKE_FAIL=0

# 5a. 60ms delta throttle + screenshot Base64 streaming in OmniRoute provider
if ! grep -q "minIntervalMs = 60L\|60L" app/src/main/java/com/aniob/app/provider/AniobOmniRouteProvider.kt 2>/dev/null; then
    echo "ERROR: OmniRoute 60ms delta throttle not found."
    SMOKE_FAIL=1
fi
if ! grep -q "data:image/jpeg;base64" app/src/main/java/com/aniob/app/provider/AniobOmniRouteProvider.kt 2>/dev/null; then
    echo "ERROR: OmniRoute screenshot Base64 vision payload not found."
    SMOKE_FAIL=1
fi
# ConnectionPool reuse via AniobHttpClientSingleton
if ! grep -q "AniobHttpClientSingleton.client" app/src/main/java/com/aniob/app/provider/AniobOmniRouteProvider.kt 2>/dev/null; then
    echo "ERROR: OmniRoute provider not reusing shared ConnectionPool."
    SMOKE_FAIL=1
fi

# 5b. Reflex intent mappings (SeeClick)
if ! grep -q "turn_on_flashlight" core-agent/src/main/kotlin/com/aniob/core/ladder/AniobIntentResolver.kt 2>/dev/null; then
    echo "ERROR: flashlight reflex intent mapping missing."
    SMOKE_FAIL=1
fi
if ! grep -q "get_device_info" core-agent/src/main/kotlin/com/aniob/core/ladder/AniobIntentResolver.kt 2>/dev/null; then
    echo "ERROR: check_battery reflex intent mapping missing."
    SMOKE_FAIL=1
fi

# 5c. Battery-low reflex (pure JVM)
if ! grep -q "fun isLowBattery" core-agent/src/main/kotlin/com/aniob/core/tools/AniobBatteryHealth.kt 2>/dev/null; then
    echo "ERROR: isLowBattery reflex missing."
    SMOKE_FAIL=1
fi

# 5d. Watchdog N=3 same-action detection
if ! grep -q "actionKey" core-agent/src/main/kotlin/com/aniob/core/verifier/AniobWatchdog.kt 2>/dev/null || ! grep -q "takeLast(loopThreshold)" core-agent/src/main/kotlin/com/aniob/core/verifier/AniobWatchdog.kt 2>/dev/null; then
    echo "ERROR: Watchdog same-action-3x detection missing."
    SMOKE_FAIL=1
fi

# 5d-2. Bundled plan (multi-action) contract: the runner is what keeps a plan that crosses a
# screen boundary from firing blind, and the parser must keep accepting the plan shape it ships.
BURST_FILES="core-agent/src/main/kotlin/com/aniob/core/perception/BurstPlanScope.kt \
core-agent/src/main/kotlin/com/aniob/core/execution/ActionBurstRunner.kt"
for f in $BURST_FILES; do
    if [ ! -f "$f" ]; then
        echo "ERROR: bundled-plan file missing: $f"
        SMOKE_FAIL=1
    fi
done
if ! grep -rq --include="AniobViewModel*.kt" "parseBurstOrRepair" app/src/main/java/com/aniob/app/ui/ 2>/dev/null; then
    echo "ERROR: the step loop no longer reads a bundled plan."
    SMOKE_FAIL=1
fi
if ! grep -rq --include="AniobViewModel*.kt" "ActionBurstRunner" app/src/main/java/com/aniob/app/ui/ 2>/dev/null; then
    echo "ERROR: the step loop executes a bundled plan without the re-observing runner."
    SMOKE_FAIL=1
fi
if ! grep -q "carriedPlanHint" core-agent/src/main/kotlin/com/aniob/core/domain/ExecutorRequest.kt 2>/dev/null; then
    echo "ERROR: the held-back part of a plan is no longer carried into the next planning step."
    SMOKE_FAIL=1
fi

# 5e. Package receiver reflex
if [ ! -f app/src/main/java/com/aniob/app/receiver/AniobPackageReceiver.kt ]; then
    echo "ERROR: AniobPackageReceiver reflex missing."
    SMOKE_FAIL=1
fi

# 5f. Shared AskPolicy approval gate + notification OTP blocklist
# Consequence prompts now flow through AskPolicy and the one StepPipeline confirmation gate;
# they are no longer represented as a special case in AniobSafetyInterceptor.
if ! grep -q "AskPolicy.decide" core-agent/src/main/kotlin/com/aniob/core/safety/AniobSafetyInterceptor.kt 2>/dev/null || \
   ! grep -q "if (approvalVerdict.requiresConfirmation)" core-agent/src/main/kotlin/com/aniob/core/execution/StepPipeline.kt 2>/dev/null; then
    echo "ERROR: shared AskPolicy/StepPipeline confirmation gate missing."
    SMOKE_FAIL=1
fi
if [ ! -f app/src/main/java/com/aniob/app/service/AniobNotificationListenerService.kt ]; then
    echo "ERROR: NotificationListenerService OTP reflex missing."
    SMOKE_FAIL=1
fi

# 5g. Omniroute JsonMode gate
if ! grep -q "response_format" app/src/main/java/com/aniob/app/provider/AniobOmniRouteProvider.kt 2>/dev/null; then
    echo "ERROR: Omniroute JsonMode (response_format) missing."
    SMOKE_FAIL=1
fi

if [ "$SMOKE_FAIL" -eq 1 ]; then
    echo "ERROR: Prompt-4 smoke M0 pins failed (see above)."
    exit 1
fi
echo "Prompt-4 smoke M0 pins verified (streaming vision, reflexes, watchdog, safety)."

# 6. User-Facing Copy, Color, Touch Target, and Component Kit Guard (UX-7)
echo "[Guard 6/8] Verifying user-facing copy, colors, and touch targets..."
bash scripts/ux-copy-guard.sh

# 7. Model Catalog URL Health Guard
echo "[Guard 7/8] Verifying model catalog URLs..."
bash scripts/check-catalog-urls.sh

# 8. Repository hygiene: secrets and build outputs stay out of the index
echo "[Guard 8/8] Verifying repository hygiene (no secrets, env files or build outputs)..."
HYGIENE_FAIL=0

TRACKED_FORBIDDEN=$(git ls-files | grep -E '(^|/)\.env$|(^|/)\.env\.(local|development|production|staging|test)$|\.(jks|keystore|p12|pfx|pem|key)$|(^|/)local\.properties$|(^|/)\.gradle/|(^|/)build/|(^|/)\.idea/|(^|/)\.vscode/|\.(apk|aab|ap_|dex|class|iml|hprof|log)$' || true)
if [ -n "$TRACKED_FORBIDDEN" ]; then
    echo "ERROR: generated or secret files are tracked (remove with: git rm --cached <path>):"
    echo "$TRACKED_FORBIDDEN"
    HYGIENE_FAIL=1
fi

# File names only: printing the match would copy the credential into the CI log.
TRACKED_SECRETS=$(git grep -lEI '(sk-[0-9a-f]{16,}-[0-9a-f]{4,}-[0-9a-f]{4,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)' -- . ':!*Test.kt' ':!*.md' || true)
if [ -n "$TRACKED_SECRETS" ]; then
    echo "ERROR: credential-shaped string in tracked files (rotate the key, then remove the file):"
    echo "$TRACKED_SECRETS"
    HYGIENE_FAIL=1
fi

BIG_FILES=""
while IFS= read -r -d '' f; do
    size=$(wc -c < "$f" 2>/dev/null || echo 0)
    if [ "${size:-0}" -gt 5242880 ]; then
        BIG_FILES="${BIG_FILES}${size} ${f}"$'\n'
    fi
done < <(git ls-files -z)
if [ -n "$BIG_FILES" ]; then
    echo "ERROR: tracked files over 5 MB (store them outside Git or use Git LFS):"
    printf '%s' "$BIG_FILES"
    HYGIENE_FAIL=1
fi

if [ "$HYGIENE_FAIL" -eq 1 ]; then
    echo "ERROR: repository hygiene guard failed (see above)."
    exit 1
fi
echo "Repository hygiene verified (no env files, keys, build outputs or files over 5 MB)."

# 9. Audit guards: locale-proof text and honest outcomes
echo "[Guard 9] Locale-sensitive text APIs and outcome invariants..."
LOC_BAD=$( (grep -rEn '\.(toLowerCase|toUpperCase|capitalize|decapitalize)\(\)' app/src/main core-agent/src/main --include=*.kt 2>/dev/null || true) )
if [ -n "$LOC_BAD" ]; then
    echo "ERROR: locale-sensitive String API (use AniobTextFold.fold for matching, lowercase(Locale.ROOT) otherwise):"
    echo "$LOC_BAD"
    exit 1
fi
# Matching UI text must go through AniobTextFold: a bare lowercase() on node text is the U-01 pattern.
NODE_LOWER=$( (grep -rEn '(node|it|n)\.(text|contentDescription|tooltip)\.lowercase\(\)' core-agent/src/main --include=*.kt 2>/dev/null || true) )
if [ -n "$NODE_LOWER" ]; then
    echo "ERROR: node text lowercased directly; use AniobTextFold.fold/compact/same:"
    echo "$NODE_LOWER"
    exit 1
fi
if ! grep -q "task_outcomes_guard_" app/src/main/java/com/aniob/app/db/OutcomeGuard.kt; then
    echo "ERROR: SQL outcome guard missing (VERIFIED must require an evidence reference)."
    exit 1
fi
if grep -rEn 'class .*Origin.*EXECUTOR|EXECUTOR\s*[,)]' core-agent/src/main/kotlin/com/aniob/core/outcome 2>/dev/null | grep -v '^\s*//' | grep -q .; then
    echo "ERROR: an EXECUTOR evidence origin was added; the executor may emit actions, never evidence."
    exit 1
fi
echo "Audit guards verified."

echo "=== ALL PREFLIGHT CI GUARDS PASSED SUCCESSFULLY ==="
exit 0

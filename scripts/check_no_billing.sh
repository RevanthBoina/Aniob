#!/usr/bin/env bash
set -euo pipefail

echo "=== Aniob Compliance Check: Verifying No BILLING or Payment SDKs ==="

# Check handwritten source/manifest and dependency roots, never build intermediates.
FORBIDDEN_TERMS=(
    "com.android.vending.BILLING"
    "com.google.android.gms.wallet"
    "com.stripe.android"
    "com.razorpay"
    "com.google.android.gms.ads"
)

FOUND=0
for term in "${FORBIDDEN_TERMS[@]}"; do
    if matches=$(grep -rHF -- "$term" app/src/ core-agent/src/ app/build.gradle.kts core-agent/build.gradle.kts gradle/libs.versions.toml); then
        # Exact declaration only: preserve the runtime safety denylist without allowing SDK use.
        allowed='core-agent/src/main/kotlin/com/aniob/core/safety/AniobSafetyInterceptor.kt:    private const val DENIED_BILLING_PERMISSION = "com.android.vending.BILLING"'
        matches=$(printf '%s\n' "$matches" | grep -vFx -- "$allowed" || true)
        if [ -n "$matches" ]; then
            printf '%s\n' "$matches"
            echo "ERROR: Forbidden billing/ad reference '$term' found in codebase!"
            FOUND=1
        fi
    else
        status=$?
        if [ "$status" -ne 1 ]; then
            echo "ERROR: Could not inspect all billing guard inputs."
            exit "$status"
        fi
    fi
done

if [ "$FOUND" -eq 1 ]; then
    echo "FAILED: Billing or ad dependency detected."
    exit 1
fi

echo "SUCCESS: Zero billing, payment SDK, or ad references found."
exit 0

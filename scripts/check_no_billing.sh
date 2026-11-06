#!/usr/bin/env bash
set -e

echo "=== Aniob Compliance Check: Verifying No BILLING or Payment SDKs ==="

# Check for forbidden permissions in all manifests
FORBIDDEN_TERMS=(
    "com.android.vending.BILLING"
    "com.google.android.gms.wallet"
    "com.stripe.android"
    "com.razorpay"
    "com.google.android.gms.ads"
)

FOUND=0
for term in "${FORBIDDEN_TERMS[@]}"; do
    if grep -rn --exclude-dir=build "$term" app/src/ core-agent/src/ 2>/dev/null; then
        echo "ERROR: Forbidden billing/ad reference '$term' found in codebase!"
        FOUND=1
    fi
done

if [ "$FOUND" -eq 1 ]; then
    echo "FAILED: Billing or ad dependency detected."
    exit 1
fi

echo "SUCCESS: Zero billing, payment SDK, or ad references found."
exit 0

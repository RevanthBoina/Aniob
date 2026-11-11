#!/usr/bin/env bash
set -e

echo "=== Running Aniob CI Gate Verification ==="

# 1. Check no billing
chmod +x scripts/check_no_billing.sh
./scripts/check_no_billing.sh

# 2. Check minSdk >= 30 and targetSdk >= 35 in app/build.gradle.kts
echo "Checking SDK constraints in app/build.gradle.kts..."
if ! grep -q "minSdk = 30" app/build.gradle.kts; then
    echo "ERROR: minSdk must be 30"
    exit 1
fi

echo "SDK version constraints verified."

# 3. Check for core-agent pure JVM rule (zero android.* imports in core-agent)
echo "Checking core-agent purity (zero android.* imports)..."
if grep -rn "import android\." core-agent/src/main/ 2>/dev/null; then
    echo "ERROR: Found android.* import in pure JVM core-agent module!"
    exit 1
fi
echo "core-agent purity verified (0 android.* imports)."

# 4. Check bundled configs/ assets have not drifted from the repo files
chmod +x scripts/config-assets-sync-check.sh
./scripts/config-assets-sync-check.sh

# 5. Check skill library assets have not drifted
chmod +x scripts/skill-assets-sync-check.sh
./scripts/skill-assets-sync-check.sh

echo "=== ALL CI GATES PASSED SUCCESSFULLY ==="
exit 0

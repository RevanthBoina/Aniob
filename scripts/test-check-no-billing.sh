#!/usr/bin/env bash
set -euo pipefail
GUARD="$(cd "$(dirname "$0")" && pwd)/check_no_billing.sh"
FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
cd "$FIXTURE"
mkdir -p app/src/main core-agent/src/main gradle app/build/generated
: > app/build.gradle.kts
: > core-agent/build.gradle.kts
: > gradle/libs.versions.toml
printf '%s\n' 'com.android.vending.BILLING' > app/build/generated/ignored.txt
mkdir -p core-agent/src/main/kotlin/com/aniob/core/safety
printf '%s\n' '    private const val DENIED_BILLING_PERMISSION = "com.android.vending.BILLING"' > core-agent/src/main/kotlin/com/aniob/core/safety/AniobSafetyInterceptor.kt
bash "$GUARD" >/dev/null
for file in app/src/main/AndroidManifest.xml core-agent/src/main/example.kt core-agent/src/main/kotlin/com/aniob/core/safety/AniobSafetyInterceptor.kt app/build.gradle.kts core-agent/build.gradle.kts gradle/libs.versions.toml; do
    printf '%s\n' 'com.android.vending.BILLING' > "$file"
    if bash "$GUARD" >/dev/null; then
        echo "ERROR: billing guard missed $file"
        exit 1
    fi
    : > "$file"
done
rm -rf core-agent/src
if bash "$GUARD" >/dev/null 2>&1; then
    echo "ERROR: billing guard silently ignored a missing source root"
    exit 1
fi
echo "Billing guard regression tests passed."

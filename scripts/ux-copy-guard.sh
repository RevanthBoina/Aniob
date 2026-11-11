#!/usr/bin/env bash
#
# GUARD 6/6 — USER-FACING COPY
# ============================
# docs/UX_COPY.md declares a ban list and says it is "CI-enforced in UX-7".
# It never was. scripts/ci-preflight.sh has five guards and none of them look at a
# single user-visible string, which is why the shipped APK contains:
#
#   AniobOnboardingScreen.kt:180  "M0 Validation Demo"
#   AniobOnboardingScreen.kt:103  "...minimal 32dp execution pill..."
#   AniobOnboardingScreen.kt:72   "...perceives on-screen UI hierarchies and actuates..."
#   AniobSettingsScreen.kt:181    label { Text("OMNIROUTE_API_KEY") }
#   AniobViewModel.kt:1386        badge = "Pre-flight Failed"
#   AniobStatsScreen.kt:315       providerUsed == "FAST_PATH"
#   AniobChatScreen.kt:141        "Execution Steps (Local Zero-Capture)"
#   AniobModelDownloadScreen.kt   "Installable Models (Q4_K_M GGUF)", "Device Capability (8GB Target)"
#
# This guard closes that hole. It only inspects STRING LITERALS THAT REACH A USER —
# i.e. literals inside Text(...)/label/title/placeholder/contentDescription/description
# and every <string> in strings.xml. Internal enum values, route names, log tags and
# comparison keys are explicitly allowed, because banning those would be theatre.
#
# Usage:  bash scripts/ux-copy-guard.sh
set -uo pipefail

FAIL=0
UI_DIR="app/src/main/java/com/aniob/app/ui"
STRINGS="app/src/main/res/values/strings.xml"

# --- 1. Hard ban list from docs/UX_COPY.md -----------------------------------
# Matched case-insensitively inside user-visible literals only.
BANNED=(
  "M0"
  "32dp"
  "hierarchies"
  "actuates"
  "FAST_PATH"
  # Internal provider identifiers (OMNIROUTE_CLOUD, OMNIROUTE_API_KEY) must never be rendered.
  # The product name "OmniRoute" is a proper noun and is allowed on the Cloud AI setup card.
  "OMNIROUTE_"
  "Pre-flight"
  "Preflight"
)

# --- 2. Jargon ban list (the "confusion text" the user reported) -------------
# These are the terms a non-engineer cannot act on. Every one of them currently
# ships on a primary surface.
JARGON=(
  "GGUF"
  "Q4_K_M"
  "Quant"
  "tok/s"
  "MMLU"
  "HumanEval"
  "SLM"
  "\\bRAM\\b"
  "Zero-Capture"
  "UI nodes"
  "foreground service"
  "Base URL"
  "API_KEY"
  "endpoint"
  "Provider Config"
  "telemetry"
  "8GB Target"
)

echo "[Guard 6/6] Verifying user-facing copy..."

# Extract only literals that are rendered to a user.
# Covers:  Text("..")  label = ".."  title = ".."  placeholder  contentDescription
#          description = ".."  actionText = ".."  subtitle = ".."
extract_user_strings() {
  grep -rhnoE \
    '(Text\(|label = |title = |subtitle = |description = |actionText = |placeholder = |contentDescription = |badge = )"[^"]{2,}"' \
    "$UI_DIR" 2>/dev/null
}

check_list() {
  local kind="$1"; shift
  local -a list=("$@")
  for term in "${list[@]}"; do
    # -F fixed string, -i case-insensitive
    local hits
    hits=$(grep -rniE \
      "(Text\(|label = |title = |subtitle = |description = |actionText = |placeholder = |contentDescription = |badge = )\"[^\"]*${term}[^\"]*\"" \
      "$UI_DIR" 2>/dev/null)
    if [ -n "$hits" ]; then
      echo "  ✗ $kind term '${term}' is rendered to users:"
      echo "$hits" | sed 's/^/      /'
      FAIL=1
    fi

    local xml_hits
    xml_hits=$(grep -niE "<string[^>]*>[^<]*${term}[^<]*</string>" "$STRINGS" 2>/dev/null)
    if [ -n "$xml_hits" ]; then
      echo "  ✗ $kind term '${term}' in strings.xml:"
      echo "$xml_hits" | sed 's/^/      /'
      FAIL=1
    fi
  done
}

check_list "BANNED" "${BANNED[@]}"
check_list "JARGON" "${JARGON[@]}"

# --- 3. Hardcoded colours in UI code -----------------------------------------
# Every one of these breaks dark mode and bypasses the agent-state palette.
echo "[Guard 6/6] Verifying no hardcoded colours in UI..."
HARDCODED=$(grep -rn "Color(0x" "$UI_DIR" 2>/dev/null | grep -v "/theme/")
if [ -n "$HARDCODED" ]; then
  echo "  ✗ Hardcoded Color(0x..) outside ui/theme — use AniobTheme.state.* :"
  echo "$HARDCODED" | sed 's/^/      /'
  FAIL=1
fi

# --- 4. Touch targets ---------------------------------------------------------
# WCAG 2.5.5 / Material minimum is 48dp.
echo "[Guard 6/6] Verifying minimum touch targets..."
SMALL=$(awk '
  /IconButton|FilledIconButton|\.clickable/ { ctx=4 }
  ctx>0 && /Modifier\.size\(([1-9]|[1-3][0-9]|4[0-7])\.dp\)/ {
      printf "%s:%d:%s\n", FILENAME, FNR, $0
  }
  ctx>0 { ctx-- }
' $(find "$UI_DIR" -name '*.kt') 2>/dev/null)
if [ -n "$SMALL" ]; then
  echo "  ✗ Interactive elements sized below the 48dp minimum:"
  echo "$SMALL" | sed 's/^/      /'
  FAIL=1
fi

# --- 5. Orphaned shared components -------------------------------------------
# AniobComponents.kt exports StatusBanner/EmptyState/ErrorState/SectionCard and
# ZERO screens import it, so each screen hand-rolls its own banner. That is the
# root cause of "every screen looks slightly different".
echo "[Guard 6/6] Verifying the shared component kit is actually used..."
KIT_USE=$(grep -rl "ui.components" "$UI_DIR" 2>/dev/null | grep -v "AniobComponents.kt" | wc -l)
if [ "$KIT_USE" -eq 0 ]; then
  echo "  ✗ ui/components/AniobComponents.kt is exported but imported by 0 screens."
  echo "      Screens are hand-rolling banners instead. Migrate or delete the kit."
  FAIL=1
fi

if [ "$FAIL" -eq 1 ]; then
  echo "=== UX COPY GUARD FAILED ==="
  exit 1
fi
echo "UX copy guard passed."
exit 0

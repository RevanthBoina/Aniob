#!/usr/bin/env bash
set -e

# Verify adb command is available, falling back to ANDROID_HOME / standard paths if not on PATH
if ! command -v adb >/dev/null 2>&1; then
    if [ -n "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
        export PATH="$ANDROID_HOME/platform-tools:$PATH"
    elif [ -n "$ANDROID_HOME" ] && [ -x "$ANDROID_HOME/platform-tools/adb.exe" ]; then
        export PATH="$ANDROID_HOME/platform-tools:$PATH"
    elif [ -n "$ANDROID_SDK_ROOT" ] && [ -x "$ANDROID_SDK_ROOT/platform-tools/adb" ]; then
        export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
    elif [ -n "$LOCALAPPDATA" ] && [ -x "$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" ]; then
        export PATH="$LOCALAPPDATA/Android/Sdk/platform-tools:$PATH"
    fi
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: 'adb' executable not found in PATH." >&2
    echo "Please ensure Android SDK platform-tools is installed and added to PATH." >&2
    exit 1
fi

# Verify connected device
DEVICE_OUTPUT=$(adb devices | grep -v "^List of devices" | grep "device$" || true)
if [ -z "$DEVICE_OUTPUT" ]; then
    echo "ERROR: No authorized Android device connected via ADB." >&2
    echo "Please connect your phone, enable USB debugging, and authorize this machine." >&2
    exit 1
fi

PACKAGE="com.aniob.app"
REMOTE_DIR="/sdcard/Android/data/${PACKAGE}/files"
RUNS_CSV="research_runs.csv"
SUMMARY_CSV="research_summary_table.csv"

echo "=== Pulling Research Telemetry from ${PACKAGE} ==="

if adb shell "test -f ${REMOTE_DIR}/${RUNS_CSV}" >/dev/null 2>&1; then
    adb pull "${REMOTE_DIR}/${RUNS_CSV}" ./"${RUNS_CSV}"
    echo "Successfully pulled ${RUNS_CSV} ($(wc -l < "${RUNS_CSV}" 2>/dev/null || echo "?") lines)"
else
    echo "Notice: Remote ${RUNS_CSV} not found on device at ${REMOTE_DIR}/${RUNS_CSV}."
fi

if adb shell "test -f ${REMOTE_DIR}/${SUMMARY_CSV}" >/dev/null 2>&1; then
    adb pull "${REMOTE_DIR}/${SUMMARY_CSV}" ./"${SUMMARY_CSV}"
    echo "Successfully pulled ${SUMMARY_CSV}"
    echo ""
    echo "=== Summary Table ==="
    cat ./"${SUMMARY_CSV}"
    echo ""
else
    echo "Notice: Remote ${SUMMARY_CSV} not found on device at ${REMOTE_DIR}/${SUMMARY_CSV}."
fi

echo "Pull operation complete."

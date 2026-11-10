#!/usr/bin/env bash
set -e

echo "=== Checking Skill Assets Drift ==="

SRC_DIR="skill_library/skills"
ASSET_DIR="app/src/main/assets/aniob_skill_library/skills"

if [ ! -d "$SRC_DIR" ]; then
    echo "ERROR: Source skills directory '$SRC_DIR' not found."
    exit 1
fi

if [ ! -d "$ASSET_DIR" ]; then
    echo "ERROR: Asset skills directory '$ASSET_DIR' not found."
    exit 1
fi

DIFF_OUTPUT=$(diff -r -u "$SRC_DIR" "$ASSET_DIR" || true)

if [ -n "$DIFF_OUTPUT" ]; then
    echo "ERROR: Drift detected between '$SRC_DIR' and '$ASSET_DIR':"
    echo "$DIFF_OUTPUT"
    exit 1
fi

echo "SUCCESS: skill_library and assets are in 100% sync."
exit 0

#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Kofipod release orchestrator.
#
# Usage:
#   ./scripts/release.sh [patch|minor|major] [--no-git]
#
# Steps:
#   1. Verify clean working tree (skipped with --no-git).
#   2. Verify keystore.properties + keystore/release.jks exist.
#   3. Bump version.properties via :composeApp:bumpVersion.
#   4. Build signed release APK + AAB.
#   5. Copy artifacts to dist/, print SHA-256 of each.
#   6. Commit version.properties and tag v<VERSION_NAME> (skipped with --no-git).

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

BUMP_TYPE="patch"
NO_GIT=0
for arg in "$@"; do
    case "$arg" in
        patch|minor|major) BUMP_TYPE="$arg" ;;
        --no-git) NO_GIT=1 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
blue()  { printf '\033[34m%s\033[0m\n' "$*"; }

# 1. clean tree
if [ "$NO_GIT" = "0" ]; then
    if [ -n "$(git status --porcelain)" ]; then
        red "Working tree is dirty. Commit or stash first, or pass --no-git."
        git status --short
        exit 1
    fi
fi

# 2. keystore present
if [ ! -f "keystore.properties" ] || [ ! -f "keystore/release.jks" ]; then
    red "Missing keystore.properties or keystore/release.jks."
    red "See README 'Release' section for one-time keystore setup."
    exit 1
fi

# 3. bump version
blue "Bumping version ($BUMP_TYPE)..."
./gradlew -q :composeApp:bumpVersion -Ptype="$BUMP_TYPE"

VERSION_NAME=$(awk -F= '/^VERSION_NAME=/ {print $2}' version.properties)
VERSION_CODE=$(awk -F= '/^VERSION_CODE=/ {print $2}' version.properties)
green "Building v${VERSION_NAME} (${VERSION_CODE})"

# 4. build APK + AAB
./gradlew :composeApp:assembleRelease :composeApp:bundleRelease

# 5. copy + checksum
mkdir -p dist
APK_SRC="composeApp/build/outputs/apk/release/composeApp-release.apk"
AAB_SRC="composeApp/build/outputs/bundle/release/composeApp-release.aab"
APK_DST="dist/kofipod-${VERSION_NAME}-${VERSION_CODE}-release.apk"
AAB_DST="dist/kofipod-${VERSION_NAME}-${VERSION_CODE}-release.aab"

if [ ! -f "$APK_SRC" ]; then
    red "Expected APK not found: $APK_SRC"
    exit 1
fi
if [ ! -f "$AAB_SRC" ]; then
    red "Expected AAB not found: $AAB_SRC"
    exit 1
fi

cp "$APK_SRC" "$APK_DST"
cp "$AAB_SRC" "$AAB_DST"

green "Artifacts:"
shasum -a 256 "$APK_DST" "$AAB_DST"

# 6. commit + tag
if [ "$NO_GIT" = "0" ]; then
    git add version.properties
    git commit -m "chore: release v${VERSION_NAME} (${VERSION_CODE})"
    git tag "v${VERSION_NAME}"
    green "Committed and tagged v${VERSION_NAME}."
    blue "Next: git push && git push --tags"
else
    blue "Skipped git commit + tag (--no-git)."
fi

#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Kofipod release orchestrator.
#
# Usage:
#   ./scripts/release.sh [patch|minor|major] [--no-git] [--publish]
#
# Steps:
#   1. Verify clean working tree (skipped with --no-git).
#   2. Verify keystore.properties + keystore/release.jks exist.
#   3. Run lintVitalRelease — fail fast BEFORE bumping, so a lint failure
#      doesn't leave version.properties dirty.
#   4. Bump version.properties via :composeApp:bumpVersion.
#   5. Build signed release APK + AAB.
#   6. Copy artifacts to dist/, print SHA-256 of each.
#   7. Commit version.properties and tag v<VERSION_NAME> (skipped with --no-git).
#   8. With --publish: push commit + tag, then create a GitHub release with
#      auto-generated notes and attach the signed APK.

set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

BUMP_TYPE="patch"
NO_GIT=0
PUBLISH=0
for arg in "$@"; do
    case "$arg" in
        patch|minor|major) BUMP_TYPE="$arg" ;;
        --no-git) NO_GIT=1 ;;
        --publish) PUBLISH=1 ;;
        *) echo "unknown arg: $arg" >&2; exit 2 ;;
    esac
done

if [ "$PUBLISH" = "1" ] && [ "$NO_GIT" = "1" ]; then
    echo "--publish and --no-git cannot be combined (publish requires a tag)." >&2
    exit 2
fi

if [ "$PUBLISH" = "1" ] && ! command -v gh >/dev/null 2>&1; then
    echo "--publish requires the GitHub CLI ('gh'). Install via 'brew install gh'." >&2
    exit 2
fi

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

# 3. lint-gate release variant before bumping. `assembleRelease` below will
#    run this anyway, but doing it up front means a lint failure doesn't
#    leave version.properties bumped and the tree dirty.
blue "Running release-variant lint..."
./gradlew :composeApp:lintVitalRelease

# 4. bump version
blue "Bumping version ($BUMP_TYPE)..."
./gradlew -q :composeApp:bumpVersion -Ptype="$BUMP_TYPE"

VERSION_NAME=$(awk -F= '/^VERSION_NAME=/ {print $2}' version.properties)
VERSION_CODE=$(awk -F= '/^VERSION_CODE=/ {print $2}' version.properties)
green "Building v${VERSION_NAME} (${VERSION_CODE})"

# 4. build APK + AAB
./gradlew :composeApp:assembleRelease :composeApp:bundleRelease

# 5. copy + checksum
mkdir -p dist
APK_SRC="composeApp/build/outputs/apk/release/kofipod-${VERSION_NAME}-${VERSION_CODE}-release.apk"
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
    if [ "$PUBLISH" = "0" ]; then
        blue "Next: git push && git push --tags"
    fi
else
    blue "Skipped git commit + tag (--no-git)."
fi

# 7. publish to GitHub
if [ "$PUBLISH" = "1" ]; then
    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    blue "Pushing ${BRANCH} and tag v${VERSION_NAME}..."
    git push origin "$BRANCH"
    git push origin "v${VERSION_NAME}"

    blue "Creating GitHub release v${VERSION_NAME}..."
    gh release create "v${VERSION_NAME}" \
        --title "v${VERSION_NAME}" \
        --generate-notes \
        "$APK_DST"
    green "Published v${VERSION_NAME} to GitHub."
fi

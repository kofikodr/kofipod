#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Kofipod release orchestrator.
#
# Usage:
#   ./scripts/release.sh [patch|minor|major] [--no-git] [--publish]
#
# Two flavors ship from this repo:
#   - play: Play Store revenue build. We build the AAB only (Play Console
#     distributes from .aab; the play APK isn't useful to end users).
#   - foss: sideload-friendly build. We build the APK only (this is what
#     gets attached to the GitHub release).
#
# Steps:
#   1. Verify clean working tree (skipped with --no-git).
#   2. Verify keystore.properties + keystore/release.jks exist.
#   3. Run lintVitalPlayRelease + lintVitalFossRelease — fail fast BEFORE
#      bumping, so a lint failure doesn't leave version.properties dirty.
#   4. Bump version.properties via :composeApp:bumpVersion.
#   5. Build the play AAB and the foss APK (signed).
#   6. Copy artifacts to dist/, print SHA-256 of each.
#   7. Commit version.properties and tag v<VERSION_NAME> (skipped with --no-git).
#   8. With --publish: push commit + tag, then create a GitHub release with
#      auto-generated notes and attach the foss APK. The play AAB stays in
#      dist/ for manual upload to Play Console.

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

# 3. lint-gate both release variants before bumping. assemble/bundle below
#    will run this anyway, but doing it up front means a lint failure doesn't
#    leave version.properties bumped and the tree dirty.
blue "Running release-variant lint (play + foss)..."
./gradlew :composeApp:lintVitalPlayRelease :composeApp:lintVitalFossRelease

# 4. bump version
blue "Bumping version ($BUMP_TYPE)..."
./gradlew -q :composeApp:bumpVersion -Ptype="$BUMP_TYPE"

VERSION_NAME=$(awk -F= '/^VERSION_NAME=/ {print $2}' version.properties)
VERSION_CODE=$(awk -F= '/^VERSION_CODE=/ {print $2}' version.properties)
green "Building v${VERSION_NAME} (${VERSION_CODE}) — play AAB + foss APK"

# 5. build play AAB (Play Console) + foss APK (GitHub release).
./gradlew :composeApp:bundlePlayRelease :composeApp:assembleFossRelease

# 6. copy + checksum. AGP nests flavored outputs under the flavor name and
#    the per-flavor versionNameSuffix lands in the APK filename via the
#    output template in composeApp/build.gradle.kts.
mkdir -p dist
PLAY_AAB_SRC="composeApp/build/outputs/bundle/playRelease/composeApp-play-release.aab"
FOSS_APK_SRC="composeApp/build/outputs/apk/foss/release/kofipod-foss-${VERSION_NAME}-foss-${VERSION_CODE}-release.apk"
PLAY_AAB_DST="dist/kofipod-play-${VERSION_NAME}-${VERSION_CODE}-release.aab"
FOSS_APK_DST="dist/kofipod-foss-${VERSION_NAME}-foss-${VERSION_CODE}-release.apk"

if [ ! -f "$PLAY_AAB_SRC" ]; then
    red "Expected play AAB not found: $PLAY_AAB_SRC"
    exit 1
fi
if [ ! -f "$FOSS_APK_SRC" ]; then
    red "Expected foss APK not found: $FOSS_APK_SRC"
    exit 1
fi

cp "$PLAY_AAB_SRC" "$PLAY_AAB_DST"
cp "$FOSS_APK_SRC" "$FOSS_APK_DST"

green "Artifacts:"
shasum -a 256 "$PLAY_AAB_DST" "$FOSS_APK_DST"

# 7. commit + tag
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

# 8. publish to GitHub
if [ "$PUBLISH" = "1" ]; then
    BRANCH=$(git rev-parse --abbrev-ref HEAD)
    blue "Pushing ${BRANCH} and tag v${VERSION_NAME}..."
    git push origin "$BRANCH"
    git push origin "v${VERSION_NAME}"

    FOSS_NOTES=$(cat <<EOF
This is the **FOSS** build of Kofipod — Pro features are unlocked, no Play Billing.

Application ID: \`com.kofikodr.kofipod.foss\` (installs alongside the Play Store build as a separate app, not as an upgrade).
EOF
)
    blue "Creating GitHub release v${VERSION_NAME} (attaching foss APK)..."
    gh release create "v${VERSION_NAME}" \
        --title "v${VERSION_NAME}" \
        --generate-notes \
        --notes "$FOSS_NOTES" \
        "$FOSS_APK_DST"
    green "Published v${VERSION_NAME} to GitHub."
    blue "Play AAB ready for manual Play Console upload: $PLAY_AAB_DST"
fi

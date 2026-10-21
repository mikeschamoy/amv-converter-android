#!/usr/bin/env bash
# Termux: fetch only the APK built from the EXACT local HEAD. Never opens installer.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
REPO='mikeschamoy/amv-converter-android'
DEST="${HOME}/storage/shared/Download"
command -v gh >/dev/null || { echo 'Install GitHub CLI: pkg install gh'; exit 1; }
command -v unzip >/dev/null || { echo 'Install unzip: pkg install unzip'; exit 1; }
mkdir -p "$DEST"
SHA="$(git rev-parse HEAD)"
RUN="$(gh run list -R "$REPO" --branch main --limit 35 --json databaseId,headSha \
  --jq "[.[] | select(.headSha == \"$SHA\") | .databaseId][0] // empty")"
if [ -z "$RUN" ]; then
  echo "No GitHub Actions run for commit $SHA yet." >&2
  gh run list -R "$REPO" --limit 5
  exit 1
fi
echo "Checking GitHub Actions run $RUN for commit ${SHA:0:10}"
if ! gh run watch "$RUN" -R "$REPO" --exit-status; then
  echo 'Build failed. Recent error lines:' >&2
  gh run view "$RUN" -R "$REPO" --log-failed | tail -n 80 || true
  exit 1
fi
TMP="$(mktemp -d "$DEST/.clipamv-apk-XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
gh run download "$RUN" -R "$REPO" -n ClipAMV-debug -D "$TMP"
APK="$(find "$TMP" -type f -name '*.apk' -print -quit)"
[ -n "$APK" ] && [ -s "$APK" ] || { echo 'Missing/empty APK artifact' >&2; exit 1; }
unzip -tq "$APK" >/dev/null || { echo 'APK archive integrity failed' >&2; exit 1; }
unzip -Z1 "$APK" | grep -qx 'AndroidManifest.xml' || { echo 'APK missing AndroidManifest.xml' >&2; exit 1; }
VERSION="$(grep 'versionName =' app/build.gradle.kts | head -1 | cut -d\" -f2)"
OUT="$DEST/ClipAMV-v${VERSION}-${SHA:0:7}.apk"
cp "$APK" "$OUT.tmp"
mv -f "$OUT.tmp" "$OUT"
echo "APK verified and saved: $OUT"
ls -lh "$OUT"
sha256sum "$OUT"
if command -v aapt >/dev/null; then
  aapt dump badging "$OUT" 2>/dev/null | grep -m1 '^package:' || true
fi
echo 'No installer was launched. Open APK manually with Files when ready.'

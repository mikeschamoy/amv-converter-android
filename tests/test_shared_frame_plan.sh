#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v javac >/dev/null
command -v ffmpeg >/dev/null
T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
J="$ROOT/app/src/main/java/com/mikeschamoy/amvconverter"
javac -d "$T" "$J/PresetEngine.java" "$J/PictureContentGuard.java" "$J/FramePlanResolver.java" "$ROOT/tests/SharedFramePlanRegression.java"
java -cp "$T" com.mikeschamoy.amvconverter.SharedFramePlanRegression > "$T/plan.txt"
head -1 "$T/plan.txt"
F="$(sed -n 's/^CORRECTED_FILTER=//p' "$T/plan.txt")"
[ -n "$F" ] || exit 1
ffmpeg -hide_banner -loglevel info -f lavfi -i testsrc2=size=640x280:rate=25 \
  -vf "pad=640:360:0:40:black,$F,cropdetect=limit=24:round=2:reset=0" \
  -frames:v 60 -an -f null - > "$T/ff.log" 2>&1
CROP="$(grep 'crop=' "$T/ff.log" | tail -1 | sed -E 's/.*crop=([0-9]+:[0-9]+:[0-9]+:[0-9]+).*/\1/')"
echo "Real FFmpeg corrected picture: $CROP"
[ "$CROP" = '256:192:64:24' ] || { echo 'FAIL: visible 192px was not achieved'; exit 1; }
# Test the independently verified output-level correction if source inversion is unsafe.
FALLBACK="$(sed -n 's/^FALLBACK_FILTER=//p' "$T/plan.txt")"
ffmpeg -hide_banner -loglevel info -f lavfi -i testsrc2=size=640x280:rate=25 \
  -vf "pad=640:360:0:40:black,$FALLBACK,cropdetect=limit=24:round=2:reset=0" \
  -frames:v 60 -an -f null - > "$T/fallback.log" 2>&1
FCROP="$(grep 'crop=' "$T/fallback.log" | tail -1 | sed -E 's/.*crop=([0-9]+:[0-9]+:[0-9]+:[0-9]+).*/\1/')"
echo "Real FFmpeg output-level fallback: $FCROP"
[ "$FCROP" = '256:184:64:28' ] || { echo 'FAIL: fallback did not fill 192px'; exit 1; }
# Catch reintroducing a separate uncorrected preview filter.
grep -q 'FramePlanResolver.resolve' "$J/MainActivity.java"
grep -q 'FramePlanResolver.resolve' "$J/ConversionService.java"
if grep -q 'private PresetEngine.Preset repairStableLetterboxIfFound' "$J/ConversionService.java";then
 echo 'FAIL: duplicate resolver still exists';exit 1
fi
echo 'PASS: preview and encoder both use shared resolver, real FFmpeg content-aware picture'

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
command -v ffmpeg >/dev/null || { echo 'ERROR: ffmpeg is required for pixel-height test';exit 1; }
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" "$ROOT/app/src/main/java/com/mikeschamoy/amvconverter/PresetEngine.java" "$ROOT/tests/Fixed216FrameRegression.java"
java -cp "$TMP" com.mikeschamoy.amvconverter.Fixed216FrameRegression > "$TMP/filters.txt"
for WHICH in PLAIN CROPPED; do
 VF="$(sed -n "s/^FILTER_${WHICH}=//p" "$TMP/filters.txt")"
 [ -n "$VF" ] || { echo "Missing $WHICH filter";exit 1; }
 ffmpeg -hide_banner -loglevel info -f lavfi -i testsrc2=size=640x280:rate=25 \
     -vf "pad=640:360:0:40:black,$VF,cropdetect=limit=24:round=2:reset=0" \
     -frames:v 65 -an -f null - >"$TMP/$WHICH.log" 2>&1
 CROP="$(grep 'crop=' "$TMP/$WHICH.log" | tail -1 | sed -E 's/.*crop=([0-9]+:[0-9]+:[0-9]+:[0-9]+).*/\1/')"
 echo "$WHICH observed crop: $CROP"
 if [ "$WHICH" = CROPPED ];then
  [ "$CROP" = '256:216:64:12' ] || { echo 'FAIL corrected frame not full 216';exit 1; }
 else
  case "$CROP" in 256:1??:64:*) ;; *) echo 'FAIL test did not reproduce undersized picture'; exit 1;; esac
 fi
done
echo 'PASS actual FFmpeg 16:9 content height 216 after letterbox correction and X64 viewport'

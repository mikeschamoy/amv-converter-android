#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
J="$ROOT/app/src/main/java/com/mikeschamoy/amvconverter"
javac -d "$T" "$J/PresetEngine.java" "$J/PictureContentGuard.java" "$J/FramePlanResolver.java" "$ROOT/tests/SharedFramePlanRegression.java"
java -cp "$T" com.mikeschamoy.amvconverter.SharedFramePlanRegression >"$T/filters"
FILTER="$(sed -n 's/^CORRECTED_FILTER=//p' "$T/filters")"
[ -n "$FILTER" ]
ffmpeg -nostdin -hide_banner -loglevel error -y \
  -f lavfi -i 'testsrc2=size=640x280:rate=25' \
  -f lavfi -i 'sine=frequency=440:sample_rate=22050' \
  -filter_complex "[0:v]pad=640:360:0:40:black,$FILTER[v]" \
  -map '[v]' -map 1:a -t 2 -r 25 -c:v amv -pix_fmt yuvj420p \
  -c:a adpcm_ima_amv -ac 1 -ar 22050 -block_size 882 "$T/actual.amv"
META="$(ffprobe -v error -show_entries stream=codec_name,width,height -select_streams v:0 -of default=nw=1 "$T/actual.amv")"
printf '%s\n' "$META"
echo "$META" | grep -q 'width=320'
echo "$META" | grep -q 'height=240'
ffmpeg -nostdin -hide_banner -loglevel info -ss 0.5 -i "$T/actual.amv" \
  -vf 'cropdetect=limit=24:round=2:reset=0' -frames:v 20 -an -f null - >"$T/decoded.log" 2>&1
CROP="$(grep 'crop=' "$T/decoded.log"|tail -1|sed -E 's/.*crop=([0-9]+:[0-9]+:[0-9]+:[0-9]+).*/\1/')"
echo "Actual AMV encoded and decoded picture: $CROP"
[ "$CROP" = '256:216:64:12' ]
echo 'PASS: real AMV file encodes and decodes to full 256x216 picture at X64'

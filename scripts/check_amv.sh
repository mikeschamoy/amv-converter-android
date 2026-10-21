#!/usr/bin/env bash
# Termux AMV quality gate. With --deep, decodes the entire file and counts video frames.
set -euo pipefail
DEEP=false
if [ "${1:-}" = '--deep' ]; then DEEP=true; shift; fi
FILE="${1:-}"
if [ -z "$FILE" ]; then
  DIR="$HOME/storage/shared/Download"
  # Fast, nonrecursive scan. Quoted names with spaces are safe.
  FILE="$(find "$DIR" -maxdepth 1 -type f -iname '*.amv' -printf '%T@ %p\n' | sort -nr | sed -n '1p' | cut -d' ' -f2-)"
fi
[ -n "$FILE" ] && [ -s "$FILE" ] || { echo 'Pass the AMV path, or place one in Downloads.' >&2; exit 1; }
command -v ffprobe >/dev/null && command -v ffmpeg >/dev/null || { echo 'Run: pkg install ffmpeg' >&2; exit 1; }
echo "CHECKING: $FILE"
VIDEO="$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name,width,height,avg_frame_rate -of default=nw=1 "$FILE")"
AUDIO="$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name,sample_rate,channels -of default=nw=1 "$FILE")"
printf '%s\n%s\n' "$VIDEO" "$AUDIO"
for VALUE in codec_name=amv width=320 height=240; do
  grep -qx "$VALUE" <<< "$VIDEO" || { echo "FAIL: expected $VALUE" >&2; exit 1; }
done
for VALUE in codec_name=adpcm_ima_amv sample_rate=22050 channels=1; do
  grep -qx "$VALUE" <<< "$AUDIO" || { echo "FAIL: expected $VALUE" >&2; exit 1; }
done
# A successful process without decoded frames can be a false positive.
FIRST="$(ffmpeg -hide_banner -v error -i "$FILE" -frames:v 1 -an -f framemd5 -)"
grep -Eq '^0,[[:space:]]*[0-9-]+,' <<< "$FIRST" || { echo 'FAIL: video frame could not be decoded' >&2; exit 1; }
echo 'PASS: codec, dimensions, audio and decoded first frame'
# Additional structural + near-end seek audit. Unlike a generic exit-code check,
# this requires a real decoded frame at multiple late-video positions.
python3 "$(dirname "$0")/check_amv_seek.py" "$FILE" $("$DEEP" && echo --deep || true)

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
J="$ROOT/app/src/main/java/com/mikeschamoy/amvconverter"
javac -d "$T" "$J/PresetEngine.java" "$ROOT/tests/ContentAwareAutoRegression.java"
java -cp "$T" com.mikeschamoy.amvconverter.ContentAwareAutoRegression > "$T/filters"
grep 'PASS:' "$T/filters"
for config in '16_9 360 0 216 12' 'CINEMA_192 280 40 192 24' 'CINEMA_184 272 44 184 28'; do
    read -r label pic pad want y <<< "$config"
    filter=$(sed -n "s/^FILTER_${label}=//p" "$T/filters")
    [ -n "$filter" ] || exit 1
    ffmpeg -nostdin -hide_banner -loglevel error -y \
      -f lavfi -i "testsrc2=size=640x${pic}:rate=25" \
      -f lavfi -i 'sine=frequency=440:sample_rate=22050' \
      -filter_complex "[0:v]pad=640:360:0:${pad}:black,${filter}[v]" \
      -map '[v]' -map 1:a -t 1 -r 25 -c:v amv -pix_fmt yuvj420p \
      -c:a adpcm_ima_amv -ac 1 -ar 22050 -block_size 882 "$T/${label}.amv"
    ffmpeg -nostdin -hide_banner -loglevel info -ss 0.2 -i "$T/${label}.amv" \
      -vf 'cropdetect=limit=24:round=2:reset=0' -frames:v 17 -an -f null - > "$T/${label}.log" 2>&1
    measured=$(grep 'crop=' "$T/${label}.log" | tail -1 | sed -E 's/.*crop=([0-9]+:[0-9]+:[0-9]+:[0-9]+).*/\1/')
    echo "$label decoded AMV visible crop: $measured"
    [ "$measured" = "256:${want}:64:${y}" ] || { echo "FAIL: $label output differs"; exit 1; }
done

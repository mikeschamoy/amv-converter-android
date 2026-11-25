#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
AAR=app/libs/ffmpeg-kit-morph-full-16kb.aar
SHA=007b25f3d6a47a4bb8d0fe37bdf6a6469814d75d2c0166e7c4d605d2aea63f43
URL=https://github.com/leventyayla/ffmpeg-kit/releases/download/v6.0-4-morph-full-16kb/ffmpeg-kit-morph-full-16kb.aar
if ! printf '%s  %s\n' "$SHA" "$AAR" | sha256sum -c --status; then
  curl --fail --location --retry 3 --connect-timeout 30 "$URL" -o "$AAR.part"
  printf '%s  %s\n' "$SHA" "$AAR.part" | sha256sum -c -
  mv "$AAR.part" "$AAR"
fi
python3 scripts/verify_arm32_engine.py "$AAR"

# ClipAMV v0.6.5 — full-length seek-safety gate

This update is a **compatibility audit**, not a new AMV format. It preserves the established 320×240 AMV video, 22,050 Hz mono ADPCM, FPS/block-size combinations, Gen 2 calibration, and picture/framing presets.

## What changes

- The application now scans the **entire** locally staged AMV for its RIFF AMV header, correct 320×240 frame rate, strictly alternating video and audio packets, consistent ADPCM packet size, every JPEG frame's start/end markers, exact final `AMV_END_` marker, and agreement between header duration, frame count, and requested runtime.
- Before publishing the output, it decodes a real frame at 10%, 50%, 75%, 90%, 95%, 98%, and near the file's end (the exact set varies for short clips). A successful FFmpeg process that emits **zero frames** no longer counts as a passing seek.
- A conversion with a detected seek/integrity failure is **not** marked successful or published. Diagnostics/history tell you which validation failed.
- `scripts/check_amv_seek.py` can test an existing `.amv` from Termux; `scripts/check_amv.sh` now runs that audit as well. `--deep` additionally decodes all frames.
- Fixed the unmatched quote in `scripts/download_latest_apk.sh` while retaining the **download-only** behavior (no installation popup).

## Fundamental format limitation

FFmpeg's AMV muxer writes no standard `idx1` seek index. Some proprietary MP3 firmwares use different or fragile seek algorithms. Even a passing full structural audit, true late-frame decode, and successful copy cannot guarantee every fast-forward position on the physical Gen 2 player. **Do not add a conventional AVI index to these AMVs without confirming the player supports it.** This is not a universal cure for firmware parsing bugs.

## On-phone tests

1. Choose a normal 15-second YouTube sample, then Fit/Standard/25 FPS and convert.
2. Inspect the resulting AMV with `bash scripts/check_amv.sh <file.amv>`.
3. Check 15, 25, and 30 FPS clips, both short and a full episode.
4. Copy the AMV to your MP3 player. Confirm playback and fast-forward at approximately 25%, 50%, 75%, 90%, 95%, and **within the final 10 seconds**.
5. Compare `sha256sum` of source and destination to rule out transfer corruption, if both files are accessible in Termux.
6. Keep an example of any AMV that passes computer QA but fails on the player. It may reveal a player-specific frame size/seek limitation requiring a separate firmware-compatible fix.

## Termux checks

```bash
cd ~/storage/shared/Download/AMVConverterAndroid
bash scripts/check_amv.sh "$HOME/storage/shared/Download/YourVideo.amv"
bash scripts/check_amv.sh --deep "$HOME/storage/shared/Download/YourVideo.amv"
```

Deep checks can be time-consuming on long videos. Test after conversion; do not run parallel to an active encode.

## Local validation for the patch

- 48 framing/preset regression cases and 8 command checks.
- Java byte-structure regression accepted a complete AMV and rejected damaged footer and truncated packet cases.
- Real 30 FPS 6:46 Acura AMV passed late-seek checks at 50%, 90%, 95%, 98%, and within 1.5 seconds of the end.
- Real 15/25/30 FPS encoded sample clips passed both AMV structure and framecrc late-frame checks.

**Still pending:** GitHub Actions full Android compilation; Motorola live conversion; physical Gen 2 seek behavior.

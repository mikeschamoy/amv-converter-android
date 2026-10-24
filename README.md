## ClipAMV v0.7.0 (Final candidate)

Convert video to Ruizu/Clip Sport Gen 2 compatible AMV on Android. **Auto** recommends a preset for each video, **Customize** reveals manual framing/picture quality/FPS/subtitles and diagnostics, and batch conversion supports **Pause after current file → Resume remaining files** without reconverting completed outputs. Converted AMVs are verified for packet structure and seekability before publishing. Files are saved in Downloads by default and display names are shortened to 16 characters (excluding `.amv`).

**APK download:** GitHub Actions → **ClipAMV Final APK** → artifact **ClipAMV-Final-APK** → `ClipAMV.apk` (debug-signed). **Required before public release:** verify Android build and on-player pause/resume/seek QA. See `CLIPAMV_FINAL_RELEASE_NOTES.md`.

---

# ClipAMV v0.6.4 — release candidate

This candidate builds on v0.6.3 and preserves the 320×240 Gen 2 AMV presets,
calibrated preview, Auto/Sharp/Smooth, Fit/Fill, and redesigned launcher.
It improves output safety, crash recovery, and performance/thread defaults.
**It is not certified final until GitHub Actions and physical Motorola/MP3 tests pass.**

Termux developer checks: `bash scripts/test_presets.sh`

Download the exact GitHub build (no installer prompt): `bash scripts/download_latest_apk.sh`

Check a finished AMV: `bash scripts/check_amv.sh "/storage/emulated/0/Download/NAME.amv"`

Full device checklist: `RELEASE_QA_v064.md`.

---

# ClipAMV v0.6.2

**Your Media, Player-Ready** — Android AMV converter for Gen 2 players.

## Features

- YouTube / Movie / Cartoon / Portrait presets
- Space Saver / Standard / Quality; 15 / 25 / 30 / Auto FPS
- Auto / Sharp (Lanczos) / Smooth (Bicubic) picture processing, with same-frame comparison
- Fit (keep the picture) and Fill (crop uniformly) framing with calibrated 320×240 AMV output
- Source playback that starts without FFmpeg proxy encoding; slider seeks original video
- RENDER 10S for FFmpeg-filtered Player View / Raw AMV preview
- Content-provider file selection and output, queue conversion, trim, test clips
- SAF-safe staged AMV output verification and detailed diagnostics
- Adaptive launcher icon and dark interface

Build: GitHub Actions workflow `.github/workflows/build-apk.yml`.

See `REPAIR_NOTES_v062.md` for changes, passed media tests, and device-validation requirements.

## Full-length seek QA (v0.6.5)

`bash scripts/check_amv.sh <path-to-amv>` verifies AMV packet structure and actual late-video frame decoding; use `--deep` to decode every frame. See `SEEK_QA_v065.md`. A passing computer test still requires physical Gen 2 seek confirmation.

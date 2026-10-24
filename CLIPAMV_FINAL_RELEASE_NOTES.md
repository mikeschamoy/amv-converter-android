# ClipAMV v0.7.0 — final-release candidate

Built from the working RC4 source at Git commit `b749688`.

## Changes from RC4

- **Queue pause after current file**: completes the current encode, AMV audit, seek check and write before pausing; preserves the remaining input URI list and chosen settings for later resume, including after reopening the app.
- **Resuming does not reconvert already completed queue items.** An unfinished individual video still starts from the beginning, not a half-written AMV.
- **Paused queues stay saved** even if you run another single conversion; a separate *Discard remaining queue* button requires confirmation and never removes completed AMVs.
- **Downloads and chosen-folder duplicate-name protection**: number suffixes rather than knowingly reusing a name when the provider supports name queries. On providers that prohibit listing, Android's `createDocument` decides collision handling.
- **Simpler first-use screen**: video preview, source specs, storage estimate and technical speed/thermal bar appear when useful, rather than showing large empty areas before selecting a file.
- **Optional expert controls remain**: presets, framing, crop/black bars, subtitle burning, picture processing, performance mode, trim and diagnostics, with explanatory Auto guidance.
- **Collapsible conversion history**, `.amv` hidden in app history labels but retained on disk, elapsed time in completion summary, new high-contrast adaptive launcher glyph.
- **v0.7.0, version code 18**; additional GitHub Actions workflow uploads a debug-signed APK as `ClipAMV.apk` in the `ClipAMV-Final-APK` artifact (with SHA256SUMS.txt).
- **Conversion encoder and all established AMV presets unchanged.**

## Build and test limitations

Source-level checks pass: 96 framing/processing regressions, 8 FFmpeg command checks, complete/truncated AMV integrity cases, 11 new queue pause policy cases, Android XML and resource-reference checks. These do **not** replace a GitHub Android build and real device tests. Do not claim production-signed release: this workflow provides a **debug-signed** APK for user acceptance.

## Required on-device acceptance test

1. Select at least three short videos and start an Auto queue to Downloads. During the first video, tap **Pause after current file**; verify that it finishes and the remaining count is correct.
2. Fully close and reopen ClipAMV; tap **Resume remaining files**; verify that the first completed file was not duplicated or overwritten.
3. Repeat pause using the foreground notification, with the app in the background. Test cancellation, duplicate file names, and manually selected destination folders.
4. Verify the resulting AMVs play (including audio, seeking, and the ending) on the Ruizu/Clip Sport Gen 2.
5. Test an advanced override, a portrait clip, a 4:3 clip and a subtitle option where available.

If the Android build or these tests fail, the provided artifact is a final **candidate**, not a verified final APK.

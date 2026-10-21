# ClipAMV 0.6.4 — Release candidate QA

This is a **candidate**, not a certified final build. Source baseline: v0.6.3 (commit `1e1fe1d`), reconstructed from the user-uploaded v0.6.2 source archive and the exact 0.6.3 patch. No user data or player firmware is modified.

## Corrections in 0.6.4

- Faster mode no longer forces `-threads 2` on the AMV encoder; it uses automatic encoder threading, and still limits filter workers to 2. Maximum uses automatic threading for both. Speed gains are device- and source-dependent. No change to codec/quality/FPS profiles.
- Batch outputs are generated locally and verified **before** creating a new destination document; pending downloads remain hidden until copy and publication complete. Failed output copies clean up only app-created destinations, not the user's preselected document.
- Verify AMV **audio** (mono ADPCM 22050 Hz) as well as its 320×240 AMV video. Record a separate stage failure if output creation or publishing fails.
- Changing preset settings invalidates the rendered preview and returns to the original source until a new `RENDER 10S` preview is generated.
- Cancel/destroy async preview work when the Activity is destroyed, guarding late callbacks.
- Add JVM unit-style smoke checks in GitHub Actions and Termux for 48 Fit/Fill/Sharp/Smooth combinations, performance flags, codecs, muxer, and Android XML parsing.
- Add exact-commit GitHub APK downloader that verifies zip integrity and leaves APK in Downloads **without opening an installer**.
- Add AMV format validator with optional full-decode `--deep` mode.

## Run locally in Termux before push

```bash
bash scripts/test_presets.sh
```

Then commit and push. GitHub Actions runs the above test and builds the debug APK. Install manually after:

```bash
bash scripts/download_latest_apk.sh
```

After a conversion, run:

```bash
bash scripts/check_amv.sh "/storage/emulated/0/Download/EXAMPLE.amv"
bash scripts/check_amv.sh --deep "/storage/emulated/0/Download/EXAMPLE.amv"
```

## APK signing caution

CI now caches its generated **debug signing keystore** to improve update compatibility across later builds. GitHub may evict caches; this is *not a guaranteed permanent signing identity*. If installation reports an incompatible signature, **do not blindly uninstall**: uninstalling can erase local app data and settings. A privately stored release signing key is required before calling this a distributable final release. The first candidate build may not match the signing certificate of an already-installed older debug APK.

## Mandatory device acceptance tests

1. Install v0.6.4 over installed release; confirm old app updates, icon correct, app launches and retains settings.
2. Source playback Play/Pause (including after pause and resume); drag seekbar while paused/playing, including near the end. Check original-source label.
3. Render 10 seconds, listen to preview, switch Player/Raw view. Change Fit/Fill while preview open: app must invalidate the old render and offer a new one.
4. Compare Auto/Sharp/Smooth recommendations with A/B frame; test frame heights and FPS on the exact Acura 3:2 video.
5. Convert 30 seconds of the Acura, inspect output filename, completion notification, and run `scripts/check_amv.sh --deep` on file.
6. Transfer Acura to real Gen 2; physically check visible centering (X calibration), clipping, audio sync and seek. **Simulation alone is insufficient**.
7. Convert H.264 widescreen, 4:3, portrait/rotated, silent video, and an odd-size source. Inspect files.
8. Convert Carrie 1440×720 HEVC 10-bit with Standard, Faster, Maximum on the same 60-second segment while phone is cool, one run at a time; record speed and thermal status. Expect a CPU-intensive encode; 10-bit HEVC is not guaranteed to accelerate with threading.
9. Batch 3 mixed files with Auto per file; verify no extra/missing items, unique output names, and error handling.
10. Test cancellation mid-encode and output copy, near-full storage, inaccessible output folder, leaving/reopening app, screen-off processing, notifications and diagnostics. Ensure no stray zero-byte downloads or cache accumulation.

Do not call the app final until the build passes and the above mandatory behaviors are confirmed on the user's Motorola and MP3 player.

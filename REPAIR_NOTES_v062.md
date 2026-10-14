# ClipAMV v0.6.2 — Repair Notes

Source base: v0.6.1 (`5088e79`). This update keeps the v0.6 UI and adaptive icon.

## Reliability changes

- **Playback no longer depends on FFmpeg proxy generation.** The ▶ button plays the original selected video; moving the seek bar seeks the original media immediately. You no longer have to wait for a ten-second proxy encode just to play or seek.
- The separate **RENDER 10S** action generates an FFmpeg-filtered ten-second preview (MPEG-4/AAC), using the same framing filter as AMV conversion. Only this rendered mode supports simulated Gen 2 Player/Raw AMV views. Source playback preserves original video aspect ratio and is not a calibrated AMV preview.
- A failed proxy falls back to direct source playback. Image inputs use the still-frame renderer.
- Auto picture analysis no longer blocks original video playback after inspection.
- **Conversion uses a temporary local `.amv` file**, verifies local stream parameters and sample seeks, then copies it to the document-provider output. This avoids muxing directly to nonseekable SAF URIs. The app checks staging space and cleans up temporary files on normal completion or failure. This staging requires extra free space approximately equal to the output size.
- Output copy requests a truncate-capable stream, with a basic-write fallback for providers that do not support it, and verifies the copied byte count (plus provider size when available).
- Failure reasons, FFmpeg logs and preview errors are included in exported diagnostics. Source codec metadata handles both `codec_type`/`codec_name` orders.
- Silent input video receives an empty mono audio track for AMV compatibility.
- Release versionCode 9, versionName 0.6.2.

## Tests performed outside Android

- Java `PresetEngine` compiled and produced the exact FFmpeg conversion command used for real-video testing.
- Uploaded 1994 Acura video: a 30-second AMV encoded successfully (320×240, AMV video, 22,050 Hz mono ADPCM audio). Seek/decode verified at 1, 8, 15, 23, and 28 seconds.
- A ten-second FFmpeg-rendered preview encoded successfully (320×240 MPEG-4/AAC).
- Forty-eight synthetic rendering combinations (12 dimensions × Fit/Fill × Sharp/Smooth) passed.
- A silent source video successfully converted to AMV with generated mono audio.
- Android XML resources parsed. Java source parsed by `javac`, but cannot type-check against Android classes here because the Android SDK/FFmpegKit Android dependencies are not installed.

## Device checks still required

1. GitHub Actions build of the complete APK.
2. Install/update app and play/pause a selected source without using RENDER 10S.
3. Seek to different timestamps using the slider, paused and playing.
4. Press RENDER 10S, play its filtered preview, switch Player View vs Raw AMV.
5. Convert a 30-second test clip; confirm it saves and shows successful history.
6. Transfer the result to the physical Gen 2 and check calibration/seek.
7. Export diagnostics if any step fails; the error report now includes the failed stage.

The APK and hardware behavior have **not** been verified in this environment. The media commands passed; actual Android runtime and manufacturer-specific document providers require a device test.

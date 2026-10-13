# AMV Converter Android — v0.1

Native Android proof-of-concept for the 320×240 AMV workflow.

## What v0.1 does

- Select MP4/MKV/AVI/MOV/WebM via Android's document picker.
- Inspect resolution, FPS and duration with FFprobe.
- Choose:
  - YouTube
  - Movie / Show
  - Cartoon
- Auto-select the established AMV preset.
- Override visible height: 216 / 208 / 200 / 192 / 184.
- True 4:3 modes:
  - Full 320×240
  - Preserve 288×216
- Convert directly to `.amv`.
- Conversion runs as a foreground media-processing service with a persistent notification.
- You can leave the app and turn the screen off while it converts.
- Cancel from the notification.

## Engine

Uses the maintained Android FFmpegKit package:

`dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9`

The original FFmpegKit was retired; this maintained Android continuation is published on Maven Central.

## Build without a computer

This project contains `.github/workflows/build-apk.yml`.

Push the project to a GitHub repository. GitHub Actions builds the APK in the cloud.

Then:

1. Open the repository on GitHub.
2. Open **Actions**.
3. Open the latest **Build APK** run.
4. Download the **AMV-Converter-debug** artifact.
5. Extract the ZIP and install `app-debug.apk` on Android.

## Current limitations

- v0.1 does not yet include the live 320×240 visual crop preview from the web prototype.
- Baked-in pillarbox detection is not automated yet.
- It maps audio optionally (`0:a:0?`) so silent sources don't fail.
- Test AMV playback on the target player before doing a large library batch.

## Next milestones

1. Live player preview with 216/208/200/192/184 buttons.
2. Automatic black-bar / pillarbox detection.
3. Better in-app progress + ETA.
4. Conversion queue.
5. Saved conversion history.
6. Optional trim start/end.

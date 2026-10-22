# ClipAMV 0.6.6 — Auto Framing release candidate

Source baseline: commit `91c3a29` (v0.6.5 + Android emulator QA + Gradle syntax repair). X64 calibrated viewport is unchanged.

## Behavior

- Framing now has **AUTO / FIT / BALANCED / FILL**, independently from **Auto / Sharp / Smooth** picture processing.
- AUTO resolves per input: ordinary 16:9 YouTube/Skate -> FILL; square/portrait -> FIT; wide cinema -> BALANCED; other landscape -> FILL.
- Manual selection overrides Auto, with existing ordinal values FIT=0 and FILL=1 retained.
- Queue Auto Each File no longer discards manually selected height or FPS. If left unselected, those settings still auto-select per source.
- Display now reports expected *picture* dimensions and the target height separately. `YouTube 208 / AUTO` on 16:9 should show `picture 256×208` rather than 144.
- BALANCED limits estimated horizontal crop to about 33% on ultrawide/movie sources, sometimes choosing a smaller picture height.
- Source with non-square pixels retains full rescale normalization; square-pixel sources skip a redundant large scaling pass.
- Frame X64 calibrated display viewport and 320×240 AMV audio/video/seek-integrity logic unchanged.

## Evidence collected

- Local tests: 96 framing/picture combinations + command checks + AMV integrity regressions passed.
- Eight representative QA sources rendered one real frame with FFmpeg into a 320×240 canvas, including anamorphic, HEVC Main 10 and portrait.
- Full Android build/emulator conversions and physical MP3 playback **not yet verified**.

## Emulator QA continuation

- Latest known emulator run `39698591157` was triggered from commit `91c3a29` after fixing Gradle Kotlin syntax. Its full error log is not available in this environment; don't claim it passed.
- This overlay adds a Gradle preflight build before the emulator and an Android instrumentation assertion for YT 208 AUTO framing.
- Inspect the failed run's *first* exception with `gh run view 39698591157 -R mikeschamoy/amv-converter-android --log-failed | grep -A22 -B6 -m1 "^.*e: .*\|Script compilation error\|What went wrong\|error:"`.
- Then trigger `gh workflow run android-emulator-qa.yml -R mikeschamoy/amv-converter-android` after pushing v0.6.6. Logs/artifacts from that run determine next fixes.

## Physical acceptance

1. On the Motorola, select a 16:9 15s QA host, select **YouTube → 208 → AUTO**, and check displayed `picture 256×208`.
2. Convert three YouTube videos with Auto Each File and 208 explicitly selected. All three should be 208 high on the MP3, verified by `ffmpeg -vf cropdetect` and physical check.
3. Convert a scope movie (BALANCED) and a square/portrait input (FIT) and inspect black space and cropping.
4. Check Play/Pause, timeline, RENDER 10S, output, queue ordering and sound.
5. Validate a completed movie with `scripts/check_amv.sh --deep` and seek to 95%, 98%, last 10 s on the MP3.

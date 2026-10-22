# ClipAMV v0.7.0 RC1 — Consolidated release-candidate notes

## Base and scope

Overlay targets **clean git commit `0651b66`**. Includes pending v0.6.6 Auto Framing,
v0.6.7 subtitle/queue/black-bar/UI fixes, plus a targeted native-performance and QA pass.
Does **not** yet integrate Termux as an Android command executor.

## Preserved compatibility

- Output: 320×240 AMV, IMA ADPCM 22,050 Hz mono, 15/25/30 fps and matching block sizes.
- Confirmed physical-player **X64** viewport calibration unchanged.
- Verified-stage publishing, packet/trailer validation, and late-video seek checks preserved.
- No automatic Android package installer launch.

## Changes in this candidate

- Source-aware Auto Framing; normal YouTube 208 chooses Fill and outputs a 256×208
  picture at X64, not the prior incorrectly shrunken 256×144 Fit image.
- Explicit height/FPS preserved in queue auto mode. Auto picture processing stays separate.
- Subtitle options: Off, Forced Text, First Text; Normal/Large; burn-in only.
  Bitmap subtitles (PGS/VobSub) are not supported. Default is Off.
- Two-sample black-bar detection and corrected named `cropdetect` parameters.
- Safer source/preview state handling, missing layout ID repaired, 4:3 FULL corrected.
- Pure-axis FFmpeg scale selection (`scale=-2:H` or `scale=W:-2`) instead of
  force-original-aspect option, preserving aspect with fewer runtime conditions.
  This produces identical decoded frames on six local QA examples, but **speed benefit is
  not guaranteed** on every CPU/FFmpeg build.
- Faster mode stops forcing `-filter_threads 2` and uses FFmpeg defaults, more closely
  matching the direct Termux command. Maximum still requests automatic filter scheduling.
- Export Diagnostics now includes effective FFmpeg filter, constructed placeholder command,
  measured native encode-only speed, and encode time. Source/output identifiers are placeholders.
- Termux filter-only benchmark to isolate filter cost within the same FFmpeg binary.
- Android instrumentation suite now selects all 3 methods: 39-case matrix,
  Auto Framing assertion, and embedded forced-text subtitle integration.
  CSV stored in private app files to match `run-as` result retrieval.

## Tests performed in this environment

- 96 pure-Java framing/processing tests + 8 command assertions: passed.
- AMV integrity positive + intentionally corrupted/truncated negative tests: passed.
- All 17 XML resources parsed and 84 MainActivity layout IDs resolved.
- Six source geometries: old/new normalized framing produced bit-identical rendered pixels.
- Actual QA YouTube sample: detected picture area `256:208:64:16`.
- Local HEVC Main 10 AMV (3 sec): 320×240 AMV + 22,050 Hz mono ADPCM.
- Real forced-text MKV: subtitle extraction and burn-in AMV encode passed locally.
- Shell QA runner and Python filter benchmark syntax: passed.

## NOT verified yet

- Full Android Gradle compilation of v0.7.0 RC1 and new instrumentation methods.
- Subtitle burn-in in the Android FFmpegKit binary (required emulator test).
- Actual Motorola sustained FPS and speed vs Termux FFmpeg on same 60-sec input.
- UI interaction tests T35–T39, real phone playback/seeking/cancellation.
- Full-length firmware seek compatibility on Gen 2 (AMV integrity cannot simulate firmware).

## Required release gate

1. Apply overlay to clean `0651b66` and run `bash scripts/test_presets.sh`.
2. Push only after checks pass. Run Android emulator QA and inspect matrix.csv.
3. Expect **39 rows**: 34 or 35+ PASS depending on fixture + 5 NOT_RUN; and
   additional Auto Framing and forced subtitle methods must pass separately.
4. Benchmark native ClipAMV vs Termux at identical settings, controlled battery/temperature.
5. Physical X64/208 framing and full-length seek tests, then finalize signing/version.

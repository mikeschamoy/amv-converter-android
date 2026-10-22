# ClipAMV v0.6.7 Release Candidate QA

## New subtitle behavior
- Default **OFF** keeps existing `-sn` behavior: soft/embedded subtitle streams are excluded.
- **FORCED** selects the first text track marked forced. If none exists, output stays unsubtitled.
- **FIRST TEXT** prefers an English text subtitle track, otherwise the first compatible text subtitle. Explicit language/track selection is a future requirement.
- **NORMAL/LARGE** sets libass font size 16/22, thick outlined lettering, horizontally centered in the Gen 2 X64 picture viewport.
- Bitmap DVD/PGS tracks and hardcoded text are not affected; forcing subtitles requires libass at runtime.
- Preview should show rendered subtitle text in Refresh 10S; the normal Play mode always shows the original source.

## Release gates
1. Run `bash scripts/test_presets.sh`; the emulator includes `checkForcedSubtitleBurnIn()` using an embedded forced SRT test MKV. It has not yet been executed on Android.
2. Test queue: 3 different ratio sources with Auto Framing at selected height 208; assert 16:9 YouTube frame actually 256×208 at X64 and no default FIT to 144.
3. Test subtitle streams using no track, forced SRT, ordinary SRT, bitmap-only subtitle, and trimmed segments. For Text mode with a missing text track, document the no-op.
4. Convert original Acura; 4:3; 3:2; 16:9; 2.4:1; portrait; anamorphic; square; silent. Test playable previews and compare.
5. Verify 320×240 AMV codecs, seek index-independent packet integrity and late-video seek on both FFmpeg and physical Gen 2.
6. Verify queue cancel during encode/copy; no partial output published; compare file sizes and checksum after successful export.
7. Benchmark same 60-second HEVC Main 10 movie with FFmpeg App and ClipAMV at same FPS/height/Q/filter. Thermal conditions controlled.
8. Test real Motorola Android 11 source playback, seek, output, low-storage case, app background/foreground, notifications, install-over.
9. Emulator matrix Android tests must report actual counts, not just compilation success.
10. Keep X64 screen calibration fixed.

**No Android APK or physical device verification has been performed for this RC.**

## Additional audit changes
- Queue no longer resets manual height/FPS when category or quality buttons are tapped.
- Manual height selection now applies to 4:3 and portrait clips in Auto Each File queues as well.
- 4:3 FULL uses Fill; 4:3 PRESERVE uses Fit with X64 calibration.
- Auto Detect Bars is on by default but can be disabled. It requires agreement between two short samples before cropping; no unconditional crop on dark scenes.
- Batch output cancellation is checked immediately before publishing and no already-unverified output is made visible.
- 30-second clips estimate actual duration for videos shorter than 30 seconds.
- Android instrumentation Framing Mode mapping no longer silently replaces Auto/Balanced with Fit.

Known gaps: selecting one of several same-language subtitle tracks, bitmap subtitles, direct Android UI automation, provider-specific SAF failures, thermal performance and physical MP3 seek behavior.

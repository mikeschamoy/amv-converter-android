# ClipAMV v0.4
**Your Media, Player-Ready**

Gen-2-focused AMV converter.

## v0.4 preview/queue reliability pass
- Hard reset preview state whenever the active source changes
- Separate original source URI from temporary preview proxy
- Raw H.264 compatibility proxy for MKV/AVI/HEVC/etc.; final crop/framing is never baked into the proxy
- Generation tokens discard stale async callbacks from previously selected files
- Per-generation proxy/frame cache names prevent old FFmpeg jobs from overwriting the current preview
- Refresh no longer converts the proxy into a new logical source
- Trim changes regenerate proxy previews around the new start point
- Player/Raw framing controls remain live after proxy creation
- Static fallback frames re-render when framing changes
- 216/208/200/192/184 changes now affect output-size estimates modestly
- FPS and quality remain the major file-size levers

## Queue changes
- Queue panel visibly lists all selected media
- Every queued file is inspected before conversion
- Odd aspect-ratio/FPS items are flagged
- Tap a queued item to preview it before starting
- Auto Each File is the default
- Same For All is an explicit option
- Queue trim is no longer inherited from the previewed episode
- Queue estimate updates when quality/FPS/framing settings change
- Direct `Downloads` output on Android 10+; no forced subfolder
- Optional custom output folder remains available
- Direct Downloads checks free space before starting
- Duplicate Downloads filenames receive a safe numeric suffix

## Gen-2 compatibility changes
- Clean generated timestamps (`-fflags +genpts`)
- Negative timestamps normalized to zero
- Finished AMVs are verified for codec/duration
- Post-conversion seek checks decode frames near 25%, 50%, and 75%
- History marks seek-compatible outputs or warns when seek verification fails

## Core features retained
- YouTube / Movie / Cartoon / Portrait
- Space Saver / Standard / Quality
- 15 / 25 / 30 / Auto FPS
- 4:3 Full / Preserve
- Portrait Fit / Zoom / Rotate 90
- Image input -> player-ready JPEG
- Black-bar detection
- Trim + 30-second test
- Smart short filenames
- Background conversion, wake lock, progress/speed/ETA
- Advanced panel + diagnostics
- FFmpeg engine self-test

Space Saver is intentionally conservative in v0.4: 15 fps default with q3 until Gen-2 testing proves a more aggressive q level is reliably playable.

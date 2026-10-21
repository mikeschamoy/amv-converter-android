# ClipAMV v0.6.5 — Android emulator matrix runner (experimental)

This **adds tests only**, not changes to the conversion behavior. It uses `ConversionService`, `FFmpegKit`, `AMVIntegrity`, MediaStore on a real Android emulator (not Termux FFmpeg).

It automatically runs **34/39 matrix entries**: T01–T34 (32 conversion configurations, two 3-file queues). Five cases (T35–T39) are explicitly marked `NOT_RUN`, never marked as passed. Manual MP3 playback, hardware calibration and real Motorola performance are not automated.

**Prerequisites:** repo at commit `7472c36`, no local changes, enough GitHub Actions usage budget. Emulator compatibility with FFmpegKit (including x86_64) has NOT been tested yet. This workflow is intentionally manually triggered; the existing APK-building workflow remains unchanged.

The test media adds ~23MB to the test APK, not the shipping ClipAMV debug APK. Each run can take significant GitHub Actions minutes; don't trigger repeatedly.

Install: extract overlay into repository, then `python scripts/install_qa_overlay.py`, `git diff --check`, commit, push. The new workflow appears as **ClipAMV Android Emulator QA** in GitHub Actions. Start via `gh workflow run android-emulator-qa.yml -R mikeschamoy/amv-converter-android`. Results are saved as `ClipAMV-Android-Emulator-QA` including `matrix.csv`, instrumentation test report and logcat.

If a conversion fails, inspect the CSV/Logcat artifact; do not assume source-level ffmpeg tests prove the Android app works. A green GitHub APK build also does not prove these tests passed.

The run may find real app bugs; do not treat the suite itself as already proven because it compiles as Java source in this container. This is a first instrumentation implementation and requires its first GitHub run to validate.

**Privacy/copyright:** The 15-second excerpt from the user-provided Acura video (T39) is not included in the GitHub test assets. It is intentionally NOT_RUN in CI, so the user’s media is not republished in their repository. Local source-level FFmpeg tests for that clip have already been performed separately.

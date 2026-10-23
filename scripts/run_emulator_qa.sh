#!/usr/bin/env bash
# Build and run Android instrumentation WITHOUT Gradle connected-test teardown.
# connectedDebugAndroidTest uninstalls target app before we can use run-as.
set -Eeuo pipefail
PKG=com.mikeschamoy.amvconverter
OUT=qa-emulator-results
mkdir -p "$OUT"
rm -f "$OUT/matrix.csv"

collect_debug() {
  adb logcat -d -v time > "$OUT/logcat.txt" 2>&1 || true
  adb shell screencap -p /sdcard/clipamv_qa_screen.png >/dev/null 2>&1 || true
  adb pull /sdcard/clipamv_qa_screen.png "$OUT/screenshot.png" >/dev/null 2>&1 || true
}
trap collect_debug EXIT

echo '=== COMPILE TEST AND APP APKS ==='
set +e
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace 2>&1 | tee "$OUT/instrumentation-gradle.log"
BUILD_CODE=${PIPESTATUS[0]}
set -e
if (( BUILD_CODE != 0 )); then
  echo "FAIL: APK or test-APK compilation failed: $BUILD_CODE"
  exit "$BUILD_CODE"
fi

APP_APK=app/build/outputs/apk/debug/app-debug.apk
TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ -s "$APP_APK" ]] || { echo "FAIL: missing $APP_APK"; exit 1; }
[[ -s "$TEST_APK" ]] || { echo "FAIL: missing $TEST_APK"; exit 1; }

echo '=== INSTALL APKs; KEEP THEM INSTALLED DURING REPORT COLLECTION ==='
adb wait-for-device
adb install -r -g -t "$APP_APK" > "$OUT/app-install.log"
adb install -r -g -t "$TEST_APK" > "$OUT/test-install.log"
adb shell pm path "$PKG" | tee "$OUT/package-path.txt"

echo '=== RUN ACTUAL ANDROID CONVERSION MATRIX ==='
set +e
adb shell am instrument -w -r \
  -e class 'com.mikeschamoy.amvconverter.ClipAMVDeviceMatrixTest' \
  'com.mikeschamoy.amvconverter.test/androidx.test.runner.AndroidJUnitRunner' \
  2>&1 | tee "$OUT/instrumentation.txt"
INSTR_CODE=${PIPESTATUS[0]}
set -e
if (( INSTR_CODE != 0 )) || ! grep -Eq '^INSTRUMENTATION_CODE: -1\r?$' "$OUT/instrumentation.txt"; then
  echo "FAIL: instrumentation did not complete successfully (adb code $INSTR_CODE)"
  exit 1
fi

# These three methods include the 39-scenario matrix, forced subtitle burn-in,
# and the Auto Framing geometry regression. An instrumentation success code
# alone does not prove all new methods were actually selected.
if ! grep -Eq '^INSTRUMENTATION_STATUS: numtests=4\r?$' "$OUT/instrumentation.txt"; then
  echo 'FAIL: expected all 4 instrumentation methods to run'
  exit 1
fi
echo '=== RETRIEVE REPORT BEFORE APP UNINSTALL ==='
if ! adb exec-out run-as "$PKG" cat files/ClipAMV-emulator-matrix.csv \
     > "$OUT/matrix.csv" 2> "$OUT/report-pull-error.txt"; then
  cat "$OUT/report-pull-error.txt"
  echo 'FAIL: unable to collect app-private matrix report'
  exit 1
fi

python3 - "$OUT/matrix.csv" <<'PY_REPORT'
import csv,sys
with open(sys.argv[1],encoding='utf-8-sig',newline='') as f:
    rows=list(csv.DictReader(f))
tests=[r for r in rows if r.get('test_id','').startswith('T') and r['test_id'][1:].isdigit()]
if len(tests)!=39 or len({r['test_id'] for r in tests})!=39:
    raise SystemExit(f'FAIL: expected 39 unique test records; got {len(tests)}')
failed=[r for r in tests if r.get('result')=='FAIL']
unknown=[r for r in tests if r.get('result') not in ('PASS','FAIL','NOT_RUN')]
summary=[r for r in rows if r.get('test_id')=='TOTAL']
if len(summary)!=1 or summary[0].get('result')!='PASS' or failed or unknown:
    print('FAIL: matrix failed or missing PASS summary')
    for r in failed+unknown:print(r.get('test_id'),r.get('result'),r.get('details'))
    raise SystemExit(1)
pas=sum(r['result']=='PASS' for r in tests)
notrun=sum(r['result']=='NOT_RUN' for r in tests)
if pas==0:raise SystemExit('FAIL: zero conversions passed')
print(f'QA VERIFIED: {pas} PASS, {notrun} NOT_RUN, 0 FAIL (39 scenarios)')
PY_REPORT

# Detect silent cropdetect failures that previously left auto black-bar detection broken.
adb logcat -d -v time > "$OUT/logcat.txt" 2>&1 || true
if grep -Fq "Error parsing filterchain 'cropdetect=" "$OUT/logcat.txt"; then
  echo 'FAIL: FFmpegKit cropdetect filter still fails to parse'
  grep -F "Error parsing filterchain 'cropdetect=" "$OUT/logcat.txt" | head -5
  exit 1
fi

echo '=== PASS: verified emulator matrix and cropdetect parser ==='

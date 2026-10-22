#!/usr/bin/env bash
# Run as ONE persistent shell from reactivecircus/android-emulator-runner.
set -uo pipefail
PKG="com.mikeschamoy.amvconverter"
OUT="qa-emulator-results"
mkdir -p "$OUT"

# Preserve the Gradle result across all report-collection commands.
set +e
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace 2>&1 | tee "$OUT/instrumentation-gradle.log"
code=${PIPESTATUS[0]}
set -e

if (( code != 0 )); then
  echo "FAIL: compilation failed"
  exit "$code"
fi

APP=app/build/outputs/apk/debug/app-debug.apk
TEST=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

[ -s "$APP" ] && [ -s "$TEST" ] || {
  echo "FAIL: APK missing"
  exit 1
}

adb install -r -g -t "$APP"
adb install -r -g -t "$TEST"

set +e
adb shell am instrument -w -r   -e class 'com.mikeschamoy.amvconverter.ClipAMVDeviceMatrixTest#runMatrixOnActualAndroidEncoder'   'com.mikeschamoy.amvconverter.test/androidx.test.runner.AndroidJUnitRunner'   2>&1 | tee "$OUT/instrumentation.txt"
result=${PIPESTATUS[0]}
set -e

if (( result != 0 )) || ! grep -q 'INSTRUMENTATION_CODE: -1' "$OUT/instrumentation.txt"; then
  echo "FAIL: Android instrumentation did not pass"
  exit 1
fi

adb logcat -d -v time > "$OUT/logcat.txt" 2>&1 || true
adb shell screencap -p /sdcard/clipamv_qa_screen.png >/dev/null 2>&1 || true
adb pull /sdcard/clipamv_qa_screen.png "$OUT/screenshot.png" >/dev/null 2>&1 || true

# Instrumentation writes into target app PRIVATE files; use run-as on debug APK.
if ! adb exec-out run-as "$PKG" cat files/ClipAMV-emulator-matrix.csv > "$OUT/matrix.csv" 2> "$OUT/report-pull-error.txt"; then
    rm -f "$OUT/matrix.csv"
    echo "No private app QA report. Details:"
    cat "$OUT/report-pull-error.txt"
fi

if [[ ! -s "$OUT/matrix.csv" ]]; then
    echo "FAIL: matrix.csv is missing or empty; no verified test coverage"
    exit 1
fi

python3 - "$OUT/matrix.csv" <<'PY_REPORT'
import csv,sys
p=sys.argv[1]
with open(p,newline='',encoding='utf-8') as f:
    rows=list(csv.DictReader(f))
tests=[r for r in rows if r.get('test_id','').startswith('T') and r['test_id'][1:].isdigit()]
summary=[r for r in rows if r.get('test_id')=='TOTAL']
if len(tests)!=39 or len({r['test_id'] for r in tests})!=39:
    raise SystemExit(f'FAIL: expected 39 unique test records, got {len(tests)}')
if len(summary)!=1 or summary[0].get('result')!='PASS':
    raise SystemExit('FAIL: test TOTAL summary absent or not PASS')
fail=[r for r in tests if r.get('result')=='FAIL']
unknown=[r for r in tests if r.get('result') not in ('PASS','FAIL','NOT_RUN')]
if fail or unknown:
    print('FAILED/UNKNOWN SCENARIOS:',*(f"{r['test_id']}: {r.get('details')}" for r in fail+unknown),sep='\n')
    raise SystemExit(1)
pas=sum(r['result']=='PASS' for r in tests)
skip=sum(r['result']=='NOT_RUN' for r in tests)
if pas==0:
    raise SystemExit('FAIL: no completed conversion tests')
print(f'QA REPORT VERIFIED: {pas} passed, {skip} not run, 0 failed, 39 total')
PY_REPORT
report_code=$?

if [[ "$code" -ne 0 ]]; then
    echo "FAIL: instrumentation Gradle exit code $code"
    exit "$code"
fi
if [[ "$report_code" -ne 0 ]]; then
    echo "FAIL: invalid or failing QA report"
    exit "$report_code"
fi

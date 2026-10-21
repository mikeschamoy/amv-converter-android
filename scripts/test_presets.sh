#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
javac -d "$TMP" app/src/main/java/com/mikeschamoy/amvconverter/PresetEngine.java tests/PresetEngineRegression.java
java -cp "$TMP" com.mikeschamoy.amvconverter.PresetEngineRegression
python3 - <<'PY'
from pathlib import Path
from xml.etree import ElementTree as ET
paths=list(Path('app/src/main').rglob('*.xml'))
for p in paths:ET.parse(p)
print(f'PASS: {len(paths)} Android XML files parsed')
PY

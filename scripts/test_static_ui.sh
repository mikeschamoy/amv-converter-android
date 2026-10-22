#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 - <<'PY2'
from pathlib import Path
import re
from xml.etree import ElementTree as ET
layout=Path("app/src/main/res/layout/activity_main.xml")
xml=layout.read_text()
ET.fromstring(xml)
java=Path("app/src/main/java/com/mikeschamoy/amvconverter/MainActivity.java").read_text()
declared=set(re.findall(r"@\+id/([A-Za-z0-9_]+)",xml))
used=set(re.findall(r"R\.id\.([A-Za-z0-9_]+)",java))
missing=sorted(used-declared)
assert not missing, "Missing layout IDs: "+", ".join(missing)
assert 'EXTRA_SUBTITLE_MODE' in java
assert 'FrameMode.AUTO' in java
assert 'heightOverride=0;refresh()' not in java, 'Content buttons still reset height'
print("PASS: all",len(used),"Activity resource IDs declared; no height-reset regression")
PY2

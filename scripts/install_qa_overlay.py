#!/usr/bin/env python3
from pathlib import Path
p=Path("app/build.gradle.kts")
s=p.read_text()
if 'testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"' not in s:
    assert 'targetSdk = 35' in s, 'Unknown Gradle structure; no changes made'
    s=s.replace('targetSdk = 35', 'targetSdk = 35\n        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"', 1)
if 'androidTestImplementation("androidx.test:runner:' not in s:
    assert 'dependencies {' in s, 'Unknown dependencies block; no changes made'
    s=s.replace('dependencies {', 'dependencies {\n    androidTestImplementation("androidx.test:runner:1.6.2")\n    androidTestImplementation("androidx.test.ext:junit:1.2.1")',1)
p.write_text(s)
print("Android instrumentation dependencies configured")

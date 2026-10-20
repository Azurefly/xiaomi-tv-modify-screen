# Copyright (C) 2026 小狗便利店投屏 contributors
# SPDX-License-Identifier: GPL-3.0-only
"""Regression checks for real source resolutions, codec groups and unsupported tracks."""
import os
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[1]
java=Path(os.environ['JAVA_HOME'])/'bin/java'
classes=root/'build/quality-tests';classes.mkdir(parents=True,exist_ok=True)
subprocess.run([str(java.with_name('javac')),'-encoding','UTF-8','-d',str(classes),str(root/'app/src/main/java/cn/local/livingroomcast/core/QualityCatalog.java'),str(root/'tests/QualityHarness.java')],check=True)
subprocess.run([str(java),'-cp',str(classes),'QualityHarness'],check=True)

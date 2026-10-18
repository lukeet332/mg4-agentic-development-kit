#!/bin/bash
# Rig run: ADB is the container's adb, REPO_DIR=/apk holds the CLI + MCP copies.
A=/sdk/platform-tools/adb
$A shell am start -n com.lukeet.agenticdevkit/.MainActivity >/dev/null 2>&1
sleep 5
$A forward tcp:8787 tcp:8787 >/dev/null 2>&1
cd /apk
REPO_DIR=/apk ADB=$A SERIAL=emulator-5554 BASE=http://127.0.0.1:8787 \
ADK_TOKEN=rigtoken-0123456789abcdef ADK_APK=/apk/adk.apk \
bash /apk/test_api.sh

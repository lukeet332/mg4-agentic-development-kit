#!/usr/bin/env bash
# Build and platform-sign the ADK APK locally, exactly like CI does.
#
#   tools/build.sh [--version 0.2.0] [--no-build]
#
# Output: dist/AgenticDevelopmentKit-<version>.apk (+ .sha256)
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION=""
DO_BUILD=1

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2 ;;
    --no-build) DO_BUILD=0; shift ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# Workspace defaults (override with env if your SDK/JDK live elsewhere)
export JAVA_HOME="${JAVA_HOME:-/home/node/tools/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-/home/node/MG4/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/home/node/MG4/.gradle-home}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/home/node/MG4/.android-home}"
export PATH="$JAVA_HOME/bin:$PATH"

GRADLE="${GRADLE:-}"
if [ -z "$GRADLE" ]; then
  if [ -x "$REPO/gradlew" ]; then
    GRADLE="$REPO/gradlew"
  else
    GRADLE="$(ls -d "$GRADLE_USER_HOME"/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle 2>/dev/null | sort -V | tail -1)"
  fi
fi
[ -n "$GRADLE" ] || { echo "no gradle found; set GRADLE=" >&2; exit 2; }

KEY="${PLATFORM_KEY:-/home/node/Research/.mg4-work/signing/platform.pk8}"
CERT="${PLATFORM_CERT:-/home/node/Research/.mg4-work/signing/platform.x509.pem}"
[ -f "$KEY" ]  || { echo "missing platform key: $KEY (set PLATFORM_KEY)" >&2; exit 2; }
[ -f "$CERT" ] || { echo "missing platform cert: $CERT (set PLATFORM_CERT)" >&2; exit 2; }

cd "$REPO"
if [ -z "$VERSION" ]; then
  VERSION="$(grep -oP '^adk\.versionName=\K.*' gradle.properties)"
fi

if [ "$DO_BUILD" = 1 ]; then
  echo "==> building $VERSION"
  "$GRADLE" :app:assembleRelease --no-daemon
fi

BT="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)"
mkdir -p dist
OUT="dist/AgenticDevelopmentKit-${VERSION}.apk"
"$BT/zipalign" -f 4 app/build/outputs/apk/release/app-release-unsigned.apk dist/aligned.apk
"$BT/apksigner" sign --key "$KEY" --cert "$CERT" --out "$OUT" dist/aligned.apk
"$BT/apksigner" verify --print-certs "$OUT"
sha256sum "$OUT" | tee "$OUT.sha256"
echo "==> $OUT"

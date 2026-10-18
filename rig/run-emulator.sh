#!/usr/bin/env bash
# Supervisor for the MG4 test rig emulator.
#
# The emulator build in this container (37.2.12, no /dev/kvm, TCG only) segfaults
# in its graphics layer after a while. Nothing is lost when it does: the AVD data
# partition persists, so this loop just brings it back up. The app under test is
# installed in /data and its BootReceiver restarts the control channel on boot.
#
# Usage: rig/run-emulator.sh [--wipe]
set -uo pipefail

WIPE=""
[ "${1:-}" = "--wipe" ] && WIPE="-wipe-data"

export ANDROID_SDK_ROOT=/home/node/android-sdk
export ANDROID_HOME=/home/node/android-sdk
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-/home/node/.android/avd}
# Only the X11 libs the emulator lacks - never the whole foreign tree, or its
# glib/gcrypt would override the emulator's own bundled copies and corrupt it.
export LD_LIBRARY_PATH=/home/node/MG4/rig/emulibs-x11:/home/node/android-sdk/emulator/lib64:/home/node/android-sdk/emulator/lib64/qt/lib:/home/node/android-sdk/emulator/lib64/gles_swiftshader
export QT_QPA_PLATFORM=offscreen

ADB=${ADB:-/home/node/MG4/sdk/platform-tools/adb}
ATTEMPT=0

while true; do
  ATTEMPT=$((ATTEMPT + 1))
  echo "[rig] attempt $ATTEMPT: starting mg4_32 $(date -Is)"
  rm -f "$ANDROID_AVD_HOME"/running/pid_*.ini \
        "$ANDROID_AVD_HOME"/mg4_32.avd/hardware-qemu.ini.lock \
        "$ANDROID_AVD_HOME"/mg4_32.avd/multiinstance.lock 2>/dev/null

  # 720p keeps the software renderer cheap; the API under test does not care.
  /home/node/android-sdk/emulator/emulator -avd mg4_32 \
    -no-window -no-audio -no-boot-anim -no-snapshot -no-metrics \
    -gpu swiftshader_indirect -accel off -memory 2048 -cores 4 -skin 1280x720 \
    ${QEMU_CPU:+-qemu -cpu $QEMU_CPU} $WIPE
  rc=$?
  echo "[rig] emulator exited rc=$rc at $(date -Is); restarting in 5s"
  "$ADB" kill-server >/dev/null 2>&1
  sleep 5
done

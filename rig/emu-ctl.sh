#!/usr/bin/env bash
# Control the emulator inside the KVM rig container (no ps/pkill there, so this
# walks /proc and cleans the AVD locks that otherwise cause a FATAL "multiple
# emulators with the same AVD" on the next start).
set -uo pipefail
CT=${CT:-adk-rig}
AVD=${AVD:-mg4}
SDK=/sdk
EMU_ARGS=${EMU_ARGS:--no-window -no-audio -no-boot-anim -no-snapshot -no-metrics -gpu swiftshader_indirect}

kill_emu() {
  docker exec "$CT" bash -c '
    for p in /proc/[0-9]*; do
      pid=${p#/proc/}
      first=$(tr "\0" "\n" < "$p/cmdline" 2>/dev/null | head -1)
      case "$first" in
        /sdk/emulator/*|*/qemu-system-*) kill -9 "$pid" 2>/dev/null ;;
      esac
    done
    sleep 1
    rm -f /avd/*.avd/*.lock /avd/*.avd/*.lock.d 2>/dev/null
    rm -rf /root/.android/avd/running 2>/dev/null
    true'
}

case "${1:-start}" in
  kill) kill_emu ;;
  status)
    docker exec "$CT" sh -c '/sdk/platform-tools/adb devices; /sdk/platform-tools/adb shell getprop sys.boot_completed 2>/dev/null | tr -d "\r"' ;;
  start)
    kill_emu
    docker exec -d "$CT" bash -c "export ANDROID_SDK_ROOT=$SDK ANDROID_HOME=$SDK ANDROID_AVD_HOME=/avd; /sdk/emulator/emulator -avd $AVD $EMU_ARGS > /tmp/emu.log 2>&1"
    echo "started $AVD with: $EMU_ARGS" ;;
esac

# Rig: the MG4 test emulator

Everything in this file was learned the hard way on this workspace's rig. Read it
before you try to boot an emulator here.

## The short version

The rig is an **Android 9 (API 28) x86_64 AVD named `mg4`, 1920×1080, inside a Docker
container that has `/dev/kvm`**. Run it like this:

```bash
docker start emu2                       # or any container built from emu-base:latest with --device /dev/kvm
docker exec -d emu2 bash -c 'export ANDROID_AVD_HOME=/avd ANDROID_SDK_ROOT=/sdk; \
  nohup /sdk/emulator/emulator -avd mg4 -no-window -no-audio -no-snapshot -no-boot-anim \
  -gpu angle_indirect -feature -Vulkan -port 5554 > /tmp/emu.log 2>&1'
docker exec emu2 /sdk/platform-tools/adb wait-for-device
docker exec emu2 /sdk/platform-tools/adb shell getprop sys.boot_completed   # -> 1 in ~10 s
```

Then `adb` for the guest is `docker exec emu2 /sdk/platform-tools/adb …`, and the app's
HTTP port is reached with a forward **inside** the container:

```bash
docker exec emu2 /sdk/platform-tools/adb forward tcp:8787 tcp:8787
docker exec emu2 curl -s -H 'X-ADK-Token: <token>' http://127.0.0.1:8787/health
```

## The two things that waste a day if you don't know them

1. **`-gpu angle_indirect -feature -Vulkan` is load-bearing.** With
   `-gpu swiftshader_indirect` (the obvious choice, and what the AVD was last launched
   with) the emulator segfaults in its graphics/address-space code roughly 10–155 s
   after start — the guest gets as far as showing the home screen and then the whole
   VM dies with `WARNING: cannnot unmap ptr … as it is in the protected range`. That is
   an emulator bug, not an app bug. With `angle_indirect` plus Vulkan disabled, the
   same AVD boots in ~10 s and stays up.
2. **Run it where `/dev/kvm` exists.** Without KVM the guest runs under TCG, which is
   slow enough that it never finishes booting inside the crash window. The `dsh`
   session container has no `/dev/kvm`; the rig containers (`emu2`, `adk-rig`) do.

## Installing and driving ADK on the rig

```bash
# the container shares /apk with the host, so copy the APK there and install from it
cp dist/AgenticDevelopmentKit-<version>.apk /home/luke/immich-shared-albums/dsh-android/apk/adk.apk
docker exec emu2 /sdk/platform-tools/adb install -r -d /apk/adk.apk
docker exec emu2 /sdk/platform-tools/adb shell am start -n com.lukeet.agenticdevkit/.MainActivity
docker exec emu2 /sdk/platform-tools/adb shell dumpsys package com.lukeet.agenticdevkit | grep userId=
# -> userId=1000, sharedUser=SharedUserSetting{… android.uid.system/1000}
```

`am start-foreground-service` from the shell **cannot** start `AdkService`
(`android:exported="false"`, so the shell uid is refused — correctly). Start the
activity instead; it starts the service.

Provision a known master token without touching the UI:

```bash
docker exec emu2 /sdk/platform-tools/adb shell settings put global adk_token rigtoken-0123456789abcdef
```

## Running the acceptance suite

`rig/test_api.sh` is the suite (67 tests, including drift checks that fail if the CLI
or MCP server stop agreeing with the app). It needs `curl`, which the rig image does
not ship: `apt-get install -y curl python3` inside the container (python3 enables the
CLI/MCP drift tests).

```bash
cp rig/test_api.sh /home/luke/immich-shared-albums/dsh-android/apk/
mkdir -p /home/luke/immich-shared-albums/dsh-android/apk/{tools,mcp}
cp tools/adk        /home/luke/immich-shared-albums/dsh-android/apk/tools/
cp mcp/adk-mcp.py   /home/luke/immich-shared-albums/dsh-android/apk/mcp/
docker exec emu2 bash /apk/run-suite.sh
```

The suite derives the pairing code the way a person would — it taps **Pair a device**
through `POST /input`, waits for `GET /pair/status` to report the window open, and reads
the 6-digit code back off the screen through `GET /ui` — then pairs, lists and revokes
the client.

## What the rig proved (and what it caught)

Bugs the rig found that nothing else would have:

| Symptom | Cause | Fix |
| --- | --- | --- |
| Every HTTP request hung | the header parser waited for `LF LF`; every real client sends `CRLF CRLF` | `HttpServer.readHead` checks both terminators |
| Every JSON response was missing its leading `{` | `Json.Obj` was built on an empty `StringBuilder` | seed it with `{`; the strict JSON parsers in the CLI/MCP caught what regex tests missed |
| App aborted on screenshot | `SurfaceControl.screenshot(null, …)` aborts in JNI (`obj == null`) | pass a real `Rect` |
| `input`, `screencap`, `screenrecord`, `uiautomator` all died with exit 159 | exec'ing them from an app inherits the app's seccomp filter (`SIGSYS`) | inject input via `InputManager`, capture via `SurfaceControl`/`MediaProjection`, read the tree via an `AccessibilityService` |
| `pm install` failed with `StorageManager.getVolumes()` / installd refusing the file | installd cannot read the app's own cache (`system_app_data_file`) | install through a `PackageInstaller` session |
| Token mirror to `/data/local/tmp` denied | `system_app` may not touch `shell_data_file` | mirror to `/sdcard/adk.token` and log it; `/state` reports where it landed |
| Self-update left the car unreachable | replacing a running package kills the process and nothing restarts it | an `AlarmManager` self-restart alarm (+15 s), auto-confirm when the new build serves, rollback alarm otherwise |
| `/proc/version` came back empty | procfs reports length 0, and the reader trusted `File.length()` | stream reads |

## If the emulator still misbehaves

- `FATAL | Running multiple emulators with the same AVD` — stale locks. The rig image
  has no `ps`/`pkill`, so clean them by hand: kill the qemu processes by walking
  `/proc/*/cmdline`, then `rm -f /avd/*.avd/*.lock` and `rm -rf /root/.android/avd/running`.
  `rig/emu-ctl.sh kill|start|status` does exactly that.
- `adb devices` shows `offline` for minutes — the guest is still booting; with KVM it
  should be `device` within ~10 s. If it never comes up, the emulator crashed: check
  `/tmp/emu.log` for the protected-range warning above.
- `adb root` restarts adbd and **drops every `adb forward`**; re-add them.

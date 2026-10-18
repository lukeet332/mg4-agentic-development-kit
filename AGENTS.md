# Agentic Development Kit — instructions for agent sessions

This repo is the **car-side** half of the MG4 development loop: a platform-signed
Android system app that serves an HTTP control API, plus the agent drivers that talk
to it. Read [README.md](README.md) first, then [docs/AGENT.md](docs/AGENT.md) if you
are about to drive a car.

## The one architectural rule

**Endpoints are declared exactly once, in `Api.ENDPOINTS`**
(`app/src/main/java/com/lukeet/agenticdevkit/Api.java`).

Everything else is generated from that table:

* `GET /help` and `GET /openapi.json` — built from the table at runtime
* `docs/API.md` — `tools/gen-docs.py` reads `/openapi.json`
* `tools/adk` — resolves `operation_id`s and parameter types from `/help` at run time
* `mcp/adk-mcp.py` — derives every MCP tool from `/openapi.json` at run time
* `rig/test_api.sh` — has drift tests asserting the three agree

So: to add or change an endpoint, edit the table and the handler. Do **not** hand-write
endpoint knowledge into a driver, a doc, or a test — that is the drift this design
exists to prevent. `tools/adk call <operation_id>` exists precisely so a driver never
needs to know a path.

## Build

```bash
tools/build.sh                      # build + platform-sign -> dist/AgenticDevelopmentKit-<version>.apk
tools/build.sh --version 0.2.0
```

The version lives in `gradle.properties` (`adk.versionName`, `adk.versionCode`); CI
stamps a unique `+<run>` on main pushes. Signing needs the platform key — see
[signing/README.md](signing/README.md). A build signed with the wrong key will not
install over an existing ADK.

Local toolchain in this workspace (override with env if it moves):
`JAVA_HOME=/home/node/tools/jdk`, `ANDROID_HOME=/home/node/MG4/sdk`,
`GRADLE_USER_HOME=/home/node/MG4/.gradle-home`, `ANDROID_USER_HOME=/home/node/MG4/.android-home`.

## Test on the rig

The rig is the `mg4_32` AVD (Android 9 / API 28, x86) run headless. **It is fragile in
this container**: there is no `/dev/kvm`, so it runs under TCG, and the emulator
segfaults in its address-space/graphics path roughly every 60–155 seconds. The guest's
package manager is usable from ~90 s into each cycle and `/data` survives the crashes.

```bash
rig/run-emulator.sh            # supervisor: restarts the emulator whenever it dies
rig/rig-up.sh [APK]            # waits for a usable window, installs, starts the channel, prints the token
rig/test_api.sh [APK]          # 59-test acceptance suite (plus 4 drift tests)
```

Read [rig/README.md](rig/README.md) for details, including why `rig/rig-up.sh` waits
for `sm list-volumes` to report a mounted volume (installing before storage is up
fails with a `StorageManager.getVolumes()` NPE inside PackageManagerService).

If the rig will not cooperate, say so explicitly rather than reporting an unverified
"works": the honest states are *verified on the rig*, *built but unverified*, and
*blocked by the emulator*.

## Conventions

* Java only, **no third-party dependencies** (the app must build offline and stay tiny).
* `targetSdk 28`, `minSdk 28` — matching the head unit; do not raise it casually.
* Anything that shells out runs as the system uid (1000), which is what makes
  `pm install`, `screencap`, `input`, `logcat` and friends behave like adb shell.
* Report failures honestly: `POST /prop` on an SELinux-protected property must say it
  was refused, not pretend it worked.
* Never weaken the auth model: new endpoints go through `reg(...)` (token-checked)
  unless there is a specific reason, and `POST /pair`, `GET /pair/status`,
  `GET /health`, `GET /` are the only public ones.
* Self-updates must keep the rollback path intact (`Rollback.java`, 180 s alarm,
  `last-good.apk`); it is the only thing that recovers a broken ADK over the network.

## Publishing

`.github/workflows/build.yml` builds, signs and releases on every push to `main`.
The release APK is signed with the committed AOSP test key unless
`PLATFORM_PK8_B64` / `PLATFORM_CERT_B64` secrets are set. That key is public — never
describe it as a secret, and never put a real token or key in this repo.

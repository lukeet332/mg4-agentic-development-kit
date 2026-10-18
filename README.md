# MG4 Agentic Development Kit

A remote, system-level control channel for the MG4 head unit — built so an agent can
develop, install, test and operate the car **without the USB-stick round trip**.

Today, changing anything on the car means: build on the Mac → copy to a USB stick →
walk to the car → install through the file manager → drive → save a report to the
stick → walk back. Every change is a round trip, and reports come back empty. ADK
replaces that with an HTTP call.

```
   Mac / agent  ──────────►  car hotspot or home Wi-Fi  ──────────►  ADK on the head unit
   adk CLI, MCP, curl              http://<car-ip>:8787              runs as android.uid.system
```

## What it does

The app is signed with the **platform (test) key**, the same one SAIC ships in MG4
firmware. That signature is the whole trick: the app declares
`sharedUserId="android.uid.system"`, so it runs as the car's system user and can do
things ordinary apps cannot.

| Capability | Endpoint |
| --- | --- |
| Install / update an APK | `POST /install` |
| Run a shell command as system | `GET|POST /exec` |
| Persistent interactive shell (adb shell, no adbd) | `GET /shell/open` + `/shell/write` + `/shell/read` |
| Live and historical logs | `GET /logs` (`follow=1` streams) |
| One-shot diagnostic bundle | `GET /report` |
| Screenshots and screen recording | `GET /screenshot`, `GET /screenrecord` |
| Drive the UI (tap, swipe, type, keys) | `POST /input` |
| Read the screen like an agent reads a page | `GET /ui`, `GET /wait`, `GET /foreground` |
| Read/write system settings and properties | `GET|POST /settings`, `GET|POST /prop` |
| Push/pull files, list directories | `GET|POST /file`, `GET /ls` |
| dumpsys, am start/broadcast, force-stop | `GET /dumpsys`, `POST /intent` |
| TCP forwarding (adb forward / reverse) | `POST /forward/add` |
| Update ADK itself, safely | `POST /selfupdate` (+ auto-rollback) |
| Reboot the head unit | `POST /reboot` |

45 endpoints in total; `GET /help` and `GET /openapi.json` list them all, and the
human dashboard lives at `GET /`.

## Reaching it

The app serves whichever network the car is on — its own hotspot when you are in it,
your home Wi-Fi when it is parked at home — on port **8787** (configurable in the app;
the SAIC firewall drops 5555, 7000, 5353, 7777, 2011, 63012, 63014, so those are
avoided).

```bash
curl http://192.168.1.50:8787/health          # liveness, no token
adb forward tcp:8787 tcp:8787                 # or, on the test rig
```

## Pairing — the security boundary

The channel is a system-level shell on a car, so it is not open by default:

1. In the car, open **Agentic Development Kit** → **Pair a device**. The app shows a
   **6-digit code**, valid for **120 seconds**, usable **once** (five wrong codes lock
   pairing for a minute).
2. The agent exchanges it for a long-lived device token:

   ```bash
   adk --url http://192.168.1.50:8787 pair 123456 --name mac-agent
   ```

   ```json
   {"ok":true,"client_id":"9f2c1ab4","token":"<64 hex>","name":"mac-agent"}
   ```

3. Every later call sends `X-ADK-Token: <token>`. Tokens are per-device and revocable
   (`GET /clients`, `POST /pair/revoke`).

The **master token** (full control, never handed out over the network) is shown in the
app and mirrored to `/data/local/tmp/adk.token` and `/sdcard/adk.token` for adb users.
`POST /pair`, `GET /pair/status`, `GET /health` and `GET /` are the only endpoints that
do not need a token.

## Agent drivers

The app owns the API contract. Both drivers **derive themselves from
`GET /openapi.json` at runtime**, so they cannot drift from what the car actually
serves — add an endpoint to the app and the CLI and MCP server pick it up.

| Driver | Use it when |
| --- | --- |
| `tools/adk` — CLI (Python 3, stdlib only, 38 subcommands) | the agent can run shell commands: composable, pipeable, scriptable |
| `mcp/adk-mcp.py` — MCP stdio server | the client speaks MCP (Claude Desktop/Code, Cursor…), or the agent cannot shell out |

```bash
adk exec 'getprop ro.build.version.release'
adk install app-debug.apk
adk logs --follow --grep OPENHU
adk shot car.png
adk wait 'Settings' --tap
adk call post_exec cmd='id' timeout_ms=5000     # drift-proof escape hatch
```

MCP client configuration:

```json
{
  "mcpServers": {
    "mg4-adk": {
      "command": "python3",
      "args": ["/path/to/agentic-dev-kit/mcp/adk-mcp.py"],
      "env": { "ADK_URL": "http://192.168.1.50:8787", "ADK_TOKEN": "<paired token>" }
    }
  }
}
```

See [docs/AGENT.md](docs/AGENT.md) for the agent-facing guide and
[docs/API.md](docs/API.md) for the generated endpoint reference.

## The recursive loop

Once ADK is on the car, the car is part of the development loop:

```
build locally ──► adk install ──► adk logs / adk shot / adk ui ──► fix ──► repeat
```

And ADK can ship **itself**: `POST /selfupdate` backs up the running build to
`last-good.apk`, installs the new one, and arms a 180-second rollback alarm. If the new
build cannot serve requests — so nobody can confirm it — the alarm reinstalls the
last-known-good APK and the channel comes back. `POST /selfupdate/confirm` cancels the
rollback once `GET /health` shows the new version.


## Status

Verified end to end on the MG4 test rig (Android 9 / API 28 emulator, KVM): **65 of 67
acceptance tests pass, 0 fail, 2 skip** (`rig/test_api.sh`, see [docs/RIG.md](docs/RIG.md)).

Confirmed on the rig, not just built:

* installed as **`userId=1000`, `sharedUser=android.uid.system`** — the platform signature
  really does buy the system uid
* `POST /install` replaces a running package silently, through a `PackageInstaller`
  session (no `pm install`, no confirmation on the car)
* `POST /selfupdate` installs ADK over itself, the channel comes back on a self-restart
  alarm, and an unconfirmed build is reverted to `last-good.apk`
* screenshots through `SurfaceControl` (1920×1080 PNG), input injection through
  `InputManager`, the UI tree through an `AccessibilityService`
* the pairing handshake driven entirely over HTTP: tap **Pair a device** with `POST /input`,
  read the 6-digit code off the screen with `GET /ui`, `POST /pair`, then revoke
* the CLI and the MCP server both deriving themselves from the live spec (drift tests)

The 2 skips are environmental: `POST /uninstall` needs a throwaway APK supplied by the
caller, and `GET /screenrecord` is best-effort (the `screenrecord` binary dies with
`SIGSYS` under the app's seccomp filter — a MediaProjection/MediaRecorder implementation
is the next step there).

Bugs the rig caught that unit tests did not — a CRLF header parser that hung every real
client, a JSON writer missing its opening brace, a JNI abort in the screenshot path, and
`SIGSYS` on every shell tool — are listed in [docs/RIG.md](docs/RIG.md#what-the-rig-proved-and-what-it-caught).

## Install

```bash
adb install -r AgenticDevelopmentKit-<version>.apk     # or copy to a stick, once
```

Then open the app, leave the channel running (it starts on boot via `BOOT_COMPLETED`),
and pair an agent. Requirements: Android 9 / API 28+ (MG4 head units are API 28), and
a platform signature that matches the unit — see [signing/README.md](signing/README.md).

## Build

```bash
tools/build.sh                     # build + platform-sign -> dist/AgenticDevelopmentKit-<version>.apk
tools/build.sh --version 0.2.0     # explicit version
```

CI ([.github/workflows/build.yml](.github/workflows/build.yml)) builds and signs on
every push to `main`, stamps a unique version (`0.1.0+<run>`), and publishes a GitHub
Release with the APK and its SHA-256. Pull requests build without releasing; manual
runs can override the version. Releases prefer repository secrets
`PLATFORM_PK8_B64` / `PLATFORM_CERT_B64` and fall back to the committed AOSP test key.

## Repository layout

```
app/                  the Android app (Java, no third-party dependencies)
  src/main/java/com/lukeet/agenticdevkit/
    Api.java          every endpoint + the declaration table that generates /help and /openapi.json
    HttpServer.java   HTTP/1.1 on raw sockets (streaming, chunked, multipart, keep-alive)
    Pairing.java      the 6-digit pairing window and per-device tokens
    AdkService.java   foreground service that owns the channel
    Rollback.java     self-update safety net
    Shell.java, ShellSession.java, Forwarder.java, Multipart.java, Json.java, ...
mcp/adk-mcp.py        MCP stdio server, tools derived from the live spec
tools/adk             CLI, endpoints resolved from the live spec
tools/build.sh        local build + platform signing
tools/gen-docs.py     regenerates docs/API.md from the spec
docs/                 API reference, agent guide, MCP notes
signing/              AOSP test key (public) + how to use your own
```

## Security notes

* The **AOSP test key is public** — anyone can sign an APK with it. It is committed so
  releases install as system-uid updates out of the box. The real boundary is the
  pairing code and the per-device tokens, so treat a paired token like a root password.
* Traffic is **plain HTTP** on the car's hotspot or your LAN. Use it on networks you
  control; TLS with a pinned self-signed certificate is a natural next step.
* `POST /exec` is a system shell, `POST /reboot` reboots the car, `POST /install`
  installs apps. Every one of them requires a token; revoke tokens you no longer trust
  with `POST /pair/revoke`.
* The app cannot set SELinux-protected properties such as `service.adb.tcp.port` —
  `POST /prop` reports that honestly instead of pretending. `/shell/*` and
  `/forward/*` are the substitutes for adb's own transport.

## Related

* [DiAuto-MG4](https://github.com/merthankaraman/DiAuto-MG4) — the wireless Android Auto
  app this kit was built to develop against.
* [lkernan/ADBUtil](https://github.com/lkernan/ADBUtil) — enabling adb on SAIC head units.
* [jamakr4/MG4-360-Camera-App](https://github.com/jamakr4/MG4-360-Camera-App) — MG4 ADB
  install notes.

## License

MIT — see [LICENSE](LICENSE).

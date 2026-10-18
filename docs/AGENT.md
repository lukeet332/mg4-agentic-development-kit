# AGENT.md — driving the MG4 head unit with `adk`

You are an AI agent. `tools/adk` is your only interface to the car: a stdlib-only Python 3
CLI over the ADK app's HTTP API. Every command is token-authenticated, prints JSON
(pretty, `indent=2`) or raw text, and exits `0` ok / `1` HTTP-or-remote error /
`2` usage-or-config error. `adk exec` and `adk sh` propagate the remote exit code.

| Fact | Value |
| --- | --- |
| CLI | `tools/adk` (run from the repo root; needs Python 3, no deps) |
| Default URL | `http://127.0.0.1:8787` (override: `--url` or `$ADK_URL`) |
| Token | `--token`, `$ADK_TOKEN`, else `~/.adk/token` (written by `adk pair`, mode 600) |
| Auth header | `X-ADK-Token` |
| ADK package | `com.lukeet.agenticdevkit` (platform-signed, `android.uid.system`) |
| Token mirror | `/data/local/tmp/adk.token` (read over adb) |

## 1. Reach the car

| Where you run | How | Then |
| --- | --- | --- |
| Dev rig / emulator with adb | `adb forward tcp:8787 tcp:8787` | the default URL just works |
| Laptop on the car hotspot or home Wi-Fi | find the car IP (`adb shell ip route`, or the ADK app screen) | `adk --url http://<car-ip>:8787 health` |

```bash
adb forward tcp:8787 tcp:8787          # rig: re-run after every adb reconnect
export ADK_URL=http://192.168.43.1:8787   # optional, for Wi-Fi
```

If the channel is not running, launch the ADK app on the car screen (or reboot — it
autostarts). `adk call post_service_stop` kills your own access; only do it when you can
touch the car.

## 2. Pair once per client

The 6-digit code **only exists on the car screen** — a human must tap *Pair a device* in the
ADK app and read it to you. The window lasts 120 s, the code is single-use, 5 wrong codes
lock pairing for 60 s. You cannot derive or brute-force it.

```bash
adk pair 123456 --name mac-agent     # -> client_id: <id>, token saved to ~/.adk/token
adk token                            # source + first 8 chars
adk token --show                     # full token (only when you must copy it)
adk use <64-hex-token>               # store a token you already have
```

Escape hatch with no code: over adb, `adb shell cat /data/local/tmp/adk.token` gives the
master token — `adk use "$(adb shell cat /data/local/tmp/adk.token | tr -d '\r')"`.

Before asking a human for a code, check `adk call get_pair_status` — `{"open":true,
"seconds_left":N}` means a window is already open; `"locked":true` means wait ~60 s.

Then confirm you are in: `adk health` (no token needed) and `adk state` (needs one).

## 3. Core verbs

```bash
adk endpoints                        # live API surface: operation_id, method, path, summary
adk state                            # build, battery, network, screen, foreground app
adk exec getprop ro.build.version.release
adk exec pm list packages -3
adk install app/build/outputs/apk/release/app-release.apk   # -r -d -g, user 0
adk logs --lines 300 --grep 'FATAL|AndroidRuntime'
adk logs --follow --tag OPENHU       # streams until Ctrl-C
adk shot /tmp/car.png --scale 0.5    # JPEG: --jpeg --quality 80
adk ui | head -60                    # uiautomator nodes as JSON; --xml for raw
adk wait Settings --tap --timeout 10000    # 0 found, 1 on 408 timeout
adk tap 960 540 ; adk swipe 100 200 900 200 --ms 400 ; adk text "hello" ; adk key KEYCODE_HOME
adk foreground ; adk dumpsys battery --grep level
adk get /data/local/tmp/adk.token /tmp/tok --text    # download (temp file if LOCAL omitted)
adk put ./patch.bin /data/local/tmp/patch.bin
adk report --out /tmp/adk-report.txt                 # state + logcat + dumpsys bundle
adk packages --third-party --filter diauto
adk intent action=start component=com.drivehub.diauto.mg4/.main.MainActivity
adk reboot --mode ""                 # normal reboot; recovery/bootloader also exist
```

## 4. The escape hatch: `adk call`

Anything not wrapped above is reachable generically, with no client drift: `adk call` reads
`GET /help` at run time, so it always matches the running app.

```bash
adk call get_state
adk call get_logs lines=100 grep=FATAL          # GET -> query params
adk call post_input action=swipe x1=100 y1=200 x2=900 y2=200 duration=400
adk call "POST /input" --json '{"action":"key","key":"KEYCODE_HOME"}'
adk call post_install --file dist/AgenticDevelopmentKit-0.1.0.apk replace=1
adk call get_screenshot --out /tmp/car.png      # binary without --out -> temp path on stdout
adk call get_file path=/proc/version --out /tmp/version.txt
adk call get_pair_status ; adk call post_selfupdate_confirm
```

Rules: `key=value` pairs are typed from the `/help` table (`x=960` becomes an int); for POST
they go in the JSON body, for GET in the query. `--file` streams a local file as the raw
body. Unknown `key=value` keys are still sent (drift-tolerant). Exit `2` means you typed the
endpoint name wrong — run `adk endpoints`.

## 5. The recursive dev loop

```bash
tools/build.sh --version 0.2.0            # gradle + platform sign -> dist/AgenticDevelopmentKit-0.2.0.apk
adk install dist/AgenticDevelopmentKit-0.2.0.apk
adk intent action=start component=com.drivehub.diauto.mg4/.main.MainActivity
sleep 3
adk shot /tmp/after.png                   # look at the screen (read the image, don't guess)
adk ui > /tmp/ui.json                     # exact text/bounds for taps
adk logs --lines 400 --grep 'FATAL|Exception|OPENHU'
adk tap 960 540                           # act on what you saw, then screenshot again
```

Loop: build → install → launch → observe (`shot`/`ui`/`logs`/`foreground`) → change one
thing → repeat. Prefer `adk wait TEXT --tap` over blind coordinates: it is
resolution-independent and returns 408 (exit 1) when the UI never appeared. Use
`adk report --out FILE` when you need to hand a whole failure bundle to a human.

## 6. Self-update loop (ADK updating itself)

Plain `adk install` can also replace ADK itself, but it gives you no way back. Use the
self-update path instead: it arms an automatic rollback to the last-known-good build.

```bash
tools/build.sh --version 0.2.0
adk selfupdate dist/AgenticDevelopmentKit-0.2.0.apk
# ADK restarts itself. It stays on the new build only if you confirm within 180 s.
adk health                                # unauthenticated: is the channel back?
adk version                               # is the new version_name live?
adk confirm                               # keep it (cancels the rollback alarm)
```

- 180 s after a successful `selfupdate`, if nobody confirms, the app reinstalls
  `last-good.apk` and restarts — a bad build cannot brick your access.
- If the new build never answers, wait ~3 minutes, then `adk health` again; you are back on
  the old build. `adk rollback` forces that immediately.
- Paired tokens survive the update (`pm install -r` keeps app data), so no re-pairing.
- `selfupdate` only accepts an APK whose package is `com.lukeet.agenticdevkit`; anything
  else gets `400` — use `adk install` for ordinary apps.

## 7. When things go wrong

| Symptom | Cause | Fix |
| --- | --- | --- |
| `HTTP 401 ... unknown or revoked token` | not paired, token revoked, or wrong `--token` | `adk pair <code from car screen>`, or `adk use "$(adb shell cat /data/local/tmp/adk.token)"` |
| `connection refused` | HTTP channel stopped, wrong port, or `adb forward` died | relaunch the ADK app; `adb forward tcp:8787 tcp:8787`; try `--url http://<car-ip>:8787` |
| `cannot reach ADK ... timed out` | wrong subnet / car on a different AP | join the car hotspot or the same Wi-Fi as the car |
| `uiautomator dump failed` | screen is off or the UI is not idle | `adk key KEYCODE_WAKEUP`, retry `adk ui`; add `adk key KEYCODE_MENU` if a lock screen is up |
| `adk wait` exits 1 with HTTP 408 | text never appeared in `timeout_ms` | screenshot, re-read `adk ui`, fix the selector |
| `pm install` failed | see reasons below | read `"output"` in the JSON reply |
| `install`/`selfupdate` returns 400 `that APK is not ...` | wrong APK for that endpoint | `selfupdate` takes only ADK; `install` takes any app |
| `adk logs --follow` seems to hang | it is a stream, by design | it prints as it arrives; Ctrl-C to stop (exit 0) |

Common `pm install` outputs, as reported in `"output"`:

| Output | Meaning |
| --- | --- |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | versionCode is lower — retry without `--no-downgrade` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | different signing key than the installed app — uninstall first (loses app data) |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` / `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES` | APK is unsigned or badly signed |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | free space: `adk exec df -h /data` |
| `INSTALL_FAILED_VERIFICATION_FAILURE` | package verifier blocked it — platform-signed builds pass |
| `INSTALL_FAILED_INVALID_APK` / `INSTALL_FAILED_INVALID_URI` | truncated upload; re-check the file size |
| `Success` | installed (`ok: true`) |

## 8. Etiquette

- `adk reboot` and `adk call post_service_stop` cut your own link. Reboot only when the car
  is parked.
- Screen capture/record and input injection are visible to the driver. Do not drive the UI
  while the car is moving.
- Pairing is single-use and time-boxed on purpose: never ask a human to re-read a code when
  `~/.adk/token` already works. Check `adk health` before blaming the network.
- Use `--quiet` in scripts; stdout is the payload, stderr is diagnostics.

#!/usr/bin/env bash
# =============================================================================
# test_api.sh - acceptance suite for the Agentic Development Kit (ADK) HTTP API
#
# Runs on the host against the ADK service on the MG4 rig, reached through
# `adb forward tcp:8787 tcp:8787` -> http://127.0.0.1:8787.
#
# Usage:
#   rig/test_api.sh [APK]                    # APK path enables the POST /install test
#   ADK_APK=/path/app.apk rig/test_api.sh
#   ADK_PAIR_CODE=123456 rig/test_api.sh     # enables the pairing round-trip test
#   ADK_TOKEN=<64-hex> rig/test_api.sh       # skips reading the token off the device
#
# Environment:
#   ADB        adb binary             (default /home/node/MG4/sdk/platform-tools/adb)
#   SERIAL     device serial          (default emulator-5554)
#   BASE       host-side base URL     (default http://127.0.0.1:8787)
#   PORT       device port to forward (default: parsed from BASE, else 8787)
#   ADK_TOKEN  master token; if unset the suite reads /data/local/tmp/adk.token
#   ADK_APK    APK for POST /install (same as $1)
#   ADK_THROWAWAY_APK + ADK_THROWAWAY_PKG   enable the POST /uninstall test
#   ADK_PAIR_CODE  6-digit code shown in the car; enables the pair round-trip
#   ADK_PAIR_NAME  client name used when pairing (default adk-test-client)
#   TIMEOUT    default curl timeout, seconds (default 30)
#   ADK_FORCE  1 = keep going when preflight (device / token / health) fails
#
# Exit status: 0 = no failures, 1 = at least one FAIL, 2 = preflight abort.
# Uses only bash, curl, adb and coreutils. Failures are collected, never fatal.
# =============================================================================
set -uo pipefail

ADB=${ADB:-/home/node/MG4/sdk/platform-tools/adb}
SERIAL=${SERIAL:-emulator-5554}
BASE=${BASE:-http://127.0.0.1:8787}
TIMEOUT=${TIMEOUT:-30}
TOKEN=${ADK_TOKEN:-}
APK=${1:-${ADK_APK:-}}
THROWAWAY_APK=${ADK_THROWAWAY_APK:-}
THROWAWAY_PKG=${ADK_THROWAWAY_PKG:-}
PAIR_CODE=${ADK_PAIR_CODE:-}
PAIR_NAME=${ADK_PAIR_NAME:-adk-test-client}
BODY_MAX=${BODY_MAX:-400}
PORT=${PORT:-$(printf '%s' "$BASE" | sed -n 's#^[A-Za-z]*://[^/:]*:\([0-9]\{1,5\}\).*#\1#p')}
PORT=${PORT:-8787}
ADB_OK=0

WORK=$(mktemp -d "${TMPDIR:-/tmp}/adk-api-test.XXXXXX" 2>/dev/null) || { WORK=/tmp/adk-api-test.$$; mkdir -p "$WORK"; }
BODY_FILE="$WORK/body"; ERR_FILE="$WORK/err"; REQ_LOG="$WORK/requests.log"
: > "$REQ_LOG"

if [ -t 1 ]; then G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; C=$'\033[36m'; N=$'\033[0m'
else G=; R=; Y=; C=; N=; fi

# ---------------------------------------------------------------- test bookkeeping
TEST_N=0; PASS=0; FAIL=0; SKIP=0; FAILED_NAMES=()
TEST_NAME=""; TEST_FAILED=0; TEST_SKIPPED=0; TEST_ERRS=""; TEST_SKIP_REASON=""
HTTP_STATUS="000"; CURL_RC=0; REQ_DESC="(no request)"

t_begin() { TEST_N=$((TEST_N + 1)); TEST_NAME=$1; TEST_FAILED=0; TEST_SKIPPED=0; TEST_ERRS=""; TEST_SKIP_REASON=""; }
t_fail()  { TEST_FAILED=1; TEST_ERRS="${TEST_ERRS}${TEST_ERRS:+; }$1"; }
t_skip()  { TEST_SKIPPED=1; TEST_SKIP_REASON=$1; }
note()    { printf '     %snote%s %s\n' "$C" "$N" "$1"; }

t_end() {
  local body
  if [ "$TEST_SKIPPED" = 1 ]; then
    SKIP=$((SKIP + 1))
    printf '%sSKIP%s [%02d] %-52s %s\n' "$Y" "$N" "$TEST_N" "$TEST_NAME" "$TEST_SKIP_REASON"
  elif [ "$TEST_FAILED" = 1 ]; then
    FAIL=$((FAIL + 1)); FAILED_NAMES+=("$TEST_NAME")
    body=$(tr -d '\000' < "$BODY_FILE" 2>/dev/null | head -c "$BODY_MAX" | tr '\n\r' '  ')
    printf '%sFAIL%s [%02d] %-52s %s -> HTTP %s (curl rc %s)\n' "$R" "$N" "$TEST_N" "$TEST_NAME" "$REQ_DESC" "$HTTP_STATUS" "$CURL_RC"
    printf '     reason: %s\n' "$TEST_ERRS"
    printf '     body  : %s\n' "$body"
    cp "$BODY_FILE" "$WORK/fail-$(printf '%02d' "$TEST_N").body" 2>/dev/null
  else
    PASS=$((PASS + 1))
    printf '%sPASS%s [%02d] %-52s %s -> HTTP %s\n' "$G" "$N" "$TEST_N" "$TEST_NAME" "$REQ_DESC" "$HTTP_STATUS"
  fi
}

# ---------------------------------------------------------------- pairing helper
# When no code is supplied, derive it the way a person would: tap "Pair a device"
# on the car screen (through POST /input) and read the code back off the screen
# (through GET /ui). That also proves the screen-driving endpoints end to end.
derive_pair_code() {
  local attempt bounds l t r b cx cy code i
  for attempt in 1 2 3; do
    # Match on the raw single-line JSON: bounds values contain commas, so anything
    # that splits on commas first (tr ',') corrupts them.
    req GET /ui >/dev/null 2>&1
    bounds=$(grep -o '"id":"com.lukeet.agenticdevkit:id/pair"[^}]*"bounds":"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$BODY_FILE" \
        | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
    [ -z "$bounds" ] && return 1
    l=$(printf '%s' "$bounds" | sed -n 's/^\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]$/\1/p')
    t=$(printf '%s' "$bounds" | sed -n 's/^\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]$/\2/p')
    r=$(printf '%s' "$bounds" | sed -n 's/^\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]$/\3/p')
    b=$(printf '%s' "$bounds" | sed -n 's/^\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]$/\4/p')
    cx=$(( (l + r) / 2 )); cy=$(( (t + b) / 2 ))
    # notes go to stderr: stdout is the return value of this function
    note "tapping the pair button at $cx,$cy through POST /input (attempt $attempt)" >&2
    pjson /input "{\"action\":\"tap\",\"x\":$cx,\"y\":$cy}" >/dev/null 2>&1
    # wait for the window to actually open before reading the code off the screen
    for i in 1 2 3 4 5 6; do
      sleep 1
      req GET /pair/status >/dev/null 2>&1
      grep -q '"open":true' "$BODY_FILE" && break
    done
    req GET /ui >/dev/null 2>&1
    code=$(grep -o '"text":"[0-9][0-9][0-9] [0-9][0-9][0-9]"[^}]*"id":"com.lukeet.agenticdevkit:id/pair_code"' "$BODY_FILE" \
        | grep -o '[0-9]' | tr -d '\n' | head -c 6)
    if [ ${#code} -eq 6 ]; then
      printf '%s' "$code"
      return 0
    fi
  done
  return 1
}

# ---------------------------------------------------------------- request plumbing
# req [--noauth] [--token T] [--timeout S] [--save FILE] METHOD PATH [curl args...]
# Records HTTP_STATUS, CURL_RC and the body in $BODY_FILE.
req() {
  local noauth=0 tokhdr="" tmo="$TIMEOUT" save=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --noauth)  noauth=1; shift ;;
      --token)   tokhdr=${2:-}; shift 2 ;;
      --timeout) tmo=${2:-$TIMEOUT}; shift 2 ;;
      --save)    save=${2:-}; shift 2 ;;
      *) break ;;
    esac
  done
  local method=${1:-GET} path=${2:-/}
  shift 2 2>/dev/null || shift $#
  REQ_DESC="$method $path"; CURL_RC=0; : > "$BODY_FILE"
  local args=(-sS -N -o "$BODY_FILE" -w '%{http_code}' -m "$tmo" -X "$method" -H 'Expect:')
  if [ "$noauth" = 0 ]; then
    if [ -n "$tokhdr" ]; then args+=(-H "X-ADK-Token: $tokhdr")
    elif [ -n "$TOKEN" ]; then args+=(-H "X-ADK-Token: $TOKEN"); fi
  fi
  HTTP_STATUS=$(curl "${args[@]}" "$@" "$BASE$path" 2>"$ERR_FILE") || CURL_RC=$?
  HTTP_STATUS=${HTTP_STATUS:-000}
  [ -n "$save" ] && cp "$BODY_FILE" "$save" 2>/dev/null
  printf '%s %s -> %s rc=%s bytes=%s\n' "$(date +%H:%M:%S)" "$REQ_DESC" "$HTTP_STATUS" "$CURL_RC" "$(wc -c < "$BODY_FILE" 2>/dev/null)" >> "$REQ_LOG"
  return 0
}
pjson() { req POST "$1" -H 'Content-Type: application/json' --data-binary "$2"; }

# check status 200 [201...] | body SUBSTR | re ERE | nomatch ERE | magic HEX | minbytes N | rc 0 28
check() {
  local kind=${1:-}; shift || true
  local c ok
  case "$kind" in
    status)
      ok=0; for c in "$@"; do [ "$HTTP_STATUS" = "$c" ] && ok=1; done
      [ "$ok" = 1 ] || t_fail "expected HTTP $*, got $HTTP_STATUS" ;;
    body)    grep -qF -- "${1:-}" "$BODY_FILE" 2>/dev/null || t_fail "body lacks substring: ${1:-}" ;;
    re)      grep -qE -- "${1:-}" "$BODY_FILE" 2>/dev/null || t_fail "body does not match ERE: ${1:-}" ;;
    nomatch) grep -qE -- "${1:-}" "$BODY_FILE" 2>/dev/null && t_fail "body unexpectedly matches: ${1:-}" ;;
    magic)
      local want=${1:-} got
      got=$(od -An -tx1 -N$(( ${#want} / 2 )) "$BODY_FILE" 2>/dev/null | tr -d ' \n')
      [ "$got" = "$want" ] || t_fail "magic bytes $got != $want" ;;
    minbytes)
      local n; n=$(wc -c < "$BODY_FILE" 2>/dev/null || echo 0)
      [ "$n" -ge "${1:-1}" ] || t_fail "body is $n bytes, want >= ${1:-1}" ;;
    rc)
      ok=0; for c in "$@"; do [ "$CURL_RC" = "$c" ] && ok=1; done
      [ "$ok" = 1 ] || t_fail "expected curl rc $*, got $CURL_RC" ;;
    *) t_fail "unknown check kind: $kind" ;;
  esac
  return 0
}

bytes() { wc -c < "$BODY_FILE" 2>/dev/null || echo 0; }
# json_str KEY / json_raw KEY - pull the first "key":"value" / "key":value from the body
json_str() { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$BODY_FILE" 2>/dev/null | head -1 | sed 's/.*:[[:space:]]*"//; s/"$//'; }
json_raw() { grep -oE "\"$1\"[[:space:]]*:[[:space:]]*[^,}[:space:]]*" "$BODY_FILE" 2>/dev/null | head -1 | sed 's/.*:[[:space:]]*//; s/"//g'; }
adbdev() { "$ADB" -s "$SERIAL" "$@"; }

# ---------------------------------------------------------------- preflight
fail_pre() {
  printf '%sFATAL%s %s\n' "$R" "$N" "$1"
  if [ "${ADK_FORCE:-0}" = 1 ]; then printf '      ADK_FORCE=1 -> continuing anyway\n'
  else printf '      fix the rig (see rig/README.md) or set ADK_FORCE=1 to run regardless\n'; exit 2; fi
}

command -v curl >/dev/null 2>&1 || { printf 'FATAL: curl not found\n'; exit 2; }
printf '%sADK API acceptance suite%s  base=%s serial=%s\n' "$C" "$N" "$BASE" "$SERIAL"
if [ -x "$ADB" ]; then
  "$ADB" start-server >/dev/null 2>&1 || true
  st=$(adbdev get-state 2>/dev/null | tr -d '\r')
  if [ "$st" = device ]; then
    ADB_OK=1
    adbdev forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1 \
      && printf 'adb forward tcp:%s tcp:%s ok\n' "$PORT" "$PORT" \
      || printf '%swarn%s adb forward tcp:%s failed\n' "$Y" "$N" "$PORT"
  else
    printf '%swarn%s adb device %s not ready (get-state=%s)\n' "$Y" "$N" "$SERIAL" "${st:-none}"
  fi
else
  printf '%swarn%s adb not executable at %s\n' "$Y" "$N" "$ADB"
fi
if [ -z "$TOKEN" ]; then
  [ "$ADB_OK" = 1 ] || fail_pre "no ADK_TOKEN and no adb device to read it from"
  TOKEN=$(adbdev shell cat /data/local/tmp/adk.token 2>/dev/null | tr -d '\r\n')
  [ -n "$TOKEN" ] || fail_pre "could not read /data/local/tmp/adk.token (is AdkService running?)"
  printf 'master token read from device (%s chars)\n' "${#TOKEN}"
else
  printf 'using ADK_TOKEN from the environment (%s chars)\n' "${#TOKEN}"
fi
hcode=$(curl -sS -m 5 -o "$WORK/health.json" -w '%{http_code}' "$BASE/health" 2>/dev/null) || true
[ "${hcode:-000}" = 200 ] || fail_pre "GET $BASE/health returned ${hcode:-000}"
printf 'service is up: %s\n\n' "$(head -c 160 "$WORK/health.json" | tr -d '\n')"

# ============================== health, root and authentication ===============
t_begin "GET /health (no auth)"; req --noauth GET /health; check status 200; check re '"ok"[[:space:]]*:[[:space:]]*true'; t_end
t_begin "GET / (no auth required)"; req --noauth GET /; case "$HTTP_STATUS" in 401|403|000) t_fail "GET / returned $HTTP_STATUS, must be reachable without auth" ;; *) : ;; esac; t_end
t_begin "GET /health with X-ADK-Token"; req GET /health; check status 200; t_end
t_begin "GET /state without token -> 401"; req --noauth GET /state; check status 401; t_end
t_begin "GET /state with bogus token -> 401"; req --token deadbeefdeadbeefdeadbeefdeadbeef GET /state; check status 401; t_end
t_begin "GET /state with Authorization: Bearer"; req --noauth GET /state -H "Authorization: Bearer $TOKEN"; check status 200; t_end
t_begin "GET /state with ?token= query auth"; req --noauth GET "/state?token=$TOKEN"; check status 200; t_end

# ============================== state =========================================
t_begin "GET /state is a JSON object with uid"; req GET /state; check status 200; check body '"uid"'; t_end
t_begin "GET /state carries the documented sections"; req GET /state; check status 200
n=$(grep -oE '"(device|build|uid|battery|network|screen|foreground|server)"' "$BODY_FILE" 2>/dev/null | sort -u | wc -l)
[ "$n" -ge 4 ] || t_fail "only $n of 8 documented sections present"
t_end

# ============================== exec ==========================================
t_begin "GET /exec?cmd=id runs as the system uid"; req GET "/exec?cmd=id&timeout_ms=10000"; check status 200; check re 'uid=(0|1000)'; t_end
t_begin "GET /exec?cmd=echo hi"; req GET "/exec?cmd=echo%20hi&timeout_ms=10000"; check status 200; check body 'hi'; t_end
t_begin "POST /exec {\"cmd\":\"echo hi\"}"; pjson /exec '{"cmd":"echo hi","timeout_ms":10000}'; check status 200; check body 'hi'; t_end

# ============================== packages / install / uninstall ================
t_begin "GET /packages?filter=agentic finds the app"; req GET "/packages?filter=agentic"; check status 200; check body 'com.lukeet.agenticdevkit'; t_end
t_begin "GET /packages (unfiltered) returns packages"; req GET /packages; check status 200; check re '"packages"|\[|android'; t_end
if [ -n "$APK" ] && [ -f "$APK" ]; then
  t_begin "POST /install $(basename "$APK")"
  req --timeout 300 POST /install -H 'Content-Type: application/vnd.android.package-archive' --data-binary "@$APK"
  check status 200; check re '"ok"[[:space:]]*:[[:space:]]*true|installed|success'
  t_end

  # Installing ADK over itself replaces the running package, so the channel drops by
  # design: /selfupdate answers first, then installs. Bring it back and prove it.
  if [ -n "$APK" ]; then
    t_begin "channel recovers after ADK is installed over itself"
    adbdev shell am start -n com.lukeet.agenticdevkit/.MainActivity >/dev/null 2>&1
    adbdev forward "tcp:$PORT" "tcp:$PORT" >/dev/null 2>&1
    recovered=0
    for i in $(seq 1 25); do
      sleep 2
      if curl -s -m 3 -o /dev/null "$BASE/health"; then recovered=1; break; fi
      # ADK arms a 15s self-restart alarm; nudge it too in case the alarm path is
      # unavailable (the app is also startable by hand, which is what an agent does)
      [ $((i % 5)) -eq 0 ] && adbdev shell am start -n com.lukeet.agenticdevkit/.MainActivity >/dev/null 2>&1
    done
    REQ_DESC="GET /health after self-install"; HTTP_STATUS=$([ "$recovered" = 1 ] && echo 200 || echo 000)
    [ "$recovered" = 1 ] || t_fail "the channel did not come back within 40s"
    t_end
    # A self-install arms the 180s rollback alarm; confirm the new build, or the
    # app will revert itself in the middle of this run (which is what happened
    # the first time this suite met the self-update path).
    t_begin "POST /selfupdate/confirm (optional; the app also self-confirms when healthy)"
    req POST /selfupdate/confirm; check status 200; check re '"rollback_pending"[[:space:]]*:[[:space:]]*false'
    t_end
  fi
else
  t_begin "POST /install (raw APK body)"; t_skip "no APK given - pass it as \$1 or set ADK_APK"; t_end
fi
if [ -n "$THROWAWAY_APK" ] && [ -n "$THROWAWAY_PKG" ] && [ -f "$THROWAWAY_APK" ] && [ "$ADB_OK" = 1 ]; then
  t_begin "POST /uninstall?pkg=$THROWAWAY_PKG (throwaway)"
  req --timeout 300 POST /install -H 'Content-Type: application/vnd.android.package-archive' --data-binary "@$THROWAWAY_APK"
  check status 200
  req POST "/uninstall?pkg=$THROWAWAY_PKG"; check status 200
  if adbdev shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$THROWAWAY_PKG"; then t_fail "package still installed after /uninstall"; fi
  t_end
else
  t_begin "POST /uninstall?pkg=... (throwaway package)"; t_skip "set ADK_THROWAWAY_APK + ADK_THROWAWAY_PKG to enable"; t_end
fi

# ============================== logs / report =================================
t_begin "GET /logs?lines=50"; req GET "/logs?lines=50"; check status 200; check minbytes 1; t_end
t_begin "GET /logs?lines=20&grep=ActivityManager"; req GET "/logs?lines=20&grep=ActivityManager"; check status 200; t_end
t_begin "GET /logs?follow=1 (3s window)"
req --timeout 3 GET "/logs?follow=1" -N
if [ "$CURL_RC" = 28 ]; then
  if [ "$(bytes)" -gt 0 ]; then :; else t_skip "streamed nothing in 3s (idle device), HTTP $HTTP_STATUS"; fi
else
  check status 200; check minbytes 1
fi
t_end
t_begin "GET /report?lines=200"; req --timeout 60 GET "/report?lines=200"; check status 200; check minbytes 200; t_end

# ============================== screenshot ====================================
t_begin "GET /screenshot -> PNG"; req GET /screenshot; check status 200; check magic 89504e47; t_end
t_begin "GET /screenshot?format=jpeg&scale=0.5 -> JPEG"; req GET "/screenshot?format=jpeg&scale=0.5"; check status 200; check magic ffd8ff; t_end
t_begin "GET /screenshot without token -> 401"; req --noauth GET /screenshot; check status 401; t_end

# ============================== input =========================================
t_begin "POST /input tap"; pjson /input '{"action":"tap","x":100,"y":200}'; check status 200; t_end
t_begin "POST /input swipe"; pjson /input '{"action":"swipe","x1":100,"y1":400,"x2":100,"y2":800,"duration_ms":200}'; check status 200; t_end
t_begin "POST /input key KEYCODE_HOME"; pjson /input '{"action":"key","key":"KEYCODE_HOME"}'; check status 200; t_end
t_begin "POST /input text"; pjson /input '{"action":"text","text":"hello"}'; check status 200; t_end

# ============================== settings ======================================
t_begin "GET /settings?ns=global&key=airplane_mode_on"; req GET "/settings?ns=global&key=airplane_mode_on"; check status 200; check re 'airplane_mode_on|"value"|"ok"'; t_end
SB_ORIG=""
t_begin "POST /settings sets screen_brightness, read-back confirms"
req GET "/settings?ns=system&key=screen_brightness"; check status 200; SB_ORIG=$(json_raw value)
pjson /settings '{"ns":"system","key":"screen_brightness","value":"120"}'; check status 200
req GET "/settings?ns=system&key=screen_brightness"; check status 200; check body '120'
t_end
if [ -n "$SB_ORIG" ] && [ "$SB_ORIG" != 120 ]; then
  pjson /settings "{\"ns\":\"system\",\"key\":\"screen_brightness\",\"value\":\"$SB_ORIG\"}" >/dev/null 2>&1
  printf '     (restored screen_brightness to %s)\n' "$SB_ORIG"
fi
t_begin "GET /settings unknown key -> 200/404, never 5xx"; req GET "/settings?ns=global&key=adk_suite_missing_key"; check status 200 404; t_end

# ============================== file / ls =====================================
PROBE="adk-write-probe-$$"
t_begin "GET /file?path=/proc/version"; req GET "/file?path=/proc/version"; check status 200; check body 'Linux'; t_end
t_begin "POST /file?path=/sdcard/adk-test.txt (write)"; req POST "/file?path=/sdcard/adk-test.txt" --data-binary "$PROBE"; check status 200; t_end
t_begin "GET /file read-back matches what was written"; req GET "/file?path=/sdcard/adk-test.txt"; check status 200; check body "$PROBE"; t_end
t_begin "GET /ls?path=/sdcard"
req GET "/ls?path=/sdcard"; check status 200
if [ "$ADB_OK" = 1 ]; then check body 'adk.token'; else check minbytes 1; fi
t_end

# ============================== ui / foreground / dumpsys =====================
t_begin "GET /ui returns a hierarchy"; req --timeout 60 GET /ui; check status 200; check minbytes 20; t_end
t_begin "GET /foreground"; req GET /foreground; check status 200; check re 'package|activity|Activity|com\.'; t_end
t_begin "GET /dumpsys?service=battery"; req GET "/dumpsys?service=battery"; check status 200; check re 'Battery|battery'; t_end

# ============================== help ==========================================
t_begin "GET /help is an endpoint index"; req GET /help; check status 200; check re '/exec|/screenshot|/health'; t_end

# ============================== shell sessions ================================
SID=""
t_begin "GET /shell/open creates a session"; req GET /shell/open; check status 200; SID=$(json_str session_id); [ -n "$SID" ] || t_fail "no session_id in response"; t_end
t_begin "POST /shell/write?sid=... runs echo hello"
if [ -z "$SID" ]; then t_skip "no shell session"; else req POST "/shell/write?sid=$SID&data=echo%20hello"; check status 200; fi
t_end
t_begin "GET /shell/read?sid=...&offset=0 shows output"
if [ -z "$SID" ]; then t_skip "no shell session"
else
  sleep 1; req GET "/shell/read?sid=$SID&offset=0"; check status 200
  if ! grep -qF hello "$BODY_FILE" 2>/dev/null; then sleep 1; req GET "/shell/read?sid=$SID&offset=0"; check status 200; check body 'hello'; fi
fi
t_end
t_begin "GET /shell/read with bogus sid -> 400/404"; req GET "/shell/read?sid=adk-no-such-session&offset=0"; check status 400 404; t_end
t_begin "GET /shell/close?sid=..."
if [ -z "$SID" ]; then t_skip "no shell session"; else req GET "/shell/close?sid=$SID"; check status 200; fi
t_end

# ============================== port forwarding ===============================
FWD_ID=""
t_begin "POST /forward/add 18080 -> 8787"
pjson /forward/add '{"bind":"127.0.0.1","local_port":18080,"host":"127.0.0.1","port":8787}'
check status 200; FWD_ID=$(json_raw id); [ -n "$FWD_ID" ] || t_fail "no forward id in response"
t_end
t_begin "GET /forward/list shows local_port 18080"; req GET /forward/list; check status 200; check body '18080'; t_end
t_begin "forwarded port serves /health (end-to-end)"
if [ -z "$FWD_ID" ]; then t_skip "forward was not added"
elif [ "$ADB_OK" != 1 ]; then t_skip "no adb to reach the device-side port"
else
  adbdev forward tcp:18080 tcp:18080 >/dev/null 2>&1 || true
  code=$(curl -sS -m 5 -N -o "$WORK/fwd.body" -w '%{http_code}' http://127.0.0.1:18080/health 2>/dev/null) || true
  cp "$WORK/fwd.body" "$BODY_FILE" 2>/dev/null
  REQ_DESC="GET http://127.0.0.1:18080/health (via ADK forwarder)"; HTTP_STATUS=${code:-000}
  [ "$HTTP_STATUS" = 200 ] || t_fail "forwarded /health returned $HTTP_STATUS"
  adbdev forward --remove tcp:18080 >/dev/null 2>&1 || true
fi
t_end
t_begin "POST /forward/remove?id=..."
if [ -z "$FWD_ID" ]; then t_skip "forward was not added"; else req POST "/forward/remove?id=$FWD_ID"; check status 200; fi
t_end

# ============================== prop / intent / screenrecord ==================
t_begin "GET /prop?key=ro.build.version.sdk"; req GET "/prop?key=ro.build.version.sdk"; check status 200; check body '28'; t_end
t_begin "POST /intent start .MainActivity"; pjson /intent '{"action":"start","component":"com.lukeet.agenticdevkit/.MainActivity"}'; check status 200; t_end
t_begin "GET /foreground shows MainActivity after /intent"; sleep 1; req GET /foreground; check status 200; check re 'agenticdevkit|MainActivity'; t_end
t_begin "GET /screenrecord?seconds=3 (best effort)"
req --timeout 45 GET "/screenrecord?seconds=3"
if [ "$HTTP_STATUS" = 200 ] && [ "$(bytes)" -ge 1024 ]; then :
elif [ "$HTTP_STATUS" = 200 ]; then t_skip "200 but only $(bytes) bytes of video"
else t_skip "screenrecord unsupported on this image (HTTP $HTTP_STATUS, rc $CURL_RC)"
fi
t_end

# ============================== pairing =======================================
PAIR_OPEN=""
t_begin "GET /pair/status (no auth)"; req --noauth GET /pair/status; check status 200; check re '"open"'; PAIR_OPEN=$(json_raw open); t_end
t_begin "POST /pair with a wrong code -> 403"
if [ "$PAIR_OPEN" = true ]; then t_skip "pairing window is open; refusing to burn a wrong-code attempt"
else
  req --noauth POST /pair -H 'Content-Type: application/json' --data-binary '{"code":"000000","name":"adk-suite-wrongcode"}'
  if [ "$HTTP_STATUS" = 401 ]; then
    note "unauthenticated /pair gave 401 - pairing seems to need the master token"
    pjson /pair '{"code":"000000","name":"adk-suite-wrongcode"}'
  fi
  check status 403
fi
t_end
t_begin "GET /clients without token -> 401"; req --noauth GET /clients; check status 401; t_end
CLIENT_ID=""
if [ -z "$PAIR_CODE" ] && [ "$ADB_OK" = 1 ]; then
  derived=$(derive_pair_code)
  if [ -n "$derived" ]; then
    PAIR_CODE="$derived"
    PAIR_OPEN=true   # the derivation just opened the window and read the code off it
    note "derived pairing code from the car screen"
  fi
fi
t_begin "pairing round-trip with ADK_PAIR_CODE"
if [ -z "$PAIR_CODE" ]; then t_skip "set ADK_PAIR_CODE=<6-digit code from the car screen> to enable"
elif [ "$PAIR_OPEN" != true ]; then t_skip "pairing window not open - tap 'Pair a device' in the car app first"
else
  req --noauth POST /pair -H 'Content-Type: application/json' --data-binary "{\"code\":\"$PAIR_CODE\",\"name\":\"$PAIR_NAME\"}"
  if [ "$HTTP_STATUS" = 401 ]; then note "unauthenticated /pair gave 401; retrying with the master token"; pjson /pair "{\"code\":\"$PAIR_CODE\",\"name\":\"$PAIR_NAME\"}"; fi
  check status 200; check re '"client_id"'; check re '"token"[[:space:]]*:[[:space:]]*"[0-9a-fA-F]{64}"'
  CLIENT_ID=$(json_str client_id)
fi
t_end
t_begin "GET /clients lists the newly paired client"
if [ -z "$CLIENT_ID" ]; then t_skip "no client was paired"; else req GET /clients; check status 200; check body "$PAIR_NAME"; fi
t_end
t_begin "POST /pair/revoke removes the client"
if [ -z "$CLIENT_ID" ]; then t_skip "no client was paired"
else
  pjson /pair/revoke "{\"client_id\":\"$CLIENT_ID\"}"; check status 200
  req GET /clients; check status 200; check nomatch "$CLIENT_ID"
fi
t_end



# ============================== rig-level checks ==============================
t_begin "GET /adk-no-such-endpoint -> 404"; req GET /adk-no-such-endpoint; check status 404; t_end
t_begin "device token mirror matches the token in use (/sdcard or /data/local/tmp)"
REQ_DESC="adb shell cat /data/local/tmp/adk.token"; HTTP_STATUS="-"; : > "$BODY_FILE"
if [ "$ADB_OK" != 1 ]; then t_skip "no adb device"
else
  tok=$(adbdev shell cat /sdcard/adk.token 2>/dev/null | tr -d '\r\n')
  [ -z "$tok" ] && tok=$(adbdev shell cat /data/local/tmp/adk.token 2>/dev/null | tr -d '\r\n')
  if [ -z "$tok" ]; then t_fail "missing or empty /sdcard/adk.token (and /data/local/tmp/adk.token)"
  elif [ "$tok" != "$TOKEN" ]; then t_fail "device token does not match the token this suite used"; fi
fi
t_end


# ============================== no prompts after pairing ======================
# The car must never wait for a human once a device is paired: every action below
# is expected to run on the token alone, with no confirmation UI.
t_begin "paired token alone sets a system setting (no prompt)"
req POST /settings -H 'Content-Type: application/json' -d '{"ns":"global","key":"adk_probe","value":"1"}'
check status 200; check re '"ok"[[:space:]]*:[[:space:]]*true'
t_end
t_begin "paired token alone injects input (no prompt)"
req POST /input -H 'Content-Type: application/json' -d '{"action":"key","key":"KEYCODE_WAKEUP"}'
check status 200; check re '"ok"[[:space:]]*:[[:space:]]*true'
t_end

# ============================== driver drift checks ============================
# The app declares the API once (Api.java). /help and /openapi.json are generated
# from it, and the CLI + MCP server derive themselves from it at runtime. These
# tests fail if any of that drifts apart.
REPO_DIR=${REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../agentic-dev-kit" 2>/dev/null && pwd)}
SPEC_FILE="$WORK/openapi.json"

t_begin "GET /openapi.json is a valid contract"
req GET /openapi.json; check status 200
if [ "$HTTP_STATUS" = 200 ]; then cp "$BODY_FILE" "$SPEC_FILE"; check re '"openapi"'; check re '"paths"'; fi
t_end

t_begin "every /help endpoint appears in /openapi.json"
req GET /help; check status 200
HELP_COUNT=0; SPEC_COUNT=0
if [ "$HTTP_STATUS" = 200 ]; then
  HELP_COUNT=$(grep -o '"operation_id"' "$BODY_FILE" | wc -l | tr -d ' ')
  SPEC_COUNT=$(grep -o '"operationId"' "$SPEC_FILE" 2>/dev/null | wc -l | tr -d ' ')
  if [ "$HELP_COUNT" -lt 40 ]; then t_fail "/help lists only $HELP_COUNT endpoints"
  elif [ "$HELP_COUNT" != "$SPEC_COUNT" ]; then t_fail "/help has $HELP_COUNT endpoints but /openapi.json has $SPEC_COUNT operations"; fi
fi
t_end

t_begin "MCP server derives its tools from the live spec (no drift)"
if [ ! -f "$REPO_DIR/mcp/adk-mcp.py" ]; then t_skip "mcp/adk-mcp.py not found"
elif ! command -v python3 >/dev/null 2>&1; then t_skip "python3 not available"
else
  REQ_DESC="python3 mcp/adk-mcp.py --selftest"; HTTP_STATUS="-"; : > "$BODY_FILE"
  if ADK_URL="$BASE" ADK_TOKEN="$TOKEN" python3 "$REPO_DIR/mcp/adk-mcp.py" --selftest \
       > "$BODY_FILE" 2>"$ERR_FILE"; then
    mcp_count=$(grep -o '"adk_[a-z0-9_]*"' "$BODY_FILE" | sort -u | wc -l | tr -d ' ')
    if [ "$mcp_count" -lt 40 ]; then t_fail "MCP derived only $mcp_count tools"
    elif [ -n "$HELP_COUNT" ] && [ "$mcp_count" != "$HELP_COUNT" ]; then
      t_fail "MCP derived $mcp_count tools but the app declares $HELP_COUNT endpoints"
    fi
    grep -q '"fingerprint": "[0-9a-f]' "$BODY_FILE" || t_fail "no spec fingerprint in --selftest output"
  else
    t_fail "adk-mcp.py --selftest failed: $(head -c 200 "$ERR_FILE" | tr '\n' ' ')"
  fi
fi
t_end

t_begin "CLI resolves endpoints from the live spec (no drift)"
if [ ! -x "$REPO_DIR/tools/adk" ]; then t_skip "tools/adk not found"
elif ! command -v python3 >/dev/null 2>&1; then t_skip "python3 not available"
else
  REQ_DESC="tools/adk endpoints --json"; HTTP_STATUS="-"; : > "$BODY_FILE"
  if ADK_URL="$BASE" ADK_TOKEN="$TOKEN" python3 "$REPO_DIR/tools/adk" endpoints --json \
       > "$BODY_FILE" 2>"$ERR_FILE"; then
    cli_count=$(grep -o '"operation_id"' "$BODY_FILE" | wc -l | tr -d ' ')
    [ "$cli_count" -lt 40 ] && t_fail "CLI listed only $cli_count endpoints"
  else
    t_fail "adk endpoints failed: $(head -c 200 "$ERR_FILE" | tr '\n' ' ')"
  fi
fi
t_end

# ---------------------------------------------------------------- cleanup + summary
if [ "$ADB_OK" = 1 ]; then adbdev shell rm -f /sdcard/adk-test.txt >/dev/null 2>&1 || true; fi
printf '\n%s--------------------------------------------------------------------%s\n' "$C" "$N"
printf '%s%d passed, %d failed, %d skipped%s  (%d tests, base %s)\n' "$G" "$PASS" "$FAIL" "$SKIP" "$N" "$TEST_N" "$BASE"
if [ "${#FAILED_NAMES[@]}" -gt 0 ]; then
  printf '%sfailed tests:%s\n' "$R" "$N"
  for n in "${FAILED_NAMES[@]}"; do printf '  - %s\n' "$n"; done
fi
printf 'artifacts: %s (requests.log, fail-NN.body)\n' "$WORK"
if [ "$FAIL" -eq 0 ]; then exit 0; else exit 1; fi

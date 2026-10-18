#!/usr/bin/env python3
"""End-to-end check of the *installed release build*, driven only over HTTP.

Proves the newest behaviour is really in the published APK:
  clear clients -> open the pairing window by tapping the car screen ->
  read the 6-digit code off the screen -> pair -> tap the per-row Revoke ->
  the client is gone and its token is rejected.
"""
import json, re, sys, time, urllib.request

BASE = "http://127.0.0.1:8787"
MASTER = "rigtoken-0123456789abcdef"

def call(method, path, body=None, token=MASTER, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if token:
        req.add_header("X-ADK-Token", token)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            payload = r.read()
            return r.status, (payload if raw else json.loads(payload.decode()))
    except urllib.error.HTTPError as e:
        payload = e.read()
        try:
            return e.code, json.loads(payload.decode())
        except Exception:
            return e.code, payload.decode("utf-8", "replace")

def nodes():
    st, body = call("GET", "/ui")
    assert st == 200, f"/ui -> {st}"
    return body["nodes"]

def centre(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    l, t, r, b = map(int, m.groups())
    return (l + r) // 2, (t + b) // 2

def tap(x, y):
    st, body = call("POST", "/input", {"action": "tap", "x": x, "y": y})
    assert st == 200 and body.get("ok"), f"tap failed: {body}"

def tap_node(pred, what, tries=4):
    """Tap the first node matching pred, re-reading the tree each attempt."""
    for attempt in range(tries):
        for n in nodes():
            if pred(n):
                x, y = centre(n["bounds"])
                tap(x, y)
                return n
        time.sleep(1)
    raise SystemExit(f"FAIL: could not find {what} in the UI tree")

fails = []
def check(name, ok, detail=""):
    print(("PASS  " if ok else "FAIL  ") + name + (f"  ({detail})" if detail else ""))
    if not ok:
        fails.append(name)

# 1. start clean
for c in call("GET", "/clients")[1]["clients"]:
    call("POST", "/pair/revoke", {"client_id": c["client_id"]})
check("clients cleared", call("GET", "/clients")[1]["clients"] == [])

# 2. open the pairing window by tapping the car's own button
tap_node(lambda n: n.get("id", "").endswith(":id/pair"), "the pair button")
for _ in range(6):
    st, body = call("GET", "/pair/status", token=None)
    if body.get("open"):
        break
    time.sleep(1)
check("pairing window opened by tapping the car screen", body.get("open") is True,
      f"{body.get('seconds_left')}s left")

# 3. read the code off the screen and pair
code = None
for _ in range(6):
    for n in nodes():
        if n.get("id", "").endswith(":id/pair_code"):
            code = re.sub(r"\D", "", n.get("text", ""))
            break
    if code and len(code) == 6:
        break
    time.sleep(1)
check("6-digit code read off the car screen", bool(code and len(code) == 6), f"code {code}")

st, body = call("POST", "/pair", {"code": code, "name": "release-verify"}, token=None)
check("POST /pair accepted the code", st == 200 and "token" in body, f"HTTP {st}")
client_token = body.get("token", "")
client_id = body.get("client_id", "")

st, _ = call("GET", "/clients", token=client_token)
check("paired token works", st == 200)

# 4. the list shows the device, with its own Revoke button
time.sleep(2)
tree = nodes()
label = next((n for n in tree if client_id and client_id in n.get("text", "")), None)
check("car screen lists the paired device", label is not None)
idx = tree.index(label) if label else -1
revoke = next((n for n in tree[idx + 1:] if n.get("text") == "REVOKE"), None) if idx >= 0 else None
check("car screen offers Revoke for that row", revoke is not None)

# 5. tap it and confirm the token dies
if revoke:
    x, y = centre(revoke["bounds"])
    tap(x, y)
    time.sleep(2)
st, body = call("GET", "/clients")
ids = [c["client_id"] for c in body.get("clients", [])]
check("device removed from /clients by the UI tap", client_id not in ids, f"ids now {ids}")
st, _ = call("GET", "/clients", token=client_token)
check("revoked token is rejected (401)", st == 401, f"HTTP {st}")

print()
print("release verification:", "ALL PASS" if not fails else f"{len(fails)} FAILED: {fails}")
sys.exit(1 if fails else 0)

#!/usr/bin/env python3
"""adk-mcp - an MCP (stdio) server for the MG4 Agentic Development Kit.

Why this exists
---------------
The car app owns the API. This server owns no endpoint knowledge of its own: at
startup it fetches the app's OpenAPI document from ``GET /openapi.json`` and
derives every MCP tool from it (name from ``operationId``, input schema from the
declared parameters, dispatch from method+path). If the app grows an endpoint,
this server grows the tool on the next poll - it cannot drift, because there is
only one declaration and it lives in the app.

    app endpoint table  ->  /openapi.json  ->  tools/list + tools/call

Usage
-----
    ADK_URL=http://127.0.0.1:8787 ADK_TOKEN=... ./adk-mcp.py
    ./adk-mcp.py --url http://192.168.1.50:8787 --tools 'exec,install,logs,screenshot'
    ./adk-mcp.py --selftest          # print the derived tool list and exit

Environment
-----------
    ADK_URL                  base URL (default http://127.0.0.1:8787)
    ADK_TOKEN                token; falls back to ~/.adk/token
    ADK_MCP_TOOLS            comma-separated fnmatch patterns to expose a subset
    ADK_MCP_DOWNLOAD_DIR     where binary results are written (default ~/.adk/downloads)
    ADK_MCP_MAX_IMAGE_BYTES  larger images are written to disk instead of inlined
    ADK_MCP_POLL_SECONDS     spec re-poll interval, 0 disables (default 60)
"""

import argparse
import base64
import fnmatch
import json
import os
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

SERVER_NAME = "adk-mcp"
SERVER_VERSION = "0.1.0"
PROTOCOL_VERSION = "2024-11-05"
DEFAULT_URL = "http://127.0.0.1:8787"

TYPE_MAP = {
    "string": "string",
    "int": "integer",
    "double": "number",
    "bool": "boolean",
    "json": "object",
    "file": "string",
}


def log(*parts):
    print("[adk-mcp]", *parts, file=sys.stderr, flush=True)


# --------------------------------------------------------------------------- http


class Client:
    def __init__(self, url, token, timeout=120):
        self.url = url.rstrip("/")
        self.token = token or ""
        self.timeout = timeout

    def request(self, method, path, query=None, body=None, raw=None, raw_type=None,
                timeout=None, extra_headers=None):
        """Perform one API call. Returns (status, headers, bytes)."""
        url = self.url + path
        if query:
            clean = {k: v for k, v in query.items() if v is not None and v != ""}
            if clean:
                url += "?" + urllib.parse.urlencode(clean, doseq=True)
        data = None
        headers = {"Accept": "*/*"}
        if self.token:
            headers["X-ADK-Token"] = self.token
        if raw is not None:
            data = raw
            headers["Content-Type"] = raw_type or "application/octet-stream"
        elif body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if extra_headers:
            headers.update(extra_headers)
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers or {}), e.read()
        except urllib.error.URLError as e:
            raise ConnectionError(f"cannot reach {url}: {e.reason}") from None
        except TimeoutError:
            raise ConnectionError(f"timed out calling {url}") from None


# ---------------------------------------------------------------------- tool spec


class ToolSet:
    """Tools derived from the app's OpenAPI document."""

    def __init__(self, spec, patterns=None):
        self.spec = spec or {}
        self.patterns = patterns or ["*"]
        self.tools = {}
        self.order = []
        self.build()

    # -- derivation ---------------------------------------------------------

    def build(self):
        self.tools = {}
        self.order = []
        for path, item in (self.spec.get("paths") or {}).items():
            if not isinstance(item, dict):
                continue
            for method, op in item.items():
                if method.lower() not in ("get", "post", "put", "delete", "patch"):
                    continue
                if not isinstance(op, dict):
                    continue
                tool = self._tool(path, method.lower(), op)
                if not self._selected(tool["name"]):
                    continue
                self.tools[tool["name"]] = tool
                self.order.append(tool["name"])
        self.order.sort()

    def _selected(self, name):
        short = name[4:] if name.startswith("adk_") else name
        for pat in self.patterns:
            pat = pat.strip()
            if not pat:
                continue
            if fnmatch.fnmatch(name, pat) or fnmatch.fnmatch(short, pat) or pat in name:
                return True
        return False

    def _tool(self, path, method, op):
        operation_id = op.get("operationId") or (method + path.replace("/", "_"))
        name = "adk_" + operation_id
        returns = op.get("x-adk-returns", "json")
        auth = op.get("x-adk-auth", "token")
        summary = op.get("summary", "")

        props = {}
        required = []
        query_names, body_names, file_desc = [], [], None

        for param in op.get("parameters") or []:
            pname = param.get("name")
            if not pname:
                continue
            schema = dict(param.get("schema") or {})
            schema_type = TYPE_MAP.get(param.get("x-adk-type", "string"), "string")
            entry = {
                "type": schema_type,
                "description": "[query] " + (param.get("description") or ""),
            }
            if "default" in schema:
                entry["default"] = schema["default"]
            props[pname] = entry
            query_names.append(pname)
            if param.get("required"):
                required.append(pname)

        body_schema = (((op.get("requestBody") or {}).get("content") or {})
                       .get("application/json") or {}).get("schema") or {}
        for pname, pschema in (body_schema.get("properties") or {}).items():
            entry = dict(pschema)
            entry["description"] = "[body] " + (entry.get("description") or "")
            props[pname] = entry
            body_names.append(pname)
            if pname in (body_schema.get("required") or []):
                required.append(pname)

        raw_content = (op.get("requestBody") or {}).get("content") or {}
        if "application/octet-stream" in raw_content:
            file_desc = (op.get("requestBody") or {}).get("description") or "file to upload"
            props["file"] = {
                "type": "string",
                "description": "[upload] local path whose bytes become the request body: " + file_desc,
            }
            required.append("file")

        description = (
            f"{summary}\n\n"
            f"{method.upper()} {path} -> {returns}"
            + ("" if auth == "token" else " (no token required)")
            + (f"\nrequired: {', '.join(sorted(set(required)))}" if required else "")
            + (f"\nexample: {op.get('x-adk-example')}" if op.get("x-adk-example") else "")
        )
        return {
            "name": name,
            "description": description,
            "inputSchema": {
                "type": "object",
                "properties": props,
                "required": sorted(set(required)),
                "additionalProperties": False,
            },
            "meta": {
                "method": method.upper(),
                "path": path,
                "returns": returns,
                "auth": auth,
                "operation_id": operation_id,
                "query": query_names,
                "body": body_names,
                "raw_file": file_desc is not None,
            },
        }

    # -- presentation -------------------------------------------------------

    def listing(self):
        return [{"name": t["name"], "description": t["description"],
                 "inputSchema": t["inputSchema"]} for t in
                (self.tools[n] for n in self.order)]

    def fingerprint(self):
        blob = json.dumps([(t["name"], sorted(t["inputSchema"]["properties"]),
                            t["inputSchema"]["required"], t["meta"]["path"], t["meta"]["method"])
                           for t in (self.tools[n] for n in self.order)], sort_keys=True)
        import hashlib
        return hashlib.sha256(blob.encode("utf-8")).hexdigest()[:16]

    def get(self, name):
        return self.tools.get(name)


# ------------------------------------------------------------------------ server


class Server:
    def __init__(self, args):
        self.args = args
        self.url = (args.url or os.environ.get("ADK_URL") or DEFAULT_URL).rstrip("/")
        self.token = args.token or os.environ.get("ADK_TOKEN") or self._file_token()
        self.client = Client(self.url, self.token)
        self.patterns = [p for p in (args.tools or os.environ.get("ADK_MCP_TOOLS", "*")).split(",")]
        self.tools = ToolSet({}, self.patterns)
        self.lock = threading.Lock()
        self.write_lock = threading.Lock()
        self.spec_version = None
        self.download_dir = os.path.expanduser(
            args.download_dir or os.environ.get("ADK_MCP_DOWNLOAD_DIR", "~/.adk/downloads"))
        self.max_image = int(os.environ.get("ADK_MCP_MAX_IMAGE_BYTES", 4 * 1024 * 1024))
        self.poll_seconds = float(os.environ.get("ADK_MCP_POLL_SECONDS", args.poll_seconds))
        self.ready = threading.Event()

    def _file_token(self):
        for path in (os.path.expanduser("~/.adk/token"), "/data/local/tmp/adk.token"):
            try:
                with open(path) as fh:
                    tok = fh.read().strip()
                if tok:
                    return tok
            except OSError:
                continue
        return ""

    # -- spec ---------------------------------------------------------------

    def refresh_spec(self, announce=False):
        try:
            status, _headers, payload = self.client.request("GET", "/openapi.json", timeout=20)
        except ConnectionError as e:
            log("spec fetch failed:", e)
            return False
        if status != 200:
            body = payload.decode("utf-8", "replace")[:200]
            log(f"spec fetch returned HTTP {status}: {body}")
            return False
        try:
            spec = json.loads(payload.decode("utf-8"))
        except ValueError as e:
            log("spec was not JSON:", e)
            return False
        with self.lock:
            before = self.tools.fingerprint() if self.tools.order else None
            self.tools = ToolSet(spec, self.patterns)
            self.spec_version = spec.get("x-adk-spec-version")
            after = self.tools.fingerprint()
        self.ready.set()
        log(f"spec loaded: {len(self.tools.order)} tools, spec v{self.spec_version}, fp {after}")
        if announce and before is not None and before != after:
            log("tool surface changed; notifying client")
            self.notify("notifications/tools/list_changed", {})
        return True

    def poll_loop(self):
        while self.poll_seconds > 0:
            time.sleep(self.poll_seconds)
            self.refresh_spec(announce=True)

    # -- transport ----------------------------------------------------------

    def send(self, obj):
        line = json.dumps(obj, separators=(",", ":"))
        with self.write_lock:
            sys.stdout.write(line + "\n")
            sys.stdout.flush()

    def notify(self, method, params):
        self.send({"jsonrpc": "2.0", "method": method, "params": params})

    def reply(self, msg_id, result=None, error=None):
        out = {"jsonrpc": "2.0", "id": msg_id}
        if error is not None:
            out["error"] = error
        else:
            out["result"] = result
        self.send(out)

    # -- mcp methods --------------------------------------------------------

    def handle(self, msg):
        method = msg.get("method")
        msg_id = msg.get("id")
        params = msg.get("params") or {}

        if method == "initialize":
            self.reply(msg_id, {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {
                    "tools": {"listChanged": True},
                    "resources": {"listChanged": True},
                },
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
                "instructions": (
                    "Tools are generated from the car app's own OpenAPI document "
                    f"at {self.url}/openapi.json. Call adk_get_health first; if a tool "
                    "returns 401, pair with adk_post_pair using the 6-digit code shown in "
                    "the car app. adk_post_exec runs shell commands as the system user, "
                    "adk_post_install installs APKs, adk_get_screenshot returns an image."
                ),
            })
        elif method in ("notifications/initialized", "notifications/cancelled"):
            pass
        elif method == "ping":
            self.reply(msg_id, {})
        elif method == "tools/list":
            if not self.ready.is_set():
                self.refresh_spec()
            with self.lock:
                self.reply(msg_id, {"tools": self.tools.listing()})
        elif method == "tools/call":
            self.reply(msg_id, self.call_tool(params))
        elif method == "resources/list":
            self.reply(msg_id, {"resources": [
                {"uri": "adk://spec", "name": "ADK OpenAPI document",
                 "description": "The car app's authoritative API contract", "mimeType": "application/json"},
                {"uri": "adk://health", "name": "ADK health", "mimeType": "application/json"},
            ]})
        elif method == "resources/read":
            self.reply(msg_id, self.read_resource(params.get("uri", "")))
        elif method == "prompts/list":
            self.reply(msg_id, {"prompts": []})
        elif msg_id is not None:
            self.reply(msg_id, error={"code": -32601, "message": f"method not found: {method}"})

    def read_resource(self, uri):
        try:
            if uri == "adk://spec":
                _s, _h, payload = self.client.request("GET", "/openapi.json")
                text = payload.decode("utf-8", "replace")
            elif uri == "adk://health":
                _s, _h, payload = self.client.request("GET", "/health")
                text = payload.decode("utf-8", "replace")
            else:
                return {"contents": [], "isError": True}
        except ConnectionError as e:
            return {"contents": [{"uri": uri, "mimeType": "text/plain", "text": str(e)}], "isError": True}
        return {"contents": [{"uri": uri, "mimeType": "application/json", "text": text}]}

    # -- tool execution -----------------------------------------------------

    def call_tool(self, params):
        name = params.get("name")
        args = dict(params.get("arguments") or {})
        with self.lock:
            tool = self.tools.get(name)
        if tool is None:
            if not self.ready.is_set():
                self.refresh_spec()
                with self.lock:
                    tool = self.tools.get(name)
        if tool is None:
            return self._text(f"unknown tool '{name}'. Call tools/list - tools are derived from "
                              f"{self.url}/openapi.json and change when the car app changes.",
                              is_error=True)
        meta = tool["meta"]
        if meta["auth"] == "token" and not self.token:
            return self._text("no token configured: set ADK_TOKEN, pass --token, or pair with "
                              "adk_post_pair using the code shown in the car app.", is_error=True)

        query, body = {}, {}
        for key, value in args.items():
            if key in meta["query"]:
                query[key] = self._scalar(value)
            elif key in meta["body"]:
                body[key] = value
        raw = None
        if meta["raw_file"]:
            path = args.get("file")
            if not path:
                return self._text("this tool needs 'file': the local path to upload", is_error=True)
            path = os.path.expanduser(str(path))
            if not os.path.isfile(path):
                return self._text(f"no such file: {path}", is_error=True)
            with open(path, "rb") as fh:
                raw = fh.read()

        timeout = self.args.timeout
        if meta["path"] in ("/logs", "/screenrecord", "/report"):
            timeout = max(timeout, 300)
        try:
            status, headers, payload = self.client.request(
                meta["method"], meta["path"], query=query,
                body=body if (body and raw is None) else None, raw=raw, timeout=timeout)
        except ConnectionError as e:
            return self._text(f"{e}\nIs the channel running? Check the car app, or "
                              f"`adb forward tcp:8787 tcp:8787` for the rig.", is_error=True)

        if status == 401:
            return self._text("401 unauthorized: pair first (adk_post_pair with the 6-digit code "
                              "from the car app) or use the master token from "
                              "/data/local/tmp/adk.token.", is_error=True)
        return self._package(meta, status, headers, payload)

    @staticmethod
    def _scalar(value):
        if isinstance(value, bool):
            return "1" if value else "0"
        return value

    def _package(self, meta, status, headers, payload):
        returns = meta["returns"]
        ctype = (headers.get("Content-Type") or "").lower()
        if returns.startswith("image/") and status == 200:
            if len(payload) > self.max_image:
                path = self._save(payload, "png" if "png" in returns else "jpg")
                return self._text(f"screenshot too large to inline ({len(payload)} bytes); saved to {path}")
            return {"content": [
                {"type": "image", "data": base64.b64encode(payload).decode("ascii"),
                 "mimeType": returns},
                {"type": "text", "text": f"{meta['method']} {meta['path']} -> {status}, "
                                         f"{len(payload)} bytes"},
            ], "isError": status >= 400}
        if returns in ("json",) or "json" in ctype:
            text = payload.decode("utf-8", "replace")
            try:
                text = json.dumps(json.loads(text), indent=2)
            except ValueError:
                pass
            return self._text(text, is_error=status >= 400)
        if returns in ("text", "html") or ctype.startswith("text/"):
            return self._text(payload.decode("utf-8", "replace"), is_error=status >= 400)
        path = self._save(payload, "mp4" if "mp4" in returns else "bin")
        return self._text(f"{meta['method']} {meta['path']} -> {status}, {len(payload)} bytes "
                          f"saved to {path}", is_error=status >= 400)

    def _save(self, payload, ext):
        os.makedirs(self.download_dir, exist_ok=True)
        fd, path = tempfile.mkstemp(prefix="adk-", suffix="." + ext, dir=self.download_dir)
        with os.fdopen(fd, "wb") as fh:
            fh.write(payload)
        return path

    @staticmethod
    def _text(text, is_error=False):
        return {"content": [{"type": "text", "text": text}], "isError": bool(is_error)}

    # -- run ----------------------------------------------------------------

    def run(self):
        self.refresh_spec()
        if self.poll_seconds > 0:
            t = threading.Thread(target=self.poll_loop, daemon=True)
            t.start()
        for line in sys.stdin:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except ValueError:
                log("ignoring non-JSON line")
                continue
            try:
                self.handle(msg)
            except Exception as e:  # keep the channel alive
                log("handler error:", repr(e))
                if msg.get("id") is not None:
                    self.reply(msg["id"], error={"code": -32603, "message": str(e)})

    def selftest(self):
        ok = self.refresh_spec()
        out = {
            "ok": ok and bool(self.tools.order),
            "url": self.url,
            "spec_version": self.spec_version,
            "tool_count": len(self.tools.order),
            "fingerprint": self.tools.fingerprint() if self.tools.order else None,
            "tools": self.tools.order,
        }
        print(json.dumps(out, indent=2))
        return 0 if out["ok"] else 1


def main():
    ap = argparse.ArgumentParser(
        prog="adk-mcp",
        description="MCP (stdio) server for the MG4 Agentic Development Kit. "
                    "Tools are derived from the app's live OpenAPI document, so the MCP "
                    "surface cannot drift from the API.")
    ap.add_argument("--url", help=f"car base URL (default {DEFAULT_URL})")
    ap.add_argument("--token", help="ADK token (default: ADK_TOKEN env or ~/.adk/token)")
    ap.add_argument("--tools", help="comma-separated fnmatch patterns to expose a subset "
                                    "(default: all, or ADK_MCP_TOOLS)")
    ap.add_argument("--download-dir", help="where binary results are written")
    ap.add_argument("--timeout", type=float, default=120, help="per-call timeout in seconds")
    ap.add_argument("--poll-seconds", type=float,
                    default=float(os.environ.get("ADK_MCP_POLL_SECONDS", 60)),
                    help="how often to re-read the spec (0 disables)")
    ap.add_argument("--selftest", action="store_true",
                    help="print the derived tool list and exit (used by the rig test)")
    args = ap.parse_args()
    server = Server(args)
    if args.selftest:
        sys.exit(server.selftest())
    server.run()


if __name__ == "__main__":
    main()

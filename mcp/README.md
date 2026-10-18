# MCP server

`adk-mcp.py` exposes the car as MCP tools over stdio. It is deliberately thin.

## Why it cannot drift

The tool list is not written down here — it is **read from the app** at startup:

```
app endpoint table (Api.java)
      └─► GET /openapi.json
              └─► tools/list   (name = operationId, schema = declared parameters)
              └─► tools/call   (dispatch = method + path from the same document)
```

* Tool names come from the app's `operationId`, so they are stable and predictable
  (`adk_post_install`, `adk_get_logs`, `adk_post_exec`, …).
* Input schemas come from the app's parameter declarations — query parameters and JSON
  body fields, with types, required flags and defaults.
* The server re-reads the spec every `ADK_MCP_POLL_SECONDS` (default 60) and sends
  `notifications/tools/list_changed` when the surface changes, so a self-updated ADK
  shows its new endpoints to the agent without restarting anything.
* A `file` parameter (declared with type `file` in the app) is streamed from a local
  path as the request body — that is how `adk_post_install` and `adk_post_selfupdate`
  work.
* Binary results follow the app's declared return type: images come back as MCP image
  content, everything else is written to `ADK_MCP_DOWNLOAD_DIR` and the path is
  returned.

If the car is unreachable the server still starts and retries in the background, so an
MCP client can come up before the car does.

## Run it

```bash
ADK_URL=http://192.168.1.50:8787 ADK_TOKEN=<paired token> python3 mcp/adk-mcp.py
python3 mcp/adk-mcp.py --selftest          # print the derived tool list and exit
python3 mcp/adk-mcp.py --tools 'exec,install,logs,screenshot,ui'   # expose a subset
```

| Env | Meaning |
| --- | --- |
| `ADK_URL` | base URL (default `http://127.0.0.1:8787`) |
| `ADK_TOKEN` | token; falls back to `~/.adk/token`, then `/data/local/tmp/adk.token` |
| `ADK_MCP_TOOLS` | comma-separated fnmatch patterns to expose a subset |
| `ADK_MCP_DOWNLOAD_DIR` | where binary results are written (default `~/.adk/downloads`) |
| `ADK_MCP_MAX_IMAGE_BYTES` | larger images are saved to disk instead of inlined |
| `ADK_MCP_POLL_SECONDS` | spec re-poll interval, `0` disables |

Client configuration:

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

## Curated subsets

45 tools is a lot for a model to choose from. If your client struggles, expose the
subset you actually use — patterns match tool names:

```bash
ADK_MCP_TOOLS='adk_get_health,adk_get_state,adk_post_exec,adk_post_install,adk_get_logs,adk_get_report,adk_get_screenshot,adk_get_ui,adk_get_wait,adk_post_input,adk_post_pair'
```

Everything is still reachable through `adk_call`-style generic tools if you prefer a
two-tool surface (`adk_get_help` to discover, plus one generic caller) — but the
derived per-endpoint tools give the model typed arguments, which is the point of MCP.

## Verifying the derivation

```bash
python3 mcp/adk-mcp.py --selftest | head -20
```

prints the URL, spec revision, tool count, a fingerprint of the tool surface, and the
tool names — that is what `rig/test_api.sh` asserts against, so a mismatch between the
app and its drivers fails the rig test rather than silently drifting.

package com.lukeet.agenticdevkit;

import android.content.Context;

/** The human-facing dashboard served at GET /. */
public final class Pages {

    private Pages() {}

    public static String dashboard(Context ctx, HttpServer server) {
        StringBuilder rows = new StringBuilder();
        for (Api.Ep e : Api.ENDPOINTS) {
            rows.append("<tr><td class=m>").append(esc(e.method)).append("</td><td class=p>")
                    .append(esc(e.path)).append("</td><td>").append(esc(e.summary))
                    .append(e.auth.equals("no") ? " <span class=open>no token</span>" : "")
                    .append("</td></tr>");
        }
        String port = String.valueOf(server.port());
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Agentic Development Kit</title><style>"
                + "body{font:14px/1.45 -apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#0f1115;color:#e6e6e6}"
                + "header{padding:18px 22px;background:#1565c0;color:#fff}"
                + "h1{margin:0;font-size:19px}header p{margin:4px 0 0;opacity:.85;font-size:13px}"
                + "main{padding:18px 22px;max-width:1000px}"
                + "section{margin-bottom:22px;background:#171a21;border:1px solid #242833;border-radius:8px;padding:14px 16px}"
                + "h2{font-size:14px;margin:0 0 10px;text-transform:uppercase;letter-spacing:.06em;color:#8ab4f8}"
                + "code,pre{font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
                + "input{background:#0f1115;color:#e6e6e6;border:1px solid #333a48;border-radius:6px;padding:7px 9px;font:13px ui-monospace,monospace}"
                + "button{background:#1565c0;color:#fff;border:0;border-radius:6px;padding:8px 13px;font-size:13px;cursor:pointer}"
                + "table{border-collapse:collapse;width:100%;font-size:13px}td{padding:5px 8px;border-bottom:1px solid #22262f;vertical-align:top}"
                + "td.m{color:#7ee787;white-space:nowrap}td.p{color:#ffa657;white-space:nowrap}"
                + ".open{color:#8b949e;font-size:11px;border:1px solid #30363d;border-radius:10px;padding:1px 6px}"
                + ".tok{color:#7ee787}.muted{color:#8b949e}"
                + "</style></head><body>"
                + "<header><h1>Agentic Development Kit</h1><p>system-level control channel &middot; port "
                + esc(port) + " &middot; signed with the platform key, running as "
                + "uid " + android.os.Process.myUid() + "</p></header><main>"
                + "<section><h2>Token</h2>"
                + "<p class=muted>Paste the master token (shown in the car app, or <code>adb shell cat "
                + Config.TOKEN_MIRROR + "</code>). It is kept in this browser only.</p>"
                + "<p><input id=tok size=44 placeholder='master or paired token'> "
                + "<button onclick=\"localStorage.adk=document.getElementById('tok').value;load()\">Use token</button> "
                + "<button onclick=\"go('/health')\">/health</button> "
                + "<button onclick=\"go('/state')\">/state</button> "
                + "<button onclick=\"go('/help')\">/help</button></p>"
                + "<pre id=out class=muted>ready</pre></section>"
                + "<section><h2>Endpoints</h2><table>" + rows + "</table></section>"
                + "<section><h2>Quick calls</h2><pre>"
                + "curl -H \"X-ADK-Token: $T\" http://&lt;car-ip&gt;:" + esc(port) + "/state\n"
                + "curl -H \"X-ADK-Token: $T\" --get --data-urlencode 'cmd=id' http://&lt;car-ip&gt;:" + esc(port) + "/exec\n"
                + "curl -H \"X-ADK-Token: $T\" --data-binary @app.apk http://&lt;car-ip&gt;:" + esc(port) + "/install\n"
                + "curl -H \"X-ADK-Token: $T\" http://&lt;car-ip&gt;:" + esc(port) + "/screenshot -o car.png\n"
                + "curl -H \"X-ADK-Token: $T\" 'http://&lt;car-ip&gt;:" + esc(port) + "/logs?lines=200&follow=1'\n"
                + "</pre></section>"
                + "<script>"
                + "function tok(){return localStorage.adk||''}"
                + "function go(p){fetch(p,{headers:{'X-ADK-Token':tok()}}).then(r=>r.text()).then(t=>{document.getElementById('out').textContent=t.slice(0,20000)})"
                + ".catch(e=>{document.getElementById('out').textContent='error: '+e})}"
                + "function load(){document.getElementById('tok').value=tok();go('/health')}"
                + "load();"
                + "</script></main></body></html>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

package com.lukeet.agenticdevkit;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The whole HTTP API surface. Every handler is a method here; {@link #register()}
 * wires them onto the server and {@link #ENDPOINTS} doubles as the machine-readable
 * index served at /help and /openapi.json.
 */
public final class Api {

    private static final String TAG = "ADK";

    private final Context ctx;
    private final HttpServer server;
    private final long startedAt = System.currentTimeMillis();

    public Api(Context ctx, HttpServer server) {
        this.ctx = ctx;
        this.server = server;
    }

    /* ------------------------------------------------------------------ routing */

    /**
     * The single source of truth for the API surface.
     *
     * /help, /openapi.json and the dashboard are all generated from this table, and
     * the agent-side drivers (tools/adk, mcp/adk-mcp.py) derive themselves from
     * /openapi.json at runtime - so the app cannot drift from its clients: adding a
     * row here is the only place an endpoint is ever declared.
     */
    static final class P {
        final String in;        // "query" | "body"
        final String name;
        final String type;      // string | int | double | bool | json | file
        final boolean required;
        final String def;
        final String desc;

        P(String in, String name, String type, boolean required, String def, String desc) {
            this.in = in;
            this.name = name;
            this.type = type;
            this.required = required;
            this.def = def;
            this.desc = desc;
        }
    }

    static final class Ep {
        final String method;
        final String path;
        final String auth;      // "no" | "token"
        final String returns;   // json | text | html | image/png | image/jpeg | video/mp4 | binary
        final String summary;
        final String example;
        final P[] params;

        Ep(String method, String path, String auth, String returns, String summary, String example, P... params) {
            this.method = method;
            this.path = path;
            this.auth = auth;
            this.returns = returns;
            this.summary = summary;
            this.example = example;
            this.params = params;
        }
    }

    private static P q(String name, String type, boolean required, String def, String desc) {
        return new P("query", name, type, required, def, desc);
    }

    private static P b(String name, String type, boolean required, String def, String desc) {
        return new P("body", name, type, required, def, desc);
    }

    private static P rawBody(String desc) {
        return new P("body", "file", "file", true, null, desc);
    }

    static final Ep[] ENDPOINTS = {
            new Ep("GET", "/health", "no", "json", "liveness + version; the only unauthenticated read",
                    "curl $BASE/health"),
            new Ep("GET", "/pair/status", "no", "json", "is a pairing window open, seconds left, lockout",
                    "curl $BASE/pair/status"),
            new Ep("POST", "/pair", "no", "json", "exchange the in-car 6-digit code for a device token",
                    "curl -X POST $BASE/pair -d '{\"code\":\"123456\",\"name\":\"mac\"}'",
                    b("code", "string", true, null, "the 6-digit code shown in the car app"),
                    b("name", "string", false, "", "a label for this client, e.g. 'mac-agent'")),
            new Ep("GET", "/", "no", "html", "human dashboard (token prompt, endpoint list)",
                    "open $BASE/ in a browser"),
            new Ep("GET", "/help", "token", "json", "this endpoint index as JSON (machine readable)",
                    "curl -H \"X-ADK-Token: $T\" $BASE/help"),
            new Ep("GET", "/openapi.json", "token", "json", "OpenAPI 3.0 description; drivers derive from this",
                    "curl -H \"X-ADK-Token: $T\" $BASE/openapi.json"),
            new Ep("GET", "/state", "token", "json", "device, build, uid, battery, storage, network, screen, foreground app",
                    "curl -H \"X-ADK-Token: $T\" $BASE/state"),
            new Ep("GET", "/version", "token", "json", "app version, uptime and pending-rollback state",
                    "curl -H \"X-ADK-Token: $T\" $BASE/version"),

            new Ep("GET", "/exec", "token", "json", "run a shell command as system (adb shell equivalent)",
                    "curl -H \"X-ADK-Token: $T\" --get --data-urlencode 'cmd=id' $BASE/exec",
                    q("cmd", "string", true, null, "shell command, run with /system/bin/sh -c"),
                    q("timeout_ms", "int", false, "60000", "kill the command after this long")),
            new Ep("POST", "/exec", "token", "json", "same, command in the JSON body",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/exec -d '{\"cmd\":\"getprop ro.build.version.release\"}'",
                    b("cmd", "string", true, null, "shell command, run with /system/bin/sh -c"),
                    b("timeout_ms", "int", false, "60000", "kill the command after this long")),

            new Ep("POST", "/install", "token", "json", "install or update an APK; body is the APK (raw or multipart)",
                    "curl -H \"X-ADK-Token: $T\" --data-binary @app.apk $BASE/install",
                    rawBody("APK bytes (raw body, or multipart/form-data with one file part)"),
                    q("replace", "bool", false, "true", "pm install -r"),
                    q("downgrade", "bool", false, "true", "pm install -d, allows same/lower versionCode"),
                    q("grant", "bool", false, "true", "pm install -g, grant all runtime permissions"),
                    q("user", "int", false, "0", "target user id")),
            new Ep("GET", "/packages", "token", "json", "list installed packages",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/packages?filter=diauto'",
                    q("filter", "string", false, "", "case-insensitive substring of the package name"),
                    q("third_party", "bool", false, "false", "only non-system packages"),
                    q("labels", "bool", false, "false", "include the human label (slower)")),
            new Ep("POST", "/uninstall", "token", "json", "uninstall a package",
                    "curl -H \"X-ADK-Token: $T\" -X POST '$BASE/uninstall?pkg=com.example'",
                    b("pkg", "string", true, null, "package name"),
                    b("user", "int", false, "0", "user id")),

            new Ep("GET", "/logs", "token", "text", "logcat: one-shot dump or a live stream",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/logs?lines=200&grep=OPENHU'",
                    q("lines", "int", false, "200", "tail this many lines (with follow=1, then keep streaming)"),
                    q("tag", "string", false, "", "logcat -s <tag>:<level>"),
                    q("grep", "string", false, "", "regex filter applied server-side"),
                    q("pid", "string", false, "", "only this pid"),
                    q("level", "string", false, "V", "minimum level for tag= (V D I W E F)"),
                    q("buffers", "string", false, "", "comma list, e.g. main,system,crash"),
                    q("follow", "bool", false, "false", "stream until the client disconnects"),
                    q("jsonl", "bool", false, "false", "one JSON object per line")),
            new Ep("GET", "/report", "token", "text", "one-shot diagnostic bundle: state + logcat + dumpsys + net (never an empty report)",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/report?lines=500'",
                    q("lines", "int", false, "400", "logcat tail size"),
                    q("pkg", "string", false, "", "also dump this package")),
            new Ep("GET", "/screenshot", "token", "image/png", "PNG or JPEG of the car screen",
                    "curl -H \"X-ADK-Token: $T\" $BASE/screenshot -o car.png",
                    q("format", "string", false, "png", "png or jpeg"),
                    q("scale", "double", false, "1.0", "resize factor, e.g. 0.5"),
                    q("quality", "int", false, "85", "jpeg quality 1-100")),
            new Ep("GET", "/screenrecord", "token", "video/mp4", "record the screen for N seconds",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/screenrecord?seconds=5' -o rec.mp4",
                    q("seconds", "int", false, "10", "1-180"),
                    q("size", "string", false, "", "e.g. 1280x720"),
                    q("bitrate", "string", false, "", "e.g. 4000000")),

            new Ep("POST", "/input", "token", "json", "inject tap / swipe / drag / longpress / text / key / roll",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/input -d '{\"action\":\"tap\",\"x\":960,\"y\":540}'",
                    b("action", "string", true, null, "tap | longpress | swipe | drag | text | key | roll"),
                    b("x", "int", false, null, "tap/longpress x"),
                    b("y", "int", false, null, "tap/longpress y"),
                    b("x1", "int", false, null, "swipe/drag start x"),
                    b("y1", "int", false, null, "swipe/drag start y"),
                    b("x2", "int", false, null, "swipe/drag end x"),
                    b("y2", "int", false, null, "swipe/drag end y"),
                    b("duration", "int", false, "300", "gesture duration in ms (duration_ms is accepted too)"),
                    b("text", "string", false, null, "for action=text"),
                    b("key", "string", false, null, "for action=key, e.g. KEYCODE_HOME"),
                    b("dx", "int", false, "0", "for action=roll"),
                    b("dy", "int", false, "0", "for action=roll")),
            new Ep("GET", "/ui", "token", "json", "uiautomator hierarchy as JSON nodes (or raw XML)",
                    "curl -H \"X-ADK-Token: $T\" $BASE/ui",
                    q("format", "string", false, "json", "json or xml")),
            new Ep("GET", "/wait", "token", "json", "wait for UI text/id/desc; optionally tap it when found",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/wait?text=Settings&tap=1'",
                    q("text", "string", false, "", "substring of text or content-desc"),
                    q("id", "string", false, "", "substring of resource-id"),
                    q("desc", "string", false, "", "substring of content-desc"),
                    q("timeout_ms", "int", false, "20000", "give up after this long"),
                    q("interval_ms", "int", false, "700", "poll interval"),
                    q("tap", "bool", false, "false", "tap the centre of the match")),
            new Ep("GET", "/foreground", "token", "json", "current focused window / resumed activity",
                    "curl -H \"X-ADK-Token: $T\" $BASE/foreground"),

            new Ep("GET", "/settings", "token", "json", "read one setting, or list a whole namespace",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/settings?ns=global&key=airplane_mode_on'",
                    q("ns", "string", false, "global", "global | system | secure"),
                    q("key", "string", false, "", "setting name; omit or all=1 to list the namespace"),
                    q("all", "bool", false, "false", "list every key in the namespace")),
            new Ep("POST", "/settings", "token", "json", "write or delete a setting",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/settings -d '{\"ns\":\"global\",\"key\":\"adk_note\",\"value\":\"hi\"}'",
                    b("ns", "string", false, "global", "global | system | secure"),
                    b("key", "string", true, null, "setting name"),
                    b("value", "string", false, null, "new value"),
                    b("delete", "bool", false, "false", "delete the setting instead")),
            new Ep("GET", "/prop", "token", "text", "getprop: one key as JSON, or everything as text",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/prop?key=ro.build.version.sdk'",
                    q("key", "string", false, "", "property name; omit for the full list")),
            new Ep("POST", "/prop", "token", "json", "setprop (SELinux-protected props are refused, honestly reported)",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/prop -d '{\"key\":\"debug.adk\",\"value\":\"1\"}'",
                    b("key", "string", true, null, "property name"),
                    b("value", "string", false, "", "property value")),

            new Ep("GET", "/file", "token", "binary", "download a file from the car",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/file?path=/proc/version'",
                    q("path", "string", true, null, "absolute path on the car"),
                    q("text", "bool", false, "false", "force text/plain"),
                    q("max_bytes", "int", false, "268435456", "refuse files larger than this")),
            new Ep("POST", "/file", "token", "json", "write a file on the car (raw or multipart body)",
                    "curl -H \"X-ADK-Token: $T\" -X POST '$BASE/file?path=/data/local/tmp/x.txt' --data-binary 'hello'",
                    rawBody("file bytes to write"),
                    q("path", "string", true, null, "absolute destination path"),
                    q("append", "bool", false, "false", "append instead of truncate")),
            new Ep("GET", "/ls", "token", "json", "directory listing",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/ls?path=/sdcard'",
                    q("path", "string", false, "/sdcard", "absolute directory path")),

            new Ep("GET", "/dumpsys", "token", "text", "dumpsys a system service",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/dumpsys?service=window'",
                    q("service", "string", false, "activity", "service name, e.g. battery, window, wifi"),
                    q("grep", "string", false, "", "server-side grep -E"),
                    q("lines", "int", false, "0", "head -N, 0 for everything")),
            new Ep("POST", "/intent", "token", "json", "am start / startservice / stopservice / broadcast / force-stop",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/intent -d '{\"action\":\"start\",\"component\":\"com.drivehub.diauto.mg4/.main.MainActivity\"}'",
                    b("action", "string", false, "start", "start | startservice | stopservice | broadcast | force-stop"),
                    b("component", "string", false, "", "pkg/.Activity or pkg/pkg.Service"),
                    b("intent_action", "string", false, "", "intent action string"),
                    b("data", "string", false, "", "intent data URI"),
                    b("type", "string", false, "", "intent mime type"),
                    b("package", "string", false, "", "package (force-stop)"),
                    b("extras", "json", false, "{}", "string extras: {\"k\":\"v\"}"),
                    b("extras_int", "json", false, "{}", "int extras"),
                    b("extras_bool", "json", false, "{}", "boolean extras"),
                    b("flags", "json", false, "[]", "intent flag numbers"),
                    b("user", "int", false, "-1", "user id")),

            new Ep("GET", "/shell/open", "token", "json", "open a persistent shell session (adb shell without adbd)",
                    "curl -H \"X-ADK-Token: $T\" $BASE/shell/open"),
            new Ep("POST", "/shell/write", "token", "json", "write to a shell session",
                    "curl -H \"X-ADK-Token: $T\" -X POST '$BASE/shell/write?sid=abc&data=ls%20-la'",
                    b("sid", "string", true, null, "session id from /shell/open"),
                    b("data", "string", false, null, "text to send (a newline is added); omit to send the raw body")),
            new Ep("GET", "/shell/read", "token", "json", "read new output from a session",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/shell/read?sid=abc&offset=0&wait_ms=5000'",
                    q("sid", "string", true, null, "session id"),
                    q("offset", "int", false, "0", "byte offset returned by the previous read"),
                    q("wait_ms", "int", false, "0", "long-poll for new output up to this long")),
            new Ep("GET", "/shell/close", "token", "json", "close a shell session",
                    "curl -H \"X-ADK-Token: $T\" '$BASE/shell/close?sid=abc'",
                    q("sid", "string", true, null, "session id")),
            new Ep("GET", "/shell/list", "token", "json", "list open shell sessions",
                    "curl -H \"X-ADK-Token: $T\" $BASE/shell/list"),

            new Ep("POST", "/forward/add", "token", "json", "TCP forward: listen on the car, pipe to a host (adb forward / reverse)",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/forward/add -d '{\"local_port\":19000,\"host\":\"127.0.0.1\",\"port\":8787}'",
                    b("bind", "string", false, "0.0.0.0", "0.0.0.0 = reachable from the network (adb forward); 127.0.0.1 = car-local (adb reverse)"),
                    b("local_port", "int", true, null, "port to listen on, on the car"),
                    b("host", "string", false, "127.0.0.1", "target host"),
                    b("port", "int", true, null, "target port")),
            new Ep("GET", "/forward/list", "token", "json", "list forward rules",
                    "curl -H \"X-ADK-Token: $T\" $BASE/forward/list"),
            new Ep("POST", "/forward/remove", "token", "json", "remove a forward rule",
                    "curl -H \"X-ADK-Token: $T\" -X POST '$BASE/forward/remove?id=abc123'",
                    b("id", "string", true, null, "rule id from /forward/list")),

            new Ep("GET", "/clients", "token", "json", "paired devices (id, name, last seen, ip)",
                    "curl -H \"X-ADK-Token: $T\" $BASE/clients"),
            new Ep("POST", "/pair/revoke", "token", "json", "revoke a paired device",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/pair/revoke -d '{\"client_id\":\"deadbeef\"}'",
                    b("client_id", "string", false, null, "client id from /clients"),
                    b("token", "string", false, null, "or the token itself")),

            new Ep("POST", "/selfupdate", "token", "json", "install a new build of ADK itself, with auto-rollback to last-known-good",
                    "curl -H \"X-ADK-Token: $T\" --data-binary @adk.apk $BASE/selfupdate",
                    rawBody("the new ADK APK; must have this app's package name")),
            new Ep("POST", "/selfupdate/confirm", "token", "json", "cancel the pending rollback after a good update",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/selfupdate/confirm"),
            new Ep("POST", "/selfupdate/rollback", "token", "json", "immediately reinstall the last-known-good build",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/selfupdate/rollback"),
            new Ep("POST", "/service/restart", "token", "json", "restart the HTTP channel in place",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/service/restart"),
            new Ep("POST", "/service/stop", "token", "json", "stop the HTTP channel (the app stays installed)",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/service/stop"),
            new Ep("POST", "/reboot", "token", "json", "reboot the head unit",
                    "curl -H \"X-ADK-Token: $T\" -X POST $BASE/reboot",
                    b("mode", "string", false, "", "empty for normal, or recovery / bootloader")),
    };

    /** Kept for the legacy table shape used by older drivers. */
    public void register() {
        server.route("GET", "/health", new HttpServer.Handler() {
            @Override public void handle(HttpServer.Request r, HttpServer.Response s) { health(r, s); }
        });
        server.route("GET", "/pair/status", new HttpServer.Handler() {
            @Override public void handle(HttpServer.Request r, HttpServer.Response s) { pairStatus(r, s); }
        });
        server.route("POST", "/pair", new HttpServer.Handler() {
            @Override public void handle(HttpServer.Request r, HttpServer.Response s) { pair(r, s); }
        });
        server.route("GET", "/", new HttpServer.Handler() {
            @Override public void handle(HttpServer.Request r, HttpServer.Response s) { dashboard(r, s); }
        });
        server.route("GET", "/favicon.ico", new HttpServer.Handler() {
            @Override public void handle(HttpServer.Request r, HttpServer.Response s) { s.bytes(204, null, new byte[0]); }
        });
        server.route("ANY", "/", new HttpServer.Handler() {
            @Override public void handle(HttpServer.Request r, HttpServer.Response s) { dashboard(r, s); }
        });

        reg("GET", "/help", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { help(r, s); } });
        reg("GET", "/openapi.json", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { openapi(r, s); } });
        reg("GET", "/state", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { state(r, s); } });
        reg("GET", "/version", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { version(r, s); } });

        reg("ANY", "/exec", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { exec(r, s); } });

        reg("POST", "/install", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { install(r, s); } });
        reg("GET", "/packages", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { packages(r, s); } });
        reg("POST", "/uninstall", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { uninstall(r, s); } });

        reg("GET", "/logs", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { logs(r, s); } });
        reg("GET", "/report", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { report(r, s); } });
        reg("GET", "/screenshot", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { screenshot(r, s); } });
        reg("GET", "/screenrecord", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { screenrecord(r, s); } });

        reg("POST", "/input", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { input(r, s); } });
        reg("GET", "/ui", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { ui(r, s); } });
        reg("GET", "/wait", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { waitFor(r, s); } });
        reg("GET", "/foreground", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { foreground(r, s); } });

        reg("GET", "/settings", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { settingsGet(r, s); } });
        reg("POST", "/settings", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { settingsSet(r, s); } });
        reg("GET", "/prop", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { propGet(r, s); } });
        reg("POST", "/prop", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { propSet(r, s); } });

        reg("GET", "/file", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { fileGet(r, s); } });
        reg("POST", "/file", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { filePut(r, s); } });
        reg("GET", "/ls", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { ls(r, s); } });

        reg("GET", "/dumpsys", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { dumpsys(r, s); } });
        reg("POST", "/intent", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { intent(r, s); } });

        reg("GET", "/shell/open", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { shellOpen(r, s); } });
        reg("POST", "/shell/write", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { shellWrite(r, s); } });
        reg("GET", "/shell/read", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { shellRead(r, s); } });
        reg("GET", "/shell/close", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { shellClose(r, s); } });
        reg("GET", "/shell/list", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { shellList(r, s); } });

        reg("POST", "/forward/add", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { forwardAdd(r, s); } });
        reg("GET", "/forward/list", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { forwardList(r, s); } });
        reg("POST", "/forward/remove", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { forwardRemove(r, s); } });

        reg("GET", "/clients", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { clients(r, s); } });
        reg("POST", "/pair/revoke", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { pairRevoke(r, s); } });

        reg("POST", "/selfupdate", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { selfUpdate(r, s); } });
        reg("POST", "/selfupdate/confirm", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { selfConfirm(r, s); } });
        reg("POST", "/selfupdate/rollback", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { selfRollback(r, s); } });
        reg("POST", "/service/restart", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { serviceRestart(r, s); } });
        reg("POST", "/service/stop", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { serviceStop(r, s); } });
        reg("POST", "/reboot", new Handler() { public void run(HttpServer.Request r, HttpServer.Response s) throws Exception { reboot(r, s); } });
    }

    /** Auth-checked route registration. */
    private void reg(String method, String path, final Handler h) {
        server.route(method, path, new HttpServer.Handler() {
            @Override public void handle(HttpServer.Request r, HttpServer.Response s) throws Exception {
                if (!authorized(r, s)) return;
                h.run(r, s);
            }
        });
    }

    private interface Handler {
        void run(HttpServer.Request r, HttpServer.Response s) throws Exception;
    }

    /* --------------------------------------------------------------------- auth */

    /** Master token or a paired client token; writes 401 and returns false otherwise. */
    private boolean authorized(HttpServer.Request r, HttpServer.Response s) {
        String token = token(r);
        if (token == null || token.isEmpty()) {
            s.json(401, new Json.Obj().put("ok", false)
                    .put("error", "missing token")
                    .put("hint", "send X-ADK-Token, or pair first: POST /pair with the code shown in the car app")
                    .toString());
            server.rejected();
            return false;
        }
        if (TokenStore.equals(token, TokenStore.master(ctx))) return true;
        Pairing.Client cl = Pairing.authenticate(ctx, token, r.remoteIp);
        if (cl != null) return true;
        s.json(401, new Json.Obj().put("ok", false)
                .put("error", "unknown or revoked token")
                .put("hint", "pair again with POST /pair, or read the master token from " + Config.TOKEN_MIRROR)
                .toString());
        return false;
    }

    private static String token(HttpServer.Request r) {
        String t = r.header("x-adk-token");
        if (t == null || t.isEmpty()) {
            String auth = r.header("authorization");
            if (auth != null && auth.toLowerCase(Locale.US).startsWith("bearer ")) {
                t = auth.substring(7).trim();
            }
        }
        if (t == null || t.isEmpty()) t = r.param("token");
        return t;
    }

    /* ------------------------------------------------------------ public routes */

    private void health(HttpServer.Request r, HttpServer.Response s) {
        PackageInfo pi = info();
        s.json(200, new Json.Obj()
                .put("ok", true)
                .put("app", "Agentic Development Kit")
                .put("package", ctx.getPackageName())
                .put("version", pi == null ? "?" : pi.versionName)
                .put("version_code", pi == null ? -1 : pi.versionCode)
                .put("uid", android.os.Process.myUid())
                .put("sdk", Build.VERSION.SDK_INT)
                .put("port", server.port())
                .put("uptime_ms", System.currentTimeMillis() - startedAt)
                .put("pairing_open", Pairing.status(ctx, false).open)
                .put("auth", "token")
                .toString());
    }

    private void pairStatus(HttpServer.Request r, HttpServer.Response s) {
        Pairing.Status st = Pairing.status(ctx, false);
        s.json(200, new Json.Obj()
                .put("open", st.open)
                .put("seconds_left", st.secondsLeft)
                .put("locked", st.locked)
                .put("attempts_left", st.attemptsLeft)
                .put("clients", st.clients)
                .toString());
    }

    private void pair(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String code = Json.s(body, "code", r.param("code"));
        String name = Json.s(body, "name", r.param("name", ""));
        Pairing.PairResult pr = Pairing.pair(ctx, code, name, r.remoteIp);
        if (!pr.ok) {
            s.json(403, new Json.Obj().put("ok", false).put("error", pr.error).toString());
            return;
        }
        s.json(200, new Json.Obj()
                .put("ok", true)
                .put("client_id", pr.client.id)
                .put("name", pr.client.name)
                .put("token", pr.client.token)
                .put("note", "store this token; send it as X-ADK-Token on every call")
                .toString());
    }

    private void pairRevoke(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String id = Json.s(body, "client_id", r.param("client_id", r.param("id")));
        if (id == null) id = Json.s(body, "token", r.param("token"));
        boolean ok = Pairing.revoke(ctx, id);
        s.json(ok ? 200 : 404, new Json.Obj().put("ok", ok)
                .put("error", ok ? null : "no such client").toString());
    }

    private void clients(HttpServer.Request r, HttpServer.Response s) {
        List<String> raw = new ArrayList<>();
        for (Pairing.Client c : Pairing.clients(ctx)) {
            raw.add(new Json.Obj()
                    .put("client_id", c.id)
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("created", c.created)
                    .put("last_seen", c.lastSeen)
                    .put("last_ip", c.lastIp)
                    .put("token_prefix", c.token.length() > 8 ? c.token.substring(0, 8) : c.token)
                    .toString());
        }
        s.json(200, new Json.Obj().put("ok", true).putRawArray("clients", raw).toString());
    }

    /* ------------------------------------------------------------------- meta */

    private void help(HttpServer.Request r, HttpServer.Response s) {
        List<String> raw = new ArrayList<>();
        for (Ep e : ENDPOINTS) {
            raw.add(epJson(e).toString());
        }
        s.json(200, new Json.Obj()
                .put("ok", true)
                .put("app", "Agentic Development Kit")
                .put("base", "http://<car-ip>:" + server.port())
                .put("auth_header", "X-ADK-Token")
                .put("pairing", "POST /pair {\"code\":\"<6 digits from the car app>\"}")
                .put("spec", "/openapi.json is the authoritative contract; drivers derive from it")
                .putRawArray("endpoints", raw)
                .toString());
    }

    /** One endpoint as JSON; also the payload driver generators read. */
    private Json.Obj epJson(Ep e) {
        List<String> params = new ArrayList<>();
        for (P p : e.params) {
            Json.Obj po = new Json.Obj()
                    .put("in", p.in)
                    .put("name", p.name)
                    .put("type", p.type)
                    .put("required", p.required)
                    .put("description", p.desc);
            if (p.def != null) po.put("default", p.def);
            params.add(po.toString());
        }
        return new Json.Obj()
                .put("operation_id", operationId(e))
                .put("method", e.method)
                .put("path", e.path)
                .put("auth", e.auth)
                .put("returns", e.returns)
                .put("summary", e.summary)
                .put("example", e.example)
                .putRawArray("parameters", params);
    }

    /** Deterministic tool/operation name derived from method + path. */
    static String operationId(Ep e) {
        String p = e.path.replace("/", "_").replace("-", "_").replace(".", "_");
        while (p.startsWith("_")) p = p.substring(1);
        if (p.isEmpty()) p = "root";
        String id = e.method.toLowerCase(Locale.US) + "_" + p;
        while (id.contains("__")) id = id.replace("__", "_");
        return id;
    }

    /** Spec revision: bump when the endpoint table changes shape. */
    static final String SPEC_VERSION = "1";

    private void openapi(HttpServer.Request r, HttpServer.Response s) {
        PackageInfo pi = info();
        Map<String, Json.Obj> paths = new LinkedHashMap<>();
        for (Ep e : ENDPOINTS) {
            String method = e.method.toLowerCase(Locale.US);
            Json.Obj op = new Json.Obj()
                    .put("operationId", operationId(e))
                    .put("summary", e.summary)
                    .put("description", e.summary + (e.auth.equals("no")
                            ? " (no token required)" : " (requires X-ADK-Token)"))
                    .put("x-adk-returns", e.returns)
                    .put("x-adk-auth", e.auth)
                    .put("x-adk-example", e.example);

            List<String> queryParams = new ArrayList<>();
            List<String> bodyProps = new ArrayList<>();
            List<String> requiredBody = new ArrayList<>();
            String rawDesc = null;
            for (P p : e.params) {
                if (p.in.equals("query")) {
                    queryParams.add(paramJson(p));
                } else if (p.type.equals("file")) {
                    rawDesc = p.desc;
                } else {
                    bodyProps.add(paramJson(p));
                    if (p.required) requiredBody.add(Json.str(p.name));
                }
            }
            if (!queryParams.isEmpty()) op.putRawArray("parameters", queryParams);

            if (rawDesc != null) {
                op.putRaw("requestBody", new Json.Obj()
                        .put("required", true)
                        .put("description", rawDesc)
                        .putRaw("content", new Json.Obj()
                                .putRaw("application/octet-stream", new Json.Obj()
                                        .putRaw("schema", new Json.Obj().put("type", "string")
                                                .put("format", "binary").toString())
                                        .toString())
                                .putRaw("multipart/form-data", new Json.Obj()
                                        .putRaw("schema", new Json.Obj().put("type", "object")
                                                .putRaw("properties", new Json.Obj().putRaw("file",
                                                        new Json.Obj().put("type", "string")
                                                                .put("format", "binary").toString()).toString())
                                                .toString())
                                        .toString())
                                .toString())
                        .toString());
            } else if (!bodyProps.isEmpty()) {
                Json.Obj props = new Json.Obj();
                for (String pj : bodyProps) {
                    Map<String, Object> m = Json.parseObj(pj);
                    props.putRaw(Json.s(m, "name", ""), pj);
                }
                op.putRaw("requestBody", new Json.Obj()
                        .put("required", !requiredBody.isEmpty())
                        .putRaw("content", new Json.Obj()
                                .putRaw("application/json", new Json.Obj()
                                        .putRaw("schema", new Json.Obj()
                                                .put("type", "object")
                                                .putRaw("properties", props.toString())
                                                .putRawArray("required", requiredBody)
                                                .toString())
                                        .toString())
                                .toString())
                        .toString());
            }

            String mediaType = mediaTypeFor(e.returns);
            op.putRaw("responses", new Json.Obj()
                    .putRaw("200", new Json.Obj()
                            .put("description", "OK")
                            .putRaw("content", new Json.Obj()
                                    .putRaw(mediaType, new Json.Obj()
                                            .putRaw("schema", new Json.Obj()
                                                    .put("type", e.returns.equals("json") ? "object" : "string")
                                                    .toString())
                                            .toString())
                                    .toString())
                            .toString())
                    .putRaw("401", new Json.Obj().put("description", "missing or unknown token").toString())
                    .toString());
            if (e.auth.equals("token")) {
                op.putRaw("security", "[{\"adkToken\":[]}]");
            }
            Json.Obj item = paths.get(e.path);
            if (item == null) item = new Json.Obj();
            item.putRaw(method, op.toString());
            paths.put(e.path, item);
        }
        Json.Obj pathObj = new Json.Obj();
        for (Map.Entry<String, Json.Obj> en : paths.entrySet()) {
            pathObj.putRaw(en.getKey(), en.getValue().toString());
        }
        String doc = new Json.Obj()
                .put("openapi", "3.0.0")
                .put("x-adk-spec-version", SPEC_VERSION)
                .putRaw("info", new Json.Obj()
                        .put("title", "Agentic Development Kit")
                        .put("version", pi == null ? "0.1.0" : pi.versionName)
                        .put("description", "System-level control channel for the MG4 head unit: install apps, "
                                + "run commands, read logs, screenshots, input injection, settings, port forwards. "
                                + "Generated from the app's own endpoint table, so this document is the contract "
                                + "that agent drivers (CLI, MCP) derive themselves from at runtime.")
                        .toString())
                .putRaw("components", new Json.Obj().putRaw("securitySchemes", new Json.Obj()
                        .putRaw("adkToken", new Json.Obj()
                                .put("type", "apiKey").put("in", "header").put("name", "X-ADK-Token").toString())
                        .toString()).toString())
                .putRaw("paths", pathObj.toString())
                .toString();
        s.json(200, doc);
    }

    private static String paramJson(P p) {
        Json.Obj o = new Json.Obj()
                .put("name", p.name)
                .put("in", p.in.equals("query") ? "query" : "body")
                .put("description", p.desc)
                .put("required", p.required)
                .put("x-adk-type", p.type);
        String schemaType = "string";
        if (p.type.equals("int")) schemaType = "integer";
        else if (p.type.equals("double")) schemaType = "number";
        else if (p.type.equals("bool")) schemaType = "boolean";
        else if (p.type.equals("json")) schemaType = "object";
        Json.Obj schema = new Json.Obj().put("type", schemaType);
        if (p.type.equals("file")) schema.put("format", "binary");
        if (p.def != null && !p.def.isEmpty() && !p.type.equals("json")) {
            if (p.type.equals("int")) {
                try { schema.put("default", Long.parseLong(p.def)); } catch (Exception ex) { schema.put("default", p.def); }
            } else if (p.type.equals("bool")) {
                schema.put("default", Boolean.parseBoolean(p.def));
            } else if (p.type.equals("double")) {
                try { schema.put("default", Double.parseDouble(p.def)); } catch (Exception ex) { schema.put("default", p.def); }
            } else {
                schema.put("default", p.def);
            }
        }
        o.putRaw("schema", schema.toString());
        return o.toString();
    }

    private static String mediaTypeFor(String returns) {
        switch (returns) {
            case "json": return "application/json";
            case "text": return "text/plain";
            case "html": return "text/html";
            case "image/png": return "image/png";
            case "image/jpeg": return "image/jpeg";
            case "video/mp4": return "video/mp4";
            default: return "application/octet-stream";
        }
    }

    private void dashboard(HttpServer.Request r, HttpServer.Response s) {
        s.bytes(200, "text/html; charset=utf-8", Pages.dashboard(ctx, server).getBytes());
    }

    /* ------------------------------------------------------------------- state */

    private void version(HttpServer.Request r, HttpServer.Response s) {
        PackageInfo pi = info();
        s.json(200, new Json.Obj()
                .put("ok", true)
                .put("version", pi == null ? "?" : pi.versionName)
                .put("version_code", pi == null ? -1 : pi.versionCode)
                .put("uptime_ms", System.currentTimeMillis() - startedAt)
                .put("port", server.port())
                .put("bind", Config.bind(ctx))
                .put("requests_served", server.served())
                .put("rollback_pending", Rollback.pending(ctx))
                .put("last_good", Rollback.lastGoodPath(ctx))
                .toString());
    }

    private void state(HttpServer.Request r, HttpServer.Response s) {
        PackageInfo pi = info();
        Json.Obj o = new Json.Obj();
        o.put("ok", true);
        o.put("time", iso(System.currentTimeMillis()));
        o.put("uptime_ms", System.currentTimeMillis() - startedAt);
        o.put("uid", android.os.Process.myUid());
        o.put("pid", android.os.Process.myPid());

        o.putRaw("build", new Json.Obj()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("board", Build.BOARD)
                .put("hardware", Build.HARDWARE)
                .put("release", Build.VERSION.RELEASE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("incremental", Build.VERSION.INCREMENTAL)
                .put("fingerprint", Build.FINGERPRINT)
                .put("tags", Build.TAGS)
                .toString());
        o.putRaw("app", new Json.Obj()
                .put("package", ctx.getPackageName())
                .put("version", pi == null ? "?" : pi.versionName)
                .put("version_code", pi == null ? -1 : pi.versionCode)
                .put("apk", ctx.getPackageCodePath())
                .put("code_path", ctx.getPackageCodePath())
                .toString());
        o.putRaw("server", new Json.Obj()
                .put("port", server.port())
                .put("bind", Config.bind(ctx))
                .put("served", server.served())
                .put("rejected", server.rejected())
                .put("last_error", server.lastError())
                .put("clients", Pairing.clients(ctx).size())
                .put("token_mirrored_to", TokenStore.mirroredPaths())
                .toString());
        o.putRaw("network", network().toString());
        o.putRaw("screen", screen().toString());
        o.putRaw("foreground", foregroundJson().toString());
        o.putRaw("storage", storage().toString());
        o.put("shell_sessions", ShellSession.all().size());
        o.put("forwards", Forwarder.list().size());
        o.put("rollback_pending", Rollback.pending(ctx));
        s.json(200, o.toString());
    }

    private Json.Obj network() {
        List<String> addrs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address) {
                        addrs.add(ni.getName() + "=" + a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) { }
        String wifiSsid = "";
        try {
            Shell.Result rr = Shell.sh("dumpsys wifi | grep -m1 -i 'mWifiInfo' ", 8000);
            wifiSsid = rr.out.trim();
        } catch (Throwable ignored) { }
        return new Json.Obj()
                .put("addresses", String.join(", ", addrs))
                .putStrings("address_list", addrs)
                .put("wifi_info", wifiSsid)
                .put("airplane_mode", Settings.Global.getInt(ctx.getContentResolver(), "airplane_mode_on", 0) == 1);
    }

    private Json.Obj screen() {
        Shell.Result r = Shell.sh("dumpsys power | grep -E 'mWakefulness=|mScreenOn=' | head -2", 8000);
        Shell.Result size = Shell.sh("wm size; wm density", 8000);
        return new Json.Obj()
                .put("power", r.out.trim())
                .put("wm", size.out.trim());
    }

    private Json.Obj foregroundJson() {
        String focus = firstLine(Shell.sh("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'", 8000).out);
        String resumed = firstLine(Shell.sh("dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'", 8000).out);
        return new Json.Obj().put("focus", focus).put("resumed", resumed);
    }

    private Json.Obj storage() {
        Shell.Result df = Shell.sh("df /data /sdcard 2>/dev/null", 8000);
        return new Json.Obj()
                .put("df", df.out.trim())
                .put("files_dir", ctx.getFilesDir().getAbsolutePath())
                .put("cache_dir", ctx.getCacheDir().getAbsolutePath())
                .put("sdcard", Environment.getExternalStorageDirectory().getAbsolutePath());
    }

    /* ------------------------------------------------------------------- shell */

    private void exec(HttpServer.Request r, HttpServer.Response s) throws Exception {
        Map<String, Object> body = r.json();
        String cmd = Json.s(body, "cmd", r.param("cmd"));
        if (cmd == null || cmd.trim().isEmpty()) {
            s.json(400, new Json.Obj().put("ok", false)
                    .put("error", "no command; use ?cmd= or {\"cmd\":\"...\"}").toString());
            return;
        }
        long timeout = Json.i(body, "timeout_ms", r.paramLong("timeout_ms", 60_000));
        Shell.Result res = Shell.sh(cmd, Math.max(1000, Math.min(timeout, 600_000)));
        s.json(200, new Json.Obj()
                .put("ok", res.exit == 0)
                .put("exit", res.exit)
                .put("cmd", cmd)
                .put("stdout", res.out)
                .put("stderr", res.err)
                .put("ms", res.ms)
                .put("timeout", res.timeout)
                .toString());
    }

    /* ----------------------------------------------------------------- install */

    private void install(HttpServer.Request r, HttpServer.Response s) throws Exception {
        File apk = new File(ctx.getCacheDir(), "install-" + System.currentTimeMillis() + ".apk");
        long n = receiveApk(r, apk);
        if (n <= 0) {
            s.json(400, new Json.Obj().put("ok", false).put("error", "empty body").toString());
            return;
        }
        String target = packageNameOf(apk);
        if (ctx.getPackageName().equals(target)) {
            // Installing ADK over itself kills this process, so answer first and let
            // the self-update path (backup + rollback alarm) do the work afterwards.
            File keep = new File(ctx.getCacheDir(), "adk-selfupdate.apk");
            //noinspection ResultOfMethodCallIgnored
            apk.renameTo(keep);
            selfUpdateAsync(keep, s);
            return;
        }
        String result = installApk(apk,
                r.paramBool("replace", true),
                r.paramBool("downgrade", true),
                r.paramBool("grant", true),
                (int) r.paramLong("user", 0));
        boolean ok = result.startsWith("Success");
        Json.Obj o = new Json.Obj().put("ok", ok).put("output", result).put("bytes", n);
        String pkg = packageNameOf(apk);
        if (pkg != null) {
            o.put("package", pkg);
            PackageInfo pi = packageInfo(pkg);
            if (pi != null) {
                o.put("version_name", pi.versionName);
                o.put("version_code", pi.versionCode);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        apk.delete();
        s.json(ok ? 200 : 500, o.toString());
    }

    /** Raw body, or the first file part of a multipart/form-data body. */
    private long receiveApk(HttpServer.Request r, File apk) throws IOException {
        String ct = r.header("content-type");
        if (ct != null && ct.toLowerCase(Locale.US).contains("multipart/form-data")) {
            File raw = new File(ctx.getCacheDir(), apk.getName() + ".raw");
            long total = r.bodyToFile(raw);
            long n = Multipart.firstFile(raw, ct, apk);
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
            if (n > 0) return n;
            return total;
        }
        return r.bodyToFile(apk);
    }

    private String installApk(File apk, boolean replace, boolean downgrade, boolean grant, int user) {
        // In-process session install: `pm install` cannot work from this app because
        // installd will not read the APK out of the app's own cache (SELinux).
        String session = Installer.install(ctx, apk, grant, downgrade, user);
        if (session.startsWith("Success")) return session;
        Log.w(TAG, "session install did not succeed (" + session + "); trying pm install");
        StringBuilder cmd = new StringBuilder("pm install");
        if (replace) cmd.append(" -r");
        if (downgrade) cmd.append(" -d");
        if (grant) cmd.append(" -g");
        cmd.append(" --user ").append(user);
        cmd.append(' ').append(Shell.quote(apk.getAbsolutePath()));
        Shell.Result r = Shell.sh(cmd.toString(), 300_000);
        String out = r.combined().trim();
        if (out.isEmpty()) out = session;
        return out;
    }

    private String packageNameOf(File apk) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            return pi == null ? null : pi.packageName;
        } catch (Throwable t) {
            return null;
        }
    }

    private void packages(HttpServer.Request r, HttpServer.Response s) {
        String filter = r.param("filter", "").toLowerCase(Locale.US);
        boolean thirdPartyOnly = r.paramBool("third_party", false);
        boolean labels = r.paramBool("labels", false);
        PackageManager pm = ctx.getPackageManager();
        List<String> raw = new ArrayList<>();
        List<PackageInfo> all = pm.getInstalledPackages(0);
        Collections.sort(all, (a, b) -> a.packageName.compareToIgnoreCase(b.packageName));
        for (PackageInfo pi : all) {
            if (!filter.isEmpty() && !pi.packageName.toLowerCase(Locale.US).contains(filter)) continue;
            ApplicationInfo ai = pi.applicationInfo;
            boolean system = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (thirdPartyOnly && system) continue;
            Json.Obj o = new Json.Obj()
                    .put("package", pi.packageName)
                    .put("version_name", pi.versionName)
                    .put("version_code", pi.versionCode)
                    .put("uid", ai == null ? -1 : ai.uid)
                    .put("system", system)
                    .put("enabled", ai == null || ai.enabled)
                    .put("apk", ai == null ? "" : ai.sourceDir)
                    .put("first_install", pi.firstInstallTime)
                    .put("updated", pi.lastUpdateTime);
            if (labels) {
                try { o.put("label", String.valueOf(pm.getApplicationLabel(ai))); } catch (Throwable ignored) { }
            }
            raw.add(o.toString());
        }
        s.json(200, new Json.Obj().put("ok", true).put("count", raw.size())
                .putRawArray("packages", raw).toString());
    }

    private void uninstall(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String pkg = Json.s(body, "pkg", r.param("pkg", Json.s(body, "package", r.param("package"))));
        if (pkg == null || pkg.isEmpty()) {
            s.json(400, new Json.Obj().put("ok", false).put("error", "?pkg= required").toString());
            return;
        }
        int user = (int) Json.i(body, "user", r.paramLong("user", 0));
        Shell.Result res = Shell.sh("pm uninstall --user " + user + " " + Shell.quote(pkg), 120_000);
        String out = res.combined().trim();
        boolean ok = out.contains("Success");
        s.json(ok ? 200 : 500, new Json.Obj().put("ok", ok).put("package", pkg).put("output", out).toString());
    }

    /* -------------------------------------------------------------------- logs */

    private void logs(HttpServer.Request r, HttpServer.Response s) throws Exception {
        int lines = (int) r.paramLong("lines", 200);
        String tag = r.param("tag", "");
        String grep = r.param("grep", "");
        String pid = r.param("pid", "");
        String level = r.param("level", "V");
        boolean follow = r.paramBool("follow", false);
        boolean jsonl = r.paramBool("jsonl", false);

        List<String> argv = new ArrayList<>();
        argv.add("logcat");
        argv.add("-v");
        argv.add("threadtime");
        String buffers = r.param("buffers", "");
        if (!buffers.isEmpty()) {
            for (String b : buffers.split(",")) {
                argv.add("-b");
                argv.add(b.trim());
            }
        }
        if (!follow) argv.add("-d");
        if (lines > 0) {
            argv.add("-t");
            argv.add(String.valueOf(lines));
        }
        if (pid != null && !pid.isEmpty()) {
            argv.add("--pid=" + pid);
        }
        if (tag != null && !tag.isEmpty()) {
            argv.add("-s");
            argv.add(tag + ":" + level);
        }
        final Pattern pat = grep == null || grep.isEmpty() ? null : Pattern.compile(grep);

        if (!follow) {
            Shell.Result res = Shell.exec(argv.toArray(new String[0]), 60_000);
            StringBuilder sb = new StringBuilder();
            for (String line : res.out.split("\n", -1)) {
                if (pat != null && !pat.matcher(line).find()) continue;
                if (line.isEmpty() && sb.length() == 0) continue;
                sb.append(line).append('\n');
            }
            if (jsonl) {
                List<String> raw = new ArrayList<>();
                for (String line : sb.toString().split("\n")) {
                    if (!line.isEmpty()) raw.add(new Json.Obj().put("line", line).toString());
                }
                s.json(200, new Json.Obj().put("ok", true).putRawArray("lines", raw).toString());
            } else {
                s.text(200, sb.toString());
            }
            return;
        }

        // follow: stream until the client goes away
        Process p = Shell.start(argv.toArray(new String[0]));
        OutputStream out = s.stream(200, jsonl ? "application/x-ndjson" : "text/plain; charset=utf-8");
        InputStream in = p.getInputStream();
        try {
            byte[] buf = new byte[8192];
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();
            int n;
            while ((n = in.read(buf)) > 0) {
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        String line = new String(lineBuf.toByteArray(), "UTF-8");
                        lineBuf.reset();
                        if (pat != null && !pat.matcher(line).find()) continue;
                        if (jsonl) {
                            out.write((new Json.Obj().put("line", line).toString() + "\n").getBytes("UTF-8"));
                        } else {
                            out.write((line + "\n").getBytes("UTF-8"));
                        }
                        out.flush();
                    } else {
                        lineBuf.write(b);
                    }
                }
            }
        } catch (Throwable t) {
            // client disconnected
        } finally {
            try { in.close(); } catch (Throwable ignored) { }
            try { p.destroy(); } catch (Throwable ignored) { }
            try { out.close(); } catch (Throwable ignored) { }
        }
    }

    private void report(HttpServer.Request r, HttpServer.Response s) {
        int lines = (int) r.paramLong("lines", 400);
        String pkg = r.param("pkg", "");
        StringBuilder b = new StringBuilder(64 * 1024);
        b.append("# Agentic Development Kit report\n");
        b.append("generated: ").append(iso(System.currentTimeMillis())).append('\n');
        b.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" | ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        b.append("build: ").append(Build.DISPLAY).append(" | fingerprint ").append(Build.FINGERPRINT).append('\n');
        b.append("uid: ").append(android.os.Process.myUid()).append(" pid: ").append(android.os.Process.myPid()).append('\n');
        b.append("server: port ").append(server.port()).append(" served ").append(server.served())
                .append(" rejected ").append(server.rejected()).append('\n');
        b.append('\n');

        b.append("## network\n");
        b.append(Shell.sh("ip -4 addr show 2>/dev/null | grep -E 'inet |^[0-9]'", 8000).out.trim()).append('\n');
        b.append(Shell.sh("getprop | grep -E 'wifi|dhcp' | head -20", 8000).out.trim()).append('\n');
        b.append('\n');

        b.append("## battery / power\n");
        b.append(head(Shell.sh("dumpsys battery", 8000).out, 40)).append('\n');
        b.append(Shell.sh("dumpsys power | grep -E 'mWakefulness|mScreenOn|mHolding' | head -10", 8000).out.trim()).append('\n');
        b.append('\n');

        b.append("## storage\n");
        b.append(Shell.sh("df -h /data /sdcard /cache 2>/dev/null", 8000).out.trim()).append('\n');
        b.append('\n');

        b.append("## memory\n");
        b.append(head(Shell.sh("dumpsys meminfo | head -30", 15000).out, 30)).append('\n');
        b.append('\n');

        b.append("## foreground\n");
        b.append(Shell.sh("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'", 8000).out.trim()).append('\n');
        b.append(Shell.sh("dumpsys activity activities | grep -E 'mResumedActivity|mFocusedActivity'", 8000).out.trim()).append('\n');
        b.append('\n');

        b.append("## listening sockets\n");
        b.append(Shell.sh("netstat -ltn 2>/dev/null | head -30", 8000).out.trim()).append('\n');
        b.append('\n');

        if (pkg != null && !pkg.isEmpty()) {
            b.append("## package ").append(pkg).append('\n');
            b.append(Shell.sh("dumpsys package " + Shell.quote(pkg) + " | head -60", 20000).out.trim()).append('\n');
            b.append('\n');
        }

        b.append("## logcat (last ").append(lines).append(" lines, errors first)\n");
        b.append(Shell.sh("logcat -d -v threadtime -t " + Math.max(50, lines) + " '*:E' 2>/dev/null | tail -80", 40000).out.trim()).append('\n');
        b.append('\n');
        b.append("## logcat (last ").append(lines).append(" lines, all)\n");
        b.append(Shell.sh("logcat -d -v threadtime -t " + Math.max(50, lines) + " 2>/dev/null", 40000).out.trim()).append('\n');
        b.append('\n');
        b.append("## ADK log (tag ADK)\n");
        b.append(Shell.sh("logcat -d -v threadtime -s ADK:V -t 200 2>/dev/null", 20000).out.trim()).append('\n');
        b.append("\n# end of report\n");
        s.text(200, b.toString());
    }

    /* ------------------------------------------------------------ screen / input */

    private void screenshot(HttpServer.Request r, HttpServer.Response s) throws Exception {
        StringBuilder how = new StringBuilder();
        Bitmap bmp = Screen.capture(ctx, how);
        if (bmp == null) {
            s.json(500, new Json.Obj().put("ok", false)
                    .put("error", "no capture path worked")
                    .put("hint", "SurfaceControl, MediaProjection and screencap all failed; "
                            + "check READ_FRAME_BUFFER / CAPTURE_VIDEO_OUTPUT and the screen state")
                    .toString());
            return;
        }
        String format = r.param("format", "png").toLowerCase(Locale.US);
        double scale = 1.0;
        try { scale = Double.parseDouble(r.param("scale", "1")); } catch (Exception ignored) { }
        int quality = (int) r.paramLong("quality", 85);
        if (scale > 0 && scale != 1.0) {
            Bitmap scaled = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, (int) (bmp.getWidth() * scale)),
                    Math.max(1, (int) (bmp.getHeight() * scale)), true);
            if (scaled != bmp) bmp.recycle();
            bmp = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String type;
        if (format.equals("jpeg") || format.equals("jpg")) {
            bmp.compress(Bitmap.CompressFormat.JPEG, Math.max(1, Math.min(100, quality)), out);
            type = "image/jpeg";
        } else {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            type = "image/png";
        }
        s.header("X-ADK-Capture", how.toString());
        s.header("X-ADK-Size", bmp.getWidth() + "x" + bmp.getHeight());
        bmp.recycle();
        s.bytes(200, type, out.toByteArray());
    }

    private void screenrecord(HttpServer.Request r, HttpServer.Response s) throws Exception {
        int seconds = (int) Math.max(1, Math.min(180, r.paramLong("seconds", 10)));
        String size = r.param("size", "");
        String bitrate = r.param("bitrate", "");
        File out = new File("/data/local/tmp/adk-rec-" + System.currentTimeMillis() + ".mp4");
        StringBuilder cmd = new StringBuilder("screenrecord --time-limit ").append(seconds);
        if (!size.isEmpty()) cmd.append(" --size ").append(size);
        if (!bitrate.isEmpty()) cmd.append(" --bitrate ").append(bitrate);
        cmd.append(' ').append(Shell.quote(out.getAbsolutePath()));
        Shell.Result res = Shell.sh(cmd.toString(), (seconds + 20) * 1000L);
        if (!out.isFile() || out.length() == 0) {
            s.json(500, new Json.Obj().put("ok", false)
                    .put("error", "screenrecord produced no file")
                    .put("output", res.combined().trim()).toString());
            return;
        }
        byte[] data = readFile(out, 512 * 1024 * 1024);
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        s.bytes(200, "video/mp4", data);
    }

    private void input(HttpServer.Request r, HttpServer.Response s) throws Exception {
        Map<String, Object> body = r.json();
        String action = Json.s(body, "action", r.param("action", "")).toLowerCase(Locale.US);
        long dur = Json.i(body, "duration",
                Json.i(body, "duration_ms", r.paramLong("duration", r.paramLong("duration_ms", 300))));
        int x = (int) Json.i(body, "x", r.paramLong("x", -1));
        int y = (int) Json.i(body, "y", r.paramLong("y", -1));
        int x1 = (int) Json.i(body, "x1", r.paramLong("x1", -1));
        int y1 = (int) Json.i(body, "y1", r.paramLong("y1", -1));
        int x2 = (int) Json.i(body, "x2", r.paramLong("x2", -1));
        int y2 = (int) Json.i(body, "y2", r.paramLong("y2", -1));

        String what;
        String problem = null;
        switch (action) {
            case "tap":
            case "click":
                what = "tap";
                problem = Injector.tap(ctx, x, y);
                break;
            case "longpress":
            case "long_click":
                what = "longpress";
                problem = Injector.longPress(ctx, x, y, (int) (dur <= 0 ? 800 : dur));
                break;
            case "swipe":
                what = "swipe";
                problem = Injector.swipe(ctx, x1, y1, x2, y2, (int) (dur <= 0 ? 300 : dur));
                break;
            case "drag":
            case "draganddrop":
                what = "drag";
                problem = Injector.swipe(ctx, x1, y1, x2, y2, (int) (dur <= 0 ? 500 : dur));
                break;
            case "text":
            case "type":
                what = "text";
                problem = Injector.text(ctx, Json.s(body, "text", r.param("text", "")));
                break;
            case "key":
            case "keyevent": {
                what = "key";
                String key = Json.s(body, "key", r.param("key", Json.s(body, "keycode", r.param("keycode", ""))));
                int code = keyCode(key);
                if (code <= 0) {
                    s.json(400, new Json.Obj().put("ok", false)
                            .put("error", "unknown key '" + key + "'")
                            .put("hint", "KEYCODE_HOME, KEYCODE_BACK, 3, 4 ...").toString());
                    return;
                }
                problem = Injector.key(ctx, code);
                break;
            }
            case "roll":
                what = "roll";
                problem = Injector.roll(ctx, (int) Json.i(body, "dx", r.paramLong("dx", 0)),
                        (int) Json.i(body, "dy", r.paramLong("dy", 0)));
                break;
            default:
                s.json(400, new Json.Obj().put("ok", false)
                        .put("error", "unknown action '" + action + "'")
                        .put("supported", "tap, longpress, swipe, drag, text, key, roll").toString());
                return;
        }

        // Fallback for the rare case where injection is refused: the `input` binary
        // (dies with SIGSYS under the app's seccomp filter on most builds, so the
        // failure is reported honestly rather than hidden).
        String fallback = null;
        if (problem != null && !problem.isEmpty()) {
            String[] argv = shellInputArgv(action, x, y, x1, y1, x2, y2, (int) dur, body, r);
            if (argv != null) {
                Shell.Result res = Shell.exec(argv, 30_000);
                fallback = "input exit=" + res.exit + " " + res.combined().trim();
            }
        }
        boolean ok = problem == null || problem.isEmpty();
        Json.Obj o = new Json.Obj()
                .put("ok", ok)
                .put("action", what)
                .put("method", ok ? "InputManager.injectInputEvent" : "none")
                .put("error", ok ? null : problem);
        if (fallback != null) o.put("fallback", fallback);
        s.json(ok ? 200 : 500, o.toString());
    }

    /** `input <action> ...` equivalent, used only as a fallback. */
    private String[] shellInputArgv(String action, int x, int y, int x1, int y1, int x2, int y2,
                                    int dur, Map<String, Object> body, HttpServer.Request r) {
        switch (action) {
            case "tap":
            case "click":
                return new String[]{"input", "tap", String.valueOf(x), String.valueOf(y)};
            case "longpress":
            case "long_click":
                return new String[]{"input", "swipe", String.valueOf(x), String.valueOf(y),
                        String.valueOf(x), String.valueOf(y), String.valueOf(dur <= 0 ? 800 : dur)};
            case "swipe":
            case "drag":
            case "draganddrop":
                return new String[]{"input", "swipe", String.valueOf(x1), String.valueOf(y1),
                        String.valueOf(x2), String.valueOf(y2), String.valueOf(dur <= 0 ? 300 : dur)};
            case "text":
            case "type":
                return new String[]{"input", "text",
                        Json.s(body, "text", r.param("text", "")).replace("\n", " ").replace(" ", "%s")};
            case "key":
            case "keyevent":
                return new String[]{"input", "keyevent",
                        Json.s(body, "key", r.param("key", ""))};
            default:
                return null;
        }
    }

    /** Accepts KEYCODE_ names or numeric codes. */
    private static int keyCode(String key) {
        if (key == null || key.isEmpty()) return -1;
        String k = key.trim().toUpperCase(Locale.US);
        try {
            return Integer.parseInt(k);
        } catch (NumberFormatException ignored) { }
        try {
            java.lang.reflect.Field f = android.view.KeyEvent.class.getField(k);
            return f.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }
    private void ui(HttpServer.Request r, HttpServer.Response s) throws Exception {
        // Preferred path: the app's own accessibility service. `uiautomator dump`
        // cannot work here (SIGSYS under the app's seccomp filter, and it wants to
        // create its dalvik-cache in /data/local/tmp, which system_app cannot touch).
        AdkAccessibilityService acc = AdkAccessibilityService.get();
        if (acc == null) AdkAccessibilityService.ensureEnabled(ctx);
        acc = AdkAccessibilityService.get();
        if (acc != null) {
            List<String> nodes = acc.nodes();
            if (r.paramBool("xml", false) || "xml".equalsIgnoreCase(r.param("format", ""))) {
                s.text(200, nodesToXml(nodes));
            } else {
                s.json(200, new Json.Obj().put("ok", true).put("source", "accessibility")
                        .put("count", nodes.size()).putRawArray("nodes", nodes).toString());
            }
            return;
        }
        File xml = new File(ctx.getCacheDir(), "adk-ui.xml");
        //noinspection ResultOfMethodCallIgnored
        xml.delete();
        Shell.Result dump = Shell.sh("uiautomator dump " + Shell.quote(xml.getAbsolutePath()), 30_000);
        if (!xml.isFile()) {
            s.json(500, new Json.Obj().put("ok", false)
                    .put("error", "uiautomator dump failed")
                    .put("output", dump.combined().trim())
                    .put("hint", "the screen may be off (POST /input {\"action\":\"key\",\"key\":\"KEYCODE_WAKEUP\"}), "
                            + "or uiautomator is blocked by seccomp for this app").toString());
            return;
        }
        String content = new String(readFile(xml, 16 * 1024 * 1024), "UTF-8");
        //noinspection ResultOfMethodCallIgnored
        xml.delete();
        if (r.paramBool("xml", false) || "xml".equalsIgnoreCase(r.param("format", ""))) {
            s.text(200, content);
            return;
        }
        s.json(200, new Json.Obj().put("ok", true)
                .putRawArray("nodes", parseUiNodes(content)).toString());
    }


    /** Same shape as `uiautomator dump`, for tools that expect XML. */
    private static String nodesToXml(List<String> nodes) {
        StringBuilder b = new StringBuilder("<hierarchy source=\"accessibility\">\n");
        for (String raw : nodes) {
            Map<String, Object> m = Json.parseObj(raw);
            b.append("  <node text=\"").append(xml(Json.s(m, "text", "")))
                    .append("\" resource-id=\"").append(xml(Json.s(m, "id", "")))
                    .append("\" class=\"").append(xml(Json.s(m, "class", "")))
                    .append("\" package=\"").append(xml(Json.s(m, "package", "")))
                    .append("\" content-desc=\"").append(xml(Json.s(m, "desc", "")))
                    .append("\" clickable=\"").append(Json.b(m, "clickable", false))
                    .append("\" enabled=\"").append(Json.b(m, "enabled", false))
                    .append("\" bounds=\"").append(xml(Json.s(m, "bounds", "")))
                    .append("\" />\n");
        }
        return b.append("</hierarchy>\n").toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private List<String> parseUiNodes(String xml) {
        List<String> nodes = new ArrayList<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
            collect(doc.getDocumentElement(), nodes);
        } catch (Throwable t) {
            Log.w(TAG, "ui parse failed: " + t);
        }
        return nodes;
    }

    private void collect(Element el, List<String> out) {
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String text = e.getAttribute("text");
            String desc = e.getAttribute("content-desc");
            String cls = e.getAttribute("class");
            if (!text.isEmpty() || !desc.isEmpty() || e.hasAttribute("clickable")) {
                out.add(new Json.Obj()
                        .put("text", text)
                        .put("desc", desc)
                        .put("id", e.getAttribute("resource-id"))
                        .put("class", cls)
                        .put("package", e.getAttribute("package"))
                        .put("bounds", e.getAttribute("bounds"))
                        .put("clickable", "true".equals(e.getAttribute("clickable")))
                        .put("enabled", "true".equals(e.getAttribute("enabled")))
                        .put("focused", "true".equals(e.getAttribute("focused")))
                        .put("checkable", "true".equals(e.getAttribute("checkable")))
                        .put("checked", "true".equals(e.getAttribute("checked")))
                        .toString());
            }
            collect(e, out);
        }
    }

    private void waitFor(HttpServer.Request r, HttpServer.Response s) throws Exception {
        String text = r.param("text", "");
        String id = r.param("id", "");
        String desc = r.param("desc", "");
        long timeout = r.paramLong("timeout_ms", 20_000);
        long interval = Math.max(200, r.paramLong("interval_ms", 700));
        boolean tap = r.paramBool("tap", false);
        if (text.isEmpty() && id.isEmpty() && desc.isEmpty()) {
            s.json(400, new Json.Obj().put("ok", false).put("error", "need text=, id= or desc=").toString());
            return;
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> match = null;
        AdkAccessibilityService acc = AdkAccessibilityService.get();
        if (acc != null) {
            while (System.currentTimeMillis() - t0 < timeout) {
                for (String raw : acc.nodes()) {
                    Map<String, Object> m = Json.parseObj(raw);
                    String t = Json.s(m, "text", "");
                    String rid = Json.s(m, "id", "");
                    String d = Json.s(m, "desc", "");
                    if ((!text.isEmpty() && (t.contains(text) || d.contains(text)))
                            || (!id.isEmpty() && rid.contains(id))
                            || (!desc.isEmpty() && d.contains(desc))) {
                        match = m;
                        break;
                    }
                }
                if (match != null) break;
                Thread.sleep(interval);
            }
            if (match != null && tap) {
                int[] c = acc.tapMatch(text, id, desc);
                if (c != null) match.put("tapped", c[0] + "," + c[1]);
            }
            Json.Obj ao = new Json.Obj().put("ok", match != null).put("found", match != null)
                    .put("source", "accessibility")
                    .put("elapsed_ms", System.currentTimeMillis() - t0);
            if (match != null) {
                ao.putRaw("node", new Json.Obj()
                        .put("text", Json.s(match, "text", ""))
                        .put("id", Json.s(match, "id", ""))
                        .put("desc", Json.s(match, "desc", ""))
                        .put("bounds", Json.s(match, "bounds", ""))
                        .put("tapped", Json.s(match, "tapped", "")).toString());
            }
            s.json(match != null ? 200 : 408, ao.toString());
            return;
        }
        while (System.currentTimeMillis() - t0 < timeout) {
            // The app's own cache: system_app is denied /data/local/tmp (shell_data_file),
        // which is what broke `uiautomator dump` on the rig.
        File xml = new File(ctx.getCacheDir(), "adk-ui.xml");
            //noinspection ResultOfMethodCallIgnored
            xml.delete();
            Shell.sh("uiautomator dump " + Shell.quote(xml.getAbsolutePath()), 25_000);
            if (xml.isFile()) {
                String content = new String(readFile(xml, 16 * 1024 * 1024), "UTF-8");
                //noinspection ResultOfMethodCallIgnored
                xml.delete();
                for (String raw : parseUiNodes(content)) {
                    Map<String, Object> m = Json.parseObj(raw);
                    String t = Json.s(m, "text", "");
                    String rid = Json.s(m, "id", "");
                    String d = Json.s(m, "desc", "");
                    boolean hit = (!text.isEmpty() && (t.contains(text) || d.contains(text)))
                            || (!id.isEmpty() && rid.contains(id))
                            || (!desc.isEmpty() && d.contains(desc));
                    if (hit) {
                        match = m;
                        break;
                    }
                }
            }
            if (match != null) break;
            Thread.sleep(interval);
        }
        Json.Obj o = new Json.Obj().put("ok", match != null).put("found", match != null)
                .put("elapsed_ms", System.currentTimeMillis() - t0);
        if (match != null) {
            o.putRaw("node", new Json.Obj()
                    .put("text", Json.s(match, "text", ""))
                    .put("id", Json.s(match, "id", ""))
                    .put("desc", Json.s(match, "desc", ""))
                    .put("bounds", Json.s(match, "bounds", "")).toString());
            if (tap) {
                int[] c = center(Json.s(match, "bounds", ""));
                if (c != null) {
                    Shell.exec(new String[]{"input", "tap", String.valueOf(c[0]), String.valueOf(c[1])}, 15_000);
                    o.put("tapped", c[0] + "," + c[1]);
                }
            }
        }
        s.json(match != null ? 200 : 408, o.toString());
    }

    private static int[] center(String bounds) {
        Matcher m = Pattern.compile("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").matcher(bounds == null ? "" : bounds);
        if (!m.find()) return null;
        int x1 = Integer.parseInt(m.group(1)), y1 = Integer.parseInt(m.group(2));
        int x2 = Integer.parseInt(m.group(3)), y2 = Integer.parseInt(m.group(4));
        return new int[]{(x1 + x2) / 2, (y1 + y2) / 2};
    }

    private void foreground(HttpServer.Request r, HttpServer.Response s) {
        Json.Obj o = foregroundJson();
        o.put("ok", true);
        s.json(200, o.toString());
    }

    /* ---------------------------------------------------------------- settings */

    private void settingsGet(HttpServer.Request r, HttpServer.Response s) {
        String ns = r.param("ns", "global").toLowerCase(Locale.US);
        String key = r.param("key", "");
        if (r.paramBool("all", false) || key.isEmpty()) {
            Shell.Result res = Shell.sh("settings list " + ns, 20_000);
            Map<String, String> map = new LinkedHashMap<>();
            for (String line : res.out.split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) map.put(line.substring(0, eq), line.substring(eq + 1));
            }
            Json.Obj items = new Json.Obj();
            for (Map.Entry<String, String> e : map.entrySet()) items.put(e.getKey(), e.getValue());
            s.json(200, new Json.Obj().put("ok", true).put("ns", ns).put("count", map.size())
                    .putRaw("values", items.toString()).toString());
            return;
        }
        String value = null;
        try {
            if (ns.equals("system")) value = Settings.System.getString(ctx.getContentResolver(), key);
            else if (ns.equals("secure")) value = Settings.Secure.getString(ctx.getContentResolver(), key);
            else value = Settings.Global.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            Log.w(TAG, "settings read failed: " + t);
        }
        if (value == null) {
            Shell.Result res = Shell.sh("settings get " + ns + " " + Shell.quote(key), 10_000);
            String out = res.out.trim();
            if (!out.isEmpty() && !out.equals("null")) value = out;
        }
        s.json(200, new Json.Obj().put("ok", true).put("ns", ns).put("key", key).put("value", value).toString());
    }

    private void settingsSet(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String ns = Json.s(body, "ns", r.param("ns", "global")).toLowerCase(Locale.US);
        String key = Json.s(body, "key", r.param("key", ""));
        String value = Json.s(body, "value", r.param("value", null));
        boolean delete = Json.b(body, "delete", r.paramBool("delete", false));
        if (key.isEmpty()) {
            s.json(400, new Json.Obj().put("ok", false).put("error", "key required").toString());
            return;
        }
        String err = null;
        try {
            if (delete) value = null;
            if (ns.equals("system")) Settings.System.putString(ctx.getContentResolver(), key, value);
            else if (ns.equals("secure")) Settings.Secure.putString(ctx.getContentResolver(), key, value);
            else Settings.Global.putString(ctx.getContentResolver(), key, value);
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
        String verify = null;
        try {
            if (ns.equals("system")) verify = Settings.System.getString(ctx.getContentResolver(), key);
            else if (ns.equals("secure")) verify = Settings.Secure.getString(ctx.getContentResolver(), key);
            else verify = Settings.Global.getString(ctx.getContentResolver(), key);
        } catch (Throwable ignored) { }
        if (err != null) {
            Shell.Result res = Shell.sh("settings put " + ns + " " + Shell.quote(key) + " " + Shell.quote(value), 10_000);
            s.json(res.exit == 0 ? 200 : 500, new Json.Obj().put("ok", res.exit == 0)
                    .put("error", err).put("fallback_output", res.combined().trim()).toString());
            return;
        }
        s.json(200, new Json.Obj().put("ok", true).put("ns", ns).put("key", key)
                .put("value", delete ? null : value).put("verified", verify).toString());
    }

    private void propGet(HttpServer.Request r, HttpServer.Response s) {
        String key = r.param("key", "");
        Shell.Result res = key.isEmpty()
                ? Shell.sh("getprop", 20_000)
                : Shell.sh("getprop " + Shell.quote(key), 10_000);
        if (!key.isEmpty()) {
            s.json(200, new Json.Obj().put("ok", true).put("key", key).put("value", res.out.trim()).toString());
        } else {
            s.text(200, res.out);
        }
    }

    private void propSet(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String key = Json.s(body, "key", r.param("key", ""));
        String value = Json.s(body, "value", r.param("value", ""));
        if (key.isEmpty()) {
            s.json(400, new Json.Obj().put("ok", false).put("error", "key required").toString());
            return;
        }
        Shell.Result res = Shell.sh("setprop " + Shell.quote(key) + " " + Shell.quote(value), 10_000);
        String after = Shell.sh("getprop " + Shell.quote(key), 10_000).out.trim();
        boolean ok = res.exit == 0 && (after.equals(value) || res.combined().trim().isEmpty());
        s.json(ok ? 200 : 500, new Json.Obj().put("ok", ok).put("key", key).put("value", after)
                .put("output", res.combined().trim())
                .put("note", "SELinux-protected properties (e.g. service.adb.tcp.port) are refused for apps; use /exec if you have a host-side adb")
                .toString());
    }

    /* ------------------------------------------------------------------- files */

    private void fileGet(HttpServer.Request r, HttpServer.Response s) throws Exception {
        String path = r.param("path", "");
        if (path.isEmpty()) {
            s.json(400, new Json.Obj().put("ok", false).put("error", "?path= required").toString());
            return;
        }
        File f = new File(path);
        if (!f.isFile()) {
            s.json(404, new Json.Obj().put("ok", false).put("error", "not a file: " + path).toString());
            return;
        }
        boolean text = r.paramBool("text", false);
        long max = r.paramLong("max_bytes", 256L * 1024 * 1024);
        if (f.length() > max) {
            s.json(413, new Json.Obj().put("ok", false).put("error", "file larger than max_bytes")
                    .put("size", f.length()).toString());
            return;
        }
        byte[] data = readFile(f, max);
        String ct = text ? "text/plain; charset=utf-8" : contentType(path);
        s.header("Content-Disposition", "attachment; filename=\"" + f.getName() + "\"");
        s.bytes(200, ct, data);
    }

    private void filePut(HttpServer.Request r, HttpServer.Response s) throws Exception {
        String path = r.param("path", "");
        if (path.isEmpty()) {
            s.json(400, new Json.Obj().put("ok", false).put("error", "?path= required").toString());
            return;
        }
        File f = new File(path);
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        boolean append = r.paramBool("append", false);
        long n;
        String ct = r.header("content-type");
        if (ct != null && ct.toLowerCase(Locale.US).contains("multipart/form-data")) {
            File raw = new File(ctx.getCacheDir(), "upload-" + System.currentTimeMillis() + ".raw");
            r.bodyToFile(raw);
            n = Multipart.firstFile(raw, ct, f);
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        } else {
            OutputStream out = new FileOutputStream(f, append);
            try {
                n = copy(r, out);
            } finally {
                out.close();
            }
        }
        s.json(200, new Json.Obj().put("ok", true).put("path", f.getAbsolutePath())
                .put("bytes", n).put("size", f.length()).toString());
    }

    private void ls(HttpServer.Request r, HttpServer.Response s) {
        String path = r.param("path", "/sdcard");
        File dir = new File(path);
        if (!dir.isDirectory()) {
            s.json(404, new Json.Obj().put("ok", false).put("error", "not a directory: " + path).toString());
            return;
        }
        File[] files = dir.listFiles();
        List<String> raw = new ArrayList<>();
        if (files != null) {
            Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
            for (File f : files) {
                raw.add(new Json.Obj()
                        .put("name", f.getName())
                        .put("path", f.getAbsolutePath())
                        .put("dir", f.isDirectory())
                        .put("size", f.length())
                        .put("mtime", f.lastModified())
                        .put("readable", f.canRead())
                        .put("writable", f.canWrite())
                        .toString());
            }
        }
        s.json(200, new Json.Obj().put("ok", true).put("path", dir.getAbsolutePath())
                .put("count", raw.size()).putRawArray("entries", raw).toString());
    }

    /* ---------------------------------------------------------- dumpsys / intents */

    private void dumpsys(HttpServer.Request r, HttpServer.Response s) {
        String service = r.param("service", "activity");
        String grep = r.param("grep", "");
        int lines = (int) r.paramLong("lines", 0);
        String cmd = "dumpsys " + Shell.quote(service);
        if (!grep.isEmpty()) cmd += " | grep -E " + Shell.quote(grep);
        if (lines > 0) cmd += " | head -" + lines;
        Shell.Result res = Shell.sh(cmd, 60_000);
        s.text(200, res.out + (res.err.isEmpty() ? "" : "\n[stderr] " + res.err));
    }

    private void intent(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String kind = Json.s(body, "action", r.param("action", "start")).toLowerCase(Locale.US);
        List<String> argv = new ArrayList<>();
        argv.add("am");
        switch (kind) {
            case "start": argv.add("start"); break;
            case "startservice": argv.add("startservice"); break;
            case "stopservice": argv.add("stopservice"); break;
            case "broadcast": argv.add("broadcast"); break;
            case "force-stop":
            case "forcestop":
                argv.add("force-stop");
                argv.add(Json.s(body, "package", r.param("package", "")));
                Shell.Result res = Shell.exec(argv.toArray(new String[0]), 30_000);
                s.json(res.exit == 0 ? 200 : 500, new Json.Obj().put("ok", res.exit == 0)
                        .put("output", res.combined().trim()).toString());
                return;
            default:
                s.json(400, new Json.Obj().put("ok", false)
                        .put("error", "unknown action; use start, startservice, stopservice, broadcast, force-stop").toString());
                return;
        }
        String component = Json.s(body, "component", r.param("component", ""));
        String intentAction = Json.s(body, "intent_action", r.param("intent_action", ""));
        String data = Json.s(body, "data", r.param("data", ""));
        String type = Json.s(body, "type", r.param("type", ""));
        if (!component.isEmpty()) { argv.add("-n"); argv.add(component); }
        if (!intentAction.isEmpty()) { argv.add("-a"); argv.add(intentAction); }
        if (!data.isEmpty()) { argv.add("-d"); argv.add(data); }
        if (!type.isEmpty()) { argv.add("-t"); argv.add(type); }
        for (Object flag : Json.asArr(body.get("flags"))) {
            argv.add("-f"); argv.add(String.valueOf(flag).replace(".0", ""));
        }
        addExtras(argv, Json.asObj(body.get("extras")), "--es");
        addExtras(argv, Json.asObj(body.get("extras_int")), "--ei");
        addExtras(argv, Json.asObj(body.get("extras_bool")), "--ez");
        long user = Json.i(body, "user", r.paramLong("user", -1));
        if (user >= 0) { argv.add("--user"); argv.add(String.valueOf(user)); }
        Shell.Result res = Shell.exec(argv.toArray(new String[0]), 60_000);
        s.json(res.exit == 0 ? 200 : 500, new Json.Obj()
                .put("ok", res.exit == 0)
                .put("argv", String.join(" ", argv))
                .put("exit", res.exit)
                .put("output", res.combined().trim())
                .toString());
    }

    private static void addExtras(List<String> argv, Map<String, Object> extras, String flag) {
        for (Map.Entry<String, Object> e : extras.entrySet()) {
            Object v = e.getValue();
            String sv;
            if (v instanceof Boolean) sv = String.valueOf(v);
            else if (v instanceof Double) {
                double d = (Double) v;
                sv = (d == Math.rint(d)) ? String.valueOf((long) d) : String.valueOf(d);
            } else sv = String.valueOf(v);
            argv.add(flag);
            argv.add(e.getKey());
            argv.add(sv);
        }
    }

    /* ------------------------------------------------------------------- shells */

    private void shellOpen(HttpServer.Request r, HttpServer.Response s) throws Exception {
        ShellSession sh = ShellSession.open();
        s.json(200, new Json.Obj().put("ok", true).put("session_id", sh.id())
                .put("note", "POST /shell/write?sid=..&data=.. then GET /shell/read?sid=..&offset=..").toString());
    }

    private void shellWrite(HttpServer.Request r, HttpServer.Response s) throws Exception {
        Map<String, Object> body = r.json();
        String sid = Json.s(body, "sid", r.param("sid", ""));
        ShellSession sh = ShellSession.get(sid);
        if (sh == null) {
            s.json(404, new Json.Obj().put("ok", false).put("error", "no such session").toString());
            return;
        }
        String data = Json.s(body, "data", r.param("data", null));
        long n;
        if (data != null) {
            n = sh.write(data);
        } else {
            n = sh.writeRaw(r.body());
        }
        s.json(200, new Json.Obj().put("ok", true).put("sid", sid).put("bytes", n)
                .put("offset", sh.offset()).toString());
    }

    private void shellRead(HttpServer.Request r, HttpServer.Response s) {
        String sid = r.param("sid", "");
        ShellSession sh = ShellSession.get(sid);
        if (sh == null) {
            s.json(404, new Json.Obj().put("ok", false).put("error", "no such session").toString());
            return;
        }
        long offset = r.paramLong("offset", 0);
        long waitMs = r.paramLong("wait_ms", 0);
        String data = sh.read(offset, Math.min(waitMs, 60_000));
        s.json(200, new Json.Obj().put("ok", true).put("sid", sid)
                .put("data", data)
                .put("offset", sh.offset())
                .put("alive", sh.alive())
                .put("exit", sh.exitCode())
                .toString());
    }

    private void shellClose(HttpServer.Request r, HttpServer.Response s) {
        String sid = r.param("sid", "");
        boolean ok = ShellSession.close(sid);
        s.json(ok ? 200 : 404, new Json.Obj().put("ok", ok).toString());
    }

    private void shellList(HttpServer.Request r, HttpServer.Response s) {
        List<String> raw = new ArrayList<>();
        for (ShellSession sh : ShellSession.all()) {
            raw.add(new Json.Obj().put("sid", sh.id()).put("alive", sh.alive())
                    .put("offset", sh.offset()).put("exit", sh.exitCode()).toString());
        }
        s.json(200, new Json.Obj().put("ok", true).putRawArray("sessions", raw).toString());
    }

    /* ----------------------------------------------------------------- forwards */

    private void forwardAdd(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String bind = Json.s(body, "bind", r.param("bind", "0.0.0.0"));
        int localPort = (int) Json.i(body, "local_port", r.paramLong("local_port", -1));
        String host = Json.s(body, "host", r.param("host", "127.0.0.1"));
        int port = (int) Json.i(body, "port", r.paramLong("port", -1));
        if (localPort <= 0 || port <= 0) {
            s.json(400, new Json.Obj().put("ok", false)
                    .put("error", "local_port and port are required")
                    .put("example", "{\"bind\":\"127.0.0.1\",\"local_port\":19000,\"host\":\"10.0.0.5\",\"port\":3000}").toString());
            return;
        }
        try {
            Forwarder.Rule rule = Forwarder.add(bind, localPort, host, port);
            s.json(200, new Json.Obj().put("ok", true).put("id", rule.id)
                    .put("bind", rule.bind).put("local_port", rule.localPort)
                    .put("host", rule.host).put("port", rule.port).toString());
        } catch (Throwable t) {
            s.json(500, new Json.Obj().put("ok", false).put("error", String.valueOf(t)).toString());
        }
    }

    private void forwardList(HttpServer.Request r, HttpServer.Response s) {
        List<String> raw = new ArrayList<>();
        for (Forwarder.Rule rule : Forwarder.list()) {
            raw.add(new Json.Obj().put("id", rule.id).put("bind", rule.bind)
                    .put("local_port", rule.localPort).put("host", rule.host).put("port", rule.port)
                    .put("connections", rule.connections).put("last_error", rule.lastError)
                    .put("created", rule.created).toString());
        }
        s.json(200, new Json.Obj().put("ok", true).putRawArray("forwards", raw).toString());
    }

    private void forwardRemove(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String id = Json.s(body, "id", r.param("id", ""));
        boolean ok = Forwarder.remove(id);
        s.json(ok ? 200 : 404, new Json.Obj().put("ok", ok).toString());
    }

    /* ------------------------------------------------------- self update / admin */

    private void selfUpdate(HttpServer.Request r, HttpServer.Response s) throws Exception {
        File apk = new File(ctx.getCacheDir(), "adk-selfupdate.apk");
        long n = receiveApk(r, apk);
        if (n <= 0) {
            s.json(400, new Json.Obj().put("ok", false).put("error", "empty body").toString());
            return;
        }
        String pkg = packageNameOf(apk);
        if (pkg == null || !pkg.equals(ctx.getPackageName())) {
            s.json(400, new Json.Obj().put("ok", false)
                    .put("error", "that APK is not " + ctx.getPackageName())
                    .put("found", pkg)
                    .put("hint", "use POST /install for other apps").toString());
            return;
        }
        selfUpdateAsync(apk, s);
    }

    /**
     * Install a new build of ADK over itself.
     *
     * The response is sent *before* the install, because replacing a running
     * package kills this process: a synchronous install would never answer the
     * client. The last-known-good APK is stored and a rollback alarm armed, so a
     * build that cannot serve requests is reverted automatically.
     */
    private void selfUpdateAsync(final File apk, HttpServer.Response s) {
        final String backup = Rollback.backupCurrent(ctx);
        Rollback.schedule(ctx, 180_000L, backup);
        s.json(200, new Json.Obj()
                .put("ok", true)
                .put("status", "installing")
                .put("bytes", apk.length())
                .put("last_good", backup)
                .put("rollback_in_s", 180)
                .put("next", "the channel drops while the package is replaced; then GET /health "
                        + "must show the new version and you call POST /selfupdate/confirm")
                .toString());
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(700); } catch (InterruptedException ignored) { }
                String result = installApk(apk, true, true, true, 0);
                Log.i(TAG, "self-update install: " + result);
                // the install kills this process; the alarm brings the new build back
                Rollback.scheduleRestart(ctx, 15_000L);
                //noinspection ResultOfMethodCallIgnored
                apk.delete();
                if (!result.startsWith("Success")) {
                    // nothing changed, so do not leave a rollback armed
                    Rollback.confirm(ctx);
                }
            }
        }, "adk-selfupdate").start();
    }

    private void selfConfirm(HttpServer.Request r, HttpServer.Response s) {
        Rollback.confirm(ctx);
        PackageInfo pi = info();
        s.json(200, new Json.Obj().put("ok", true).put("version", pi == null ? "?" : pi.versionName)
                .put("version_code", pi == null ? -1 : pi.versionCode)
                .put("rollback_pending", false).toString());
    }

    private void selfRollback(HttpServer.Request r, HttpServer.Response s) {
        String out = Rollback.rollbackNow(ctx);
        s.json(out.startsWith("Success") ? 200 : 500, new Json.Obj()
                .put("ok", out.startsWith("Success")).put("output", out).toString());
    }

    private void serviceRestart(HttpServer.Request r, HttpServer.Response s) {
        s.json(200, new Json.Obj().put("ok", true).put("note", "restarting the channel now").toString());
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                AdkService.restartChannel(ctx);
            }
        }).start();
    }

    private void serviceStop(HttpServer.Request r, HttpServer.Response s) {
        s.json(200, new Json.Obj().put("ok", true).put("note", "channel stopping; the app stays installed").toString());
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(300); } catch (InterruptedException ignored) { }
                AdkService.stopChannel(ctx);
            }
        }).start();
    }

    private void reboot(HttpServer.Request r, HttpServer.Response s) {
        Map<String, Object> body = r.json();
        String mode = Json.s(body, "mode", r.param("mode", "")).trim();
        s.json(200, new Json.Obj().put("ok", true).put("mode", mode.isEmpty() ? "normal" : mode)
                .put("note", "rebooting; the channel comes back on boot if autostart is on").toString());
        final String m = mode;
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(500); } catch (InterruptedException ignored) { }
                Shell.sh(m.isEmpty() ? "reboot" : "reboot " + m, 15_000);
            }
        }).start();
    }

    /* ------------------------------------------------------------------ helpers */

    private PackageInfo info() {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private PackageInfo packageInfo(String pkg) {
        try {
            return ctx.getPackageManager().getPackageInfo(pkg, 0);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long copy(HttpServer.Request r, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = r.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
        }
        return total;
    }

    /**
     * Read a file up to {@code max} bytes.
     *
     * Streams rather than trusting File.length(): procfs and sysfs files report 0
     * even when they have content (/proc/version was coming back empty).
     */
    static byte[] readFile(File f, long max) throws IOException {
        long hint = f.length();
        ByteArrayOutputStream out = new ByteArrayOutputStream(
                (int) Math.max(64, Math.min(hint, 1 << 20)));
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while (total < max && (n = in.read(buf, 0, (int) Math.min(buf.length, max - total))) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static String contentType(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".txt") || p.endsWith(".log") || p.endsWith(".xml") || p.endsWith(".sh")) {
            return "text/plain; charset=utf-8";
        }
        if (p.endsWith(".apk")) return "application/vnd.android.package-archive";
        return "application/octet-stream";
    }

    private static String head(String s, int lines) {
        String[] parts = s.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(lines, parts.length); i++) b.append(parts[i]).append('\n');
        return b.toString().trim();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            if (!line.trim().isEmpty()) return line.trim();
        }
        return "";
    }

    static String iso(long millis) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(millis));
    }
}

package com.lukeet.agenticdevkit;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A small HTTP/1.1 server on raw sockets: no dependencies, works on API 28,
 * streams when asked to (logcat follow, file downloads) and buffers otherwise.
 */
public final class HttpServer {

    private static final String TAG = "ADK";
    private static final int MAX_BUFFERED_BODY = 64 * 1024 * 1024;

    public interface Handler {
        void handle(Request req, Response res) throws Exception;
    }

    private final String bind;
    private final int port;
    private final Map<String, Handler> routes = new LinkedHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket server;
    private volatile boolean running;
    private final AtomicLong served = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private volatile String lastError = "";

    public HttpServer(String bind, int port) {
        this.bind = bind;
        this.port = port;
    }

    public void route(String method, String path, Handler h) {
        routes.put(method.toUpperCase(Locale.US) + " " + path, h);
    }

    public long served() { return served.get(); }
    public long rejected() { return rejected.get(); }
    public String lastError() { return lastError; }
    public int port() { return port; }
    /** Whether the channel is actually accepting: false while it rebinds or after the accept thread died. */
    public boolean running() { return running && listening; }

    private volatile boolean listening;

    private ServerSocket bindSocket() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(bind, port), 64);
        return s;
    }

    public void start() throws IOException {
        server = bindSocket();
        running = true;
        listening = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "HTTP server listening on " + bind + ":" + port);
                long backoff = 500;
                while (running) {
                    final Socket s;
                    try {
                        s = server.accept();
                        backoff = 500;
                    } catch (IOException e) {
                        if (!running) break;
                        // Seen on the MG4: accept() failed once around a Wi-Fi change and the thread
                        // used to exit here, leaving the app "RUNNING" with nothing on the port.
                        lastError = String.valueOf(e);
                        listening = false;
                        Log.w(TAG, "accept failed (" + e + "); rebinding in " + backoff + "ms");
                        try { server.close(); } catch (IOException ignored) { }
                        try { Thread.sleep(backoff); } catch (InterruptedException ie) { break; }
                        backoff = Math.min(backoff * 2, 5_000);
                        try {
                            server = bindSocket();
                            listening = true;
                            Log.i(TAG, "HTTP server rebound on " + bind + ":" + port);
                        } catch (IOException be) {
                            lastError = String.valueOf(be);
                            Log.w(TAG, "rebind failed: " + be);
                        }
                        continue;
                    } catch (Throwable fatal) {
                        lastError = String.valueOf(fatal);
                        Log.e(TAG, "accept thread died", fatal);
                        break;
                    }
                    try {
                        pool.execute(new Runnable() {
                            @Override public void run() {
                                handleConnection(s);
                            }
                        });
                    } catch (Throwable rejectedExec) {
                        rejected.incrementAndGet();
                        try { s.close(); } catch (IOException ignored) { }
                    }
                }
                listening = false;
                Log.i(TAG, "HTTP server stopped");
            }
        }, "adk-http-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    /* ------------------------------------------------------------------ plumbing */

    private void handleConnection(Socket socket) {
        String ip = socket.getInetAddress() == null ? "" : socket.getInetAddress().getHostAddress();
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(30_000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 65536);
            OutputStream rawOut = new BufferedOutputStream(socket.getOutputStream(), 65536);
            boolean keepAlive = true;
            while (keepAlive && running && !socket.isClosed()) {
                Request req = new Request();
                req.remoteIp = ip;
                req.in = in;
                if (!readHead(req, in)) break;
                Response res = new Response(rawOut, req.httpVersion.equals("HTTP/1.1"));
                long t0 = System.currentTimeMillis();
                try {
                    dispatch(req, res);
                } catch (Throwable e) {
                    lastError = String.valueOf(e);
                    Log.w(TAG, "handler error for " + req.method + " " + req.path + ": " + e);
                    try {
                        res.json(500, new Json.Obj().put("ok", false).put("error", String.valueOf(e)).toString());
                    } catch (Throwable ignored) { }
                }
                keepAlive = res.finish();
                served.incrementAndGet();
                Log.i(TAG, req.method + " " + req.path + " -> " + res.status + " ("
                        + (System.currentTimeMillis() - t0) + "ms, " + ip + ")");
            }
        } catch (Throwable t) {
            // client vanished mid-request: normal for streaming endpoints
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private boolean readHead(Request req, BufferedInputStream in) throws IOException {
        StringBuilder head = new StringBuilder(512);
        int c;
        while ((c = in.read()) != -1) {
            head.append((char) c);
            int n = head.length();
            // End of headers: CRLFCRLF (every real client) or bare LFLF.
            if (c == '\n') {
                if (n >= 2 && head.charAt(n - 2) == '\n') break;
                if (n >= 4 && head.charAt(n - 2) == '\r'
                        && head.charAt(n - 3) == '\n' && head.charAt(n - 4) == '\r') {
                    break;
                }
            }
            if (n > 65536) throw new IOException("header too large");
        }
        if (head.length() == 0 || c == -1) return false;
        String[] lines = head.toString().split("\r?\n");
        if (lines.length == 0 || lines[0].trim().isEmpty()) return false;
        String[] parts = lines[0].trim().split("\\s+");
        if (parts.length < 2) return false;
        req.method = parts[0].toUpperCase(Locale.US);
        req.rawTarget = parts[1];
        req.httpVersion = parts.length > 2 ? parts[2] : "HTTP/1.0";
        int q = req.rawTarget.indexOf('?');
        req.path = decode(q >= 0 ? req.rawTarget.substring(0, q) : req.rawTarget);
        req.query = q >= 0 ? req.rawTarget.substring(q + 1) : "";
        req.params = parseQuery(req.query);
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                req.headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
        }
        req.contentLength = parseLong(req.header("content-length"), -1);
        req.chunked = "chunked".equalsIgnoreCase(req.header("transfer-encoding"));
        if ("100-continue".equalsIgnoreCase(req.header("expect"))) {
            // curl asks before sending a large body (APK uploads)
            OutputStream o = req.out();
            o.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes("UTF-8"));
            o.flush();
        }
        return true;
    }

    private void dispatch(Request req, Response res) throws Exception {
        Handler h = routes.get(req.method + " " + req.path);
        if (h == null) {
            h = routes.get("ANY " + req.path);
        }
        if (h == null) {
            res.json(404, new Json.Obj().put("ok", false)
                    .put("error", "no such endpoint: " + req.method + " " + req.path)
                    .put("hint", "GET /help lists every endpoint").toString());
            return;
        }
        h.handle(req, res);
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null || q.isEmpty()) return m;
        for (String pair : q.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            m.put(decode(k), decode(v));
        }
        return m;
    }

    /* ------------------------------------------------------------------- request */

    public static final class Request {
        public String method = "GET";
        public String rawTarget = "/";
        public String path = "/";
        public String query = "";
        public String httpVersion = "HTTP/1.1";
        public Map<String, String> headers = new LinkedHashMap<>();
        public Map<String, String> params = new LinkedHashMap<>();
        public String remoteIp = "";
        InputStream in;
        public long contentLength = -1;
        public boolean chunked;
        private byte[] body;
        private int bodyPos;
        private int chunkRemaining = -1;

        /**
         * Streaming read of the request body, for handlers that copy straight to a
         * sink (file uploads, port-forwarded payloads). Returns -1 at end of body.
         */
        public int read(byte[] buf) throws IOException {
            if (in == null) return -1;
            if (body != null) {
                if (bodyPos >= body.length) return -1;
                int n = Math.min(buf.length, body.length - bodyPos);
                System.arraycopy(body, bodyPos, buf, 0, n);
                bodyPos += n;
                return n;
            }
            if (chunked) {
                if (chunkRemaining == 0) {
                    readLine(in);
                    chunkRemaining = -1;
                }
                if (chunkRemaining < 0) {
                    String line = readLine(in);
                    if (line == null) return -1;
                    line = line.trim();
                    if (line.isEmpty()) {
                        line = readLine(in);
                        if (line == null) return -1;
                        line = line.trim();
                    }
                    int semi = line.indexOf(';');
                    if (semi >= 0) line = line.substring(0, semi);
                    long sz;
                    try {
                        sz = Long.parseLong(line.trim(), 16);
                    } catch (Exception e) {
                        return -1;
                    }
                    if (sz == 0) {
                        readLine(in);
                        return -1;
                    }
                    chunkRemaining = (int) Math.min(sz, Integer.MAX_VALUE);
                }
                int n = in.read(buf, 0, Math.min(buf.length, chunkRemaining));
                if (n > 0) chunkRemaining -= n;
                return n;
            }
            if (contentLength > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, contentLength));
                if (n > 0) contentLength -= n;
                return n;
            }
            if (contentLength == 0) return -1;
            return in.read(buf);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.US));
        }

        public String param(String name) {
            return params.get(name);
        }

        public String param(String name, String def) {
            String v = params.get(name);
            return v == null ? def : v;
        }

        public long paramLong(String name, long def) {
            return parseLong(param(name, String.valueOf(def)), def);
        }

        public boolean paramBool(String name, boolean def) {
            String v = param(name);
            if (v == null) return def;
            v = v.trim().toLowerCase(Locale.US);
            return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
        }

        OutputStream out() {
            return new OutputStream() {
                @Override public void write(int b) { }
            };
        }

        /** Read the whole body into memory (capped) and parse it as JSON. */
        public Map<String, Object> json() {
            String s = bodyString();
            if (s == null || s.trim().isEmpty()) return new LinkedHashMap<>();
            try {
                return Json.parseObj(s);
            } catch (Throwable t) {
                return new LinkedHashMap<>();
            }
        }

        public String bodyString() {
            byte[] b = body();
            try {
                return new String(b, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }

        public byte[] body() {
            if (body != null) return body;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (chunked) {
                    readChunked(in, out, MAX_BUFFERED_BODY);
                } else if (contentLength > 0) {
                    copy(in, out, contentLength, MAX_BUFFERED_BODY);
                }
                body = out.toByteArray();
            } catch (Throwable t) {
                body = new byte[0];
            }
            return body;
        }

        /** Stream the body to a file (used for APK uploads). Returns bytes written. */
        public long bodyToFile(File f) throws IOException {
            FileOutputStream fos = new FileOutputStream(f, false);
            long n;
            try {
                if (chunked) {
                    n = readChunked(in, fos, Long.MAX_VALUE);
                } else if (contentLength > 0) {
                    n = copy(in, fos, contentLength, Long.MAX_VALUE);
                } else {
                    n = 0;
                }
            } finally {
                fos.close();
            }
            return n;
        }

        /** Leave any unread body so keep-alive stays in sync. */
        void drain() {
            if (body != null) return;
            try {
                if (chunked) {
                    readChunked(in, null, MAX_BUFFERED_BODY);
                } else if (contentLength > 0) {
                    copy(in, null, contentLength, MAX_BUFFERED_BODY);
                }
            } catch (Throwable ignored) { }
        }
    }

    private static long copy(InputStream in, OutputStream out, long len, long cap) throws IOException {
        byte[] buf = new byte[65536];
        long left = len, total = 0;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            if (out != null) out.write(buf, 0, n);
            left -= n;
            total += n;
            if (total > cap) throw new IOException("body too large");
        }
        return total;
    }

    private static long readChunked(InputStream in, OutputStream out, long cap) throws IOException {
        long total = 0;
        while (true) {
            String line = readLine(in);
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            long size;
            try { size = Long.parseLong(line.trim(), 16); } catch (Exception e) { break; }
            if (size == 0) {
                readLine(in); // trailing CRLF
                break;
            }
            total += copy(in, out, size, cap);
            readLine(in); // CRLF after chunk
        }
        return total;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), "UTF-8");
    }

    /* ------------------------------------------------------------------ response */

    public static final class Response {
        public int status = 200;
        public Map<String, String> headers = new LinkedHashMap<>();
        private final OutputStream out;
        private final boolean http11;
        private boolean sent;
        private boolean streamed;
        private boolean finished;
        private byte[] pending;
        private String pendingType;

        Response(OutputStream out, boolean http11) {
            this.out = out;
            this.http11 = http11;
        }

        public void header(String k, String v) {
            headers.put(k, v);
        }

        public void text(int status, String body) {
            bytes(status, "text/plain; charset=utf-8", body == null ? new byte[0] : body.getBytes());
        }

        public void json(int status, String json) {
            bytes(status, "application/json; charset=utf-8", json == null ? new byte[0] : json.getBytes());
        }

        public void bytes(int status, String contentType, byte[] data) {
            this.status = status;
            this.pendingType = contentType;
            this.pending = data == null ? new byte[0] : data;
        }

        public void redirect(String location) {
            status = 302;
            header("Location", location);
            pending = new byte[0];
            pendingType = "text/plain";
        }

        /** Open a streaming response (chunked on HTTP/1.1). Returns the sink. */
        public OutputStream stream(int status, String contentType) throws IOException {
            this.status = status;
            this.streamed = true;
            sendHead(contentType, -1);
            if (http11) {
                return new ChunkedOutputStream(out);
            }
            return out;
        }

        public boolean isStreamed() { return streamed; }

        private void sendHead(String contentType, long contentLength) throws IOException {
            if (sent) return;
            sent = true;
            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
            h.append("Server: agentic-dev-kit\r\n");
            h.append("Access-Control-Allow-Origin: *\r\n");
            h.append("Access-Control-Allow-Headers: X-ADK-Token, Authorization, Content-Type\r\n");
            h.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            if (contentType != null) h.append("Content-Type: ").append(contentType).append("\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            if (contentLength >= 0) {
                h.append("Content-Length: ").append(contentLength).append("\r\n");
            } else if (http11) {
                h.append("Transfer-Encoding: chunked\r\n");
            }
            h.append("Connection: ").append(streamed ? "close" : "keep-alive").append("\r\n");
            h.append("\r\n");
            out.write(h.toString().getBytes("UTF-8"));
            out.flush();
        }

        /** Send the buffered response; returns true if the connection can be reused. */
        boolean finish() throws IOException {
            if (finished) return false;
            finished = true;
            if (!sent) {
                byte[] data = pending == null ? new byte[0] : pending;
                sendHead(pendingType, data.length);
                out.write(data);
                out.flush();
            }
            out.flush();
            return !streamed;
        }
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Status";
        }
    }

    /** Minimal chunked transfer encoder. */
    private static final class ChunkedOutputStream extends OutputStream {
        private final OutputStream out;

        ChunkedOutputStream(OutputStream out) { this.out = out; }

        @Override public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (len <= 0) return;
            out.write(Integer.toHexString(len).getBytes("UTF-8"));
            out.write("\r\n".getBytes("UTF-8"));
            out.write(b, off, len);
            out.write("\r\n".getBytes("UTF-8"));
            out.flush();
        }

        @Override public void flush() throws IOException { out.flush(); }

        @Override public void close() throws IOException {
            out.write("0\r\n\r\n".getBytes("UTF-8"));
            out.flush();
        }
    }
}

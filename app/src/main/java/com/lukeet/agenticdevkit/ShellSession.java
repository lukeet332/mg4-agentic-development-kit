package com.lukeet.agenticdevkit;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A persistent shell, the closest thing to `adb shell` without adbd:
 * one long-lived `sh` process whose stdin the agent writes to and whose
 * merged stdout/stderr is read back through a ring buffer with offsets.
 * State (cwd, exported vars, background jobs) survives between requests.
 *
 * Note: stdin is a pipe, not a PTY, so there is no prompt and no job control.
 */
public final class ShellSession {

    private static final String TAG = "ADK";
    private static final int RING = 1 << 20; // 1 MiB scrollback per session
    private static final Map<String, ShellSession> SESSIONS = new LinkedHashMap<>();

    private final String id;
    private final Process proc;
    private final OutputStream stdin;
    private final byte[] ring = new byte[RING];
    private long written;     // bytes ever produced
    private int size;         // bytes currently in the ring
    private boolean alive = true;
    private int exit = -1;

    private ShellSession(String id, Process proc) {
        this.id = id;
        this.proc = proc;
        this.stdin = proc.getOutputStream();
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                InputStream in = ShellSession.this.proc.getInputStream();
                byte[] buf = new byte[8192];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        append(buf, n);
                    }
                } catch (Throwable ignored) {
                } finally {
                    synchronized (ShellSession.this) {
                        alive = false;
                        try { exit = ShellSession.this.proc.exitValue(); } catch (Throwable ignored) { }
                        ShellSession.this.notifyAll();
                    }
                    Log.i(TAG, "shell session " + ShellSession.this.id + " ended (exit=" + exit + ")");
                }
            }
        });
        reader.setDaemon(true);
        reader.start();
    }

    /* ------------------------------------------------------------------ registry */

    public static ShellSession open() throws IOException {
        Process p = Shell.start(new String[]{"/system/bin/sh"});
        String id = TokenStore.randomHex(8);
        ShellSession s = new ShellSession(id, p);
        synchronized (SESSIONS) {
            SESSIONS.put(id, s);
        }
        Log.i(TAG, "shell session " + id + " opened");
        return s;
    }

    public static ShellSession get(String id) {
        synchronized (SESSIONS) {
            return id == null ? null : SESSIONS.get(id);
        }
    }

    public static boolean close(String id) {
        ShellSession s;
        synchronized (SESSIONS) {
            s = SESSIONS.remove(id);
        }
        if (s == null) return false;
        s.kill();
        return true;
    }

    public static List<ShellSession> all() {
        synchronized (SESSIONS) {
            return new ArrayList<>(SESSIONS.values());
        }
    }

    public static void closeAll() {
        List<ShellSession> copy = all();
        synchronized (SESSIONS) {
            SESSIONS.clear();
        }
        for (ShellSession s : copy) s.kill();
    }

    /* ------------------------------------------------------------------- session */

    public String id() { return id; }

    public boolean alive() { return alive; }

    public int exitCode() { return exit; }

    private void append(byte[] buf, int n) {
        synchronized (this) {
            for (int i = 0; i < n; i++) {
                ring[(int) (written % RING)] = buf[i];
                written++;
            }
            size = (int) Math.min(written, RING);
            notifyAll();
        }
    }

    /** Write raw data to the shell's stdin. A newline is added unless present. */
    public long write(String data) throws IOException {
        if (data == null) data = "";
        if (!data.endsWith("\n")) data = data + "\n";
        byte[] b = data.getBytes("UTF-8");
        synchronized (this) {
            stdin.write(b);
            stdin.flush();
        }
        return b.length;
    }

    public long writeRaw(byte[] data) throws IOException {
        synchronized (this) {
            stdin.write(data);
            stdin.flush();
        }
        return data == null ? 0 : data.length;
    }

    /**
     * Read output produced after {@code offset}. If no data is available yet and
     * {@code waitMs} > 0, wait for up to that long (long-poll).
     */
    public String read(long offset, long waitMs) {
        long deadline = System.currentTimeMillis() + Math.max(0, waitMs);
        synchronized (this) {
            while (true) {
                long start = Math.max(offset, written - size);
                if (start < written) {
                    int len = (int) (written - start);
                    byte[] out = new byte[len];
                    for (int i = 0; i < len; i++) {
                        out[i] = ring[(int) ((start + i) % RING)];
                    }
                    try {
                        return new String(out, "UTF-8");
                    } catch (Exception e) {
                        return new String(out);
                    }
                }
                if (!alive) return "";
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return "";
                try {
                    wait(left);
                } catch (InterruptedException e) {
                    return "";
                }
            }
        }
    }

    public long offset() {
        synchronized (this) {
            return written;
        }
    }

    public void kill() {
        try { stdin.close(); } catch (Throwable ignored) { }
        try { proc.destroy(); } catch (Throwable ignored) { }
        synchronized (this) {
            alive = false;
            notifyAll();
        }
    }
}

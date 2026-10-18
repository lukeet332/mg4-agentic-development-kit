package com.lukeet.agenticdevkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Process helpers. Everything the kit shells out to runs as the app's uid, which
 * for this app is the system uid (1000) - so `pm install`, `screencap`, `input`,
 * `logcat`, `dumpsys`, `am` and friends behave like adb shell on a rooted unit.
 */
public final class Shell {

    public static final class Result {
        public int exit = -1;
        public String out = "";
        public String err = "";
        public byte[] outBytes = new byte[0];
        public long ms;
        public boolean timeout;

        public boolean ok() {
            return exit == 0 && !timeout;
        }

        public String combined() {
            StringBuilder b = new StringBuilder();
            if (out != null && !out.isEmpty()) b.append(out);
            if (err != null && !err.isEmpty()) {
                if (b.length() > 0 && b.charAt(b.length() - 1) != '\n') b.append('\n');
                b.append(err);
            }
            return b.toString();
        }
    }

    private Shell() {}

    public static Result sh(String cmd) {
        return sh(cmd, 30_000L);
    }

    public static Result sh(String cmd, long timeoutMs) {
        return exec(new String[]{"/system/bin/sh", "-c", cmd}, timeoutMs);
    }

    public static Result exec(String[] argv, long timeoutMs) {
        Result r = new Result();
        long t0 = System.currentTimeMillis();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(false);
            pb.environment().put("LC_ALL", "C");
            p = pb.start();
            final Process fp = p;
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread to = pump(fp.getInputStream(), out);
            Thread te = pump(fp.getErrorStream(), err);
            final Process cp = p;
            Thread waiter = new Thread(new Runnable() {
                @Override public void run() {
                    try { cp.waitFor(); } catch (InterruptedException ignored) { }
                }
            });
            waiter.setDaemon(true);
            waiter.start();
            waiter.join(timeoutMs > 0 ? timeoutMs : 0);
            if (waiter.isAlive()) {
                r.timeout = true;
                try { p.destroy(); } catch (Throwable ignored) { }
                try { p.waitFor(); } catch (InterruptedException ignored) { }
            }
            to.join(2000);
            te.join(2000);
            r.exit = r.timeout ? -1 : safeExit(p);
            r.outBytes = out.toByteArray();
            r.out = new String(r.outBytes, "UTF-8");
            r.err = new String(err.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            r.err = String.valueOf(t);
            r.exit = -1;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
        r.ms = System.currentTimeMillis() - t0;
        return r;
    }

    private static int safeExit(Process p) {
        try { return p.exitValue(); } catch (Throwable t) { return -1; }
    }

    private static Thread pump(final InputStream in, final ByteArrayOutputStream sink) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[16384];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        sink.write(buf, 0, n);
                    }
                } catch (IOException ignored) {
                } finally {
                    try { in.close(); } catch (IOException ignored) { }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Start a long-lived process with pipes kept open (interactive shell sessions). */
    public static Process start(String[] argv) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(true);
        return pb.start();
    }

    /** POSIX single-quote escaping, safe for `sh -c`. */
    public static String quote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String[] argv(String... parts) {
        List<String> l = new ArrayList<>();
        for (String p : parts) if (p != null) l.add(p);
        return l.toArray(new String[0]);
    }

    public static void closeQuietly(OutputStream out) {
        if (out != null) try { out.close(); } catch (IOException ignored) { }
    }
}

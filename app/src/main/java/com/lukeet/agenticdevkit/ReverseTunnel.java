package com.lukeet.agenticdevkit;

import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps idle connections open from the car to the ADK Relay app on any phone on the car's hotspot.
 * The relay pairs each with a tailnet request, so the channel stays reachable while the hotspot
 * has pushed the car off home Wi-Fi. The car dials out because the phone, under Tailscale, may
 * only accept on the hotspot, not connect into it.
 */
final class ReverseTunnel {

    private static final String TAG = "ADK";
    private static final int RELAY_PORT = 18788;
    private static final int IDLE_PER_PHONE = 3;
    private static final long RETRY_MS = 5_000;

    private final int localPort;
    private final Map<String, AtomicInteger> idle = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
    /** Last time each phone's state was logged, so a dead relay is said once a minute, not every 5 s. */
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    private void logOnce(String phone, String message) {
        long now = System.currentTimeMillis();
        Long last = lastLogged.get(phone);
        if (last != null && now - last < 60_000) return;
        lastLogged.put(phone, now);
        Log.i(TAG, "reverse tunnel " + phone + ": " + message);
    }
    private volatile boolean running;

    ReverseTunnel(int localPort) { this.localPort = localPort; }

    void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    try { tick(); } catch (Throwable t) { Log.w(TAG, "reverse tunnel: " + t); }
                    try { Thread.sleep(2_000); } catch (InterruptedException e) { return; }
                }
            }
        }, "adk-reverse");
        t.setDaemon(true);
        t.start();
    }

    void stop() { running = false; }

    private void tick() {
        long now = System.currentTimeMillis();
        for (String phone : hotspotClients()) {
            Long after = retryAfter.get(phone);
            if (after != null && now < after) continue;
            AtomicInteger count = idle.get(phone);
            if (count == null) { count = new AtomicInteger(); idle.put(phone, count); }
            while (count.get() < IDLE_PER_PHONE) {
                count.incrementAndGet();
                spawn(phone, count);
            }
        }
    }

    private void spawn(final String phone, final AtomicInteger count) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                Socket relay = new Socket();
                try {
                    relay.connect(new InetSocketAddress(phone, RELAY_PORT), 1_500);
                    relay.setKeepAlive(true);
                    logOnce(phone, "linked to ADK Relay");
                    InputStream in = relay.getInputStream();
                    byte[] first = new byte[64 * 1024];
                    int n = in.read(first); // blocks until the relay hands us a request
                    count.decrementAndGet();
                    if (n < 0) return;
                    Socket local = new Socket("127.0.0.1", localPort);
                    local.getOutputStream().write(first, 0, n);
                    pipe(relay, local);
                } catch (IOException e) {
                    count.decrementAndGet();
                    retryAfter.put(phone, System.currentTimeMillis() + RETRY_MS);
                    logOnce(phone, "no relay (" + e.getMessage() + ") - is ADK Relay running on the phone?");
                } finally {
                    try { relay.close(); } catch (IOException ignored) { }
                }
            }
        }, "adk-reverse-link");
        t.setDaemon(true);
        t.start();
    }

    /** Hosts the car's hotspot has handed addresses to: ap0 entries in the ARP table. */
    private static List<String> hotspotClients() {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 6 && f[5].equals("ap0") && !f[3].equals("00:00:00:00:00:00")) out.add(f[0]);
            }
        } catch (IOException ignored) { }
        return out;
    }

    private static void pipe(final Socket a, final Socket b) {
        Thread up = new Thread(new Runnable() { public void run() { copy(a, b); } }, "adk-reverse-up");
        up.setDaemon(true);
        up.start();
        copy(b, a);
        try { up.join(); } catch (InterruptedException ignored) { }
        try { b.close(); } catch (IOException ignored) { }
    }

    private static void copy(Socket from, Socket to) {
        byte[] buf = new byte[64 * 1024];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { to.shutdownOutput(); } catch (IOException ignored) { }
        }
    }
}

package com.lukeet.agenticdevkit;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TCP forwarding, the adb forward / adb reverse equivalent.
 *
 * A rule listens on the car ({@code bind}:{@code localPort}) and pipes every
 * accepted connection to {@code host}:{@code port}. Because this app is the system
 * user it can bind privileged ports too.
 *
 *  - bind 0.0.0.0   -> like `adb forward`: the agent reaches a car-local service.
 *  - bind 127.0.0.1 -> like `adb reverse`: apps on the car reach the agent's host.
 */
public final class Forwarder {

    private static final String TAG = "ADK";

    public static final class Rule {
        public String id;
        public String bind;
        public int localPort;
        public String host;
        public int port;
        public long created;
        public volatile long connections;
        public volatile String lastError = "";
    }

    private static final Map<String, Rule> RULES = new LinkedHashMap<>();
    private static final Map<String, ServerSocket> SOCKETS = new LinkedHashMap<>();

    private Forwarder() {}

    public static Rule add(String bind, int localPort, String host, int port) throws IOException {
        final Rule rule = new Rule();
        rule.id = TokenStore.randomHex(6);
        rule.bind = (bind == null || bind.isEmpty()) ? "0.0.0.0" : bind;
        rule.localPort = localPort;
        rule.host = host;
        rule.port = port;
        rule.created = System.currentTimeMillis();

        final ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(rule.bind), localPort));
        synchronized (RULES) {
            RULES.put(rule.id, rule);
            SOCKETS.put(rule.id, server);
        }

        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "forward " + rule.id + ": " + rule.bind + ":" + rule.localPort
                        + " -> " + rule.host + ":" + rule.port);
                while (true) {
                    Socket client;
                    try {
                        client = server.accept();
                    } catch (IOException e) {
                        break; // closed
                    }
                    rule.connections++;
                    final Socket c = client;
                    Thread pipe = new Thread(new Runnable() {
                        @Override public void run() {
                            Socket target = null;
                            try {
                                target = new Socket();
                                target.connect(new InetSocketAddress(rule.host, rule.port), 10000);
                                c.setTcpNoDelay(true);
                                target.setTcpNoDelay(true);
                                Thread up = pump(c, target);
                                Thread down = pump(target, c);
                                up.join();
                                down.join();
                            } catch (Throwable e) {
                                rule.lastError = String.valueOf(e);
                                Log.w(TAG, "forward " + rule.id + " connection failed: " + e);
                            } finally {
                                closeQuietly(target);
                                closeQuietly(c);
                            }
                        }
                    });
                    pipe.setDaemon(true);
                    pipe.start();
                }
                Log.i(TAG, "forward " + rule.id + " stopped");
            }
        }, "adk-forward-" + rule.id);
        t.setDaemon(true);
        t.start();
        return rule;
    }

    public static boolean remove(String id) {
        ServerSocket s;
        synchronized (RULES) {
            RULES.remove(id);
            s = SOCKETS.remove(id);
        }
        if (s == null) return false;
        try { s.close(); } catch (IOException ignored) { }
        return true;
    }

    public static List<Rule> list() {
        synchronized (RULES) {
            return new ArrayList<>(RULES.values());
        }
    }

    public static void stopAll() {
        List<String> ids = new ArrayList<>();
        synchronized (RULES) {
            ids.addAll(RULES.keySet());
        }
        for (String id : ids) remove(id);
    }

    private static Thread pump(final Socket from, final Socket to) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[32768];
                try {
                    InputStream in = from.getInputStream();
                    OutputStream out = to.getOutputStream();
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    closeQuietly(to);
                }
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}

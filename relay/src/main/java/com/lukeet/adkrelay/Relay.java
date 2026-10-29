package com.lukeet.adkrelay;

import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Pairs each agent connection (tailnet, port 8787) with an idle connection ADK opened from the
 * car (hotspot, port 18788) and pipes the bytes. The phone only ever accepts, so Tailscale
 * never has to let this app dial out on the car's network.
 */
final class Relay {
    static final int AGENT_PORT = 8787;
    static final int CAR_PORT = 18788;
    private static final String TAG = "ADKRelay";

    private final LinkedBlockingDeque<Socket> idleCar = new LinkedBlockingDeque<>();
    private volatile boolean running;
    private ServerSocket agentServer, carServer;
    volatile int served;
    volatile String lastEvent = "starting";

    int idleCarConnections() { return idleCar.size(); }

    void start() throws IOException {
        running = true;
        carServer = listen(CAR_PORT);
        agentServer = listen(AGENT_PORT);
        thread("car-accept", new Runnable() { public void run() { acceptCar(); } });
        thread("agent-accept", new Runnable() { public void run() { acceptAgents(); } });
    }

    void stop() {
        running = false;
        closeQuietly(carServer);
        closeQuietly(agentServer);
        Socket s;
        while ((s = idleCar.poll()) != null) closeQuietly(s);
    }

    private static ServerSocket listen(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port), 16);
        return s;
    }

    private void acceptCar() {
        while (running) {
            try {
                Socket s = carServer.accept();
                if (!RelayPolicy.isCar(s.getInetAddress().getAddress())) {
                    Log.w(TAG, "refused car-side connection from " + s.getInetAddress());
                    closeQuietly(s);
                    continue;
                }
                s.setKeepAlive(true);
                idleCar.add(s);
                lastEvent = "car connected (" + idleCar.size() + " idle)";
            } catch (IOException e) {
                if (running) sleep(500);
            }
        }
    }

    private void acceptAgents() {
        while (running) {
            final Socket agent;
            try {
                agent = agentServer.accept();
            } catch (IOException e) {
                if (running) sleep(500);
                continue;
            }
            if (!RelayPolicy.isAgent(agent.getInetAddress().getAddress())) {
                Log.w(TAG, "refused agent-side connection from " + agent.getInetAddress());
                closeQuietly(agent);
                continue;
            }
            thread("pair", new Runnable() { public void run() { pair(agent); } });
        }
    }

    private void pair(Socket agent) {
        try {
            Socket car = idleCar.poll(5, TimeUnit.SECONDS);
            if (car == null) {
                lastEvent = "agent connected but no car connection is waiting";
                closeQuietly(agent);
                return;
            }
            served++;
            lastEvent = "relaying request " + served;
            pipe(agent, car);
        } catch (InterruptedException e) {
            closeQuietly(agent);
        }
    }

    /** Copies both directions until either side closes, then closes both. */
    static void pipe(final Socket a, final Socket b) {
        Thread up = thread("pipe-up", new Runnable() { public void run() { copy(a, b); } });
        copy(b, a);
        try { up.join(); } catch (InterruptedException ignored) { }
        closeQuietly(a);
        closeQuietly(b);
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

    static Thread thread(String name, Runnable r) {
        Thread t = new Thread(r, "relay-" + name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    static void closeQuietly(java.io.Closeable c) {
        try { if (c != null) c.close(); } catch (IOException ignored) { }
    }
}

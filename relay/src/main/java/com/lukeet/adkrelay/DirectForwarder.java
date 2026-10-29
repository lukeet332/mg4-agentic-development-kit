package com.lukeet.adkrelay;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Log;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Over adb (adb forward tcp:18790 tcp:18790): localhost:18790 on the phone straight to ADK on the
 * car, on the car hotspot's own network. Works while Android Auto owns that Wi-Fi, when the shell
 * and the default route cannot reach the car, and needs nothing from the car's reverse tunnel.
 */
final class DirectForwarder {
    static final int PORT = 18790;
    private static final String TAG = "ADKRelay";
    private final Context context;
    private volatile boolean running;
    private ServerSocket server;

    DirectForwarder(Context context) { this.context = context.getApplicationContext(); }

    /** The car hotspot hands out 192.168.33.x; its gateway, the car, is .1. */
    static boolean isCarHotspotAddress(byte[] a) {
        return a.length == 4 && (a[0] & 0xff) == 192 && (a[1] & 0xff) == 168 && (a[2] & 0xff) == 33;
    }

    void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
        running = true;
        Relay.thread("direct-accept", new Runnable() { public void run() { accept(); } });
    }

    void stop() {
        running = false;
        Relay.closeQuietly(server);
    }

    private void accept() {
        while (running) {
            try {
                final Socket client = server.accept();
                Relay.thread("direct", new Runnable() { public void run() { forward(client); } });
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void forward(Socket client) {
        Network network = carNetwork();
        if (network == null) {
            Log.w(TAG, "direct: not on the car hotspot");
            Relay.closeQuietly(client);
            return;
        }
        try {
            Socket car = network.getSocketFactory().createSocket();
            car.connect(new InetSocketAddress("192.168.33.1", 8787), 3000);
            Relay.pipe(client, car);
        } catch (IOException e) {
            Log.w(TAG, "direct: " + e);
            Relay.closeQuietly(client);
        }
    }

    private Network carNetwork() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        for (Network n : cm.getAllNetworks()) {
            LinkProperties lp = cm.getLinkProperties(n);
            if (lp == null) continue;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (isCarHotspotAddress(la.getAddress().getAddress())) return n;
            }
        }
        return null;
    }
}

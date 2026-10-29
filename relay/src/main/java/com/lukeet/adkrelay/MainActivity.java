package com.lukeet.adkrelay;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Starts the relay and shows how to reach it. */
public final class MainActivity extends Activity {
    private TextView text;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 2_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        text = new TextView(this);
        text.setPadding(48, 48, 48, 48);
        text.setTextSize(16);
        setContentView(text);
        RelayService.start(this);
        askToSkipBatteryOptimisation();
    }

    /** OnePlus kills background services hard; an exemption keeps the relay alive in the car. */
    private void askToSkipBatteryOptimisation() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) { }
    }

    @Override protected void onResume() { super.onResume(); handler.post(ticker); }
    @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }

    private void render() {
        Relay r = RelayService.relay();
        StringBuilder sb = new StringBuilder("ADK Relay\n\n");
        sb.append(r == null ? "Starting…" : "Running").append("\n");
        boolean exempt = ((android.os.PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
        sb.append(exempt ? "Battery: unrestricted" : "Battery: OPTIMISED - the phone may kill the relay").append("\n\n");
        if (r != null) {
            sb.append("Car links waiting: ").append(r.idleCarConnections()).append('\n');
            sb.append("Requests relayed: ").append(r.served).append('\n');
            sb.append("Last: ").append(r.lastEvent).append("\n\n");
        }
        sb.append("Agent address (tailnet):\n");
        for (String a : addresses()) sb.append("  http://").append(a).append(':').append(Relay.AGENT_PORT).append('\n');
        text.setText(sb);
    }

    private static java.util.List<String> addresses() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && RelayPolicy.isAgent(a.getAddress()) && !a.isLoopbackAddress()) {
                        out.add(a.getHostAddress());
                    }
                }
            }
        } catch (Throwable ignored) { }
        return out;
    }
}

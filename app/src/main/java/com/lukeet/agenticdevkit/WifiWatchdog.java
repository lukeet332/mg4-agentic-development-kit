package com.lukeet.agenticdevkit;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import android.util.Log;
import java.net.InetAddress;

/**
 * Recovers the MG4's Wi-Fi when the MT6630 driver wedges: scans stop completing, the data path
 * dies, but the station stays "connected", so nothing in Android notices (seen 2026-10-19,
 * 08:08-08:17; a Wi-Fi off/on cleared it). If the router stops answering while connected, bounce Wi-Fi.
 */
final class WifiWatchdog {

    private static final String TAG = "ADK";
    private static final int FAILURES_BEFORE_BOUNCE = 3;
    private static final long MIN_BOUNCE_INTERVAL_MS = 10 * 60_000L;

    private int failures;
    private long lastBounceAt;

    /** Called from the service's 15 s loop. */
    void check(Context ctx) {
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) { failures = 0; return; }
        WifiInfo info = wifi.getConnectionInfo();
        DhcpInfo dhcp = wifi.getDhcpInfo();
        if (info == null || info.getSupplicantState() != SupplicantState.COMPLETED || dhcp == null || dhcp.gateway == 0) {
            failures = 0;
            return;
        }
        if (gatewayAnswers(dhcp.gateway)) {
            failures = 0;
            return;
        }
        failures++;
        Log.w(TAG, "wifi watchdog: router not answering while connected (" + failures + "/" + FAILURES_BEFORE_BOUNCE + ")");
        long now = SystemClock.elapsedRealtime();
        if (failures < FAILURES_BEFORE_BOUNCE || (lastBounceAt != 0 && now - lastBounceAt < MIN_BOUNCE_INTERVAL_MS)) return;
        failures = 0;
        lastBounceAt = now;
        Log.w(TAG, "wifi watchdog: Wi-Fi looks wedged - switching it off and on");
        try {
            wifi.setWifiEnabled(false);
            SystemClock.sleep(3_000);
            wifi.setWifiEnabled(true);
        } catch (Throwable t) {
            Log.w(TAG, "wifi watchdog: bounce failed: " + t);
        }
    }

    /** isReachable falls back to a TCP probe; a refusal still proves the router is there. */
    private static boolean gatewayAnswers(int gateway) {
        byte[] a = { (byte) gateway, (byte) (gateway >> 8), (byte) (gateway >> 16), (byte) (gateway >> 24) };
        try {
            return InetAddress.getByAddress(a).isReachable(2_000);
        } catch (Throwable t) {
            return false;
        }
    }
}

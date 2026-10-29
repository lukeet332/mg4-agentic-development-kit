package com.lukeet.mg4apps;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Turns the drawer trigger on without the car's (Amazon keyboard only) settings screen, which the
 * system uid may do. After an update the system keeps the old service config cached, so the entry
 * is rewritten to force a rebind (same fix as ADK's).
 */
public final class AccessibilityEnabler extends BroadcastReceiver {

    private static final String TAG = "MG4Apps";

    @Override
    public void onReceive(Context context, Intent intent) {
        ensure(context, Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()));
    }

    static void ensure(Context context, boolean rebind) {
        try {
            String flat = new ComponentName(context, DrawerTriggerService.class).flattenToString();
            String current = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String others = without(current, flat);
            boolean present = !others.equals(current == null ? "" : current);
            if (present && !rebind) return;
            if (present) {
                put(context, others);
                SystemClock.sleep(500);
            }
            put(context, others.isEmpty() ? flat : others + ":" + flat);
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(TAG, "drawer trigger enabled" + (rebind ? " (rebind after update)" : ""));
        } catch (Throwable t) {
            Log.w(TAG, "cannot enable the drawer trigger: " + t);
        }
    }

    static String without(String services, String flat) {
        if (services == null) return "";
        StringBuilder out = new StringBuilder();
        for (String s : services.split(":")) {
            if (s.isEmpty() || s.equals(flat)) continue;
            if (out.length() > 0) out.append(':');
            out.append(s);
        }
        return out.toString();
    }

    private static void put(Context context, String value) {
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
    }
}

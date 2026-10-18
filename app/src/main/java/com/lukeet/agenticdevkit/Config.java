package com.lukeet.agenticdevkit;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent settings for the kit (port, master token, autostart). */
public final class Config {

    public static final int DEFAULT_PORT = 8787;
    public static final String PREFS = "adk";
    public static final String KEY_PORT = "port";
    public static final String KEY_TOKEN = "master_token";
    public static final String KEY_AUTOSTART = "autostart";
    public static final String KEY_BIND = "bind";

    /** Token mirror the host/agent can read over adb. */
    public static final String TOKEN_MIRROR = "/data/local/tmp/adk.token";

    private Config() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int port(Context c) {
        return prefs(c).getInt(KEY_PORT, DEFAULT_PORT);
    }

    public static void setPort(Context c, int p) {
        prefs(c).edit().putInt(KEY_PORT, p).apply();
    }

    public static boolean autostart(Context c) {
        return prefs(c).getBoolean(KEY_AUTOSTART, true);
    }

    public static void setAutostart(Context c, boolean v) {
        prefs(c).edit().putBoolean(KEY_AUTOSTART, v).apply();
    }

    /** Interface to bind: "0.0.0.0" (car hotspot + home wifi) or "127.0.0.1". */
    public static String bind(Context c) {
        return prefs(c).getString(KEY_BIND, "0.0.0.0");
    }

    public static void setBind(Context c, String v) {
        prefs(c).edit().putString(KEY_BIND, v).apply();
    }
}

package com.lukeet.agenticdevkit;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;

/**
 * The master token: the "physical access" credential. It is generated on first run,
 * shown in the app UI, mirrored to /data/local/tmp/adk.token so an adb-attached host
 * can read it, and can be overridden from adb with:
 *
 *   adb shell settings put global adk_token mysecrettoken
 *
 * Paired devices get their own tokens (see {@link Pairing}); this one is the
 * break-glass credential and is never handed out over the network.
 */
public final class TokenStore {

    private static final String TAG = "ADK";
    public static final String GLOBAL_OVERRIDE = "adk_token";

    private TokenStore() {}

    public static String master(Context c) {
        String override = null;
        try {
            override = Settings.Global.getString(c.getContentResolver(), GLOBAL_OVERRIDE);
        } catch (Throwable t) {
            Log.w(TAG, "cannot read Settings.Global." + GLOBAL_OVERRIDE + ": " + t);
        }
        if (override != null && override.trim().length() >= 8) {
            return override.trim();
        }
        String t = Config.prefs(c).getString(Config.KEY_TOKEN, null);
        if (t == null || t.length() < 16) {
            t = randomHex(32);
            Config.prefs(c).edit().putString(Config.KEY_TOKEN, t).apply();
        }
        return t;
    }

    /**
     * Make the master token readable for whoever has the unit: adb shell, the
     * Files app, or the log. SELinux denies system_app /data/local/tmp
     * (shell_data_file), so each location is attempted independently and the
     * result is reported in GET /state.
     */
    public static void mirror(Context c) {
        String token = master(c);
        String[] paths = {
                Config.TOKEN_MIRROR,                                  // /data/local/tmp (adb, usually denied for system_app)
                "/sdcard/adk.token",                                  // Files app / USB stick
                new File(c.getExternalFilesDir(null), "adk.token").getAbsolutePath(),
        };
        StringBuilder ok = new StringBuilder();
        for (String path : paths) {
            File f = new File(path);
            FileOutputStream out = null;
            try {
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                out = new FileOutputStream(f, false);
                out.write((token + "\n").getBytes("UTF-8"));
                out.flush();
                out.close();
                out = null;
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
                if (ok.length() > 0) ok.append(',');
                ok.append(path);
            } catch (Throwable t) {
                Log.w(TAG, "cannot mirror token to " + path + ": " + t);
            } finally {
                if (out != null) try { out.close(); } catch (IOException ignored) { }
            }
        }
        mirrored = ok.toString();
        // Always readable over adb: logcat. `adb logcat -s ADK` shows it.
        Log.i(TAG, "master token: " + token + " (mirrored to: " + (mirrored.isEmpty() ? "nothing" : mirrored) + ")");
    }

    private static volatile String mirrored = "";

    /** Where the token mirror actually succeeded, for GET /state. */
    public static String mirroredPaths() {
        return mirrored;
    }

    public static String randomHex(int chars) {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder(chars);
        for (int i = 0; i < chars; i++) {
            b.append(Character.forDigit(r.nextInt(16), 16));
        }
        return b.toString();
    }

    public static String randomDigits(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder(n);
        for (int i = 0; i < n; i++) b.append((char) ('0' + r.nextInt(10)));
        return b.toString();
    }

    /** Constant-time-ish comparison. */
    public static boolean equals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}

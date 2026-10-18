package com.lukeet.agenticdevkit;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Self-update safety net.
 *
 * Before installing a new build of ADK over itself, the current APK is copied to
 * last-good.apk and a rollback alarm is armed. If the agent confirms the new build
 * (POST /selfupdate/confirm) the alarm is cancelled. If it never confirms - because
 * the new build cannot serve requests at all - the alarm fires, reinstalls
 * last-good.apk and the channel comes back.
 */
public final class Rollback {

    private static final String TAG = "ADK";
    private static final String PREFS = "adk_rollback";
    private static final String K_PENDING = "pending";
    private static final String K_DEADLINE = "deadline";
    private static final String K_REASON = "reason";
    private static final int REQ = 4711;
    private static final int REQ_RESTART = 4712;
    private static final String K_RESTART = "restart_at";

    public static final String LAST_GOOD = "last-good.apk";

    private Rollback() {}

    public static File lastGoodFile(Context c) {
        return new File(c.getFilesDir(), LAST_GOOD);
    }

    public static String lastGoodPath(Context c) {
        File f = lastGoodFile(c);
        return f.isFile() ? f.getAbsolutePath() : "";
    }

    /** Copy the currently installed APK aside; returns its path ("" on failure). */
    public static String backupCurrent(Context c) {
        File src = new File(c.getPackageCodePath());
        File dst = lastGoodFile(c);
        FileInputStream in = null;
        FileOutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst, false);
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            Log.i(TAG, "backed up current build to " + dst + " (" + dst.length() + " bytes)");
            return dst.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "cannot back up current build: " + t);
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }

    public static void schedule(Context c, long delayMs, String backupPath) {
        long deadline = System.currentTimeMillis() + delayMs;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_PENDING, true)
                .putLong(K_DEADLINE, deadline)
                .putString(K_REASON, backupPath == null ? "" : backupPath)
                .apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.setExact(AlarmManager.RTC_WAKEUP, deadline, alarmIntent(c));
            Log.i(TAG, "rollback armed for " + Api.iso(deadline));
        } catch (Throwable t) {
            Log.w(TAG, "cannot arm rollback alarm: " + t);
        }
    }

    /**
     * Bring the channel back after a self-update.
     *
     * Replacing a running package kills this process and nothing restarts it, so
     * without this alarm a remote self-update would end with the car unreachable -
     * the exact failure the recursive loop must not have. The alarm is held by the
     * system, so it fires even though the app that set it is gone, and the receiver
     * starts the (new) build's service.
     */
    public static void scheduleRestart(Context c, long delayMs) {
        long at = System.currentTimeMillis() + delayMs;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(K_RESTART, at).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            am.setExact(AlarmManager.RTC_WAKEUP, at, restartIntent(c));
            Log.i(TAG, "self-restart armed for " + Api.iso(at));
        } catch (Throwable t) {
            Log.w(TAG, "cannot arm the self-restart alarm: " + t);
        }
    }

    public static void confirm(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(K_PENDING, false).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            try { am.cancel(alarmIntent(c)); } catch (Throwable ignored) { }
        }
        Log.i(TAG, "rollback cancelled - update confirmed");
    }

    public static boolean pending(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_PENDING, false);
    }

    public static long deadline(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(K_DEADLINE, 0);
    }

    /** Called on service start: if an update was never confirmed, roll back now. */
    public static void checkOnStart(final Context c) {
        if (!pending(c)) return;
        long deadline = deadline(c);
        if (System.currentTimeMillis() < deadline) {
            Log.i(TAG, "rollback pending until " + Api.iso(deadline) + "; waiting for /selfupdate/confirm");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                Log.w(TAG, "unconfirmed update - rolling back");
                rollbackNow(c);
            }
        }, "adk-rollback").start();
    }

    /** Reinstall last-good.apk; returns pm output. */
    public static String rollbackNow(Context c) {
        File good = lastGoodFile(c);
        if (!good.isFile()) {
            confirm(c);
            return "no last-good build stored";
        }
        Shell.Result r = Shell.sh("pm install -r -d " + Shell.quote(good.getAbsolutePath()), 300_000);
        String out = r.combined().trim();
        if (out.contains("Success")) {
            confirm(c);
            Log.i(TAG, "rolled back to last-good build");
        } else {
            Log.w(TAG, "rollback failed: " + out);
        }
        return out;
    }

    private static PendingIntent restartIntent(Context c) {
        Intent i = new Intent(c, RollbackReceiver.class);
        i.setAction("com.lukeet.agenticdevkit.RESTART");
        return PendingIntent.getBroadcast(c, REQ_RESTART, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent alarmIntent(Context c) {
        Intent i = new Intent(c, RollbackReceiver.class);
        i.setAction("com.lukeet.agenticdevkit.ROLLBACK");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        return PendingIntent.getBroadcast(c, REQ, i, flags);
    }
}

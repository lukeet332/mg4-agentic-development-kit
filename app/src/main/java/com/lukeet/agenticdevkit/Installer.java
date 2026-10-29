package com.lukeet.agenticdevkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * APK installation through the PackageInstaller session API.
 *
 * `pm install` does not work from this app: installd refuses to read the APK out
 * of the app's cache directory (SELinux: system_app_data_file), which the rig
 * showed as "Unknown class fileSystem server has no access to read file context".
 * A session streams the bytes over binder instead, and because the app holds
 * INSTALL_PACKAGES (platform signature + android.uid.system) the install is silent.
 */
public final class Installer {

    private static final String TAG = "ADK";
    private static final String ACTION = "com.lukeet.agenticdevkit.INSTALL_RESULT";
    /** PackageManager.INSTALL_INTERNAL (hidden). */
    private static final int INSTALL_INTERNAL = 0x00000010;

    private Installer() {}

    /** Returns pm-style output: "Success" or "Failure [reason]". */
    public static String install(Context ctx, File apk, boolean grant, boolean downgrade, int userId) {
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.Session session = null;
        int sessionId = -1;
        try {
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setSize(apk.length());
            if (userId >= 0) {
                try {
                    Method m = params.getClass().getMethod("setInstallLocation", int.class);
                    m.invoke(params, 0); // auto
                } catch (Throwable ignored) { }
            }
            if (grant) {
                // hidden on API 28, but present: grants every runtime permission at install
                try {
                    Method m = params.getClass().getMethod("setGrantedRuntimePermissions", boolean.class);
                    m.invoke(params, true);
                } catch (Throwable ignored) { }
            }
            if (downgrade) {
                try {
                    Method m = params.getClass().getMethod("setAllowDowngrade", boolean.class);
                    m.invoke(params, true);
                } catch (Throwable ignored) { }
            }
            // The MG4 refuses "non-system apps on internal storage" unless the install says
            // internal explicitly (what pm install -f does); without it the session never opens.
            try {
                java.lang.reflect.Field f = params.getClass().getField("installFlags");
                f.setInt(params, f.getInt(params) | INSTALL_INTERNAL);
            } catch (Throwable t) {
                Log.w(TAG, "could not request an internal install: " + t);
            }
            sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            OutputStream out = session.openWrite("base.apk", 0, apk.length());
            FileInputStream in = new FileInputStream(apk);
            try {
                byte[] buf = new byte[1 << 16];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                }
                session.fsync(out);
                Log.i(TAG, "streamed " + total + " bytes into install session " + sessionId);
            } finally {
                try { in.close(); } catch (Throwable ignored) { }
                try { out.close(); } catch (Throwable ignored) { }
            }

            final CountDownLatch done = new CountDownLatch(1);
            final String[] result = new String[]{"Failure [no result from the installer]"};
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
                    String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                    switch (status) {
                        case PackageInstaller.STATUS_SUCCESS:
                            result[0] = "Success";
                            break;
                        case PackageInstaller.STATUS_PENDING_USER_ACTION:
                            result[0] = "Failure [INSTALL_FAILED_USER_ACTION_REQUIRED] " + message;
                            break;
                        default:
                            result[0] = "Failure [" + (message == null ? "status " + status : message) + "]";
                    }
                    done.countDown();
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                ctx.registerReceiver(receiver, filter);
            }
            try {
                Intent callback = new Intent(ACTION).setPackage(ctx.getPackageName());
                int flags = PendingIntentFlags.mutable();
                android.app.PendingIntent pending =
                        android.app.PendingIntent.getBroadcast(ctx, sessionId, callback, flags);
                session.commit(pending.getIntentSender());
                if (!done.await(240, TimeUnit.SECONDS)) {
                    result[0] = "Failure [timed out waiting for the installer]";
                }
            } finally {
                try { ctx.unregisterReceiver(receiver); } catch (Throwable ignored) { }
            }
            return result[0];
        } catch (Throwable t) {
            Log.w(TAG, "session install failed: " + t);
            return "Failure [" + t + "]";
        } finally {
            try { if (session != null) session.close(); } catch (Throwable ignored) { }
            if (sessionId >= 0) {
                try { installer.abandonSession(sessionId); } catch (Throwable ignored) { }
            }
        }
    }

    /** PendingIntent mutability flags differ across API levels. */
    static final class PendingIntentFlags {
        static int mutable() {
            int flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= 1 << 25; // FLAG_MUTABLE
            return flags;
        }
    }
}

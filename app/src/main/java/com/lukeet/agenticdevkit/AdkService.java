package com.lukeet.agenticdevkit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * Foreground service that owns the HTTP channel. Kept alive by the notification
 * (Android 8+ rules) and restarted on boot by {@link BootReceiver}.
 *
 * Can be reconfigured from adb without touching the UI:
 *   adb shell am start-foreground-service -n com.lukeet.agenticdevkit/.AdkService \
 *       -e port 9000 -e token mynewtoken
 */
public final class AdkService extends Service {

    private static final String TAG = "ADK";
    private static final String CHANNEL = "adk";
    private static final int NOTIF_ID = 4711;

    public static final String ACTION_START = "com.lukeet.agenticdevkit.START";
    public static final String ACTION_STOP = "com.lukeet.agenticdevkit.STOP";
    public static final String ACTION_RESTART = "com.lukeet.agenticdevkit.RESTART";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_TOKEN = "token";

    private static volatile AdkService instance;
    private HttpServer server;
    private Thread flusher;

    /* ------------------------------------------------------------------ control */

    public static boolean isRunning() {
        return instance != null && instance.server != null && instance.server.running();
    }

    public static int runningPort() {
        AdkService s = instance;
        return s == null || s.server == null ? -1 : s.server.port();
    }

    public static HttpServer server() {
        AdkService s = instance;
        return s == null ? null : s.server;
    }

    public static void start(Context c) {
        Intent i = new Intent(c, AdkService.class);
        i.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stopChannel(Context c) {
        Intent i = new Intent(c, AdkService.class);
        i.setAction(ACTION_STOP);
        try { c.startService(i); } catch (Throwable t) { Log.w(TAG, "stop failed: " + t); }
    }

    public static void restartChannel(Context c) {
        Intent i = new Intent(c, AdkService.class);
        i.setAction(ACTION_RESTART);
        try { c.startService(i); } catch (Throwable t) { Log.w(TAG, "restart failed: " + t); }
    }

    /* ----------------------------------------------------------------- lifecycle */

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Pairing.init(this);
        TokenStore.mirror(this);
        AdkAccessibilityService.ensureEnabled(this);
        Rollback.checkOnStart(this);
        startForeground(NOTIF_ID, notification());
        startChannel(Config.port(this));
        flusher = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        Thread.sleep(15_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    Pairing.flush(AdkService.this);
                    updateNotification();
                }
            }
        }, "adk-flusher");
        flusher.setDaemon(true);
        flusher.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (intent != null) {
            String port = intent.getStringExtra(EXTRA_PORT);
            if (port != null) {
                try {
                    int p = Integer.parseInt(port.trim());
                    if (p > 0 && p < 65536) {
                        Config.setPort(this, p);
                        Log.i(TAG, "port set to " + p + " from intent");
                        restartChannelInternal();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "bad port extra: " + port);
                }
            }
            String token = intent.getStringExtra(EXTRA_TOKEN);
            if (token != null && token.trim().length() >= 8) {
                Config.prefs(this).edit().putString(Config.KEY_TOKEN, token.trim()).apply();
                TokenStore.mirror(this);
                Log.i(TAG, "master token replaced from intent");
            }
        }
        if (ACTION_STOP.equals(action)) {
            stopChannelInternal();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            restartChannelInternal();
            return START_STICKY;
        }
        if (server == null || !server.running()) {
            startChannel(Config.port(this));
        }
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopChannelInternal();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* -------------------------------------------------------------------- server */

    private synchronized void startChannel(int port) {
        if (server != null && server.running()) return;
        try {
            server = new HttpServer(Config.bind(this), port);
            new Api(this, server).register();
            server.start();
            Log.i(TAG, "channel up on " + Config.bind(this) + ":" + port);
            autoConfirmIfHealthy();
        } catch (Throwable t) {
            server = null;
            Log.e(TAG, "cannot start channel on port " + port + ": " + t);
        }
        updateNotification();
    }

    /**
     * A self-update arms a rollback in case the new build cannot serve requests.
     * Nobody should have to confirm that by hand: if this build is up and serving
     * for {@link #AUTO_CONFIRM_MS}, it is by definition healthy, so the rollback is
     * cancelled automatically. A build that crashes or never binds the port is not
     * around to run this, and the alarm reverts it.
     */
    private void autoConfirmIfHealthy() {
        if (!Rollback.pending(this)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try { Thread.sleep(AUTO_CONFIRM_MS); } catch (InterruptedException e) { return; }
                if (server != null && server.running() && server.served() > 0) {
                    Log.i(TAG, "new build is serving requests - auto-confirming the update");
                    Rollback.confirm(AdkService.this);
                }
            }
        }, "adk-autoconfirm").start();
    }

    /** How long a fresh build must serve before its rollback is cancelled. */
    private static final long AUTO_CONFIRM_MS = 45_000L;

    private synchronized void stopChannelInternal() {
        if (server != null) {
            server.stop();
            server = null;
        }
        ShellSession.closeAll();
        Forwarder.stopAll();
    }

    private synchronized void restartChannelInternal() {
        stopChannelInternal();
        startChannel(Config.port(this));
    }

    /* -------------------------------------------------------------- notification */

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, notification());
        } catch (Throwable t) {
            Log.w(TAG, "notification update failed: " + t);
        }
    }

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        int port = server == null ? Config.port(this) : server.port();
        String text = (server != null && server.running())
                ? "port " + port + " · " + Net.primaryAddress() + " · paired " + Pairing.clients(this).size()
                : "channel stopped";
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_adk)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}

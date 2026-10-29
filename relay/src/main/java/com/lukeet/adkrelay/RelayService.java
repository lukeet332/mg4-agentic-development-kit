package com.lukeet.adkrelay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/** Keeps the relay running in the foreground while the phone is in the car. */
public final class RelayService extends Service {
    private static final String CHANNEL = "relay";
    private static volatile Relay relay;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            notifyStatus();
            handler.postDelayed(this, 5_000);
        }
    };

    static Relay relay() { return relay; }

    static void start(Context c) {
        Intent i = new Intent(c, RelayService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    private DirectForwarder direct;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, notification("starting"));
        if (relay == null) {
            Relay r = new Relay();
            try {
                r.start();
                relay = r;
                direct = new DirectForwarder(this);
                direct.start();
            } catch (Throwable t) {
                Log.e("ADKRelay", "cannot start: " + t);
                startForeground(1, notification("cannot start: " + t.getMessage()));
                return START_STICKY;
            }
        }
        if (wakeLock == null) {
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "adkrelay:relay");
            wakeLock.acquire();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        if (relay != null) relay.stop();
        relay = null;
        if (direct != null) direct.stop();
        direct = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void notifyStatus() {
        Relay r = relay;
        String text = r == null ? "stopped" : r.idleCarConnections() + " car link(s) ready · " + r.served + " relayed · " + r.lastEvent;
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, notification(text));
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "ADK Relay", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("ADK Relay")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(open)
                .build();
    }
}

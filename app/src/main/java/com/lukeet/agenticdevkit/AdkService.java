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
        startBootLog();
        startChannel(Config.port(this));
        reverseTunnel = new ReverseTunnel(Config.port(this));
        reverseTunnel.start();
        flusher = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        Thread.sleep(15_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    Pairing.flush(AdkService.this);
                    // Watchdog: the channel is the only way in, so a dead listener must not stay dead.
                    if (instance == AdkService.this && (server == null || !server.running())) {
                        Log.w(TAG, "watchdog: channel not listening - restarting it");
                        restartChannelInternal();
                    }
                    try { wifiWatchdog.check(AdkService.this); } catch (Throwable t) { Log.w(TAG, "wifi watchdog: " + t); }
                    rebindAccessibilityIfDropped();
                    pruneBootLogs();
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
        if (reverseTunnel != null) reverseTunnel.stop();
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

    private static Process bootLog;
    private ReverseTunnel reverseTunnel;
    private final WifiWatchdog wifiWatchdog = new WifiWatchdog();
    private static final java.io.File LOG_DIR = new java.io.File("/sdcard/adk/logs");
    /** This logcat ignores -n, so the cap is enforced here: oldest files go first, never this boot's. */
    private static final long LOG_DIR_CAP_BYTES = 1536L * 1024 * 1024;
    private static String bootStamp;

    private long lastAccessibilityRebind;

    /**
     * After a self-update the MG4 left the service enabled in settings but unbound, so nothing
     * caught the drawer tap. Rewriting the setting makes the system bind it again.
     */
    private void rebindAccessibilityIfDropped() {
        if (AdkAccessibilityService.ready()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastAccessibilityRebind < 60_000) return;
        lastAccessibilityRebind = now;
        try {
            String flat = new android.content.ComponentName(this, AdkAccessibilityService.class).flattenToString();
            String current = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            StringBuilder others = new StringBuilder();
            if (current != null) {
                for (String s : current.split(":")) {
                    if (s.isEmpty() || s.equals(flat)) continue;
                    if (others.length() > 0) others.append(':');
                    others.append(s);
                }
            }
            android.provider.Settings.Secure.putString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.toString());
            android.os.SystemClock.sleep(500);
            AdkAccessibilityService.ensureEnabled(this);
            Log.w(TAG, "accessibility service was not bound - rewrote the setting to rebind it");
        } catch (Throwable t) {
            Log.w(TAG, "accessibility rebind failed: " + t);
        }
    }

    private static void pruneBootLogs() {
        java.io.File[] files = LOG_DIR.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, new java.util.Comparator<java.io.File>() {
            @Override public int compare(java.io.File a, java.io.File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        long total = 0;
        for (java.io.File f : files) total += f.length();
        // Raw logcat files go first; the small per-boot evidence (tuner, kernel, usb, context) only if still over.
        for (boolean evidence : new boolean[] {false, true}) {
            for (int i = 0; i < files.length && total > LOG_DIR_CAP_BYTES; i++) {
                String name = files[i].getName();
                if (bootStamp != null && name.startsWith("boot-" + bootStamp)) continue;
                if (name.endsWith(".txt") != evidence || !files[i].exists()) continue;
                total -= files[i].length();
                files[i].delete();
            }
        }
    }

    /**
     * Streams every log buffer, backlog included, to /sdcard/adk/logs for this boot, so a boot
     * that leaves the channel unreachable still leaves evidence. Kept within LOG_DIR_CAP_BYTES.
     */
    private static synchronized void startBootLog() {
        if (bootLog != null) return;
        try {
            java.io.File dir = new java.io.File("/sdcard/adk/logs");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("cannot create " + dir);
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            bootStamp = stamp;
            // Everything at info and above: the context to explain an outage is rarely in the tags we guessed.
            bootLog = new ProcessBuilder("logcat", "-b", "main,system,events,crash", "-v", "threadtime",
                    "-f", new java.io.File(dir, "boot-" + stamp + ".log").getPath(), "-r", "8192", "*:I", "ADK:V", "OPENHU:I", "WifiService:I", "WifiStateMachine:I", "WificondScannerImpl:V",
                    "ExpandPageView:I", "ActivityManager:I", "am_proc_start:I", "am_proc_died:I", "am_kill:I",
                    "am_crash:I", "am_user_state_changed:I", "boot_progress_start:I", "boot_progress_enable_screen:I",
                    "SoftApManager:I", "wificond:W", "wpa_supplicant:S",
                    // DAB tuner: an STM32 on internal USB (0483:5740) that the radio HAL must find at boot.
                    "radio_hw_comm:I", "radio_hw:I", "BroadcastRadioDefault.dab:I", "BroadcastRadioService.Dab:I",
                    "RadioService:I", "UsbHostManager:I", "UsbDeviceManager:I")
                    .redirectErrorStream(true).start();
            Log.i(TAG, "boot log -> " + dir + "/boot-" + stamp + ".log");
            snapshotUsbDevices(new java.io.File(dir, "boot-" + stamp + "-usb.txt"));
            startContextTimeline(new java.io.File(dir, "boot-" + stamp + "-context.txt"));
            TunerRecorder.start(instance.getApplicationContext(), new java.io.File(dir, "boot-" + stamp + "-tuner.txt"),
                    new java.io.File(dir, "boot-" + stamp + "-kernel.txt"));
        } catch (Throwable t) {
            Log.w(TAG, "boot log not started: " + t);
        }
    }

    private static Process contextTimeline;

    /** Runs the context script (files/context.sh if present, else the bundled one) for this boot. */
    private static void startContextTimeline(java.io.File out) {
        try {
            Context app = instance.getApplicationContext();
            java.io.File script = new java.io.File(app.getFilesDir(), "context.sh");
            if (!script.isFile()) {
                script = new java.io.File(app.getFilesDir(), "context-default.sh");
                try (java.io.InputStream in = app.getAssets().open("context.sh");
                     java.io.FileOutputStream os = new java.io.FileOutputStream(script)) {
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) os.write(buf, 0, n);
                }
            }
            contextTimeline = new ProcessBuilder("sh", script.getPath(), out.getPath(), "30").redirectErrorStream(true).start();
            Log.i(TAG, "context timeline -> " + out + " (" + script.getName() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "context timeline not started: " + t);
        }
    }

    /** What is on the USB bus shortly after boot, when the radio HAL looks for its tuner. */
    private static void snapshotUsbDevices(final java.io.File out) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                long start = android.os.SystemClock.elapsedRealtime();
                for (long at : new long[] {5_000, 30_000, 120_000}) {
                    try { Thread.sleep(Math.max(0, start + at - android.os.SystemClock.elapsedRealtime())); } catch (InterruptedException e) { return; }
                    StringBuilder b = new StringBuilder("--- uptime " + android.os.SystemClock.elapsedRealtime() / 1000 + "s\n");
                    java.io.File[] devices = new java.io.File("/sys/bus/usb/devices").listFiles();
                    if (devices != null) for (java.io.File d : devices) {
                        String vid = readSmall(new java.io.File(d, "idVendor"));
                        if (vid == null) continue;
                        b.append(d.getName()).append(' ').append(vid).append(':').append(readSmall(new java.io.File(d, "idProduct")))
                         .append(' ').append(readSmall(new java.io.File(d, "product"))).append('\n');
                    }
                    try (java.io.FileWriter w = new java.io.FileWriter(out, true)) { w.write(b.toString()); } catch (java.io.IOException e) { Log.w(TAG, "usb snapshot: " + e); }
                }
            }
        }, "adk-usb-snapshot");
        t.setDaemon(true);
        t.start();
    }

    private static String readSmall(java.io.File f) {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) { return r.readLine(); } catch (java.io.IOException e) { return null; }
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

package com.lukeet.agenticdevkit;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Per-boot DAB tuner flight recorder (boot-STAMP-tuner.txt): every tuner command, power mode and
 * diag change, a summary a minute with the GPS fix, and a snapshot (recent radio lines, hub port
 * status, USB devices) when the tuner stops answering or leaves USB. Read-only towards the car.
 */
final class TunerRecorder {

    private static final String TAG = "ADK";
    private static final long SUMMARY_EVERY_MS = 60_000;
    private static final long[] HUB_CHECKS_AFTER_START_MS = {5_000, 30_000};

    private static boolean started;

    private TunerRecorder() {}

    /** Where the vendor's bdklogd (root, started by persist.sys.bdklogd=1) keeps its rolling kernel log. */
    private static final File VENDOR_DMESG_DIR = new File("/storage/emulated/0/logs/offlinelogs/dmesg");

    static synchronized void start(final Context ctx, final File out, final File kernelOut) {
        if (started) return;
        started = true;
        final TunerHealth health = new TunerHealth();
        final SimpleDateFormat clock = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Process p = new ProcessBuilder("logcat", "-b", "main,system", "-v", "threadtime", "-T", "1",
                            "radio_hw:I", "radio_hw_comm:I", "radio_hw_resp:I", "radio_hw_req:I", "radio_hw_engine:I",
                            "radio_hw_device:I", "DABDBUS:I", "CAR.POWER:I", "vold:I", "*:S")
                            .redirectErrorStream(true).start();
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    for (String line; (line = r.readLine()) != null; ) {
                        List<String> lines;
                        synchronized (health) { lines = health.onLine(line, SystemClock.elapsedRealtime()); }
                        write(out, clock, lines);
                    }
                    write(out, clock, java.util.Collections.singletonList("RECORDER logcat ended"));
                } catch (Throwable t) {
                    Log.w(TAG, "tuner recorder logcat: " + t);
                }
            }
        }, "adk-tuner-logcat");

        Thread ticker = new Thread(new Runnable() {
            @Override public void run() {
                long start = SystemClock.elapsedRealtime();
                long nextSummary = start + SUMMARY_EVERY_MS;
                int hubCheck = 0;
                String lastIdentity = null;
                write(out, clock, java.util.Collections.singletonList("RECORDER started, uptime " + start / 1000 + "s"));
                while (true) {
                    try { Thread.sleep(1_000); } catch (InterruptedException e) { return; }
                    long now = SystemClock.elapsedRealtime();
                    File tuner = tunerDevice();
                    boolean onUsb = tuner != null;
                    long urbnum = -1;
                    if (onUsb) {
                        try { urbnum = Long.parseLong(read(new File(tuner, "urbnum")).trim()); } catch (Exception ignored) {}
                        String id = identity(tuner);
                        if (!id.equals(lastIdentity)) {
                            lastIdentity = id;
                            write(out, clock, java.util.Collections.singletonList("IDENTITY " + id));
                        }
                    }
                    List<String> events;
                    boolean snapshot;
                    String summary = null;
                    synchronized (health) {
                        events = health.onTick(now, onUsb, urbnum);
                        snapshot = health.consumeSnapshotRequest();
                        if (now >= nextSummary) {
                            summary = health.summary(now, onUsb) + " " + location(ctx);
                            nextSummary = now + SUMMARY_EVERY_MS;
                        }
                    }
                    write(out, clock, events);
                    if (summary != null) write(out, clock, java.util.Collections.singletonList(summary));
                    if (hubCheck < HUB_CHECKS_AFTER_START_MS.length && now - start >= HUB_CHECKS_AFTER_START_MS[hubCheck]) {
                        hubCheck++;
                        write(out, clock, java.util.Collections.singletonList("HUB " + hubStatus(ctx)));
                    }
                    if (snapshot) snapshot(ctx, out, clock, health);
                }
            }
        }, "adk-tuner-ticker");

        Thread kernel = new Thread(new Runnable() {
            @Override public void run() { mirrorKernelLog(out, kernelOut, clock); }
        }, "adk-kernel-mirror");
        kernel.setDaemon(true);
        kernel.start();

        reader.setDaemon(true);
        ticker.setDaemon(true);
        reader.start();
        ticker.start();
        Log.i(TAG, "tuner recorder -> " + out);
    }

    /** Copies the vendor kernel log into this boot's kernel file every second, and its USB lines into the tuner file. */
    private static void mirrorKernelLog(File tunerOut, File kernelOut, SimpleDateFormat clock) {
        try {
            if (!"1".equals(exec("getprop persist.sys.bdklogd"))) {
                exec("setprop persist.sys.bdklogd 1");
                write(tunerOut, clock, java.util.Collections.singletonList("KERNEL enabled the vendor kernel log (persist.sys.bdklogd=1)"));
            }
        } catch (Throwable t) {
            write(tunerOut, clock, java.util.Collections.singletonList("KERNEL could not enable the vendor kernel log: " + t));
        }
        KernelLogMirror mirror = new KernelLogMirror();
        while (true) {
            // The vendor file holds only ~20 records, a few seconds under audit spam.
            try { Thread.sleep(1_000); } catch (InterruptedException e) { return; }
            File[] files = VENDOR_DMESG_DIR.listFiles();
            if (files == null || files.length == 0) continue;
            File newest = files[0];
            for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f;
            List<String> lines = new java.util.ArrayList<>();
            try (BufferedReader r = new BufferedReader(new java.io.FileReader(newest))) {
                for (String l; (l = r.readLine()) != null; ) lines.add(l);
            } catch (IOException e) {
                continue;
            }
            List<String> fresh = mirror.take(lines);
            if (fresh.isEmpty()) continue;
            List<String> usb = new java.util.ArrayList<>();
            for (String l : fresh) if (KernelLogMirror.isUsb(l)) usb.add("KERNEL " + l);
            write(kernelOut, clock, fresh);
            write(tunerOut, clock, usb);
        }
    }

    private static String exec(String cmd) throws IOException {
        Process p = Runtime.getRuntime().exec(cmd);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String l = r.readLine();
            return l == null ? "" : l.trim();
        }
    }

    private static void snapshot(Context ctx, File out, SimpleDateFormat clock, TunerHealth health) {
        StringBuilder b = new StringBuilder("SNAPSHOT begin, uptime ").append(SystemClock.elapsedRealtime() / 1000).append("s\n");
        b.append("HUB ").append(hubStatus(ctx)).append('\n');
        b.append("USB ").append(usbDevices()).append('\n');
        b.append("LOCATION ").append(location(ctx)).append('\n');
        List<String> recent;
        synchronized (health) { recent = health.recentLines(); }
        for (String l : recent) b.append("  | ").append(l).append('\n');
        b.append("SNAPSHOT end");
        write(out, clock, java.util.Collections.singletonList(b.toString()));
    }

    private static File tunerDevice() {
        File[] devices = new File("/sys/bus/usb/devices").listFiles();
        if (devices == null) return null;
        for (File d : devices) {
            if ("0483".equals(read(new File(d, "idVendor"))) && "5740".equals(read(new File(d, "idProduct")))) return d;
        }
        return null;
    }

    /** What the tuner says it is: bcdDevice is its firmware revision, serial most likely the STM32 unique ID. */
    private static String identity(File d) {
        StringBuilder b = new StringBuilder(d.getName());
        for (String attr : new String[] {"manufacturer", "product", "serial", "bcdDevice", "version", "speed", "bMaxPower"}) {
            b.append(' ').append(attr).append('=').append(read(new File(d, attr)));
        }
        return b.toString();
    }

    private static String usbDevices() {
        StringBuilder b = new StringBuilder();
        File[] devices = new File("/sys/bus/usb/devices").listFiles();
        if (devices != null) for (File d : devices) {
            String vid = read(new File(d, "idVendor"));
            if (vid != null) b.append(d.getName()).append('=').append(vid).append(':').append(read(new File(d, "idProduct"))).append(' ');
        }
        return b.toString().trim();
    }

    private static String hubStatus(Context ctx) {
        try { return UsbHubStatus.read(ctx).toString(); } catch (Throwable t) { return "error " + t; }
    }

    /** Last known fixes only: nothing is requested, so the recorder never wakes the GPS. */
    private static String location(Context ctx) {
        try {
            LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : new String[] {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best == null) return "loc=none";
            long ageS = (System.currentTimeMillis() - best.getTime()) / 1000;
            return String.format(Locale.US, "loc=%.5f,%.5f speed=%.0fkmh acc=%.0fm age=%ds",
                    best.getLatitude(), best.getLongitude(), best.getSpeed() * 3.6, best.getAccuracy(), ageS);
        } catch (Throwable t) {
            return "loc=error " + t.getClass().getSimpleName();
        }
    }

    private static synchronized void write(File out, SimpleDateFormat clock, List<String> lines) {
        if (lines.isEmpty()) return;
        String stamp = clock.format(new Date());
        try (FileWriter w = new FileWriter(out, true)) {
            for (String l : lines) w.write(stamp + " " + l + "\n");
        } catch (IOException e) {
            Log.w(TAG, "tuner recorder write: " + e);
        }
    }

    private static String read(File f) {
        try (BufferedReader r = new BufferedReader(new java.io.FileReader(f))) { return r.readLine(); } catch (IOException e) { return null; }
    }
}

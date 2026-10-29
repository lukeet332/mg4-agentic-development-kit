package com.lukeet.agenticdevkit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the DAB tuner's health from the radio HAL's logcat lines. On 20 Oct the tuner stopped
 * answering the HAL's 1 Hz 0xF6 poll while staying on USB, then failed to enumerate at the next boot;
 * this turns that into timestamped events. Pure logic: TunerRecorder feeds it and writes what it returns.
 */
final class TunerHealth {

    static final long SILENT_AFTER_MS = 5_000;
    /** The HAL counts as polling while its last poll is this recent. */
    private static final long POLLING_WITHIN_MS = 3_000;
    static final int RECENT_LINES = 400;
    /** A send that blocks through the HAL's 3 x 1 s retries spreads the 1 Hz poll to ~4 s. */
    private static final long POLL_BLOCKED_MS = 2_500;
    private static final long POLL_IDLE_MS = 10_000;
    private static final long POLL_NORMAL_MS = 1_500;
    /** Uncalibrated: the summary's urbPerS shows what is normal. */
    static final long URB_STORM_PER_S = 200;

    private long lastReplyMs = -1;
    private long lastPollMs = -1;
    private boolean silent;
    private Boolean usbPresent;
    private String powerMode;
    private String lastDiag;
    private String lastUpgrade;
    private boolean notAccepting;
    private boolean urbStorm;
    private long lastUrbnum = -1, lastUrbMs, urbPerS = -1;
    private boolean snapshotRequested;
    private int polls, replies, commands, driverErrors;
    private final java.util.Set<String> driverErrorsSeen = new java.util.HashSet<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    List<String> onLine(String line, long nowMs) {
        List<String> out = new ArrayList<>(1);
        if (line.contains(" radio_hw") || line.contains(" DABDBUS")) remember(line);
        if (line.contains("radio_hw_resp")) {
            replies++;
            lastReplyMs = nowMs;
            if (silent) {
                silent = false;
                out.add("EVENT TUNER_ANSWERING again");
            }
        } else if (line.contains("Request#f6")) {
            polls++;
            long gap = lastPollMs < 0 ? -1 : nowMs - lastPollMs;
            if (!notAccepting && gap >= POLL_BLOCKED_MS && gap < POLL_IDLE_MS) {
                notAccepting = true;
                out.add("EVENT TUNER_NOT_ACCEPTING polls " + (gap / 100) / 10.0 + "s apart, sends are blocking");
            } else if (notAccepting && gap >= 0 && gap < POLL_NORMAL_MS) {
                notAccepting = false;
                out.add("EVENT TUNER_ACCEPTING polls back to 1 Hz");
            }
            lastPollMs = nowMs;
        } else if (line.contains("Request#")) {
            commands++;
            out.add("CMD " + message(line));
        } else if (line.contains("PMS_PWR_MODE=")) {
            String mode = line.substring(line.indexOf("PMS_PWR_MODE=") + 13).trim();
            if (!mode.equals(powerMode)) {
                out.add("EVENT POWER_MODE " + (powerMode == null ? "?" : powerMode) + " -> " + mode);
                powerMode = mode;
            }
        } else if (line.contains("currentDiag")) {
            String diag = message(line);
            if (!diag.equals(lastDiag)) {
                out.add("DIAG " + diag);
                lastDiag = diag;
            }
        } else if (line.contains("upgrade_signal.status=")) {
            String status = message(line);
            if (!status.equals(lastUpgrade)) {
                out.add("UPGRADE " + status);
                lastUpgrade = status;
            }
        } else if (isDriverError(line)) {
            driverErrors++;
            String msg = message(line);
            if (driverErrorsSeen.add(msg)) out.add("DRIVER " + msg);
        }
        return out;
    }

    /** urbnum: the tuner's sysfs URB counter, or -1 when it is not on USB. */
    List<String> onTick(long nowMs, boolean tunerOnUsb, long urbnum) {
        List<String> out = new ArrayList<>(1);
        if (urbnum >= 0 && lastUrbnum >= 0 && nowMs > lastUrbMs) {
            urbPerS = (urbnum - lastUrbnum) * 1000 / (nowMs - lastUrbMs);
            if (!urbStorm && urbPerS > URB_STORM_PER_S) {
                urbStorm = true;
                snapshotRequested = true;
                out.add("EVENT TUNER_URB_STORM " + urbPerS + " USB requests/s");
            } else if (urbStorm && urbPerS <= URB_STORM_PER_S) {
                urbStorm = false;
            }
        }
        lastUrbnum = urbnum;
        lastUrbMs = nowMs;
        if (usbPresent == null ? !tunerOnUsb : usbPresent != tunerOnUsb) {
            out.add(tunerOnUsb ? "EVENT TUNER_USB_BACK" : "EVENT TUNER_USB_GONE");
            if (!tunerOnUsb) snapshotRequested = true;
        }
        usbPresent = tunerOnUsb;
        boolean polling = lastPollMs >= 0 && nowMs - lastPollMs <= POLLING_WITHIN_MS;
        long sinceReply = lastReplyMs < 0 ? -1 : nowMs - lastReplyMs;
        if (!silent && polling && (lastReplyMs < 0 || sinceReply > SILENT_AFTER_MS)) {
            silent = true;
            snapshotRequested = true;
            out.add("EVENT TUNER_SILENT polls go unanswered, last reply "
                    + (sinceReply < 0 ? "never" : (sinceReply / 1000) + "s ago"));
        }
        return out;
    }

    /** One line for the window since the last summary, which it then resets. */
    String summary(long nowMs, boolean tunerOnUsb) {
        String s = "SUMMARY polls=" + polls + " replies=" + replies + " commands=" + commands
                + " driverErrors=" + driverErrors
                + " lastReply=" + (lastReplyMs < 0 ? "never" : ((nowMs - lastReplyMs) / 1000) + "s")
                + " usb=" + (tunerOnUsb ? "yes" : "no") + " urbPerS=" + urbPerS + " power=" + (powerMode == null ? "?" : powerMode)
                + (silent ? " SILENT" : "") + (notAccepting ? " NOT_ACCEPTING" : "");
        polls = replies = commands = driverErrors = 0;
        return s;
    }

    boolean consumeSnapshotRequest() {
        boolean r = snapshotRequested;
        snapshotRequested = false;
        return r;
    }

    List<String> recentLines() {
        return Collections.unmodifiableList(new ArrayList<>(recent));
    }

    private void remember(String line) {
        if (recent.size() == RECENT_LINES) recent.removeFirst();
        recent.addLast(line.length() > 240 ? line.substring(0, 240) : line);
    }

    private static boolean isDriverError(String line) {
        return line.contains("g_Device == NULL") || line.contains("usbDeviceRemoved")
                || line.contains("shake_hand") || line.contains("handleUsbErrorEvent");
    }

    /** The text after the threadtime "TAG: " prefix. */
    private static String message(String line) {
        int i = line.indexOf(": ", 31);
        return (i < 0 ? line : line.substring(i + 2)).trim();
    }
}

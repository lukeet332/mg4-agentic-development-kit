package com.lukeet.agenticdevkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns the vendor bdklogd's /dev/kmsg records ("prio,seq,usec,flag;message") into uptime-stamped
 * lines, keeping only records newer than the last one taken: its file is a rolling window that is
 * rewritten in place, so the same records are seen again on every read.
 */
final class KernelLogMirror {

    private long lastSeq = -1;

    List<String> take(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            int semi = line.indexOf(';');
            String[] head = semi < 0 ? null : line.substring(0, semi).split(",");
            if (head == null || head.length < 3) {
                // kmsg dictionary continuation (" SUBSYSTEM=usb") or unknown: no seq, so keep it.
                out.add(line);
                continue;
            }
            long seq, usec;
            try {
                seq = Long.parseLong(head[1]);
                usec = Long.parseLong(head[2]);
            } catch (NumberFormatException e) {
                out.add(line);
                continue;
            }
            if (seq <= lastSeq) continue;
            lastSeq = seq;
            int prio = 0;
            try { prio = Integer.parseInt(head[0]) & 7; } catch (NumberFormatException ignored) {}
            out.add(String.format(Locale.US, "[%d.%06d] <%d> %s", usec / 1_000_000, usec % 1_000_000, prio, line.substring(semi + 1)));
        }
        return out;
    }

    static boolean isUsb(String line) {
        return line.matches(".*\\b(usb|hub|xhci[-\\w]*|cdc_acm|ehci[-\\w]*)\\b.*");
    }
}

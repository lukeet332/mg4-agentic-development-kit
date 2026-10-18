package com.lukeet.agenticdevkit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Minimal multipart/form-data reader: pulls the first file part out of an
 * already-buffered body. Two passes over a temp file keeps memory flat, which
 * matters for 30 MB APK uploads on a head unit.
 */
public final class Multipart {

    private Multipart() {}

    public static String boundary(String contentType) {
        if (contentType == null) return null;
        for (String part : contentType.split(";")) {
            String p = part.trim();
            if (p.toLowerCase(Locale.US).startsWith("boundary=")) {
                String b = p.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() > 1) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    /** Extract the first file part of {@code raw} into {@code out}; returns bytes written. */
    public static long firstFile(File raw, String contentType, File out) throws IOException {
        String boundary = boundary(contentType);
        if (boundary == null) {
            // Not parseable: treat the whole body as the payload.
            return copyFile(raw, out);
        }
        byte[] marker = ("--" + boundary).getBytes("UTF-8");
        long start = indexOf(raw, marker, 0);
        if (start < 0) return copyFile(raw, out);
        long afterMarker = start + marker.length;
        long headerEnd = indexOf(raw, "\r\n\r\n".getBytes("UTF-8"), afterMarker);
        if (headerEnd < 0) return 0;
        long bodyStart = headerEnd + 4;
        long bodyEnd = indexOf(raw, ("\r\n--" + boundary).getBytes("UTF-8"), bodyStart);
        if (bodyEnd < 0) bodyEnd = raw.length();
        return copyRange(raw, bodyStart, bodyEnd, out);
    }

    /** Also expose any filename= from the part header (not needed today, kept small). */
    public static String firstFilename(File raw, String contentType) {
        String boundary = boundary(contentType);
        if (boundary == null) return null;
        try {
            byte[] marker = ("--" + boundary).getBytes("UTF-8");
            long start = indexOf(raw, marker, 0);
            if (start < 0) return null;
            long headerEnd = indexOf(raw, "\r\n\r\n".getBytes("UTF-8"), start + marker.length);
            if (headerEnd < 0) return null;
            int len = (int) (headerEnd - start);
            byte[] head = new byte[len];
            RandomAccessFile f = new RandomAccessFile(raw, "r");
            try {
                f.seek(start);
                f.readFully(head);
            } finally {
                f.close();
            }
            String headers = new String(head, "UTF-8");
            int i = headers.indexOf("filename=\"");
            if (i < 0) return null;
            int j = headers.indexOf('"', i + 10);
            if (j < 0) return null;
            return headers.substring(i + 10, j);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ scanning */

    private static long indexOf(File f, byte[] needle, long from) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            long len = raf.length();
            int n = needle.length;
            if (n == 0 || len < n) return -1;
            byte[] buf = new byte[1 << 16];
            long pos = Math.max(0, from);
            int carry = 0;
            long carryStart = pos;
            while (pos < len) {
                raf.seek(pos);
                int want = (int) Math.min(buf.length - carry, len - pos);
                if (want <= 0) break;
                raf.seek(pos);
                int read = raf.read(buf, carry, want);
                if (read <= 0) break;
                int total = carry + read;
                for (int i = 0; i + n <= total; i++) {
                    boolean hit = true;
                    for (int k = 0; k < n; k++) {
                        if (buf[i + k] != needle[k]) { hit = false; break; }
                    }
                    if (hit) return carryStart + i;
                }
                int keep = Math.min(n - 1, total);
                byte[] tail = new byte[keep];
                System.arraycopy(buf, total - keep, tail, 0, keep);
                System.arraycopy(tail, 0, buf, 0, keep);
                carryStart = pos + read - keep;
                carry = keep;
                pos += read;
            }
            return -1;
        } finally {
            raf.close();
        }
    }

    private static long copyRange(File in, long start, long end, File out) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(in, "r");
        FileOutputStream fos = new FileOutputStream(out, false);
        try {
            raf.seek(start);
            byte[] buf = new byte[1 << 16];
            long left = end - start;
            long total = 0;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                fos.write(buf, 0, n);
                left -= n;
                total += n;
            }
            return total;
        } finally {
            try { raf.close(); } catch (IOException ignored) { }
            fos.close();
        }
    }

    private static long copyFile(File in, File out) throws IOException {
        FileInputStream fis = new FileInputStream(in);
        FileOutputStream fos = new FileOutputStream(out, false);
        try {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = fis.read(buf)) > 0) {
                fos.write(buf, 0, n);
                total += n;
            }
            return total;
        } finally {
            try { fis.close(); } catch (IOException ignored) { }
            fos.close();
        }
    }
}

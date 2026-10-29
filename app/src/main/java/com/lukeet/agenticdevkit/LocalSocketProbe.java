package com.lukeet.agenticdevkit;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Connects to an init-created socket (/dev/socket/NAME), optionally sends, and returns what comes back. */
final class LocalSocketProbe {

    private static final int MAX_BYTES = 256 * 1024;

    private LocalSocketProbe() {}

    static Json.Obj probe(String name, String namespace, String send, String sendHex, int readMs) {
        Json.Obj out = new Json.Obj().put("name", name).put("namespace", namespace);
        LocalSocketAddress.Namespace ns = "abstract".equals(namespace) ? LocalSocketAddress.Namespace.ABSTRACT
                : "filesystem".equals(namespace) ? LocalSocketAddress.Namespace.FILESYSTEM
                : LocalSocketAddress.Namespace.RESERVED;
        LocalSocket socket = new LocalSocket();
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        try {
            socket.connect(new LocalSocketAddress(name, ns));
            if (sendHex != null && !sendHex.isEmpty()) socket.getOutputStream().write(hex(sendHex));
            else if (send != null && !send.isEmpty()) socket.getOutputStream().write(send.replace("\\n", "\n").replace("\\0", "\0").getBytes("UTF-8"));
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            long deadline = SystemClock.elapsedRealtime() + Math.max(100, Math.min(readMs, 30_000));
            byte[] buf = new byte[8192];
            while (got.size() < MAX_BYTES) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) break;
                socket.setSoTimeout((int) Math.max(50, left));
                int n;
                try { n = in.read(buf); } catch (java.net.SocketTimeoutException e) { break; }
                if (n < 0) { out.put("eof", true); break; }
                got.write(buf, 0, n);
            }
            out.put("ok", true);
        } catch (Throwable t) {
            out.put("ok", false).put("error", String.valueOf(t));
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
        byte[] bytes = got.toByteArray();
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 256); i++) hex.append(String.format("%02x", bytes[i] & 0xff));
        return out.put("bytes", bytes.length).put("hex_head", hex.toString()).put("text", printable(bytes));
    }

    private static String printable(byte[] b) {
        StringBuilder s = new StringBuilder(b.length);
        for (byte x : b) {
            int c = x & 0xff;
            s.append(c == '\n' || c == '\t' || (c >= 0x20 && c < 0x7f) ? (char) c : '.');
        }
        return s.toString();
    }

    private static byte[] hex(String h) {
        h = h.replaceAll("[^0-9a-fA-F]", "");
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }
}

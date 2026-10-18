package com.lukeet.agenticdevkit;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One-time pairing, modelled on wireless adb's "pair with code" flow.
 *
 * The person in the car opens a pairing window from the app; the app shows a
 * 6-digit code (and a QR-ish text form) that is valid for {@link #WINDOW_SECONDS}
 * seconds and can be used exactly once. An agent posts the code to /pair and gets
 * back a long-lived per-device token. Five wrong codes lock pairing for a minute.
 *
 * Only tokens minted here (or the master token from TokenStore) authenticate.
 */
public final class Pairing {

    private static final String TAG = "ADK";
    public static final int WINDOW_SECONDS = 120;
    public static final int MAX_ATTEMPTS = 5;
    public static final long LOCKOUT_MS = 60_000L;
    private static final String CLIENTS_FILE = "clients.json";

    public static final class Client {
        public String id = "";
        public String name = "";
        public String token = "";
        public long created;
        public long lastSeen;
        public String lastIp = "";
    }

    public static final class Status {
        public boolean open;
        public boolean locked;
        public int secondsLeft;
        public int attemptsLeft;
        public String code;      // null unless the caller is the in-car UI
        public int clients;
    }

    public static final class PairResult {
        public boolean ok;
        public String error;
        public Client client;
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Client> CLIENTS = new LinkedHashMap<>();
    private static String sCode;
    private static long sExpiresAt;
    private static long sLockedUntil;
    private static int sAttempts;
    private static boolean sLoaded;
    private static boolean sDirty;

    private Pairing() {}

    /* --------------------------------------------------------------- lifecycle */

    public static void init(Context c) {
        synchronized (LOCK) {
            if (sLoaded) return;
            load(c);
            sLoaded = true;
        }
    }

    /** Open a pairing window and mint a fresh single-use code (in-car UI action). */
    public static void open(Context c) {
        synchronized (LOCK) {
            init(c);
            sCode = TokenStore.randomDigits(6);
            sExpiresAt = System.currentTimeMillis() + WINDOW_SECONDS * 1000L;
            sAttempts = 0;
            sLockedUntil = 0;
            Log.i(TAG, "pairing window opened for " + WINDOW_SECONDS + "s");
        }
    }

    public static void close() {
        synchronized (LOCK) {
            sCode = null;
            sExpiresAt = 0;
        }
    }

    public static Status status(Context c, boolean includeCode) {
        synchronized (LOCK) {
            init(c);
            long now = System.currentTimeMillis();
            Status s = new Status();
            s.locked = now < sLockedUntil;
            s.open = !s.locked && sCode != null && now < sExpiresAt;
            s.secondsLeft = s.open ? (int) ((sExpiresAt - now + 999) / 1000) : 0;
            s.attemptsLeft = Math.max(0, MAX_ATTEMPTS - sAttempts);
            s.code = (includeCode && s.open) ? sCode : null;
            s.clients = CLIENTS.size();
            return s;
        }
    }

    /* ------------------------------------------------------------------ pairing */

    public static PairResult pair(Context c, String code, String name, String ip) {
        PairResult r = new PairResult();
        synchronized (LOCK) {
            init(c);
            long now = System.currentTimeMillis();
            if (now < sLockedUntil) {
                r.error = "pairing locked for " + ((sLockedUntil - now + 999) / 1000) + "s after too many wrong codes";
                return r;
            }
            if (sCode == null || now >= sExpiresAt) {
                sCode = null;
                r.error = "no pairing window is open; tap 'Pair a device' in the car app first";
                return r;
            }
            if (code == null || !TokenStore.equals(code.trim(), sCode)) {
                sAttempts++;
                if (sAttempts >= MAX_ATTEMPTS) {
                    sLockedUntil = now + LOCKOUT_MS;
                    sCode = null;
                    r.error = "too many wrong codes; pairing locked for " + (LOCKOUT_MS / 1000) + "s";
                } else {
                    r.error = "wrong code (" + (MAX_ATTEMPTS - sAttempts) + " attempts left)";
                }
                return r;
            }
            Client cl = new Client();
            cl.id = TokenStore.randomHex(8);
            cl.name = (name == null || name.trim().isEmpty()) ? "client-" + cl.id : name.trim();
            cl.token = TokenStore.randomHex(64);
            cl.created = now;
            cl.lastSeen = now;
            cl.lastIp = ip == null ? "" : ip;
            CLIENTS.put(cl.id, cl);
            sCode = null;          // single use
            sExpiresAt = 0;
            sAttempts = 0;
            sDirty = true;
            save(c);
            Log.i(TAG, "paired new client '" + cl.name + "' id=" + cl.id + " from " + cl.lastIp);
            r.ok = true;
            r.client = cl;
            return r;
        }
    }

    public static List<Client> clients(Context c) {
        synchronized (LOCK) {
            init(c);
            return new ArrayList<>(CLIENTS.values());
        }
    }

    public static boolean revoke(Context c, String idOrToken) {
        synchronized (LOCK) {
            init(c);
            String id = idOrToken;
            if (id != null && id.length() > 16) {
                id = idForTokenLocked(id);
            }
            if (id == null || CLIENTS.remove(id) == null) return false;
            sDirty = true;
            save(c);
            Log.i(TAG, "revoked client " + id);
            return true;
        }
    }

    /** Returns the client owning this token, or null. Also refreshes last-seen. */
    public static Client authenticate(Context c, String token, String ip) {
        if (token == null || token.isEmpty()) return null;
        synchronized (LOCK) {
            init(c);
            for (Client cl : CLIENTS.values()) {
                if (TokenStore.equals(cl.token, token)) {
                    cl.lastSeen = System.currentTimeMillis();
                    if (ip != null && !ip.isEmpty()) cl.lastIp = ip;
                    sDirty = true;
                    return cl;
                }
            }
            return null;
        }
    }

    public static void flush(Context c) {
        synchronized (LOCK) {
            if (!sDirty) return;
            save(c);
        }
    }

    private static String idForTokenLocked(String token) {
        for (Client cl : CLIENTS.values()) {
            if (TokenStore.equals(cl.token, token)) return cl.id;
        }
        return null;
    }

    /* -------------------------------------------------------------- persistence */

    private static File file(Context c) {
        return new File(c.getFilesDir(), CLIENTS_FILE);
    }

    private static void load(Context c) {
        CLIENTS.clear();
        File f = file(c);
        if (!f.isFile()) return;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            String json = new String(buf, 0, Math.max(0, n), "UTF-8");
            Map<String, Object> root = Json.parseObj(json);
            for (Object o : Json.asArr(root.get("clients"))) {
                Map<String, Object> m = Json.asObj(o);
                Client cl = new Client();
                cl.id = Json.s(m, "id", "");
                cl.name = Json.s(m, "name", "");
                cl.token = Json.s(m, "token", "");
                cl.created = Json.i(m, "created", 0);
                cl.lastSeen = Json.i(m, "last_seen", 0);
                cl.lastIp = Json.s(m, "last_ip", "");
                if (!cl.id.isEmpty() && !cl.token.isEmpty()) CLIENTS.put(cl.id, cl);
            }
            Log.i(TAG, "loaded " + CLIENTS.size() + " paired client(s)");
        } catch (Throwable t) {
            Log.w(TAG, "cannot read clients.json: " + t);
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static void save(Context c) {
        List<String> raw = new ArrayList<>();
        for (Client cl : CLIENTS.values()) {
            raw.add(new Json.Obj()
                    .put("id", cl.id)
                    .put("name", cl.name)
                    .put("token", cl.token)
                    .put("created", cl.created)
                    .put("last_seen", cl.lastSeen)
                    .put("last_ip", cl.lastIp)
                    .toString());
        }
        String json = new Json.Obj().putRawArray("clients", raw).toString();
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(c), false);
            out.write(json.getBytes("UTF-8"));
            out.flush();
            sDirty = false;
        } catch (Throwable t) {
            Log.w(TAG, "cannot write clients.json: " + t);
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }
}

package com.lukeet.agenticdevkit;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * The screen reader behind GET /ui and GET /wait.
 *
 * `uiautomator dump` cannot run from this app (it dies with SIGSYS under the app's
 * seccomp filter and cannot create its dalvik-cache), so the hierarchy is read
 * in-process the way accessibility tools do it. The service enables itself through
 * Settings.Secure, which the app may write because it holds WRITE_SECURE_SETTINGS.
 */
public final class AdkAccessibilityService extends AccessibilityService {

    private static final String TAG = "ADK";
    private static volatile AdkAccessibilityService instance;

    public static boolean ready() {
        return instance != null;
    }

    public static AdkAccessibilityService get() {
        return instance;
    }

    /** Make sure the service is declared as enabled; returns true when it is. */
    public static boolean ensureEnabled(Context ctx) {
        ComponentName cn = new ComponentName(ctx, AdkAccessibilityService.class);
        String flat = cn.flattenToString();
        try {
            String current = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null || !current.contains(flat)) {
                String updated = TextUtils.isEmpty(current) ? flat : current + ":" + flat;
                Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
                Settings.Secure.putInt(ctx.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.i(TAG, "enabled accessibility service: " + flat);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot enable the accessibility service: " + t);
            return false;
        }
        return ready();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
        // The system rebinds this after boot, updates and crashes: a second way back for the channel.
        if (Config.autostart(this) && !AdkService.isRunning()) AdkService.start(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The tree is read on demand; no events are acted on (the app drawer moved to MG4 Apps).
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    /* -------------------------------------------------------------------- tree */

    /** Every node with text, an id, a description or a click action. */
    public List<String> nodes() {
        List<String> out = new ArrayList<>();
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
        } catch (Throwable t) {
            Log.w(TAG, "getRootInActiveWindow failed: " + t);
        }
        if (root == null) return out;
        try {
            collect(root, out, 0);
        } finally {
            try { root.recycle(); } catch (Throwable ignored) { }
        }
        return out;
    }

    private void collect(AccessibilityNodeInfo n, List<String> out, int depth) {
        if (n == null || out.size() > 4000) return;
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        String id = n.getViewIdResourceName();
        boolean interesting = (text != null && text.length() > 0)
                || (desc != null && desc.length() > 0)
                || id != null
                || n.isClickable()
                || n.isCheckable()
                || n.isEditable();
        if (interesting) {
            android.graphics.Rect b = new android.graphics.Rect();
            n.getBoundsInScreen(b);
            out.add(new Json.Obj()
                    .put("text", text == null ? "" : text.toString())
                    .put("desc", desc == null ? "" : desc.toString())
                    .put("id", id == null ? "" : id)
                    .put("class", String.valueOf(n.getClassName()))
                    .put("package", String.valueOf(n.getPackageName()))
                    .put("bounds", "[" + b.left + "," + b.top + "][" + b.right + "," + b.bottom + "]")
                    .put("clickable", n.isClickable())
                    .put("enabled", n.isEnabled())
                    .put("focused", n.isFocused())
                    .put("checkable", n.isCheckable())
                    .put("checked", n.isChecked())
                    .put("editable", n.isEditable())
                    .put("scrollable", n.isScrollable())
                    .put("depth", depth)
                    .toString());
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = n.getChild(i);
                collect(child, out, depth + 1);
            } catch (Throwable ignored) {
            } finally {
                if (child != null) try { child.recycle(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Tap a node by matching text/id/desc; returns the tapped centre or null. */
    public int[] tapMatch(String text, String id, String desc) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        try {
            AccessibilityNodeInfo hit = find(root, text, id, desc);
            if (hit == null) return null;
            try {
                android.graphics.Rect b = new android.graphics.Rect();
                hit.getBoundsInScreen(b);
                int cx = (b.left + b.right) / 2;
                int cy = (b.top + b.bottom) / 2;
                String problem = Injector.tap(this, cx, cy);
                return problem == null || problem.isEmpty() ? new int[]{cx, cy} : null;
            } finally {
                hit.recycle();
            }
        } finally {
            try { root.recycle(); } catch (Throwable ignored) { }
        }
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo n, String text, String id, String desc) {
        if (n == null) return null;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String rid = n.getViewIdResourceName();
        boolean hit = (!TextUtils.isEmpty(text) && ((t != null && t.toString().contains(text))
                        || (d != null && d.toString().contains(text))))
                || (!TextUtils.isEmpty(id) && rid != null && rid.contains(id))
                || (!TextUtils.isEmpty(desc) && d != null && d.toString().contains(desc));
        if (hit) return AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo child = null;
            try {
                child = n.getChild(i);
                AccessibilityNodeInfo found = find(child, text, id, desc);
                if (found != null) return found;
            } catch (Throwable ignored) {
            } finally {
                if (child != null) try { child.recycle(); } catch (Throwable ignored) { }
            }
        }
        return null;
    }
}

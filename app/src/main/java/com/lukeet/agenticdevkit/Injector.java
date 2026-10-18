package com.lukeet.agenticdevkit;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Input injection done in-process, the way a system app is supposed to do it.
 *
 * Shelling out to /system/bin/input does not work from here: the child inherits
 * the app's seccomp filter and dies with SIGSYS (exit 159), which is exactly what
 * the rig caught. InputManager.injectInputEvent needs INJECT_EVENTS, which this
 * app holds because it is platform-signed and runs as android.uid.system.
 */
public final class Injector {

    private static final String TAG = "ADK";
    private static Method injectMethod;

    private Injector() {}

    private static boolean inject(Context ctx, InputEvent event) {
        try {
            if (injectMethod == null) {
                InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
                if (im == null) return false;
                injectMethod = InputManager.class.getMethod("injectInputEvent", InputEvent.class, int.class);
                injectMethod.setAccessible(true);
            }
            InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
            // mode 0 = ASYNC (fire and forget); 2 = WAIT_FOR_RESULT
            Object ok = injectMethod.invoke(im, event, 0);
            return ok instanceof Boolean && (Boolean) ok;
        } catch (Throwable t) {
            Log.w(TAG, "injectInputEvent failed: " + t);
            return false;
        }
    }

    private static boolean motion(Context ctx, int action, float x, float y, long downTime, long eventTime) {
        MotionEvent e = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        try {
            e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            return inject(ctx, e);
        } finally {
            e.recycle();
        }
    }

    public static String tap(Context ctx, int x, int y) {
        long t = SystemClock.uptimeMillis();
        boolean down = motion(ctx, MotionEvent.ACTION_DOWN, x, y, t, t);
        boolean up = motion(ctx, MotionEvent.ACTION_UP, x, y, t, t + 40);
        return (down && up) ? "" : "injectInputEvent refused the tap (INJECT_EVENTS?)";
    }

    public static String longPress(Context ctx, int x, int y, int durationMs) {
        long t = SystemClock.uptimeMillis();
        boolean down = motion(ctx, MotionEvent.ACTION_DOWN, x, y, t, t);
        sleep(durationMs);
        boolean up = motion(ctx, MotionEvent.ACTION_UP, x, y, t, t + durationMs);
        return (down && up) ? "" : "injectInputEvent refused the long press";
    }

    public static String swipe(Context ctx, int x1, int y1, int x2, int y2, int durationMs) {
        long downTime = SystemClock.uptimeMillis();
        int steps = Math.max(4, Math.min(60, durationMs / 16));
        boolean ok = motion(ctx, MotionEvent.ACTION_DOWN, x1, y1, downTime, downTime);
        for (int i = 1; i <= steps; i++) {
            float f = (float) i / steps;
            float x = x1 + (x2 - x1) * f;
            float y = y1 + (y2 - y1) * f;
            ok &= motion(ctx, MotionEvent.ACTION_MOVE, x, y, downTime,
                    downTime + (long) (durationMs * f));
            sleep(Math.max(1, durationMs / steps));
        }
        ok &= motion(ctx, MotionEvent.ACTION_UP, x2, y2, downTime, downTime + durationMs);
        return ok ? "" : "injectInputEvent refused the swipe";
    }

    public static String key(Context ctx, int keyCode) {
        long t = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, keyCode, 0);
        boolean ok = inject(ctx, down) & inject(ctx, up);
        return ok ? "" : "injectInputEvent refused the key event";
    }

    /** Type text through the virtual keyboard's character map, like `input text`. */
    public static String text(Context ctx, String text) {
        KeyCharacterMap map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        KeyEvent[] events = map.getEvents(text.toCharArray());
        if (events == null) return "no key events for that text on the virtual keyboard";
        boolean ok = true;
        for (KeyEvent e : events) {
            ok &= inject(ctx, e);
        }
        return ok ? "" : "injectInputEvent refused some key events";
    }

    public static String roll(Context ctx, int dx, int dy) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_SCROLL, 0, 0, 0);
        try {
            e.setSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER);
            boolean ok = inject(ctx, e);
            return ok ? "" : "injectInputEvent refused the roll";
        } finally {
            e.recycle();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.lukeet.agenticdevkit;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Screen capture without shelling out to screencap.
 *
 * `screencap` from an app dies with SIGSYS (the child inherits the app's seccomp
 * filter), so this tries, in order:
 *   1. SurfaceControl.screenshot(...) - the internal API system apps use (READ_FRAME_BUFFER)
 *   2. MediaProjection + VirtualDisplay + ImageReader - public API; consent is skipped
 *      because the app holds CAPTURE_VIDEO_OUTPUT
 *   3. the screencap binary, as a last resort (works on some builds)
 */
public final class Screen {

    private static final String TAG = "ADK";

    private Screen() {}

    public static Bitmap capture(Context ctx, StringBuilder how) {
        Bitmap bmp = viaSurfaceControl(how);
        if (bmp != null) return bmp;
        bmp = viaMediaProjection(ctx, how);
        if (bmp != null) return bmp;
        bmp = viaScreencap(how);
        return bmp;
    }

    /* ------------------------------------------------------- 1. SurfaceControl */

    private static Bitmap viaSurfaceControl(StringBuilder how) {
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            int w = dm.widthPixels > 0 ? dm.widthPixels : 1280;
            int h = dm.heightPixels > 0 ? dm.heightPixels : 720;
            // API 28: screenshot(Rect sourceCrop, int width, int height, int rotation).
            // The Rect must not be null - the native side reads its fields and aborts
            // the whole process (JNI DETECTED ERROR: obj == null) if it is.
            try {
                Method m = sc.getMethod("screenshot", android.graphics.Rect.class,
                        int.class, int.class, int.class);
                android.graphics.Rect crop = new android.graphics.Rect(0, 0, w, h);
                Object b = m.invoke(null, crop, w, h, 0);
                if (b instanceof Bitmap) {
                    how.append("SurfaceControl.screenshot(Rect,int,int,int)");
                    return (Bitmap) b;
                }
            } catch (Throwable t) {
                Log.w(TAG, "SurfaceControl.screenshot(Rect,...) failed: " + t);
            }
            Method m = sc.getMethod("screenshot", int.class, int.class);
            Object b = m.invoke(null, w, h);
            if (b instanceof Bitmap) {
                how.append("SurfaceControl.screenshot(int,int)");
                return (Bitmap) b;
            }
        } catch (Throwable t) {
            Log.w(TAG, "SurfaceControl.screenshot unavailable: " + t);
        }
        return null;
    }

    /* ------------------------------------------------------ 2. MediaProjection */

    private static Bitmap viaMediaProjection(Context ctx, StringBuilder how) {
        MediaProjection projection = null;
        VirtualDisplay display = null;
        ImageReader reader = null;
        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) return null;
            // CAPTURE_VIDEO_OUTPUT (held by platform-signed system apps) skips the
            // consent activity, so a dummy result intent is enough.
            projection = mpm.getMediaProjection(Activity.RESULT_OK, new android.content.Intent());
            if (projection == null) return null;

            DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
            int w = dm.widthPixels > 0 ? dm.widthPixels : 1280;
            int h = dm.heightPixels > 0 ? dm.heightPixels : 720;
            int dpi = dm.densityDpi > 0 ? dm.densityDpi : 160;

            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay("adk-capture", w, h, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);

            long deadline = SystemClock.uptimeMillis() + 4000;
            Image image = null;
            while (SystemClock.uptimeMillis() < deadline && image == null) {
                image = reader.acquireLatestImage();
                if (image == null) Thread.sleep(60);
            }
            if (image == null) return null;
            try {
                Bitmap bmp = toBitmap(image, w, h);
                if (bmp != null) how.append("MediaProjection+VirtualDisplay");
                return bmp;
            } finally {
                image.close();
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaProjection capture failed: " + t);
            return null;
        } finally {
            try { if (display != null) display.release(); } catch (Throwable ignored) { }
            try { if (reader != null) reader.close(); } catch (Throwable ignored) { }
            try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        }
    }

    private static Bitmap toBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        int bmpWidth = width + rowPadding / pixelStride;
        Bitmap bmp = Bitmap.createBitmap(bmpWidth, height, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (bmpWidth != width) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, width, height);
            if (cropped != bmp) bmp.recycle();
            return cropped;
        }
        return bmp;
    }

    /* ------------------------------------------------------------ 3. screencap */

    private static Bitmap viaScreencap(StringBuilder how) {
        try {
            Shell.Result r = Shell.exec(new String[]{"screencap", "-p"}, 30_000);
            if (r.outBytes != null && r.outBytes.length > 8) {
                Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(r.outBytes, 0, r.outBytes.length);
                if (bmp != null) {
                    how.append("screencap -p");
                    return bmp;
                }
            }
            Log.w(TAG, "screencap gave exit=" + r.exit + " bytes="
                    + (r.outBytes == null ? 0 : r.outBytes.length) + " err=" + r.err);
        } catch (Throwable t) {
            Log.w(TAG, "screencap fallback failed: " + t);
        }
        return null;
    }

    /** Raw PNG bytes via screencap, for the rare case where that path works. */
    public static byte[] rawPng() {
        Shell.Result r = Shell.exec(new String[]{"screencap", "-p"}, 30_000);
        return r.outBytes;
    }
}

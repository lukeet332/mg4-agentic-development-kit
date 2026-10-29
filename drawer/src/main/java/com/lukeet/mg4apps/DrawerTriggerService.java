package com.lukeet.mg4apps;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/** Opens the app drawer when the home screen's Apple CarPlay tile is tapped (CarPlay is unused). */
public final class DrawerTriggerService extends AccessibilityService {

    private static final String TAG = "MG4Apps";
    private static final String SAIC_LAUNCHER = "com.saicmotor.hmi.launcher";

    static boolean isCarPlayTile(String viewId) {
        if (viewId == null) return false;
        int at = viewId.indexOf(":id/");
        return viewId.substring(at < 0 ? 0 : at + 4).contains("carplay");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_VIEW_CLICKED) return;
        if (!SAIC_LAUNCHER.contentEquals(String.valueOf(event.getPackageName()))) return;
        AccessibilityNodeInfo source = event.getSource();
        String id = source == null ? null : source.getViewIdResourceName();
        if (!isCarPlayTile(id)) return;
        Log.i(TAG, "CarPlay tile tapped - opening the app drawer");
        // A beat later than the stock CarPlay screen, so the drawer lands on top of it.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                startActivity(new Intent(DrawerTriggerService.this, AppsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
        }, 350);
    }

    @Override
    public void onInterrupt() {
    }
}

package com.lukeet.agenticdevkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Brings the control channel back after a head-unit reboot. */
public final class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "ADK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        Log.i(TAG, "boot receiver: " + action);
        if (!Config.autostart(context)) {
            Log.i(TAG, "autostart disabled; not starting the channel");
            return;
        }
        AdkService.start(context);
    }
}

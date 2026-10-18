package com.lukeet.agenticdevkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fires when a self-update was never confirmed - i.e. the new build is installed
 * but cannot serve requests. Reinstalls the last-known-good APK so the channel
 * (and the agent's ability to fix things) comes back.
 */
public final class RollbackReceiver extends BroadcastReceiver {

    private static final String TAG = "ADK";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        if (action.endsWith("RESTART")) {
            Log.i(TAG, "self-restart alarm fired - starting the channel again");
            AdkService.start(context);
            return;
        }
        Log.w(TAG, "rollback alarm fired");
        if (!Rollback.pending(context)) {
            Log.i(TAG, "nothing pending; ignoring");
            return;
        }
        if (AdkService.isRunning()) {
            Log.i(TAG, "channel is up and serving - keeping this build, cancelling the rollback");
            Rollback.confirm(context);
            return;
        }
        String out = Rollback.rollbackNow(context);
        Log.w(TAG, "rollback result: " + out);
        // whatever happened, try to get the channel back
        AdkService.start(context);
    }
}

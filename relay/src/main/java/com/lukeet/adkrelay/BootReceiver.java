package com.lukeet.adkrelay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the relay when the phone boots or the app is updated. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        RelayService.start(context);
    }
}

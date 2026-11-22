package com.nemu.updateguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives the activation broadcast sent by the injected module. */
public class LsposedActiveReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !LsposedState.ACTION.equals(intent.getAction())) {
            return;
        }
        LsposedState.mark(context);
    }
}

package com.nemu.updateguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Re-posts the hidden-icon notification after a reboot or an app update,
 * because ongoing notifications do not survive either.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !HiddenIconNotifier.ACTION_REPOST.equals(action)) {
            return;
        }
        if (!HiddenIconNotifier.isLauncherIconEnabled(context)) {
            if (!HiddenIconNotifier.show(context)) {
                HiddenIconNotifier.setLauncherIconEnabled(context, true);
            }
        }
    }
}

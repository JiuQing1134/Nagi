package com.nemu.updateguard;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

/**
 * Receives the OUC hook result from the LSPosed module.
 *
 * A bound Messenger is used instead of a broadcast because the framework
 * fills Message.sendingUid in across processes and a sender cannot spoof it.
 * Only reports from com.hihonor.ouc's own UID are accepted.
 */
public class HookReportService extends Service {
    public static final int MSG_REPORT = 1;
    /** Sent by Nagi to ask the hooked OUC process to report again. */
    public static final String ACTION_REQUEST_HOOK_REPORT =
            "com.nemu.windstop.action.REQUEST_HOOK_REPORT";
    private static final String OUC = "com.hihonor.ouc";
    private static final String TAG = "UpdateGuard";

    private final Messenger messenger = new Messenger(
            new Handler(Looper.getMainLooper(), this::handleMessage));

    private boolean handleMessage(Message msg) {
        if (msg.what != MSG_REPORT) return false;
        int expected = oucUid();
        if (expected < 0 || msg.sendingUid != expected) {
            Log.w(TAG, "ignored hook report from uid=" + msg.sendingUid
                    + " (expected " + expected + ")");
            return true;
        }
        Log.i(TAG, "hook report hookOk=" + (msg.arg1 != 0)
                + " from uid=" + msg.sendingUid);
        LsposedState.mark(this, msg.arg1 != 0);
        return true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // Binder.getCallingUid() here is the service process itself (the
        // manager calls onBind), not the client that called bindService.
        // The caller is verified per message via Message.sendingUid.
        return messenger.getBinder();
    }

    private int oucUid() {
        try {
            return getPackageManager().getApplicationInfo(OUC, 0).uid;
        } catch (Throwable t) {
            return -1;
        }
    }
}

package com.nemu.updateguard;

import android.content.Context;
import android.provider.Settings;

/**
 * Activation state for the LSPosed module. The module sends a broadcast when it
 * is actually injected into a process. The receiver records the current boot
 * count, so the UI can tell whether the activation belongs to this boot.
 *
 * A broadcast is used instead of only a static field because LSPosed may load
 * the module classes in a separate class loader; a static flag set there is not
 * guaranteed to be the same class object the app reads.
 */
public final class LsposedState {
    public static final String ACTION =
            "com.nemu.windstop.action.LSPOSED_ACTIVE";
    private static final String PREFS = "lsposed_state";
    private static final String KEY_BOOT = "boot";

    public static volatile boolean active = false;
    public static volatile long lastSeen = 0L;
    private static volatile Runnable listener;

    private LsposedState() {
    }

    public static void setListener(Runnable value) {
        listener = value;
    }

    public static void clearListener(Runnable value) {
        if (listener == value) listener = null;
    }

    public static void mark(Context context) {
        active = true;
        lastSeen = System.currentTimeMillis();
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_BOOT, bootCount(context))
                    .putLong("time", lastSeen)
                    .apply();
        } catch (Throwable ignored) {
        }
        Runnable callback = listener;
        if (callback != null) {
            try {
                callback.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void clear(Context context) {
        active = false;
        lastSeen = 0L;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_BOOT).apply();
        } catch (Throwable ignored) {
        }
    }

    public static boolean isActive(Context context) {
        if (active) return true;
        try {
            int saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_BOOT, -1);
            return saved >= 0 && saved == bootCount(context);
        } catch (Throwable t) {
            return false;
        }
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable t) {
            return -1;
        }
    }
}

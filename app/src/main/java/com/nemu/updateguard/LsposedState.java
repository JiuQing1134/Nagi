package com.nemu.updateguard;

import android.content.Context;
import android.provider.Settings;

/**
 * LSPosed state for the current boot. The module now reports whether the OUC
 * result hook actually took effect, not just whether LSPosed injected some
 * code into some process:
 *
 *   hook == 1  the obfuscated result.d() method was found and hooked
 *   hook == 0  OUC was injected, but the hook target was not found
 *   missing    no report yet this boot
 *
 * The boot count prevents a report from an earlier boot from looking current.
 */
public final class LsposedState {
    private static final String PREFS = "lsposed_state";
    private static final String KEY_BOOT = "boot";
    private static final String KEY_HOOK = "hook";

    public static volatile boolean active = false;
    private static volatile Runnable listener;

    private LsposedState() {
    }

    public static void setListener(Runnable value) {
        listener = value;
    }

    public static void clearListener(Runnable value) {
        if (listener == value) listener = null;
    }

    /** Records a verified OUC hook result for the current boot. */
    public static void mark(Context context, boolean hookOk) {
        active = hookOk;
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_BOOT, bootCount(context))
                    .putInt(KEY_HOOK, hookOk ? 1 : 0)
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
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_BOOT).remove(KEY_HOOK).apply();
        } catch (Throwable ignored) {
        }
    }

    /** True only when this boot's OUC result hook was actually installed. */
    public static boolean isActive(Context context) {
        if (active) return true;
        return savedHook(context) == 1;
    }

    /** True when OUC was injected this boot but the hook target was missing. */
    public static boolean hookFailed(Context context) {
        return !active && savedHook(context) == 0;
    }

    private static int savedHook(Context context) {
        try {
            if (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_BOOT, -1) != bootCount(context)) {
                return -1;
            }
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_HOOK, -1);
        } catch (Throwable t) {
            return -1;
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

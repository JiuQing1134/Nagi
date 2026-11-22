package com.nemu.updateguard;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * LSPosed entry point.
 *
 * OUC hook: the decompiled MagicOS 10 OUC keeps the "has new version" bit in
 * com.hihonor.android.hnouc.check.model.result.a.d(). Returning false here
 * makes every check report "already latest" without touching Build fields or
 * the system version.
 *
 * Shutdown hook (system_server): before ShutdownThread.run performs the real
 * reboot, signal the root FIFO daemon installed by the Nemu module. The
 * daemon runs /data/adb/nemu/restore_frozen.sh, which the app regenerates
 * after every freeze/restore. This covers power-key, system UI and app-issued
 * shutdowns/reboots.
 */
public class UpdateHook implements IXposedHookLoadPackage {
    private static final String OUC = "com.hihonor.ouc";
    private static final String RESULT_CLASS =
            "com.hihonor.android.hnouc.check.model.result.a";
    private static final File NEMU_DIR = new File("/data/adb/nemu");
    private static final File PIPE = new File(NEMU_DIR, "shutdown.pipe");
    private static final File ACK = new File(NEMU_DIR, "restore_done");
    private static final File RESTORE = new File(NEMU_DIR, "restore_frozen.sh");

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if ("com.nemu.windstop".equals(lpparam.packageName)) {
            markActiveInSelf(lpparam);
        } else if (OUC.equals(lpparam.packageName)) {
            hookOuc(lpparam);
        } else if ("android".equals(lpparam.packageName)) {
            hookShutdown(lpparam);
        }
    }

    /**
     * LSPosed injects this module into the app's own process when it is active.
     * Do not rely on a static field across class loaders: hook Application and
     * broadcast, so the app's receiver marks the current boot.
     */
    private void markActiveInSelf(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    android.app.Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            broadcastActive((android.content.Context) param.thisObject);
                        }
                    });
            XposedBridge.log("UpdateGuard: LSPosed injected into app process");
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: self activation hook failed: " + t);
        }
    }

    private static void broadcastActive(android.content.Context context) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    "com.nemu.windstop.action.LSPOSED_ACTIVE");
            intent.setPackage("com.nemu.windstop");
            intent.addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            XposedBridge.log("UpdateGuard: activation broadcast sent");
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: activation broadcast failed: " + t);
        }
    }

    private void hookOuc(LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    android.app.Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            broadcastActive((android.content.Context) param.thisObject);
                        }
                    });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> result = XposedHelpers.findClass(
                    RESULT_CLASS, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(result, "d",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return Boolean.FALSE;
                        }
                    });
            XposedBridge.log("UpdateGuard: OUC check result.d() -> false");
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: OUC hook failed: " + t);
        }
    }

    private void hookShutdown(LoadPackageParam lpparam) {
        try {
            Class<?> shutdown = XposedHelpers.findClass(
                    "com.android.server.power.ShutdownThread",
                    lpparam.classLoader);
            XposedHelpers.findAndHookMethod(shutdown, "run",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            restoreBeforeShutdown();
                        }
                    });
            XposedBridge.log("UpdateGuard: ShutdownThread.run hooked");
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: shutdown hook failed: " + t);
        }
        try {
            Class<?> activityThread = XposedHelpers.findClass(
                    "android.app.ActivityThread", lpparam.classLoader);
            Object thread = XposedHelpers.callStaticMethod(
                    activityThread, "currentActivityThread");
            android.content.Context context =
                    (android.content.Context) XposedHelpers.callMethod(
                            thread, "getSystemContext");
            broadcastActive(context);
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: system activation broadcast failed: " + t);
        }
    }

    private void restoreBeforeShutdown() {
        try {
            // Nothing is frozen: the app writes an empty restore script. Do not
            // touch the FIFO, otherwise a missing reader can block shutdown.
            if (!RESTORE.exists() || RESTORE.length() == 0L) {
                XposedBridge.log("UpdateGuard: no restore script; skip shutdown hook");
                return;
            }
            ACK.delete();
            if (!PIPE.exists()) {
                XposedBridge.log("UpdateGuard: " + PIPE + " missing");
                return;
            }
            final FileDescriptor fd;
            try {
                fd = Os.open(PIPE.getPath(),
                        OsConstants.O_WRONLY | OsConstants.O_NONBLOCK, 0);
            } catch (ErrnoException e) {
                // ENXIO means no module daemon is reading this FIFO. Never
                // block the system_server shutdown thread on a missing reader.
                XposedBridge.log("UpdateGuard: FIFO has no reader: " + e.errno);
                return;
            }
            try {
                byte[] bytes = "shutdown\n".getBytes(StandardCharsets.UTF_8);
                Os.write(fd, bytes, 0, bytes.length);
            } finally {
                try {
                    Os.close(fd);
                } catch (Throwable ignored) {
                }
            }
            for (int i = 0; i < 80; i++) {
                if (ACK.exists()) {
                    XposedBridge.log("UpdateGuard: restore script finished");
                    return;
                }
                Thread.sleep(100);
            }
            XposedBridge.log("UpdateGuard: restore script timed out");
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: FIFO trigger failed: " + t);
        }
    }

}

package com.nemu.updateguard;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileDescriptor;
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
 * daemon runs /data/adb/nemu/restore_frozen.sh, which the Nemu app regenerates
 * after every freeze/restore. This covers power-key, system UI and app-issued
 * shutdowns/reboots. The hook is kept because Nemu's initrc path is not
 * available on every install; it deliberately does NOT report LSPosed as
 * active, because injecting into system_server says nothing about whether the
 * OUC hook actually took effect.
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
        if (OUC.equals(lpparam.packageName)) {
            hookOuc(lpparam);
        } else if ("android".equals(lpparam.packageName)) {
            hookShutdown(lpparam);
        }
    }

    /**
     * Applies the result hook and then reports the real outcome. Only OUC's
     * main process reports, so a secondary process that fails to resolve the
     * obfuscated class cannot overwrite a successful verdict from the process
     * that renders the update page.
     */
    private void hookOuc(LoadPackageParam lpparam) {
        boolean hooked = false;
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
            hooked = true;
            XposedBridge.log("UpdateGuard: OUC check result.d() -> false");
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: OUC hook failed: " + t);
        }
        final boolean hookOk = hooked;
        final boolean mainProcess = lpparam.processName == null
                || OUC.equals(lpparam.processName);
        try {
            XposedHelpers.findAndHookMethod(
                    android.app.Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (mainProcess) {
                                reportActive(
                                        (Context) param.thisObject, hookOk);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: activation hook failed: " + t);
        }
    }

    /**
     * Reports the hook result through a bound Messenger. The app verifies
     * Message.sendingUid against OUC's UID, so a normal app cannot fake the
     * "activated" state with a broadcast.
     */
    private static void reportActive(final Context context, final boolean hookOk) {
        final Intent intent = new Intent();
        intent.setComponent(new ComponentName("com.nemu.windstop",
                "com.nemu.updateguard.HookReportService"));
        try {
            boolean bound = context.bindService(intent,
                    new ServiceConnection() {
                        @Override
                        public void onServiceConnected(
                                ComponentName name, IBinder binder) {
                            try {
                                Message message = Message.obtain(
                                        null, HookReportService.MSG_REPORT);
                                message.arg1 = hookOk ? 1 : 0;
                                new Messenger(binder).send(message);
                            } catch (Throwable t) {
                                XposedBridge.log(
                                        "UpdateGuard: hook report failed: " + t);
                            } finally {
                                try {
                                    context.unbindService(this);
                                } catch (Throwable ignored) {
                                }
                            }
                        }

                        @Override
                        public void onServiceDisconnected(ComponentName name) {
                        }
                    }, Context.BIND_AUTO_CREATE);
            XposedBridge.log("UpdateGuard: hook report bind=" + bound
                    + " hookOk=" + hookOk);
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: hook report bind failed: " + t);
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
    }

    private void restoreBeforeShutdown() {
        try {
            // Nothing is frozen: the Nemu app writes an empty restore script.
            // Do not touch the FIFO, otherwise a missing reader can block
            // shutdown.
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

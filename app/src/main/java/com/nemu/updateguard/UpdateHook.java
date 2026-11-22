package com.nemu.updateguard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;

import android.app.AndroidAppHelper;

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
 * the system version. Only the OUC process is hooked; there is deliberately no
 * system_server/shutdown hook.
 */
public class UpdateHook implements IXposedHookLoadPackage {
    private static final String OUC = "com.hihonor.ouc";
    private static final String RESULT_CLASS =
            "com.hihonor.android.hnouc.check.model.result.a";
    private static final Binder PROCESS_TOKEN = new Binder();
    private static volatile boolean reportReceiverRegistered = false;
    private static volatile boolean fallbackReportTried = false;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (OUC.equals(lpparam.packageName)) {
            hookOuc(lpparam);
        }
    }

    /**
     * Applies the result hook and then reports the real outcome. Only OUC's
     * main process reports, so a secondary process that fails to resolve the
     * obfuscated class cannot overwrite a successful verdict from the process
     * that renders the update page.
     */
    private void hookOuc(LoadPackageParam lpparam) {
        final boolean mainProcess = lpparam.processName == null
                || OUC.equals(lpparam.processName);
        boolean hooked = false;
        try {
            Class<?> result = XposedHelpers.findClass(
                    RESULT_CLASS, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(result, "d",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            // Fallback for the case where hooking
                            // Application.onCreate fails but this result hook
                            // still works: register the request receiver and
                            // report from the first real d() call.
                            if (mainProcess && !fallbackReportTried) {
                                try {
                                    Context app = AndroidAppHelper.currentApplication();
                                    if (app != null
                                            && registerReportReceiver(app, true)) {
                                        fallbackReportTried = true;
                                        reportActive(app, true);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(
                                            "UpdateGuard: fallback report failed: " + t);
                                }
                            }
                            return Boolean.FALSE;
                        }
                    });
            hooked = true;
            XposedBridge.log("UpdateGuard: OUC check result.d() -> false");
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: OUC hook failed: " + t);
        }
        final boolean hookOk = hooked;
        try {
            XposedHelpers.findAndHookMethod(
                    android.app.Application.class, "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (mainProcess) {
                                Context context = (Context) param.thisObject;
                                registerReportReceiver(context, hookOk);
                                reportActive(context, hookOk);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("UpdateGuard: activation hook failed: " + t);
        }
    }

    /**
     * Lets Nagi ask the already-hooked OUC process for a fresh report. This
     * closes the gap where OUC was started before Nagi was installed or while
     * Nagi was force-stopped: without it, the only report happens once in
     * Application.onCreate. The request broadcast carries no trusted data;
     * the actual report still goes through the UID-checked service.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static boolean registerReportReceiver(
            final Context context, final boolean hookOk) {
        if (reportReceiverRegistered) return true;
        reportReceiverRegistered = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    XposedBridge.log("UpdateGuard: hook report requested");
                    reportActive(ctx, hookOk);
                }
            };
            IntentFilter filter = new IntentFilter(
                    HookReportService.ACTION_REQUEST_HOOK_REPORT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter,
                        Context.RECEIVER_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            XposedBridge.log("UpdateGuard: hook report receiver registered");
            return true;
        } catch (Throwable t) {
            reportReceiverRegistered = false;
            XposedBridge.log("UpdateGuard: hook report receiver failed: " + t);
            return false;
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
                                message.arg2 = Process.myPid();
                                Bundle data = new Bundle();
                                data.putBinder("token", PROCESS_TOKEN);
                                message.setData(data);
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

}

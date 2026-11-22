package com.nemu.updateguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Owns the launcher activity-alias state and the ongoing notification that
 * keeps a hidden 凪 reachable.
 */
final class HiddenIconNotifier {
    static final String ACTION_SHOW_ICON = "com.nemu.updateguard.action.SHOW_ICON";
    static final String ACTION_REPOST = "com.nemu.updateguard.action.REPOST_ICON";
    static final int NOTIFICATION_ID = 42;

    private static final String CHANNEL_ID = "hidden_icon";
    private static final String ALIAS_CLASS = "com.nemu.updateguard.Launcher";

    private HiddenIconNotifier() {
    }

    static boolean isLauncherIconEnabled(Context context) {
        try {
            return context.getPackageManager().getComponentEnabledSetting(
                    new ComponentName(context, ALIAS_CLASS))
                    != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Enables or disables the .Launcher alias and verifies that the package
     * manager really accepted the new state.
     */
    static boolean setLauncherIconEnabled(Context context, boolean enabled) {
        try {
            PackageManager pm = context.getPackageManager();
            ComponentName alias = new ComponentName(context, ALIAS_CLASS);
            pm.setComponentEnabledSetting(alias,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            int state = pm.getComponentEnabledSetting(alias);
            return enabled
                    ? state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean show(Context context) {
        if (!hasNotificationPermission(context)) return false;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "图标隐藏", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("图标隐藏时用于打开凪");
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
            NotificationChannel existing = nm.getNotificationChannel(CHANNEL_ID);
            if (existing != null &&
                    existing.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return false;
            }
        }
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(context, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent show = new Intent(context, MainActivity.class)
                .setAction(ACTION_SHOW_ICON)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent showIntent = PendingIntent.getActivity(context, 2, show,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent repost = new Intent(context, BootReceiver.class)
                .setAction(ACTION_REPOST);
        PendingIntent repostIntent = PendingIntent.getBroadcast(context, 3, repost,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                // Never let the system fall back to a cached app icon (that is
                // how the previous anime icon kept reappearing in the shade).
                .setLargeIcon(android.graphics.drawable.Icon.createWithResource(
                        context, R.drawable.ic_launcher_large))
                .setContentTitle("凪")
                .setContentText("图标已隐藏 · 点此打开")
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setDeleteIntent(repostIntent)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(R.drawable.ic_notification, "显示图标", showIntent)
                .build();
        try {
            nm.notify(NOTIFICATION_ID, notification);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static void cancel(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
    }
}

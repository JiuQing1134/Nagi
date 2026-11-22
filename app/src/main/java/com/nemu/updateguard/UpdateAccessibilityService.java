package com.nemu.updateguard;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import java.lang.ref.WeakReference;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Standalone mode. It cannot inject code into OUC (that is what the LSPosed
 * hook is for), so when OUC shows an update result it covers the screen with a
 * "current system is up to date" panel. The user sees the same result as the
 * real hook, without freezing or disabling OUC.
 */
public class UpdateAccessibilityService extends AccessibilityService {
    private static final String OUC = "com.hihonor.ouc";
    private static WeakReference<UpdateAccessibilityService> current =
            new WeakReference<>(null);
    private long lastShownAt = 0L;
    private long dismissedUntil = 0L;
    private View overlay;
    private WindowManager windowManager;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        current = new WeakReference<>(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    /** Called by the switch in MainActivity; disables the service in settings. */
    public static boolean disableSelfNow() {
        UpdateAccessibilityService service = current.get();
        if (service == null) return false;
        service.disableSelf();
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String eventPackage = event.getPackageName().toString();
        if (!OUC.equals(eventPackage)) {
            // Ignore our own overlay window events (otherwise the overlay
            // dismisses itself right after being added). Only a real window
            // change to another app removes a stale overlay.
            if (!getPackageName().equals(eventPackage) &&
                    overlay != null &&
                    event.getEventType() ==
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                AccessibilityNodeInfo active = getRootInActiveWindow();
                CharSequence activePackage = active == null
                        ? null : active.getPackageName();
                if (activePackage != null &&
                        !OUC.equals(activePackage.toString())) {
                    dismissOverlay(false);
                }
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now < dismissedUntil) return;
        if (now - lastShownAt < 4000) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence rootPackage = root.getPackageName();
        if (rootPackage == null || !OUC.equals(rootPackage.toString())) return;
        String text = collectText(root, 0);
        if (text.contains("发现新版本") || text.contains("发现更新") ||
                text.contains("立即更新") || text.contains("下载并安装") ||
                text.contains("新版本可用") || text.contains("有可用更新")) {
            lastShownAt = now;
            Log.i("UpdateSleep", "showing up-to-date overlay instead of update prompt");
            showLatestOverlay();
        }
    }

    private void showLatestOverlay() {
        if (windowManager == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        }
        if (windowManager == null) return;
        if (overlay != null) {
            return;
        }

        boolean dark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int cardColor = dark ? 0xFF141414 : 0xFFFFFFFF;
        int titleColor = dark ? 0xFFF2F2F2 : 0xFF1B1B1B;
        int bodyColor = dark ? 0xFFAFAFAF : 0xFF666666;
        int accent = dark ? 0xFF8FDDB7 : 0xFF2E7D5B;
        int pad = dp(24);

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER);
        screen.setPadding(pad, pad, pad, pad);
        screen.setBackgroundColor(dark ? 0xF2000000 : 0xF2FFF7FA);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(pad, dp(28), pad, dp(22));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(cardColor);
        cardBg.setCornerRadius(dp(28));
        cardBg.setStroke(dp(1), dark ? 0xFF2C2C2C : 0xFFEADFE3);
        card.setBackground(cardBg);

        TextView check = new TextView(this);
        check.setText("✓");
        check.setTextSize(30);
        check.setTextColor(accent);
        check.setGravity(Gravity.CENTER);
        GradientDrawable checkBg = new GradientDrawable();
        checkBg.setColor(dark ? 0xFF16241E : 0xFFEAF4EF);
        checkBg.setCornerRadius(dp(28));
        check.setBackground(checkBg);
        LinearLayout.LayoutParams checkLp =
                new LinearLayout.LayoutParams(dp(56), dp(56));
        checkLp.bottomMargin = dp(16);
        card.addView(check, checkLp);

        TextView title = new TextView(this);
        title.setText("当前系统已是最新版本");
        title.setTextSize(21);
        title.setTextColor(titleColor);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView body = new TextView(this);
        body.setText("凪已把这次检查结果处理为“已是最新”。\n不会下载、安装或修改系统版本。");
        body.setTextSize(14);
        body.setTextColor(bodyColor);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(10);
        card.addView(body, bodyLp);

        Button close = new Button(this);
        close.setText("知道了");
        close.setAllCaps(false);
        close.setTextSize(15);
        close.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        close.setTextColor(dark ? 0xFF062015 : Color.WHITE);
        GradientDrawable buttonBg = new GradientDrawable();
        buttonBg.setColor(accent);
        buttonBg.setCornerRadius(dp(16));
        close.setBackground(buttonBg);
        close.setPadding(dp(20), 0, dp(20), 0);
        close.setMinHeight(0);
        close.setMinWidth(0);
        close.setStateListAnimator(null);
        close.setOnClickListener(v -> dismissOverlay(true));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        closeLp.topMargin = dp(20);
        card.addView(close, closeLp);

        int cardWidth = Math.min(
                getResources().getDisplayMetrics().widthPixels - dp(40),
                dp(420));
        screen.addView(card, new LinearLayout.LayoutParams(
                cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.CENTER;
        try {
            windowManager.addView(screen, params);
            overlay = screen;
        } catch (Throwable t) {
            Log.e("UpdateSleep", "overlay failed", t);
            overlay = null;
        }
    }

    private void dismissOverlay(boolean closeUpdatePage) {
        if (overlay != null && windowManager != null) {
            try {
                windowManager.removeView(overlay);
            } catch (Throwable ignored) {
            }
            overlay = null;
        }
        // Stay closed for this OUC page even if the page still contains
        // update text. Do not send a global BACK: that would jump away from
        // the current screen.
        dismissedUntil = System.currentTimeMillis() + 60000L;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String collectText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 20) return "";
        StringBuilder sb = new StringBuilder();
        CharSequence t = node.getText();
        if (t != null) sb.append(t).append(' ');
        CharSequence d = node.getContentDescription();
        if (d != null) sb.append(d).append(' ');
        for (int i = 0; i < node.getChildCount(); i++) {
            sb.append(collectText(node.getChild(i), depth + 1));
        }
        return sb.toString();
    }

    @Override
    public void onInterrupt() {
        dismissOverlay(false);
    }

    @Override
    public void onDestroy() {
        dismissOverlay(false);
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }
}

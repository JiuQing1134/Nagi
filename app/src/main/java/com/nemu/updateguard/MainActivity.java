package com.nemu.updateguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * LSPosed-only control panel. The module reports its OUC hook result through
 * HookReportService; this screen displays that state and opens the LSPosed
 * manager. Nothing here suspends, disables, or freezes OUC.
 */
public class MainActivity extends Activity {
    private static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    private static final long LSPOSED_PROBE_INTERVAL_MS = 10000L;

    private boolean dark;
    private long lastFeedbackAt;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable statusRestore;

    private View modeDot;
    private TextView modeTitle;
    private TextView modeSubtitle;
    private TextView modeStatus;
    private TextView lsposedChip;
    private volatile Boolean lsposedManagerEnabled = null;
    private volatile long lsposedProbeAt = 0L;
    private volatile boolean lsposedProbeRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        removeLegacyIconChannels();

        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(bg());
        screen.setFitsSystemWindows(true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg());
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        root.setPadding(pad, dp(18), pad, dp(12));
        scroll.addView(root);

        root.addView(hero());
        root.addView(modeCard());

        screen.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        screen.addView(bottomBar());

        setContentView(screen);
        applySystemBars();
        applyHighRefreshRate();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LsposedState.setListener(lsposedActivated);
        probeLsposed(false);
        if (modeTitle != null) {
            refreshStatus();
        }
    }

    @Override
    protected void onPause() {
        LsposedState.clearListener(lsposedActivated);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** Removes channels left behind by the removed hide-icon feature. */
    private void removeLegacyIconChannels() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.deleteNotificationChannel("hidden_icon");
            nm.deleteNotificationChannel("hidden_icon_v2");
        } catch (Throwable ignored) {
        }
    }

    private LinearLayout hero() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));

        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{mix(accent(), cardBg(), dark ? 0.14f : 0.16f), cardBg()});
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), stroke());
        card.setBackground(bg);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(12);
        card.setLayoutParams(cardLp);

        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.ic_launcher_large);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(64), dp(64));
        markLp.setMarginEnd(dp(14));
        card.addView(mark, markLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("凪", 27, textColor(), true);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        texts.addView(title);
        TextView subtitle = text("软件更新始终显示“已是最新版本”", 13, subColor(), false);
        subtitle.setPadding(0, dp(4), 0, 0);
        texts.addView(subtitle);
        card.addView(texts, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return card;
    }

    private int mix(int fg, int bg, float ratio) {
        int r = Math.round(((fg >> 16) & 0xFF) * ratio + ((bg >> 16) & 0xFF) * (1 - ratio));
        int g = Math.round(((fg >> 8) & 0xFF) * ratio + ((bg >> 8) & 0xFF) * (1 - ratio));
        int b = Math.round((fg & 0xFF) * ratio + (bg & 0xFF) * (1 - ratio));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private LinearLayout modeCard() {
        LinearLayout card = card();

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER_VERTICAL);
        modeDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(10), dp(10));
        dotLp.setMarginEnd(dp(12));
        modeRow.addView(modeDot, dotLp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        modeTitle = text("正在检查…", 19, textColor(), true);
        titles.addView(modeTitle);
        modeSubtitle = text("", 12, subColor(), false);
        modeSubtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(modeSubtitle);
        modeRow.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView refresh = text("刷新", 12, accent(), false);
        refresh.setPadding(dp(12), dp(6), dp(4), dp(6));
        refresh.setOnClickListener(v -> {
            probeLsposed(true);
            refreshStatus();
        });
        modeRow.addView(refresh);
        card.addView(modeRow);

        LinearLayout chipRow = chipRow(14);
        lsposedChip = modeChip(chipRow, "LSPosed", this::openLsposed);
        card.addView(chipRow);

        modeStatus = text("", 11, subColor(), false);
        modeStatus.setPadding(dp(2), dp(10), dp(2), 0);
        modeStatus.setMinHeight(dp(30));
        modeStatus.setMaxLines(2);
        modeStatus.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(modeStatus);
        return card;
    }

    private LinearLayout chipRow(int topDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(topDp), 0, 0);
        return row;
    }

    private TextView modeChip(LinearLayout parent, String label, Runnable action) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(12);
        chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(0);
        chip.setMinWidth(0);
        chip.setPadding(dp(6), 0, dp(6), 0);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setStateListAnimator(null);
        chip.setBackground(chipBackground(false));
        chip.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        parent.addView(chip, lp);
        return chip;
    }

    /**
     * Reads LSPosed's own module switch. The hook report only covers the
     * current boot; this probe notices a later OFF toggle in the manager.
     * Root is used read-only when available; failure keeps the hook report.
     */
    private void probeLsposed(boolean force) {
        if (lsposedProbeRunning || !lsposedInstalled()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lsposedProbeAt < LSPOSED_PROBE_INTERVAL_MS) return;
        lsposedProbeRunning = true;
        new Thread(() -> {
            Boolean enabled = LsposedDb.queryEnabled(this);
            runOnUiThread(() -> {
                lsposedProbeRunning = false;
                lsposedProbeAt = System.currentTimeMillis();
                if (enabled != null) {
                    lsposedManagerEnabled = enabled;
                    if (!enabled) LsposedState.clear(this);
                    if (modeTitle != null) refreshStatus();
                }
            });
        }).start();
    }

    /** Real-time hook report from the injected module. */
    private final Runnable lsposedActivated = () -> {
        if (modeTitle != null) refreshStatus();
    };

    private void openLsposed() {
        if (LsposedState.isActive(this)) {
            // Already active: do not replace the bottom summary with a
            // dot-less "LSPosed 已激活" for four seconds. The chip already
            // shows the check mark and the card already shows the status.
            refreshStatus();
            return;
        }
        String message;
        if (LsposedState.hookFailed(this)) {
            message = "模块已注入，但软件更新的 hook 未生效，软件更新可能已改版。"
                    + "请到 LSPosed 管理器中确认模块状态。";
        } else if (Boolean.TRUE.equals(lsposedManagerEnabled)) {
            message = "模块已启用，打开软件更新后生效。";
        } else {
            message = "在 LSPosed 管理器中启用凪，作用域勾选“系统框架”和“软件更新”，然后重启一次。";
        }
        new AlertDialog.Builder(this)
                .setTitle("LSPosed")
                .setMessage(message)
                .setPositiveButton("打开管理器", (dialog, which) -> launchLsposed())
                .setNegativeButton("取消", null)
                .show();
    }

    private void launchLsposed() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(LSPOSED_PACKAGE);
        if (intent == null) {
            Intent fallback = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(LSPOSED_PACKAGE);
            if (fallback.resolveActivity(getPackageManager()) == null) {
                feedback("未安装 LSPosed 管理器");
                return;
            }
            intent = fallback;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            feedback("已打开 LSPosed");
        } catch (Throwable t) {
            feedback("无法打开 LSPosed");
        }
    }

    private LinearLayout bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(dp(22), dp(10), dp(22), dp(18));
        bar.setBackgroundColor(bg());

        TextView version = text(installedVersion(), 10, subColor(), false);
        version.setGravity(Gravity.CENTER);
        version.setAlpha(0.6f);
        bar.addView(version, fullWidth());

        TextView powered = text("Powered by JiuQing", 11, subColor(), false);
        powered.setGravity(Gravity.CENTER);
        powered.setAlpha(0.8f);
        powered.setPadding(0, dp(3), 0, 0);
        bar.addView(powered, fullWidth());

        TextView license = text("开源协议", 11, accent(), false);
        license.setGravity(Gravity.CENTER);
        license.setPadding(0, dp(3), 0, 0);
        license.setOnClickListener(v -> showLicense());
        bar.addView(license, fullWidth());
        return bar;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private String installedVersion() {
        try {
            String installed = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            if (installed != null && !installed.isEmpty()) return installed;
        } catch (Throwable ignored) {
        }
        return "1.3.8";
    }

    private void showLicense() {
        String text;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getResources().openRawResource(R.raw.license),
                StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            text = builder.toString();
        } catch (Throwable t) {
            text = "MIT License\n\nCopyright (c) 2026 JiuQing";
        }
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(12);
        view.setTextColor(textColor());
        int pad = dp(18);
        view.setPadding(pad, dp(8), pad, dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle("开源协议")
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(rounded(cardBg(), 22, stroke()));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        }
        return view;
    }

    private GradientDrawable rounded(int fill, float radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            drawable.setStroke(dp(1), strokeColor);
        }
        return drawable;
    }

    private Drawable chipBackground(boolean selected) {
        GradientDrawable base = rounded(
                selected ? accent() : cardBg(),
                14,
                selected ? 0 : stroke());
        int rippleColor = dark ? 0x33FFFFFF : 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), base, null);
    }

    private void styleLsposedChip() {
        if (lsposedChip == null) return;
        boolean installed = lsposedInstalled();
        boolean active = LsposedState.isActive(this);
        lsposedChip.setText(active ? "✓ LSPosed" : "LSPosed");
        lsposedChip.setTextColor(active ? onAccent()
                : installed ? textColor() : subColor());
        lsposedChip.setAlpha(installed || active ? 1f : 0.55f);
        lsposedChip.setBackground(chipBackground(active));
    }

    private void updateModeStatus() {
        if (modeStatus == null) return;
        if (System.currentTimeMillis() - lastFeedbackAt < 3000L) return;
        if (LsposedState.isActive(this)) {
            modeStatus.setText("LSPosed · 已激活");
        } else if (LsposedState.hookFailed(this)) {
            modeStatus.setText("LSPosed · 已注入，hook 未生效，软件更新可能已改版");
        } else if (!lsposedInstalled()) {
            modeStatus.setText("未检测到 LSPosed 管理器");
        } else if (Boolean.FALSE.equals(lsposedManagerEnabled)) {
            modeStatus.setText("LSPosed · 已在管理器中关闭，点芯片打开");
        } else if (Boolean.TRUE.equals(lsposedManagerEnabled)) {
            modeStatus.setText("LSPosed · 已启用，打开软件更新后生效");
        } else {
            modeStatus.setText("LSPosed · 状态未知，点芯片打开管理器");
        }
    }

    private void feedback(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        lastFeedbackAt = System.currentTimeMillis();
        if (modeStatus != null) {
            modeStatus.setText(message);
            if (statusRestore != null) uiHandler.removeCallbacks(statusRestore);
            statusRestore = this::updateModeStatus;
            uiHandler.postDelayed(statusRestore, 4000L);
        }
    }

    private boolean lsposedInstalled() {
        try {
            return getPackageManager().getLaunchIntentForPackage(LSPOSED_PACKAGE) != null
                    || getPackageManager().getPackageInfo(LSPOSED_PACKAGE, 0) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int bg() {
        return dark ? 0xFF000000 : 0xFFFAFAFA;
    }

    private int cardBg() {
        return dark ? 0xFF111111 : 0xFFFFFFFF;
    }

    private int stroke() {
        return dark ? 0xFF282828 : 0xFFE6E6E6;
    }

    private int textColor() {
        return dark ? 0xFFEDEDED : 0xFF121212;
    }

    private int subColor() {
        return dark ? 0xFFA8A8A8 : 0xFF6B6B6B;
    }

    private int accent() {
        return dark ? 0xFF9BD8BF : 0xFF2F6B5E;
    }

    private int onAccent() {
        return dark ? 0xFF06130F : 0xFFFFFFFF;
    }

    private int mutedDot() {
        return dark ? 0xFF3A3A3A : 0xFFD6D6D6;
    }

    private void applyHighRefreshRate() {
        try {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.preferredRefreshRate = 120f;
            android.view.Display display = getWindowManager().getDefaultDisplay();
            android.view.Display.Mode best = null;
            for (android.view.Display.Mode mode : display.getSupportedModes()) {
                if (best == null || mode.getRefreshRate() > best.getRefreshRate()) {
                    best = mode;
                }
            }
            if (best != null) attrs.preferredDisplayModeId = best.getModeId();
            getWindow().setAttributes(attrs);
        } catch (Throwable ignored) {
        }
    }

    private void applySystemBars() {
        getWindow().setStatusBarColor(bg());
        getWindow().setNavigationBarColor(bg());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
            }
        }
    }

    private void refreshStatus() {
        if (LsposedState.isActive(this)) {
            modeTitle.setText("LSPosed");
            modeSubtitle.setText("已激活，软件更新已返回最新");
            modeDot.setBackground(rounded(accent(), 999, 0));
        } else if (LsposedState.hookFailed(this)) {
            modeTitle.setText("LSPosed");
            modeSubtitle.setText("已注入，hook 未生效");
            modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
        } else if (!lsposedInstalled()) {
            modeTitle.setText("未安装 LSPosed");
            modeSubtitle.setText("需要 LSPosed 管理器");
            modeDot.setBackground(rounded(mutedDot(), 999, 0));
        } else if (Boolean.FALSE.equals(lsposedManagerEnabled)) {
            modeTitle.setText("LSPosed");
            modeSubtitle.setText("已在管理器中关闭");
            modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
        } else if (Boolean.TRUE.equals(lsposedManagerEnabled)) {
            modeTitle.setText("LSPosed");
            modeSubtitle.setText("已启用，打开软件更新后生效");
            modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
        } else {
            modeTitle.setText("LSPosed");
            modeSubtitle.setText("状态未知，点芯片打开管理器");
            modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
        }
        styleLsposedChip();
        updateModeStatus();
    }
}

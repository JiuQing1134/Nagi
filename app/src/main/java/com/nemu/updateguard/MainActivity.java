package com.nemu.updateguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * Minimal control panel. One switch enables the standalone overlay. The four
 * mode chips are real controls: LSPosed opens the manager, Root and Shizuku
 * enable the accessibility overlay, ADB copies the equivalent commands.
 * Nothing here suspends, disables, or freezes OUC.
 */
public class MainActivity extends Activity {
    private static final String PREFS = "update_sleep";
    private static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    private static final int REQ_SHIZUKU = 8;

    private boolean dark;
    private boolean switchUpdating;
    private boolean autoEnableTried;
    private boolean rootCheckInFlight;
    private boolean rootCheckAttempted;
    private long lastRootCheckAt;
    private volatile boolean rootOk;
    private long lastFeedbackAt;
    private String selectedMode = "";
    private SharedPreferences prefs;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable statusRestore;

    private View modeDot;
    private TextView modeTitle;
    private TextView modeSubtitle;
    private TextView modeStatus;
    private TextView lsposedMode;
    private TextView rootMode;
    private TextView shizukuMode;
    private TextView adbMode;
    private Switch independentSwitch;
    private volatile Boolean lsposedManagerEnabled = null;
    private volatile long lsposedProbeAt = 0L;
    private volatile boolean lsposedProbeRunning = false;

    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU) return;
                runOnUiThread(() -> {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        runShizukuEnable();
                    } else {
                        feedback("Shizuku 授权被拒绝");
                    }
                });
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        selectedMode = prefs.getString("mode", "");

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg());
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        root.setPadding(pad, dp(18), pad, dp(28));
        root.setFitsSystemWindows(true);
        scroll.addView(root);

        root.addView(hero());
        root.addView(modeCard());
        root.addView(footer());

        setContentView(scroll);
        applySystemBars();
        applyHighRefreshRate();
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        try {
            IntentFilter lsposedFilter = new IntentFilter(LsposedState.ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(lsposedReceiver, lsposedFilter,
                        Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(lsposedReceiver, lsposedFilter);
            }
        } catch (Throwable ignored) {
        }
        refreshStatus();
        // The self-injection broadcast can arrive a few milliseconds after the
        // first refreshStatus. Re-read once so the chip does not stay on
        // "not activated" until a manual refresh.
        uiHandler.postDelayed(() -> {
            if (modeTitle != null) refreshStatus();
        }, 800L);
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
        try {
            unregisterReceiver(lsposedReceiver);
        } catch (Throwable ignored) {
        }
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        super.onDestroy();
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
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(dp(76), dp(76));
        markLp.setMarginEnd(dp(16));
        card.addView(mark, markLp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("凪", 27, textColor(), true);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        texts.addView(title);
        TextView subtitle = text("让更新检查保持最新", 13, subColor(), false);
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
        card.addView(modeRow);

        LinearLayout firstRow = chipRow(14);
        lsposedMode = modeChip(firstRow, "LSPosed", "lsposed", this::openLsposed);
        rootMode = modeChip(firstRow, "Root", "root", this::enableViaRoot);
        card.addView(firstRow);

        LinearLayout secondRow = chipRow(8);
        shizukuMode = modeChip(secondRow, "Shizuku", "shizuku", this::enableViaShizuku);
        adbMode = modeChip(secondRow, "ADB", "adb", this::copyAdbCommand);
        card.addView(secondRow);

        modeStatus = text("", 11, subColor(), false);
        modeStatus.setPadding(dp(2), dp(10), dp(2), 0);
        modeStatus.setMinHeight(dp(30));
        modeStatus.setMaxLines(2);
        modeStatus.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(modeStatus);

        View divider = new View(this);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerLp.topMargin = dp(12);
        dividerLp.bottomMargin = dp(4);
        divider.setBackgroundColor(stroke());
        card.addView(divider, dividerLp);

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, dp(8), 0, 0);
        TextView switchLabel = text("独立模式", 15, textColor(), false);
        switchRow.addView(switchLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        independentSwitch = new Switch(this);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        independentSwitch.setThumbTintList(new ColorStateList(
                states, new int[]{0xFFFFFFFF, 0xFFFFFFFF}));
        independentSwitch.setTrackTintList(new ColorStateList(
                states, new int[]{accent(), mutedDot()}));
        independentSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (switchUpdating) return;
            if (checked) {
                enableIndependentMode();
            } else {
                disableIndependentMode();
            }
        });
        switchRow.addView(independentSwitch);
        card.addView(switchRow);
        return card;
    }

    private LinearLayout chipRow(int topDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(topDp), 0, 0);
        return row;
    }

    private TextView modeChip(LinearLayout parent, String label, String mode,
                              Runnable action) {
        TextView chip = new TextView(this);
        chip.setTag(mode);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMarginEnd(dp(6));
        parent.addView(chip, lp);
        return chip;
    }

    private boolean isLsposedManagerOn() {
        Boolean manager = lsposedManagerEnabled;
        if (manager != null) return manager;
        return LsposedState.isActive(this);
    }

    private void openLsposed() {
        if (isLsposedManagerOn()) {
            setSelectedMode("lsposed");
            feedback("LSPosed 已激活");
            refreshStatus();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("LSPosed")
                .setMessage("在 LSPosed 管理器中启用凪，作用域勾选“系统框架”和“软件更新”，然后重启一次。")
                .setPositiveButton("打开管理器", (dialog, which) -> launchLsposed())
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean isLsposedActive() {
        Boolean manager = lsposedManagerEnabled;
        if (manager != null) return manager;
        return LsposedState.isActive(this);
    }

    /**
     * Reads LSPosed's own module switch. The activation broadcast only tells us
     * "injected at least once"; this probe notices a later OFF toggle in the
     * manager. Root is used read-only; failure keeps the broadcast fallback.
     */
    private void probeLsposed(boolean force) {
        if (!rootOk || lsposedProbeRunning) return;
        long now = System.currentTimeMillis();
        if (!force && now - lsposedProbeAt < 1500L) return;
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

    /** Real-time activation broadcast from the injected module. */
    private final Runnable lsposedActivated = () -> {
        if (modeTitle != null) refreshStatus();
    };

    private final BroadcastReceiver lsposedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && LsposedState.ACTION.equals(intent.getAction())) {
                LsposedState.mark(context);
                refreshStatus();
            }
        }
    };

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
            setSelectedMode("lsposed");
            feedback("已打开 LSPosed");
        } catch (Throwable t) {
            feedback("无法打开 LSPosed");
        }
    }

    private void enableViaRoot() {
        feedback("正在通过 Root 开启…");
        new Thread(() -> {
            String id = execute("id", null);
            boolean root = id.contains("uid=0");
            String out = "";
            if (root) {
                out = execute(enableAccessibilityCommand() + " && echo __OK__", null);
            }
            boolean opened = out.contains("__OK__");
            runOnUiThread(() -> {
                rootOk = root;
                if (opened) {
                    setSelectedMode("root");
                    feedback("Root · 独立模式已开启");
                } else if (!root) {
                    feedback("未获得 Root 权限，请先授权");
                } else {
                    feedback("Root 已授权，但独立模式开启失败");
                }
                refreshStatus();
            });
        }).start();
    }

    private void enableViaShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                feedback("Shizuku 未运行，请先启动 Shizuku");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_SHIZUKU);
                feedback("请在 Shizuku 中允许凪");
                return;
            }
        } catch (Throwable t) {
            feedback("Shizuku 不可用");
            return;
        }
        runShizukuEnable();
    }

    private void runShizukuEnable() {
        if (!shizukuGranted()) return;
        feedback("正在通过 Shizuku 开启…");
        new Thread(() -> {
            String result = shizukuExec(enableAccessibilityCommand() + " && echo __OK__");
            boolean opened = result.contains("__OK__") && parseExit(result) == 0;
            runOnUiThread(() -> {
                if (opened) {
                    setSelectedMode("shizuku");
                    feedback("Shizuku · 独立模式已开启");
                } else {
                    feedback("Shizuku 执行失败" + detail(result));
                }
                refreshStatus();
            });
        }).start();
    }

    private void copyAdbCommand() {
        String merged = mergedAccessibilityServices();
        String cmd = "adb shell settings put secure enabled_accessibility_services "
                + merged + "\n"
                + "adb shell settings put secure accessibility_enabled 1";
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("ADB", cmd));
        }
        setSelectedMode("adb");
        feedback("ADB 命令已复制");
    }

    private LinearLayout footer() {
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        String versionName = "1.3.3";
        try {
            String installed = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            if (installed != null && !installed.isEmpty()) versionName = installed;
        } catch (Throwable ignored) {
        }
        TextView version = text(versionName, 12, subColor(), false);
        footer.addView(version, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView refresh = text("刷新", 13, accent(), false);
        refresh.setPadding(dp(12), dp(10), dp(4), dp(10));
        refresh.setOnClickListener(v -> {
            rootCheckAttempted = false;
            probeLsposed(true);
            refreshStatus();
        });
        footer.addView(refresh);
        return footer;
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

    private void styleChips() {
        if (lsposedMode == null) return;
        boolean lsposed = lsposedInstalled();
        boolean shizuku = shizukuGranted();
        boolean adb = adbEnabled();
        styleChip(lsposedMode, "LSPosed", lsposed,
                lsposed && "lsposed".equals(selectedMode));
        styleChip(rootMode, "Root", rootOk, rootOk && "root".equals(selectedMode));
        styleChip(shizukuMode, "Shizuku", shizuku,
                shizuku && "shizuku".equals(selectedMode));
        styleChip(adbMode, "ADB", true, "adb".equals(selectedMode));
    }

    private void styleChip(TextView chip, String label, boolean available,
                           boolean selected) {
        chip.setText(selected ? "✓ " + label : label);
        chip.setTextColor(selected ? onAccent() : available ? textColor() : subColor());
        chip.setAlpha(selected || available ? 1f : 0.55f);
        chip.setBackground(chipBackground(selected));
    }

    private void setSelectedMode(String mode) {
        selectedMode = mode;
        prefs.edit().putString("mode", mode).apply();
        styleChips();
        updateModeStatus(isAccessibilityEnabled());
    }

    private String modeLabel(String mode) {
        switch (mode) {
            case "lsposed": return "LSPosed";
            case "root": return "Root";
            case "shizuku": return "Shizuku";
            case "adb": return "ADB";
            default: return "";
        }
    }

    private void updateModeStatus(boolean a11y) {
        if (modeStatus == null) return;
        if (System.currentTimeMillis() - lastFeedbackAt < 3000L) return;
        String label = modeLabel(selectedMode);
        if ("lsposed".equals(selectedMode) && !a11y) {
            if (Boolean.FALSE.equals(lsposedManagerEnabled)) {
                modeStatus.setText("LSPosed · 已在管理器中关闭，点芯片打开");
            } else if (LsposedState.isActive(this)) {
                modeStatus.setText("LSPosed · 已激活，更新检查返回已最新");
            } else if (Boolean.TRUE.equals(lsposedManagerEnabled)) {
                modeStatus.setText("LSPosed · 已启用，重启后生效");
            } else {
                modeStatus.setText("LSPosed · 状态未知，点芯片打开管理器");
            }
        } else if (label.isEmpty()) {
            modeStatus.setText(a11y ? "独立模式已开启" : "点选下方模式");
        } else {
            modeStatus.setText(a11y
                    ? label + " · 独立模式已开启"
                    : label + " · 独立模式未开启");
        }
    }

    private void feedback(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        lastFeedbackAt = System.currentTimeMillis();
        if (modeStatus != null) {
            modeStatus.setText(message);
            if (statusRestore != null) uiHandler.removeCallbacks(statusRestore);
            statusRestore = () -> updateModeStatus(isAccessibilityEnabled());
            uiHandler.postDelayed(statusRestore, 4000L);
        }
    }

    private boolean shizukuGranted() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
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

    private boolean adbEnabled() {
        try {
            return Settings.Global.getInt(getContentResolver(),
                    Settings.Global.ADB_ENABLED, 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isAccessibilityEnabled() {
        try {
            if (Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                return false;
            }
            String services = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null) return false;
            String shortName = getPackageName() + "/.UpdateAccessibilityService";
            String fullName = getPackageName() + "/"
                    + UpdateAccessibilityService.class.getName();
            return services.contains(shortName) || services.contains(fullName);
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
        boolean a11y = isAccessibilityEnabled();

        switchUpdating = true;
        independentSwitch.setChecked(a11y);
        switchUpdating = false;

        if (a11y) {
            modeTitle.setText("独立模式");
            modeSubtitle.setText("更新页显示「已是最新」");
            modeDot.setBackground(rounded(accent(), 999, 0));
        } else if ("lsposed".equals(selectedMode)) {
            modeTitle.setText("LSPosed");
            if (Boolean.FALSE.equals(lsposedManagerEnabled)) {
                modeSubtitle.setText("已在管理器中关闭");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
            } else if (LsposedState.isActive(this)) {
                modeSubtitle.setText("已激活，更新检查返回已最新");
                modeDot.setBackground(rounded(accent(), 999, 0));
            } else if (Boolean.TRUE.equals(lsposedManagerEnabled)) {
                modeSubtitle.setText("已启用，重启后生效");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
            } else {
                modeSubtitle.setText("状态未知，点芯片打开管理器");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
            }
        } else {
            modeTitle.setText("未启用");
            modeSubtitle.setText("打开开关或点选下方通道");
            modeDot.setBackground(rounded(mutedDot(), 999, 0));
        }
        styleChips();
        updateModeStatus(a11y);

        checkRootAsync();
    }

    private void checkRootAsync() {
        long now = System.currentTimeMillis();
        if (rootCheckInFlight) return;
        if (rootCheckAttempted && now - lastRootCheckAt < 30000L) return;
        rootCheckInFlight = true;
        rootCheckAttempted = true;
        lastRootCheckAt = now;
        new Thread(() -> {
            boolean root = execute("id", null).contains("uid=0");
            runOnUiThread(() -> {
                rootCheckInFlight = false;
                rootOk = root;
                styleChips();
                if (root) probeLsposed(true);
            });
        }).start();
    }

    private void enableIndependentMode() {
        autoEnableTried = true;
        prefs.edit().putBoolean("user_disabled", false).apply();
        if (shizukuGranted()) {
            runShizukuEnable();
            return;
        }
        if (rootOk) {
            enableViaRoot();
            return;
        }
        feedback("正在检查 Root…");
        new Thread(() -> {
            String out = execute("id", null);
            boolean root = out.contains("uid=0");
            runOnUiThread(() -> {
                rootOk = root;
                styleChips();
                if (root && !prefs.getBoolean("user_disabled", false)
                        && independentSwitch.isChecked()) {
                    enableViaRoot();
                } else if (root) {
                    // The user turned the switch off while root was checked.
                } else {
                    switchUpdating = true;
                    independentSwitch.setChecked(false);
                    switchUpdating = false;
                    feedback("未授权 Root/Shizuku，无法自动开启独立模式");
                    refreshStatus();
                }
            });
        }).start();
    }

    private void disableIndependentMode() {
        autoEnableTried = true;
        prefs.edit().putBoolean("user_disabled", true).apply();
        boolean enabled = isAccessibilityEnabled();
        if (enabled && !UpdateAccessibilityService.disableSelfNow()) {
            switchUpdating = true;
            independentSwitch.setChecked(true);
            switchUpdating = false;
            feedback("请在系统中关闭凪的独立模式");
        } else {
            switchUpdating = true;
            independentSwitch.setChecked(false);
            switchUpdating = false;
            feedback(enabled ? "独立模式已关闭" : "已取消开启");
        }
        refreshStatus();
    }

    private void bringHomeToFront() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        } catch (Throwable ignored) {
        }
    }

    private String execute(String command, String[] prefix) {
        Process p = null;
        try {
            String[] cmd = prefix == null
                    ? new String[]{"su", "-c", command}
                    : new String[]{prefix[0], prefix[1], prefix[2], command};
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            final Process process = p;
            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) out.append(line).append('\n');
                } catch (Throwable ignored) {
                }
            });
            reader.start();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return "超时";
            }
            reader.join(1000);
            return out.toString().trim();
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return "失败：" + t;
        }
    }

    private String shizukuExec(String command) {
        try {
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            moe.shizuku.server.IRemoteProcess process =
                    service.newProcess(new String[]{"sh"}, null, null);
            try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(
                    process.getOutputStream())) {
                out.write(command.getBytes());
            }
            AtomicReference<String> stdout = new AtomicReference<>("");
            AtomicReference<String> stderr = new AtomicReference<>("");
            Thread t1 = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new ParcelFileDescriptor.AutoCloseInputStream(process.getInputStream())))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    stdout.set(sb.toString());
                } catch (Throwable ignored) {
                }
            });
            Thread t2 = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new ParcelFileDescriptor.AutoCloseInputStream(process.getErrorStream())))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    stderr.set(sb.toString());
                } catch (Throwable ignored) {
                }
            });
            AtomicInteger code = new AtomicInteger(-1);
            CountDownLatch done = new CountDownLatch(1);
            t1.start();
            t2.start();
            new Thread(() -> {
                try {
                    code.set(process.waitFor());
                } catch (Throwable ignored) {
                }
                done.countDown();
            }).start();
            if (!done.await(15, TimeUnit.SECONDS)) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
                return "超时";
            }
            t1.join(1000);
            t2.join(1000);
            String text = stdout.get();
            if (text.isEmpty()) text = stderr.get();
            return "exit=" + code.get() + "\n" + text.trim();
        } catch (Throwable t) {
            return "失败：" + t;
        }
    }

    /**
     * Current accessibility service list plus 凪, preserving other services
     * instead of overwriting them. The raw setting is restricted to characters
     * legal in component names before it is embedded in a root shell command.
     */
    private String mergedAccessibilityServices() {
        // The Java namespace stays com.nemu.updateguard while the application
        // id is com.nemu.windstop, so the short ".Service" form would resolve
        // to a class that does not exist. Always write the full class name.
        String self = getPackageName() + "/"
                + UpdateAccessibilityService.class.getName();
        String current = null;
        try {
            current = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Throwable ignored) {
        }
        if (current != null) {
            current = current.replaceAll("[^A-Za-z0-9._:/$-]", "");
        }
        if (current == null || current.isEmpty()) return self;
        String full = getPackageName() + "/"
                + UpdateAccessibilityService.class.getName();
        for (String entry : current.split(":")) {
            if (entry.equals(self) || entry.equals(full)) return current;
        }
        return current + ":" + self;
    }

    /** Shell command used by the Root and Shizuku chips. */
    private String enableAccessibilityCommand() {
        return "settings put secure enabled_accessibility_services '"
                + mergedAccessibilityServices() + "'"
                + " && settings put secure accessibility_enabled 1";
    }

    private int parseExit(String result) {
        if (result == null || !result.startsWith("exit=")) return -1;
        int nl = result.indexOf('\n');
        String number = nl >= 0 ? result.substring(5, nl) : result.substring(5);
        try {
            return Integer.parseInt(number.trim());
        } catch (Throwable t) {
            return -1;
        }
    }

    private String stripExit(String result) {
        if (result == null) return "";
        if (result.startsWith("exit=")) {
            int nl = result.indexOf('\n');
            return nl >= 0 ? result.substring(nl + 1) : "";
        }
        return result;
    }

    private String detail(String result) {
        String body = stripExit(result);
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return "：" + (trimmed.length() > 80
                        ? trimmed.substring(0, 80) + "…" : trimmed);
            }
        }
        return "";
    }
}

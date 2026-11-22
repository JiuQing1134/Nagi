package com.nemu.updateguard;

import android.annotation.SuppressLint;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
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
// Chinese-only UI; all status strings are intentional and not localized.
@SuppressLint("SetTextI18n")
public class MainActivity extends Activity {
    private static final String TAG = "UpdateGuard";
    private static final String OUC_PACKAGE = "com.hihonor.ouc";
    private static final String LSPOSED_PACKAGE = "org.lsposed.manager";
    private static final long HOOK_REPORT_TIMEOUT_MS = 2000L;

    private boolean dark;
    private boolean resumed;
    private boolean hookReportFresh = false;
    private boolean hookReportPending = false;
    private boolean lsposedProbeRetried = false;
    private long hookReportRequestedAt = 0L;
    private final Handler reportHandler = new Handler(Looper.getMainLooper());

    private final Runnable hookReportTimeout = () -> {
        hookReportPending = false;
        if (!hookReportFresh) {
            Log.i(TAG, "hook report timeout");
            if (!isFinishing() && !isDestroyed()) {
                refreshStatus();
            }
        }
    };

    private View modeDot;
    private TextView modeTitle;
    private TextView modeSubtitle;
    private TextView modeStatus;
    private TextView lsposedChip;
    private volatile Boolean lsposedManagerEnabled = null;
    private volatile Boolean lsposedOucScoped = null;
    private volatile boolean lsposedProbeDone = false;
    private volatile boolean lsposedProbeFailed = false;
    private volatile boolean lsposedProbeRunning = false;

    private final Runnable lsposedProbeRetry = () -> {
        if (resumed) probeLsposed();
    };

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
        // Check exactly once per foreground entry: app launch, or returning
        // from the background. There is deliberately no periodic polling.
        resumed = true;
        lsposedProbeRetried = false;
        reportHandler.removeCallbacks(lsposedProbeRetry);
        LsposedState.setListener(lsposedActivated);
        requestHookReport();
        probeLsposed();
        if (modeTitle != null) {
            refreshStatus();
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        LsposedState.clearListener(lsposedActivated);
        reportHandler.removeCallbacks(hookReportTimeout);
        reportHandler.removeCallbacks(lsposedProbeRetry);
        hookReportPending = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        reportHandler.removeCallbacksAndMessages(null);
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
        TextView subtitle = text("让软件更新始终显示“已是最新版本”", 13, subColor(), false);
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
            requestHookReport();
            probeLsposed();
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
     * Asks the already-hooked OUC process for a fresh report. This makes the
     * activation state current even when OUC was started before Nagi was
     * installed, updated, or force-stopped.
     */
    private void requestHookReport() {
        lsposedProbeRetried = false;
        reportHandler.removeCallbacks(lsposedProbeRetry);
        hookReportRequestedAt = SystemClock.elapsedRealtime();
        hookReportFresh = false;
        hookReportPending = true;
        reportHandler.removeCallbacks(hookReportTimeout);
        reportHandler.postDelayed(hookReportTimeout, HOOK_REPORT_TIMEOUT_MS);
        try {
            Intent intent = new Intent(
                    HookReportService.ACTION_REQUEST_HOOK_REPORT);
            intent.setPackage(OUC_PACKAGE);
            sendBroadcast(intent);
            Log.i(TAG, "requested hook report");
        } catch (Throwable t) {
            Log.w(TAG, "hook report request failed", t);
        }
        if (modeTitle != null) refreshStatus();
    }

    /**
     * Reads LSPosed's own module switch and OUC scope. The hook report only
     * covers the current boot; this probe notices a later OFF toggle or a
     * removed scope. Root is used read-only. A null result keeps the last
     * known values; the hook report remains primary.
     */
    private void probeLsposed() {
        if (!lsposedInstalled()) {
            lsposedManagerEnabled = null;
            lsposedOucScoped = null;
            lsposedProbeDone = true;
            lsposedProbeFailed = false;
            if (modeTitle != null) refreshStatus();
            return;
        }
        if (lsposedProbeRunning) return;
        lsposedProbeRunning = true;
        new Thread(() -> {
            LsposedDb.State state;
            try {
                state = LsposedDb.queryState(this);
            } catch (Throwable t) {
                Log.w(TAG, "lspd probe crashed", t);
                state = null;
            }
            LsposedDb.State result = state;
            runOnUiThread(() -> {
                lsposedProbeRunning = false;
                lsposedProbeDone = true;
                lsposedProbeFailed = result == null;
                if (result != null) {
                    if (!Boolean.valueOf(result.enabled).equals(lsposedManagerEnabled)
                            || !java.util.Objects.equals(
                                    result.oucScoped, lsposedOucScoped)) {
                        Log.i(TAG, "manager switch=" + result.enabled
                                + " oucScope=" + result.oucScoped);
                    }
                    lsposedManagerEnabled = result.enabled;
                    lsposedOucScoped = result.oucScoped;
                    lsposedProbeRetried = false;
                    // Turning the manager switch off or removing OUC from the
                    // scope does not unload hooks from an already-running OUC
                    // process, so keep the hook report primary and surface the
                    // configuration as a secondary note.
                } else if (lsposedManagerEnabled != null
                        && !lsposedProbeRetried && resumed) {
                    // A transient read failure after a known state gets one
                    // silent retry; a first-time failure (e.g. root denied)
                    // does not, to avoid repeated root prompts.
                    lsposedProbeRetried = true;
                    reportHandler.removeCallbacks(lsposedProbeRetry);
                    reportHandler.postDelayed(lsposedProbeRetry, 800L);
                }
                // A null result keeps the last known values. The next
                // foreground entry or the refresh button retries.
                if (modeTitle != null && !isDestroyed()) refreshStatus();
            });
        }).start();
    }

    /** Fresh hook report from the injected module. */
    private final Runnable lsposedActivated = () -> {
        if (LsposedState.lastReportElapsed >= hookReportRequestedAt) {
            hookReportFresh = true;
            hookReportPending = false;
            reportHandler.removeCallbacks(hookReportTimeout);
        }
        if (modeTitle != null) refreshStatus();
    };

    private void openLsposed() {
        if (!lsposedInstalled()) {
            new AlertDialog.Builder(this)
                    .setTitle("LSPosed")
                    .setMessage("未检测到 LSPosed 管理器。凪只通过 LSPosed 工作，"
                            + "请先安装并启用模块。")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }
        boolean active = freshActive();
        boolean failed = freshFailed();
        if (Boolean.TRUE.equals(lsposedManagerEnabled) && active) {
            // Already active and the manager switch is known to be ON. Open
            // the manager directly; launchLsposed() only uses a Toast, so the
            // chip/status state is not replaced.
            launchLsposed();
            return;
        }
        String message;
        if (lsposedProbeFailed) {
            if (active) {
                message = "当前软件更新的 hook 已生效；管理器状态读取失败，"
                        + "请打开管理器确认开关和作用域。";
            } else if (failed) {
                message = "软件更新的 hook 未生效；管理器状态读取失败，"
                        + "请打开管理器确认模块状态。";
            } else {
                message = "管理器状态读取失败，点刷新重试，或打开管理器确认模块状态。";
            }
        } else if (Boolean.FALSE.equals(lsposedManagerEnabled) && active) {
            message = "此前软件更新的 hook 已生效，但管理器里已关闭。"
                    + "新启动的软件更新进程不会再加载 hook；请打开管理器确认或重新启用。";
        } else if (Boolean.FALSE.equals(lsposedManagerEnabled)) {
            message = "在 LSPosed 管理器中启用凪，作用域勾选“软件更新”，然后重启一次。";
        } else if (Boolean.FALSE.equals(lsposedOucScoped) && active) {
            message = "此前软件更新的 hook 已生效，但作用域里已取消“软件更新”。"
                    + "新启动的软件更新进程不会再加载 hook；请打开管理器重新勾选。";
        } else if (Boolean.FALSE.equals(lsposedOucScoped)) {
            message = "在 LSPosed 管理器中确认凪已启用，并把“软件更新”加入作用域，然后重启一次。";
        } else if (failed) {
            message = "模块已注入，但软件更新的 hook 未生效，软件更新可能已改版。"
                    + "请到 LSPosed 管理器中确认模块状态。";
        } else if (Boolean.TRUE.equals(lsposedManagerEnabled)) {
            if (LsposedState.isActive(this)) {
                message = "上次软件更新的 hook 已生效，但当前进程未上报。"
                        + "模块已启用；请重启软件更新。";
            } else if (LsposedState.hookFailed(this)) {
                message = "上次软件更新的 hook 未生效。模块已启用；请重启软件更新后重试。";
            } else {
                message = "模块已启用，重启软件更新后生效。";
            }
        } else if (active) {
            message = "当前软件更新进程的 hook 已生效。可以打开管理器查看模块开关。";
        } else {
            message = "在 LSPosed 管理器中启用凪，作用域勾选“软件更新”，然后重启一次。";
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
                toast("未安装 LSPosed 管理器");
                return;
            }
            intent = fallback;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
            toast("已打开 LSPosed");
        } catch (Throwable t) {
            toast("无法打开 LSPosed");
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
            text = "CC BY-NC 4.0\n\nCopyright (c) 2026 JiuQing\n\n"
                    + "许可证文件读取失败，请查看项目根目录的 LICENSE 文件。";
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
        // The check mark means this foreground check received a live hook
        // report, not merely a report saved earlier this boot.
        boolean active = freshActive();
        lsposedChip.setText(active ? "✓ LSPosed" : "LSPosed");
        lsposedChip.setTextColor(active ? onAccent()
                : installed ? textColor() : subColor());
        lsposedChip.setAlpha(installed || active ? 1f : 0.55f);
        lsposedChip.setBackground(chipBackground(active));
    }

    private void updateModeStatus() {
        if (modeStatus == null) return;
        StatusModel.State state = statusState();
        String note = bottomConfigNote();
        switch (state) {
            case FRESH_ACTIVE:
                modeStatus.setText(lsposedProbeFailed
                        ? "LSPosed · 已激活（管理器读取失败）"
                        : note != null
                        ? "LSPosed · 已激活（" + note + "）"
                        : "LSPosed · 已激活");
                break;
            case FRESH_FAILED:
                modeStatus.setText(lsposedProbeFailed
                        ? "LSPosed · hook 未生效（管理器读取失败）"
                        : note != null
                        ? "LSPosed · hook 未生效（" + note + "）"
                        : "LSPosed · 已注入，hook 未生效，软件更新可能已改版");
                break;
            case NOT_INSTALLED:
                modeStatus.setText("未检测到 LSPosed 管理器");
                break;
            case CHECKING_HOOK:
                modeStatus.setText(lsposedProbeDone
                        ? "LSPosed · 正在检查软件更新进程…"
                        : "LSPosed · 正在检查…");
                break;
            case CHECKING_MANAGER:
                modeStatus.setText(hookReportRequestedAt != 0
                        ? "LSPosed · 本次未收到 hook 报告，正在读取管理器…"
                        : "LSPosed · 正在检查…");
                break;
            case MANAGER_FAILED:
                modeStatus.setText("LSPosed · 管理器状态读取失败，点刷新重试");
                break;
            case MANAGER_OFF:
                modeStatus.setText("LSPosed · 已在管理器中关闭，点芯片打开");
                break;
            case SCOPE_OFF:
                modeStatus.setText("LSPosed · 作用域未勾选软件更新");
                break;
            case ENABLED_HISTORICAL_ACTIVE:
                modeStatus.setText("LSPosed · 已启用，当前进程未上报");
                break;
            case ENABLED_HISTORICAL_FAILED:
                modeStatus.setText("LSPosed · 已启用，上次 hook 未生效");
                break;
            case ENABLED:
                modeStatus.setText("LSPosed · 已启用，重启软件更新后生效");
                break;
            default:
                modeStatus.setText("LSPosed · 未能读取管理器开关");
                break;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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
                if (best == null || Math.abs(mode.getRefreshRate() - 120f)
                        < Math.abs(best.getRefreshRate() - 120f)) {
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
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(dark ? 0 : mask, mask);
        }
    }

    private StatusModel.State statusState() {
        return StatusModel.compute(
                lsposedInstalled(),
                hookReportFresh && !LsposedState.processGone,
                LsposedState.active,
                hookReportPending,
                lsposedProbeDone,
                lsposedProbeFailed,
                lsposedManagerEnabled,
                lsposedOucScoped,
                LsposedState.isActive(this),
                LsposedState.hookFailed(this));
    }

    private boolean freshActive() {
        return hookReportFresh && LsposedState.active
                && !LsposedState.processGone;
    }

    private boolean freshFailed() {
        return hookReportFresh && !LsposedState.active
                && !LsposedState.processGone;
    }

    /** Subtitle note when the running hook is still alive but new OUC
     *  processes will not load it. */
    private String activeConfigNote() {
        if (lsposedProbeFailed) return null;
        boolean managerOff = Boolean.FALSE.equals(lsposedManagerEnabled);
        boolean scopeOff = Boolean.FALSE.equals(lsposedOucScoped);
        if (managerOff && scopeOff) return "管理器已关闭、作用域已取消";
        if (managerOff) return "管理器已关闭";
        if (scopeOff) return "作用域已取消";
        return null;
    }

    private String bottomConfigNote() {
        if (lsposedProbeFailed) return null;
        boolean managerOff = Boolean.FALSE.equals(lsposedManagerEnabled);
        boolean scopeOff = Boolean.FALSE.equals(lsposedOucScoped);
        if (managerOff && scopeOff) return "管理器关/作用域取消";
        if (managerOff) return "管理器已关";
        if (scopeOff) return "作用域已取消";
        return null;
    }

    private void refreshStatus() {
        StatusModel.State state = statusState();
        String note = activeConfigNote();
        switch (state) {
            case FRESH_ACTIVE:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText(note != null
                        ? "已激活（" + note + "，新进程不再生效）"
                        : "已激活，软件更新已返回最新");
                modeDot.setBackground(rounded(accent(), 999, 0));
                break;
            case FRESH_FAILED:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText(note != null
                        ? "已注入，hook 未生效（" + note + "）"
                        : "已注入，hook 未生效");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
                break;
            case NOT_INSTALLED:
                modeTitle.setText("未安装 LSPosed");
                modeSubtitle.setText("需要 LSPosed 管理器");
                modeDot.setBackground(rounded(mutedDot(), 999, 0));
                break;
            case CHECKING_HOOK:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText(lsposedProbeDone
                        ? "正在检查软件更新进程…"
                        : "正在检查 LSPosed 状态…");
                modeDot.setBackground(rounded(mutedDot(), 999, 0));
                break;
            case CHECKING_MANAGER:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText(hookReportRequestedAt != 0
                        ? "本次未收到软件更新进程报告，正在读取管理器…"
                        : "正在检查 LSPosed 状态…");
                modeDot.setBackground(rounded(mutedDot(), 999, 0));
                break;
            case MANAGER_FAILED:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText("管理器状态读取失败，点刷新重试");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
                break;
            case MANAGER_OFF:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText("已在管理器中关闭");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
                break;
            case SCOPE_OFF:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText(Boolean.TRUE.equals(lsposedManagerEnabled)
                        ? "已启用，但作用域未勾选软件更新"
                        : "作用域未勾选软件更新");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
                break;
            case ENABLED_HISTORICAL_ACTIVE:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText("已启用，当前软件更新进程未上报（上次 hook 已生效）");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
                break;
            case ENABLED_HISTORICAL_FAILED:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText("已启用，上次 hook 未生效；重启软件更新后重试");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
                break;
            case ENABLED:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText("已启用，重启软件更新后生效");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
                break;
            default:
                modeTitle.setText("LSPosed");
                modeSubtitle.setText("未能读取管理器开关，点芯片打开管理器");
                modeDot.setBackground(rounded(0xFFD9A441, 999, 0));
                break;
        }
        styleLsposedChip();
        updateModeStatus();
    }
}

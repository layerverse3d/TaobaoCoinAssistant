package com.local.taobaocoinassistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Optional top status strip for the pure Shizuku build.
 *
 * Uses TYPE_APPLICATION_OVERLAY, so it requires the one-time Android "display over other apps"
 * permission only when the feature is enabled. The overlay is positioned below the real system
 * status-bar/display-cutout safe inset instead of using a hard-coded Y coordinate, so it remains
 * visible and touchable across different resolutions, DPI values and notches.
 */
public final class StatusOverlay {
    private final Context context;
    private final Runnable stopAction;
    private final Runnable restartAction;
    private final WindowManager windowManager;

    private LinearLayout root;
    private TextView statusView;
    private TextView stopView;
    private TextView restartView;
    private boolean attached = false;
    private boolean creationFailed = false;

    public StatusOverlay(Context context, Runnable stopAction, Runnable restartAction) {
        this.context = context.getApplicationContext();
        this.stopAction = stopAction;
        this.restartAction = restartAction;
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        if (attached || creationFailed) return;
        if (!Settings.canDrawOverlays(context)) return;

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(7), 0, dp(4), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC171717);
        bg.setCornerRadius(dp(8));
        root.setBackground(bg);

        statusView = new TextView(context);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(11);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(statusView, new LinearLayout.LayoutParams(0, -1, 1f));

        stopView = new TextView(context);
        stopView.setText("停止");
        stopView.setTextColor(Color.WHITE);
        stopView.setTextSize(10);
        stopView.setGravity(Gravity.CENTER);
        stopView.setPadding(dp(9), 0, dp(9), 0);
        stopView.setMinWidth(dp(44));
        stopView.setOnClickListener(v -> stopAction.run());

        GradientDrawable stopBg = new GradientDrawable();
        stopBg.setColor(0xD9B3261E);
        stopBg.setCornerRadius(dp(7));
        stopView.setBackground(stopBg);
        root.addView(stopView, new LinearLayout.LayoutParams(-2, dp(24)));

        // 停止/异常/完成后显示“重新开始”：由服务串行调度，从当前页面原地恢复，
        // 不重启淘宝；与“停止”按钮互斥显示。
        restartView = new TextView(context);
        restartView.setText("重新开始");
        restartView.setTextColor(Color.WHITE);
        restartView.setTextSize(10);
        restartView.setGravity(Gravity.CENTER);
        restartView.setPadding(dp(9), 0, dp(9), 0);
        restartView.setMinWidth(dp(56));
        restartView.setOnClickListener(v -> {
            restartView.setPressed(true);
            restartView.postDelayed(() -> {
                if (restartView != null) restartView.setPressed(false);
            }, 180);
            restartAction.run();
        });

        GradientDrawable restartBg = new GradientDrawable();
        restartBg.setColor(0xD91B7A45);
        restartBg.setCornerRadius(dp(7));
        restartView.setBackground(restartBg);
        root.addView(restartView, new LinearLayout.LayoutParams(-2, dp(24)));

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int width = Math.min(Math.max(dp(220), screenWidth - dp(16)), dp(330));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width,
                dp(30),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // FLAG_LAYOUT_IN_SCREEN uses physical screen coordinates, so offset the overlay by the
        // actual system status-bar / cutout inset. This avoids placing the bar behind the clock,
        // signal icons or a notch on devices with taller status bars.
        lp.y = topSafeInset() + dp(4);
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        try {
            windowManager.addView(root, lp);
            attached = true;
            update();
        } catch (Throwable e) {
            AppState.log("顶部悬浮状态条创建失败: " + e.getMessage());
            attached = false;
            creationFailed = true;
        }
    }

    public void hide() {
        if (attached && root != null) {
            try { windowManager.removeView(root); } catch (Throwable ignored) {}
        }
        attached = false;
        creationFailed = false;
        root = null;
        statusView = null;
        stopView = null;
        restartView = null;
    }

    public void update() {
        if (!attached || root == null) return;
        root.post(() -> {
            if (!attached || statusView == null || stopView == null || restartView == null) return;
            boolean running = AppState.running;
            statusView.setText(AppState.displayStatus());
            // 运行中显示“停止”；停止/异常/完成后切换为“重新开始”。
            stopView.setVisibility(running ? View.VISIBLE : View.GONE);
            restartView.setVisibility(running ? View.GONE : View.VISIBLE);
        });
    }

    public boolean isAttached() { return attached; }

    private int topSafeInset() {
        int inset = 0;

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowMetrics metrics = windowManager.getCurrentWindowMetrics();
                WindowInsets windowInsets = metrics.getWindowInsets();
                Insets safe = windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()
                );
                inset = Math.max(inset, safe.top);
            } catch (Throwable ignored) {
            }
        }

        // ROM fallback. Some vendors do not expose meaningful WindowInsets to an application
        // context used by TYPE_APPLICATION_OVERLAY.
        if (inset <= 0) {
            try {
                int id = context.getResources().getIdentifier(
                        "status_bar_height", "dimen", "android"
                );
                if (id > 0) inset = context.getResources().getDimensionPixelSize(id);
            } catch (Throwable ignored) {
            }
        }

        return Math.max(0, inset);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

package com.xsdanser.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v25 剩余时间提示条：常驻悬浮小窗，每秒只改文字，不再依赖系统 Toast。
 *
 * <p>问题：v24 用「同一个 Toast 反复 setText + show」实现最后一分钟逐秒倒数。
 * 小米盒子（Android 6 / MIUI）对系统 Toast 有单次显示时长和连续弹出频率限制，
 * 倒数几秒后后续 show() 被系统丢弃，屏幕上看不到后面的倒数。
 *
 * <p>方案：改成一个 TYPE_PHONE / TYPE_APPLICATION_OVERLAY 的轻量小窗，
 * 与已有的答题全屏浮层同一套权限，文字原地刷新，全程可见；不抢焦点、不拦截按键。
 * 弹题 / 加时 / 切方案 / 服务停止时由 QuizMode.resetReminderState() 主动收起；
 * 另有 3.2 秒看门狗：若 tick 中断（服务被杀等），提示条自动消失，不会残留。
 *
 * <p>若设备未授予悬浮窗权限导致 addView 失败，降级为「每次新建 Toast」，
 * 至少避免复用同一个 Toast 实例造成的丢字问题。
 */
public final class ReminderBanner {

    private ReminderBanner() { }

    private static final long WATCHDOG_MS = 3200L;
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Runnable HIDE = new Runnable() {
        @Override public void run() { hideNow(); }
    };

    private static TextView sView;
    private static WindowManager sWm;
    private static Toast sFallback;
    private static int sBuiltW = -1, sBuiltH = -1;

    /** 显示 / 更新提示文字；可任意线程调用，内部切主线程。 */
    public static void show(final Context app, final String text) {
        if (app == null || text == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            H.post(new Runnable() {
                @Override public void run() { show(app, text); }
            });
            return;
        }
        H.removeCallbacks(HIDE);
        if (ensureView(app)) {
            sView.setText(text);
            sView.setVisibility(View.VISIBLE);
        } else {
            try {
                if (sFallback != null) sFallback.cancel();
                sFallback = Toast.makeText(app, text, Toast.LENGTH_SHORT);
                sFallback.show();
            } catch (Throwable ignored) { }
        }
        // 倒数每秒都会再 show() 一次并顺延；tick 中断 3.2 秒后自动收起。
        H.postDelayed(HIDE, WATCHDOG_MS);
    }

    /** 立即收起提示条（弹题、加时、切换方案、时间归零）。 */
    public static void hide() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            H.post(new Runnable() {
                @Override public void run() { hide(); }
            });
            return;
        }
        H.removeCallbacks(HIDE);
        hideNow();
    }

    private static void hideNow() {
        if (sView != null) {
            try {
                if (sWm != null) sWm.removeViewImmediate(sView);
            } catch (Throwable ignored) { }
            sView = null;
            sWm = null;
            sBuiltW = -1;
            sBuiltH = -1;
        }
        if (sFallback != null) {
            try { sFallback.cancel(); } catch (Throwable ignored) { }
            sFallback = null;
        }
    }

    private static boolean ensureView(Context c) {
        final Context app = c.getApplicationContext();
        // v51：悬浮窗是进程内单例，窗口宽高/字号/内边距都是按创建时的
        // density 算出的像素。运行中切换分辨率（HDMI 输出变化、wm size）
        // 若不重建，4K 下会残留 720p 的小条。这里按物理像素检测并重建。
        ScreenFit.apply(app);       // 独立冷启动时也要先基准化
        int w = app.getResources().getDisplayMetrics().widthPixels;
        int h = app.getResources().getDisplayMetrics().heightPixels;
        if (sView != null) {
            if (sBuiltW == w && sBuiltH == h) return true;
            hideNow();
        }
        try {
            TextView t = new TextView(app);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xEE142238);
            bg.setCornerRadius(V10Ui.dp(app, 14));
            bg.setStroke(Math.max(1, V10Ui.dp(app, 2)), V10Ui.ACCENT);
            t.setBackground(bg);
            t.setTextColor(V10Ui.TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            t.setGravity(Gravity.CENTER);
            // v32：小米盒子（TV 过扫描 + MIUI 悬浮窗宽度限制）会把长文案单行裁掉。
            // 去掉单行限制、最多两行，并把宽度锁在屏宽安全区以内自动折行；
            // 短文案仍保持一行紧凑小条，文案内容一字不改。
            t.setSingleLine(false);
            t.setMaxLines(2);
            int safe = V10Ui.dp(app, 56);
            int maxW = app.getResources().getDisplayMetrics().widthPixels - safe * 2;
            if (maxW > V10Ui.dp(app, 200)) t.setMaxWidth(maxW);
            t.setPadding(V10Ui.dp(app, 28), V10Ui.dp(app, 11),
                    V10Ui.dp(app, 28), V10Ui.dp(app, 11));

            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.type = overlayType();
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            // v32：WRAP_CONTENT 在 MIUI TV 上会被压到约 1/3 屏宽，长文案被迫拆词折行。
            // 改成足够宽但不到全屏的固定小窗，文字居中一行读完，两侧仍留呼吸空间。
            int screenW = app.getResources().getDisplayMetrics().widthPixels;
            int panelW = Math.min(screenW - V10Ui.dp(app, 80), V10Ui.dp(app, 700));
            lp.width = Math.max(V10Ui.dp(app, 320), panelW);
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.y = V10Ui.dp(app, 44);
            wm.addView(t, lp);
            sView = t;
            sWm = wm;
            sBuiltW = w;
            sBuiltH = h;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** API 26+ 必须用 TYPE_APPLICATION_OVERLAY；老平台用 TYPE_PHONE（编译 stub 无新常量）。 */
    private static int overlayType() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                return ((Integer) WindowManager.LayoutParams.class
                        .getField("TYPE_APPLICATION_OVERLAY").get(null)).intValue();
            } catch (Throwable t) {
                return 2038;   // TYPE_APPLICATION_OVERLAY
            }
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }
}

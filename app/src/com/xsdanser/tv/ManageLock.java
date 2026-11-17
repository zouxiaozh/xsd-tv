package com.xsdanser.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v31「家长扫码管理」的远程锁屏：一个全屏、吞掉所有遥控器按键的悬浮层。
 *
 * <p>与答题遮罩同属 SYSTEM_ALERT_WINDOW 体系（应用已有该权限），只在家长从
 * 手机控制台点「锁屏」时出现；点「解除锁屏」或在电视端退出应用即消失。
 * 必须调用在主线程（ManageServer 已把 HTTP 动作 post 到主线程）。
 */
public final class ManageLock {

    private static volatile View sView;
    private static volatile WindowManager sWm;

    private ManageLock() { }

    public static boolean isLocked() { return sView != null; }

    public static synchronized void show(Context c, final String message) {
        if (sView != null) return;
        final Context app = c.getApplicationContext();
        try {
            LinearLayout root = new LinearLayout(app) {
                @Override public boolean dispatchKeyEvent(KeyEvent e) {
                    // 吞掉全部按键：方向键 / 返回 / 主页键之外的媒体键都无效
                    return true;
                }
            };
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            root.setBackgroundColor(0xF2090F1A);
            root.setClickable(true);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);

            LinearLayout card = V10Ui.vbox(app);
            card.setBackground(V10Ui.outlined(V10Ui.SURFACE, 24, app, 1, V10Ui.SELECT_DARK));
            card.setPadding(V10Ui.dp(app, 40), V10Ui.dp(app, 32),
                    V10Ui.dp(app, 40), V10Ui.dp(app, 32));
            card.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView badge = V10Ui.tv(app, "锁", 44, V10Ui.SELECT_LIGHT, Gravity.CENTER);
            badge.setBackground(V10Ui.round(V10Ui.SELECT_DARK, 22, app));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    V10Ui.dp(app, 96), V10Ui.dp(app, 96));
            blp.bottomMargin = V10Ui.dp(app, 22);
            card.addView(badge, blp);

            TextView title = V10Ui.tv(app, "家长已锁屏", 30, V10Ui.TEXT, Gravity.CENTER);
            card.addView(title, V10Ui.matchWrap());
            TextView sub = V10Ui.tv(app,
                    message == null || message.length() == 0
                            ? "休息一下吧 · 家长可在手机控制台解除" : message,
                    16, V10Ui.MUTED, Gravity.CENTER);
            sub.setPadding(0, V10Ui.dp(app, 12), 0, 0);
            card.addView(sub, V10Ui.matchWrap());

            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    V10Ui.dp(app, 520), ViewGroup.LayoutParams.WRAP_CONTENT);
            root.addView(card, clp);

            WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= 26
                    ? 2038 /* TYPE_APPLICATION_OVERLAY */
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.CENTER;
            wm.addView(root, lp);
            root.requestFocus();
            sWm = wm;
            sView = root;
        } catch (Throwable ignored) {
            sView = null;
            sWm = null;
        }
    }

    public static synchronized void hide() {
        View v = sView;
        WindowManager wm = sWm;
        sView = null;
        sWm = null;
        if (v != null && wm != null) {
            try { wm.removeViewImmediate(v); } catch (Throwable ignored) { }
        }
    }
}

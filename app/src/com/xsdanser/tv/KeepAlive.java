package com.xsdanser.tv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * 三管线保活自愈工具（对齐 Aerial 桌面 guard 机制）。
 *
 * 由「通知监听 / 无障碍 / 账号同步」三条系统级长绑定管线的回调共同调用：
 *  1) 拉起答题守护前台服务；
 *  2) 用 WRITE_SECURE_SETTINGS 把三条管线的系统配置写回去（强停被清后自我修复）；
 *  3) 恢复被系统禁用的管线组件。
 *
 * 隐私：不读取任何通知/无障碍事件内容，只借系统绑定通道做自恢复。
 * 兼容：为兼容项目老版编译桩，新 API（startForegroundService / checkSelfPermission）
 * 一律用反射或字符串常量调用，运行期按系统版本生效。
 */
public final class KeepAlive {
    public static final String TAG = "XsdAnser.KeepAlive";
    static final String PKG = "com.xsdanser.tv";
    static final String NOTIF_COMP = PKG + "/com.xsdanser.tv.RevivalListener";
    static final String ACC_COMP = PKG + "/com.xsdanser.tv.KeepAliveAccessibility";
    // Settings.Secure 键名（避免依赖新编译桩常量）
    static final String K_NOTIF_LISTENERS = "enabled_notification_listeners";
    static final String K_ACC_SERVICES = "enabled_accessibility_services";
    static final String K_ACC_ENABLED = "accessibility_enabled";

    private KeepAlive() {}

    /** 任一管线绑定成功时调用：拉起守护 + 自愈三管线。 */
    public static void onBound(Context c, String from) {
        Log.i(TAG, "管线绑定 [" + from + "]：开始自愈并复核答题守护服务");
        ensureGuard(c);
        selfHeal(c);
    }

    /** 拉起答题守护（前台服务）。重复调用无副作用，服务内部自行判重。 */
    public static void ensureGuard(Context c) {
        try {
            Intent s = new Intent(c, QuizOverlayService.class);
            s.setAction(QuizOverlayService.ACTION_START);
            startServiceCompat(c, s);
            Log.i(TAG, "已请求拉起答题守护服务");
        } catch (Throwable t) {
            Log.e(TAG, "拉起答题守护失败：" + t);
        }
    }

    /** 兼容后台启动限制：优先 startService，失败/受限降级 startForegroundService（反射）。 */
    public static void startServiceCompat(Context c, Intent i) {
        try {
            c.startService(i);
        } catch (Throwable t) {
            Log.i(TAG, "startService 受限，尝试 startForegroundService：" + t);
            try {
                java.lang.reflect.Method m = Context.class.getMethod("startForegroundService", Intent.class);
                m.invoke(c, i);
            } catch (Throwable t2) {
                Log.e(TAG, "启动服务两种路径均失败：" + t + " / " + t2);
            }
        }
    }

    /** 是否有 WRITE_SECURE_SETTINGS（反射 checkSelfPermission，兼容老桩）。 */
    private static boolean canWriteSecure(Context c) {
        try {
            java.lang.reflect.Method m = Context.class.getMethod("checkSelfPermission", String.class);
            Object r = m.invoke(c, "android.permission.WRITE_SECURE_SETTINGS");
            return r instanceof Integer && (Integer) r == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            // 反射不可用（极老系统）：退化为「尝试写入，失败即跳过」策略。
            return true;
        }
    }

    /** 复刻 Aerial：把三条绑定管线配置 / 被禁组件自愈写回系统安全设置。 */
    public static void selfHeal(Context c) {
        boolean writable = canWriteSecure(c);
        Log.i(TAG, "自愈开始，WRITE_SECURE_SETTINGS=" + writable);
        keepNotification(c, writable);
        if (!writable) {
            Log.w(TAG, "无 WRITE_SECURE_SETTINGS，跳过安全设置自愈（需 ADB 授权一次）");
            return;
        }
        try {
            keepAccessibility(c);
            keepComponents(c);
            KeepAliveSync.setupSync(c);
        } catch (Throwable t) {
            Log.e(TAG, "自愈过程异常：" + t);
        }
    }

    /** 保证自己的通知监听条目在 enabled_notification_listeners 中（追加，不覆盖他人）。 */
    private static void keepNotification(Context c, boolean writable) {
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), K_NOTIF_LISTENERS);
            boolean has = cur != null && cur.contains(NOTIF_COMP);
            if (!has && writable) {
                String next = (cur == null || cur.trim().isEmpty())
                        ? NOTIF_COMP : cur + ":" + NOTIF_COMP;
                Settings.Secure.putString(c.getContentResolver(), K_NOTIF_LISTENERS, next);
                Log.i(TAG, "已写回通知监听配置");
            }
        } catch (Throwable t) {
            Log.w(TAG, "写回通知监听失败：" + t);
        }
    }

    /** 保证无障碍服务开启（追加保留他人条目）。需 WRITE_SECURE_SETTINGS。 */
    private static void keepAccessibility(Context c) {
        try {
            String cur = Settings.Secure.getString(c.getContentResolver(), K_ACC_SERVICES);
            if (cur == null || !cur.contains(ACC_COMP)) {
                String next = (cur == null || cur.trim().isEmpty())
                        ? ACC_COMP : cur + ":" + ACC_COMP;
                Settings.Secure.putString(c.getContentResolver(), K_ACC_SERVICES, next);
                Log.i(TAG, "已写回无障碍服务配置");
            }
            Settings.Secure.putInt(c.getContentResolver(), K_ACC_ENABLED, 1);
        } catch (Throwable t) {
            Log.w(TAG, "写回无障碍配置失败：" + t);
        }
    }

    /** 恢复被系统禁用的三管线组件（对齐 Aerial setComponentEnabledSetting DEFAULT）。 */
    private static void keepComponents(Context c) {
        PackageManager pm = c.getPackageManager();
        ComponentName[] comps = new ComponentName[]{
                new ComponentName(c, RevivalListener.class),
                new ComponentName(c, KeepAliveAccessibility.class),
                new ComponentName(c, KeepAliveSync.SyncService.class),
                new ComponentName(c, KeepAliveSync.AccountService.class)
        };
        for (ComponentName cn : comps) {
            try {
                int st = pm.getComponentEnabledSetting(cn);
                if (st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    pm.setComponentEnabledSetting(cn,
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            PackageManager.DONT_KILL_APP);
                    Log.i(TAG, "已恢复被禁组件：" + cn.getClassName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "恢复组件失败 " + cn.getClassName() + "：" + t);
            }
        }
    }
}

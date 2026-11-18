package com.xsdanser.tv;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import android.util.Log;

/**
 * 复活监听服务（免 ROOT 常驻）—— 三管线保活之「管线①通知监听」。
 *
 * 原理：本服务被用户在系统「通知使用权」里授权后，Android 框架会长期绑定它。
 * 当电视待机/省电策略或系统清理把 App 进程清掉时，绑定断开，系统会自动重新绑定，
 * 于是本服务被拉起 —— 借此把答题守护服务重新拉起来，并用 WRITE_SECURE_SETTINGS
 * 把三条保活管线全部自愈写回（对齐 Aerial 桌面机制）。
 *
 * 隐私：完全不读取任何通知内容（不覆写 onNotificationPosted / onNotificationRemoved），
 * 只借系统绑定通道做自恢复。授权由手机端「学时兑守护」通过 ADB 自动开通。
 */
public class RevivalListener extends NotificationListenerService {

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(KeepAlive.TAG, "复活监听服务已创建");
    }

    /** 系统（重新）绑定成功时回调：自愈三管线 + 拉起答题守护服务。 */
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        KeepAlive.onBound(this, "通知监听");
    }

    /** 系统解绑（进程被清/被强停）时主动请求重绑，加速复活（API 24+）。 */
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.i(KeepAlive.TAG, "通知监听断开，请求系统重绑");
        try {
            java.lang.reflect.Method m = NotificationListenerService.class.getMethod(
                    "requestRebind", ComponentName.class);
            m.invoke(this, new ComponentName(this, RevivalListener.class));
        } catch (Throwable t) {
            Log.i(KeepAlive.TAG, "requestRebind 不可用（低版本正常）：" + t);
        }
    }

    @Override
    public void onDestroy() {
        Log.i(KeepAlive.TAG, "复活监听服务被销毁，等待系统重绑");
        super.onDestroy();
    }
}

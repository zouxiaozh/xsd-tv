package com.xsdanser.tv;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 三管线保活之「管线②无障碍服务」—— 第二重系统级长绑定。
 *
 * 无障碍服务由 AccessibilityManagerService 长期绑定，强停后系统会重新绑定，
 * 借此拉起答题守护并自愈三管线。与 Aerial 的 HomeGuardService 同机制。
 *
 * 隐私：不读取、不处理任何无障碍事件内容（onAccessibilityEvent 为空实现），
 * 只借系统绑定通道做自恢复。
 */
public class KeepAliveAccessibility extends AccessibilityService {

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(KeepAlive.TAG, "无障碍守护服务已连接");
        KeepAlive.onBound(this, "无障碍服务");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不读取任何事件内容，仅为满足接口要求。
    }

    @Override
    public void onInterrupt() {
        Log.i(KeepAlive.TAG, "无障碍守护服务被中断，等待系统重连");
    }

    @Override
    public void onDestroy() {
        Log.i(KeepAlive.TAG, "无障碍守护服务被销毁，等待系统重连");
        super.onDestroy();
    }
}

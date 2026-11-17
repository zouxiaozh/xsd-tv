package com.xsdanser.tv;

import android.app.Activity;

/**
 * v55 方案 A 桥接：守护在「非本 App 前台」（例如孩子正在看云视听等视频 App）时弹题，
 * 硬件视频层(HWC)会把答题遮罩压在下面 —— 孩子看不见也答不了 → quizShowing 永久 true
 * → 倒计时冻结 + 永不弹新题（即用户报的「看视频一段时间倒数/答题全消失」死锁）。
 *
 * 解决办法：把本 App 自己的全屏 Activity（MainActivity）提到前台（视频退后台，
 * 合成器把其 HWC 层藏掉），答题遮罩必然可见；答完后 moveTaskToBack 把用户送回原 App。
 *
 * 本类只持有「本 App 是否前台」与「承载页引用」两个进程内静态状态 —— 守护/答题页面
 * 同进程，故无需任何权限即可判断，比 UsageStats / GET_TASKS 更可靠，也不会触发弹窗。
 */
public final class QuizHost {
    private static volatile boolean sUiForeground = false;
    private static volatile boolean sForceHost = false;
    private static Activity sHost = null;

    /** 本 App 任意前台 Activity 的 onResume / onPause 调用，标记「本 App 是否在前台」。 */
    public static void markUiForeground(boolean f) { sUiForeground = f; }

    /**
     * 守护弹题时判断：true = 本 App 自己在前台（遮罩直接盖本 App，无需提承载页）；
     * false = 外部 App（视频等）在前台，需要把本 App 承载页提上来盖住其 HWC 层。
     * sForceHost 仅供验证包强制走「承载页」路径，正式包恒为 false。
     */
    public static boolean isUiForeground() { return sForceHost ? false : sUiForeground; }

    /** 被提上来的承载页（MainActivity host 模式）在 onCreate / onNewIntent 调用，登记自身。 */
    public static void setHost(Activity a) { sHost = a; }

    /** 答题结束：若本次是「劫持前台」模式，把用户送回被压在下面的原 App（moveTaskToBack）。 */
    public static void releaseHost() {
        Activity h = sHost;
        sHost = null;
        if (h != null) {
            try { h.moveTaskToBack(true); } catch (Throwable ignored) { }
            try { h.finish(); } catch (Throwable ignored) { }
        }
    }

    /** 仅供 ZYX_VERIFY_FORCE_HOST 验证包使用：强制走承载页路径，正式包请勿调用。 */
    public static void setForceHost(boolean f) { sForceHost = f; }
}

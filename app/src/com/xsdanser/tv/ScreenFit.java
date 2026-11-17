package com.xsdanser.tv;

import android.content.Context;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/**
 * v51：全分辨率统一适配（720p / 1080p / 2K / 4K / 8K）。
 *
 * 电视与盒子的分辨率和厂商上报的 density 经常不匹配：同一套 dp 布局在
 * 1280x720@320dpi 上会被撑出屏幕，在 3840x2160@320dpi 上又只占左上角
 * 一小块。这里以 1080p@320dpi（= 960x540dp）为设计基准，按屏幕物理像素
 * 重算 density，使「UI 占屏比例」在任何分辨率下恒定：
 *
 *   1280x720   -> density 1.333 (213dpi)   UI 相对 1080p 缩小 2/3
 *   1920x1080  -> density 2.000 (320dpi)   基准，与历史版本逐像素一致
 *   2560x1440  -> density 2.667 (427dpi)   UI 放大 4/3
 *   3840x2160  -> density 4.000 (640dpi)   UI 放大 2 倍
 *   7680x4320  -> density 8.000 (1280dpi)  UI 放大 4 倍
 *
 * 关键点：所有页面（Java 页面、历史 smali 页面、答题悬浮窗）都用同一个
 * Resources 的 DisplayMetrics，dp() 与 TextView 的 sp 换算都读它，所以
 * 一次 apply 全 App 生效，无需逐页改布局。
 *
 * 修改的是 DisplayMetrics 字段本身，不调用 updateConfiguration，不会触发
 * Activity 重建；系统因横竖屏/分辨率变化重置 density 时，下一次创建 View
 * 会通过 V10Ui / Ui 的懒调用自动重新 apply。
 */
public final class ScreenFit {
    private ScreenFit() {}

    /** 设计基准：1080p @ 320dpi 的可用逻辑尺寸。 */
    public static final float BASE_W_DP = 960f;
    public static final float BASE_H_DP = 540f;

    private static float sFontRatio = -1f;
    private static boolean sApplied = false;
    private static int sLastW = -1, sLastH = -1, sLastDpi = -1;
    private static float sLastDensity = -1f;

    /** 供测试/诊断使用：给定物理像素，返回应当采用的 density。 */
    public static float densityFor(int wPx, int hPx) {
        if (wPx <= 0 || hPx <= 0) return 0f;
        // 16:9 屏幕两项相等；非 16:9（4:3 投影、超宽屏）取较小值，保证不溢出。
        return Math.min(wPx / BASE_W_DP, hPx / BASE_H_DP);
    }

    /** 幂等。任何页面创建 View 之前调用一次即可。 */
    public static synchronized void apply(Context c) {
        if (c == null) return;
        try {
            Resources r = c.getResources();
            DisplayMetrics dm = r.getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            int dpi0 = (int) (densityFor(w, h) * 160f + 0.5f);
            // 快速路径：dp()/tv() 每次构建 View 都会调用，避免重复反射与写字段。
            if (sApplied && sLastW == w && sLastH == h && sLastDpi == dpi0
                    && dm.densityDpi == dpi0) return;
            float target = densityFor(w, h);
            if (target <= 0f) return;

            // 首次记录系统字体缩放比例，之后保持（避免每次 apply 把比例洗成 1）。
            if (sFontRatio <= 0f) {
                float d = dm.density;
                sFontRatio = d > 0f ? dm.scaledDensity / d : 1f;
                if (sFontRatio < 0.8f || sFontRatio > 1.6f) sFontRatio = 1f;
            }

            int dpi = (int) (target * 160f + 0.5f);
            dm.density = target;
            dm.densityDpi = dpi;
            dm.scaledDensity = target * sFontRatio;
            dm.xdpi = target * 160f;
            dm.ydpi = target * 160f;

            // 同步 Configuration，减少系统后续回写 density 的概率；反射以兼容旧 stub。
            try {
                java.lang.reflect.Field f =
                        android.content.res.Configuration.class.getField("densityDpi");
                f.setInt(r.getConfiguration(), dpi);
            } catch (Throwable ignore) { }

            sLastW = w;
            sLastH = h;
            sLastDpi = dpi;
            sLastDensity = target;
            sApplied = true;
        } catch (Throwable ignore) {
            // 任何异常都退回系统原 density，功能不受影响（等同 v50 行为）。
        }
    }

    /** 无 Context 的辅助 dp()（悬浮卡圆角等）用：返回当前基准 density。 */
    public static float density() {
        if (sLastDensity > 0f) return sLastDensity;
        return Resources.getSystem().getDisplayMetrics().density;
    }

    public static boolean applied() { return sApplied; }
}

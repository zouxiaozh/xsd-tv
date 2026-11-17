package com.xsdanser.tv;

import android.content.Context;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v62 答题遮罩全分辨率自适应（720p / 1080p / 2K / 4K / 8K、非 16:9、电视过扫描）+ v63 中文注音适配。
 *
 * 历史 smali 的 OverlayQuizView 用「题干 weight=1 + 一堆固定高度块」排版：
 * 答对后解析文字变长、或标题元信息多折一行，就会把 weight 题干挤到 0 高，
 * 表现为「题目被遮挡 / 直接看不见」。固定 20dp 的卡片边距在电视过扫描下
 * 又会连标题一起被裁掉。
 *
 * 这里以 ScreenFit 基准化后的屏幕逻辑高度为准，给题干、选项、解析、标题
 * 全部定高（不再有 weight 抢高度），并按题目长度与可用高度自动收敛题干字号；
 * 卡片边距按屏高比例放大，把内容收进过扫描安全区。
 *
 * v63 关键：中文注音（Pinyin）会给每个汉字上方预留约 0.48×字号 的拼音高度，
 * 使每行实际变高。若仍用固定高度 + maxLines 上限，开启注音后文字会被挤出
 * 可视区裁掉（题干、选项都看不见）。因此本类对注音「感知」：
 *  - 注音开启时题干字号整体降档、题干区域改用 wrap + min/maxHeight（永不裁切/永不消失）；
 *  - 选项区 gridPx 加高、applyPinyin 同步缩小选项字号，保证带拼音按钮完整可见；
 *  - 注音关闭时完全走原逻辑（零改动）。
 *
 * 本类是全新类，由 build_v30_ui.py 编入新 dex；历史 smali 的 OverlayQuizView
 * / QuizOverlayService 通过静态方法调用它，不改动它们的类定义。
 */
public final class QuizLayout {
    private QuizLayout() {}

    private static DisplayMetrics dm(Context c) {
        ScreenFit.apply(c);
        return c.getResources().getDisplayMetrics();
    }

    private static float density(Context c) {
        float d = dm(c).density;
        return d > 0f ? d : 1f;
    }

    /** ScreenFit 以 960x540dp 为基准；非 16:9 屏这个值只会更大，按它算不会溢出。 */
    private static float hDp(Context c) {
        return Math.max(320f, dm(c).heightPixels / density(c));
    }

    private static float wDp(Context c) {
        return Math.max(480f, dm(c).widthPixels / density(c));
    }

    private static int px(Context c, float dp) {
        return Math.round(dp * density(c));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float marginDp(Context c) {
        return clamp(hDp(c) * 0.055f, 28f, 48f);
    }

    /** 卡片四周安全边距：至少 28dp，屏越大越宽，抗电视过扫描裁切。 */
    public static int cardMarginPx(Context c) {
        return px(c, marginDp(c));
    }

    /** v63：是否开启中文注音（Pinyin.on）。关闭时本类所有分支都退回 v62 原行为。 */
    public static boolean pinyinOn(Context c) {
        try { return Pinyin.on(c); } catch (Throwable ignored) { return false; }
    }

    /**
     * 标题区定高（题源 + 元信息两行），长 meta 不再挤压题干。
     * 标题不带注音，高度与 v62 一致。
     */
    public static LinearLayout.LayoutParams topicLp(Context c) {
        int h = px(c, clamp(hDp(c) * 0.098f, 50f, 68f));
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
    }

    public static void fitTopic(TextView t, Context c) {
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.0f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
    }

    /**
     * 题干区域：关闭注音时为固定高度（与 v62 一致）；开启注音改为 wrap_content，
     * 由 fitQuestion 的 min/maxHeight 约束——既能随拼音文字长高而不被裁，又不会
     * 坍缩成 0 高（minHeight 兜底，避免与选项重叠）。
     */
    public static LinearLayout.LayoutParams questionLp(Context c) {
        if (pinyinOn(c)) {
            return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
        }
        int h = px(c, clamp(hDp(c) * 0.185f, 88f, 150f));
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
    }

    /** 题干非注音时的固定高度（同时作为注音时的下限 minHeight）。 */
    private static float qBaseH(Context c) {
        return clamp(hDp(c) * 0.185f, 88f, 150f);
    }

    /** 题干注音时的上限高度：比非注音多约 60%，容纳每行上方的拼音。 */
    private static float qMaxH(Context c) {
        return clamp(hDp(c) * 0.30f, 150f, 250f);
    }

    /** 选项区高度随屏高缩放，钳制 110~200dp；注音开启时加高到 150~250dp。 */
    public static int gridPx(Context c) {
        if (pinyinOn(c)) {
            return px(c, clamp(hDp(c) * 0.34f, 150f, 250f));
        }
        return px(c, clamp(hDp(c) * 0.305f, 110f, 200f));
    }

    /** 解析区定高，长解析不再把题干顶出可视区。标题/解析不带注音，与 v62 一致。 */
    public static LinearLayout.LayoutParams explainLp(Context c) {
        int h = px(c, clamp(hDp(c) * 0.070f, 34f, 58f));
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h);
    }

    public static void fitExplain(TextView t, Context c) {
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.05f);
    }

    /**
     * 题干字号与高度：在 QuizTextSizer 按长度分档基础上，再按「可用高度 / 估算行数」
     * 收敛到能完整显示的字号（下限 13sp）。不省略题干文字，长应用题也整题可见。
     *
     * v63：注音开启时，每行字上方多出约 0.46×字号 的拼音，整体行高增大，所以先整体
     * 降一档字号（×0.78）并把「可用高度」换成注音上限 qMaxH，循环里按带拼音的行高
     * 收敛，保证整题（含拼音）落在 maxHeight 内不被裁切；同时放宽 maxLines，并用
     * setMinHeight/setMaxHeight 双约束（区域永不被挤没、也永不超过上限）。
     */
    public static void fitQuestion(TextView t, String s, Context c) {
        ScreenFit.apply(c);
        String q = s == null ? "" : s;
        int len = q.length();
        boolean ruby = pinyinOn(c);
        int sp = QuizTextSizer.sp(q);
        // 卡片内容宽度 ≈ 屏宽 - 两侧安全边距 - 卡片内边距 - 题干自身左右 padding
        float cardW = Math.max(280f, wDp(c) - 2f * marginDp(c) - 112f);
        float capH = ruby ? qMaxH(c) : qBaseH(c);
        if (ruby) sp = Math.max(13, (int) (sp * 0.78f));   // 先给拼音让出高度
        int lines = 1;
        for (int guard = 0; guard < 14; guard++) {
            // v66：每个汉字的实际步进 = max(字宽, 拼音宽)。1 个汉字的拼音通常比它本身宽
            // 约 10~45%，所以注音时按 1.30×字号估算（否则行数估少，最后一行会被
            // setMaxHeight 裁掉——这正是「题干只露上半截 / 整行消失」的成因之一）。
            float adv = ruby ? sp * 1.30f : sp * 1.08f;
            int perLine = Math.max(8, (int) (cardW / adv));
            lines = Math.max(1, (len + perLine - 1) / perLine);
            // 带拼音时的真实行高：汉字行高 + 首行上方的拼音高度
            float lineH = sp * 1.30f + (ruby ? sp * 0.34f : 0f);
            if (lines * lineH <= capH || sp <= 13) break;
            sp -= 2;
        }
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setMaxLines(Math.max(3, Math.min(lines + (ruby ? 2 : 1), ruby ? 7 : 5)));
        t.setEllipsize(null);              // 题干绝不截断
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.10f);
        float minH = ruby ? clamp(hDp(c) * 0.12f, 70f, 120f) : qBaseH(c);
        t.setMinHeight(px(c, minH));
        t.setMaxHeight(px(c, capH));
        if (ruby) {
            // v66：RubySpan 不再抬高行框，拼音画在字格上方约 0.31×字号px 的行间空隙里。
            // 顶部内边距只需盖住这段空隙（0.34×字号 + 1dp）；多留会把文字整体压低，
            // 在 setMaxHeight 限制下反而把最后一行顶出可视区。底部不留白，
            // 左右保持 24dp 与历史 smali 一致。
            int topPad = px(c, sp * 0.34f + 1f);
            t.setPadding(px(c, 24f), topPad, px(c, 24f), 0);
        }
    }

    /**
     * v63：选项按钮在注音开启时的字号。原 smali 用 26sp，挂上拼音后每行变高，
     * 四个按钮在固定选项区内会顶满/溢出；降到约 0.66 倍（≈17sp）给拼音留高度，
     * 仍清晰可读。仅在注音开启时由 applyPinyin 调用，关闭时零改动。
     */
    public static float pinyinOptionSp() {
        return 17f;
    }
}

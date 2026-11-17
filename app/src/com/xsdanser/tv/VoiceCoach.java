package com.xsdanser.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * v23 答错反馈：<b>纯文字思路 + 全屏控屏倒计时</b>，并按方案联动「选项重排」。
 *
 * <p>已彻底删除语音朗读：没有 TTS 引擎绑定、在线合成、发音人、语速、试听与预热。
 * 类名保留 VoiceCoach 是为了兼容 base dex 的 smali 调用
 * （QuizOverlayService 只调用 isLocked() / onWrong() 两个静态方法）。
 *
 * <p>严谨守护：答错后弹重做过的思路卡片，只给题干与题库 explain（经
 * AnswerShuffle.maskAnswer 遮去结果文字），不再显示答案，同时锁定
 * 遥控器按键直到倒计时结束；<b>倒计时结束会调用 AnswerShuffle.onLockEnded()，
 * 把当前题四个选项打乱后重答</b>，避免孩子记住「选 A」蒙答案。
 * 时长由「家长设置 → 答题规则 → 答错停留」决定（两种方案共用，可设为 0 关闭）。
 *
 * <p>自主可控：答错后弹的也是这张控屏卡（界面与功能同严谨守护），
 * 仅停留时长由「家长设置 → 答题规则 → 自主可控 → 答错停留」控制；
 * 本类的 isLocked() 两种方案统一以 lockedUntil 为准。
 *
 * <p>控屏不新开可聚焦窗口：全屏浮层带 FLAG_NOT_FOCUSABLE，只吃触摸不吃遥控器焦点，
 * 按键仍回到原答题窗口，但 QuizOverlayService.answer() 开头的 isLocked() 守卫会丢弃作答。
 */
public final class VoiceCoach {

    public static final String PREF = "tvbank";
    public static final String K_LOCK = "voice_lock_sec";

    public static final int[] LOCK_STEPS = {0, 30, 60, 90, 120};

    private static final int MASK     = 0xE6090F1A;
    private static final int CARD     = 0xFF17233A;
    private static final int TEXT     = 0xFFF1F5F9;
    private static final int MUTED    = 0xFF9FB0CC;
    private static final int BLUE     = 0xFF2563EB;
    private static final int AMBER    = 0xFFD97706;
    private static final int AMBER_LT = 0xFFFBBF24;
    private static final int BORDER   = 0xFF31507E;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static volatile long lockedUntil = 0L;
    private static View overlay;
    private static WindowManager wmRef;
    private static int generation = 0;

    private VoiceCoach() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------- 控屏时长（唯一保留的设置项，归入「答题规则 → 严谨守护」） ----------

    public static int lockSec(Context c) { return prefs(c).getInt(K_LOCK, 30); }

    public static void setLockSec(Context c, int sec) {
        prefs(c).edit().putInt(K_LOCK, sec).commit();
    }

    /** 两种方案共用同一停留时长：严谨守护是控屏卡倒计时，自主可控是解析卡倒计时。 */
    public static String lockLabel(Context c) {
        int s = lockSec(c);
        return s <= 0 ? "关闭" : s + " 秒";
    }

    public static void cycleLock(Context c) {
        int cur = lockSec(c);
        int next = LOCK_STEPS[0];
        for (int i = 0; i < LOCK_STEPS.length; i++) {
            if (LOCK_STEPS[i] > cur) { next = LOCK_STEPS[i]; break; }
        }
        setLockSec(c, next);
    }

    // ---------- 判题入口守卫 ----------

    /** QuizOverlayService.answer() 开头调用：控屏 / 解析卡展示期间丢弃一切作答。
     *  两种方案现共用同一张控屏卡，统一以 lockedUntil 为准；
     *  AnswerShuffle.isBlocking() 仍作为自主可控的兜底判定。 */
    public static boolean isLocked() {
        if (QuizMode.isFreeCached())
            return System.currentTimeMillis() < lockedUntil || AnswerShuffle.isBlocking();
        return System.currentTimeMillis() < lockedUntil;
    }

    /** 答错时调用。q 来自题库，explain 只作为思路提示，不再显示结果文字。 */
    public static void onWrong(Context ctx, QuizEngine.Question q, int wrongIdx) {
        if (q == null) return;
        final Context c = ctx.getApplicationContext();

        int sec = lockSec(c);
        if (sec <= 0) {
            // 家长关掉了控屏：也把选项重排一次，保证两种时长设置下行为一致。
            H.postDelayed(new Runnable() {
                @Override public void run() { AnswerShuffle.onLockEnded(); }
            }, 300L);
            return;
        }

        String exp = AnswerShuffle.maskAnswer(
                q.explain == null ? "" : q.explain.trim(), q);
        String stem = q.text == null ? "" : q.text.trim();

        lockedUntil = System.currentTimeMillis() + sec * 1000L;
        showOverlay(c, stem, exp, sec);
    }

    // ---------- 控屏浮层（纯文字，v22 重做版式） ----------

    private static final class MaskRoot extends FrameLayout {
        MaskRoot(Context c) { super(c); }
        @Override public boolean dispatchKeyEvent(KeyEvent e) { return true; }
    }

    private static void showOverlay(Context c, String stem, String exp, int sec) {
        dismissOverlay();
        final int gen = ++generation;

        MaskRoot root = new MaskRoot(c);
        root.setBackgroundColor(MASK);
        root.setClickable(true);

        LinearLayout card = box(c);
        card.setBackground(round(CARD, 26, BORDER, 2));
        card.setPadding(dp(c, 36), dp(c, 22), dp(c, 36), dp(c, 22));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        cp.setMargins(dp(c, 76), dp(c, 20), dp(c, 76), dp(c, 20));
        card.setLayoutParams(cp);

        // 顶部：温和胶囊 + 状态
        LinearLayout head = hbox(c);
        TextView tag = tv(c, "稍作休息 · 看看这道题", 13, 0xFFFFFFFF, Gravity.CENTER);
        tag.setBackground(round(BLUE, 12, 0, 0));
        tag.setPadding(dp(c, 12), dp(c, 5), dp(c, 12), dp(c, 5));
        head.addView(tag, wrap());
        View sp = new View(c);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(tv(c, "遥控器暂时休息", 13, MUTED, Gravity.RIGHT), wrap());
        card.addView(head, matchWrap(c, 0));

        card.addView(tv(c, "再想一想", 29, TEXT, Gravity.CENTER), matchWrap(c, 14));
        if (!stem.isEmpty()) {
            TextView st = tv(c, stem, 17, MUTED, Gravity.CENTER);
            st.setMaxLines(3);
            card.addView(st, matchWrap(c, 8));
        }

        View rule = new View(c);
        rule.setBackgroundColor(0xFF30415F);
        card.addView(rule, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));

        // 思路：左侧琥珀竖条（v23 起不再展示答案文字）
        LinearLayout expRow = hbox(c);
        View bar = new View(c);
        bar.setBackgroundColor(AMBER);
        expRow.addView(bar, new LinearLayout.LayoutParams(dp(c, 3),
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView et = tv(c, "思路  ·  " + (exp.isEmpty() ? "本题暂无文字思路，先自己想一想" : exp),
                19, TEXT, Gravity.LEFT);
        et.setPadding(dp(c, 14), dp(c, 2), 0, dp(c, 2));
        et.setLineSpacing(0, 1.3f);
        expRow.addView(et, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(expRow, matchWrap(c, 14));

        // 倒计时：大数字 + 进度条
        LinearLayout cdRow = hbox(c);
        final TextView cd = tv(c, String.valueOf(sec), 38, AMBER_LT, Gravity.LEFT);
        cd.setMinWidth(dp(c, 74));
        cdRow.addView(cd, wrap());
        cdRow.addView(tv(c, "秒后可以继续作答", 15, MUTED, Gravity.LEFT), wrap());
        card.addView(cdRow, matchWrap(c, 16));

        final View fill = new View(c);
        fill.setBackground(round(AMBER, 5, 0, 0));
        final FrameLayout track = new FrameLayout(c);
        track.setBackground(round(0xFF2A3B58, 5, 0, 0));
        track.addView(fill, new FrameLayout.LayoutParams(0, dp(c, 7)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 7));
        tlp.topMargin = dp(c, 10);
        card.addView(track, tlp);

        card.addView(tv(c, "倒计时结束后四个选项会重新排列 · 再看一遍题目作答",
                13, MUTED, Gravity.CENTER), matchWrap(c, 12));
        root.addView(card);

        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26 ? 2038 : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | 0x00000002,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.dimAmount = 0.9f;
        try {
            wm.addView(root, lp);
            overlay = root;
            wmRef = wm;
        } catch (Exception e) {
            lockedUntil = 0L;
            return;
        }

        final int[] left = {sec};
        final int total = sec;
        final long start = System.currentTimeMillis();
        H.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != generation || System.currentTimeMillis() >= lockedUntil) {
                    lockedUntil = 0L;
                    dismissOverlay();
                    AnswerShuffle.onLockEnded();
                    return;
                }
                long used = System.currentTimeMillis() - start;
                left[0] = Math.max(1, total - (int) (used / 1000L));
                cd.setText(String.valueOf(left[0]));
                int pct = (int) Math.max(0L, 100L - used * 100L / (total * 1000L));
                FrameLayout.LayoutParams fp =
                        (FrameLayout.LayoutParams) fill.getLayoutParams();
                fp.width = track.getWidth() * pct / 100;
                fill.setLayoutParams(fp);
                H.postDelayed(this, 200L);
            }
        }, 200L);
    }

    private static void dismissOverlay() {
        View v = overlay;
        overlay = null;
        if (v != null && wmRef != null) {
            try { wmRef.removeViewImmediate(v); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static LinearLayout box(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private static GradientDrawable round(int fill, int radiusDp, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(sysDp(radiusDp));
        if (strokePx > 0) g.setStroke(sysDp(strokePx), stroke);
        return g;
    }

    private static int sysDp(int v) {
        // v51：与全局基准 density 对齐
        return (int) (v * ScreenFit.density() + 0.5f);
    }

    private static TextView tv(Context c, String s, int sp, int color, int gravity) {
        ScreenFit.apply(c);   // v51
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(gravity);
        t.setLineSpacing(0, 1.25f);
        return t;
    }

    private static LinearLayout.LayoutParams matchWrap(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Context c, int v) {
        ScreenFit.apply(c);   // v51
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }
}

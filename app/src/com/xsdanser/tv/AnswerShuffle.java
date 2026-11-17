package com.xsdanser.tv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * v23「答错之后」的统一处理：显示思路、打乱四个选项、重答本题、累计答错次数。
 *
 * <p>设计约束：本工程 classes.dex 的答题浮层 / 服务来自带 v5~v14 历史补丁的 smali，
 * Java 源码落后于补丁，不能重编译。所以本类只通过 2 个新增 smali 挂接拿上下文：
 * <ul>
 *   <li>{@code OverlayQuizView.bind()} 开头 -> {@link #onBind}</li>
 *   <li>{@code QuizOverlayService.answer()} 答错分支 -> {@link #onWrong}</li>
 * </ul>
 * 之后所有动作都直接调用 {@code OverlayQuizView} 的 public 方法，不改历史 smali 逻辑。
 *
 * <p>v23 口径：答错弹窗不再出现答案文字；答题浮层的 explain 区由 {@link #decorate}
 * 直接隐藏，历史 smali 在 bind / correct 时写回的题库解析因此始终不可见。
 *
 * <p>两种方案第 1 次答错弹的是<b>同一张思路控屏卡</b>（界面与功能完全一致，
 * 均由 VoiceCoach 负责）：展示文字思路并控屏，倒计时结束调用
 * {@link #onLockEnded()} 把当前题的四个选项打乱后重答，避免记住位置蒙答案；
 * 停留时长由家长在「答错停留」里设置（默认 30 秒），两种方案共用。不扣时间、不惩罚。</p>
 *
 * <p><b>v35：第 2 次答错两种模式完全一致</b>——不再调用 onWrong / VoiceCoach，
 * 不弹任何卡片、不重排选项，由 {@link #onFinalWrong()} 收干净本题状态，
 * QuizOverlayService 直接把原题记入错题库并立即换一道新题。
 *
 * <p>解析卡刻意做成 FLAG_NOT_FOCUSABLE 的不可聚焦浮层（与控屏同一套窗口策略），
 * 不抢遥控器焦点；解析展示期间由 {@link #isBlocking()} 让判题入口丢弃作答。
 */
public final class AnswerShuffle {

    // 解析卡停留时长由 VoiceCoach.lockSec(c) 决定（家长可设，默认 30 秒），两种方案共用。
    // 下面这行常量仅作构建门禁的版本标记保留，运行时已不再直接使用。
    private static final long EXPLAIN_MS = 20000L;

    private static final int MASK = 0xE6090F1A;
    private static final int CARD = 0xFF17233A;
    private static final int TEXT = 0xFFF1F5F9;
    private static final int MUTED = 0xFF9FB0CC;
    private static final int BLUE = 0xFF2563EB;
    private static final int BLUE_SOFT = 0xFF60A5FA;
    private static final int AMBER = 0xFFD97706;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Random R = new Random();

    private static volatile WeakReference<OverlayQuizView> sViewRef;
    private static volatile QuizEngine.Question sQuestion;
    private static volatile String sMeta = "";
    private static volatile Context sApp;
    private static volatile String sKey = "";
    private static volatile int sWrongCount = 0;
    private static volatile long sBlockUntil = 0L;
    private static volatile WeakReference<View> sExplainRef;
    private static volatile WindowManager sWm;
    private static volatile int sGeneration = 0;
    private static volatile WeakReference<OverlayQuizView> sDecorated;
    /** 重排 rebind 会走 onBind，但那是同一道题，不能把答错次数清零。 */
    private static volatile boolean sKeepCountOnNextBind = false;

    private AnswerShuffle() { }

    // ---------------------------------------------------------------- 挂接入口

    /** smali 挂接：OverlayQuizView.bind() 开头。缓存当前题、浮层与题面 meta。 */
    public static void onBind(OverlayQuizView view, QuizEngine.Question q, String meta) {
        if (view == null || q == null) return;
        sViewRef = new WeakReference<OverlayQuizView>(view);
        sQuestion = q;
        sMeta = meta == null ? "" : meta;
        sApp = view.view().getContext().getApplicationContext();
        String key = safeKey(q);
        if (sKeepCountOnNextBind) {
            // 打乱选项后的 rebind：还是同一道题，答错次数必须继续累计
            sKey = key;
        } else if (!key.equals(sKey)) {
            sKey = key;
            sWrongCount = 0;          // 真正换题才归零
        }
        decorate(view);
        applyPinyin(view, q);
        applyLocalBankLabel(view);
    }

    /**
     * v31 中文注音：onBind 是 bind() 的开头，历史 smali 之后才会 setText，
     * 所以这里 post 到消息队列，等 bind() 写完题干和四个选项再反射替换成
     * 带拼音的 CharSequence。开关关闭时完全不介入，旧行为零改动。
     */
    private static void applyPinyin(final OverlayQuizView view, final QuizEngine.Question q) {
        final Context c = sApp != null ? sApp : view.view().getContext();
        if (!Pinyin.on(c)) return;
        final float optSp = QuizLayout.pinyinOptionSp();
        // v65：内边距必须按真实密度换算成 px。v63 把 sp 数值直接当 px 用
        //（topPad≈12px 恒定），在模拟器密度上碰巧够、在小米盒子上不足，
        // 拼音被顶出按钮上沿被裁（"遮挡"）。RubySpan.draw 现在锚定汉字视觉顶，
        // 单行 BoringLayout 按钮的拼音在行顶上方约 0.55×字号px，顶部内边距
        // 覆盖 0.60×字号px + 2dp 即可完整收进按钮。
        final float density = c.getResources().getDisplayMetrics().density;
        final float optPx = optSp * (density > 0f ? density : 1f);
        final int sidePad = (int) (optPx * 0.42f + 4f * (density > 0f ? density : 1f));
        // v66: 上下内边距必须对称。按钮 gravity 是 CENTER_VERTICAL|LEFT，
        // 非对称内边距（v65 上 0.60em / 下 0.18em）会把文字整体下移，
        // 配合行框膨胀就会把汉字顶出按钮边界——只留拼音。对称即保持原居中。
        final int vPad = (int) (optPx * 0.06f);
        final Runnable apply = new Runnable() {
            @Override public void run() {
                try {
                    Field tf = OverlayQuizView.class.getDeclaredField("text");
                    tf.setAccessible(true);
                    TextView t = (TextView) tf.get(view);
                    if (t != null) {
                        String want = q.text == null ? "" : q.text;
                        CharSequence cur = t.getText();
                        if (!hasRuby(cur) && cur != null && cur.toString().equals(want)) {
                            t.setText(Pinyin.ruby(want));
                        }
                    }

                    Field of = OverlayQuizView.class.getDeclaredField("opts");
                    of.setAccessible(true);
                    Button[] bs = (Button[]) of.get(view);
                    if (bs == null || q.options == null) return;
                    for (int i = 0; i < bs.length && i < q.options.length; i++) {
                        if (bs[i] == null) continue;
                        // v63: option buttons get a smaller size so pinyin fits the grid.
                        // v66: symmetric vertical padding keeps the previous centering.
                        bs[i].setTextSize(TypedValue.COMPLEX_UNIT_SP, optSp);
                        bs[i].setPadding(sidePad, vPad, sidePad, vPad);
                        // v65: keep smali's "A + 3 spaces + option" prefix so A/B/C/D stays.
                        // v66: only rewrite when this button still holds THIS question's
                        // plain text; an option already carrying ruby, or belonging to a
                        // different question, is left untouched.
                        String plain = q.options[i] == null ? "" : q.options[i];
                        String withLetter = (char) ('A' + i) + "   " + plain;
                        CharSequence cu = bs[i].getText();
                        String cu2 = cu == null ? "" : cu.toString();
                        // 兼容两种写法：带 "A   " 前缀（smali 原样）或不带前缀。
                        if (!hasRuby(cu) && (cu2.equals(withLetter) || cu2.equals(plain))) {
                            bs[i].setText(Pinyin.ruby(withLetter));
                        }
                    }
                } catch (Throwable ignored) { }
            }
        };
        final View ov = view.view();
        ov.post(apply);
        // v66: bind() may run again right after us (reshuffle / size calibration) and
        // reset some buttons to plain text. Re-applying is now harmless because the line
        // box is untouched and every write is idempotent, so two extra delayed passes
        // heal the "3 of 4 options have pinyin" inconsistency.
        ov.postDelayed(apply, 180);
        ov.postDelayed(apply, 460);
    }

    /** 文本是否已含注音 RubySpan（避免重复写入 / 误改其它题）。 */
    private static boolean hasRuby(CharSequence cs) {
        if (cs instanceof Spannable) {
            Spannable s = (Spannable) cs;
            return s.getSpans(0, s.length(), Pinyin.RubySpan.class).length > 0;
        }
        return false;
    }

    /**
     * v45：答题界面顶部的题库来源统一显示「本地题库」。
     * OverlayQuizView 是历史 smali 类，不参与 Java 重编译，里面写死的是「在线题库」；
     * 同 applyPinyin 一样 post 到 bind() 写完文本之后，再反射把该词替换掉，
     * 历史出题逻辑、配色补丁与 dex 结构全部保持零改动。
     */
    private static void applyLocalBankLabel(final OverlayQuizView view) {
        view.view().post(new Runnable() {
            @Override public void run() {
                try {
                    Field f = OverlayQuizView.class.getDeclaredField("topic");
                    f.setAccessible(true);
                    TextView tv = (TextView) f.get(view);
                    if (tv == null) return;
                    CharSequence cs = tv.getText();
                    if (cs == null) return;
                    String s = cs.toString();
                    int i = s.indexOf("在线题库");
                    if (i >= 0) {
                        tv.setText(s.substring(0, i) + "本地题库"
                                + s.substring(i + "在线题库".length()));
                    }
                } catch (Throwable ignored) { }
            }
        });
    }

    /** smali 挂接：QuizOverlayService.answer() 判定答错之后。 */
    public static void onWrong(int wrongIdx) {
        OverlayQuizView view = view();
        QuizEngine.Question q = sQuestion;
        if (view == null || q == null) return;
        sWrongCount++;
        Context c = sApp != null ? sApp : view.view().getContext();
        // 两种方案都改由 VoiceCoach.onWrong 弹同一张控屏卡（界面/功能一致）；
        // 这里只登记本题答错次数，等控屏结束回调 onLockEnded() 重排选项。
    }

    /**
     * v35：本题第 2 次答错（最终判定）。此时 QuizOverlayService 会直接把原题记入
     * 错题库并换一道新题，<b>不弹解析卡、不弹思路控屏卡、不再重排选项</b>。
     *
     * <p>这里只负责把「本题」的临时状态收干净：
     * <ul>
     *   <li>{@code sGeneration++} 让第 1 次答错那张解析卡的停留时长延时回调失效，
     *       否则它会在换题后把旧题重新 bind 回来；</li>
     *   <li>{@code sQuestion = null} 同时挡住 VoiceCoach 控屏结束的 onLockEnded()
     *       重排回调（shuffleAndRebind 遇到 q == null 会直接返回）；</li>
     *   <li>清空答错次数、阻塞时间与题目 key，保证换题后的新题从 0 开始计数。</li>
     * </ul>
     * 随后 answer() 里的 bind() 会经 onBind() 重新填回新题的 view / question / key。
     */
    public static void onFinalWrong() {
        sGeneration++;
        dismissExplain(false);
        sWrongCount = 0;
        sKey = "";
        sQuestion = null;
        sKeepCountOnNextBind = false;
        sBlockUntil = 0L;
    }

    /** 答对时重置本题计数（由 QuizMode.correctHint() 调用，两种模式都覆盖）。 */
    public static void onCorrect() {
        sWrongCount = 0;
    }

    /** VoiceCoach 控屏倒计时结束时调用：严谨守护打乱选项后重答本题。 */
    public static void onLockEnded() {
        shuffleAndRebind(false);
    }

    /** 自主可控：解析卡展示期间判题入口丢弃作答，但不锁遥控器、不叫控屏。 */
    public static boolean isBlocking() {
        return System.currentTimeMillis() < sBlockUntil;
    }

    // ---------------------------------------------------------------- 解析卡（自主可控）

    private static final class ExplainRoot extends FrameLayout {
        ExplainRoot(Context c) { super(c); }
        @Override public boolean dispatchKeyEvent(KeyEvent e) { return true; }
    }

    private static void showExplain(Context c, QuizEngine.Question q) {
        dismissExplain(false);
        final int gen = ++sGeneration;
        // 与严谨守护控屏卡共用同一家长可设时长（VoiceCoach.lockSec，默认 30 秒）。
        final long holdMs = VoiceCoach.lockSec(c) * 1000L;
        sBlockUntil = System.currentTimeMillis() + holdMs;

        String stem = q.text == null ? "" : q.text.trim();
        String exp = maskAnswer(q.explain == null ? "" : q.explain.trim(), q);

        ExplainRoot root = new ExplainRoot(c);
        root.setBackgroundColor(MASK);
        root.setClickable(true);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(CARD, 24, 0xFF31507E, 2));
        card.setPadding(dp(c, 34), dp(c, 22), dp(c, 34), dp(c, 22));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        cp.setMargins(dp(c, 80), dp(c, 20), dp(c, 80), dp(c, 20));
        card.setLayoutParams(cp);

        // 顶部一行：温和标签 + 本题答错次数
        LinearLayout head = hbox(c);
        TextView tag = tv(c, "再看一眼", 13, 0xFFFFFFFF, Gravity.CENTER);
        tag.setBackground(round(BLUE, 12, 0, 0));
        tag.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
        head.addView(tag, wrap(c));
        View spacer = new View(c);
        head.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        head.addView(tv(c, "本题已答错 " + sWrongCount + " 次", 13,
                0xFFFBBF24, Gravity.RIGHT), wrap(c));
        card.addView(head, matchWrap(c, 0));

        card.addView(tv(c, "先看看思路，再试一次", 25, TEXT, Gravity.CENTER),
                matchWrap(c, 14));
        if (!stem.isEmpty()) {
            TextView st = tv(c, stem, 16, MUTED, Gravity.CENTER);
            st.setMaxLines(3);
            card.addView(st, matchWrap(c, 8));
        }

        // 思路：左侧蓝色竖条 + 文本（不显示答案文字）
        LinearLayout expRow = hbox(c);
        View bar = new View(c);
        bar.setBackgroundColor(BLUE_SOFT);
        expRow.addView(bar, new LinearLayout.LayoutParams(dp(c, 3),
                ViewGroup.LayoutParams.MATCH_PARENT));
        TextView et = tv(c, "思路  ·  " + (exp.isEmpty() ? "本题暂无文字思路，先自己想一想再看题目" : exp),
                19, TEXT, Gravity.LEFT);
        et.setPadding(dp(c, 14), dp(c, 2), 0, dp(c, 2));
        et.setLineSpacing(0, 1.3f);
        expRow.addView(et, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(expRow, matchWrap(c, 16));

        // 进度条
        final View fill = new View(c);
        fill.setBackground(round(BLUE_SOFT, 4, 0, 0));
        FrameLayout track = new FrameLayout(c);
        track.setBackground(round(0xFF2A3B58, 4, 0, 0));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(0, dp(c, 6));
        track.addView(fill, fp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 6));
        tlp.topMargin = dp(c, 18);
        card.addView(track, tlp);
        final int total = (int) holdMs;
        final long start = System.currentTimeMillis();
        final Runnable progress = new Runnable() {
            @Override public void run() {
                if (gen != sGeneration) return;
                long used = System.currentTimeMillis() - start;
                int pct = (int) Math.min(100L, used * 100L / total);
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) fill.getLayoutParams();
                int full = track.getWidth();
                lp.width = full * pct / 100;
                fill.setLayoutParams(lp);
                if (used < total) H.postDelayed(this, 80L);
            }
        };
        H.postDelayed(progress, 80L);

        card.addView(tv(c, "马上把四个选项重新排一次 · 这道题再答一遍就好",
                13, MUTED, Gravity.CENTER), matchWrap(c, 12));
        root.addView(card);

        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        int type = Build.VERSION.SDK_INT >= 26 ? 2038 : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | 0x00000002 /* FLAG_DIM_BEHIND */,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.dimAmount = 0.86f;
        try {
            wm.addView(root, lp);
            sExplainRef = new WeakReference<View>(root);
            sWm = wm;
        } catch (Throwable t) {
            sBlockUntil = 0L;
            return;
        }
        H.postDelayed(new Runnable() {
            @Override public void run() {
                if (gen != sGeneration) return;
                dismissExplain(false);
                shuffleAndRebind(true);
            }
        }, holdMs);
    }

    private static void dismissExplain(boolean bump) {
        if (bump) sGeneration++;
        sBlockUntil = 0L;
        View v = sExplainRef == null ? null : sExplainRef.get();
        sExplainRef = null;
        if (v != null && sWm != null) {
            try { sWm.removeViewImmediate(v); } catch (Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- 打乱 + 重答本题

    private static void shuffleAndRebind(boolean free) {
        OverlayQuizView view = view();
        QuizEngine.Question q = sQuestion;
        if (view == null || q == null) return;
        try {
            if (!QuizOverlayService.isQuizShowing()) return;   // 用户已经离开答题页
        } catch (Throwable ignored) { }

        shuffleOptions(q);

        // v34：bind() 内部的 QuizFlow.reset() 会把本题答错次数清零。若不恢复，
        // 默认控屏时长下「答错 2 次换题 + 记入错题库」永远触发不了：
        // 每次答错 -> 控屏 -> 选项重排 -> rebind -> 计数归零。
        // sWrongCount 由 onWrong/onCorrect/onBind 维护，只在真正换题时归零，
        // 正是本题累计答错次数，用它把状态机计数补回去。
        int keepWrong = sWrongCount;
        try {
            sKeepCountOnNextBind = true;
            view.bind(q, sMeta);   // 同一题、选项重排；内部会 flow.reset()、重画四个按钮
        } catch (Throwable t) {
            sKeepCountOnNextBind = false;
            return;
        } finally {
            sKeepCountOnNextBind = false;
        }

        try {
            QuizFlow flow = view.flow();
            Field wf = QuizFlow.class.getDeclaredField("wrongAttempts");
            wf.setAccessible(true);
            wf.setInt(flow, keepWrong);
        } catch (Throwable ignored) { }

        int n = sWrongCount;
        String hint = free
                ? "选项已重新排列 · 本题已答错 " + n + " 次 · 再看一遍，选一个试试"
                : "选项已重新排列 · 再看一遍题目，重新作答";
        try { view.hint(hint); } catch (Throwable ignored) { }
        // v23：不再把解析回写进答题浮层，explain 区统一由 decorate() 隐藏。
    }

    /** Fisher–Yates 原地打乱，保证与旧顺序不同，并用对象引用重算正确下标。 */
    private static void shuffleOptions(QuizEngine.Question q) {
        String[] o = q.options;
        if (o == null || o.length < 2) return;
        int n = o.length;
        int ai = q.answerIndex;
        if (ai < 0 || ai >= n) return;
        String correct = o[ai];
        String[] before = o.clone();
        for (int attempt = 0; attempt < 16; attempt++) {
            for (int i = n - 1; i > 0; i--) {
                int j = R.nextInt(i + 1);
                String t = o[i]; o[i] = o[j]; o[j] = t;
            }
            boolean same = true;
            for (int i = 0; i < n; i++) {
                if (o[i] != before[i]) { same = false; break; }
            }
            if (!same) break;
        }
        for (int i = 0; i < n; i++) {
            if (o[i] == correct) { q.answerIndex = i; return; }
        }
    }

    // ---------------------------------------------------------------- 按钮聚焦统一

    /**
     * 答题浮层「继续观看」按钮由历史 smali 创建，没有焦点高亮。
     * 这里补一个「常态蓝、聚焦绿」的监听器，让全 app 的按钮规则一致。
     */
    private static void decorate(OverlayQuizView view) {
        // v23：答题浮层永远不显示题库解析，也不显示「答对后这里会显示解析」占位文案。
        // 历史 smali 只在 explain 上 setText、不会改 visibility，所以设为 GONE 即长期生效；
        // 每次 bind 都重设一次，成本极低且能覆盖任何运行期状态变化，无需改 smali。
        try {
            Field e = OverlayQuizView.class.getDeclaredField("explain");
            e.setAccessible(true);
            TextView t = (TextView) e.get(view);
            if (t != null) t.setVisibility(View.GONE);
        } catch (Throwable ignored) { }

        if (sDecorated != null && sDecorated.get() == view) return;
        try {
            Field f = OverlayQuizView.class.getDeclaredField("cont");
            f.setAccessible(true);
            final Button b = (Button) f.get(view);
            if (b == null) return;
            final Context c = view.view().getContext();
            b.setBackground(V10Ui.round(BLUE, 10, c));
            b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View v, boolean has) {
                    b.setBackground(has
                            ? V10Ui.outlined(V10Ui.SELECT_DARK, 10, c, 2, V10Ui.SELECT)
                            : V10Ui.round(BLUE, 10, c));
                    b.setTextColor(0xFFFFFFFF);
                }
            });
            sDecorated = new WeakReference<OverlayQuizView>(view);
        } catch (Throwable ignored) { }
    }

    // ---------------------------------------------------------------- 小工具

    private static OverlayQuizView view() {
        return sViewRef == null ? null : sViewRef.get();
    }

    private static String safeKey(QuizEngine.Question q) {
        try {
            String k = q.key();
            return k == null ? String.valueOf(q.hashCode()) : k;
        } catch (Throwable t) {
            return String.valueOf(q.hashCode());
        }
    }

    /**
     * 题库 explain 偶尔会原样写出选中项的文字（例如「用 a」「应为『厉害』」「顺序是 ②①③④」）。
     * 这里把与选中项相同的片段遮成填空，保证两个方案的解析窗口只给思路、不报结果。
     * 纯字母数字选项（含 a / an / 4 这类短词）按「独立成词」匹配，避免把 book、
     * banana 里的字母也替换掉；中文与符号选项则直接整串替换。
     */
    public static String maskAnswer(String exp, QuizEngine.Question q) {
        if (exp == null) return "";
        if (exp.isEmpty() || q == null || q.options == null) return exp;
        int ai = q.answerIndex;
        if (ai < 0 || ai >= q.options.length || q.options[ai] == null) return exp;
        String chosen = q.options[ai].trim();
        if (chosen.isEmpty()) return exp;
        if (chosen.matches("[A-Za-z0-9]+")) {
            return exp.replaceAll("(?<![A-Za-z0-9_])" + Pattern.quote(chosen)
                    + "(?![A-Za-z0-9_])", "＿＿");
        }
        return exp.replace(chosen, "＿＿");
    }

    private static GradientDrawable round(int fill, int radiusDp, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (strokePx > 0) g.setStroke(dp(strokePx), stroke);
        return g;
    }

    private static int dp(int v) {
        // v51：与全局基准 density 对齐，避免悬浮卡圆角与 padding 用了不同比例。
        return (int) (v * ScreenFit.density() + 0.5f);
    }

    private static int dp(Context c, int v) {
        ScreenFit.apply(c);   // v51
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    private static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private static TextView tv(Context c, String s, int sp, int color, int gravity) {
        ScreenFit.apply(c);   // v51：sp 字号按基准 density
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(gravity);
        t.setLineSpacing(0, 1.2f);
        return t;
    }

    private static LinearLayout.LayoutParams matchWrap(Context c, int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(c, topDp);
        return p;
    }

    private static LinearLayout.LayoutParams wrap(Context c) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

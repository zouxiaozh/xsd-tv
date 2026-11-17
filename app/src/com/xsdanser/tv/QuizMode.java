package com.xsdanser.tv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.Toast;

/**
 * v21「答题节奏」双模式：严谨守护 / 自主可控（时间银行）。
 *
 * <p>设计约束（重要）：本工程的 classes.dex 由「base.dex（apktool 反编译后打了
 * v5~v14 历史补丁的 smali）」+「少量重编译的 Java 类」合并而成。
 * QuizOverlayService / AnswerPolicy / QuizFlow / OverlayQuizView 的 Java 源码
 * 落后于 smali，重编译会冲掉历史补丁，所以这里绝不改动那些类的实现，
 * 而是由 patch_mode_v20.py 在 smali 里插入 11 处 invoke-static，把决策点委托给本类。
 *
 * <p>MODE_GUARD 严谨守护：按频率弹全屏题、暂停视频、文字思路 + 控屏。
 * 这是默认值。
 *
 * <p>v35 两种方案共用同一套答错规则（shouldSwap 不再区分模式）：
 * 同一题第 1 次答错弹解析卡 / 思路控屏卡并重排选项，第 2 次答错不弹任何卡片，
 * 原题记入错题库并立即自动换题。判题分支在 QuizOverlayService 的 smali 里。
 *
 * <p>MODE_FREE 自主可控（v20 时间银行）：
 * <ul>
 *   <li>观看时间以落盘的「绝对到期时间」（deadline）为准：App 在前台、退到后台、
 *       乃至被强杀到守护重新拉起这段空白期，<b>真实流逝的时间一律照扣</b>；
 *       答题浮层显示期间才暂停计时（每秒把 deadline 往后锚）。详见 v59 / v68。</li>
 *   <li>余额归零：立即暂停视频并弹出全屏答题浮层——这是唯一入口，不再等出题间隔。</li>
 *   <li>答对一题 +「每题可得」时长，可连续答不限题数（本轮题数改成 9999），
 *       余额累加；第 1 次答错弹解析卡、不禁用选项、不控屏、不显示惩罚文案，
 *       第 2 次答错与严谨守护一样入库并立即换题（v35）。</li>
 *   <li>有余额时按返回键结束答题、恢复播放；余额为 0 时返回键只给一句提示，
 *       保证「没有时间就不能看」，但绝不训斥、不倒计时、不加罚。</li>
 *   <li>v21：右下角邀请卡已删除，低余额只用一条可自动消失的 Toast 提示。</li>
 * </ul>
 *
 * <p>v68：余额快照每 2 秒及每个关键事件用 <b>commit() 同步</b>落盘（不再用
 * apply() 异步、也不再每 10 秒才写一次），冷启动还会立刻写回一次，保证盘上
 * 永远有 quiz_balance_ms —— 这是修掉「被杀后余额回退成原始默认时间」的关键。
 * 每秒只在内存校准余额，避免老盒子每秒 fsync 卡顿。
 *
 * <p><b>v59：为什么不再「每秒内存扣减」</b>——小米盒子的定制 lmkd 会在内存吃紧时
 * 对 adj 9 进程做 forceclosePackage 整包强停，服务随之停摆、START_STICKY 也失效。
 * 旧实现里余额只在 tick 里递减、quiz_pending 也只在 tick 归零那一秒才置位，
 * 于是「被杀 → 用户继续看视频 → 家长重新点开 App」这段空白既没扣时也没记待办，
 * 孩子白看一段时间却不弹题（漏题）。现在把到期时间（墙钟 + elapsedRealtime 双写，
 * 取较早到期者，防改表 / 防重启归零）落盘，进程一启动就按「deadline - now」算出
 * 真实余额；若已过期，reloadBalance() 直接置 quiz_pending，由首页 onHomeResumed()
 * 补弹一次答题。休眠 / 被杀期间的时间同样计入，孩子不能靠关电视或等进程被杀刷时长。
 *
 * <p><b>v68 定稿规则</b>：App 打开 / 后台 / 被杀到拉起的空白期，余额一律按真实
 * 流逝时间照扣（恢复 v59 的 deadline − now 反推），孩子不能靠杀进程刷时长；
 * 但绝不再「回到原始默认时间」——那个 bug 的根因是落盘不可靠（apply 异步 +
 * 每 10 秒才写 + 冷启动不写回），而不是 deadline 语义本身。v68 把落盘修好后
 * 保留 deadline 语义，两者并不冲突。
 *
 * <p>v24：两种方案共用同一套「剩余时间」提醒——剩余 10 / 5 / 2 分钟各提示一次，
 * 最后 1 分钟每秒倒数。自主可控的剩余时间 = 落盘的观看余额；严谨守护的剩余时间
 * v70 起 = 落盘的「下一次出题绝对到期时间」（由家长设置的出题频率决定）。
 *
 * <p><b>v70：严谨守护「距下次出题」沿用自主可控的时间规则</b>——历史 smali 服务里
 * remainSec 只是内存静态字段，进程被强杀后 startGuard() 会重置成 interval，孩子
 * 杀一次进程就能白看一轮。现在用 {@code guard_next_quiz_wall_ms} +
 * {@code guard_next_quiz_elapsed_ms} 双时钟落盘：App 前台、退到后台、被杀到守护
 * 拉起的空白期一律按真实流逝时间倒数；扣到负数归零，由原有 showQuiz() 弹出答题。
 * 每秒 tick 通过反射把真实剩余秒数写回 smali 的私有字段 remainSec（+1 补偿随后的
 * 自减），既不重编译历史 smali、也不新增第二条弹题路径。答题浮层显示期间只保持
 * 已到期的 deadline（不重锚，杀进程重拉仍归零补弹）；只有答对 / 关闭答题 /
 * 切方案 / 改频率时重新锚定下一轮，堵住「杀进程逃题白得一轮」。
 *
 * <p>v25：提醒改由 {@link ReminderBanner} 常驻小窗承载。小米盒子对系统 Toast
 * 有连续弹出限制，v24 复用同一个 Toast 逐秒改文字会在几秒后丢字；现在每秒
 * 原地刷新同一个悬浮小窗，全程可见。答题流程与 13 处挂接零改动。
 *
 * <p><b>v68：空白期照扣，但不回退默认时间</b> —— 用户最初的投诉是「被杀后余额
 * 恢复到原始默认时间」，根因有两层：v59 用 deadline − now 反推余额（空白期扣时，
 * 这是<b>正确且需要保留</b>的规则），但落盘用 apply() 异步 + 每 10 秒才写一次，
 * 刚启动 / 刚切方案就被强杀时盘上连 quiz_balance_ms 都没有，reloadBalance()
 * 就回退成 startMinutes 发放默认时长。v68 保留 deadline 反推，只修落盘：
 * tick 每 2 秒 commit() 同步写、冷启动立即写回余额快照；有 deadline 时写回
 * 快照<b>绝不重锚 deadline</b>（否则刚扣掉的空白期会被补回来），只有首次运行 /
 * 刚切方案这种本来就没有 deadline 的场景才锚定并完整落盘。
 */
public final class QuizMode {

    public static final int MODE_GUARD = 0;
    public static final int MODE_FREE = 1;
    public static final String[] MODE_NAMES = {"严谨守护", "自主可控"};

    /** 答对一题可获得的观看分钟数。 */
    public static final int[] REWARD_MINUTES = {1, 2, 3, 5, 10, 15};
    /** 切到自主可控 / 点「重置」时的初始观看分钟数。 */
    public static final int[] START_MINUTES = {0, 3, 5, 10, 20};

    private static final String PREF = "tvbank";
    private static final String K_MODE = "quiz_mode";
    private static final String K_REWARD_MIN = "quiz_reward_min";
    private static final String K_START_MIN = "quiz_start_min";
    private static final String K_BALANCE = "quiz_balance_ms";
    /** v28：时间已用完、尚欠一次答题（落盘，进程重启不丢）。 */
    private static final String K_PENDING = "quiz_pending";
    /**
     * v59：观看余额的「绝对到期时间」，墙钟 + 单调时钟双写。
     * 旧实现每秒只在内存扣减余额，进程被小米 lmkd forceclosePackage 整包强停后
     * tick 断掉、余额冻结、quiz_pending 也来不及置位 —— 孩子白看一段时间却不弹题。
     * 改成落盘 deadline 后，进程重启时按真实流逝的时间直接算出该不该答题，不漏题。
     */
    private static final String K_DEADLINE_WALL = "quiz_deadline_wall_ms";
    private static final String K_DEADLINE_ELAPSED = "quiz_deadline_elapsed_ms";

    /**
     * v70：严谨守护「距下次出题」的绝对到期时间，与自主可控余额同样双时钟落盘。
     * 历史 smali 服务的 remainSec 只是内存静态字段，进程被杀后 startGuard() 会
     * 把它重置成 interval —— 孩子杀一次进程就能白看一轮。改成 deadline 后，
     * 空白期照扣、扣到 0 立即弹题，前台/后台都按真实流逝时间倒数。
     */
    private static final String K_GUARD_WALL = "guard_next_quiz_wall_ms";
    private static final String K_GUARD_ELAPSED = "guard_next_quiz_elapsed_ms";

    /** 提醒档位（秒）：10 分钟 / 5 分钟 / 2 分钟 / 最后 1 分钟逐秒倒数。 */
    private static final long REMIND_10M = 600L;
    private static final long REMIND_5M = 300L;
    private static final long REMIND_2M = 120L;
    private static final long REMIND_LAST = 60L;
    private static final long MAX_BALANCE_MS = 12L * 60L * 60L * 1000L;
    /** 同一份「待弹题」两次主动弹题的最小间隔，防止回前台 / tick 反复弹。 */
    private static final long QUIZ_COOLDOWN_MS = 9000L;
    /**
     * v62：自主可控按返回键主动退出答题后的静默窗口。期间清掉待弹题、不再显示
     * 「还有 X 秒就要答题啦」提示条、也不把答题界面自动弹回来，避免用户刚退出
     * 就又看到一张「该答题了」界面。窗口过后若余额真的归零，守护仍照常补题。
     */
    private static final long EXIT_SUPPRESS_MS = 12000L;
    private static volatile long sUserExitAt = 0L;
    private static final int FREE_ROUND_TOTAL = 9999;

    private static volatile int sMode = MODE_GUARD;
    private static volatile int sRewardMin = 1;   // v31 默认每题可得 1 分钟
    private static volatile int sStartMin = 5;
    private static volatile long sBalanceMs = 0L;
    /** v59：绝对到期时间（墙钟 / 开机单调时钟），只在 MODE_FREE 下作为权威余额来源。 */
    private static volatile long sDeadlineWall = 0L;
    private static volatile long sDeadlineElapsed = 0L;
    private static volatile boolean sDeadlineValid = false;
    /** v70：严谨守护下一轮出题的绝对到期时间（墙钟 / 单调时钟双写）。 */
    private static volatile long sGuardWall = 0L;
    private static volatile long sGuardElapsed = 0L;
    private static volatile boolean sGuardValid = false;
    private static volatile int sGuardPersistTick = 0;
    private static volatile long sLastTick = 0L;
    private static volatile boolean sLoaded = false;
    private static volatile boolean sTimeUpTriggered = false;
    private static volatile boolean sForceNext = false;
    private static volatile boolean sPendingQuiz = false;
    private static volatile long sLastQuizRequestAt = 0L;
    /** 家长设置页在前台时抑制自动弹题，避免全屏题盖住设置。 */
    private static volatile boolean sSettingsForeground = false;
    /** 提醒档位：0=时间充足，1/2/3=10/5/2 分钟，4=最后 1 分钟；-1=新一轮待对齐。 */
    private static volatile int sRemindStage = -1;
    private static volatile long sLastCountdownSec = -1L;
    /**
     * v68：余额快照同步落盘的 tick 间隔。旧值 10 秒 + apply() 异步，进程刚被
     * 强杀时盘上往往还是上一次（甚至没有）余额，冷启动就回退成原始默认时间。
     * 2 秒一次 commit()：最坏只丢 2 秒，且 SharedPreferences 只在值变化时写文件，
     * 老盒子也不会明显卡顿。deadline 仍是扣时的权威时钟，这里的快照只是
     * 「不让余额回退默认值」的兜底。
     */
    private static final int PERSIST_EVERY_TICKS = 2;
    private static volatile int sPersistTick = 0;
    private static volatile Context sApp;

    private static final Handler H = new Handler(Looper.getMainLooper());

    private QuizMode() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------- 家长设置读写 ----------

    public static int mode(Context c) {
        return prefs(c).getInt(K_MODE, MODE_GUARD) == MODE_FREE ? MODE_FREE : MODE_GUARD;
    }

    public static String modeLabel(Context c) { return MODE_NAMES[mode(c)]; }

    public static void setMode(Context c, int m) {
        int v = m == MODE_FREE ? MODE_FREE : MODE_GUARD;
        // 切方案等于重新发放起始时长，旧的到期时间与余额快照必须一并作废；
        // reloadBalance() 随后会按 startMinutes 发放并立刻 commit() 写回，
        // 这样「刚切方案就被强杀」也不会冷启动回退成「上次方案」的余额。
        prefs(c).edit().putInt(K_MODE, v).remove(K_BALANCE)
                .remove(K_PENDING).remove(K_DEADLINE_WALL)
                .remove(K_DEADLINE_ELAPSED)
                .remove(K_GUARD_WALL).remove(K_GUARD_ELAPSED).commit();
        sMode = v;
        sLoaded = false;
        sGuardValid = false;   // v70：切方案作废旧出题到期时间，sync() 会重新发一轮
        sForceNext = false;
        sTimeUpTriggered = false;
        sPendingQuiz = false;
        sDeadlineValid = false;
        sLastQuizRequestAt = 0L;
        sLastTick = 0L;
        resetReminderState();
        sync(c);                 // 按「起始时长」重新发放余额
    }

    public static void cycleMode(Context c) {
        setMode(c, mode(c) == MODE_GUARD ? MODE_FREE : MODE_GUARD);
    }

    private static int pick(int[] steps, int cur, int fallback) {
        for (int i = 0; i < steps.length; i++) if (steps[i] == cur) return cur;
        return fallback;
    }

    /** v31 默认「每题可得」= 1 分钟（原来是 3 分钟）。 */
    public static int rewardMinutes(Context c) {
        return pick(REWARD_MINUTES, prefs(c).getInt(K_REWARD_MIN, 1), 1);
    }

    public static String rewardLabel(Context c) { return rewardMinutes(c) + " 分钟 / 题"; }

    public static void cycleRewardMinutes(Context c) {
        int cur = rewardMinutes(c), next = REWARD_MINUTES[0];
        for (int i = 0; i < REWARD_MINUTES.length; i++) {
            if (REWARD_MINUTES[i] > cur) { next = REWARD_MINUTES[i]; break; }
        }
        sRewardMin = next;
        prefs(c).edit().putInt(K_REWARD_MIN, next).commit();
    }

    public static int startMinutes(Context c) {
        return pick(START_MINUTES, prefs(c).getInt(K_START_MIN, 5), 5);
    }

    public static String startLabel(Context c) { return startMinutes(c) + " 分钟"; }

    public static void cycleStartMinutes(Context c) {
        int cur = startMinutes(c), next = START_MINUTES[0];
        for (int i = 0; i < START_MINUTES.length; i++) {
            if (START_MINUTES[i] > cur) { next = START_MINUTES[i]; break; }
        }
        sStartMin = next;
        prefs(c).edit().putInt(K_START_MIN, next).commit();
    }

    /** 家长设置「剩余观看时间」行：显示当前余额，点击重置为起始时长。 */
    public static String balanceLabel(Context c) {
        if (mode(c) != MODE_FREE) return "守护模式不计时";
        sync(c);
        long sec = Math.max(0L, sBalanceMs) / 1000L;
        long mm = sec / 60L, ss = sec % 60L;
        return mm + " 分 " + (ss < 10 ? "0" : "") + ss + " 秒 · 点此重置";
    }

    /** 首页指标用：返回当前可用观看毫秒数（守护模式返回 -1）。 */
    public static long balanceMs(Context c) {
        if (mode(c) != MODE_FREE) return -1L;
        sync(c);
        return Math.max(0L, sBalanceMs);
    }

    public static void resetBalance(Context c) {
        sync(c);
        // v59：统一走 setBalance()，让落盘的 deadline 与内存余额同步重锚。
        setBalance(c, startMinutes(c) * 60000L);
        // v28：余额归零 = 记下一份「待弹题」待办并落盘。家长此刻仍在设置页，
        // 由退出设置 / 回首页 / 每秒兜底择机消费，不在这里直接盖全屏题。
        setPending(c, sBalanceMs <= 0L);
        sTimeUpTriggered = false;
        sLastTick = 0L;
        resetReminderState();
        persist(c);
    }

    // ---------- v59：绝对到期时间（deadline）持久化 ----------

    private static long nowWall() { return System.currentTimeMillis(); }

    private static long nowElapsed() { return android.os.SystemClock.elapsedRealtime(); }

    /**
     * 按双时钟算出「真实剩余观看时间」，取两者中较早到期的那个。
     * 只用墙钟会被家长改表刷时长；只用 elapsedRealtime 会在盒子重启后归零，
     * 把过期的余额误判成还很充裕。取 min 后两种异常都不会多送观看时间：
     * 重启 -> byElapsed 虚大，选 byWall；往前调表 -> byWall 虚大，选 byElapsed。
     */
    private static long remainingFromDeadline() {
        long byWall = sDeadlineWall - nowWall();
        long byElapsed = sDeadlineElapsed - nowElapsed();
        return Math.min(byWall, byElapsed);
    }

    /** 以「此刻 + 当前余额」重新锚定到期时间（只改内存，不落盘）。 */
    private static void anchorDeadline() {
        sDeadlineWall = nowWall() + sBalanceMs;
        sDeadlineElapsed = nowElapsed() + sBalanceMs;
        sDeadlineValid = true;
    }

    /** 所有改变余额的入口都必须走这里，保证 deadline 与余额一同更新。 */
    private static void setBalance(Context c, long ms) {
        sBalanceMs = Math.max(0L, Math.min(MAX_BALANCE_MS, ms));
        anchorDeadline();
        persist(c);
    }

    /**
     * v68 冷启动读盘：按「绝对到期时间」还原真实剩余余额，空白期照扣。
     *
     * <p>用户定稿规则：App 打开 / 退到后台 / 被强杀到守护拉起这段空白，
     * 真实流逝的时间一律照扣，孩子不能靠杀进程刷时长。deadline 是墙钟 +
     * elapsedRealtime 双写的绝对到期时间，进程在不在都与它无关，所以
     * {@code deadline − now} 天然把空白期算进去。
     *
     * <p>同时修掉「回退原始默认时间」：旧版落盘用 apply() 异步 + 每 10 秒才写，
     * 刚启动 / 刚切方案就被强杀时盘上连 quiz_balance_ms 都没有，只能按
     * startMinutes 重发默认时长。v68 在冷启动这里<b>必定同步写回一次余额快照</b>，
     * 保证键永远存在。
     * <ol>
     *   <li>有 deadline：读回 deadline，用 {@link #remainingFromDeadline()} 只往小
     *       校准余额（补扣空白期），再调 {@link #writeBalanceSnapshot} 写回快照；
     *       <b>绝不重锚 deadline</b>，否则刚扣掉的空白期会被「现在 + 余额」加回来。</li>
     *   <li>无 deadline（首次运行 / 刚切方案）：此刻才发放起始时长，本来没有空白期
     *       可扣，锚定 deadline 并把余额 + deadline 完整落盘。</li>
     *   <li>余额已归零（含空白期扣到 0）补记 quiz_pending，由 onHomeResumed() /
     *       每秒兜底补弹，堵住漏题。</li>
     * </ol>
     */
    public static void reloadBalance(Context c) {
        SharedPreferences p = prefs(c);
        final int modeNow = p.getInt(K_MODE, MODE_GUARD) == MODE_FREE
                ? MODE_FREE : MODE_GUARD;
        sBalanceMs = p.contains(K_BALANCE)
                ? p.getLong(K_BALANCE, 0L) : startMinutes(c) * 60000L;
        sBalanceMs = Math.max(0L, Math.min(MAX_BALANCE_MS, sBalanceMs));
        sLoaded = true;
        sLastTick = 0L;
        sPersistTick = 0;
        sTimeUpTriggered = false;

        final boolean hadDeadline = p.contains(K_DEADLINE_WALL);
        if (hadDeadline) {
            sDeadlineWall = p.getLong(K_DEADLINE_WALL, 0L);
            sDeadlineElapsed = p.getLong(K_DEADLINE_ELAPSED, 0L);
            sDeadlineValid = true;
            long real = remainingFromDeadline();
            if (real < 0L) real = 0L;
            if (real > MAX_BALANCE_MS) real = MAX_BALANCE_MS;
            // 只往小校准：正常运行中 deadline 与余额同步递减，两者应当一致；
            // 只有 tick 断过（被杀 / 后台冻结 / 休眠）时 real 才明显更小，
            // 这段真实流逝的时间在这里一次性补扣。
            sBalanceMs = Math.min(Math.max(0L, sBalanceMs), real);
            // v68 关键：只写回余额快照，保住盘上的原 deadline。
            writeBalanceSnapshot(c);
        } else {
            // 首次运行 / 刚切方案：从「现在」开始计时，完整落盘余额 + deadline。
            persist(c);
        }

        // v28：不再从余额反推「时间到已处理过」——那正是余额 0 却不弹题的根因。
        // 旧版偏好没有 quiz_pending 键时兼容升级：自主可控 + 余额 0 视为还欠一次答题。
        sPendingQuiz = p.contains(K_PENDING)
                ? p.getBoolean(K_PENDING, false)
                : (modeNow == MODE_FREE && sBalanceMs <= 0L);
        // 余额已归零时（含被杀空白期扣到 0）直接记下「欠一次答题」，
        // 由 onHomeResumed() / 每秒兜底补弹，堵住漏题。
        if (modeNow == MODE_FREE && sBalanceMs <= 0L) {
            setPending(c, true);
        }
    }

    /** 每次 sync（每秒 tick、首页渲染、消费待办等）都用真实流逝时间校准一次。 */
    private static void reconcileDeadline(Context c) {
        if (sMode != MODE_FREE || !sDeadlineValid) return;
        // 答题浮层显示期间暂停计时。这里必须「重锚后直接返回」：sync() 每秒都在
        // tick 之前运行，若照常按上一秒的 deadline 校准，余额还是会每秒被减 1 秒
        // （盒子实测：答题 22 秒后 2:00 -> 1:31），暂停计时形同虚设。
        if (QuizOverlayService.isQuizShowing()) {
            anchorDeadline();
            return;
        }
        long real = remainingFromDeadline();
        if (real < 0L) real = 0L;
        if (real > MAX_BALANCE_MS) real = MAX_BALANCE_MS;
        if (real < sBalanceMs) sBalanceMs = real;
    }

    /** 「待弹题」是唯一权威状态：时间用完置位，答对加时或家长补足余额时清位。 */
    private static void setPending(Context c, boolean v) {
        sPendingQuiz = v;
        try { prefs(c).edit().putBoolean(K_PENDING, v).commit(); }
        catch (Throwable ignored) { }
    }

    /** 无 Context 的纯 Java 策略类（AnswerPolicy / QuizFlow / VoiceCoach）查询用。 */
    public static boolean isFreeCached() { return sMode == MODE_FREE; }

    /** 刷新静态缓存；余额只在首次加载时读盘，之后由本类内存维护并定期落盘。 */
    public static void sync(Context c) {
        migrateDefaults(c);
        SharedPreferences p = prefs(c);
        sApp = c.getApplicationContext();
        sMode = p.getInt(K_MODE, MODE_GUARD) == MODE_FREE ? MODE_FREE : MODE_GUARD;
        sRewardMin = pick(REWARD_MINUTES, p.getInt(K_REWARD_MIN, 1), 1);
        sStartMin = pick(START_MINUTES, p.getInt(K_START_MIN, 5), 5);
        if (!sLoaded) reloadBalance(c);
        // v70：严谨守护的「距下次出题」也用落盘的绝对到期时间；只在进程首次
        // sync 时读盘一次，之后由内存维护，避免每秒 tick 反复读 SharedPreferences。
        if (sMode == MODE_GUARD && !sGuardValid) guardReload(c);
        // v68：余额以绝对到期时间为准，tick 断过（后台冻结 / 休眠）也照常扣真实
        // 流逝时间；整包强杀后的空白期已由 reloadBalance() 一次性补扣。
        reconcileDeadline(c);
    }

    /** v31 是否已做过默认参数迁移（进程内只查一次，避免每秒 tick 反复读盘）。 */
    private static volatile boolean sDefaultsChecked = false;

    /**
     * v31：把三项默认参数一次性迁移到新口径——出题频率 10 分钟、每轮 1 题、
     * 每题可得 1 分钟。只覆盖「从未设置过」或「仍是旧默认值」的键，家长自己
     * 调过的数值原样保留。迁移标记落盘，覆盖安装 / 重启都不会重复执行。
     */
    private static void migrateDefaults(Context c) {
        if (sDefaultsChecked) return;
        sDefaultsChecked = true;
        try {
            SharedPreferences p = prefs(c);
            if (p.getBoolean("xsd_defaults_v31", false)) return;
            SharedPreferences.Editor e = p.edit();
            if (!p.contains("interval") || p.getInt("interval", 30) == 30) {
                e.putInt("interval", 600);          // 10 分钟
            }
            if (!p.contains("quizPerRound") || p.getInt("quizPerRound", 10) == 10) {
                e.putInt("quizPerRound", 1);        // 1 题 / 轮
            }
            if (!p.contains(K_REWARD_MIN) || p.getInt(K_REWARD_MIN, 3) == 3) {
                e.putInt(K_REWARD_MIN, 1);          // 1 分钟 / 题
            }
            e.putBoolean("xsd_defaults_v31", true).commit();
        } catch (Throwable ignored) { }
    }

    /**
     * v68：只同步写回余额快照，<b>绝不动 deadline</b>。冷启动按 deadline 补扣完
     * 空白期后调用它，保证盘上永远有 quiz_balance_ms（不回退默认时间），又不会
     * 把刚扣掉的空白期通过重锚 deadline 还回去。
     */
    private static void writeBalanceSnapshot(Context c) {
        try { prefs(c).edit().putLong(K_BALANCE, Math.max(0L, sBalanceMs)).commit(); }
        catch (Throwable ignored) { }
    }

    private static void persist(Context c) {
        // v68：每 2 秒及每个关键事件，把「余额快照 + 到期时间」同步落盘。
        // 必须用 commit()：apply() 只是把写盘排进 QueuedWork，lmkd / force-stop
        // 整包强杀时那次写盘可能永远没执行，冷启动就会读到上一份甚至空余额。
        // 注意：本方法会重锚 deadline，只允许在「余额已按真实时间校准」之后调用；
        // 冷启动补扣空白期时请改用 writeBalanceSnapshot()。
        anchorDeadline();
        try {
            prefs(c).edit()
                    .putLong(K_BALANCE, Math.max(0L, sBalanceMs))
                    .putLong(K_DEADLINE_WALL, sDeadlineWall)
                    .putLong(K_DEADLINE_ELAPSED, sDeadlineElapsed)
                    .commit();
        } catch (Throwable ignored) { }
    }

    // ---------- v70：严谨守护「距下次出题」的绝对到期时间 ----------

    /** 一轮出题倒计时的毫秒数（家长设置的出题频率，默认 10 分钟）。 */
    private static long guardIntervalMs(Context c) {
        int sec = prefs(c).getInt("interval", 600);
        if (sec < 10) sec = 10;          // 防脏数据把倒计时压成 0 反复弹题
        return sec * 1000L;
    }

    /** 以「此刻 + 一轮」重新锚定并同步落盘（弹题结束 / 切方案 / 改频率后调用）。 */
    private static void guardAnchor(Context c) {
        long ms = guardIntervalMs(c);
        sGuardWall = nowWall() + ms;
        sGuardElapsed = nowElapsed() + ms;
        sGuardValid = true;
        guardPersist(c);
    }

    private static void guardPersist(Context c) {
        try {
            prefs(c).edit().putLong(K_GUARD_WALL, sGuardWall)
                    .putLong(K_GUARD_ELAPSED, sGuardElapsed).commit();
        } catch (Throwable ignored) { }
    }

    /**
     * 冷启动读盘：<b>有 deadline 就原样恢复</b>，被强杀 → 守护拉起这段空白期
     * 由 {@code deadline − now} 天然扣掉；只有首次运行 / 刚切方案才锚定新一轮。
     * 这里绝不重锚，否则刚扣掉的空白期会被「现在 + 一轮」还回去。
     */
    private static void guardReload(Context c) {
        SharedPreferences p = prefs(c);
        if (p.contains(K_GUARD_WALL)) {
            sGuardWall = p.getLong(K_GUARD_WALL, 0L);
            sGuardElapsed = p.getLong(K_GUARD_ELAPSED, 0L);
            sGuardValid = true;
        } else {
            guardAnchor(c);
        }
    }

    /** 真实剩余秒数：双时钟取较早到期者（防改表 / 防重启后 elapsed 归零），负数归 0。 */
    private static long guardRemainingSec() {
        long ms = Math.min(sGuardWall - nowWall(), sGuardElapsed - nowElapsed());
        if (ms < 0L) ms = 0L;
        return (ms + 999L) / 1000L;
    }

    /** 首页 / 验证探针用：严谨守护距下次出题的秒数（自主可控返回 -1）。 */
    public static long guardRemainSec(Context c) {
        sync(c);
        if (sMode != MODE_GUARD) return -1L;
        return guardRemainingSec();
    }

    /**
     * 答题浮层显示期间<b>保持已到期的 deadline 不变</b>：不扣时、更不重锚。
     * 这一点与自主可控「答题时把到期时间往后锚」不同，是有意为之——若在这里
     * 把倒计时延长成完整一轮，孩子只要「倒计时归零 → 弹题 → 强杀进程」，
     * 守护重新拉起就会读到一轮新的 600 秒，等于靠杀进程白得一轮。
     * 现在盘上始终是那个已过期的 deadline：被杀空白期照扣、拉起仍是 0 秒并
     * 由原 showQuiz() 立刻补弹。只有真正答对 / 结束答题才由 onCorrect /
     * onQuizFinished 调用 {@link #guardAnchor} 开启下一轮。
     * 每 2 秒把当前 deadline 原样同步落盘一次，答题中被杀也不会读到旧值。
     */
    private static void guardHold(Context c) {
        if (++sGuardPersistTick >= PERSIST_EVERY_TICKS) {
            sGuardPersistTick = 0;
            guardPersist(c);
        }
    }

    /**
     * 把按 deadline 算出的剩余秒数写回历史 smali 服务的私有静态字段 remainSec。
     * smali 每秒 tick 的顺序是「QuizMode.tickSecond() → remainSec-- → <=0 弹题」，
     * 所以这里 <b>要 +1 补偿</b>随后的自减；剩余 0 秒时写 1，自减后正好 0，
     * 由原有 showQuiz() 弹出答题，不需要第二条弹题路径，避免重复弹。
     * 反射是唯一不改历史 smali 就能同步的办法（该类由 base dex 提供，不能重编译）。
     */
    private static void syncServiceRemainSec(long sec) {
        int v = sec > 0L ? (int) Math.min(sec + 1L, 2147483647L) : 1;
        try {
            java.lang.reflect.Field f = QuizOverlayService.class.getDeclaredField("remainSec");
            f.setAccessible(true);
            f.setInt(null, v);
        } catch (Throwable ignored) { }
    }

    private static String balanceShort() {
        long m = (Math.max(0L, sBalanceMs) + 59999L) / 60000L;
        return m + " 分钟";
    }

    // ---------- smali 挂接 1：showQuiz() 开头 ----------

    /**
     * @return true = 拦截原来的全屏强制弹题（余额充足）；false = 放行全屏答题。
     */
    public static boolean interceptShow(Context c) {
        sync(c);
        if (QuizOverlayService.isQuizShowing()) return false;
        if (sMode != MODE_FREE) {
            sForceNext = false;
            resetReminderState();   // 弹题瞬间收掉倒数，避免提示压在答题遮罩上
            return false;
        }
        if (sForceNext) {
            sForceNext = false;
            resetReminderState();
            forcePauseIfNeeded(c);
            return false;
        }
        if (sBalanceMs <= 0L) {
            // 兜底：tick 没跑到（服务刚重启等），到点也立刻进入答题并停播。
            resetReminderState();
            forcePauseIfNeeded(c);
            return false;
        }
        return true;   // 余额充足，不弹题
    }

    // ---------- smali 挂接 2：每秒 tick ----------

    /**
     * 服务每秒调用一次：
     * <ul>
     *   <li>严谨守护：只按「距下一题剩余秒数」做提醒，原有按频率弹题逻辑完全不动。</li>
     *   <li>自主可控：扣减观看余额；归零时立即暂停视频并进入答题。</li>
     * </ul>
     */
    public static void tickSecond(Context c) {
        // v68：sync() 内部按真实流逝时间校准余额（前台 / 后台都照扣）；进程被强停
        // 的空白期由 reloadBalance() 按 deadline − now 一次性补扣。
        sync(c);
        final boolean showing = QuizOverlayService.isQuizShowing();

        if (sMode != MODE_FREE) {
            if (showing) {
                // v70：答题期间保持已到期 deadline 不变，杀进程重拉仍是 0 秒补弹；
                // 不写回 remainSec（smali 在 quizShowing 时也不自减）。
                guardHold(c);
                resetReminderState();
            } else {
                // v70：以落盘的绝对到期时间为准，前台 / 后台 / 被杀空白期一律照扣；
                // 每秒把真实剩余秒数校正回历史 smali 的 remainSec，减到 0 由原
                // showQuiz() 弹出答题。扣到负数时 guardRemainingSec() 已归零。
                long sec = guardRemainingSec();
                syncServiceRemainSec(sec);
                remind(sec);
            }
            return;
        }

        if (showing) {
            // v59：答题浮层显示期间不扣观看时间——每秒把到期时间往后锚一次，
            // 等价于暂停计时；即使此刻进程被杀，盘上 deadline 也不会被判成早已过期。
            anchorDeadline();
            // v68：答题浮层显示期间也要持续写余额快照，否则「答题中被杀」
            // 会丢掉这期间家长刚补 / 刚赚到的时间。
            if (++sPersistTick >= PERSIST_EVERY_TICKS) { sPersistTick = 0; persist(c); }
            resetReminderState();
            return;
        }

        // v68：每 2 秒把当前余额同步写盘，保证盘上永远有 quiz_balance_ms，
        // 强杀后冷启动不会回退成 startMinutes 原始默认时间。
        if (++sPersistTick >= PERSIST_EVERY_TICKS) { sPersistTick = 0; persist(c); }
        remind((sBalanceMs + 999L) / 1000L);

        if (sBalanceMs <= 0L) {
            if (!sPendingQuiz) {
                setPending(c, true);
                resetReminderState();
                persist(c);
                toast("观看时间用完啦 · 答一题就能继续看");
                sLastQuizRequestAt = 0L;   // 时间刚归零，允许立刻弹一次
            }
            // v28：每秒兜底也走同一消费入口。冷却窗口 + 浮层状态防重复弹；
            // 进程被杀后重启、服务已在跑、休眠唤醒都能靠这条链路补上。
            consumePending(c, false);
        } else {
            sTimeUpTriggered = false;
            if (sPendingQuiz) setPending(c, false);
        }
    }

    // ---------- v24 剩余时间提醒：两种方案共用 ----------

    /** 剩余 10 / 5 / 2 分钟各提示一次；最后 1 分钟每秒倒数。 */
    private static void remind(long sec) {
        // v62：用户刚按返回退出答题，静默窗口内不再冒出「还有 X 秒就要答题啦」提示条
        if (System.currentTimeMillis() - sUserExitAt < EXIT_SUPPRESS_MS) {
            resetReminderState();
            return;
        }
        if (sec <= 0L) { resetReminderState(); return; }
        int stage = stageOf(sec);
        if (stage == 0) {                     // 时间充足 / 刚加时：复位整套提醒
            resetReminderState();
            sRemindStage = 0;
            return;
        }
        if (sRemindStage < 0) {               // 新一轮：服务刚起 / 刚答完题 / 刚切方案
            sRemindStage = stage;
            sLastCountdownSec = -1L;
            showReminder(stage == 4 ? countdownText(sec) : milestoneText(sec));
            return;
        }
        if (stage < sRemindStage) return;     // 加时让余额回升：不倒回旧档重复播报
        if (stage > sRemindStage) {           // 跨过 10 / 5 / 2 分钟，或进入最后 1 分钟
            sRemindStage = stage;
            sLastCountdownSec = -1L;
            showReminder(stage == 4 ? countdownText(sec) : milestoneText(sec));
            return;
        }
        if (stage == 4 && sec != sLastCountdownSec) {
            sLastCountdownSec = sec;
            showReminder(countdownText(sec));
        }
    }

    private static int stageOf(long sec) {
        if (sec > REMIND_10M) return 0;
        if (sec > REMIND_5M) return 1;
        if (sec > REMIND_2M) return 2;
        if (sec > REMIND_LAST) return 3;
        return 4;
    }

    private static String milestoneText(long sec) {
        long min = (sec + 59L) / 60L;
        return sMode == MODE_FREE
                ? "还能看 " + min + " 分钟 · 想接着看可以先答一题 +" + sRewardMin + " 分钟"
                : "还有 " + min + " 分钟就要答题啦 · 先准备一下";
    }

    private static String countdownText(long sec) {
        return sMode == MODE_FREE
                ? "还能看 " + sec + " 秒 · 答一题 +" + sRewardMin + " 分钟"
                : "还有 " + sec + " 秒就要答题啦";
    }

    /** 里程碑与逐秒倒数共用一条常驻悬浮提示：原地改文字，不受 Toast 限流影响。 */
    private static void showReminder(final String text) {
        final Context app = sApp;
        if (app == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            H.post(new Runnable() { @Override public void run() { showReminder(text); } });
            return;
        }
        try {
            ReminderBanner.show(app, text);
        } catch (Throwable ignored) { }
    }

    private static void resetReminderState() {
        sRemindStage = -1;
        sLastCountdownSec = -1L;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            H.post(new Runnable() { @Override public void run() { cancelReminder(); } });
            return;
        }
        cancelReminder();
    }

    private static void cancelReminder() {
        try { ReminderBanner.hide(); } catch (Throwable ignored) { }
    }

    // ---------- smali 挂接 3：handleCorrect() 开头 ----------

    /** 自主可控：答对一题累加一段观看时间，之后可继续答不限题数。 */
    public static void onCorrect(Context c) {
        sync(c);
        // v70：严谨守护答对即开启新一轮倒计时（对齐 smali 的 finishQuiz 重置）。
        if (sMode != MODE_FREE) { guardAnchor(c); return; }
        // v59：加时必须同步重锚 deadline，否则下次重启会按旧到期时间把刚赚的时间抹掉。
        setBalance(c, sBalanceMs + rewardMinutes(c) * 60000L);
        sTimeUpTriggered = false;
        // v28：答对加时即解锁，「待弹题」待办完成，落盘避免下次打开 App 又弹。
        setPending(c, false);
        resetReminderState();
        sForceNext = false;
    }

    // ---------- smali 挂接 4：finishQuiz() 开头 ----------

    /** 家长关掉「暂停视频」时，原有控制器不会恢复播放，这里补一发 MEDIA_PLAY。 */
    public static void onQuizFinished(Context c) {
        sync(c);
        // v70：严谨守护答题结束（含家长关闭）开启新一轮倒计时。
        if (sMode != MODE_FREE) { guardAnchor(c); return; }
        if (sBalanceMs <= 0L) return;
        if (prefs(c).getBoolean("pauseOnQuiz", true)) return;
        final Context app = c.getApplicationContext();
        H.postDelayed(new Runnable() {
            @Override public void run() { dispatchMediaKey(app, KeyEvent.KEYCODE_MEDIA_PLAY); }
        }, 400L);
    }

    // ---------- smali 挂接 5~7：判错策略 / 无限连答 / 文案 ----------

    /**
     * v35：两种模式统一——同一题累计答错 2 次立即换题（原题由
     * QuizOverlayService 记入错题库）。不再区分严谨守护 / 自主可控，
     * 避免孩子在同一题上无限重试。第 1 次答错仍由 VoiceCoach /
     * AnswerShuffle 弹卡并重排选项。
     */
    public static boolean shouldSwap(int wrongAttempts) {
        return wrongAttempts >= 2;
    }

    /** 自主可控模式不禁用选项（无损重试）。 */
    public static boolean noPenalty() { return sMode == MODE_FREE; }

    /**
     * 第 2 次答错已自动换题，两种模式都提示「已入库 + 已换题」；
     * 第 1 次答错沿用原有模式文案，自主可控返回 null 时走 AnswerPolicy 默认文案。
     */
    public static String wrongHint(int wrongAttempts) {
        if (wrongAttempts >= 2) {
            return "✗ 已答错 2 次 · 原题已记入错题库 · 已自动换一题";
        }
        return sMode == MODE_FREE ? "再看看 · 选项会重新排一次，再想一遍" : null;
    }

    /** 自主可控模式把本轮题数改成 9999：答对后自动出下一题，不限数量。 */
    public static int roundTotal(int base) {
        return sMode == MODE_FREE ? FREE_ROUND_TOTAL : base;
    }

    /** 答对后的提示：写清赚了多少、攒了多少、怎么去看电视；顺手清掉本题答错计数。 */
    public static String correctHint(String original) {
        AnswerShuffle.onCorrect();
        if (sMode != MODE_FREE) return original;
        return "✓ 答对啦！+" + sRewardMin + " 分钟 · 已攒 " + balanceShort()
                + " · 继续答题或按返回键去看电视";
    }

    /** 浮层底部操作提示也随模式切换。 */
    public static String footerHint() {
        return sMode == MODE_FREE
                ? "答对 +" + sRewardMin + " 分钟 · 方向键/确定/数字键 1-4 作答 · "
                  + "按返回键去看电视 · 答错两次自动换题，不扣时间"
                : "方向键选择 · 确定/数字键 1-4 作答 · 第 1 次答错看解析，第 2 次入库换题 · "
                  + "返回键无效 · 答对才能继续";
    }

    /**
     * v22 首页「立即答题」：自主可控模式下允许孩子主动进入答题攒时间，
     * 不受「余额充足就拦截弹题」影响。复用 sForceNext 放行 showQuiz()。
     */
    public static void requestManualQuiz(Context c) {
        sync(c);
        Context app = c.getApplicationContext();
        sForceNext = true;
        sTimeUpTriggered = false;
        // 手动进答题也算消费了一次，避免 320ms 后的回前台兜底再补一发。
        sLastQuizRequestAt = System.currentTimeMillis();
        try {
            app.startService(new Intent(app, QuizOverlayService.class)
                    .setAction(QuizOverlayService.ACTION_QUIZ_NOW));
        } catch (Throwable t) {
            sForceNext = false;
        }
    }

    // ---------- smali 挂接 8：OverlayQuizView.onKey() 开头 ----------

    /**
     * @return 0 = 不接管，走原有 KeyRouter；1 = 吞掉返回键（余额为 0）；
     *         2 = 调用 listener.onContinue() 结束答题并恢复播放。
     */
    public static int freeExitKey(KeyEvent e) {
        if (sMode != MODE_FREE) return 0;
        if (e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return 0;
        if (e.getKeyCode() != KeyEvent.KEYCODE_BACK) return 0;
        if (sBalanceMs <= 0L) {
            toast("答对一题就能去看电视啦");
            return 1;
        }
        onUserExitQuiz();
        return 2;
    }

    /**
     * v62：自主可控按返回键主动退出答题。清掉待弹题、收起剩余时间提示条，并在
     * 静默窗口内抑制 onHomeResumed / 每秒兜底把「该答题啦」界面重新弹回来。
     * 只影响「用户主动退出」这一条路径，时间真正用完时守护补题逻辑不变。
     */
    private static void onUserExitQuiz() {
        Context c = sApp;
        if (c != null) setPending(c, false);
        long now = System.currentTimeMillis();
        sUserExitAt = now;
        sLastQuizRequestAt = now;
        sForceNext = false;
        sTimeUpTriggered = false;
        resetReminderState();
    }

    // ---------- v28：待弹题统一消费（打开 App / 回首页 / 退出设置 / 每秒兜底） ----------

    /**
     * v27 只补了「退出家长设置」一条入口。打开 App 显示 0:00 却不弹题的根因是
     * sTimeUpTriggered 同时承担「已处理别重复弹」与「由余额反推」两种语义：
     * 冷启动读盘时余额 0 直接被判成已处理，首页 onResume 又从不请求弹题。
     *
     * <p>v28 把「待弹题」独立成落盘状态 quiz_pending：时间归零 / 家长重置为 0
     * 时置位，答对加时清位。所有入口统一走 consumePending()，由「浮层是否已显示 +
     * 冷却窗口 + 家长设置前台抑制」三重条件防重复、防打扰。
     */
    public static void setSettingsForeground(boolean v) { sSettingsForeground = v; }

    /** 首页 onResume：稍等界面稳定后消费待办，覆盖「打开 App / 从别处回首页」。 */
    public static void onHomeResumed(final Context c) {
        final Context app = c.getApplicationContext();
        H.postDelayed(new Runnable() {
            @Override public void run() { consumePending(app, false); }
        }, 320L);
    }

    /** 家长保存退出：忽略冷却立即补一次，保留 v27 已有的即时体验。 */
    public static void onSettingsSaved(final Context c) {
        final Context app = c.getApplicationContext();
        H.postDelayed(new Runnable() {
            @Override public void run() {
                // v70：家长改了出题频率，新一轮按新值从保存时刻开始计时。
                if (mode(app) == MODE_GUARD) guardAnchor(app);
                consumePending(app, true);
            }
        }, 320L);
    }

    private static void consumePending(Context c, boolean force) {
        Context app = c.getApplicationContext();
        sync(app);
        // v62：主动退出后的静默窗口内不补弹（家长在设置里强制保存仍可绕过）
        if (!force && System.currentTimeMillis() - sUserExitAt < EXIT_SUPPRESS_MS) return;
        if (sMode != MODE_FREE || sBalanceMs > 0L || !sPendingQuiz) return;
        if (sSettingsForeground && !force) return;
        if (QuizOverlayService.isQuizShowing()) return;
        long now = System.currentTimeMillis();
        if (!force && now - sLastQuizRequestAt < QUIZ_COOLDOWN_MS) return;
        sLastQuizRequestAt = now;
        resetReminderState();
        forcePauseIfNeeded(app);
        sTimeUpTriggered = true;
        sForceNext = true;
        try {
            app.startService(new Intent(app, QuizOverlayService.class)
                    .setAction(QuizOverlayService.ACTION_QUIZ_NOW));
        } catch (Throwable t) {
            sForceNext = false;
            sLastQuizRequestAt = 0L;
        }
    }

    // ---------- 时间到 ----------
    // v28：弹题统一由 consumePending() 发起（含冷却窗口与浮层去重）。
    // 原先由每秒 tick 直接调用的 forceQuiz 已移除，不再有第二条路径。

    /** 家长关掉「暂停视频」时也要做到「时间用完立即停止」。 */
    private static void forcePauseIfNeeded(Context c) {
        if (prefs(c).getBoolean("pauseOnQuiz", true)) return;
        dispatchMediaKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    private static void dispatchMediaKey(Context c, int code) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            // 老工程的 android-stub.jar 没导出这个方法（真机 API 19+ 有），
            // 与 base dex 里的 MediaPauseController.dispatch() 一样走反射调用。
            java.lang.reflect.Method m = AudioManager.class.getMethod(
                    "dispatchMediaKeyEvent", KeyEvent.class);
            m.invoke(am, new KeyEvent(KeyEvent.ACTION_DOWN, code));
            m.invoke(am, new KeyEvent(KeyEvent.ACTION_UP, code));
        } catch (Throwable ignored) { }
    }

    private static void toast(final String s) {
        final Context app = sApp;
        if (app == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            H.post(new Runnable() { @Override public void run() { toast(s); } });
            return;
        }
        try { Toast.makeText(app, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) { }
    }
}

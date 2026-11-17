package com.xsdanser.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v29 控制台首页：标题 + 参数条 + 指标卡 + 方案大字 + 快捷按钮。
 *
 * <p>方案切换仍只在家长设置（PIN 门禁）里进行，首页只读。
 * v29：删除「当前状态」「快捷操作」分节标题与底部统计行，页面更干净；
 * 方案说明从 14sp 小灰字放大为 19sp 白色居中大字；指标卡加高到 150dp、
 * 按钮字号 16sp，视觉更显眼。
 * v28：取消整张「当前方案」大框，方案名收进顶部徽标，三项实时参数压成
 * 一行无框参数条；五个快捷按钮各用一种非绿常态色（红/蓝/琥珀/紫/深灰），
 * 只有遥控器聚焦才统一变绿。
 */
public class MainActivity extends Activity {

    private static final int GRID_COLS = 5;

    private SettingsStore st;
    private FrameLayout rootView;
    private Button btnGuard, btnQuiz, btnWrong;
    private final java.util.List<Button[]> gridRows = new java.util.ArrayList<Button[]>();
    private PinVerifyView verifyView;

    private Boolean paintedRunning = null;
    private String appVersion = "?", appVersionCode = "?";
    private boolean alive = false, canDrawCached = false;
    /** v55 方案 A：被守护作为「答题承载页」提上来盖视频 HWC 层时为 true。 */
    private boolean quizHostMode = false;
    /** v55 方案 A：跨进程不可见的承载页意图键（与服务端 smali 字符串保持一致）。 */
    static final String ZYX_HOST_EXTRA = "com.xsdanser.tv.ZYX_HOST_EXTRA";

    /**
     * v60：ADB 常驻守护的静默拉起入口。
     * 小米 lmkd 会 forceclosePackage 让包进入 stopped 状态，三方 App 的
     * START_STICKY / 静态广播 / 闹钟全部失效，只能由外部显式 am start 拉起。
     * 带此 extra 时只重建守护服务 + 按绝对到期时间补扣空白期（v68），
     * 不铺首页、不抢电视焦点。
     */
    static final String ZYX_SILENT_EXTRA = "com.xsdanser.tv.ZYX_SILENT_LAUNCH";
    /** v60：当前 Activity 是否由静默拉起创建（决定 onResume / onNewIntent / BACK 行为）。 */
    private boolean silentLaunchMode = false;

    /** 通知权限授予后补执行的守护动作（Android 13+ 先申请 POST_NOTIFICATIONS 再启动）。 */
    private String pendingAction;

    private Metric mGuard, mNext;
    private TextView modeBadge, schemeNote;
    /** v28：无框参数条里的三项实时参数（标签 + 值），取代概览大卡。 */
    private final TextView[] paramLabel = new TextView[3];
    private final TextView[] paramValue = new TextView[3];
    private String aboutText = "";
    /**
     * v50 矮屏适配：首页固定块在 1080p/density320 下合计约 414dp，
     * 而 720p/density320 只有 360dp，导致「方案说明」所在的 weight 弹性区被压成 0、
     * 19sp 胶囊下半截被裁（用户反馈「文字底部被挡住」）。
     * 这里按屏幕可用高度等比压缩所有固定尺寸；1080p 时恒为 1，外观零变化。
     */
    private float uiScale = 1f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable renderOnce = new Runnable() {
        @Override public void run() { if (alive) render(); }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            render();
            handler.postDelayed(this, 1000);
        }
    };
    /** v60：静默拉起在 onResume 之后把本 task 送回电视，窗口尚未绘制，肉眼无闪。 */
    private final Runnable silentBack = new Runnable() {
        @Override public void run() {
            if (silentLaunchMode) moveTaskToBack(true);
        }
    };
    /**
     * v62：承载页残留兜底。releaseHost() 依赖静态 sHost，进程被杀/重建后可能为空，
     * 答完题（含自主可控按返回退出）承载页就可能留在前台露出空白页。
     * 延时复核：答题遮罩已不在就把承载页送回原视频 App。
     */
    private final Runnable hostAutoBack = new Runnable() {
        @Override public void run() {
            if (quizHostMode && !QuizOverlayService.isQuizShowing()) moveTaskToBack(true);
        }
    };

    private static final class Metric {
        TextView label, value, sub;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        alive = true;
        // v60：ADB 常驻守护的静默拉起（进程被强停后 stopped=true 的唯一外部入口）。
        // 必须在承载页分支之前判断，且不铺首页 UI。
        if (handleSilentLaunch(getIntent(), true)) return;
        // v55 方案 A：被守护当作「答题承载页」提上来盖视频 HWC 层时不走首页逻辑，
        // 直接铺一张不透明全屏兜底背景，答题遮罩（TYPE_APPLICATION_OVERLAY）绘于其上，
        // 孩子必然看得见；答完由服务侧 QuizHost.releaseHost() 把用户送回原 App。
        if (getIntent() != null
                && getIntent().getBooleanExtra(ZYX_HOST_EXTRA, false)) {
            quizHostMode = true;
            ScreenFit.apply(this);
            setContentView(buildHostView());
            QuizHost.setHost(this);
            V10Ui.fitEdgeToEdge(this);
            return;
        }
        quizHostMode = false;
        st = new SettingsStore(this);
        QuizMode.sync(this);            // 首页要读当前方案与观看时间余额
        canDrawCached = canDraw();
        preloadBank();
        readVersion();
        // v51：先把 density 基准化（720p~8K 全分辨率），再算矮屏压缩；
        // 顺序不能反，否则 computeUiScale 读到旧 density 会与本页 helper 二次缩放。
        ScreenFit.apply(this);
        computeUiScale();          // v50：必须在 buildView 之前，矮屏等比压缩
        setContentView(buildView());
        V10Ui.fitEdgeToEdge(this);   // v49：Android 15 边到边适配
        render();
        if (LaunchPolicy.shouldAutoGuard(b == null, st.autoGuardOnBoot(),
                canDrawCached, QuizOverlayService.isRunning())) {
            send(QuizOverlayService.ACTION_START);
            Toast.makeText(this, "已自动开启守护", Toast.LENGTH_SHORT).show();
            handler.postDelayed(renderOnce, 300);
        }
        // v52：首次启动弹一次配置向导，引导家长开启悬浮窗 / 自启动 / 电池优化三项。
        if (st.firstLaunch()) {
            showFirstLaunchGuide();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // v60：静默实例永远不算「App 前台」（否则服务不会提承载页，答题可能不可见），
        // 并在窗口 resume 完成后立刻把 task 送回电视。
        if (silentLaunchMode) {
            QuizHost.markUiForeground(false);
            handler.removeCallbacks(silentBack);
            handler.post(silentBack);
            return;
        }
        QuizHost.markUiForeground(true);   // v55 方案 A：标记本 App 回到前台
        if (quizHostMode) {
            // v62：不依赖 sHost 的兜底，遮罩关闭后立刻把承载页送回后台
            handler.removeCallbacks(hostAutoBack);
            handler.postDelayed(hostAutoBack, 1200L);
            return;                        // 承载页不跑首页刷新逻辑
        }
        handler.removeCallbacks(hostAutoBack);
        alive = true;
        QuizMode.sync(this);
        canDrawCached = canDraw();
        WrongBook.refreshAsync(getApplicationContext(), new WrongBook.RefreshCallback() {
            @Override public void onLoaded(java.util.List<WrongBook.Entry> entries, int total) {
                if (alive) render();
            }
        });
        handler.removeCallbacks(refresh);
        handler.post(refresh);
        // v28：自主可控 + 余额 0 时，打开 App / 从别处回首页都补一次答题，
        // 不再出现「首页显示 0:00 却安安静静」。家长设置在前台会自动抑制。
        QuizMode.onHomeResumed(this);
    }

    @Override protected void onPause() {
        super.onPause();
        QuizHost.markUiForeground(false);  // v55 方案 A：标记本 App 已离开前台
        if (quizHostMode || silentLaunchMode) return;  // 承载页/静默页不清理首页回调
        handler.removeCallbacks(refresh);
    }

    /** v55 方案 A：singleTask 复用实例时，承载页意图再次到达需重建承载页。 */
    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        boolean wasHost = quizHostMode;
        boolean wasSilent = silentLaunchMode;
        boolean host = (i != null && i.getBooleanExtra(ZYX_HOST_EXTRA, false));
        boolean silent = (i != null && i.getBooleanExtra(ZYX_SILENT_EXTRA, false));
        // v58：singleTask 复用时 getIntent() 仍是旧的 host intent；不 setIntent 的话
        // recreate() 会再次走进承载页分支，形成死循环。
        setIntent(i != null ? i : new Intent(this, MainActivity.class));
        // v60：进程本来就活着时守护误判重复拉起，只补一次服务，不打断当前首页。
        if (silent && !wasHost && !wasSilent && !host) {
            QuizMode.sync(this);
            send(QuizOverlayService.ACTION_START);
            return;
        }
        quizHostMode = host;
        silentLaunchMode = silent;
        if (silent) {
            handler.removeCallbacks(silentBack);
            handler.post(silentBack);
            return;
        }
        if (host) {
            handler.removeCallbacks(silentBack);
            ScreenFit.apply(this);
            setContentView(buildHostView());
            QuizHost.setHost(this);
            V10Ui.fitEdgeToEdge(this);
        } else if (wasHost || wasSilent) {
            // v58：承载页完成使命后 Activity 保留在后台（不 finish，避免 MIUI LMK
            // 把进程降到 adj 9 整包强停）。用户从桌面重新点开时恢复成正常首页。
            // v60：静默实例被用户从桌面点开时同样 recreate 回正常首页。
            recreate();
        }
    }

    @Override public void onBackPressed() {
        // v55 方案 A：承载页期间答题遮罩仍在，禁止后退把承载页关掉而露出空窗
        if (quizHostMode && QuizOverlayService.isQuizShowing()) return;
        // v58：MiBOX4 会对失去全部 Activity 的进程（adj 9）执行 forceclosePackage。
        // 返回键一律退到后台而不是 finish，保住进程优先级与守护常驻。
        moveTaskToBack(true);
    }

    @Override protected void onDestroy() {
        alive = false;
        handler.removeCallbacksAndMessages(null);
        verifyView = null;
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (verifyView != null && e.getAction() == KeyEvent.ACTION_DOWN
                && e.getRepeatCount() == 0) {
            int code = e.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK) { verifyView.cancel(); return true; }
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                verifyView.key(String.valueOf(code - KeyEvent.KEYCODE_0));
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL) { verifyView.key("退格"); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    private void readVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            appVersion = pi.versionName == null ? "?" : pi.versionName;
            appVersionCode = String.valueOf(pi.versionCode);
        } catch (Exception e) {
            appVersion = "?";
            appVersionCode = "?";
        }
    }

    private void preloadBank() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() {
                QuizDb.open(app);
                BankRepository.installBuiltin(app, st);
                WrongBook.refreshAsync(app);
                handler.post(renderOnce);
            }
        }, "xsdanser-db-preload").start();
    }

    private boolean canDraw() {
        try {
            Class<?> c = Class.forName("android.provider.Settings");
            java.lang.reflect.Method m = c.getMethod("canDrawOverlays", Context.class);
            return ((Boolean) m.invoke(null, this)).booleanValue();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * v60：ADB 守护专用静默拉起。
     *
     * <p>小米 lmkd 的 forceclosePackage 会让包进入 stopped 状态，三方 App 的
     * START_STICKY / 静态广播 / 闹钟全部失效，只能由外部显式 am start 拉起。
     * 这里做完「重建守护服务 + 按 deadline 补扣被杀空白期 + 补欠题」三件事，
     * 再由 onResume 里的 silentBack 把 task 送回电视；不铺首页、不 finish。
     *
     * @param fresh true 表示 onCreate 新建（需要铺透明窗口），false 表示 onNewIntent 复用
     * @return true 表示本次启动是静默拉起，调用方应直接 return
     */
    private boolean handleSilentLaunch(Intent i, boolean fresh) {
        if (i == null || !i.getBooleanExtra(ZYX_SILENT_EXTRA, false)) return false;
        silentLaunchMode = true;
        quizHostMode = false;
        if (fresh) {
            // 透明窗口 + 空内容视图：即使绘制了一帧也不会盖住电视画面。
            getWindow().setBackgroundDrawable(new ColorDrawable(0));
            setContentView(new View(this));
        }
        QuizMode.sync(this);                        // v68 冷启动按 deadline 补扣空白期，欠题照常补弹
        send(QuizOverlayService.ACTION_START);       // 重建 QuizOverlayService（幂等）
        QuizMode.onHomeResumed(this);                // 有欠题走统一 consumePending 链路补弹
        return true;
    }

    private void send(String action) {
        Intent i = new Intent(this, QuizOverlayService.class);
        i.setAction(action);
        try { startService(i); }
        catch (Exception e) {
            Toast.makeText(this, "服务启动失败", Toast.LENGTH_LONG).show();
        }
    }

    /** 只在未授权时由守护/出题操作兜底跳转；设置入口已移到家长设置。 */
    private void openOverlaySettings() {
        Intent i = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION",
                Uri.parse("package:" + getPackageName()));
        try { startActivity(i); }
        catch (Exception e) { Toast.makeText(this, "该设备没有悬浮窗权限入口",
                Toast.LENGTH_LONG).show(); }
    }

    // ---------------------------------------------------------------- 布局

    /** 按屏幕可用高度算首页整体缩放系数：1080p=1（外观不变），720p≈0.72。 */
    private void computeUiScale() {
        // v51：density 已由 ScreenFit 基准化，availDp 恒 >= 540，这里恒为 1，
        // 不再重复缩放；仅当 ScreenFit 因异常未生效时退回 v50 的矮屏压缩兜底。
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density > 0 ? dm.density : 1f;
        float availDp = dm.heightPixels / density;
        // 500dp = 固定块 414dp + 方案说明胶囊 ~56dp + 30dp 余量（状态栏/字体放大/过扫描）。
        final float NEED_DP = 500f;
        uiScale = Math.min(1f, Math.max(0.60f, availDp / NEED_DP));
    }

    /** 等比缩放的 dp。 */
    private int d(float v) { return V10Ui.dp(this, v * uiScale); }

    /** 等比缩放的固定高度行。 */
    private LinearLayout.LayoutParams fixed(float dp) {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, d(dp));
    }

    /** 等比缩放字号的 TextView。 */
    private TextView tv(String text, int sp, int color, int gravity) {
        TextView t = V10Ui.tv(this, text, sp, color, gravity);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp * uiScale);
        return t;
    }

    /** 等比缩放字号与内边距的按钮。 */
    private Button button(String text, int bg, int fg, int sp) {
        Button b = V10Ui.button(this, text, bg, fg, sp);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp * uiScale);
        b.setPadding(d(12), d(6), d(12), d(6));
        return b;
    }

    private Metric addMetric(LinearLayout row, String label, int color) {
        LinearLayout card = V10Ui.vbox(this);
        card.setBackground(V10Ui.outlined(V10Ui.SURFACE, 16, this, 1, V10Ui.STROKE));
        card.setGravity(Gravity.CENTER);
        card.setPadding(d(12), d(10),
                d(12), d(10));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        cp.setMargins(d(6), 0, d(6), 0);
        row.addView(card, cp);

        Metric m = new Metric();
        m.label = tv(label, 12, V10Ui.MUTED, Gravity.CENTER);
        m.label.setSingleLine(true);
        card.addView(m.label, V10Ui.matchWrap());
        m.value = tv("—", 30, color, Gravity.CENTER);
        m.value.setSingleLine(true);
        m.value.setPadding(0, d(2), 0, 0);
        card.addView(m.value, V10Ui.matchWrap());
        m.sub = tv("", 11, V10Ui.MUTED, Gravity.CENTER);
        m.sub.setSingleLine(true);
        card.addView(m.sub, V10Ui.matchWrap());
        return m;
    }

    private Button action(LinearLayout row, String label, int bg, View.OnClickListener l) {
        // v29：按钮字号 15→16，与去掉分节标题后更大的整体布局匹配。
        Button b = button(label, bg, V10Ui.TEXT, 16);
        b.setSingleLine(true);
        b.setOnClickListener(l);
        V10Ui.focus(b, this, bg, V10Ui.TEXT);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(d(5), d(4),
                d(5), d(4));
        row.addView(b, p);
        return b;
    }

    private void wireGrid() {
        for (int r = 0; r < gridRows.size(); r++) {
            Button[] row = gridRows.get(r);
            for (int c = 0; c < row.length; c++) {
                Button b = row[c];
                if (b == null) continue;
                if (c > 0) b.setNextFocusLeftId(V10Ui.ensureId(row[c - 1]));
                if (c < row.length - 1) b.setNextFocusRightId(V10Ui.ensureId(row[c + 1]));
                if (r > 0) b.setNextFocusUpId(V10Ui.ensureId(gridRows.get(r - 1)[c]));
                if (r < gridRows.size() - 1) b.setNextFocusDownId(V10Ui.ensureId(gridRows.get(r + 1)[c]));
            }
        }
    }

    /** v28：参数条里的一段「标签 + 实时值」，无卡片边框，只用细竖线分隔。 */
    private void param(LinearLayout row, int idx, String label, String value, int dot) {
        LinearLayout cell = V10Ui.vbox(this);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(d(18), d(4),
                d(18), d(4));
        row.addView(cell, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        LinearLayout head = V10Ui.hbox(this);
        View d = new View(this);
        d.setBackgroundColor(dot);
        head.addView(d, new LinearLayout.LayoutParams(
                d(3), d(11)));
        paramLabel[idx] = tv(label, 11, V10Ui.MUTED, Gravity.LEFT);
        paramLabel[idx].setSingleLine(true);
        paramLabel[idx].setPadding(d(7), 0, 0, 0);
        head.addView(paramLabel[idx], V10Ui.matchWrap());
        cell.addView(head, V10Ui.matchWrap());

        paramValue[idx] = tv(value, 17, V10Ui.ACCENT_LIGHT, Gravity.LEFT);
        paramValue[idx].setSingleLine(true);
        paramValue[idx].setPadding(0, d(2), 0, 0);
        cell.addView(paramValue[idx], V10Ui.matchWrap());

        if (idx < 2) {
            View sep = new View(this);
            sep.setBackgroundColor(V10Ui.STROKE);
            row.addView(sep, new LinearLayout.LayoutParams(
                    Math.max(1, d(1)), d(30)));
        }
    }

    /**
     * 控制台首页背景：优先用 assets/console_bg.png（贴合「学时兑」意境的暗色背景图），
     * 加载失败则回退到纯色 V10Ui.BG，保证任何异常下页面都能正常显示。
     */
    private Drawable consoleBackground() {
        try {
            Bitmap bm = BitmapFactory.decodeStream(getAssets().open("console_bg.png"));
            if (bm != null) return new BitmapDrawable(getResources(), bm);
        } catch (Exception ignored) {
            // 资源缺失或解码失败：回退纯色
        }
        return new ColorDrawable(V10Ui.BG);
    }

    private View buildView() {
        rootView = new FrameLayout(this);
        LinearLayout l = V10Ui.vbox(this);
        l.setBackground(consoleBackground());
        l.setPadding(d(40), d(16),
                d(40), d(10));

        // 顶部：产品名 + 一句话说明 | 版本 | 当前方案徽标
        LinearLayout head = V10Ui.hbox(this);
        LinearLayout titles = V10Ui.vbox(this);
        titles.addView(tv("学时兑", 25, V10Ui.TEXT, Gravity.LEFT),
                V10Ui.matchWrap());
        titles.addView(tv("看电视 · 答个题 · 有约定", 12,
                V10Ui.MUTED, Gravity.LEFT), V10Ui.matchWrap());
        head.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(tv("v" + appVersion, 12, V10Ui.MUTED, Gravity.RIGHT),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        modeBadge = tv("当前方案 · 严谨守护", 13,
                V10Ui.ACCENT_LIGHT, Gravity.CENTER);
        modeBadge.setBackground(V10Ui.round(0xFF152A4A, 12, this));
        modeBadge.setPadding(d(14), d(4),
                d(14), d(4));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = d(14);
        head.addView(modeBadge, bp);
        // 高度按内容自适应，避免两行标题在部分密度下被固定高度裁掉下半截。
        LinearLayout.LayoutParams hp = V10Ui.matchWrap();
        hp.bottomMargin = d(6);
        l.addView(head, hp);

        // ① v28：取消整张「当前方案」大框，三项实时参数压成一行无框参数条
        LinearLayout params = V10Ui.hbox(this);
        param(params, 0, "出题频率", "—", V10Ui.ACCENT_LIGHT);
        param(params, 1, "每轮题数", "—", V10Ui.AMBER);
        param(params, 2, "答错停留", "—", V10Ui.PURPLE);
        l.addView(params, fixed(54));
        View paramLine = new View(this);
        paramLine.setBackgroundColor(V10Ui.STROKE);
        LinearLayout.LayoutParams plp = V10Ui.matchWrap();
        plp.height = Math.max(1, d(1));
        plp.bottomMargin = d(6);
        l.addView(paramLine, plp);

        // ② 当前状态：v29 去掉「当前状态」分节标题，两张大指标卡直接成为视觉主体
        LinearLayout metrics = V10Ui.hbox(this);
        mGuard = addMetric(metrics, "守护状态", V10Ui.ACCENT_LIGHT);
        mNext  = addMetric(metrics, "距下次出题", V10Ui.AMBER);
        LinearLayout.LayoutParams mp = fixed(150);
        mp.topMargin = d(6);
        l.addView(metrics, mp);

        // 弹性留白里放一句放大的方案说明：v29 从 14sp 提到 19sp、白色居中，
        // 外面套一枚细描边胶囊，在空白区里成为一条明确的视觉分隔，不显孤立。
        LinearLayout noteRow = V10Ui.hbox(this);
        noteRow.setGravity(Gravity.CENTER);
        LinearLayout notePill = V10Ui.hbox(this);
        notePill.setGravity(Gravity.CENTER);
        notePill.setBackground(V10Ui.outlined(0xFF101B2E, 24, this, 1,
                V10Ui.ACCENT_DEEP));
        notePill.setPadding(d(28), d(11),
                d(28), d(11));
        schemeNote = tv("", 19, V10Ui.TEXT, Gravity.CENTER);
        // 中文没有下伸部，默认 font padding 会让文字视觉上贴住胶囊底边；
        // 关掉后上下留白对称，矮屏上不会再出现「底部被挡」的错觉。
        schemeNote.setIncludeFontPadding(false);
        schemeNote.setSingleLine(false);
        // 宽度贴合文字：MATCH_PARENT 会让父按整屏宽度测量，矮屏上容易多换行被裁。
        notePill.addView(schemeNote, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // 宽度必须 wrap_content，否则会被 match_parent 拉成整条横带。
        noteRow.addView(notePill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        // v50：height 由 0 改为 WRAP_CONTENT，保证至少容纳胶囊完整高度；
        // weight 仍为 1，屏幕有富余时继续吸收留白让胶囊居中。
        // 配合 computeUiScale() 压缩固定块，720p 下不会再被挤成 0 导致文字截断。
        noteRow.setMinimumHeight(d(56));
        l.addView(noteRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ③ 快捷操作：v29 去掉「快捷操作」分节标题，一行 5 个按钮直接落地
        LinearLayout row1 = V10Ui.hbox(this);
        Button[] r1 = new Button[GRID_COLS];
        r1[0] = btnGuard = action(row1, GuardState.toggleLabel(false),
                V10Ui.BTN_RED, new View.OnClickListener() {
                    @Override public void onClick(View v) { toggleGuard(); }
                });
        r1[1] = btnQuiz = action(row1, "立即出题", V10Ui.BTN_BLUE,
                new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!canDraw()) { openOverlaySettings(); return; }
                if (QuizMode.mode(MainActivity.this) == QuizMode.MODE_FREE) {
                    // 自主可控：主动进入答题攒时间，不受「余额充足不弹题」拦截。
                    QuizMode.requestManualQuiz(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            "去答题攒时间 · 答对一题 +"
                                    + QuizMode.rewardMinutes(MainActivity.this) + " 分钟",
                            Toast.LENGTH_SHORT).show();
                } else {
                    send(QuizOverlayService.ACTION_QUIZ_NOW);
                }
            }
        });
        r1[2] = action(row1, "家长设置", V10Ui.BTN_AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        r1[3] = btnWrong = action(row1, "错题库", V10Ui.BTN_PURPLE,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        startActivity(new Intent(MainActivity.this, WrongBookActivity.class));
                    }
                });
        r1[4] = action(row1, "关于", V10Ui.BTN_SLATE, new View.OnClickListener() {
            @Override public void onClick(View v) { showAbout(); }
        });
        // v32：整排按钮从贴底改为留出 TV 安全区；上方加一条细线托住操作区，
        // 信息区与操作区更分明，也避开电视过扫描把最下方按钮裁掉。
        View actionsLine = new View(this);
        actionsLine.setBackgroundColor(V10Ui.STROKE);
        LinearLayout.LayoutParams alp = V10Ui.matchWrap();
        alp.height = Math.max(1, d(1));
        alp.topMargin = d(6);
        l.addView(actionsLine, alp);
        LinearLayout.LayoutParams rlp = fixed(66);
        rlp.topMargin = d(8);
        rlp.bottomMargin = d(34);
        l.addView(row1, rlp);
        gridRows.add(r1);
        wireGrid();
        // v29：删掉底部「错题/答对/规则与密码」统计行与细线，页面收在按钮排。

        rootView.addView(l, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return rootView;
    }

    /** v55 方案 A：承载页 —— 不透明全屏兜底背景，铺在视频退后台后的空窗之上，
     *  答题遮罩（TYPE_APPLICATION_OVERLAY）绘制在其上，孩子必然看得见；它本身只是一张
     *  安静的背景，真正的答题交互由守护服务的悬浮遮罩承载。 */
    private View buildHostView() {
        LinearLayout l = V10Ui.vbox(this);
        l.setBackgroundColor(V10Ui.BG);
        // v62：按用户要求取消「答题时间到啦」提示界面。承载页只保留一层不透明
        // 背景用于盖住视频 HWC 层，题目全部由守护服务的悬浮遮罩承载；按返回键
        // 退出答题后不会再露出一张写着「该答题了」的空页面。
        return l;
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("关于 学时兑")
                .setMessage(aboutText)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** v52：首次启动引导。讲清三项必须手动开启的系统能力，并引导去家长设置逐项开启。 */
    private void showFirstLaunchGuide() {
        new AlertDialog.Builder(this)
                .setTitle("守护能力设置引导")
                .setMessage("为保证 App 能在后台守护、开机自动启动、息屏不被回收，请完成三项设置：\n\n"
                        + "1. 悬浮窗授权：允许「在其他应用上显示」，否则全屏答题遮罩无法弹出；\n"
                        + "2. 开机自启：在厂商设置里允许本应用自启动（已默认开启，仍需厂商白名单放行）；\n"
                        + "3. 电池优化：设为「不优化」，否则息屏后守护可能被系统回收。\n\n"
                        + "点击下方「前往家长设置」可逐项开启（首次需设置家长密码）。")
                .setPositiveButton("前往家长设置", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        st.setGuided();
                        try {
                            startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                        } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("稍后", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { st.setGuided(); }
                })
                .show();
    }

    private void blockBase(boolean block) {
        View base = rootView.getChildAt(0);
        if (base instanceof ViewGroup) {
            ((ViewGroup) base).setDescendantFocusability(block
                    ? ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    : ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
    }

    // ---------------------------------------------------------------- 关闭守护验证

    private void showVerify() {
        hideVerify();
        verifyView = new PinVerifyView(this, st, new PinVerifyView.Callback() {
            @Override public void onVerified() { doStop(); }
            @Override public void onCancel() { hideVerify(); }
        });
        blockBase(true);
        rootView.addView(verifyView.view(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        verifyView.requestFocusFirst();
    }

    private void hideVerify() {
        blockBase(false);
        if (verifyView != null) {
            try { rootView.removeView(verifyView.view()); } catch (Exception ignored) { }
            verifyView = null;
        }
    }

    private void doStop() {
        hideVerify();
        QuizOverlayService.requestStop(this, true);
        render();
        handler.postDelayed(renderOnce, 350);
    }

    private void toggleGuard() {
        if (QuizOverlayService.isRunning()) {
            if (GuardPolicy.requiresPinToStop(st.hasPin())) showVerify();
            else doStop();
            return;
        }
        if (!canDraw()) { openOverlaySettings(); return; }
        startGuardWithNotifyPermission();
    }

    private static final int REQ_POST_NOTIFY = 1001;
    /** 反射取 API level（编译 stub 较旧无 Build.VERSION.SDK_INT）。 */
    private static int sdkInt() {
        try {
            return ((Integer) Class.forName("android.os.Build$VERSION")
                    .getField("SDK_INT").get(null)).intValue();
        } catch (Throwable t) { return 0; }
    }
    /** 开启守护前先确保通知权限（Android 13+），否则前台服务通知被系统抑制。 */
    private void startGuardWithNotifyPermission() {
        if (sdkInt() >= 33 && !hasNotifyPermission()) {
            pendingAction = QuizOverlayService.ACTION_START;
            requestNotifyPermission();
            return;
        }
        send(QuizOverlayService.ACTION_START);
        handler.postDelayed(renderOnce, 300);
    }

    /** 反射检查 POST_NOTIFICATIONS 是否授权；反射失败（旧平台无该方法）视为已授权。 */
    private boolean hasNotifyPermission() {
        try {
            java.lang.reflect.Method m = getClass().getMethod("checkSelfPermission", String.class);
            int r = ((Integer) m.invoke(this, "android.permission.POST_NOTIFICATIONS")).intValue();
            return r == 0; // PackageManager.PERMISSION_GRANTED == 0
        } catch (Throwable t) {
            return true;
        }
    }

    /** 反射申请通知权限；反射失败则退而直接启动守护（仅通知显示受影响）。 */
    private void requestNotifyPermission() {
        try {
            java.lang.reflect.Method m = getClass().getMethod(
                "requestPermissions", String[].class, int.class);
            m.invoke(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIFY);
        } catch (Throwable t) {
            send(QuizOverlayService.ACTION_START);
            handler.postDelayed(renderOnce, 300);
        }
    }

    /** 运行时 Activity 存在该方法；编译 stub 较旧未暴露，故不加 @Override、不调 super，
     *  运行时仍被正确重写为权限回调。 */
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_POST_NOTIFY && pendingAction != null) {
            String a = pendingAction; pendingAction = null;
            send(a);
            handler.postDelayed(renderOnce, 300);
        }
    }

    // v26：修改家长密码已整体迁入「家长设置 → 家长密码」，首页不再保留密码浮层。

    // ---------------------------------------------------------------- 渲染

    private static void setText(TextView t, String s) {
        if (t != null && !s.equals(t.getText().toString())) t.setText(s);
    }

    private static void setButton(Button b, String s) {
        if (b != null && !s.equals(b.getText().toString())) b.setText(s);
    }

    private String intervalLabel() {
        int sec = st.intervalSec();
        for (int i = 0; i < SettingsStore.INTERVALS.length; i++) {
            if (SettingsStore.INTERVALS[i] == sec) return SettingsStore.INTERVAL_LABELS[i];
        }
        return sec + " 秒";
    }

    private static String clock(long ms) {
        long sec = Math.max(0L, ms) / 1000L;
        long mm = sec / 60L, ss = sec % 60L;
        return mm + ":" + (ss < 10 ? "0" : "") + ss;
    }

    /** v28：顶部徽标 + 无框参数条 + 一行方案说明，不再绘制任何方案大卡。 */
    private void renderParams(int mode, boolean free, int lockSec) {
        setText(modeBadge, "当前方案 · " + QuizMode.MODE_NAMES[mode]);
        setText(schemeNote, free
                ? "答题赚时间 · 用完立即暂停 · 可连答不限题数"
                : "到点自动出题 · 暂停视频 · 答对才能继续");
        if (free) {
            setText(paramLabel[0], "起始时长");
            setText(paramValue[0], QuizMode.startMinutes(this) + " 分");
            setText(paramLabel[1], "每题可得");
            setText(paramValue[1], "+" + QuizMode.rewardMinutes(this) + " 分");
            setText(paramLabel[2], "剩余时间");
            setText(paramValue[2], clock(QuizMode.balanceMs(this)));
        } else {
            setText(paramLabel[0], "出题频率");
            setText(paramValue[0], "每 " + intervalLabel());
            setText(paramLabel[1], "每轮题数");
            setText(paramValue[1], st.quizPerRound() + " 题");
            setText(paramLabel[2], "答错停留");
            setText(paramValue[2], lockSec <= 0 ? "关闭" : lockSec + " 秒");
        }
    }

    private void render() {
        if (!alive || mGuard == null) return;
        boolean running = QuizOverlayService.isRunning();
        QuizMode.sync(this);
        int mode = QuizMode.mode(this);
        boolean free = mode == QuizMode.MODE_FREE;
        renderParams(mode, free, VoiceCoach.lockSec(this));

        setText(mGuard.value, running ? "运行中" : "已停止");
        mGuard.value.setTextColor(running ? V10Ui.ACCENT_LIGHT : V10Ui.RED);
        setText(mGuard.sub, running ? "答题守护已开启" : "点下方按钮开启");

        if (free) {
            long left = QuizMode.balanceMs(this);
            setText(mNext.label, "剩余观看时间");
            setText(mNext.value, clock(left));
            setText(mNext.sub, left <= 0L ? "答一题就能继续看" : "答题可继续赚时长");
            mNext.value.setTextColor(left <= 0L ? V10Ui.RED : V10Ui.ACCENT_LIGHT);
        } else if (running && !QuizOverlayService.isQuizShowing()) {
            setText(mNext.label, "距下次出题");
            // v70：改为按落盘的绝对到期时间显示，前台每秒倒数；服务里内存字段
            // remainSec 由 QuizMode.tickSecond() 每秒按同一 deadline 校正。
            setText(mNext.value, String.valueOf(QuizMode.guardRemainSec(this)));
            setText(mNext.sub, "秒后出题");
            mNext.value.setTextColor(V10Ui.ACCENT_LIGHT);
        } else if (running) {
            setText(mNext.label, "距下次出题");
            setText(mNext.value, "答题中");
            setText(mNext.sub, "遮罩已弹出");
            mNext.value.setTextColor(V10Ui.ACCENT_LIGHT);
        } else {
            setText(mNext.label, "距下次出题");
            setText(mNext.value, "—");
            setText(mNext.sub, "守护未开启");
            mNext.value.setTextColor(V10Ui.ACCENT_LIGHT);
        }

        setButton(btnWrong, "错题库 · " + WrongBook.cachedCount());
        // v22：自主可控时这个按钮是「主动进去答题攒时间」，不是被动出题。
        setButton(btnQuiz, free ? "立即答题" : "立即出题");

        if (paintedRunning == null || paintedRunning.booleanValue() != running) {
            paintedRunning = Boolean.valueOf(running);
            // v28：五个按钮常态色固定互不相同，守护开关只换文字、不换底色。
            setButton(btnGuard, GuardState.toggleLabel(running));
            V10Ui.focus(btnGuard, this, V10Ui.BTN_RED, V10Ui.TEXT);
            btnGuard.setBackground(V10Ui.round(V10Ui.BTN_RED, 12, this));
        }

        aboutText = "学时兑  v" + appVersion + "（build " + appVersionCode + "）\n"
                + "当前答题方案：" + QuizMode.MODE_NAMES[mode] + "\n"
                + "悬浮窗权限：" + (canDrawCached ? "已授权" : "未授权") + "\n"
                + "错题：" + WrongBook.cachedCount() + " 题  ·  答对 "
                + st.correct() + " / " + st.questioned() + " 题";
    }
}

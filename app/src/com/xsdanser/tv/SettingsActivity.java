package com.xsdanser.tv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * v12 家长设置：单列深色卡片流，分类项全部由 quiz.db 真实数据驱动。
 *
 * <p>相对 v10 的改动：
 * <ul>
 *   <li>删除旧 K12 失效项：1–9 年级循环、仅三科/全学科、学习学期三档循环、
 *       已学单元、其他学科配比行、每行独立的 8 档教材版本。</li>
 *   <li>新增「题库信息」只读卡片；出题范围改为 教材版本 / 年级 / 学期，
 *       可选值从 questions 表 DISTINCT 查询，库里没有的值根本不出现。</li>
 *   <li>新增「知识点范围」：数学 / 语文 / 英语各自按 topic_group 大类筛选。</li>
 *   <li>「学科配比」只保留 0–5 配比，不再与教材版本混在一行。</li>
 *   <li>保留：门禁页无忘记密码提示、保存按钮在表单内不悬浮。</li>
 *   <li>v26：修改家长密码入口由控制台并入本页「系统与安全 → 家长密码」，
 *       复用门禁视图的数字键盘；「确定」常态一律蓝色，只有聚焦才变绿。</li>
 * </ul>
 */
public class SettingsActivity extends Activity {

    private static final int GUTTER_DP = 88;
    private static final int LABEL_DP = 150;
    private static final int ROW_GAP_DP = 10;
    private static final int[] ROUND_STEPS = {1, 2, 3, 5, 10, 15, 20, 30};
    // v47：第 4 槽「其它」= 下载导入的非语数英学科题（物理 / 历史 / 科学 …）。
    // SettingsStore / SubjectWeights / QuizOverlayService 来自历史 smali，不参与重编译，
    // 因此配比默认值由本页首次创建时落盘，全学科开关由 save() 与 QuizDb.syncLegacyKeys() 联动。
    private static final int[] CORE = {Subjects.MATH, Subjects.CHINESE,
            Subjects.ENGLISH, Subjects.OTHER};
    private static final String[] CORE_NAMES = {"数学", "语文", "英语", "其它"};

    private SettingsStore st;
    private PinGate gate;
    private QuizDb bank;
    private SharedPreferences sp;
    private boolean inSettings = false;
    /** v26：正在用门禁键盘修改家长密码（与首次设置 / 进入设置页的门禁区分）。 */
    private boolean inChangePin = false;
    private PinGate changeGate;

    private View gateView, settingsView, manageView;
    /** v31：正在显示扫码管理页（二维码 + 管理地址）。 */
    private boolean inManage = false;
    private ImageView manageQr;
    private TextView manageUrl, manageInfo;
    private Button manageBack;
    private TextView gateTitle, gateMask, gateMsg, gateState, gateHint;
    private TextView pinState, bankLine, bankSummaryLine;
    private Button firstGateBtn, gateCancel;
    // v71：家长密码进门慢的根因是历史 SettingsStore 用 60000 轮 PBKDF2，且校验
    // 跑在 UI 线程。这里把迭代降到电视盒子可承受的 8000 轮、把校验移出主线程；
    // 写盘仍是 pinHash / pinSalt / pinIter 三个键，服务侧 SettingsStore.checkPin()
    // 读 pinIter 后照常校验，密码格式完全兼容、不需要迁移数据。
    private static final int FAST_PIN_ITER = 8000;
    private final Handler mUi = new Handler(Looper.getMainLooper());
    private volatile boolean mVerifying = false;
    private boolean mBuildStarted = false, mPendingEnter = false;
    private final PinGate.Store mPinStore = new PinGate.Store() {
        @Override public boolean hasPin() { return st.hasPin(); }
        @Override public boolean checkPin(String pin) {
            boolean ok = st.checkPin(pin);
            // 旧版 60000 轮密码：验证成功后在后台重写成快速格式，下次进门就快了。
            if (ok && sp.getInt("pinIter", 0) > FAST_PIN_ITER) {
                final String p = pin;
                new Thread(new Runnable() {
                    @Override public void run() { writeFastPin(p); }
                }, "pin-migrate").start();
            }
            return ok;
        }
        @Override public boolean setPin(String pin) { return writeFastPin(pin); }
    };
    private Button rowEdition, rowGrade, rowTerm;
    private Button[] rowGroup = new Button[CORE.length];
    private Button[] rowWt = new Button[CORE.length];
    private Button rowMode, rowFreeMin, rowStartMin, rowBalance, rowInterval, rowPerRound;
    private Button rowPinyin;
    private Button rowLock;
    private Button rowOverlay, rowBoot, rowBattery, rowUsage, rowAdmin, rowAutoGuard, rowPause, rowResume, firstSettingsBtn;
    private TextView groupGuard, groupFree;
    private final java.util.List<View[]> focusRows = new java.util.ArrayList<View[]>();
    // v39：题库下载导入
    private EditText dlCode;
    private Button dlBtn;
    private TextView dlStatus;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScreenFit.apply(this);      // v51：全分辨率基准化，须在 buildGate 之前
        st = new SettingsStore(this);
        QuizMode.sync(this);            // v20：读入答题节奏与观看时间余额
        sp = getSharedPreferences(SettingsStore.PREF, MODE_PRIVATE);
        // v47：其它学科配比默认 0。历史 smali 的 SettingsStore.weight(OTHER) 默认 2，
        // 且该类不在重编译目标里，所以由本页首次创建时显式落盘 w3=0（家长改过的值保留）。
        if (!sp.contains("w" + Subjects.OTHER)) {
            sp.edit().putInt("w" + Subjects.OTHER, 0).commit();
        }
        gate = new PinGate(mPinStore);
        gateView = buildGate();
        enterGate(false);   // 先立刻显示门禁，避免 QuizDb.open 阻塞 UI 线程
        // v64/v71：题库打开 + 设置页构建较重，统一交给 ensureSettingsBuilt() 在后台
        // 只跑一次；门禁秒过时显示加载页等待，绝不在 UI 线程重复跑一遍。
        ensureSettingsBuilt();
        // v49：Android 15 适配——边到边 inset + 预测返回手势（API33+）。
        V10Ui.fitEdgeToEdge(this);
        // 注册 OnBackInvokedCallback（反射：编译 stub 不含 android.window.* 类）。
        // 注册成功后系统不再走 onBackPressed，改走 onBackInvoked→handleBack；
        // 注册失败则回退 onBackPressed，功能不受影响。
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Object dispatcher = Activity.class.getMethod("getOnBackInvokedDispatcher").invoke(this);
                if (dispatcher != null) {
                    Class<?> cbIface = Class.forName("android.window.OnBackInvokedCallback");
                    Class<?> dispIface = Class.forName("android.window.OnBackInvokedDispatcher");
                    final SettingsActivity self = this;
                    Object cb = java.lang.reflect.Proxy.newProxyInstance(
                            SettingsActivity.class.getClassLoader(), new Class[]{cbIface},
                            new java.lang.reflect.InvocationHandler() {
                                @Override public Object invoke(Object proxy, java.lang.reflect.Method m, Object[] x) {
                                    if ("onBackInvoked".equals(m.getName())) { self.handleBack(); return null; }
                                    if ("hashCode".equals(m.getName())) return 0;
                                    if ("equals".equals(m.getName())) return proxy == (x != null && x.length > 0 ? x[0] : null);
                                    if ("toString".equals(m.getName())) return "SettingsBackCallback";
                                    return null;
                                }
                            });
                    int priority = dispIface.getField("PRIORITY_DEFAULT").getInt(null);
                    dispIface.getMethod("registerOnBackInvokedCallback", int.class, cbIface)
                            .invoke(dispatcher, priority, cb);
                }
            } catch (Exception ignore) { /* 无回调则回退 onBackPressed */ }
        }
    }

    /** v28：家长设置在前台时抑制自动弹题，避免全屏题盖住正在修改的参数。
     *  v55 方案 A：同时标记本 App 在前台，避免守护误以为孩子在看视频而强行劫持前台。 */
    @Override protected void onResume() {
        super.onResume();
        QuizMode.setSettingsForeground(true);
        QuizHost.markUiForeground(true);
    }

    @Override protected void onPause() {
        QuizMode.setSettingsForeground(false);
        QuizHost.markUiForeground(false);
        super.onPause();
    }

    @Override public void onBackPressed() {
        handleBack();
    }

    /** v49：返回逻辑抽出来，供 onBackPressed（API<33）与 OnBackInvokedCallback（API33+）共用。 */
    private void handleBack() {
        if (inManage) {
            // 返回家长设置但保留管理服务器：家长在手机上仍可继续调参数/锁屏。
            inManage = false;
            enterSettings();
        } else if (inChangePin) exitChangePin();
        else if (inSettings) save();
        else finish();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (!inSettings && e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            int code = e.getKeyCode();
            PinGate active = activeGate();
            if (active == null) return super.dispatchKeyEvent(e);
            // 返回键统一交给 onBackPressed（避免与系统回退重复触发）。
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                // v71：统一走 onKey，验证中不再往门禁里塞新数字。
                onKey(String.valueOf(code - KeyEvent.KEYCODE_0));
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL) {
                onKey("退格");
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------- v71 快速密码校验（与历史 SettingsStore 存储格式完全兼容） ----------

    /** 反射调用系统 PBKDF2WithHmacSHA1，参数与 dex 里的 SettingsStore.derive() 一致。 */
    private static byte[] deriveFast(String pin, byte[] salt, int iter) {
        try {
            Class<?> specCls = Class.forName("javax.crypto.spec.PBEKeySpec");
            Object spec = specCls
                    .getConstructor(char[].class, byte[].class, int.class, int.class)
                    .newInstance(pin.toCharArray(), salt,
                            Integer.valueOf(iter), Integer.valueOf(256));
            Object fac = Class.forName("javax.crypto.SecretKeyFactory")
                    .getMethod("getInstance", String.class)
                    .invoke(null, "PBKDF2WithHmacSHA1");
            Object key = fac.getClass()
                    .getMethod("generateSecret", Class.forName("java.security.spec.KeySpec"))
                    .invoke(fac, spec);
            byte[] out = (byte[]) Class.forName("javax.crypto.SecretKey")
                    .getMethod("getEncoded").invoke(key);
            specCls.getMethod("clearPassword").invoke(spec);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String hexBytes(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x & 0xff));
        return sb.toString();
    }

    /**
     * 用较低迭代重写密码哈希。写盘前备份旧值、写后用 dex 的 SettingsStore.checkPin()
     * 回读校验；任何一步失败都整体回滚，绝不把家长锁在门外。
     */
    private boolean writeFastPin(String pin) {
        if (pin == null || pin.length() < 4) return false;
        try {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            byte[] dk = deriveFast(pin, salt, FAST_PIN_ITER);
            if (dk == null) return false;
            String oldH = sp.getString("pinHash", "");
            String oldS = sp.getString("pinSalt", "");
            int oldI = sp.getInt("pinIter", 0);
            boolean saved = sp.edit()
                    .putString("pinSalt", hexBytes(salt))
                    .putString("pinHash", hexBytes(dk))
                    .putInt("pinIter", FAST_PIN_ITER).commit();
            if (!saved || !st.checkPin(pin)) {
                sp.edit().putString("pinHash", oldH)
                        .putString("pinSalt", oldS)
                        .putInt("pinIter", oldI).commit();
                return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 题库初始化 + 设置页构建只允许启动一次，避免门禁兜底在 UI 线程重复跑一遍。 */
    private synchronized boolean beginBuild() {
        if (mBuildStarted) return false;
        mBuildStarted = true;
        return true;
    }

    private void ensureSettingsBuilt() {
        if (!beginBuild()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BankRepository.installBuiltin(SettingsActivity.this, st);
                    bank = QuizDb.open(SettingsActivity.this);
                    mUi.post(new Runnable() {
                        @Override public void run() {
                            if (settingsView == null) settingsView = buildSettings();
                            if (mPendingEnter) { mPendingEnter = false; enterSettings(); }
                        }
                    });
                } catch (Throwable ignored) {
                    // 极端异常时补一次，避免永远停在「正在载入设置…」。
                    mUi.post(new Runnable() {
                        @Override public void run() {
                            try {
                                bank = QuizDb.open(SettingsActivity.this);
                                settingsView = buildSettings();
                            } catch (Throwable ignored2) { }
                            if (mPendingEnter && settingsView != null) {
                                mPendingEnter = false;
                                setContentView(settingsView);
                                refreshRows();
                            }
                        }
                    });
                }
            }
        }, "settings-build").start();
    }

    /** 后台尚未就绪时先给一个可见的加载页，绝不在主线程同步做重活。 */
    private void showSettingsLoading() {
        LinearLayout l = V10Ui.vbox(this);
        l.setBackgroundColor(V10Ui.BG);
        l.setGravity(Gravity.CENTER);
        l.addView(V10Ui.tv(this, "正在载入设置…", 20, V10Ui.TEXT, Gravity.CENTER));
        setContentView(l);
    }

    // ---------- 密码门禁 ----------

    private View buildGate() {
        LinearLayout l = V10Ui.vbox(this);
        l.setBackgroundColor(V10Ui.BG);
        l.setPadding(V10Ui.dp(this, 40), V10Ui.dp(this, 16),
                V10Ui.dp(this, 40), V10Ui.dp(this, 12));
        l.setGravity(Gravity.CENTER);

        LinearLayout card = V10Ui.vbox(this);
        card.setBackground(V10Ui.outlined(V10Ui.SURFACE, 20, this, 1, V10Ui.ACCENT_DEEP));
        card.setPadding(V10Ui.dp(this, 28), V10Ui.dp(this, 18),
                V10Ui.dp(this, 28), V10Ui.dp(this, 18));
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                V10Ui.dp(this, 500), ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = V10Ui.dp(this, 6);
        l.addView(card, clp);

        gateTitle = V10Ui.tv(this, "", 22, V10Ui.TEXT, Gravity.CENTER);
        card.addView(gateTitle, V10Ui.fixed(this, 36));
        gateMask = V10Ui.tv(this, "", 30, V10Ui.ACCENT_LIGHT, Gravity.CENTER);
        card.addView(gateMask, V10Ui.fixed(this, 40));
        gateMsg = V10Ui.tv(this, "", 14, V10Ui.RED, Gravity.CENTER);
        card.addView(gateMsg, V10Ui.fixed(this, 20));
        gateState = V10Ui.tv(this, "", 12, V10Ui.MUTED, Gravity.CENTER);
        card.addView(gateState, V10Ui.fixed(this, 20));

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "清空", "0", "确定"};
        for (int row = 0; row < 4; row++) {
            LinearLayout r = V10Ui.hbox(this);
            for (int col = 0; col < 3; col++) {
                final String k = keys[row * 3 + col];
                // v26：「确定」常态蓝色，只有获得焦点才变绿（全局配色规则）。
                final int bg = "确定".equals(k) ? V10Ui.BLUE : V10Ui.SLATE;
                Button btn = V10Ui.button(this, k, bg, V10Ui.TEXT, 19);
                btn.setFocusableInTouchMode(false);
                btn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { onKey(k); }
                });
                V10Ui.focus(btn, this, bg, V10Ui.TEXT);
                if (firstGateBtn == null) firstGateBtn = btn;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                        V10Ui.dp(this, 130), V10Ui.dp(this, 44));
                p.setMargins(V10Ui.dp(this, 5), V10Ui.dp(this, 3),
                        V10Ui.dp(this, 5), V10Ui.dp(this, 3));
                r.addView(btn, p);
            }
            card.addView(r, V10Ui.fixed(this, 52));
        }
        gateHint = V10Ui.tv(this, "遥控器按数字键输入，输完点「确定」",
                12, V10Ui.MUTED, Gravity.CENTER);
        card.addView(gateHint, V10Ui.fixed(this, 24));
        // 只有「修改家长密码」流程才显示返回按钮；首次设置 / 日常门禁不显示。
        gateCancel = V10Ui.button(this, "放弃修改 · 返回家长设置", 0xFF26344C,
                V10Ui.MUTED, 14);
        V10Ui.focus(gateCancel, this, 0xFF26344C, V10Ui.MUTED);
        gateCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exitChangePin(); }
        });
        card.addView(gateCancel, V10Ui.fixed(this, 38));
        gateCancel.setVisibility(View.GONE);
        return l;
    }

    private PinGate activeGate() {
        return inChangePin ? changeGate : gate;
    }

    private void onKey(String k) {
        PinGate active = activeGate();
        if (active == null || mVerifying) return;
        if (!"确定".equals(k)) {
            active.key(k);
            refreshGate();
            return;
        }
        // v71：点「确定」后立刻给反馈，把门禁状态机（内含 PBKDF2 校验 / 密码写盘）
        // 放到后台线程，主线程不再被独占，遥控器随时可响应。
        mVerifying = true;
        gateMsg.setText("正在验证…");
        final PinGate g = active;
        new Thread(new Runnable() {
            @Override public void run() {
                g.key("确定");
                mUi.post(new Runnable() {
                    @Override public void run() {
                        mVerifying = false;
                        if (activeGate() != g) return;   // 期间切过门禁，丢弃过期结果
                        refreshGate();
                    }
                });
            }
        }, "pin-verify").start();
    }

    private void refreshGate() {
        PinGate active = activeGate();
        if (active == null) return;
        gateTitle.setText(active.title());
        gateMask.setText(active.mask());
        gateMsg.setText(active.messageText());
        gateState.setText(inChangePin
                ? "修改家长密码 · 完成后自动返回设置"
                : (st.hasPin() ? "已设置家长密码" : "首次使用，请设置密码"));
        gateHint.setText(inChangePin
                ? "先输入旧密码，再输入两次新密码；按返回键放弃"
                : "遥控器按数字键输入，输完点「确定」");
        gateCancel.setVisibility(inChangePin ? View.VISIBLE : View.GONE);
        String t = active.consumeToast();
        if (!t.isEmpty()) Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
        if (active.consumeEnterSettings()) {
            if (inChangePin) {
                exitChangePin();
                Toast.makeText(this, "家长密码已保存", Toast.LENGTH_SHORT).show();
            } else {
                enterSettings();
            }
        }
    }

    /** 家长设置里点「修改家长密码」：复用门禁数字键盘，走 CHG_OLD→CHG_NEW→CHG_CONFIRM。 */
    private void enterChangePin() {
        changeGate = new PinGate(mPinStore);
        // 尚未设置密码时直接走「首次设置」，避免要求输入不存在的旧密码。
        if (st.hasPin()) changeGate.startChange();
        inChangePin = true;
        inSettings = false;
        setContentView(gateView);
        if (firstGateBtn != null) firstGateBtn.requestFocus();
        refreshGate();
    }

    private void exitChangePin() {
        inChangePin = false;
        changeGate = null;
        enterSettings();
    }

    private void enterGate(boolean reset) {
        if (reset) gate.backToGate();
        inChangePin = false;
        changeGate = null;
        inSettings = false;
        setContentView(gateView);
        if (firstGateBtn != null) firstGateBtn.requestFocus();
        refreshGate();
    }

    private void enterSettings() {
        inSettings = true;
        if (settingsView == null) {
            // v71：后台还没建好时只显示加载页并登记「建好后自动进入」，不再在 UI
            // 线程同步跑题库初始化 + 250 行控件构建（旧版卡顿的第二来源）。
            mPendingEnter = true;
            showSettingsLoading();
            ensureSettingsBuilt();
            return;
        }
        setContentView(settingsView);
        refreshRows();
        if (firstSettingsBtn != null) firstSettingsBtn.requestFocus();
    }

    // ---------- 卡片流布局 ----------

    private LinearLayout addCard(LinearLayout parent, String title) {
        LinearLayout card = V10Ui.card(this, title);
        V10Ui.attachCard(parent, card, this);
        return card;
    }

    private void note(LinearLayout parent, String text) {
        parent.addView(V10Ui.note(this, text), rowLp());
    }

    private void track(View... views) {
        java.util.List<View> usable = new java.util.ArrayList<View>();
        for (View v : views) {
            if (v != null && v.isEnabled() && v.isFocusable()) usable.add(v);
        }
        if (!usable.isEmpty()) focusRows.add(usable.toArray(new View[0]));
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int half = V10Ui.dp(this, ROW_GAP_DP / 2);
        p.setMargins(0, half, 0, half);
        return p;
    }

    private Button addRow(LinearLayout parent, String label, View.OnClickListener l) {
        LinearLayout row = V10Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(V10Ui.tv(this, label, 14, V10Ui.MUTED, Gravity.LEFT),
                new LinearLayout.LayoutParams(V10Ui.dp(this, LABEL_DP),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        Button b = V10Ui.button(this, "", V10Ui.SURFACE2, V10Ui.TEXT, 14);
        b.setSingleLine(false);
        b.setMaxLines(2);
        b.setEllipsize(null);
        b.setMinHeight(V10Ui.dp(this, 42));
        b.setPadding(V10Ui.dp(this, 10), V10Ui.dp(this, 2),
                V10Ui.dp(this, 10), V10Ui.dp(this, 2));
        b.setOnClickListener(l);
        V10Ui.focus(b, this, V10Ui.SURFACE2, V10Ui.TEXT);
        row.addView(b, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, rowLp());
        track(b);
        if (firstSettingsBtn == null) firstSettingsBtn = b;
        return b;
    }

    /** 只读信息行：外观与设置行一致，但不是按钮、不进遥控器焦点链。 */
    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = V10Ui.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(V10Ui.tv(this, label, 14, V10Ui.MUTED, Gravity.LEFT),
                new LinearLayout.LayoutParams(V10Ui.dp(this, LABEL_DP),
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = V10Ui.tv(this, value, 14, V10Ui.TEXT, Gravity.LEFT);
        v.setMinHeight(V10Ui.dp(this, 42));
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(V10Ui.dp(this, 10), V10Ui.dp(this, 2),
                V10Ui.dp(this, 10), V10Ui.dp(this, 2));
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(row, rowLp());
    }

    /** 分组小标题：左侧蓝色竖条 + 标题文字，不进遥控器焦点链。 */
    private TextView addGroup(LinearLayout parent, String text) {
        LinearLayout row = V10Ui.hbox(this);
        View bar = new View(this);
        bar.setBackgroundColor(V10Ui.ACCENT);
        row.addView(bar, new LinearLayout.LayoutParams(
                V10Ui.dp(this, 3), V10Ui.dp(this, 14)));
        TextView t = V10Ui.tv(this, text, 13, V10Ui.ACCENT_LIGHT, Gravity.LEFT);
        t.setPadding(V10Ui.dp(this, 8), 0, 0, 0);
        row.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = rowLp();
        lp.topMargin = V10Ui.dp(this, 12);
        parent.addView(row, lp);
        return t;
    }

    private void addButton(LinearLayout parent, String label, int bg, View.OnClickListener l) {
        Button b = V10Ui.button(this, label, bg, V10Ui.TEXT, 15);
        b.setOnClickListener(l);
        V10Ui.focus(b, this, bg, V10Ui.TEXT);
        b.setMinHeight(V10Ui.dp(this, 42));
        parent.addView(b, rowLp());
        track(b);
        if (firstSettingsBtn == null) firstSettingsBtn = b;
    }

    // ---------- v44：官网入口与下载码导入左右并排 ----------

    private void addDownloadSection(LinearLayout card) {
        final String SITE_URL = "https://xsd.qx47.com/";

        // 两块等宽、等高的圆角面板并排同一行：左=官网题库下载（含二维码），右=下载码导入。
        // 统一最小高度 + 两个子面板 height=MATCH_PARENT：横向 LinearLayout 会把
        // 二者强制拉伸到同一高度，二维码框与下载框上下边永远齐平。
        final int panelH = V10Ui.dp(this, 160);
        LinearLayout cols = V10Ui.hbox(this);
        cols.setGravity(Gravity.CENTER_VERTICAL);

        // ---- 左板块：白色圆角二维码卡 + 标题 + 灰色纯文本网址 ----
        LinearLayout left = V10Ui.hbox(this);
        left.setGravity(Gravity.CENTER_VERTICAL);
        left.setMinimumHeight(panelH);
        left.setBackground(V10Ui.outlined(V10Ui.SURFACE2, 14, this, 1, V10Ui.STROKE));
        left.setPadding(V10Ui.dp(this, 14), V10Ui.dp(this, 12),
                V10Ui.dp(this, 14), V10Ui.dp(this, 12));

        // 复用扫码管理的自研 QrEncoder（qrBitmap 自带 4 模块安静区）。
        int qrPx = V10Ui.dp(this, 120);
        LinearLayout qrCard = V10Ui.vbox(this);
        qrCard.setBackground(V10Ui.round(0xFFFFFFFF, 10, this));
        qrCard.setPadding(V10Ui.dp(this, 7), V10Ui.dp(this, 7),
                V10Ui.dp(this, 7), V10Ui.dp(this, 7));
        ImageView siteQr = new ImageView(this);
        siteQr.setBackgroundColor(0xFFFFFFFF);
        siteQr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        siteQr.setImageBitmap(ManageServer.qrBitmap(SITE_URL, qrPx));
        siteQr.setContentDescription("学时兑官网题库二维码");
        qrCard.addView(siteQr, new LinearLayout.LayoutParams(qrPx, qrPx));
        left.addView(qrCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout info = V10Ui.vbox(this);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = V10Ui.dp(this, 14);
        left.addView(info, infoLp);
        info.addView(V10Ui.tv(this, "学时兑官网题库下载", 15, V10Ui.TEXT, Gravity.LEFT),
                V10Ui.matchWrap());
        TextView site = V10Ui.tv(this, SITE_URL, 12, V10Ui.MUTED, Gravity.LEFT);
        site.setPadding(0, V10Ui.dp(this, 6), 0, 0);
        info.addView(site, V10Ui.matchWrap());

        cols.addView(left, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        // ---- 右板块：输入框与按钮换行显示，中间留 12dp 间距 ----
        LinearLayout right = V10Ui.vbox(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setMinimumHeight(panelH);
        right.setBackground(V10Ui.outlined(V10Ui.SURFACE2, 14, this, 1, V10Ui.STROKE));
        right.setPadding(V10Ui.dp(this, 14), V10Ui.dp(this, 12),
                V10Ui.dp(this, 14), V10Ui.dp(this, 12));

        dlCode = new EditText(this);
        dlCode.setHint("输入下载码");
        dlCode.setTextSize(14);
        dlCode.setTextColor(V10Ui.TEXT);
        dlCode.setHintTextColor(V10Ui.MUTED);
        dlCode.setBackground(V10Ui.outlined(V10Ui.SURFACE, 10, this, 1, V10Ui.STROKE));
        dlCode.setPadding(V10Ui.dp(this, 10), V10Ui.dp(this, 6),
                V10Ui.dp(this, 10), V10Ui.dp(this, 6));
        dlCode.setMinHeight(V10Ui.dp(this, 42));
        dlCode.setSingleLine(true);
        dlCode.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        right.addView(dlCode, V10Ui.matchWrap());

        dlBtn = V10Ui.button(this, "下载并导入", V10Ui.ACCENT, V10Ui.TEXT, 14);
        dlBtn.setMinHeight(V10Ui.dp(this, 42));
        dlBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doDownload(); }
        });
        V10Ui.focus(dlBtn, this, V10Ui.ACCENT, V10Ui.TEXT);
        LinearLayout.LayoutParams btnLp = V10Ui.matchWrap();
        btnLp.topMargin = V10Ui.dp(this, 12);
        right.addView(dlBtn, btnLp);

        dlStatus = V10Ui.tv(this, "", 12, V10Ui.MUTED, Gravity.LEFT);
        LinearLayout.LayoutParams statusLp = V10Ui.matchWrap();
        statusLp.topMargin = V10Ui.dp(this, 8);
        right.addView(dlStatus, statusLp);

        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        rightLp.leftMargin = V10Ui.dp(this, 12);
        cols.addView(right, rightLp);

        card.addView(cols, rowLp());
        // 输入框与按钮都纳入遥控器上下焦点链，否则 TV 上聚焦不到输入框、无法输下载码。
        track(dlCode);
        track(dlBtn);
    }

    /** 后台下载 + 导入，结果回到主线程显示。 */
    private void doDownload() {
        final String code = dlCode.getText().toString().trim();
        if (code.isEmpty()) { dlStatus.setText("请先输入下载码"); return; }
        // [DEBUG-REPRO] 本地文件导入：输入 FILE:/sdcard/sample.json 直接导入本地题库，便于复现
        final boolean localFile = code.startsWith("FILE:");
        final String fileArg = localFile ? code.substring(5).trim() : null;
        if (!localFile && !code.matches("[A-Za-z0-9_-]{1,64}")) {
            dlStatus.setText("下载码只能是字母、数字、下划线或短横线（1–64 位）");
            return;
        }
        dlBtn.setEnabled(false);
        dlStatus.setText(localFile ? "正在读取本地文件…" : "正在下载…");
        final Handler h = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override public void run() {
                String msg = "导入失败：未知错误";
                final boolean[] ok = {false};
                try {
                    String json;
                    if (localFile) {
                        java.io.File lf = new java.io.File(fileArg);
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int n;
                        java.io.FileInputStream lfin = new java.io.FileInputStream(lf);
                        while ((n = lfin.read(buf)) > 0) bo.write(buf, 0, n);
                        lfin.close();
                        json = new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    } else {
                        String url = "https://xsd.qx47.com/app/download/"
                                + Uri.encode(code) + ".json";
                        byte[] data = httpGetJson(url);
                        json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    }
                    QuizDb.ImportReport rep = QuizDb.importDownload(
                            SettingsActivity.this,
                            localFile ? ("FILE_" + fileArg.hashCode()) : code, json);
                    if (rep.error != null) {
                        msg = "导入失败：" + rep.error;
                    } else {
                        ok[0] = true;
                        StringBuilder sb = new StringBuilder();
                        sb.append("成功导入 ").append(rep.inserted).append(" 题");
                        if (rep.skipped > 0) sb.append("，跳过 ").append(rep.skipped).append(" 题（字段不全）");
                        sb.append("。已生效。");
                        // 按导入内容智能提示：仅当含非语数英题时才需要调「其它」配比。
                        if (rep.otherInserted > 0 && rep.coreInserted == 0) {
                            sb.append("本次全部为「其它」学科，请到「学科配比 → 其它」调大配比后出题。");
                        } else if (rep.otherInserted > 0) {
                            sb.append("含 ").append(rep.otherInserted)
                              .append(" 道非语数英题，需到「学科配比 → 其它」调大配比才会出这些题；语数英题按当前配比即可出题。");
                        } else {
                            sb.append("均为语数英题，按当前出题范围与配比即可出题。");
                        }
                        msg = sb.toString();
                    }
                } catch (Exception e) {
                    msg = friendlyDownloadError(e);
                }
                final String fmsg = msg;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (ok[0]) {
                            // importDownload 已把 QuizDb.instance 置空：这里立刻重建，
                            // 让题库统计 / 知识点大类（含「其它」）在本页刷新；再通知悬浮窗
                            // 重建出题引擎，重新识别非三科题池，无需重启应用。
                            bank = QuizDb.open(SettingsActivity.this);
                            if (bank != null) {
                                // 导入改变了题库内容（年级 / 版本 / 其它学科），立刻把 scope
                                // 同步到持久化设置，与 save() 口径一致。否则不点「保存」直接关
                                // App、前台服务保活使进程未死、重开时 QuizDb 单例复用且构造器的
                                // syncLegacyKeys 不重跑，持久化年级等停留在导入前旧值，首页 / 家长
                                // 设置重建时可能空白（用户实测：不保存重开空白、保存则正常）。
                                applyEditionToLegacy(bank.scopeEdition());
                                st.grade(bank.scopeGrade());
                                st.termIndex(0);
                                st.unitLimit(0);
                                st.allSubjects(st.weight(Subjects.OTHER) > 0);
                                refreshRows();
                            }
                            sendReload();
                        }
                        dlStatus.setText(fmsg);
                        dlBtn.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    /**
     * 下载码专用 GET：超时、手动跟随同源重定向（防跨主机）、限 16MB。
     * 不复用 BankRepository.httpGet —— 该类不进本包重编译的 dex，运行时仍是 private。
     */
    private static byte[] httpGetJson(String url) throws java.io.IOException {
        final String host = new java.net.URL(url).getHost();
        for (int hop = 0; hop <= 3; hop++) {
            final java.net.URL _u = new java.net.URL(url);
            java.net.HttpURLConnection c;
            if ("https".equalsIgnoreCase(_u.getProtocol())) {
                javax.net.ssl.HttpsURLConnection _h =
                        (javax.net.ssl.HttpsURLConnection) _u.openConnection();
                // 老系统（Android 6.0）CA 库不含 Let's Encrypt ISRG X1 根，
                // 访问 xsd.qx47.com 下载题库会 SSLHandshakeException；
                // 仅对该域名回退到内置根证书验证（主机名仍严格校验）。
                if (_u.getHost().endsWith("qx47.com")) {
                    try { _h.setSSLSocketFactory(getLeFallbackSslSocketFactory()); }
                    catch (Exception ignored) { }
                }
                c = _h;
            } else {
                c = (java.net.HttpURLConnection) _u.openConnection();
            }
            java.io.InputStream in = null;
            try {
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                c.setRequestMethod("GET");
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("Accept", "application/json");
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303
                        || code == 307 || code == 308) {
                    if (hop == 3) throw new java.io.IOException("重定向次数超过 3 次");
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty())
                        throw new java.io.IOException("重定向缺少 Location");
                    java.net.URL next = new java.net.URL(new java.net.URL(url), loc);
                    if (!host.equals(next.getHost()))
                        throw new java.io.IOException("重定向跨主机地址被拒绝");
                    url = next.toString();
                    continue;
                }
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                in = c.getInputStream();
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 16 * 1024 * 1024)
                        throw new java.io.IOException("文件超过 16MB");
                    bo.write(buf, 0, n);
                }
                return bo.toByteArray();
            } finally {
                if (in != null) { try { in.close(); } catch (Exception ignored) { } }
                c.disconnect();
            }
        }
        throw new java.io.IOException("重定向次数超过 3 次");
    }

    /**
     * 把底层网络异常翻成家长能看懂、能自救的中文提示。
     * 老电视盒子/投影仪断电后 RTC 常回到出厂日期，会让证书被判「尚未生效/已过期」，
     * 这类情况内置根证书也救不了，必须引导用户去开自动校时。
     */
    private static String friendlyDownloadError(Exception e) {
        StringBuilder sb = new StringBuilder();
        Throwable t = e;
        int depth = 0;
        while (t != null && depth++ < 8) {
            sb.append(t.getClass().getName()).append(' ')
              .append(t.getMessage() == null ? "" : t.getMessage()).append(' ');
            t = t.getCause();
        }
        String s = sb.toString();
        if (s.contains("CertificateNotYetValid") || s.contains("not yet valid")
                || s.contains("CertificateExpired") || s.contains("has expired")
                || s.contains("expired on") || s.contains("certificate expired")) {
            return "下载失败：设备日期时间不正确，证书校验未通过。请到「系统设置 → 日期和时间」开启自动确定时间后重试。";
        }
        if (s.contains("CertPathValidatorException") || s.contains("Trust anchor")
                || s.contains("SSLHandshakeException") || s.contains("No peer certificate")) {
            return "下载失败：设备不信任服务器证书（老系统缺少根证书）。请确认网络正常后重试；若始终失败，请把此提示反馈给客服。";
        }
        if (s.contains("SSLProtocolException") || s.contains("protocol")
                || s.contains("SSL routines") || s.contains("handshake failure")) {
            return "下载失败：系统 TLS 版本过旧，无法建立安全连接。请升级设备系统后重试。";
        }
        if (s.contains("UnknownHostException") || s.contains("Unable to resolve host")) {
            return "下载失败：无法解析服务器地址，请检查网络连接（DNS）后重试。";
        }
        if (s.contains("SocketTimeoutException") || s.contains("timed out")
                || s.contains("ConnectException") || s.contains("ECONN")) {
            return "下载失败：连接服务器超时，请检查网络后重试。";
        }
        if (s.contains("FileNotFoundException") || s.contains("HTTP 404")) {
            return "下载失败：下载码不存在，请核对后重新输入。";
        }
        if (s.contains("HTTP 5")) {
            return "下载失败：服务器暂时不可用，请稍后重试。";
        }
        return "下载失败：" + e.getClass().getSimpleName()
                + (e.getMessage() == null ? "" : "：" + e.getMessage());
    }

    /**
     * Let's Encrypt 根证书 ISRG X1：Android 7.0+ 才内置进系统 CA 库，Android 6.0 缺失，
     * 导致访问 xsd.qx47.com 下载题库时出现 SSLHandshakeException(Trust anchor not found)。
     * 内置此根作为回退信任锚，仅对 qx47.com 域名在系统验证失败时生效，主机名仍严格校验。
     */
    private static final String ISRG_X1_PEM =
        "-----BEGIN CERTIFICATE-----\n" +
        "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n" +
        "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n" +
        "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n" +
        "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n" +
        "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n" +
        "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n" +
        "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+\n" +
        "0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U\n" +
        "A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW\n" +
        "T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH\n" +
        "B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC\n" +
        "B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv\n" +
        "KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn\n" +
        "OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn\n" +
        "jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw\n" +
        "qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI\n" +
        "rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV\n" +
        "HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq\n" +
        "hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL\n" +
        "ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ\n" +
        "3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK\n" +
        "NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5\n" +
        "ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur\n" +
        "TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC\n" +
        "jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc\n" +
        "oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq\n" +
        "4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA\n" +
        "mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d\n" +
        "emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=\n" +
        "-----END CERTIFICATE-----\n";

    /** 仅对 qx47.com 的 HTTPS 生效：系统 CA 不含 ISRG X1 时回退到内置根，
     *  解决 Android 6.0 等老系统下载题库的 SSLHandshakeException。带缓存。 */
    private static volatile javax.net.ssl.SSLSocketFactory sLeSslFactory;
    private static javax.net.ssl.SSLSocketFactory getLeFallbackSslSocketFactory() throws Exception {
        javax.net.ssl.SSLSocketFactory f = sLeSslFactory;
        if (f != null) return f;
        synchronized (SettingsActivity.class) {
            if (sLeSslFactory != null) return sLeSslFactory;
            // 系统默认 TrustManager（新系统正常通过）
            javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((java.security.KeyStore) null);
            final javax.net.ssl.X509TrustManager sys =
                    (javax.net.ssl.X509TrustManager) tmf.getTrustManagers()[0];
            // 内置 ISRG X1 根作为额外信任锚
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            java.security.KeyStore ks = java.security.KeyStore.getInstance(
                    java.security.KeyStore.getDefaultType());
            ks.load(null);
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(
                    ISRG_X1_PEM.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.security.cert.X509Certificate root = (java.security.cert.X509Certificate)
                    cf.generateCertificate(bais);
            bais.close();
            ks.setCertificateEntry("isrg_x1", root);
            javax.net.ssl.TrustManagerFactory tmf2 = javax.net.ssl.TrustManagerFactory.getInstance(
                    javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
            tmf2.init(ks);
            final javax.net.ssl.X509TrustManager builtin =
                    (javax.net.ssl.X509TrustManager) tmf2.getTrustManagers()[0];
            // 先试系统，失败再试内置根（仅当系统缺失 ISRG X1 时回退生效）
            javax.net.ssl.X509TrustManager merged = new javax.net.ssl.X509TrustManager() {
                public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                    return sys.getAcceptedIssuers();
                }
                public void checkClientTrusted(
                        java.security.cert.X509Certificate[] chain, String authType) { }
                public void checkServerTrusted(
                        java.security.cert.X509Certificate[] chain, String authType)
                        throws java.security.cert.CertificateException {
                    try {
                        sys.checkServerTrusted(chain, authType);
                    } catch (java.security.cert.CertificateException e) {
                        builtin.checkServerTrusted(chain, authType);
                    }
                }
            };
            // 与 DonateImages.compatFactory() 口径一致：优先固定 TLSv1.2（兼容
            // Android 5.x/6.x 上某些默认只启用 TLSv1.0 的魔改系统），取不到再退回通用 TLS。
            javax.net.ssl.SSLContext ctx;
            try {
                ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2");
            } catch (java.security.NoSuchAlgorithmException na) {
                ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            }
            ctx.init(null, new javax.net.ssl.TrustManager[]{merged}, null);
            sLeSslFactory = ctx.getSocketFactory();
        }
        return sLeSslFactory;
    }

    private View buildSettings() {
        LinearLayout root = V10Ui.vbox(this);
        root.setBackgroundColor(V10Ui.BG);
        root.setPadding(V10Ui.dp(this, 36), V10Ui.dp(this, 12),
                V10Ui.dp(this, 36), V10Ui.dp(this, 10));

        LinearLayout head = V10Ui.hbox(this);
        head.addView(V10Ui.tv(this, "家长设置", 26, V10Ui.TEXT, Gravity.LEFT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(V10Ui.tv(this, "修改即时生效", 12, V10Ui.MUTED, Gravity.RIGHT),
                V10Ui.matchWrap());
        root.addView(head, V10Ui.fixed(this, 40));

        pinState = V10Ui.tv(this, "", 12, V10Ui.MUTED, Gravity.LEFT);
        pinState.setPadding(V10Ui.dp(this, GUTTER_DP), 0, 0, 0);
        root.addView(pinState, V10Ui.fixed(this, 20));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(true);
        LinearLayout shell = V10Ui.vbox(this);
        shell.setPadding(V10Ui.dp(this, GUTTER_DP), V10Ui.dp(this, 4),
                V10Ui.dp(this, GUTTER_DP), V10Ui.dp(this, 16));
        LinearLayout col = V10Ui.vbox(this);
        shell.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sv.addView(shell, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 01 题库信息：分「已有题库」与「新增题库」两个小类 ----
        LinearLayout info = addCard(col, "题库信息");
        addGroup(info, "已有题库");
        bankSummaryLine = V10Ui.tv(this, "", 15, V10Ui.TEXT, Gravity.LEFT);
        info.addView(bankSummaryLine, rowLp());
        bankLine = V10Ui.tv(this, "", 12, V10Ui.MUTED, Gravity.LEFT);
        info.addView(bankLine, rowLp());
        addGroup(info, "新增题库");
        // v41：下载码入口并入本卡片。放在此处调用，track() 的遥控器上下焦点顺序
        // 才与页面从上到下的视觉顺序一致。
        addDownloadSection(info);

        // ---- 02 出题范围：可选值全部由 questions 表生成 ----
        LinearLayout scope = addCard(col, "出题范围");
        rowEdition = addRow(scope, "教材版本", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleEdition(); }
        });
        rowGrade = addRow(scope, "年级", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleGrade(); }
        });
        rowTerm = addRow(scope, "学期", new View.OnClickListener() {
            @Override public void onClick(View v) { cycleTerm(); }
        });
        note(scope, "可选值取自题库，库中不存在的分类不会出现。");

        // ---- 03 知识点范围：每科独立按大类筛选（v47 增加「其它」） ----
        LinearLayout groups = addCard(col, "知识点范围");
        for (int i = 0; i < CORE.length; i++) {
            final int idx = i;
            rowGroup[i] = addRow(groups, CORE_NAMES[i], new View.OnClickListener() {
                @Override public void onClick(View v) { cycleGroup(idx); }
            });
        }
        note(groups, "选「全部」则按学科配比抽题；选大类则只抽该大类。"
                + "「其它」对应下载导入的非语数英题目。");

        // ---- 04 学科配比（v47 增加「其它」，默认 0） ----
        LinearLayout subjects = addCard(col, "学科配比");
        for (int i = 0; i < CORE.length; i++) {
            final int idx = i;
            rowWt[i] = addRow(subjects, CORE_NAMES[i], new View.OnClickListener() {
                @Override public void onClick(View v) {
                    st.weight(CORE[idx], (st.weight(CORE[idx]) + 1)
                            % (SubjectWeights.MAX_WEIGHT + 1));
                    refreshRows();
                }
            });
        }
        note(subjects, "配比 0 = 该科不出题。「其它」默认 0；导入其它学科题库后调大即可出题。");

        // ---- 05 答题规则（v21：按方案把参数分成三组，默认严谨守护） ----
        LinearLayout quiz = addCard(col, "答题规则");
        note(quiz, "先在「答题节奏」选方案，下面两组参数只对各自方案生效。"
                + "两种方案都会在剩余 10 / 5 / 2 分钟各提醒一次，最后 1 分钟每秒倒数；"
                + "严谨守护按「出题频率」倒计时，自主可控按观看余额倒计时。");

        addGroup(quiz, "公共设置");
        rowMode = addRow(quiz, "答题节奏", new View.OnClickListener() {
            @Override public void onClick(View v) {
                QuizMode.cycleMode(SettingsActivity.this);
                refreshRows();
            }
        });
        // v31：中文注音开关。开启后出题界面的题干与四个选项在每个汉字上方标拼音，
        // 供低年级还不认字的小朋友独立读题；不影响判题、错题本与题库数据。
        rowPinyin = addRow(quiz, "中文注音", new View.OnClickListener() {
            @Override public void onClick(View v) {
                Pinyin.setOn(SettingsActivity.this, !Pinyin.on(SettingsActivity.this));
                refreshRows();
            }
        });
        note(quiz, "开启后题干和选项的每个汉字上方会标出带声调的拼音，适合低年级小朋友；关闭则只显示汉字。");
        rowLock = addRow(quiz, "答错停留", new View.OnClickListener() {
            @Override public void onClick(View v) {
                VoiceCoach.cycleLock(SettingsActivity.this);
                refreshRows();
            }
        });

        groupGuard = addGroup(quiz, "严谨守护");
        rowInterval = addRow(quiz, "出题频率", new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = (st.intervalIndex() + 1) % SettingsStore.INTERVALS.length;
                st.intervalSec(SettingsStore.INTERVALS[i]);
                refreshRows();
            }
        });
        rowPerRound = addRow(quiz, "每轮题数", new View.OnClickListener() {
            @Override public void onClick(View v) {
                int cur = st.quizPerRound();
                int next = ROUND_STEPS[0];
                for (int i = 0; i < ROUND_STEPS.length; i++) {
                    if (ROUND_STEPS[i] > cur) { next = ROUND_STEPS[i]; break; }
                }
                st.quizPerRound(next);
                refreshRows();
            }
        });
        note(quiz, "到点弹全屏题并暂停视频；答错 2 次换题并记入错题库。"
                + "控屏期间展示题库文字解析，倒计时结束后四个选项重新排列、再答本题，"
                + "避免记住选项位置乱选。答错停留时长见上方「公共设置」。");

        groupFree = addGroup(quiz, "自主可控");
        rowStartMin = addRow(quiz, "起始时长", new View.OnClickListener() {
            @Override public void onClick(View v) {
                QuizMode.cycleStartMinutes(SettingsActivity.this);
                refreshRows();
            }
        });
        rowFreeMin = addRow(quiz, "每题可得", new View.OnClickListener() {
            @Override public void onClick(View v) {
                QuizMode.cycleRewardMinutes(SettingsActivity.this);
                refreshRows();
            }
        });
        rowBalance = addRow(quiz, "剩余观看时间", new View.OnClickListener() {
            @Override public void onClick(View v) {
                QuizMode.resetBalance(SettingsActivity.this);
                Toast.makeText(SettingsActivity.this,
                        "已按「起始时长」重置观看时间", Toast.LENGTH_SHORT).show();
                refreshRows();
            }
        });
        note(quiz, "先给孩子一段观看时间，用完立即暂停视频并进入答题；"
                + "答对一题 +「每题可得」，可一次连答不限题数，攒够了按返回键去看电视。"
                + "控制台点「立即答题」可随时主动进去攒时间。"
                + "答错后弹出与严谨守护相同的思路卡：倒计时结束后四个选项重新排列、再答本题，"
                + "不换题、不扣时间；停留时长由「公共设置」里的「答错停留」控制。"
                + "点「剩余观看时间」可按起始时长重置。");

        // ---- 06 系统与安全（v25 合并权限/自启；v26 再合入家长密码，共三组） ----
        LinearLayout sys = addCard(col, "系统与安全");
        addGroup(sys, "权限与自启");
        rowOverlay = addRow(sys, "悬浮窗授权", new View.OnClickListener() {
            @Override public void onClick(View v) { openOverlaySettings(); }
        });
        rowBoot = addRow(sys, "开机自启", new View.OnClickListener() {
            @Override public void onClick(View v) { st.bootAutostart(!st.bootAutostart()); refreshRows(); }
        });
        rowBattery = addRow(sys, "电池优化", new View.OnClickListener() {
            @Override public void onClick(View v) { openBatterySettings(); }
        });
        rowUsage = addRow(sys, "使用情况访问（可选）", new View.OnClickListener() {
            @Override public void onClick(View v) { openUsageSettings(); }
        });
        rowUsage.setText("前往设置");
        rowAdmin = addRow(sys, "防卸载（可选）", new View.OnClickListener() {
            @Override public void onClick(View v) { openDeviceAdminSettings(); }
        });
        rowAdmin.setText("前往设置");
        addButton(sys, "打开开机自启设置", V10Ui.ACCENT_DEEP,
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        BootSettings.open(SettingsActivity.this);
                        Toast.makeText(SettingsActivity.this,
                                "请在厂商设置里允许「自启动」", Toast.LENGTH_SHORT).show();
                    }
                });
        note(sys, "以下权限应用均无法自动获取，需家长在系统设置里手动开启："
                + "① 悬浮窗授权（设置→应用→特殊权限→显示在其他应用上层）：用于全屏答题遮罩；"
                + "② 开机自启（设置→应用→自启动管理）：开机后自动拉起守护；"
                + "③ 电池优化（设置→应用→电池→不受限制）：请设为「不优化」，否则息屏后守护可能被系统回收；"
                + "④ 使用情况访问（可选，设置→应用→特殊权限→使用情况访问）：开启后可在桌面/系统设置界面不扣时，计时更精准；"
                + "⑤ 防卸载（可选，设置→安全→设备管理员）：将本应用设为设备管理员后，孩子需先解除管理员才能卸载。");

        addGroup(sys, "播放与守护");
        rowAutoGuard = addRow(sys, "自动守护", new View.OnClickListener() {
            @Override public void onClick(View v) { st.autoGuardOnBoot(!st.autoGuardOnBoot()); refreshRows(); }
        });
        rowPause = addRow(sys, "暂停视频", new View.OnClickListener() {
            @Override public void onClick(View v) { st.pauseOnQuiz(!st.pauseOnQuiz()); refreshRows(); }
        });
        rowResume = addRow(sys, "恢复播放", new View.OnClickListener() {
            @Override public void onClick(View v) { st.resumePlayback(!st.resumePlayback()); refreshRows(); }
        });

        addGroup(sys, "家长密码");
        addButton(sys, "修改家长密码", V10Ui.BLUE, new View.OnClickListener() {
            @Override public void onClick(View v) { enterChangePin(); }
        });
        note(sys, "首次使用在此设置密码；已设置时先输入旧密码，再输入两次新密码。"
                + "入口已从控制台移入本页，孩子不会在首页看到密码相关按钮。");

        // ---- 06b 扫码管理（v31）：二维码 + 局域网家长控制台 ----
        LinearLayout remote = addCard(col, "扫码管理");
        note(remote, "用手机相机/微信扫一扫下面的二维码，即可在手机浏览器里调整参数、"
                + "立即出题、锁屏或解锁；手机与电视需连接同一个 Wi-Fi。");
        addButton(remote, "打开扫码管理", V10Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { showManage(); }
        });
        note(remote, "二维码只在通过家长密码后显示，地址内含一次性令牌；离开本页后管理仍有效，退出应用即失效。");

        // ---- 07 错题与题库（v14：打赏移出，单独成框） ----
        LinearLayout data = addCard(col, "错题与题库");
        addButton(data, "查看错题库", V10Ui.ORANGE, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, WrongBookActivity.class));
            }
        });
        note(data, "题库内置离线，答题与解析都不需要联网。");

        // ---- 07b 题库下载导入已并入「题库信息」卡片（见上方 01） ----

        // ---- 08 打赏（独立卡片） ----
        LinearLayout donate = addCard(col, "打赏");
        addButton(donate, "打赏作者", V10Ui.AMBER, new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, DonateActivity.class));
            }
        });
        note(donate, "支持作者，感谢你的鼓励。");

        // ---- 保存：表单内普通按钮，不悬浮 ----
        LinearLayout saveCard = addCard(col, "");
        addButton(saveCard, "保存设置并返回", V10Ui.ACCENT, new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });

        root.addView(sv, V10Ui.fill(1));
        wireVerticalFocus();
        return root;
    }

    // ---------- v31 扫码管理：二维码 + 局域网控制台 ----------

    private View buildManage() {
        LinearLayout root = V10Ui.vbox(this);
        root.setBackgroundColor(V10Ui.BG);
        root.setPadding(V10Ui.dp(this, 40), V10Ui.dp(this, 16),
                V10Ui.dp(this, 40), V10Ui.dp(this, 12));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(V10Ui.tv(this, "扫码管理", 26, V10Ui.TEXT, Gravity.CENTER),
                V10Ui.fixed(this, 40));
        root.addView(V10Ui.tv(this, "手机扫一扫 · 直接管理这台电视", 14,
                V10Ui.MUTED, Gravity.CENTER), V10Ui.fixed(this, 24));

        LinearLayout body = V10Ui.hbox(this);
        body.setGravity(Gravity.CENTER_VERTICAL);

        int qrPx = V10Ui.dp(this, 300);
        LinearLayout left = V10Ui.vbox(this);
        left.setGravity(Gravity.CENTER);
        left.setBackground(V10Ui.outlined(V10Ui.SURFACE, 18, this, 1, V10Ui.STROKE));
        left.setPadding(V10Ui.dp(this, 18), V10Ui.dp(this, 18),
                V10Ui.dp(this, 18), V10Ui.dp(this, 18));
        manageQr = new ImageView(this);
        manageQr.setBackgroundColor(0xFFFFFFFF);
        manageQr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        left.addView(manageQr, new LinearLayout.LayoutParams(qrPx, qrPx));
        body.addView(left, new LinearLayout.LayoutParams(
                V10Ui.dp(this, 336), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout right = V10Ui.vbox(this);
        right.setPadding(V10Ui.dp(this, 34), 0, 0, 0);
        right.addView(V10Ui.tv(this, "管理地址", 14, V10Ui.ACCENT_LIGHT, Gravity.LEFT),
                V10Ui.matchWrap());
        manageUrl = V10Ui.tv(this, "", 18, V10Ui.TEXT, Gravity.LEFT);
        manageUrl.setPadding(0, V10Ui.dp(this, 6), 0, V10Ui.dp(this, 12));
        right.addView(manageUrl, V10Ui.matchWrap());
        manageInfo = V10Ui.tv(this, "", 13, V10Ui.MUTED, Gravity.LEFT);
        manageInfo.setLineSpacing(0, 1.35f);
        right.addView(manageInfo, V10Ui.matchWrap());
        manageBack = V10Ui.button(this, "返回家长设置", V10Ui.BLUE, V10Ui.TEXT, 16);
        V10Ui.focus(manageBack, this, V10Ui.BLUE, V10Ui.TEXT);
        manageBack.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                inManage = false;
                enterSettings();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                V10Ui.dp(this, 300), V10Ui.dp(this, 52));
        blp.topMargin = V10Ui.dp(this, 18);
        right.addView(manageBack, blp);
        body.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(body, V10Ui.fill(1));
        root.addView(V10Ui.tv(this,
                "二维码含一次性管理令牌，请勿转发；离开本页后手机仍可管理，退出应用即失效。",
                12, V10Ui.MUTED, Gravity.CENTER), V10Ui.fixed(this, 24));
        return root;
    }

    private void showManage() {
        if (manageView == null) manageView = buildManage();
        String url = ManageServer.start(this);
        if (url == null || url.length() == 0) {
            Toast.makeText(this, "管理端口被占用，请稍后重试", Toast.LENGTH_LONG).show();
            return;
        }
        manageQr.setImageBitmap(ManageServer.qrBitmap(url, V10Ui.dp(this, 300)));
        manageUrl.setText(url);
        manageInfo.setText("电视地址 " + ManageServer.ip() + ":" + ManageServer.port()
                + " · 令牌每次启动随机生成\n\n"
                + "手机与电视需在同一 Wi-Fi。扫码后可：调整三项默认参数与年级/学期、"
                + "开关中文注音、切换答题方案、立即出题、锁屏 / 解除锁屏、"
                + "暂停或恢复播放、重置观看时间。");
        inManage = true;
        setContentView(manageView);
        if (manageBack != null) manageBack.requestFocus();
    }

    // ---------- 数据驱动的取值切换 ----------

    private void cycleEdition() {
        String[] eds = bank.editions();
        if (eds.length <= 1) {
            Toast.makeText(this, "题库仅含 " + (eds.length == 1 ? eds[0] : "0") + " 个版本",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String cur = bank.scopeEdition();
        int i = 0;
        for (int k = 0; k < eds.length; k++) if (eds[k].equals(cur)) { i = k; break; }
        String next = eds[(i + 1) % eds.length];
        sp.edit().putString(QuizDb.K_EDITION, next).commit();
        applyEditionToLegacy(next);
        refreshRows();
    }

    private void cycleGrade() {
        int[] gs = bank.grades();
        if (gs.length <= 1) {
            Toast.makeText(this, "题库仅含 1 个年级", Toast.LENGTH_SHORT).show();
            return;
        }
        int cur = bank.scopeGrade();
        int i = 0;
        for (int k = 0; k < gs.length; k++) if (gs[k] == cur) { i = k; break; }
        int next = gs[(i + 1) % gs.length];
        sp.edit().putInt(QuizDb.K_GRADE, next).commit();
        st.grade(next);
        refreshRows();
    }

    private void cycleTerm() {
        String[] ts = bank.terms();
        if (ts.length == 0) {
            Toast.makeText(this, "本题库不分册", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = new String[ts.length + 1];
        opts[0] = QuizDb.TERM_ALL;
        System.arraycopy(ts, 0, opts, 1, ts.length);
        String cur = sp.getString(QuizDb.K_TERM, "");
        int i = 0;
        for (int k = 0; k < opts.length; k++) if (opts[k].equals(cur)) { i = k; break; }
        sp.edit().putString(QuizDb.K_TERM, opts[(i + 1) % opts.length]).commit();
        refreshRows();
    }

    private void cycleGroup(int idx) {
        String subj = CORE_NAMES[idx];
        String[] gs = bank.groupsFor(subj);
        if (gs.length == 0) {
            Toast.makeText(this, "该学科暂无知识点大类", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = new String[gs.length + 1];
        opts[0] = "";                       // 空串 = 全部
        System.arraycopy(gs, 0, opts, 1, gs.length);
        String key = QuizDb.K_GROUP_PREFIX + groupKey(idx);
        String cur = sp.getString(key, "");
        int i = 0;
        for (int k = 0; k < opts.length; k++) if (opts[k].equals(cur)) { i = k; break; }
        sp.edit().putString(key, opts[(i + 1) % opts.length]).commit();
        refreshRows();
    }

    /** 与 QuizDb.subjectKey() 的键名一致；idx=3「其它」→ other。 */
    private static String groupKey(int idx) {
        if (idx == 0) return "math";
        if (idx == 1) return "chinese";
        if (idx == 2) return "english";
        return "other";
    }

    /** 同步旧 edition 键，让答题页教材版本标签显示库里真实版本。 */
    private void applyEditionToLegacy(String edition) {
        int idx = 1;                        // 人教版
        for (int i = 0; i < SettingsStore.EDITIONS.length; i++) {
            if (SettingsStore.EDITIONS[i].equals(edition)) { idx = i; break; }
        }
        for (int s = 0; s < Subjects.COUNT; s++) st.setEditionIndexFor(s, idx);
    }

    private String gradeLabelFor(int g) {
        int[] gs = bank.grades();
        String[] ls = bank.gradeLabels();
        for (int i = 0; i < gs.length; i++) if (gs[i] == g) return ls[i];
        return g + "年级";
    }

    private String groupDisplay(int idx) {
        String key = QuizDb.K_GROUP_PREFIX + groupKey(idx);
        String v = sp.getString(key, "");
        if (v.isEmpty() || bank.groupsFor(CORE_NAMES[idx]).length == 0) return "全部";
        return v;
    }

    private String intervalLabel() {
        int sec = st.intervalSec();
        for (int i = 0; i < SettingsStore.INTERVALS.length; i++) {
            if (SettingsStore.INTERVALS[i] == sec) return SettingsStore.INTERVAL_LABELS[i];
        }
        return sec + " 秒";
    }

    private void wireVerticalFocus() {
        for (int i = 0; i < focusRows.size(); i++) {
            View[] row = focusRows.get(i);
            View up = i > 0 ? firstUsable(focusRows.get(i - 1)) : null;
            View down = i < focusRows.size() - 1 ? firstUsable(focusRows.get(i + 1)) : null;
            for (View v : row) {
                if (v == null || !v.isEnabled() || !v.isFocusable()) continue;
                if (up != null) v.setNextFocusUpId(V10Ui.ensureId(up));
                if (down != null) v.setNextFocusDownId(V10Ui.ensureId(down));
                int self = V10Ui.ensureId(v);
                if (v.getNextFocusLeftId() == View.NO_ID) v.setNextFocusLeftId(self);
                if (v.getNextFocusRightId() == View.NO_ID) v.setNextFocusRightId(self);
            }
        }
    }

    private static View firstUsable(View[] row) {
        for (View v : row) {
            if (v != null && v.isEnabled() && v.isFocusable()) return v;
        }
        return null;
    }

    /** 反射读取悬浮窗权限；老平台无 canDrawOverlays 时视为已授权。 */
    private boolean canDraw() {
        try {
            Class<?> c = Class.forName("android.provider.Settings");
            java.lang.reflect.Method m = c.getMethod("canDrawOverlays",
                    android.content.Context.class);
            return ((Boolean) m.invoke(null, this)).booleanValue();
        } catch (Throwable t) {
            return true;
        }
    }

    /** v25：由控制台移入家长设置的「悬浮窗授权」入口。 */
    private void openOverlaySettings() {
        Intent i = new Intent("android.settings.action.MANAGE_OVERLAY_PERMISSION",
                Uri.parse("package:" + getPackageName()));
        try { startActivity(i); }
        catch (Exception e) {
            Toast.makeText(this, "该设备没有悬浮窗权限入口", Toast.LENGTH_LONG).show();
        }
    }

    /** v52：电池优化白名单引导。Android 6+ 需把本应用加入「不优化」，
     *  否则息屏 Doze / 厂商省电会杀掉后台守护。低于 6.0 无需设置。 */
    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            Intent i = new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(i);
                return;
            } catch (Exception ignored) {
                // 该 ROM 没有这个直跳入口，降级到应用详情页
            }
            try {
                startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                Toast.makeText(this, "未找到电池设置入口，请在系统设置搜索「电池优化」",
                        Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "该 Android 版本无需设置电池优化", Toast.LENGTH_SHORT).show();
        }
    }

    /** 使用情况访问（可选）：用于识别前台应用、桌面/系统设置界面不扣时、计时更精准。
     *  API>=21 跳 USAGE_ACCESS_SETTINGS，失败降级应用详情页，再失败提示手动搜索。 */
    private void openUsageSettings() {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                startActivity(new Intent("android.settings.USAGE_ACCESS_SETTINGS"));
                return;
            } catch (Exception ignored) {
                // 该 ROM 没有这个直跳入口，降级到应用详情页
            }
            try {
                startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) {
                Toast.makeText(this, "未找到使用情况访问入口，请在系统设置搜索「使用情况访问」",
                        Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "该 Android 版本不支持使用情况访问", Toast.LENGTH_SHORT).show();
        }
    }

    /** 防卸载（可选）：将本应用设为设备管理员后，孩子需先解除管理员才能卸载。
     *  跳 ACTION_DEVICE_ADMIN_SETTINGS，失败降级安全设置 / 应用详情页，再失败提示手动搜索。 */
    private void openDeviceAdminSettings() {
        try {
            startActivity(new Intent("android.settings.ACTION_DEVICE_ADMIN_SETTINGS"));
            return;
        } catch (Exception ignored) {
            // 该 ROM 没有这个直跳入口，降级到安全设置
        }
        try {
            startActivity(new Intent("android.settings.SECURITY_SETTINGS"));
            return;
        } catch (Exception ignored) {
            // 再降级到应用详情页
        }
        try {
            startActivity(new Intent("android.settings.APPLICATION_DETAILS_SETTINGS",
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {
            Toast.makeText(this, "未找到设备管理员入口，请在系统设置搜索「设备管理员」",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 当前是否被电池优化（即未加白名单）。低版本视为已豁免。
     *  编译 stub 是精简版，getSystemService / PowerManager 方法均缺失，
     *  故全部走反射，避免编译期报方法/字段不存在。 */
    private boolean batteryOptimized() {
        if (Build.VERSION.SDK_INT < 23) return false;
        try {
            java.lang.reflect.Method gm = Context.class.getMethod(
                    "getSystemService", String.class);
            Object pm = gm.invoke(this, "power");   // Context.POWER_SERVICE == "power"
            if (pm == null) return false;
            java.lang.reflect.Method m = pm.getClass().getMethod(
                    "isIgnoringBatteryOptimizations", String.class);
            Boolean b = (Boolean) m.invoke(pm, getPackageName());
            return b == null || !b.booleanValue();
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshRows() {
        bankSummaryLine.setText(bank.bankSummary());
        bankLine.setText("版本 " + st.bankVersion() + " · " + st.bankQuestions()
                + " 题 · 离线");

        rowEdition.setText(bank.scopeEdition());
        rowGrade.setText(gradeLabelFor(bank.scopeGrade()));
        String[] ts = bank.terms();
        if (ts.length == 0) {
            // 保持可聚焦：禁用会让 wireVerticalFocus 的上下焦点链断开
            rowTerm.setText("不分册（库内未标学期）");
        } else {
            String t = sp.getString(QuizDb.K_TERM, "");
            rowTerm.setText(t.isEmpty() ? QuizDb.TERM_ALL : t);
        }

        for (int i = 0; i < CORE.length; i++) {
            rowGroup[i].setText(groupDisplay(i));
            rowWt[i].setText("配比 " + st.weight(CORE[i]));
        }
        boolean free = QuizMode.mode(this) == QuizMode.MODE_FREE;
        // 「当前生效」是唯一被选中的方案，用绿色；未启用用灰色。
        groupGuard.setText(free ? "严谨守护 · 未启用" : "严谨守护 · 当前生效");
        groupGuard.setTextColor(free ? V10Ui.MUTED : V10Ui.SELECT_LIGHT);
        groupFree.setText(free ? "自主可控 · 当前生效" : "自主可控 · 未启用");
        groupFree.setTextColor(free ? V10Ui.SELECT_LIGHT : V10Ui.MUTED);

        rowMode.setText(QuizMode.modeLabel(this));
        rowPinyin.setText(Pinyin.label(this));
        rowFreeMin.setText(QuizMode.rewardLabel(this));
        rowStartMin.setText(QuizMode.startLabel(this));
        rowBalance.setText(QuizMode.balanceLabel(this));
        rowInterval.setText(intervalLabel());
        rowPerRound.setText(st.quizPerRound() + " 题 / 轮");
        rowLock.setText(VoiceCoach.lockLabel(this));
        rowOverlay.setText(canDraw() ? "已授权 · 点此管理" : "未授权 · 点此开启");
        rowBoot.setText(st.bootAutostart() ? "开启" : "关闭");
        rowBattery.setText(batteryOptimized() ? "被优化 · 点此加白" : "已加白 · 点此管理");
        rowAutoGuard.setText(st.autoGuardOnBoot() ? "开启" : "关闭");
        rowPause.setText(st.pauseOnQuiz() ? "开启" : "关闭");
        rowResume.setText(st.resumePlayback() ? "开启" : "关闭");
        pinState.setText(st.hasPin()
                ? "家长密码：已设置 · 修改请到下方「系统与安全 → 家长密码」"
                : "家长密码：未设置 · 首次使用请在下方设置");
    }

    private void sendReload() {
        if (!QuizOverlayService.isRunning()) return;
        Intent i = new Intent(this, QuizOverlayService.class);
        i.setAction(QuizOverlayService.ACTION_RELOAD);
        try { startService(i); } catch (Exception ignored) { }
    }

    private void save() {
        // 让旧版题面标签（年级 / 教材版本）与库内真实数据一致
        applyEditionToLegacy(bank.scopeEdition());
        st.grade(bank.scopeGrade());
        st.termIndex(0);
        st.unitLimit(0);
        // v47：「其它」配比 >0 时打开全学科开关。历史 smali 的 SubjectWeights 要求
        // allSubjects && otherAvailable 才让第 4 槽参与轮盘赌；这个开关没有 UI，
        // 且 QuizDb.syncLegacyKeys() 每次冷启动也会按 w3 重新写它，两处口径保持一致。
        st.allSubjects(st.weight(Subjects.OTHER) > 0);
        sendReload();
        // v27：自主可控 + 观看余额已归零（起始时长设 0 后重置余额、或切换到
        // 起始时长为 0 的自主可控）时，退出设置后立即进入答题攒时间。
        QuizMode.onSettingsSaved(this);
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}

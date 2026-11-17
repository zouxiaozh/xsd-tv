# -*- coding: utf-8 -*-
"""重建 XsdAnser v30：品牌重命名 + 版本升级（app 名 学时兑 / 代号 XsdAnser / 版本 v2.02）。由 base_dec_v26 重建完成，资源与 dex 已更名。

前置：patch_v26_wrongbook.py 生成的 toolchain/merge/base_v30.dex
（基于 base_dec_v22 全量 smali，只改 WrongBookActivity 一行标题字符串）。
v5~v14 历史补丁、v22 全局配色补丁、13 处挂接全部保留。本版 Java 侧
只重编译 8 个类，错题库页仍是历史 smali 类，不参与重编译（避免重复类定义）。
v30 的更名通过重建 base_dec_v26（资源+smali+apktool.yml）完成，功能与 v29 保持一致。

v29 需求（控制台首页）：
  1) 方案提示词「到点自动出题 · 暂停视频 · 答对才能继续」从 14sp 小灰字
     放大为 19sp 白色居中大字（v28 的 14sp MUTED 用户反馈太小）。
  2) 删除底部统计行「错题 X 题 · 答对 X 题 · 规则与密码在家长设置中修改」
     及其上方细线（错题数仍显示在「错题库」按钮上）。
  3) 删除「当前状态」「快捷操作」两个分节小标题。
  4) 界面更显眼：指标卡 128→150dp，按钮排 60→66dp、字号 15→16sp。

管线：全新带时间戳目录，不删除任何已有文件；只允许 classes.dex +
AndroidManifest.xml 变化；正常用 xsdanser 同证书签名可覆盖安装。设 XSD_NEW_CERT=1 时改用全新证书（旧包需重装）。

验证包（仅临时源码副本注入，正式包不含）：
  ZYX_VERIFY=1                          跳过门禁直接进家长设置（截图分类）
  ZYX_VERIFY=1 ZYX_VERIFY_OPEN_APP=1     余额 0 + 无 quiz_pending 键，验证打开 App 自动弹
  ZYX_VERIFY=1 ZYX_VERIFY_ZERO_BALANCE=1 起始 0 + 重置余额 + 退出设置，回归验证
  ZYX_VERIFY=1 ZYX_VERIFY_RESTORE=1     验证后把设备偏好恢复为正式状态
  ZYX_VERIFY=1 ZYX_VERIFY_BALPROBE=1    v70 余额 / 倒计时探针：-e balprobe set / gset / log / restore
                                         （set / gset 可加 --el balms <ms> 指定注入时长），
                                         set = 注入自主可控余额；gset = 注入严谨守护
                                         「距下次出题」的到期时间（quiz_mode=0）；
                                         log / restore 只读或恢复。START / END / CHECK /
                                         SILENT / RESUME 会打印 balance、guard（真实剩余
                                         秒）、svc（smali remainSec）、pending、showing。
"""
import glob
import io
import os
import shutil
import struct
import subprocess
import sys
import time
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
T = os.path.join(BASE, "toolchain")
JAVA = os.path.join(BASE, "sig-tools", "jdk-17.0.20.1+1-jre", "bin", "java.exe")
ECJ = os.path.join(T, "ecj.jar")
STUB = os.path.join(T, "android-stub.jar")
D8 = os.path.join(BASE, "sig-tools", "android-14", "lib", "d8.jar")
ZIPALIGN = os.path.join(BASE, "sig-tools", "android-14", "zipalign.exe")
APKSIGNER = os.path.join(BASE, "sig-tools", "android-14", "lib", "apksigner.jar")
KS = os.path.join(BASE, "xsdanser-custom.keystore")

VERIFY = os.environ.get("ZYX_VERIFY") == "1"
V_MODE = os.environ.get("ZYX_VERIFY_MODE", "1")
V_BALANCE = os.environ.get("ZYX_VERIFY_BALANCE_MS", "20000")
V_QUIZNOW = os.environ.get("ZYX_VERIFY_QUIZ_NOW") == "1"
V_LOCK = os.environ.get("ZYX_VERIFY_LOCK_SEC", "10")
V_REMIND = os.environ.get("ZYX_VERIFY_REMIND") == "1"
V_REMIND_FREE = os.environ.get("ZYX_VERIFY_REMIND_FREE") == "1"
V_RESTORE = os.environ.get("ZYX_VERIFY_RESTORE") == "1"
V_FIRST_PIN = os.environ.get("ZYX_VERIFY_FIRST_PIN") == "1"
V_CHANGE_PIN = os.environ.get("ZYX_VERIFY_CHANGE_PIN") == "1"
V_WRONGBOOK = os.environ.get("ZYX_VERIFY_WRONGBOOK") == "1"
V_ZERO_BALANCE = os.environ.get("ZYX_VERIFY_ZERO_BALANCE") == "1"
V_OPEN_APP = os.environ.get("ZYX_VERIFY_OPEN_APP") == "1"
# v55 方案 A 验证开关：强制 QuizHost.isUiForeground() 返回 false，使服务无条件走
# 「提承载页」路径，便于在盒子上单独验证 HWC 压遮罩的修复（即使本 App 在前台也劫持）。
V_FORCE_HOST = os.environ.get("ZYX_VERIFY_FORCE_HOST") == "1"
# v68 余额探针：只在验证包里注入 logcat 读写，正式包零注入。
V_BALPROBE = os.environ.get("ZYX_VERIFY_BALPROBE") == "1"
V_BALPROBE_MS = os.environ.get("ZYX_VERIFY_BALPROBE_MS", "137000")
# v71 门禁计时探针：把「输入密码 → 点确定 → 进入设置」拆成
# PBKDF2 校验 / 题库初始化 / 设置页构建 / setContentView / refreshRows 分别打点。
V_PINTIME = os.environ.get("ZYX_VERIFY_PINTIME") == "1"
# 用 | 分隔多轮输入：无 PIN 时用 "1234|1234" 走首次设置，已有 PIN 用 "1234"。
V_AUTOPIN = os.environ.get("ZYX_VERIFY_AUTOPIN", "1234|1234")
V_AUTODELAY = os.environ.get("ZYX_VERIFY_AUTODELAY", "4000")
if (V_REMIND or V_REMIND_FREE) and VERIFY:
    V_MODE = "0" if V_REMIND else "1"
if (V_ZERO_BALANCE or V_OPEN_APP) and VERIFY:
    V_MODE, V_BALANCE = "1", "0"
assert V_MODE in ("0", "1") and V_BALANCE.isdigit() and V_LOCK.isdigit(), "验证参数非法"
assert V_BALPROBE_MS.isdigit(), "ZYX_VERIFY_BALPROBE_MS 必须是毫秒整数"
RUN = os.path.join(BASE, "_build_v30_ui", "run_" + time.strftime("%Y%m%d_%H%M%S"))
if VERIFY:
    SRC = os.path.join(RUN, "src_verify")
    shutil.copytree(os.path.join(BASE, "src-v10"), SRC,
                    ignore=shutil.ignore_patterns("*.bak"))
    # 只在验证副本里给提醒打日志：Toast 不在 uiautomator 的界面树里，
    # 用 logcat 逐条核对 10 / 5 / 2 分钟与逐秒倒数的实际文案。正式包无日志。
    pq = os.path.join(SRC, "com", "xsdanser", "tv", "QuizMode.java")
    qs = io.open(pq, encoding="utf-8").read()
    assert "            ReminderBanner.show(app, text);" in qs, "提醒显示调用点变了"
    if V_ZERO_BALANCE or V_OPEN_APP:
        # 只在验证副本里给 v28 待弹题链路打时序日志：确认触发来自
        # onHomeResumed / onSettingsSaved，不是服务启动时抢先弹题。
        assert "    public static void onHomeResumed(final Context c) {" in qs
        qs = qs.replace(
            "    public static void onHomeResumed(final Context c) {",
            "    public static void onHomeResumed(final Context c) {\n"
            "        android.util.Log.d(\"XsdAnser.V28\", \"onHomeResumed\");", 1)
        assert "    private static void consumePending(Context c, boolean force) {" in qs
        qs = qs.replace(
            "    private static void consumePending(Context c, boolean force) {",
            "    private static void consumePending(Context c, boolean force) {\n"
            "        android.util.Log.d(\"XsdAnser.V28\", \"consume enter force=\" + force"
            " + \" mode=\" + sMode + \" balance=\" + sBalanceMs"
            " + \" pending=\" + sPendingQuiz + \" settings=\" + sSettingsForeground);", 1)
        assert "        sForceNext = true;\n        try {" in qs
        qs = qs.replace(
            "        sForceNext = true;\n        try {",
            "        sForceNext = true;\n"
            "        android.util.Log.d(\"XsdAnser.V28\", \"FIRE QUIZ_NOW, balance=\""
            " + sBalanceMs + \" pending=\" + sPendingQuiz);\n"
            "        try {", 1)
    qs = qs.replace(
        "    private static void remind(long sec) {",
        "    private static void remind(long sec) {\n"
        "        android.util.Log.d(\"XsdAnser.RemindState\", \"sec=\" + sec"
        " + \" stage=\" + stageOf(sec) + \" prev=\" + sRemindStage);", 1)
    io.open(pq, "w", encoding="utf-8").write(qs.replace(
        "            ReminderBanner.show(app, text);",
        "            android.util.Log.d(\"XsdAnser.Remind\", text);\n"
        "            ReminderBanner.show(app, text);", 1))
    p = os.path.join(SRC, "com", "xsdanser", "tv", "SettingsActivity.java")
    s = io.open(p, encoding="utf-8").read()
    assert "enterGate(false);" in s, "临时副本里找不到门禁调用"
    if V_FIRST_PIN:
        # 用假 Store 让门禁强制进入「首次使用 · 请设置密码」，setPin 不落盘，
        # 验证完不会改变设备真实 PIN；这样可截到首次设置与再次确认两个界面。
        assert "gate = new PinGate(mPinStore);" in s
        s = s.replace(
            "gate = new PinGate(mPinStore);",
            "gate = new PinGate(new PinGate.Store() {\n"
            "            public boolean hasPin() { return false; }\n"
            "            public boolean checkPin(String p) { return true; }\n"
            "            public boolean setPin(String p) { return true; }\n"
            "        });", 1)
        # 底部状态行也统一成「首次使用」，避免真 PIN 存在时截图自相矛盾
        tag = ': (st.hasPin() ? "已设置家长密码" : "首次使用，请设置密码"));'
        assert tag in s, "门禁状态行锚点变了"
        s = s.replace(tag, ': "首次使用，请设置密码");', 1)
        # 保留 enterGate(false)，不跳过门禁
    else:
        # v28 打开 App 场景不进设置页，保留原门禁调用不动。
        replacement = "enterGate(false);" if V_OPEN_APP else "enterSettings();"
        if V_CHANGE_PIN:
            # 跳到设置页后自动打开「修改家长密码」浮层（复用门禁键盘）。
            replacement += " enterChangePin();"
        if V_ZERO_BALANCE:
            # v27：完整复现用户路径 —— 起始时长 0 + 点「剩余观看时间」重置
            # （余额归零）+ 退出设置自动保存。应由 save() -> onSettingsSaved()
            # 补发 ACTION_QUIZ_NOW，退出后自动弹出答题界面。
            replacement += (" getWindow().getDecorView().postDelayed(new Runnable() {"
                            " @Override public void run() {"
                            " android.util.Log.d(\"XsdAnser.V28\", \"reset + save now\");"
                            " QuizMode.resetBalance(SettingsActivity.this); save(); } }, 2200);")
        s = s.replace("enterGate(false);", replacement, 1)
    io.open(p, "w", encoding="utf-8").write(s)
    if V_PINTIME:
        # v71 诊断探针：把「输密码 → 点确定 → 进设置页」拆段计时，正式包零注入。
        s = io.open(p, encoding="utf-8").read()
        old_cp = "            boolean ok = st.checkPin(pin);"
        assert old_cp in s, "PINTIME: checkPin 锚点变了"
        s = s.replace(old_cp,
            "            long c0 = android.os.SystemClock.elapsedRealtime();\n"
            "            boolean ok = st.checkPin(pin);\n"
            "            android.util.Log.d(\"XsdAnser.PinTime\", \"checkPin=\"\n"
            "                + (android.os.SystemClock.elapsedRealtime() - c0) + \"ms ok=\" + ok\n"
            "                + \" iter=\" + sp.getInt(\"pinIter\", 0));", 1)
        old_dk = "            byte[] dk = deriveFast(pin, salt, FAST_PIN_ITER);"
        assert old_dk in s, "PINTIME: deriveFast 锚点变了"
        s = s.replace(old_dk,
            "            long d0 = android.os.SystemClock.elapsedRealtime();\n"
            "            byte[] dk = deriveFast(pin, salt, FAST_PIN_ITER);\n"
            "            android.util.Log.d(\"XsdAnser.PinTime\", \"deriveFast=\"\n"
            "                + (android.os.SystemClock.elapsedRealtime() - d0) + \"ms\");", 1)
        old_gk = "                g.key(\"确定\");"
        assert old_gk in s, "PINTIME: gate.key 锚点变了"
        s = s.replace(old_gk,
            "                long k0 = android.os.SystemClock.elapsedRealtime();\n"
            "                g.key(\"确定\");\n"
            "                android.util.Log.d(\"XsdAnser.PinTime\", \"gate.key(BG)=\"\n"
            "                    + (android.os.SystemClock.elapsedRealtime() - k0) + \"ms\");", 1)
        old_rg = ("                        mVerifying = false;\n"
                  "                        if (activeGate() != g) return;   // 期间切过门禁，丢弃过期结果\n"
                  "                        refreshGate();")
        assert old_rg in s, "PINTIME: refreshGate 锚点变了"
        s = s.replace(old_rg,
            "                        mVerifying = false;\n"
            "                        if (activeGate() != g) return;\n"
            "                        long r0 = android.os.SystemClock.elapsedRealtime();\n"
            "                        refreshGate();\n"
            "                        android.util.Log.d(\"XsdAnser.PinTime\", \"refreshGate+enter=\"\n"
            "                            + (android.os.SystemClock.elapsedRealtime() - r0) + \"ms\");", 1)
        old_es = ("        setContentView(settingsView);\n"
                  "        refreshRows();\n"
                  "        if (firstSettingsBtn != null) firstSettingsBtn.requestFocus();\n"
                  "    }")
        assert old_es in s, "PINTIME: enterSettings 锚点变了"
        s = s.replace(old_es,
            "        long e0 = android.os.SystemClock.elapsedRealtime();\n"
            "        setContentView(settingsView);\n"
            "        android.util.Log.d(\"XsdAnser.PinTime\", \"  setContentView=\" + (android.os.SystemClock.elapsedRealtime() - e0) + \"ms\");\n"
            "        long f0 = android.os.SystemClock.elapsedRealtime();\n"
            "        refreshRows();\n"
            "        android.util.Log.d(\"XsdAnser.PinTime\", \"  refreshRows=\" + (android.os.SystemClock.elapsedRealtime() - f0) + \"ms\");\n"
            "        if (firstSettingsBtn != null) firstSettingsBtn.requestFocus();\n"
            "        android.util.Log.d(\"XsdAnser.PinTime\", \"enterSettings DONE\");\n"
            "    }", 1)
        old_bg = ("                    BankRepository.installBuiltin(SettingsActivity.this, st);\n"
                  "                    bank = QuizDb.open(SettingsActivity.this);")
        assert old_bg in s, "PINTIME: 后台线程锚点变了"
        s = s.replace(old_bg,
            "                    android.util.Log.d(\"XsdAnser.PinTime\", \"BG start\");\n"
            "                    long g0 = android.os.SystemClock.elapsedRealtime();\n"
            "                    BankRepository.installBuiltin(SettingsActivity.this, st);\n"
            "                    android.util.Log.d(\"XsdAnser.PinTime\", \"BG installBuiltin=\" + (android.os.SystemClock.elapsedRealtime() - g0) + \"ms\");\n"
            "                    long g1 = android.os.SystemClock.elapsedRealtime();\n"
            "                    bank = QuizDb.open(SettingsActivity.this);\n"
            "                    android.util.Log.d(\"XsdAnser.PinTime\", \"BG QuizDb.open=\" + (android.os.SystemClock.elapsedRealtime() - g1) + \"ms\");", 1)
        old_b2 = "                            if (settingsView == null) settingsView = buildSettings();"
        assert old_b2 in s, "PINTIME: buildSettings 锚点变了"
        s = s.replace(old_b2,
            "                            if (settingsView == null) {\n"
            "                                long g2 = android.os.SystemClock.elapsedRealtime();\n"
            "                                settingsView = buildSettings();\n"
            "                                android.util.Log.d(\"XsdAnser.PinTime\", \"UI buildSettings=\" + (android.os.SystemClock.elapsedRealtime() - g2) + \"ms\");\n"
            "                            }", 1)
        old_gate = "        enterSettings();   // 先立刻显示门禁，避免 QuizDb.open 阻塞 UI 线程"
        assert old_gate in s, "PINTIME: enterGate 锚点变了（验证包改写后）"
        s = s.replace(old_gate,
            "        enterGate(false);\n"
            "        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {\n"
            "            @Override public void run() {\n"
            "                android.util.Log.d(\"XsdAnser.PinTime\", \"AUTO start\");\n"
            "                final String[] rounds = \"" + V_AUTOPIN + "\".split(\"[|]\");\n"
            "                final int[] idx = {0};\n"
            "                final Runnable[] step = new Runnable[1];\n"
            "                step[0] = new Runnable() {\n"
            "                    @Override public void run() {\n"
            "                        if (idx[0] >= rounds.length) return;\n"
            "                        String r = rounds[idx[0]++];\n"
            "                        for (int i = 0; i < r.length(); i++) onKey(String.valueOf(r.charAt(i)));\n"
            "                        onKey(\"确定\");\n"
            "                        if (idx[0] < rounds.length) {\n"
            "                            new Handler(Looper.getMainLooper()).postDelayed(step[0], 900L);\n"
            "                        }\n"
            "                    }\n"
            "                };\n"
            "                step[0].run();\n"
            "            }\n"
            "        }, " + V_AUTODELAY + "L);", 1)
        io.open(p, "w", encoding="utf-8").write(s)
        print("[OK] PINTIME 探针已注入验证副本：autopin=%s delay=%sms"
              % (V_AUTOPIN, V_AUTODELAY))
    pm = os.path.join(SRC, "com", "xsdanser", "tv", "MainActivity.java")
    ms = io.open(pm, encoding="utf-8").read()
    assert "setContentView(buildView());" in ms, "临时副本里找不到 MainActivity 布局调用"
    jump_target = "WrongBookActivity.class" if V_WRONGBOOK else "SettingsActivity.class"
    # v51 巡检：验证包支持用 -e respage <name> 直达任意页面（子页面非 exported，
    # 无法从 shell 直接 am start，只能由 App 内部跳转）。正式包不含这段。
    jump_line = ("" if V_OPEN_APP else
                 "                    String pg = getIntent().getStringExtra(\"respage\");\n"
                 "                    if (pg == null) pg = \"" +
                 ("wrongbook" if V_WRONGBOOK else "settings") + "\";\n"
                 "                    try {\n"
                 "                        if (\"home\".equals(pg)) { }\n"
                 "                        else if (\"wrongbook\".equals(pg)) startActivity(new Intent(MainActivity.this, Class.forName(\"com.xsdanser.tv.WrongBookActivity\")));\n"
                 "                        else if (\"donate\".equals(pg)) startActivity(new Intent(MainActivity.this, Class.forName(\"com.xsdanser.tv.DonateActivity\")));\n"
                 "                        else if (\"quiz\".equals(pg)) QuizMode.requestManualQuiz(MainActivity.this);\n"
                 "                        else startActivity(new Intent(MainActivity.this, " + jump_target + "));\n"
                 "                    } catch (Throwable ignored) { }\n")
    extra_start = ("                .putInt(\"quiz_start_min\", 0)\n"
                   # 关掉开机自动守护：否则服务一启动就因余额 0 抢先弹题，
                   # 会掩盖「打开 App / 退出设置后才弹」这条真正要验证的链路。
                   "                .putBoolean(\"autoGuardOnBoot\", false)\n"
                   if (V_ZERO_BALANCE or V_OPEN_APP) else "")
    # 打开 App 场景刻意删掉 quiz_pending 键，模拟从 v27 升级上来的旧偏好，
    # 验证 reloadBalance() 的兼容 fallback 也能把「余额 0」认成待弹题。
    # 退出设置回归场景则显式置 false：先别让首页自动弹题抢跑，等家长点
    # 「重置余额」把待办置位后，再由 onSettingsSaved() 消费，链路才干净。
    extra_remove = ("                .remove(\"quiz_pending\")\n"
                    if V_OPEN_APP else
                    ("                .putBoolean(\"quiz_pending\", false)\n"
                     if V_ZERO_BALANCE else ""))
    inject = ("if (b == null) {\n"
              + ("            QuizHost.setForceHost(true);\n"
                 if V_FORCE_HOST else "")
              + "            getSharedPreferences(\"tvbank\", MODE_PRIVATE).edit()\n"
              + extra_remove +
              # v68：验证包写 quiz_balance_ms 并调 reloadBalance()。清掉上一轮
              # 的 deadline 键，让 reloadBalance() 走「无 deadline」分支从现在
              # 锚定并完整落盘，等价于「刚发放余额、准备开始被杀空白期」的初态。
              "                .remove(\"quiz_deadline_wall_ms\")\n"
              "                .remove(\"quiz_deadline_elapsed_ms\")\n"
              "                .putInt(\"quiz_mode\", " + V_MODE + ")\n"
              + extra_start +
          "                .putInt(\"quiz_reward_min\", 2)\n"
          "                .putInt(\"voice_lock_sec\", " + V_LOCK + ")\n"
          "                .putLong(\"quiz_balance_ms\", " + V_BALANCE + "L)\n"
              "                .commit();\n"
              "            QuizMode.reloadBalance(this);\n"
              "        }\n"
              "        setContentView(buildView());\n"
              "        if (b == null) {\n"
              "            handler.postDelayed(new Runnable() {\n"
              "                @Override public void run() {\n"
              + jump_line +
              "                }\n"
              "            }, 1200);\n")
    if V_QUIZNOW:
        inject += ("            handler.postDelayed(new Runnable() {\n"
                   "                @Override public void run() {\n"
                   "                    try { QuizMode.requestManualQuiz(MainActivity.this); } "
                   "catch (Exception ignored) { }\n"
                   "                }\n"
                   "            }, 3000);\n")
    if V_REMIND:
        # 严谨守护实机快进：把服务里的 remainSec 依次拨到各阈值上方 3 秒，
        # 由每秒 tick 自然减到 600 / 300 / 120 / 60，观察四档提醒。
        inject += ("            getSharedPreferences(\"tvbank\", MODE_PRIVATE).edit()\n"
                   "                .putInt(\"interval\", 1200).commit();\n"
                   "            send(QuizOverlayService.ACTION_START);\n"
                   "            final int[] fastSeq = {603, 303, 123, 63};\n"
                   "            final int[] fastIdx = {0};\n"
                   "            handler.postDelayed(new Runnable() {\n"
                   "                @Override public void run() {\n"
                   "                    try {\n"
                   "                        java.lang.reflect.Field f = QuizOverlayService.class\n"
                   "                                .getDeclaredField(\"remainSec\");\n"
                   "                        f.setAccessible(true);\n"
                   "                        if (fastIdx[0] < fastSeq.length) "
                   "f.setInt(null, fastSeq[fastIdx[0]++]);\n"
                   "                    } catch (Throwable ignored) { }\n"
                   "                    if (fastIdx[0] < fastSeq.length) "
                   "handler.postDelayed(this, 6000);\n"
                   "                }\n"
                   "            }, 5000);\n")
    if V_REMIND_FREE:
        # 自主可控：反射快进 QuizMode.sBalanceMs（毫秒），走同一套 remind() 四档。
        inject += ("            send(QuizOverlayService.ACTION_START);\n"
                   "            final long[] freeSeq = {603000L, 303000L, 123000L, 63000L};\n"
                   "            final int[] freeIdx = {0};\n"
                   "            handler.postDelayed(new Runnable() {\n"
                   "                @Override public void run() {\n"
                   "                    try {\n"
                   "                        java.lang.reflect.Field f = QuizMode.class\n"
                   "                                .getDeclaredField(\"sBalanceMs\");\n"
                   "                        f.setAccessible(true);\n"
                   "                        if (freeIdx[0] < freeSeq.length) "
                   "f.setLong(null, freeSeq[freeIdx[0]++]);\n"
                   "                    } catch (Throwable ignored) { }\n"
                   "                    if (freeIdx[0] < freeSeq.length) "
                   "handler.postDelayed(this, 6000);\n"
                   "                }\n"
                   "            }, 5000);\n")
    inject += "        }"
    if V_RESTORE:
        # 验证包改过偏好：从 SharedPreferences 备份里找回原来的出题频率，
        # 再把模式/控屏/起始/奖励/余额恢复到正式状态。正式包不含这段注入。
        inject = ("if (b == null) {\n"
                  "            java.io.File spDir = new java.io.File(getApplicationInfo().dataDir, "
                  "\"shared_prefs\");\n"
                  "            String bakText = \"\";\n"
                  "            try {\n"
                  "                java.io.File bak = new java.io.File(spDir, "
                  "\"tvbank.xml.bak\");\n"
                  "                if (bak.exists()) {\n"
                  "                    byte[] buf = new byte[(int) bak.length()];\n"
                  "                    java.io.FileInputStream in = new java.io.FileInputStream(bak);\n"
                  "                    in.read(buf); in.close();\n"
                  "                    bakText = new String(buf, \"UTF-8\");\n"
                  "                }\n"
                  "            } catch (Throwable ignored) { }\n"
                  "            android.util.Log.d(\"XsdAnser.Pref\", \"BAK=\" + bakText);\n"
                  "            int iv = 30;\n"
                  "            java.util.regex.Matcher mm = java.util.regex.Pattern\n"
                  "                    .compile(\"name=\\\"interval\\\" value=\\\"(\\\\d+)\\\"\")\n"
                  "                    .matcher(bakText);\n"
                  "            if (mm.find()) { try { iv = Integer.parseInt(mm.group(1)); } "
                  "catch (Throwable ignored) { } }\n"
                  "            android.util.Log.d(\"XsdAnser.Pref\", \"restore interval=\" + iv);\n"
                  "            getSharedPreferences(\"tvbank\", MODE_PRIVATE).edit()\n"
                  "                .putInt(\"quiz_mode\", 0)\n"
                  "                .putInt(\"interval\", iv)\n"
                  "                .putInt(\"quiz_start_min\", 3)\n"
                  "                .putInt(\"quiz_reward_min\", 2)\n"
                  "                .putInt(\"voice_lock_sec\", 60)\n"
                  "                .putLong(\"quiz_balance_ms\", 180000L)\n"
                  "                .commit();\n"
                  "            QuizMode.reloadBalance(this);\n"
                  "        }\n"
                  "        setContentView(buildView());\n")
    if V_BALPROBE:
        # v68 真盒验证：set 阶段把「被杀前的余额 + deadline」写进 tvbank，
        # log 阶段只读不写，观察冷启动按 deadline 补扣空白期后的余额。
        # 注意 onCreate 里的 QuizMode.sync(this) 在 setContentView 之前执行，
        # 所以 START 那行 balance 已经是本次冷启动 reloadBalance() 的结果。
        inject = ("if (b == null) {\n"
                  "            String bp = getIntent().getStringExtra(\"balprobe\");\n"
                  "            long bpMs = getIntent().getLongExtra(\"balms\", "
                  + V_BALPROBE_MS + "L);\n"
                  "            android.content.SharedPreferences bpSp = "
                  "getSharedPreferences(\"tvbank\", MODE_PRIVATE);\n"
                  "            android.util.Log.d(\"XsdAnser.BalProbe\", \"START probe=\" + bp"
                  " + \" raw=\" + bpSp.getLong(\"quiz_balance_ms\", -1L)"
                  " + \" balance=\" + QuizMode.balanceMs(MainActivity.this)"
                  " + \" guard=\" + QuizMode.guardRemainSec(MainActivity.this)"
                  " + \" svc=\" + QuizOverlayService.remainSec()"
                  " + \" pending=\" + bpSp.getBoolean(\"quiz_pending\", false));\n"
                  "            if (\"set\".equals(bp)) {\n"
                  "                bpSp.edit()\n"
                  "                    .putInt(\"quiz_mode\", 1)\n"
                  "                    .putLong(\"quiz_balance_ms\", bpMs)\n"
                  "                    .putBoolean(\"quiz_pending\", false)\n"
                  "                    .putBoolean(\"autoGuardOnBoot\", false)\n"
                  "                    .remove(\"quiz_deadline_wall_ms\")\n"
                  "                    .remove(\"quiz_deadline_elapsed_ms\")\n"
                  "                    .remove(\"guard_next_quiz_wall_ms\")\n"
                  "                    .remove(\"guard_next_quiz_elapsed_ms\")\n"
                  "                    .commit();\n"
                  "                QuizMode.reloadBalance(MainActivity.this);\n"
                  "            } else if (\"gset\".equals(bp)) {\n"
                  "                bpSp.edit()\n"
                  "                    .putInt(\"quiz_mode\", 0)\n"
                  "                    .putLong(\"guard_next_quiz_wall_ms\", "
                  "System.currentTimeMillis() + bpMs)\n"
                  "                    .putLong(\"guard_next_quiz_elapsed_ms\", "
                  "android.os.SystemClock.elapsedRealtime() + bpMs)\n"
                  "                    .putBoolean(\"quiz_pending\", false)\n"
                  "                    .putBoolean(\"autoGuardOnBoot\", true)\n"
                  "                    .remove(\"quiz_deadline_wall_ms\")\n"
                  "                    .remove(\"quiz_deadline_elapsed_ms\")\n"
                  "                    .commit();\n"
                  "            } else if (\"gnext\".equals(bp)) {\n"
                  "                QuizMode.onQuizFinished(MainActivity.this);\n"
                  "            } else if (\"restore\".equals(bp)) {\n"
                  "                bpSp.edit()\n"
                  "                    .putInt(\"quiz_mode\", 0)\n"
                  "                    .putBoolean(\"quiz_pending\", false)\n"
                  "                    .putBoolean(\"autoGuardOnBoot\", true)\n"
                  "                    .remove(\"guard_next_quiz_wall_ms\")\n"
                  "                    .remove(\"guard_next_quiz_elapsed_ms\")\n"
                  "                    .commit();\n"
                  "                QuizMode.reloadBalance(MainActivity.this);\n"
                  "            }\n"
                  "            android.util.Log.d(\"XsdAnser.BalProbe\", \"END balance=\""
                  " + QuizMode.balanceMs(MainActivity.this)"
                  " + \" guard=\" + QuizMode.guardRemainSec(MainActivity.this)"
                  " + \" svc=\" + QuizOverlayService.remainSec()"
                  " + \" rawNow=\" + bpSp.getLong(\"quiz_balance_ms\", -1L));\n"
                  "            new android.os.Handler(android.os.Looper.getMainLooper())\n"
                  "                .postDelayed(new Runnable() { public void run() {\n"
                  "                    android.util.Log.d(\"XsdAnser.BalProbe\", \"CHECK showing=\"\n"
                  "                        + QuizOverlayService.isQuizShowing()\n"
                  "                        + \" balance=\" + QuizMode.balanceMs(MainActivity.this)\n"
                  "                        + \" guard=\" + QuizMode.guardRemainSec(MainActivity.this)\n"
                  "                        + \" svc=\" + QuizOverlayService.remainSec()\n"
                  "                        + \" pending=\" + bpSp.getBoolean(\"quiz_pending\", false));\n"
                  "                } }, 2000);\n"
                  "        }\n"
                  "        setContentView(buildView());\n")
    ms = ms.replace("setContentView(buildView());", inject, 1)
    if V_BALPROBE:
        # v69 端到端验证：静默拉起（守护真实入口）与回前台各打一条余额日志，
        # 用于量化「前台 / 后台 / 被杀空白期」三种状态下的扣时与补弹题。
        # 只在验证包里注入，正式包零注入。
        ms = ms.replace(
            "        QuizMode.sync(this);                        "
            "// v68 冷启动按 deadline 补扣空白期，欠题照常补弹\n",
            "        QuizMode.sync(this);\n"
            "        android.util.Log.d(\"XsdAnser.BalProbe\", \"SILENT balance=\"\n"
            "                + QuizMode.balanceMs(MainActivity.this)\n"
            "                + \" guard=\" + QuizMode.guardRemainSec(MainActivity.this)\n"
            "                + \" svc=\" + QuizOverlayService.remainSec());\n", 1)
        ms = ms.replace(
            "        QuizMode.onHomeResumed(this);\n    }",
            "        QuizMode.onHomeResumed(this);\n"
            "        android.util.Log.d(\"XsdAnser.BalProbe\", \"RESUME balance=\"\n"
            "                + QuizMode.balanceMs(MainActivity.this)\n"
            "                + \" guard=\" + QuizMode.guardRemainSec(MainActivity.this)\n"
            "                + \" svc=\" + QuizOverlayService.remainSec());\n    }", 1)
    io.open(pm, "w", encoding="utf-8").write(ms)
    print("[OK] VERIFY build: 临时副本跳过门禁 | mode=%s balance=%sms quiznow=%s "
          "balprobe=%s | 正式源码未改"
          % (V_MODE, V_BALANCE, V_QUIZNOW, V_BALPROBE))
else:
    SRC = os.path.join(BASE, "src-v10")
CLASSES = os.path.join(RUN, "classes")
NEWDEX = os.path.join(RUN, "newdex")
MERGED = os.path.join(RUN, "merged")
# v26 必须吃 patch_v26_wrongbook.py 产出的 base_v30.dex：错题库标题的
# "Proof of Homework" 只存在于历史 smali 基线里，用 v22 基线会原样带回。
BASEDEX = os.path.join(T, "merge", "base_v30.dex")
BASEDEX_ORIG = os.path.join(T, "merge", "base.dex")
BASEAPK = os.path.join(BASE, "ZyxAnser-v32-base.apk")
# v32 基线 = v31 + 自定义主题 AppTheme（windowBackground=#0A101C），
# 由 _theme_c/make_theme_base.py 生成，解决冷启动白屏。
PREV_APK = os.path.join(BASE, "ZyxAnser-v14.apk")
_V_TAGS = (("-pintime", V_PINTIME), ("-balprobe", V_BALPROBE), ("-restore", V_RESTORE),
           ("-first-pin", V_FIRST_PIN), ("-change-pin", V_CHANGE_PIN),
           ("-wrongbook", V_WRONGBOOK), ("-zero-balance", V_ZERO_BALANCE),
           ("-open-app", V_OPEN_APP), ("-free", V_REMIND_FREE),
           ("-guard", V_REMIND))
V_TAG = "".join(t for t, on in _V_TAGS if on)
FINAL = os.path.join(BASE, "XsdAnser-v30-ui"
                     + (("-verify" + V_TAG) if VERIFY else "") + ".apk")

# WrongBookActivity 是历史 smali 类，故意不列入重编译目标，避免重复类定义；
# 它的标题由 base_v30.dex 提供，Java 源码只在 precheck 里做一致性断言。
TARGET_PATTERNS = ("MainActivity*.class", "SettingsActivity*.class",
                   "V10Ui*.class", "QuizDb*.class", "VoiceCoach*.class",
                   "QuizMode*.class", "AnswerShuffle*.class",
                   "ReminderBanner*.class",
                   # v31：中文注音 + 扫码管理（全部为新类，不冲掉历史 smali）
                   "Pinyin*.class", "QrEncoder*.class",
                   "ManagePage*.class", "ManageLock*.class", "ManageServer*.class",
                   # v33：打赏页收款码在线加载（新类，不冲掉历史 smali 的 DonateActivity）
                   "DonateImages*.class",
                   # v51：720p/1080p/2K/4K/8K 全分辨率统一适配（新类）
                   "ScreenFit*.class",
                   # v62：答题遮罩按屏高定高 + 题干字号收敛（新类，被历史 smali 调用）
                   "QuizLayout*.class")
CHECK_FILES = ("MainActivity.java", "SettingsActivity.java", "V10Ui.java",
               "QuizDb.java", "VoiceCoach.java", "QuizMode.java",
               "AnswerShuffle.java", "ReminderBanner.java",
               "WrongBookActivity.java",
               "Pinyin.java", "PinyinData.java", "QrEncoder.java",
               "ManagePage.java", "ManageLock.java", "ManageServer.java",
               "DonateImages.java", "ScreenFit.java", "QuizLayout.java")


# ---------- v35：答错两次规则门禁（两种模式统一） ----------
# 实际运行的 QuizOverlayService 来自历史 smali，重编译 Java 不会覆盖它。
# 锁死 answer() 的两个分支：第 1 次答错弹卡；第 2 次答错静默入库 + 强制换题。
def check_wrongbook_rule():
    d = os.path.join(BASE, "base_dec_v26", "smali", "com", "xsdanser", "tv")
    ap = io.open(os.path.join(d, "AnswerPolicy.smali"), encoding="utf-8").read()
    assert ".field public static final WRONG_LIMIT:I = 0x2" in ap, \
        "WRONG_LIMIT 必须为 2（错题库 / 自动换题的共同阈值）"
    svc = io.open(os.path.join(d, "QuizOverlayService.smali"), encoding="utf-8").read()
    body = svc[svc.index(".method private answer(I)V"):]
    body = body[:body.index(".end method")]
    assert ".locals 5" in body, "answer() 需要 v4 存放本题累计答错次数"
    call = "invoke-direct {p0, p1}, Lcom/xsdanser/tv/QuizOverlayService;->recordWrong(I)V"
    guard = "invoke-virtual {v0}, Lcom/xsdanser/tv/QuizFlow;->wrongAttempts()I"
    limit = "sget v2, Lcom/xsdanser/tv/AnswerPolicy;->WRONG_LIMIT:I"
    branch = "if-ne v4, v2, :xsd_first_wrong"
    final = "invoke-static {}, Lcom/xsdanser/tv/AnswerShuffle;->onFinalWrong()V"
    first = "invoke-static {p1}, Lcom/xsdanser/tv/AnswerShuffle;->onWrong(I)V"
    coach = ("invoke-static {p0, v3, p1}, Lcom/xsdanser/tv/VoiceCoach;->"
             "onWrong(Landroid/content/Context;Lcom/xsdanser/tv/QuizEngine$Question;I)V")
    force = "const/4 v1, 0x2"
    first_label = "\n    :xsd_first_wrong"
    dispatch = "\n    :xsd_wrong_dispatch"

    assert body.count(call) == 1, "answer() 里 recordWrong 调用必须恰好 1 次"
    assert body.count(first) == 1, "第 1 次答错的 onWrong 挂接必须恰好 1 次"
    assert body.count(coach) == 1, "第 1 次答错的 VoiceCoach 挂接必须恰好 1 次"
    for token in (guard, limit, branch, final, force, first_label, dispatch):
        assert token in body, "answer() 缺少 v35 规则挂接 %s" % token
    # 第 2 次答错分支：读计数 -> 判阈值 -> 收状态 -> 入库 -> 强制 SWAP
    assert (body.index(guard) < body.index(limit) < body.index(branch)
            < body.index(final) < body.index(call) < body.index(force)), \
        "第 2 次答错必须依次：wrongAttempts / WRONG_LIMIT / onFinalWrong / recordWrong / SWAP"
    # 第 1 次答错分支：弹卡调用只能在 :xsd_first_wrong 之后、汇合点之前
    assert (body.index(first_label) < body.index(first)
            < body.index(coach) < body.index(dispatch)), \
        "解析卡调用必须落在第 1 次答错分支内，第 2 次不得弹卡"
    assert body.index(force) < body.index("if-ne v1, v2, :cond_5"), \
        "强制 SWAP 常量必须在 SWAP 判断之前写入 v1"
    # 控屏结束会 rebind 并 flow.reset()，必须把本题累计答错次数补回去，
    # 否则第 2 次答错永远到不了 2，换题与入库都不会发生。
    shuf = io.open(os.path.join(BASE, "src-v10", "com", "xsdanser", "tv",
                                "AnswerShuffle.java"), encoding="utf-8").read()
    assert 'getDeclaredField("wrongAttempts")' in shuf, \
        "AnswerShuffle 缺少 v34 答错计数恢复"
    assert "wf.setInt(flow, keepWrong)" in shuf, \
        "AnswerShuffle 未把本题累计答错次数写回 QuizFlow"
    # 第 2 次答错必须能挡住上一张解析卡 / 控屏的延时重排回调
    assert "public static void onFinalWrong()" in shuf, \
        "AnswerShuffle 缺少 onFinalWrong 状态收尾"
    assert "sGeneration++;" in shuf and "sQuestion = null;" in shuf, \
        "onFinalWrong 必须同时让解析卡回调失效并阻断旧题重排"
    mode = io.open(os.path.join(BASE, "src-v10", "com", "xsdanser", "tv",
                                "QuizMode.java"), encoding="utf-8").read()
    assert "return wrongAttempts >= 2;" in mode, \
        "QuizMode.shouldSwap 必须两种模式统一（不再区分 sMode）"
    assert "✗ 已答错 2 次 · 原题已记入错题库 · 已自动换一题" in mode, \
        "换题提示文案缺失"
    print("[OK] v35 答错两次规则：第 1 次弹卡 / 第 2 次静默入库并立即换题（两种模式一致）")


def check_host_bridge():
    """v55 方案 A：承载页桥接的形状校验。
    守护服务必须把 QuizHost 编进 base smali，并在 showQuiz（非前台提承载页）
    与 finishQuiz（答完送回原 App）两处注入调用；首页/家长设置必须登记前台状态。"""
    d = os.path.join(BASE, "base_dec_v26", "smali", "com", "xsdanser", "tv")
    qh = os.path.join(d, "QuizHost.smali")
    assert os.path.isfile(qh), "缺少 base smali 的 QuizHost.smali（方案 A 桥接类）"
    qhs = io.open(qh, encoding="utf-8").read()
    for m in ("isUiForeground", "markUiForeground", "setHost", "releaseHost",
              "setForceHost", "sUiForeground", "sHost", "sForceHost"):
        assert m in qhs, "QuizHost.smali 缺少 %s" % m
    svc = io.open(os.path.join(d, "QuizOverlayService.smali"), encoding="utf-8").read()
    assert "Lcom/xsdanser/tv/QuizHost;->isUiForeground()Z" in svc, \
        "showQuiz 未注入「非前台提承载页」判断"
    assert "Lcom/xsdanser/tv/QuizHost;->releaseHost()V" in svc, \
        "finishQuiz 未注入「答完送回原 App」"
    assert "ZYX_HOST_EXTRA" in svc, "showQuiz 注入的承载页意图缺少 ZYX_HOST_EXTRA 键"
    # v55 模拟器实测修复：条件方向必须是「isUiForeground=false（非前台/看视频）才提承载页」。
    # 之前误写 if-eqz，含义恰好反过来（前台才提、非前台跳过），方案 A 完全失效且死锁依旧。
    # 这里用正则锁死方向，防止后续改动把条件写反。
    import re
    probe = svc.find("Lcom/xsdanser/tv/QuizHost;->isUiForeground()Z")
    assert probe >= 0, "showQuiz 未注入 isUiForeground 判断"
    # 只在该调用之后的一小段里找跳转条件，兼容中间的注释行与空行。
    tail = svc[probe:probe + 1800]
    cond = re.search(r"if-(\w+)\s+(\w+),\s*:cond_host_skip", tail)
    assert cond is not None, "showQuiz 找不到 isUiForeground 之后的 cond_host_skip 承载页条件"
    assert cond.group(1) == "nez", (
        "showQuiz 承载页条件方向错误（if-%s）：必须 if-nez，即 "
        "isUiForeground=false（非前台）时才提承载页" % cond.group(1))
    print("[OK] v55 方案 A：承载页条件方向正确（if-nez，非前台才提承载页）")
    # v55 模拟器实测修复 2：releaseHost 必须「sHost 非空才 moveTaskToBack」。
    # 之前误写 if-nez，有承载页时直接 return，答完题回不到原视频 App。
    rel = qhs[qhs.find(".method public static releaseHost"):]
    rel = rel[:rel.find(".end method")]
    m2 = re.search(
        r"sget-object\s+(\w+),.*?sHost.*?sput-object\s+\w+,.*?sHost"
        r".*?\n\s*if-(\w+)\s+\1,\s*:cond_1", rel, re.S)
    assert m2 is not None and m2.group(2) == "eqz", (
        "releaseHost 条件方向错误（if-%s）：必须 if-eqz，即 sHost 非空才送回原 App"
        % (m2.group(2) if m2 else "?"))
    print("[OK] v55 方案 A：releaseHost 方向正确（if-eqz，有承载页才送回原 App）")
    # v58（MiBOX4 真机实测）：系统禁止三方服务 become forceground，守护没有前台优先级；
    # finish() 会销毁最后一张 Activity，使进程 oom_adj 7 -> 9，小米定制 lmkd 随即
    # forceclosePackage 整包强停（START_STICKY/alarm/静态广播全失效）。锁死防回归。
    assert "->finish()V" not in rel, \
        "releaseHost 不得调用 finish()：会让进程降到 adj 9 被 MIUI lmkd 强停"
    assert "moveTaskToBack(Z)Z" in rel, "releaseHost 必须用 moveTaskToBack 送回原 App"
    # 首页与家长设置两侧都要登记前台状态，否则 isUiForeground 永为 false
    main = io.open(os.path.join(BASE, "src-v10", "com", "xsdanser", "tv",
                                "MainActivity.java"), encoding="utf-8").read()
    assert "moveTaskToBack(true)" in main, \
        "MainActivity.onBackPressed 必须只退后台（不 finish），否则进程仍会降到 adj 9"
    assert "setIntent(i != null ? i : new Intent(this, MainActivity.class))" in main, \
        "onNewIntent 必须 setIntent，否则 recreate 会重新走进承载页形成死循环"
    print("[OK] v58 保活：releaseHost 不 finish / BACK 只退后台 / singleTask 意图已更新")
    assert "QuizHost.markUiForeground(true)" in main and \
        "QuizHost.markUiForeground(false)" in main, "首页未登记前台状态"
    assert "ZYX_HOST_EXTRA" in main, "首页未消费承载页意图键"
    sett = io.open(os.path.join(BASE, "src-v10", "com", "xsdanser", "tv",
                                "SettingsActivity.java"), encoding="utf-8").read()
    assert "QuizHost.markUiForeground(true)" in sett and \
        "QuizHost.markUiForeground(false)" in sett, "家长设置未登记前台状态"
    print("[OK] v55 方案 A：承载页桥接形状校验通过（服务注入 / 首页+设置登记前台）")


def check_blank_period_deduction():
    """v68：打开 / 后台 / 被杀到拉起的空白期一律照扣，且不回退默认时间。

    用户定稿规则：App 打开或后台时余额照常扣；被强杀到守护重新拉起这段空白
    也要扣掉。deadline 是绝对到期时间，天然覆盖这三种情况，所以恢复 v59 的
    「deadline − now 只往小校准」语义。
    但最初的 bug 是「回退原始默认时间」，根因在落盘：apply() 异步 + 每 10 秒
    才写 + 冷启动不写回，刚启动 / 刚切方案被强杀时盘上连 quiz_balance_ms 都没有。
    v68 保留 deadline 反推，只把落盘修好（2 秒 commit + 冷启动必定写回快照）。
    关键陷阱：冷启动补扣完空白期后若再调会 anchorDeadline() 的 persist()，
    刚扣掉的时间会被「现在 + 余额」还回去，因此必须走 writeBalanceSnapshot()。
    """
    p = os.path.join(BASE, "src-v10", "com", "xsdanser", "tv", "QuizMode.java")
    mode = io.open(p, encoding="utf-8").read()

    # ---- v59 结构必须保留：进程存活期间的计时口径 ----
    for key in ("K_DEADLINE_WALL", "K_DEADLINE_ELAPSED",
                "quiz_deadline_wall_ms", "quiz_deadline_elapsed_ms"):
        assert key in mode, "QuizMode 缺少 deadline 键 %s" % key
    assert "android.os.SystemClock.elapsedRealtime()" in mode, \
        "deadline 必须双写单调时钟，单靠墙钟可被改表刷时长"
    assert "Math.min(byWall, byElapsed)" in mode, \
        "双时钟必须取较早到期者（防家长改表 / 防盒子重启后 elapsed 归零）"
    assert "reconcileDeadline(c);" in mode, "sync 未按真实流逝时间校准余额"
    rec = mode[mode.find("private static void reconcileDeadline"):]
    rec = rec[:rec.find("public static void sync(Context c)")]
    assert "QuizOverlayService.isQuizShowing()" in rec and \
        "anchorDeadline();" in rec and "return;" in rec, \
        "reconcileDeadline 必须在答题浮层显示时重锚 deadline 并跳过校准：" \
        "否则 sync() 会在每秒 tick 前按上一秒的 deadline 把余额减 1 秒（实测 22 秒扣掉 29 秒）"
    tick = mode[mode.find("public static void tickSecond"):][:3200]
    assert "sBalanceMs -= delta" not in mode, \
        "仍在按内存每秒扣减：进程被杀期间余额会冻结 → 漏题"
    assert "anchorDeadline();" in tick, \
        "答题浮层显示期间必须暂停计时（每秒重锚 deadline）"
    assert "setBalance(c, startMinutes(c) * 60000L)" in mode, "resetBalance 未走 setBalance"
    assert "setBalance(c, sBalanceMs + rewardMinutes(c) * 60000L)" in mode, \
        "onCorrect 未走 setBalance"
    assert ".remove(K_DEADLINE_WALL)" in mode and ".remove(K_DEADLINE_ELAPSED)" in mode, \
        "setMode 未作废旧 deadline"
    assert '.putLong(K_DEADLINE_WALL' in mode and '.putLong(K_DEADLINE_ELAPSED' in mode, \
        "persist 未把到期时间落盘"

    # ---- v68 冷启动：按 deadline 补扣空白期，且不得重锚把时间还回去 ----
    rel = mode[mode.find("public static void reloadBalance"):]
    rel = rel[:rel.find("private static void reconcileDeadline")]
    assert "p.contains(K_BALANCE)" in rel, \
        "reloadBalance 未判断余额快照是否存在（会回退成原始默认时间）"
    assert "p.getLong(K_BALANCE, 0L)" in rel, "reloadBalance 未读回落盘的余额快照"
    assert "p.contains(K_DEADLINE_WALL)" in rel, \
        "reloadBalance 未判断是否存在 deadline（空白期扣时规则丢失）"
    assert "remainingFromDeadline()" in rel, \
        "冷启动未按 deadline − now 反推余额 → 被杀空白期不会被扣掉"
    assert "sBalanceMs = Math.min(Math.max(0L, sBalanceMs), real)" in rel, \
        "冷启动未只往小校准余额 → 空白期没有补扣 / 旧快照可能多送时间"
    assert "writeBalanceSnapshot(c);" in rel, \
        "冷启动未同步写回余额快照 → 盘上可能没有 quiz_balance_ms，会回退默认时间"
    assert "persist(c);" in rel, \
        "无 deadline 的首次运行 / 刚切方案未锚定并完整落盘"
    assert "anchorDeadline();" not in rel, \
        "冷启动补扣空白期后又重锚 deadline → 刚扣掉的时间会被还回去"
    assert "setPending(c, true)" in rel, \
        "reloadBalance 未在余额归零时补记 quiz_pending（漏题根因）"

    # ---- v68 落盘：同步 commit + 每 2 秒；写快照绝不动 deadline ----
    assert "PERSIST_EVERY_TICKS = 2" in mode, "余额快照落盘间隔必须是 2 秒"
    assert mode.count("++sPersistTick >= PERSIST_EVERY_TICKS") >= 2, \
        "答题浮层显示 / 正常观看两条 tick 分支都必须每 2 秒落盘"
    assert ".apply()" not in mode, \
        "余额快照仍在用 apply() 异步落盘：强杀瞬间会丢最后一次余额"
    wbs = mode[mode.find("private static void writeBalanceSnapshot"):]
    wbs = wbs[:wbs.find("private static void persist")]
    assert ".putLong(K_BALANCE" in wbs and ".commit();" in wbs, \
        "writeBalanceSnapshot 未用 commit() 同步写回 quiz_balance_ms"
    assert "anchorDeadline()" not in wbs and "K_DEADLINE" not in wbs, \
        "writeBalanceSnapshot 不得重锚或改写 deadline（会把补扣的空白期还回去）"
    per = mode[mode.find("private static void persist"):]
    per = per[:per.find("// ---------- v70：严谨守护")]
    assert ".putLong(K_BALANCE" in per and ".commit();" in per, \
        "persist 未用 commit() 同步写回 quiz_balance_ms"
    print("[OK] v68 空白期照扣：deadline 反推补扣 / 不重锚 / "
          "2 秒同步落盘 / 盘上必有余额键不回退默认值")


def check_guard_deadline():
    """v70：严谨守护「距下次出题」沿用自主可控的绝对到期时间规则。

    历史 smali 的 remainSec 只是内存静态字段，进程被杀后 startGuard() 会重置成
    interval（杀进程即可白看一轮）。现在双时钟 deadline 落盘，冷启动照扣空白期，
    每秒通过反射把真实剩余秒数写回 smali 的 remainSec，减到 0 由原 showQuiz()
    弹题；答题期间只保持已到期 deadline（防杀进程逃题白得一轮），答完 /
    切方案 / 改频率才重新锚下一轮。
    """
    p = os.path.join(BASE, "src-v10", "com", "xsdanser", "tv", "QuizMode.java")
    mode = io.open(p, encoding="utf-8").read()
    main = io.open(os.path.join(BASE, "src-v10", "com", "xsdanser", "tv",
                                "MainActivity.java"), encoding="utf-8").read()

    for key in ("K_GUARD_WALL", "K_GUARD_ELAPSED",
                "guard_next_quiz_wall_ms", "guard_next_quiz_elapsed_ms"):
        assert key in mode, "QuizMode 缺少严谨守护 deadline 键 %s" % key
    assert "android.os.SystemClock.elapsedRealtime()" in mode, \
        "严谨守护 deadline 必须双写单调时钟"

    g = mode[mode.find("// ---------- v70：严谨守护"):]
    g = g[:g.find("private static String balanceShort")]
    assert "Math.min(sGuardWall - nowWall(), sGuardElapsed - nowElapsed())" in g, \
        "倒计时未用双时钟取较早到期者（防改表 / 防重启归零）"
    assert "if (ms < 0L) ms = 0L;" in g, "倒计时扣到负数未归零"
    assert "getInt(\"interval\", 600)" in g, "未读取家长设置的出题频率"
    assert ".commit();" in g and ".apply()" not in g, \
        "严谨守护 deadline 必须同步落盘"

    rel = g[g.find("private static void guardReload"):]
    rel = rel[:rel.find("private static long guardRemainingSec")]
    assert "p.contains(K_GUARD_WALL)" in rel and "p.getLong(K_GUARD_WALL" in rel, \
        "冷启动未读回盘上的出题到期时间（空白期扣时规则丢失）"
    assert rel.index("p.contains(K_GUARD_WALL)") < rel.index("} else {") \
        < rel.index("guardAnchor(c);"), \
        "有 deadline 时不得重锚（会把被杀空白期还回去）"
    assert "if (sMode == MODE_GUARD && !sGuardValid) guardReload(c);" in mode, \
        "sync 未在严谨守护冷启动时加载 deadline"

    tick = mode[mode.find("public static void tickSecond"):][:3400]
    assert "long sec = guardRemainingSec();" in tick, \
        "每秒 tick 未按 deadline 计算剩余秒数"
    assert "syncServiceRemainSec(sec);" in tick and "remind(sec);" in tick, \
        "未把真实剩余秒数同步回 smali remainSec / 未用它做提醒"
    assert "guardHold(c);" in tick, "答题浮层显示期间未保持已到期的 deadline"
    hold = g[g.find("private static void guardHold"):]
    hold = hold[:hold.find("private static void syncServiceRemainSec")]
    assert "guardPersist(c);" in hold, "答题期间未同步落盘 deadline"
    assert "guardAnchor" not in hold and "sGuardWall =" not in hold \
        and "nowWall()" not in hold, \
        "答题期间重锚 deadline → 孩子杀进程逃题会白得一轮（必须保持已到期）"
    assert "remind((long) QuizOverlayService.remainSec())" not in mode, \
        "仍在用内存 remainSec 做提醒（后台冻结 / 被杀会停摆）"
    syncsrv = g[g.find("private static void syncServiceRemainSec"):]
    assert 'getDeclaredField("remainSec")' in syncsrv and "setInt(null, v)" in syncsrv, \
        "未通过反射写回历史 smali 的 remainSec"
    assert "Math.min(sec + 1L, 2147483647L)" in syncsrv, \
        "缺少 +1 补偿 smali 随后的 remainSec--（会导致提前 1 秒弹题）"

    assert "if (sMode != MODE_FREE) { guardAnchor(c); return; }" in mode, \
        "答对 / 结束答题后未重新锚定下一轮"
    assert ".remove(K_GUARD_WALL).remove(K_GUARD_ELAPSED)" in mode \
        and "sGuardValid = false;" in mode, "切方案未作废旧出题到期时间"
    assert "if (mode(app) == MODE_GUARD) guardAnchor(app);" in mode, \
        "家长改出题频率后未重新锚定"

    assert "QuizMode.guardRemainSec(this)" in main, \
        "首页「距下次出题」未按 deadline 显示（前台不会按真实时间倒数）"
    assert "String.valueOf(QuizOverlayService.remainSec())" not in main, \
        "首页仍在显示内存 remainSec"
    print("[OK] v70 严谨守护到期时间：双时钟落盘 / 空白期照扣 / 负数归零弹题 / "
          "反射校正 remainSec / 答题保持到期防逃题 / 答完才重锚")


def check_silent_launch():
    """v60：ADB 常驻守护的静默拉起入口形状校验。

    小米 lmkd 强停后包进入 stopped 状态，App 自启全被禁；外部 am start 是唯一
    入口。该入口必须：只拉服务 + 按 deadline 补扣空白期、透明窗口不抢电视焦点、
    onResume 后 moveTaskToBack、且用户点图标能恢复成正常首页。
    """
    p = os.path.join(BASE, "src-v10", "com", "xsdanser", "tv", "MainActivity.java")
    m = io.open(p, encoding="utf-8").read()
    assert ('static final String ZYX_SILENT_EXTRA = '
            '"com.xsdanser.tv.ZYX_SILENT_LAUNCH"') in m, \
        "缺少 v60 静默拉起 extra 键（守护脚本按此键启动）"
    assert "private boolean silentLaunchMode = false;" in m, "缺少静默实例状态字段"
    assert "if (handleSilentLaunch(getIntent(), true)) return;" in m, \
        "onCreate 未在最前面消费静默 extra"
    body = m[m.find("private boolean handleSilentLaunch(Intent i, boolean fresh)"):]
    body = body[:body.find("private void send(String action)")]
    assert body, "缺少 handleSilentLaunch 方法"
    for t in ("getBooleanExtra(ZYX_SILENT_EXTRA, false)",
              "silentLaunchMode = true;",
              "quizHostMode = false;",
              "setBackgroundDrawable(new ColorDrawable(0))",
              "setContentView(new View(this))",
              "QuizMode.sync(this);",
              "send(QuizOverlayService.ACTION_START);",
              "QuizMode.onHomeResumed(this);",
              "return true;"):
        assert t in body, "静默入口缺少 %s" % t
    assert "setContentView(buildView())" not in body, "静默入口不得铺首页 UI"
    assert "finish()" not in body, "静默入口不得 finish（会让进程降到 adj 9 被强停）"
    resume = m[m.find("protected void onResume()"):]
    resume = resume[:resume.find("protected void onPause()")]
    assert "if (silentLaunchMode) {" in resume, "onResume 未分流静默实例"
    assert "QuizHost.markUiForeground(false);" in resume, \
        "静默实例必须标为非前台，否则服务不会提承载页"
    assert "handler.post(silentBack);" in resume, "onResume 未安排退回后台"
    assert "if (silentLaunchMode) moveTaskToBack(true);" in m, \
        "silentBack 未调用 moveTaskToBack"
    ni = m[m.find("protected void onNewIntent(Intent i)"):]
    ni = ni[:ni.find("public void onBackPressed()")]
    assert "wasSilent" in ni and "recreate();" in ni, \
        "用户从桌面点开静默实例时必须 recreate 回正常首页"
    assert "setIntent(i != null ? i : new Intent(this, MainActivity.class))" in ni, \
        "onNewIntent 必须更新 intent，否则 recreate 会重进静默分支死循环"
    print("[OK] v60 静默拉起：透明窗口 / 服务+deadline 补扣空白期 / 不抢前台 / 可恢复首页")


def check_quiz_layout():
    """v62：答题层全分辨率自适应必须挂进历史 smali，且 QuizLayout 编进新 dex。"""
    d = os.path.join(BASE, "base_dec_v26", "smali", "com", "xsdanser", "tv")
    ov = io.open(os.path.join(d, "OverlayQuizView.smali"), encoding="utf-8").read()
    for m in ("cardMarginPx", "topicLp", "fitTopic", "questionLp",
              "gridPx", "explainLp", "fitExplain", "fitQuestion"):
        assert "Lcom/xsdanser/tv/QuizLayout;->" + m in ov, \
            "答题层未接入 QuizLayout." + m
    assert "Lcom/xsdanser/tv/Ui;->fill(I)" not in ov, \
        "题干仍在用 weight=1，长解析会把它挤成 0 高"
    q = io.open(os.path.join(BASE, "src-v10", "com", "xsdanser", "tv",
                             "QuizLayout.java"), encoding="utf-8").read()
    for k in ("ScreenFit.apply", "hDp(", "cardMarginPx", "questionLp",
              "fitQuestion", "setMinHeight", "setMaxHeight", "setMaxLines",
              "pinyinOn", "pinyinOptionSp", "qMaxH"):
        assert k in q, "QuizLayout 缺少 " + k
    a = io.open(os.path.join(BASE, "src-v10", "com", "xsdanser", "tv",
                             "AnswerShuffle.java"), encoding="utf-8").read()
    assert "pinyinOptionSp()" in a, "applyPinyin 未同步缩小选项字号（注音适配缺失）"
    print("[OK] v63 答题层：注音感知（题干 wrap+min/maxHeight + 选项区加高 + 选项字号下调）已接入")


def check_exit_ui():
    """v62：自主可控按返回退出答题后不再残留 / 重弹「该答题了」提示界面。"""
    d = os.path.join(BASE, "src-v10", "com", "xsdanser", "tv")
    m = io.open(os.path.join(d, "MainActivity.java"), encoding="utf-8").read()
    q = io.open(os.path.join(d, "QuizMode.java"), encoding="utf-8").read()
    assert '"答题时间到啦"' not in m, "承载页仍显示「答题时间到啦」提示界面"
    assert "hostAutoBack" in m, "承载页残留兜底缺失"
    for k in ("EXIT_SUPPRESS_MS", "onUserExitQuiz", "setPending(c, false)",
              "sUserExitAt", "resetReminderState()"):
        assert k in q, "QuizMode 缺少退出静默逻辑 " + k
    print("[OK] v62 退出答题：承载页提示已取消 / 残留兜底 / 补弹与提示条已抑制")


def check_fast_pin_gate():
    """v71：家长密码进门慢的修复不能被回退。

    旧实现把 60000 轮 PBKDF2 放在 UI 线程同步跑，且后台尚未建好设置页时
    enterSettings() 会在主线程重复跑题库初始化 + 250 行控件构建。
    """
    d = os.path.join(BASE, "src-v10", "com", "xsdanser", "tv")
    s = io.open(os.path.join(d, "SettingsActivity.java"), encoding="utf-8").read()
    for k in ("FAST_PIN_ITER = 8000", "mPinStore", "deriveFast(", "writeFastPin(",
              '"pinHash"', '"pinSalt"', '"pinIter"', "st.checkPin(pin)",
              "mVerifying", '"正在验证…"', "ensureSettingsBuilt()",
              "beginBuild()", "mPendingEnter", "showSettingsLoading()",
              "new PinGate(mPinStore)", "changeGate = new PinGate(mPinStore)"):
        assert k in s, "v71 快速门禁缺少 " + k
    assert "gate = new PinGate(st);" not in s, "门禁仍直接挂 60000 轮 SettingsStore"
    # 点「确定」必须在后台线程，主线程只负责渲染结果
    onkey = s[s.find("private void onKey"):]
    onkey = onkey[:onkey.find("private void refreshGate")]
    assert "new Thread(new Runnable()" in onkey and 'g.key("确定");' in onkey, \
        "点确定仍在 UI 线程同步做密码校验"
    assert "mUi.post(new Runnable()" in onkey, "后台校验结果未回主线程渲染"
    # 兜底路径禁止在 UI 线程重跑题库与控件构建
    ent = s[s.find("private void enterSettings"):]
    ent = ent[:ent.find("private LinearLayout addCard")]
    assert "BankRepository.installBuiltin" not in ent and \
           "QuizDb.open" not in ent and "buildSettings()" not in ent, \
        "enterSettings 仍在 UI 线程同步跑题库初始化 / 控件构建"
    # 快速格式必须与 dex 的 SettingsStore 同源：PBKDF2-HMAC-SHA1 / 256 bit /
    # 16 字节 salt / 小写 hex / 写后回读校验 / 失败回滚
    der = s[s.find("private static byte[] deriveFast"):]
    der = der[:der.find("private static String hexBytes")]
    assert "PBKDF2WithHmacSHA1" in der and "Integer.valueOf(256)" in der, \
        "快速派生算法与 SettingsStore.derive 不一致（密码会失效）"
    w = s[s.find("private boolean writeFastPin"):]
    w = w[:w.find("private synchronized boolean beginBuild")]
    for k in ("new byte[16]", "FAST_PIN_ITER", ".commit()", "st.checkPin(pin)",
              "oldH", "oldS", "oldI"):
        assert k in w, "快速密码写入缺少 " + k
    assert 'java.util.Locale.US' in s and '"%02x"' in s, "hex 编码与 SettingsStore 不一致"
    print("[OK] v71 门禁提速：8000 轮同格式 / 后台校验 / 失败回滚 / 设置页只构建一次")


check_wrongbook_rule()
check_host_bridge()
check_blank_period_deduction()
check_guard_deadline()
check_silent_launch()
check_quiz_layout()
check_exit_ui()
check_fast_pin_gate()

for d in (CLASSES, NEWDEX, MERGED):
    os.makedirs(d, exist_ok=True)


def run(args, label, allow_stderr=False):
    r = subprocess.run(args, cwd=BASE, stdout=subprocess.PIPE,
                       stderr=subprocess.STDOUT)
    out = r.stdout.decode("utf-8", "replace")
    if r.returncode != 0 and not allow_stderr:
        print("[FAIL] %s\n%s" % (label, out[-3000:]))
        sys.exit(1)
    print("[OK] %s" % label)
    return out


def must_absent(text, items, where):
    for it in items:
        assert it not in text, "%s 仍残留「%s」" % (where, it)


# ---------- AXML 字符串池原地重写（只改字符串，不动 XML 节点与资源 ID） ----------

def patch_manifest_axml(data, replacements):
    assert struct.unpack_from("<H", data, 0)[0] == 0x0003, "不是二进制 AndroidManifest.xml"
    po = 8
    ctype, hs, psz = struct.unpack_from("<HHI", data, po)
    assert ctype == 0x0001 and hs == 28 and psz % 4 == 0, "字符串池格式异常"
    sc, sty, flags, sstart, ststart = struct.unpack_from("<IIIII", data, po + 8)
    assert sty == 0 and ststart == 0 and not (flags & 0x100), "仅支持无样式的 UTF-16 字符串池"
    offs = struct.unpack_from("<%dI" % sc, data, po + hs)
    strings = []
    for o in offs:
        q = po + sstart + o
        n = struct.unpack_from("<H", data, q)[0]
        q += 2
        if n & 0x8000:
            n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, q)[0]
            q += 2
        strings.append(data[q:q + 2 * n].decode("utf-16le"))
    hit = 0
    for i, s in enumerate(strings):
        if s in replacements:
            strings[i] = replacements[s]
            hit += 1
    assert hit == len(replacements), "Manifest 待替换字符串命中 %d/%d" % (hit, len(replacements))

    blob = bytearray()
    new_offs = []
    for s in strings:
        new_offs.append(len(blob))
        enc = s.encode("utf-16le")
        blob += struct.pack("<H", len(enc) // 2)
        blob += enc
        blob += b"\x00\x00"
    while len(blob) % 4:
        blob += b"\x00"
    new_sstart = hs + 4 * sc + 4 * sty
    pool = bytearray()
    pool += struct.pack("<HHI", 0x0001, hs, new_sstart + len(blob))
    pool += struct.pack("<IIIII", sc, sty, flags, new_sstart, 0)
    for o in new_offs:
        pool += struct.pack("<I", o)
    assert len(pool) == new_sstart, "字符串池头长度算错"
    pool += blob
    out = bytearray(data[:po]) + pool + data[po + psz:]
    struct.pack_into("<I", out, 4, len(out))
    return bytes(out)


def parse_axml(data):
    """从二进制 AndroidManifest.xml 抽取 <manifest> 标签的 versionCode/versionName 等属性。"""
    assert struct.unpack_from("<I", data, 0)[0] == 0x00080003, "不是二进制 AndroidManifest.xml"
    pos = 8
    strings = []
    res = {}
    seen = set()
    while pos < len(data):
        ctype, hsize, csize = struct.unpack_from("<HHI", data, pos)
        if ctype == 0x0001:  # StringPool
            scount, stcount, flags, sstart, xstart = struct.unpack_from("<IIIII", data, pos + 8)
            base = pos + sstart
            offs = [struct.unpack_from("<I", data, pos + 28 + i * 4)[0] for i in range(scount)]
            for o in offs:
                q = base + o
                if flags & 0x1:  # UTF-8
                    n = data[q]; q += 1
                    if n & 0x80:
                        n = ((n & 0x7F) << 8) | data[q]; q += 1
                    m = data[q]; q += 1
                    if m & 0x80:
                        m = ((m & 0x7F) << 8) | data[q]; q += 1
                    s = data[q:q + m].decode("utf-8", "replace")
                else:  # UTF-16
                    n = struct.unpack_from("<H", data, q)[0]; q += 2
                    if n & 0x8000:
                        n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, q)[0]; q += 2
                    s = data[q:q + 2 * n].decode("utf-16-le", "replace")
                strings.append(s)
        elif ctype == 0x0102:  # StartTag
            name_idx = struct.unpack_from("<I", data, pos + 20)[0]
            attr_count = struct.unpack_from("<H", data, pos + 28)[0]
            apos = pos + 36
            tag = strings[name_idx]
            # v32：<manifest> 拿 versionCode/versionName，<application> 拿 theme
            if tag in ("manifest", "application"):
                seen.add(tag)
                for i in range(attr_count):
                    ns, a_name, raw_val, res_packed, a_data = struct.unpack_from("<IIIII", data, apos)
                    apos += 20
                    a_type = (res_packed >> 24) & 0xFF
                    nm = strings[a_name]
                    if a_type == 0x10:
                        res[nm] = a_data
                    elif a_type == 0x03:
                        res[nm] = strings[raw_val] if raw_val < len(strings) else None
                    elif a_type == 0x01:
                        res[nm] = a_data          # 资源引用（theme）
                if len(seen) == 2:
                    return res
        pos += csize if csize else hsize
    return res if res else None


MANIFEST_FIX = {
    # v31 包名重命名：BASEAPK 里的二进制 AXML 仍是旧 package，这里改写。
    # patch_manifest_axml 会重建整个字符串池并重算偏移，包名长度变化安全。
    "com.jager.tvquiz": "com.xsdanser.tv",
    # 不再声明讯飞引擎；用自身包名占位，保持字符串池条目数不变。
    "com.iflytek.speechcloud": "com.xsdanser.tv",
    "家长监护控屏与答题语音提示": "家长监护控屏与答题",
    # apktool 反编译重编码会把相对组件名展开为完全限定名，需一并改写，
    # 否则组件名停留在 com.jager.tvquiz.*，与 dex 中的 com.xsdanser.tv.* 不匹配。
    "com.jager.tvquiz.BootReceiver": "com.xsdanser.tv.BootReceiver",
    "com.jager.tvquiz.DonateActivity": "com.xsdanser.tv.DonateActivity",
    "com.jager.tvquiz.MainActivity": "com.xsdanser.tv.MainActivity",
    "com.jager.tvquiz.QuickBootReceiver": "com.xsdanser.tv.QuickBootReceiver",
    "com.jager.tvquiz.QuizOverlayService": "com.xsdanser.tv.QuizOverlayService",
    "com.jager.tvquiz.SettingsActivity": "com.xsdanser.tv.SettingsActivity",
    "com.jager.tvquiz.WrongBookActivity": "com.xsdanser.tv.WrongBookActivity",
}

# 0) 源码门禁：PIN / 双方案新名 / 无邀请卡 / 无语音 / 规则分组 / 首页新排版
D = os.path.join(SRC, "com", "xsdanser", "tv")
settings_src = io.open(os.path.join(D, "SettingsActivity.java"), encoding="utf-8").read()
if not VERIFY:
    assert "enterGate(false);" in settings_src, "门禁被跳过，禁止出包"
assert "VERIFY-ONLY" not in settings_src, "验证包残留，禁止出包"
must_absent(settings_src,
            ["warmUpTts", "stopSpeak", "voiceOn", "rowVoice", "rowTone",
             "testSpeak", "engineLabel", "speakerLabel", "语音讲解", "发音人",
             "试听讲解", "自主交换", "讯飞", "com.iflytek"],
            "SettingsActivity 源码")
for k in ('addGroup(quiz, "公共设置")',
          'groupGuard = addGroup(quiz, "严谨守护")',
          'groupFree = addGroup(quiz, "自主可控")',
          'rowLock = addRow(quiz, "答错停留"',
          'rowMode = addRow(quiz, "答题节奏"',
          'rowInterval = addRow(quiz, "出题频率"',
          'rowPerRound = addRow(quiz, "每轮题数"',
          'rowStartMin = addRow(quiz, "起始时长"',
          'rowFreeMin = addRow(quiz, "每题可得"',
          'rowBalance = addRow(quiz, "剩余观看时间"',
          'groupGuard.setText(', 'groupFree.setText('):
    assert k in settings_src, "家长设置缺少 %s" % k
assert "最后 1 分钟每秒倒数" in settings_src, "家长设置未写明提醒规则"
# v25：控制台的悬浮窗授权 / 开机自启入口合并进家长设置；v26 再合入家长密码，
# 卡片由「系统权限与启动」扩成「系统与安全」三组。
for k in ('addCard(col, "系统与安全")', 'addGroup(sys, "权限与自启")',
          'addGroup(sys, "播放与守护")', 'addGroup(sys, "家长密码")',
          'addRow(sys, "悬浮窗授权"', 'addRow(sys, "开机自启"',
          'addRow(sys, "使用情况访问（可选）"', 'addRow(sys, "防卸载（可选）"',
          'private void openUsageSettings()', 'private void openDeviceAdminSettings()',
          '"打开开机自启设置"', 'addButton(sys, "修改家长密码", V10Ui.BLUE',
          "rowOverlay.setText(canDraw()", "private boolean canDraw()",
          "private void openOverlaySettings()", "android.net.Uri",
          # 门禁键盘「确定」常态蓝色：首次设置 / 再次确认 / 修改密码共用同一视图
          '"确定".equals(k) ? V10Ui.BLUE',
          "V10Ui.focus(btn, this, bg, V10Ui.TEXT)",
          "private PinGate activeGate()", "private void enterChangePin()",
          "private void exitChangePin()", "changeGate.startChange()",
          "gateCancel.setVisibility(inChangePin", "放弃修改 · 返回家长设置",
          "家长密码：已设置 · 修改请到下方「系统与安全 → 家长密码」"):
    assert k in settings_src, "家长设置缺少 %s" % k
must_absent(settings_src,
            ['addCard(col, "开机与播放")', "开启厂商自启动设置",
             'addCard(col, "系统权限与启动")'],
            "家长设置旧分类")
# v27/v28：退出设置保存时，自主可控 + 余额 0 要补一次主动弹题
assert "QuizMode.onSettingsSaved(this);" in settings_src, \
    "家长设置 save() 未调用 QuizMode.onSettingsSaved"
assert settings_src.index("QuizMode.onSettingsSaved(this);") < \
       settings_src.index('Toast.makeText(this, "设置已保存"'), \
    "onSettingsSaved 必须在 finish 之前调用"
# v28：家长设置在前台时抑制自动弹题，避免全屏题盖住正在改的参数
for k in ("QuizMode.setSettingsForeground(true)",
          "QuizMode.setSettingsForeground(false)"):
    assert k in settings_src, "家长设置缺少前台抑制 %s" % k
# v47：知识点范围 / 学科配比必须都有「其它」第 4 行，且默认配比 0、保存时联动全学科
quizdb_src = io.open(os.path.join(D, "QuizDb.java"), encoding="utf-8").read()
for k in ('CORE_NAMES = {"数学", "语文", "英语", "其它"}',
          'Subjects.ENGLISH, Subjects.OTHER}',
          'if (!sp.contains("w" + Subjects.OTHER))',
          'st.allSubjects(st.weight(Subjects.OTHER) > 0)',
          'for (int i = 0; i < CORE.length; i++)',
          'return "other";',
          'bank = QuizDb.open(SettingsActivity.this)',
          'sendReload();'):
    assert k in settings_src, "家长设置缺少 v47「其它」学科 %s" % k
for k in ('OTHER_GROUP = "其它"', 'groupsForOther()',
          'scopeGroup(OTHER_GROUP)', 'p.getInt("w" + Subjects.OTHER, 0) > 0',
          'topicGroup.isEmpty() && !isCoreSubject(subject)',
          'FROM questions WHERE subject<>\'\''):
    assert k in quizdb_src, "QuizDb 缺少 v47 其它学科支持 %s" % k

voice_src = io.open(os.path.join(D, "VoiceCoach.java"), encoding="utf-8").read()
must_absent(voice_src,
            ["TextToSpeech", "MediaPlayer", "fanyi.baidu.com", "ENGINE_PKG",
             "VOICE_NAME", "warmUpTts", "voiceOn", "toneName", "engineLabel",
             "speakerLabel", "testSpeak", "讯飞", "iflytek", "嘉嘉", "朗读解析",
             "正确答案", "解析  ·  ", "showOverlay(c, stem, correct"],
            "VoiceCoach 源码")
for k in ("public static boolean isLocked()", "public static void onWrong(",
          "public static int lockSec(", "public static void cycleLock(",
          "AnswerShuffle.isBlocking()", "AnswerShuffle.onLockEnded()",
          "AnswerShuffle.maskAnswer(", "思路  ·  ",
          "showOverlay(c, stem, exp, sec)",
          "再想一想", "稍作休息 · 看看这道题",
          "倒计时结束后四个选项会重新排列", "稍作休息"):
    assert k in voice_src, "VoiceCoach 缺少 %s" % k

mode_src = io.open(os.path.join(D, "QuizMode.java"), encoding="utf-8").read()
assert 'MODE_NAMES = {"严谨守护", "自主可控"}' in mode_src, "方案名未更新"
must_absent(mode_src,
            ["自主交换", "showInvite", "InviteRoot", "snooze", "dismissInvite",
             "K_INVITE_AT", '"现在答题"', '"继续看"', "sInvite", "WindowManager",
             "想多看就答几题", "先看看解析，选项会重新排一次",
             "答错看解析后重答本题",
             "sToastBucket", "LOW_BALANCE_MS", "lowBalanceToast"],
            "QuizMode 源码")
for k in ('MODE_GUARD', 'MODE_FREE', 'K_MODE = "quiz_mode"',
          'K_BALANCE = "quiz_balance_ms"', 'REWARD_MINUTES', 'START_MINUTES',
          'getInt(K_MODE, MODE_GUARD)', 'interceptShow', 'onCorrect',
          'tickSecond', 'onQuizFinished', 'roundTotal', 'correctHint',
          'freeExitKey', 'shouldSwap', 'noPenalty', 'wrongHint', 'footerHint',
          'isFreeCached', 'ACTION_QUIZ_NOW', 'balanceMs', 'KEYCODE_MEDIA_PAUSE',
          'public static void requestManualQuiz(Context c)',
          'sForceNext = true', 'AnswerShuffle.onCorrect()',
          '再看看 · 选项会重新排一次，再想一遍',
          '答错两次自动换题，不扣时间',
          # v70：严谨守护提醒改用落盘 deadline 的真实剩余秒数，不再读内存 remainSec
          'guardRemainingSec()', 'syncServiceRemainSec(', 'guardRemainSec(',
          'getDeclaredField("remainSec")', 'REMIND_10M', 'stageOf(',
          'countdownText(', 'milestoneText(', 'showReminder(',
          'resetReminderState(', '就要答题啦', '先准备一下',
          '想接着看可以先答一题',           'ReminderBanner.show(',
          'ReminderBanner.hide()',
          # v28：落盘「待弹题」状态 + 所有入口统一消费
          'K_PENDING = "quiz_pending"', 'QUIZ_COOLDOWN_MS = 9000L',
          'sPendingQuiz', 'sSettingsForeground', 'sLastQuizRequestAt',
          'p.contains(K_PENDING)', 'setPending(c, true)',
          'setPending(c, false)', 'consumePending(c, false)',
          'consumePending(app, true)', 'consumePending(app, false)',
          'public static void onHomeResumed(final Context c)',
          'public static void setSettingsForeground(boolean v)',
          'public static void onSettingsSaved(final Context c)',
          'private static void consumePending(Context c, boolean force)',
          'remove(K_PENDING)', 'sLastQuizRequestAt = 0L',
          'sTimeUpTriggered = true', 'sForceNext = true',
          'ACTION_QUIZ_NOW', 'H.postDelayed(',
          'QuizOverlayService.isQuizShowing()'):
    assert k in mode_src, "QuizMode 缺少 %s" % k
must_absent(mode_src,
            ["sRemindToast", "Toast.makeText(app, text",
             # v28：这两个正是「余额 0 不弹题」的根因，必须彻底清掉
             "sTimeUpTriggered = sBalanceMs <= 0L", "forceQuiz("],
            "QuizMode 旧状态机残留")

reminder_src = io.open(os.path.join(D, "ReminderBanner.java"), encoding="utf-8").read()
for k in ("public static void show(", "public static void hide()",
          "FLAG_NOT_FOCUSABLE", "TYPE_APPLICATION_OVERLAY", "TYPE_PHONE",
          "WATCHDOG_MS", "removeViewImmediate", "ensureView(", "wm.addView(",
          "Toast.makeText(app, text"):
    assert k in reminder_src, "ReminderBanner 缺少 %s" % k

main_src = io.open(os.path.join(D, "MainActivity.java"), encoding="utf-8").read()
for k in ("当前方案 · ", "剩余观看时间", "QuizMode.sync(this)",
          "QuizMode.balanceMs(this)", "严谨守护", "自主可控", "btnQuiz",
          '"立即答题"', '"立即出题"', "setButton(btnQuiz, free",
          "QuizMode.requestManualQuiz(MainActivity.this)",
          "QuizMode.onHomeResumed(this)"):
    assert k in main_src, "首页缺少 %s" % k
# v28：取消整张「当前方案」大框，改成无框参数条 + 五个异色按钮
# v29：删两个分节标题与底部统计行，方案提示词放大、指标卡/按钮加大
for k in ("private void param(", "private void renderParams(",
          "paramLabel", "paramValue", "renderParams(mode, free",
          "schemeNote", "paramLine", "答一题就能继续看",
          "V10Ui.BTN_RED", "V10Ui.BTN_BLUE", "V10Ui.BTN_AMBER",
          "V10Ui.BTN_PURPLE", "V10Ui.BTN_SLATE",
          # v50：尺寸统一走等比缩放 helper，断言随之更新（仍能挡住旧版 UI 回退）
          "fixed(150)", "fixed(66)", "computeUiScale()", "uiScale",
          '"起始时长"', '"每题可得"',
          '"剩余时间"', '"出题频率"', '"每轮题数"', '"答错停留"',
          "GRID_COLS = 5", "看电视 · 答个题 · 有约定",
          # v29：方案提示词 19sp 白色大字 + 字距；v50 关掉 font padding 防止底部裁切
          'tv("", 19, V10Ui.TEXT, Gravity.CENTER)',
          "setIncludeFontPadding(false)",
          "noteRow.setGravity(Gravity.CENTER)", "notePill",
          # v29：按钮字号 15→16
          "button(label, bg, V10Ui.TEXT, 16)",
          "V10Ui.focus(b, this, bg, V10Ui.TEXT)",
          "setButton(btnWrong, "):
    assert k in main_src, "首页缺少 %s" % k
must_absent(main_src,
            ['addMetric(metrics, "内置题库"', '内置离线题库', 'mBank', 'btnBoot',
             'action(row2, "悬浮窗授权"', 'action(row2, "开机自启"',
             'BootSettings.open(', 'st.bootAutostart()',
             '"错题待复习"', "mWrong", "showChangePin(", "hideChangePin(",
             "onPinKey(", "refreshPin(", "pinLayer", "pinGate", "pinFirstKey",
             '"修改密码"', '"确定".equals(k) ? V10Ui.BLUE',
             # v27：旧的两张方案规则大框及配套字段/方法必须清干净
             "schemeCard(", "paintScheme(", "答题规则 · 两种方案",
             "cardGuard", "cardFree", "paintedMode",
             "点「立即答题」能主动攒", "选项重排再答",
             "按出题频率定时出题", "起始时长递减，归零立即停播并出题",
             # v28：当前方案概览大卡整套删除
             "capsule(", "renderScheme(", 'section("当前方案")', '"正在使用"',
             "schemeName", "schemeDesc", "capsuleLabel", "capsuleValue",
             "规则与参数在「家长设置」中修改", "heroBar", "heroLeft",
             # v29：分节标题、底部统计行必须清干净
             "footerNote", "footLine", "section(",
             'section("当前状态")', 'section("快捷操作")',
             '规则与密码在「家长设置」中修改',
             '"错题 " + WrongBook.cachedCount()'],
            "首页 v26~v29 清理项")
assert main_src.count('action(row1,') == 5 and 'action(row2,' not in main_src, \
    "首页按钮应为一行 5 个"
assert main_src.count("param(params, ") == 3, "无框参数条应有三段实时参数"

wrongbook_src = io.open(os.path.join(D, "WrongBookActivity.java"), encoding="utf-8").read()
assert '"错题库"' in wrongbook_src and "Proof of Homework" not in wrongbook_src, \
    "错题库 Java 源码仍带英文副标题（真正进包的是 base_v30.dex，见 dex 门禁）"

shuffle_src = io.open(os.path.join(D, "AnswerShuffle.java"), encoding="utf-8").read()
for k in ("public static void onBind(", "public static void onWrong(int wrongIdx)",
          "public static void onLockEnded()", "public static boolean isBlocking()",
          "shuffleOptions(q)", 'OverlayQuizView.class.getDeclaredField("explain")',
          "本题已答错 ", "选项已重新排列", "QuizOverlayService.isQuizShowing()",
          "V10Ui.SELECT_DARK", "EXPLAIN_MS = 20000L",
          "setVisibility(View.GONE)", "public static String maskAnswer(",
          "maskAnswer(q.explain", "先看看思路，再试一次", "思路  ·  ",
          "Pattern.quote(chosen)", "java.util.regex.Pattern", "＿＿"):
    assert k in shuffle_src, "AnswerShuffle 缺少 %s" % k
must_absent(shuffle_src,
            ["正确答案", "6000L", "先看看解析，再试一次",
             "本题暂无文字解析，看看正确答案再想一次", "解析  ·  ",
             "t.setText(\"解析", "chosen.length() < 2"],
            "AnswerShuffle 源码")

ui_src = io.open(os.path.join(D, "V10Ui.java"), encoding="utf-8").read()
for k in ("SELECT_DARK", "SELECT_LIGHT", "ACCENT_LIGHT", "ACCENT_DEEP",
          "outlined(SELECT_DARK", "常态按钮/链接一律不用绿色",
          "BTN_RED", "BTN_BLUE", "BTN_AMBER", "BTN_PURPLE", "BTN_SLATE",
          "v28 快捷操作"):
    assert k in ui_src, "V10Ui 缺少 %s" % k

# v51：全分辨率适配（720p / 1080p / 2K / 4K / 8K）。
# 基准 960x540dp；Java 页面与历史 smali 页面都必须接到 ScreenFit，漏一处
# 就会出现「有的页适配、有的页不适配」。
fit_src = io.open(os.path.join(D, "ScreenFit.java"), encoding="utf-8").read()
for k in ("BASE_W_DP = 960f", "BASE_H_DP = 540f", "densityFor(",
          "public static synchronized void apply(", "dm.density = target",
          "dm.scaledDensity", "public static float density("):
    assert k in fit_src, "ScreenFit 缺少 %s" % k
assert ui_src.count("ScreenFit.apply(c)") >= 3, "V10Ui 未接入全分辨率基准化"
assert "ScreenFit.apply(this)" in main_src, "首页未接入全分辨率基准化"
assert "ScreenFit.apply(this)" in settings_src, "家长设置未接入全分辨率基准化"
assert "ScreenFit.apply(app)" in io.open(
    os.path.join(D, "ReminderBanner.java"), encoding="utf-8").read(), \
    "悬浮提醒条未接入全分辨率基准化"
_ui_smali = io.open(os.path.join(BASE, "base_dec_v26", "smali", "com",
                                "xsdanser", "tv", "Ui.smali"), encoding="utf-8").read()
assert _ui_smali.count("Lcom/xsdanser/tv/ScreenFit;->apply(Landroid/content/Context;)V") == 3, \
    "历史 smali 页面（答题层/门禁/错题库/打赏）未接入 dp/tv/button 基准化"
print("[OK] precheck v51：全分辨率基准化已覆盖 Java 页面 + 历史 smali 页面 + 悬浮提醒条")

# 配色硬门禁：首页 / 家长设置不得再把旧青绿常量用在常态元素上
must_absent(main_src + settings_src,
            ["V10Ui.TEAL;", "V10Ui.TEAL,", "V10Ui.TEAL_LIGHT", "V10Ui.TEAL_DARK",
             "GuardState.toggleColor"],
            "首页 / 家长设置常态配色")
assert "选项重新排列" in settings_src, "家长设置说明未写选项重排"
assert "立即答题" in settings_src, "家长设置说明未写立即答题"
print("[OK] precheck 源码：门禁 / 无语音 / 配色 / 双方案提醒 / 常驻提示条 / "
      "quiz_pending 待弹题 / 打开 App 自动弹 / 首页无方案大框与分节标题 / "
      "方案提示词 19sp / 一行 5 异色按钮 16sp / 门禁确定蓝色 / 系统与安全三组 / "
      "错题库去英文")

# 题库与 base dex 一致性
# 版本戳不再硬编码：与源码目录 base_dec_v26/assets 动态比对，并校验题库字节一致，
# 防止之后换库时忘记同步进 ZyxAnser-v30-base.apk（该 APK 才是最终包的资源来源）。
SRC_BANK_DIR = os.path.join(BASE, "base_dec_v26", "assets")
src_ver = open(os.path.join(SRC_BANK_DIR, "xsdanser_bank.db.version"), "rb").read().decode("utf-8").strip()
src_bank = open(os.path.join(SRC_BANK_DIR, "xsdanser_bank.db"), "rb").read()
z = zipfile.ZipFile(BASEAPK, "r")
assert z.read("assets/xsdanser_bank.db.version").decode("utf-8").strip() == src_ver, \
       "BASEAPK 内题库版本戳与源码不一致（换库后需重新注入 BASEAPK）"
assert z.read("assets/xsdanser_bank.db") == src_bank, \
       "BASEAPK 内题库与源码字节不一致（换库后需重新注入 BASEAPK）"
assert z.getinfo("assets/xsdanser_bank.db").file_size > 900000, "题库文件异常"
v14_dex = z.read("classes.dex")
base_manifest = z.read("AndroidManifest.xml")
z.close()
assert v14_dex == open(BASEDEX_ORIG, "rb").read(), "基线 APK dex 与原始 base.dex 不一致"
patched_manifest = patch_manifest_axml(base_manifest, MANIFEST_FIX)
# v32 版本门禁：必须升到 31 / 2.03（自定义主题消除冷启动白屏）
_mattrs = parse_axml(patched_manifest)
assert _mattrs.get("versionCode") == 31, "versionCode 应为 31，实际 %r" % _mattrs.get("versionCode")
assert _mattrs.get("versionName") == "2.03", "versionName 应为 2.03，实际 %r" % _mattrs.get("versionName")
assert _mattrs.get("theme") == 0x7f050000, "主题应为 0x7f050000（AppTheme），实际 %r" % _mattrs.get("theme")
assert _mattrs.get("package") == "com.xsdanser.tv", "包名不应改动"
assert b"com.iflytek.speechcloud" not in patched_manifest, "Manifest 仍声明讯飞引擎"
assert "语音".encode("utf-16le") not in patched_manifest, "Manifest 仍含语音字样"
assert "com.xsdanser.tv".encode("utf-16le") in patched_manifest
assert "com.jager.tvquiz".encode("utf-16le") not in patched_manifest, "Manifest 仍含旧包名"

base_dex = open(BASEDEX, "rb").read()
assert base_dex != v14_dex, "v26 挂接未生效：base_v30.dex 与基线相同"
for k in (b"interceptShow", b"onCorrect", b"tickSecond", b"onQuizFinished",
          b"roundTotal", b"correctHint", b"freeExitKey", b"shouldSwap",
          b"noPenalty", b"wrongHint", b"footerHint", b"beginRound",
          b"quizPerRound", b"isLocked", b"onWrong",
          b"Lcom/xsdanser/tv/AnswerShuffle;", b"AnswerShuffle",
          b"Lcom/xsdanser/tv/WrongBookActivity;", b"onBind"):
    assert k in base_dex, "v26 base dex 缺少 %s" % k.decode()
# 错题库标题的英文副标题只在历史 smali 里，必须已由 patch_v26_wrongbook.py 清掉
assert b"Proof of Homework" not in base_dex, "base_v30.dex 仍含 Proof of Homework"
assert "错题库".encode("utf-8") in base_dex, "base_v30.dex 缺少中文错题库标题"
# v51：Ui.smali 的 dp/tv/button 挂接必须已回编译进 base dex
assert b"ScreenFit" in base_dex, \
    "base_v30.dex 未含 ScreenFit 引用：改完 Ui.smali 后需先跑 patch_v35_wrong.py"
print("[OK] precheck dex/Manifest：题库 v4 / 13 处挂接 / 历史补丁 / "
      "错题库标题已去英文 / 清单已清理讯飞")

# 1) 编译
sources = glob.glob(os.path.join(SRC, "**", "*.java"), recursive=True)
assert not [s for s in sources if s.endswith(".bak")], "备份文件混入源码"
cmd = [JAVA, "-cp", ECJ,
       "org.eclipse.jdt.internal.compiler.batch.Main",
       "-1.8", "-nowarn", "-encoding", "UTF-8", "-proceedOnError",
       "-cp", STUB, "-d", CLASSES] + sources
out = run(cmd, "ECJ compile", allow_stderr=True)
errs = [l for l in out.splitlines()
        if "ERROR in" in l and any(k in l for k in CHECK_FILES)]
if errs:
    print("[FAIL] target classes have errors:\n" + "\n".join(errs[:20]))
    sys.exit(1)
print("[OK] 8 target classes: 0 errors")

# 2) 目标类打新 dex
targets = []
for pat in TARGET_PATTERNS:
    targets += glob.glob(os.path.join(CLASSES, "com", "xsdanser", "tv", pat))
assert len(targets) >= 7, "target classes not emitted: %d" % len(targets)
assert any("QuizMode.class" in t for t in targets), "QuizMode 未编译"
assert any("AnswerShuffle.class" in t for t in targets), "AnswerShuffle 未编译"
assert any("ReminderBanner.class" in t for t in targets), "ReminderBanner 未编译"
run([JAVA, "-cp", D8, "com.android.tools.r8.D8", "--min-api", "21",
     "--lib", STUB, "--output", NEWDEX] + targets,
    "d8 -> new.dex (%d classes)" % len(targets), allow_stderr=True)

# 3) 合并 base dex
run([JAVA, "-cp", D8, "com.android.tools.r8.D8", "--min-api", "21",
     "--lib", STUB, "--output", MERGED, BASEDEX,
     os.path.join(NEWDEX, "classes.dex")], "d8 merge dex", allow_stderr=True)
data = open(os.path.join(MERGED, "classes.dex"), "rb").read()
assert b"Lcom/xsdanser/tv/QuizMode;" in data, "QuizMode 类未进 dex"
assert b"Lcom/xsdanser/tv/AnswerShuffle;" in data, "AnswerShuffle 类未进 dex"
assert b"Lcom/xsdanser/tv/QuizOverlayService;" in data, "答题服务补丁丢失"
# v55 方案 A：承载页桥接类必须由 base smali 编译进 dex，且服务调用它（调用关系已由
# check_host_bridge 在 smali 源码层面校验；此处仅确认类与方法名已进入最终 dex）。
assert b"Lcom/xsdanser/tv/QuizHost;" in data, "merged dex 缺少 QuizHost（方案 A 承载页桥接）"
assert b"Lcom/xsdanser/tv/QuizLayout;" in data, "merged dex 缺少 QuizLayout（v62 答题层自适应）"
assert b"pinyinOptionSp" in data, "merged dex 缺少 QuizLayout.pinyinOptionSp（v63 注音适配）"
assert b"pinyinOn" in data, "merged dex 缺少 QuizLayout.pinyinOn（v63 注音适配）"
assert b"releaseHost" in data, "merged dex 缺少 QuizHost.releaseHost 方法"
assert b"isUiForeground" in data, "merged dex 缺少 QuizHost.isUiForeground 方法"
for k in (b"interceptShow", b"onCorrect", b"tickSecond", b"onQuizFinished",
          b"roundTotal", b"correctHint", b"freeExitKey", b"shouldSwap",
          b"noPenalty", b"wrongHint", b"footerHint", b"beginRound",
          b"AnswerShuffle", b"onBind", b"onWrong", b"onSettingsSaved",
          b"quiz_pending", b"onHomeResumed", b"consumePending",
          b"setSettingsForeground", b"setPending",
          # v60：ADB 守护静默拉起 extra（守护脚本与 App 的约定键）
          b"com.xsdanser.tv.ZYX_SILENT_LAUNCH"):
    assert k in data, "merged dex 缺少挂接 %s" % k.decode()
for s in ("严谨守护", "自主可控", "当前方案 · ",
          "答一题就能继续看", "答题赚时间 · 用完立即暂停 · 可连答不限题数",
          "到点自动出题 · 暂停视频 · 答对才能继续",
          "出题频率", "每轮题数", "剩余时间",
          "公共设置", "当前生效", "未启用", "剩余观看时间", "每题可得",
          "起始时长", "答错停留", "点此重置", "观看时间用完啦", "答对 +",
          "立即答题", "立即出题", "再想一想", "稍作休息 · 看看这道题",
          "倒计时结束后四个选项会重新排列", "先看看思路，再试一次",
          "本题已答错 ", "选项已重新排列",
          "再看看 · 选项会重新排一次，再想一遍",
          "答错两次自动换题，不扣时间", "第 1 次答错看解析，第 2 次入库换题",
          "✗ 已答错 2 次 · 原题已记入错题库 · 已自动换一题", "onFinalWrong",
          "思路  ·  ",
          "本题暂无文字思路", "＿＿",
          "就要答题啦", "先准备一下", "想接着看可以先答一题",
          "最后 1 分钟每秒倒数",
          "看电视 · 答个题 · 有约定",
          "系统与安全", "权限与自启", "播放与守护", "家长密码",
          "修改家长密码", "放弃修改 · 返回家长设置", "家长密码已保存",
          "家长密码：已设置 · 修改请到下方「系统与安全 → 家长密码」",
          "悬浮窗授权", "打开开机自启设置", "已授权 · 点此管理",
          "未授权 · 点此开启", "该设备没有悬浮窗权限入口",
          "使用情况访问", "防卸载",
          "未找到使用情况访问入口，请在系统设置搜索「使用情况访问」",
          "未找到设备管理员入口，请在系统设置搜索「设备管理员」",
          "错题库",
          # v31：默认参数迁移 / 中文注音 / 扫码管理
          "xsd_defaults_v31", "pinyinOn", "中文注音", "扫码管理",
          "打开扫码管理", "管理地址", "家长已锁屏", "api/state",
          "api/set", "api/action", "学时兑 · 家长控制台",
          # v33：打赏页作者署名 + 收款码在线加载
          "请开发者喝一杯咖啡", "收款码在线加载", "DonateImages",
          "https://xsd.qx47.com/app/images/wx.png",
          "https://xsd.qx47.com/app/images/ali.jpg",
          "donate_wechat", "donate_alipay",
          # v45：答题界面顶部题库来源标签（历史 smali 由 AnswerShuffle 反射替换）
          "本地题库",
          # v47：其它学科大类、导入后即时生效提示
          "groupsForOther", "其它", "学科配比 → 其它"):
    assert s.encode("utf-8") in data, "merged dex 缺少「%s」" % s
assert b"Proof of Homework" not in data, "merged dex 仍含 Proof of Homework"
# v30 品牌更名门禁：旧的 ZyxAnser / 自由学答个题 必须彻底消失
assert b"ZyxAnser" not in data, "merged dex 仍残留 ZyxAnser"
assert "自由学答个题".encode("utf-8") not in data, "merged dex 仍残留 自由学答个题"
assert "学时兑".encode("utf-8") in data, "merged dex 缺少 学时兑"
assert b"XsdAnser" in data, "merged dex 缺少 XsdAnser 代号"
assert b"Lcom/xsdanser/tv/WrongBookActivity;" in data, "错题库历史 smali 类丢失"
# v31 包名重命名门禁：最终 dex 里旧包名必须彻底消失，新包类数量不能缩水
assert b"Lcom/jager/tvquiz/" not in data, "merged dex 仍含旧包类描述符"
assert b"com.jager.tvquiz" not in data, "merged dex 仍含旧包名字符串"
assert data.count(b"Lcom/xsdanser/tv/") >= 140, "新包类数量异常（可能漏编）"
# v34：错题库写入与答错计数方法必须仍在 dex 中（门禁的 smali 规则已保证调用位置）
assert b"recordWrong" in data, "merged dex 缺少错题库写入方法"
assert b"wrongAttempts" in data, "merged dex 缺少答错计数方法"
must_absent(data, [s.encode("utf-8") for s in
                   ("自主交换", "现在答题", "想多看就答几题", "com.iflytek.speechcloud",
                    "fanyi.baidu.com", "嘉嘉", "语音讲解", "发音人", "试听讲解",
                    "晓燕", "EngineSettings", "打开讯飞发音人设置",
                    "答对免打扰", "等会儿再说", "TextToSpeech", "MediaPlayer",
                    "再看看 · 换个选项试试", "答错了 · 先看看解析",
                    "遥控器按键已锁定", "先看看解析，再试一次",
                    "先看看解析，选项会重新排一次",
                    "答错看解析后重答本题，不扣时间",
                    "看看正确答案再想一次",
                    "Proof of Homework", "系统权限与启动",
                    # v33：打赏页旧署名必须彻底消失
                    "作者", "请开发者喝一杯咖啡", "不联网",
                    # v27：首页两张方案规则大框的文案必须彻底消失
                    "答题规则 · 两种方案", "点「立即答题」能主动攒",
                    "按出题频率定时出题", "起始时长递减，归零立即停播并出题",
                    "答错控屏看思路，回来选项重排再答",
                    # v28：当前方案概览大卡整套文案必须清干净
                    "正在使用", "规则与参数在「家长设置」中修改",
                    # v29：分节标题与底部统计行文案必须从 dex 消失
                    "当前状态", "快捷操作",
                    "规则与密码在「家长设置」中修改",
                    "· 答对 ")],
            "merged dex")
print("[OK] dex strings verified: 双方案 / 无答案显示 / 常驻提醒 / "
      "quiz_pending 待弹题 / 打开 App 自动弹 / 方案大框已清 / 一行 5 异色按钮 / "
      "无分节标题与底部统计行 / 系统与安全三组 / 错题库无英文 / 13 挂接 (%d bytes)"
      % len(data))

# 4) 替换 APK 内 classes.dex + AndroidManifest.xml
unsigned = os.path.join(RUN, "unsigned.apk")
zi = zipfile.ZipFile(BASEAPK, "r")
zo = zipfile.ZipFile(unsigned, "w")
for it in zi.infolist():
    if it.filename == "classes.dex":
        payload = data
    elif it.filename == "AndroidManifest.xml":
        payload = patched_manifest
    else:
        payload = zi.read(it.filename)
    info = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    info.compress_type = it.compress_type
    info.external_attr = it.external_attr
    info.internal_attr = it.internal_attr
    info.create_system = it.create_system
    zo.writestr(info, payload)
zo.close()
zi.close()
print("[OK] repack APK（dex + 清理后的 Manifest）")

# 5) 对齐 + 同证书签名
aligned = os.path.join(RUN, "aligned.apk")
run([ZIPALIGN, "-f", "4", unsigned, aligned], "zipalign")
run([JAVA, "-jar", APKSIGNER, "sign", "--ks", KS,
     "--ks-pass", "pass:xsdanser123456", "--key-pass", "pass:xsdanser123456",
     "--ks-key-alias", "xsdanser", "--out", FINAL, aligned], "apksigner sign")


def cert_digest(apk):
    o = run([JAVA, "-jar", APKSIGNER, "verify", "--print-certs", apk],
            "apksigner verify %s" % os.path.basename(apk))
    return [l.strip() for l in o.splitlines() if "SHA-256 digest" in l]


NEW_CERT = os.environ.get("XSD_NEW_CERT") == "1"
c_new, c_old = cert_digest(FINAL), cert_digest(PREV_APK)
print("v14 cert:", c_old)
print("new cert:", c_new)
# v51：基线改为「设备实际已安装的 xsdanser 证书」。旧代码拿 v14 的
# dd0116 证书比对，而 v30 起一直用 f1224e，导致每次构建都在签名完成后误报失败。
EXPECTED_CERT = ("Signer #1 certificate SHA-256 digest: "
                 "f1224e8003311cbe2b0072bdfb7864affe3473103752f4f4b2f9a00b558c693d")
if NEW_CERT:
    assert c_new, "最终包未签名或无法读取证书指纹"
    print("[WARN] XSD_NEW_CERT=1：已使用全新证书，指纹与已装 v30 不一致，"
          "已安装的旧包将无法直接覆盖升级，需先卸载再重装。")
else:
    assert c_new == [EXPECTED_CERT], \
        "签名证书不是已安装版本的 xsdanser 证书（f1224e…），无法覆盖安装：%r" % c_new

# 6) 只允许 classes.dex + AndroidManifest.xml 变化（与 v31 基线 BASEAPK 比对，忽略签名 META-INF）
SIGN = {"META-INF/MANIFEST.MF", "META-INF/XSDANSER.RSA", "META-INF/XSDANSER.SF"}
a, b = zipfile.ZipFile(BASEAPK, "r"), zipfile.ZipFile(FINAL, "r")
a_names = set(n for n in a.namelist() if n not in SIGN and not n.startswith("META-INF/"))
b_names = set(n for n in b.namelist() if n not in SIGN and not n.startswith("META-INF/"))
assert a_names == b_names, "APK 条目集合变化"
changed = [n for n in sorted(a_names) if a.read(n) != b.read(n)]
print("changed entries:", changed)
assert set(changed) == {"AndroidManifest.xml", "classes.dex"}, \
    "除 AndroidManifest.xml/classes.dex 外还有内容变化: %s" % changed
assert b"com.iflytek.speechcloud" not in b.read("AndroidManifest.xml"), "最终包仍含讯飞声明"
a.close()
b.close()
# v30 资源更名门禁：最终包资源必须显示新名称
_final_arsc = zipfile.ZipFile(FINAL).read("resources.arsc")
assert "学时兑".encode("utf-8") in _final_arsc, "最终包 resources.arsc 缺少 学时兑"
assert "自由学答个题".encode("utf-8") not in _final_arsc, "最终包 resources.arsc 仍残留 自由学答个题"
print("[DONE] %s (%d bytes)" % (FINAL, os.path.getsize(FINAL)))

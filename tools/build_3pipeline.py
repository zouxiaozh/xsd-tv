# -*- coding: utf-8 -*-
"""
学时兑 v2.05 三管线保活构建（对齐 Aerial 桌面常驻机制）。

基于 build_v30_ui.py 完全同源的 dex 管线（base_v30.dex + 目标类 ECJ/d8 合并），
Manifest 与 res/xml 由 Stage A（apktool 文本改造，见 _stageA_res.apk）提供。

三管线：
  ① 通知监听 RevivalListener（增强：requestRebind + 自愈）
  ② 无障碍服务 KeepAliveAccessibility
  ③ 账号同步 KeepAliveSync（AccountService + SyncService）
  统一自愈工具类 KeepAlive（WRITE_SECURE_SETTINGS 写回三管线配置 + 恢复被禁组件）

输出：XsdAnser-v3pipeline.apk（同 xsdanser 证书签，可覆盖安装）。
不修改任何历史源码；只新增 4 个类文件 + 改写 RevivalListener。
"""
import glob
import io
import os
import shutil
import subprocess
import sys
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

SRC = os.path.join(BASE, "src-v10")
BASEDEX = os.path.join(T, "merge", "base_v30.dex")
STAGE_A = os.path.join(BASE, "_stageA_res.apk")           # Stage A：Manifest+资源就绪
OUT = os.path.join(BASE, "XsdAnser-v3pipeline.apk")
EXPECTED_CERT = ("Signer #1 certificate SHA-256 digest: "
                 "f1224e8003311cbe2b0072bdfb7864affe3473103752f4f4b2f9a00b558c693d")

# 目标类：历史重编译类（与 build_v30_ui.py TARGET_PATTERNS 一致）+ 三管线新类
HIST_PATTERNS = ("MainActivity*.class", "SettingsActivity*.class",
                 "V10Ui*.class", "QuizDb*.class", "VoiceCoach*.class",
                 "QuizMode*.class", "AnswerShuffle*.class",
                 "ReminderBanner*.class", "Pinyin*.class", "QrEncoder*.class",
                 "ManagePage*.class", "ManageLock*.class", "ManageServer*.class",
                 "DonateImages*.class", "ScreenFit*.class", "QuizLayout*.class")
NEW_PATTERNS = ("RevivalListener*.class", "KeepAlive*.class")
TARGET_PATTERNS = HIST_PATTERNS + NEW_PATTERNS


def run(args, label, allow_stderr=False):
    p = subprocess.run(args, cwd=BASE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = (p.stdout or b"").decode("utf-8", "replace")
    if p.returncode != 0 and not allow_stderr:
        print("[FAIL] %s rc=%d\n%s" % (label, p.returncode, out[-3000:]))
        sys.exit(1)
    print("[OK] %s" % label)
    return out


RUN = os.path.join(BASE, "_build_3pipeline")
for d in (RUN, os.path.join(RUN, "classes"), os.path.join(RUN, "newdex"),
          os.path.join(RUN, "merged")):
    os.makedirs(d, exist_ok=True)
CLASSES = os.path.join(RUN, "classes")
NEWDEX = os.path.join(RUN, "newdex")
MERGED = os.path.join(RUN, "merged")

print("[1/6] ECJ 全量编译源码")
sources = glob.glob(os.path.join(SRC, "**", "*.java"), recursive=True)
assert not [s for s in sources if s.endswith(".bak")], "备份文件混入源码"
cmd = [JAVA, "-cp", ECJ, "org.eclipse.jdt.internal.compiler.batch.Main",
       "-1.8", "-nowarn", "-encoding", "UTF-8", "-proceedOnError",
       "-cp", STUB, "-d", CLASSES] + sources
out = run(cmd, "ECJ compile", allow_stderr=True)
new_check = ("KeepAlive.java", "KeepAliveAccessibility.java",
             "KeepAliveSync.java", "RevivalListener.java")
errs = [l for l in out.splitlines()
        if "ERROR in" in l and any(k in l for k in new_check)]
if errs:
    print("[FAIL] 三管线新类编译错误：\n" + "\n".join(errs[:20]))
    sys.exit(1)
print("[OK] 三管线新类 0 编译错误")

print("[2/6] d8 目标类 -> new.dex")
targets = []
for pat in TARGET_PATTERNS:
    targets += glob.glob(os.path.join(CLASSES, "com", "xsdanser", "tv", pat))
assert any("KeepAlive.class" in t and "$" not in t for t in targets), "KeepAlive 未编译"
assert any("KeepAliveAccessibility.class" in t for t in targets), "无障碍类未编译"
assert any("KeepAliveSync$SyncService.class" in t for t in targets), "SyncService 未编译"
assert any("KeepAliveSync$AccountService.class" in t for t in targets), "AccountService 未编译"
assert any("RevivalListener.class" in t for t in targets), "RevivalListener 未编译"
run([JAVA, "-cp", D8, "com.android.tools.r8.D8", "--min-api", "21",
     "--lib", STUB, "--output", NEWDEX] + targets, "d8 new.dex (%d classes)" % len(targets),
    allow_stderr=True)

print("[3/6] d8 合并 base_v30.dex + new.dex")
run([JAVA, "-cp", D8, "com.android.tools.r8.D8", "--min-api", "21",
     "--lib", STUB, "--output", MERGED, BASEDEX,
     os.path.join(NEWDEX, "classes.dex")], "d8 merge", allow_stderr=True)
data = open(os.path.join(MERGED, "classes.dex"), "rb").read()
for k in (b"Lcom/xsdanser/tv/KeepAlive;",
          b"Lcom/xsdanser/tv/KeepAliveAccessibility;",
          b"Lcom/xsdanser/tv/KeepAliveSync$SyncService;",
          b"Lcom/xsdanser/tv/KeepAliveSync$AccountService;",
          b"Lcom/xsdanser/tv/KeepAliveSync$SyncAdapter;",
          b"Lcom/xsdanser/tv/KeepAliveSync$Authenticator;",
          b"Lcom/xsdanser/tv/RevivalListener;",
          b"Lcom/xsdanser/tv/QuizOverlayService;",
          b"Lcom/xsdanser/tv/QuizMode;"):
    assert k in data, "merged dex 缺少 %s" % k.decode()
# 历史功能门禁（与 build_v30_ui.py 同源、已验证）
for s in ("学时兑", "家长设置", "错题库"):
    assert s.encode("utf-8") in data, "merged dex 缺少「%s」" % s
assert b"XsdAnser" in data, "merged dex 缺少 XsdAnser 代号"
assert b"Lcom/xsdanser/tv/WrongBookActivity;" in data, "错题库历史类丢失"
assert b"Lcom/jager/tvquiz/" not in data, "merged dex 仍含旧包名"
assert b"com.jager.tvquiz" not in data, "merged dex 仍含旧包名字符串"
assert data.count(b"Lcom/xsdanser/tv/") >= 140, "新包类数量异常（可能漏编）"
assert b"recordWrong" in data and b"wrongAttempts" in data, "错题库方法丢失"
assert b"Proof of Homework" not in data, "merged dex 仍含英文副标题"
print("[OK] merged dex %d bytes，三管线类齐全（%d 个新包类引用）"
      % (len(data), data.count(b"Lcom/xsdanser/tv/")))

print("[4/6] 替换 Stage A APK 的 classes.dex")
unsigned = os.path.join(RUN, "unsigned.apk")
zi = zipfile.ZipFile(STAGE_A, "r")
zo = zipfile.ZipFile(unsigned, "w")
for it in zi.infolist():
    payload = data if it.filename == "classes.dex" else zi.read(it.filename)
    info = zipfile.ZipInfo(it.filename, date_time=it.date_time)
    info.compress_type = it.compress_type
    info.external_attr = it.external_attr
    info.internal_attr = it.internal_attr
    info.create_system = it.create_system
    zo.writestr(info, payload)
zo.close(); zi.close()
print("[OK] repack（dex 替换，Manifest/资源保持 Stage A）")

print("[5/6] zipalign + 同证书签名")
aligned = os.path.join(RUN, "aligned.apk")
run([ZIPALIGN, "-f", "4", unsigned, aligned], "zipalign")
run([JAVA, "-jar", APKSIGNER, "sign", "--ks", KS,
     "--ks-pass", "pass:xsdanser123456", "--key-pass", "pass:xsdanser123456",
     "--ks-key-alias", "xsdanser", "--out", OUT, aligned], "apksigner sign")
o = run([JAVA, "-jar", APKSIGNER, "verify", "--print-certs", OUT], "verify cert")
certs = [l.strip() for l in o.splitlines() if "SHA-256 digest" in l]
assert certs == [EXPECTED_CERT], "证书指纹不符，无法覆盖安装：%r" % certs
print("[OK] 证书 f1224e… 一致")

print("[6/6] 最终校验")
z = zipfile.ZipFile(OUT)
m = z.read("AndroidManifest.xml")
# AXML 字符串池为 UTF-16LE，权限/类名需用 UTF-16LE 字节搜索
for s in ("WRITE_SECURE_SETTINGS", "BIND_NOTIFICATION_LISTENER_SERVICE",
          "BIND_ACCESSIBILITY_SERVICE", "android.content.SyncAdapter",
          "KeepAliveAccessibility", "RevivalListener"):
    assert s.encode("utf-16le") in m, "Manifest 缺少 %s" % s
for r in ("res/xml/keepalive_accessibility.xml", "res/xml/keepalive_sync.xml",
          "res/xml/keepalive_account.xml"):
    assert r in z.namelist(), "缺少资源 %s" % r
assert z.read("classes.dex") == data, "classes.dex 未正确替换"
z.close()
print("[DONE] %s (%d bytes)" % (OUT, os.path.getsize(OUT)))

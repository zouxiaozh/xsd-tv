# -*- coding: utf-8 -*-
"""方案 C：给学时兑 TV 端加自定义主题，彻底消除冷启动白屏。

白屏来源：清单主题是 @android:style/Theme.Material.Light.NoActionBar，
它的 windowBackground 是 #FAFAFA，而首页底色是 #0A101C。冷启动时系统先画
「启动窗口」，再画窗口底色，两段都露白，直到首帧内容画出来。

本脚本用 aapt2 重建一份带 AppTheme 的 resources.arsc（只覆盖
windowBackground=@color/app_bg，父主题仍是浅色 Material，弹窗配色不变），
再把新资源表 + 二进制清单里的 theme 引用打进新的基线包。

产物：ZyxAnser-v32-base.apk（供 build_v30_ui.py 当 BASEAPK 用）

安全约束：
  * 原有 11 个资源 id 由 --stable-ids 钉死，新增只有 color/style 两个新类型；
  * 只改 resources.arsc / res 文件路径名 / 清单 theme 一个属性，其余字节全保留；
  * 脚本自带校验，任何一条不满足直接抛错，不会产出半成品基线。

用法：python make_theme_base.py
"""
import io
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(BASE)
AAPT2 = os.path.join(TOOLS, "sig-tools", "android-14", "aapt2.exe")
FRAMEWORK = os.path.join(os.environ.get("LOCALAPPDATA", ""),
                         "apktool", "framework", "1.apk")
OLD_BASE = os.path.join(TOOLS, "ZyxAnser-v31-base.apk")
NEW_BASE = os.path.join(TOOLS, "ZyxAnser-v32-base.apk")
BUILD = os.path.join(BASE, "build")
RES = os.path.join(BASE, "res")
MANIFEST_TXT = os.path.join(BASE, "AndroidManifest.xml")
STABLE_IDS = os.path.join(BASE, "stable-ids.txt")

# 原主题（Theme.Material.Light.NoActionBar 的框架资源 id）
OLD_THEME_ID = 0x01030241
APP_BG = 0xFF0A101C

# 原有资源 id：重建后必须一字不差
ORIGINAL_IDS = {
    "drawable/donate_alipay": 0x7F010000,
    "drawable/donate_wechat": 0x7F010001,
    "drawable/ic_launcher": 0x7F010002,
    "drawable/ic_stat_quiz": 0x7F010003,
    "drawable/tv_banner": 0x7F010004,
    "string/app_codename": 0x7F020000,
    "string/app_name": 0x7F020001,
    "string/guard_channel": 0x7F020002,
    "string/guard_notification_text": 0x7F020003,
    "string/guard_notification_title": 0x7F020004,
    "xml/network_security_config": 0x7F030000,
}


def run(cmd, label):
    p = subprocess.run(cmd, capture_output=True)
    out = (p.stdout or b"").decode("utf-8", "replace") + \
          (p.stderr or b"").decode("utf-8", "replace")
    if p.returncode != 0:
        raise SystemExit("[FAIL] %s\n%s" % (label, out.strip()))
    return out


# ---------------------------------------------------------------- AXML 处理
def _pool_strings(data, off, hsize):
    """解析 ResStringPool，返回字符串列表。"""
    count = struct.unpack_from("<I", data, off + 8)[0]
    flags = struct.unpack_from("<I", data, off + 16)[0]
    str_start = struct.unpack_from("<I", data, off + 20)[0]
    utf8 = bool(flags & (1 << 8))
    out = []
    for i in range(count):
        so = struct.unpack_from("<I", data, off + hsize + i * 4)[0]
        p = off + str_start + so
        if utf8:
            n = data[p]
            p += 2 if n & 0x80 else 1          # 跳过 utf16 长度（不用）
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            out.append(data[p:p + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            out.append(data[p:p + n * 2].decode("utf-16-le", "replace"))
    return out


def _pool_string_pos(data, off, hsize, idx):
    """返回第 idx 个字符串的数据起点与字符数（仅 UTF-16 池）。"""
    str_start = struct.unpack_from("<I", data, off + 20)[0]
    so = struct.unpack_from("<I", data, off + hsize + idx * 4)[0]
    p = off + str_start + so
    n = struct.unpack_from("<H", data, p)[0]
    p += 2
    if n & 0x8000:
        n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
        p += 2
    return p, n


def patch_theme(man, new_id, version_code=31, version_name="2.03"):
    """改二进制清单里三处：application/theme 引用、versionCode、versionName。

    versionName 走字符串池原地覆盖（"2.02" -> "2.03" 等长），不动池结构。
    """
    data = bytearray(man)
    off = struct.unpack_from("<H", data, 2)[0]
    total = struct.unpack_from("<I", data, 4)[0]
    strings = None
    pool_off = pool_hsize = None
    hits = 0
    vc_done = vn_done = False
    while off < total:
        ctype, chsize, csize = struct.unpack_from("<HHI", data, off)
        if csize == 0:
            break
        if ctype == 0x0001:
            strings = _pool_strings(data, off, chsize)
            pool_off, pool_hsize = off, chsize
        elif ctype == 0x0102 and strings is not None:
            base = off + chsize
            name = strings[struct.unpack_from("<I", data, base + 4)[0]]
            a_start, a_size, a_count = struct.unpack_from("<HHH", data, base + 8)
            if name in ("manifest", "application"):
                ap = base + a_start
                for i in range(a_count):
                    a = ap + i * a_size
                    nm = strings[struct.unpack_from("<I", data, a + 4)[0]]
                    raw = struct.unpack_from("<I", data, a + 8)[0]
                    dtype = data[a + 15]
                    val = struct.unpack_from("<I", data, a + 16)[0]
                    if nm == "theme" and name == "application":
                        assert dtype == 0x01, "theme 不是资源引用，dtype=0x%02x" % dtype
                        assert val == OLD_THEME_ID, \
                            "theme 当前值不是预期主题：0x%08x（清单已被改过？）" % val
                        struct.pack_into("<I", data, a + 16, new_id)
                        hits += 1
                    elif nm == "versionCode" and name == "manifest":
                        assert dtype == 0x10, "versionCode 不是整型"
                        struct.pack_into("<I", data, a + 16, version_code)
                        vc_done = True
                    elif nm == "versionName" and name == "manifest" and dtype == 0x03:
                        p, n = _pool_string_pos(data, pool_off, pool_hsize, raw)
                        enc = version_name.encode("utf-16-le")
                        assert n * 2 == len(enc), \
                            "versionName 长度变化会破坏字符串池（%d vs %d）" % (n, len(enc))
                        data[p:p + len(enc)] = enc
                        vn_done = True
        off += csize
    assert hits == 1, "清单里找到 %d 处 theme 属性（应为 1）" % hits
    assert vc_done, "清单里没找到 versionCode"
    assert vn_done, "清单里没找到 versionName"
    return bytes(data)


def theme_id_of(apk):
    out = run([AAPT2, "dump", "resources", apk], "dump resources")
    m = re.search(r"resource (0x7f[0-9a-f]{6}) style/AppTheme", out)
    assert m, "新资源表里找不到 style/AppTheme"
    return int(m.group(1), 16)


def res_paths_of(apk):
    out = run([AAPT2, "dump", "resources", apk], "dump resources")
    return sorted(set(re.findall(r"(res/[^\s]+\.(?:png|xml|9\.png))", out)))


def check_ids(apk):
    out = run([AAPT2, "dump", "resources", apk], "dump resources")
    got = dict((k, int(v, 16)) for v, k in
               re.findall(r"resource (0x7f[0-9a-f]{6}) ([a-z]+/[A-Za-z0-9_]+)",
                          out))
    for name, want in ORIGINAL_IDS.items():
        assert got.get(name) == want, \
            "资源 id 变动：%s 期望 0x%08x 实际 %s" % (name, want, got.get(name))
    return got


def main():
    if os.path.isdir(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(BUILD)
    assert os.path.isfile(FRAMEWORK), "缺少 apktool 框架包：%s" % FRAMEWORK

    # 1) aapt2 编译 + 链接（--stable-ids 钉住原有 id，--no-auto-version 少改动）
    res_zip = os.path.join(BUILD, "res.zip")
    theme_apk = os.path.join(BUILD, "theme.apk")
    run([AAPT2, "compile", "--dir", RES, "-o", res_zip], "aapt2 compile")
    run([AAPT2, "link", "-o", theme_apk, "-I", FRAMEWORK,
         "--manifest", MANIFEST_TXT, "--stable-ids", STABLE_IDS,
         "--min-sdk-version", "21", "--target-sdk-version", "33",
         "--no-auto-version", res_zip], "aapt2 link")

    # 2) 校验资源 id 没有漂移
    sid = theme_id_of(theme_apk)
    check_ids(theme_apk)
    print("[OK] 资源 id 保持不变（11 项），新 style/AppTheme = 0x%08x" % sid)

    # 3) 新资源表里的文件路径（aapt2 会把 drawable 目录升成 -v4）
    paths = res_paths_of(theme_apk)
    rename = {}
    for p in paths:
        old = p.replace("-v4/", "/")
        rename[old] = p
    print("[OK] res 文件路径 %d 个（drawable 目录加 -v4 后缀）" % len(paths))

    # 4) 组装新基线包
    src = zipfile.ZipFile(OLD_BASE, "r")
    names = set(src.namelist())
    for old in rename:
        assert old in names, "旧基线包里找不到 %s" % old
    arsc = zipfile.ZipFile(theme_apk).read("resources.arsc")
    man = patch_theme(src.read("AndroidManifest.xml"), sid)
    print("[OK] 清单 theme 已改为 0x%08x（#%06X 窗口底色）" % (sid, APP_BG & 0xFFFFFF))

    if os.path.exists(NEW_BASE):
        os.remove(NEW_BASE)
    zo = zipfile.ZipFile(NEW_BASE, "w")
    skip = {"resources.arsc", "AndroidManifest.xml"}
    for it in src.infolist():
        n = it.filename
        if n in skip:
            continue
        payload = src.read(n)
        target = rename.get(n, n)
        info = zipfile.ZipInfo(target, date_time=it.date_time)
        info.compress_type = it.compress_type
        info.external_attr = it.external_attr
        info.internal_attr = it.internal_attr
        info.create_system = it.create_system
        zo.writestr(info, payload)
    for n, payload in (("resources.arsc", arsc), ("AndroidManifest.xml", man)):
        info = zipfile.ZipInfo(n, date_time=(2026, 11, 16, 20, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED if n == "resources.arsc" else \
            zipfile.ZIP_STORED
        info.external_attr = 0o600 << 16
        zo.writestr(info, payload)
    zo.close()
    src.close()

    # 5) 成品自检：条目齐全 / 资源表可读 / 主题引用正确
    z = zipfile.ZipFile(NEW_BASE)
    for p in paths:
        assert p in z.namelist(), "新基线包缺少 %s" % p
    assert "res/drawable-mdpi/ic_launcher.png" not in z.namelist()
    z.close()
    check_ids(NEW_BASE)
    back = run([AAPT2, "dump", "resources", NEW_BASE], "dump 新基线包")
    assert "style/AppTheme" in back and "color/app_bg" in back
    print("[OK] 新基线包：%s (%d bytes)" % (os.path.basename(NEW_BASE),
                                          os.path.getsize(NEW_BASE)))


if __name__ == "__main__":
    sys.exit(main())

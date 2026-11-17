# -*- coding: utf-8 -*-
"""冷启动白屏 A/B 测试：录屏 -> 抽帧 -> 逐帧统计亮度/白像素占比。

用法：
  python coldstart_probe.py <apk路径> <标签>
输出：adb-tools/_theme_c/shots/<标签>/frame_XX.png + 控制台逐帧报告
"""
import os
import subprocess
import sys
import time

ADB = r"D:\HBuilderX\plugins\launcher-tools\tools\adbs\adb.exe"
SER = "127.0.0.1:5567"
HERE = os.path.dirname(os.path.abspath(__file__))
PKG = "com.xsdanser.tv"
ACT = "com.xsdanser.tv/.MainActivity"
FFMPEG = None


def run(cmd, timeout=120):
    return subprocess.run(cmd, capture_output=True, timeout=timeout)


def adb(*args, **kw):
    return run([ADB, "-s", SER] + list(args), **kw)


def main():
    apk, tag = sys.argv[1], sys.argv[2]
    out = os.path.join(HERE, "shots", tag)
    os.makedirs(out, exist_ok=True)
    for f in os.listdir(out):
        os.remove(os.path.join(out, f))

    adb("connect", SER)
    print("[1] 安装", os.path.basename(apk))
    r = adb("install", "-r", "-d", apk, timeout=300)
    print("   ", (r.stdout or b"").decode("utf-8", "replace").strip()[-80:])

    print("[2] 清进程 + 回桌面")
    adb("shell", "am force-stop " + PKG)
    adb("shell", "input keyevent 3")
    time.sleep(2)
    adb("shell", "rm -f /sdcard/cs.mp4")

    print("[3] 开始录屏（6 秒）")
    rec = subprocess.Popen([ADB, "-s", SER, "shell",
                            "screenrecord --time-limit 6 --bit-rate 6000000 /sdcard/cs.mp4"],
                           stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    time.sleep(1.2)
    print("[4] 冷启动 App")
    adb("shell", "am start -W -n " + ACT, timeout=120)
    rec.wait(timeout=60)
    time.sleep(1)
    adb("pull", "/sdcard/cs.mp4", os.path.join(out, "cs.mp4"))

    print("[5] 抽帧")
    run([FFMPEG, "-y", "-i", os.path.join(out, "cs.mp4"),
         "-fps_mode", "passthrough", os.path.join(out, "frame_%02d.png")], timeout=180)

    frames = sorted(f for f in os.listdir(out) if f.startswith("frame_"))
    print("    共 %d 帧" % len(frames))
    from PIL import Image
    white_frames = []
    for f in frames:
        im = Image.open(os.path.join(out, f)).convert("RGB")
        w, h = im.size
        small = im.resize((w // 8, h // 8))
        px = list(small.getdata())
        avg = sum(sum(p) for p in px) / (len(px) * 3.0)
        bright = sum(1 for p in px if min(p) > 200) / float(len(px))
        flag = "WHITE" if bright > 0.5 else ("light" if avg > 120 else "")
        print("    %-12s avg=%6.1f  white=%5.1f%%  %s"
              % (f, avg, bright * 100, flag))
        if bright > 0.5:
            white_frames.append(f)
    print("[RESULT] %s 白帧 %d 个：%s" % (tag, len(white_frames), white_frames[:8]))


if __name__ == "__main__":
    FFMPEG = __import__("imageio_ffmpeg").get_ffmpeg_exe()
    main()

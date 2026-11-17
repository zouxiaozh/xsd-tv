# 学时兑 · 电视端

一个跑在 Android TV / 安卓盒子上的「答对题才能看电视」应用。到点全屏暂停视频，答对自动放行；答错不罚，先讲解析，倒计时结束重排选项再答。全部功能免费，无广告、无内购。

## 关键词

电视答题、答对才能看电视、看电视先答题、儿童学习、小学生学习、语文数学、拼音、人教版、一年级到九年级、家长控制、家长设置、看电视时间管理、防沉迷、学习习惯、错题库、知识点出题、启蒙教育、家庭教育、免费无广告、安卓电视、电视盒子、Android TV、智能电视、kids learning、quiz、parental control。

## 功能

- 出题频率、每轮题数、答错停留时间均可由家长设置
- 语文 / 数学 / 英语配比可调，教材版本、年级、学期可切换
- 按知识点大类筛选，错题库自动收集错题
- 家长设置密码门禁，手机端可远程管理
- 支持开机自启、守护保活

## 安装（直接装现成 APK）

`apk/XsdAnser-v2.03.apk` 是已签名的安装包，可直接安装，无需编译。三种方式任选：

**方式一：手机端守护 App 推送（最省事）**

在手机上安装「学时兑守护」，连上电视后点「安装学时兑到电视」，App 会自动下载并推送安装，不用开电脑、不用 U 盘。

**方式二：ADB 安装（电脑命令行）**

```bash
adb connect <电视IP>:5555
adb install -r -d apk/XsdAnser-v2.03.apk
```

`-r` 覆盖安装会保留原有设置和学习数据。电视需先在开发者选项里打开「网络调试/ADB 调试」。

**方式三：U 盘安装**

把 APK 拷进 U 盘插到电视上，用文件管理器打开安装。需在设置里允许「未知来源」。

首次打开会进入设置向导：选教材版本、年级、出题频率和学科配比，设一个家长密码即可。之后全自动运行。

## 目录结构

```
app/src/com/xsdanser/tv/   应用源码（48 个 Java 文件）
apk/                       已签名安装包
base/                      基线资源（AndroidManifest、res、assets 题库）
tools/build_v30_ui.py      主构建脚本
tools/gen_bank_downloads.py 题库分包生成脚本
tools/theme/               自定义启动主题工具链（消除冷启动白屏）
```

## 构建

构建脚本采用 dex 注入方式：以预编译基线 APK 为底，替换 `classes.dex` 与 `AndroidManifest.xml` 后重新签名。

```bash
cd tools
python build_v30_ui.py
```

脚本会校验：

- 版本门禁（versionCode 31 / versionName 2.03）
- 主题引用必须为 `0x7f050000`（自定义 `AppTheme`）
- 基线内题库与 `base/assets/xsdanser_bank.db` 字节一致

完整构建还需要以下**未随仓库分发**的依赖，请自行准备：

- `sig-tools/`：JDK 17、Android SDK 14 的 aapt2 / d8 / apksigner / zipalign
- `toolchain/`：基线 dex 与 stub jar
- `ZyxAnser-v32-base.apk`：预编译基线包
- `xsdanser-custom.keystore`：签名密钥（请用自己的密钥替换，勿提交）

## 题库

内置题库 `base/assets/xsdanser_bank.db` 为 SQLite 数据库，`questions` 表共 10800 道人教版 1–9 年级题目。

题库格式规范见 `tools/gen_bank_downloads.py`。导入下载包时可覆盖 `topicGroup` 字段来按单元筛题；App 当前不支持按难度等级筛选。

## 说明

- 本仓库不含签名密钥与个人收款码；`apk/` 下提供已签名的现成安装包。
- `base/res/drawable-nodpi/` 下的打赏码为占位图，如需启用请自行替换同名文件。
- 项目采用自定义主题把启动窗口底色设为与应用一致的深色，消除冷启动白闪。

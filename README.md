# 学时兑 · 电视端

一个跑在 Android TV / 安卓盒子上的「答对题才能看电视」应用。到点全屏暂停视频，答对自动放行；答错不罚，先讲解析，倒计时结束重排选项再答。全部功能免费，无广告、无内购。

## 功能

- 出题频率、每轮题数、答错停留时间均可由家长设置
- 语文 / 数学 / 英语配比可调，教材版本、年级、学期可切换
- 按知识点大类筛选，错题库自动收集错题
- 家长设置密码门禁，手机端可远程管理
- 支持开机自启、守护保活

## 目录结构

```
app/src/com/xsdanser/tv/   应用源码（48 个 Java 文件）
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

- 本仓库不含签名密钥、个人收款码与预编译 APK，这些文件不会也无法从仓库中恢复。
- `base/res/drawable-nodpi/` 下的打赏码为占位图，如需启用请自行替换同名文件。
- 项目采用自定义主题把启动窗口底色设为与应用一致的深色，消除冷启动白闪。

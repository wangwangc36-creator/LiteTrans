# LiteTrans 0.1 Alpha

一个以“滚动流畅优先”为目标的 LSPosed 实时翻译模块：**英语 / 西班牙语 → 简体中文**。

## 第一版的目标

- 标准 Android `TextView` 原位翻译。
- Hook 热路径只做过滤、RAM 缓存查找、异步入队。
- 翻译绝不阻塞目标 App 的 UI 线程。
- 约 24ms 合并一批文字再通过 Binder 发给独立 `:translator` 进程。
- Google ML Kit 官方本地翻译模型；第一次下载后可离线。
- 目标 App 进程与翻译服务各有一层 LRU RAM Cache。
- RecyclerView/列表复用通过 generation + source 双校验，过期翻译不会覆盖新内容。
- 尽量保留常见 `Spanned` 字体、颜色、链接等 span。

## 刻意不做的事情

为了保证 Alpha 版性能，暂时不做：

- `StaticLayout.Builder` 同步翻译
- Dex 全量扫描 / 全类 `setText` 扫描
- SQLite 热路径缓存
- 每条文字的 Xposed 日志
- WebView DOM 翻译
- Jetpack Compose 专用 Hook

这意味着第一版对标准 View UI 最有效；Telegram 的部分自绘消息、某些 Compose App、WebView 页面可能暂时无法覆盖。应先在实机确认“流畅度基线”，再增加定点适配，而不是重新引入全局重 Hook。

## 构建

要求：JDK 17、Android SDK 35、Gradle 8.9。项目使用 Android Gradle Plugin 8.7.3。

```bash
gradle :app:assembleDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`

仓库内也包含 GitHub Actions 工作流，推送到 GitHub 后可以直接构建 Debug APK。

## 安装 / 使用

1. 安装 APK。
2. 打开 LiteTrans，点“准备英 / 西 / 中文翻译模型”，首次需要联网。
3. 在 LSPosed 中启用 LiteTrans。
4. 作用域只选择需要翻译的普通 App。
5. 不要选择 Android、SystemUI、输入法。
6. 强制停止并重新打开目标 App。

## 翻译引擎

使用 Google 官方 ML Kit Translation `17.0.3` 与 bundled Language ID `17.0.6`。模型准备完成后翻译在设备本地运行。

Translation powered by Google ML Kit.

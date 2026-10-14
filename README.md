# tVNS Android SDK Demo

演示如何用 tVNS Android SDK 完成设备扫描、连接、刺激控制与状态查询。

本工程只调用 SDK 的公开接口，可作为接入样板。完整接口说明（每个接口的请求字段、响应字段、错误码）见随 SDK 交付的《集成文档》。

## 环境要求

| 项 | 要求 |
|---|---|
| Android Studio | Koala Feature Drop (2024.1.2) 或更新 |
| JDK | 17（AGP 8.5 的运行要求）；工程源码兼容级别为 11 |
| Gradle / AGP | 工程自带 wrapper（Gradle 8.9 / AGP 8.5.2），无需另行安装 |
| compileSdk / targetSdk | 35 |
| minSdk | 24（Android 7.0） |
| 运行设备 | **真机，或 arm64 模拟器**。SDK 只提供 `arm64-v8a` 与 `armeabi-v7a` 两套原生库，x86 模拟器跑不起来 |

## 运行步骤

1. 用 Android Studio 打开本工程根目录
2. 等待 Gradle Sync 完成（首次需联网下载依赖）
3. 连接 Android 真机并打开 USB 调试，点 Run
4. 首次运行会申请蓝牙相关权限，全部允许

## 界面与接口对应

| 功能 | 界面元素 | SDK 接口 |
|---|---|---|
| 初始化 | 启动时自动 | `TvnsSdk.getInstance().init(context, TvnsConfig.defaults())` |
| 注册回调 | 启动时自动 | `setSdkListener()` / `setScanListener()` |
| 扫描设备 | 「扫描」/「停止扫描」 | `startScan()` / `stopScan()` |
| 连接设备 | 点击扫描列表中的设备 | `connect(mac)` |
| 断开连接 | 「断开」 | `disconnect()` |
| 开始刺激 | 「开始」 | `startStimulate(args, callback)` |
| 暂停 / 恢复 | 「暂停」⇄「恢复」 | `stopStimulate()` / `continueStimulate()` |
| 停止刺激 | 「停止」 | `stopStimulate()` |
| 调节强度 | 滑杆、「减档」、「加档」 | `adjustStimulateStrength(target, callback)` |
| 查询状态 | 「查询」 | `queryState(callback)` |

刺激参数（`StimulateStartArgs`）：

- **模式 A / 模式 B**：`StimulateStartArgs.ofMode(PatternMode.A / PatternMode.B)`，时长固定为 `kModeDurationSeconds`
- **自定义**：`StimulateStartArgs.ofCustom(durationSeconds, CustomParameters(...))`
  - 脉宽只有 `100 / 150 / 200 / 250 / 300` us 五个档位，用 `CustomParameters.kPulseWidths` 或 `isSupportedPulseWidth()` 校验 —— 它不是 100~300 的连续区间，`110` 这类值设备不认
  - 频率、开始时间、关闭时间的上下限见 `CustomParameters` 上的常量

## 代码导航

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/wecare/tvns/sdkdemo/MainActivity.kt` | 全部演示逻辑：初始化、扫描、连接、刺激控制、状态渲染 |
| `app/src/main/java/com/wecare/tvns/sdkdemo/DeviceAdapter.kt` | 扫描结果列表的 RecyclerView 适配器 |
| `app/libs/tvns-sdk-release.aar` | SDK 本体。客户接入时把这个 AAR 放进自己的工程即可 |

页面底部日志区打印全部回调与错误，排查问题先看这里；「复制日志」可一键导出。

## 说明

- 本工程 **不开混淆**（`isMinifyEnabled = false`），与 SDK 的交付态一致。客户工程是否开启由客户自行决定。
- 本工程的 `AndroidManifest.xml` **不声明蓝牙权限**，权限由 AAR 的 manifest 合并进来。若客户的构建流程关掉了 manifest 合并，扫描会直接失败。
- AAR 通过 `files(...)` 引入，Gradle 读不到它的 pom，传递依赖需要手动补 —— 本工程补的是 `androidx.annotation`。

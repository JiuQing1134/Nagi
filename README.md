# 凪（Nagi）

让 HONOR 软件更新（`com.hihonor.ouc`）显示“当前系统已是最新”的独立 App。
不冻结、不卸载 OUC，不修改系统版本号。

- **applicationId**：`com.nemu.windstop`（源码命名空间 `com.nemu.updateguard`）
- **版本**：`1.3.8` / `versionCode 36`
- **图标**：白底翠绿三角回环，圆点位于正中心；名字是 **凪**。
- **配套项目（Nemu / 眠梦）**：https://github.com/JiuQing1134/Nemu
- **完整地址与产物**：[`PROJECTS.md`](PROJECTS.md)

## 工作方式

- **只保留 LSPosed 模式**：hook `com.hihonor.ouc` 的
  `com.hihonor.android.hnouc.check.model.result.a.d()` 返回 false。
  作用域勾选“软件更新”，启用模块后重启一次。
- 1.3.5 起删除了独立模式（无障碍覆盖层）及 Root / Shizuku / ADB 通道。
  没有 LSPosed 时，本 App 不会拦截更新。

> “已激活”优先以**这次进入前台/点刷新后收到的 fresh hook 报告**为准。App 会发一次请求广播；
> OUC 主进程如果已 hook，会在约 0.5 秒内通过 `HookReportService` 重报。超过 2 秒没有新报告时，
> 只要本次开机曾经成功 hook 过，仍显示“已激活”，不再显示黄色“当前进程未上报”。
> 只有本次开机从未成功 hook、fresh 报告明确失败、或管理器开关/作用域被关闭/取消时，
> 才显示非激活状态。OUC 进程退出时 Binder death 会标记这次 fresh 报告失效，但已成功 hook
> 的历史仍按激活显示；重新打开软件更新并点检查更新后会收到新报告。LSPosed 管理器开关/
> 作用域是另一层配置：关闭只影响新进程，不会卸载已运行 OUC 进程里的 hook。
>
> App 每次进入前台（启动/从后台切回）或点“刷新”时，通过 root 只读查询一次管理器
> `modules_state` 和 `scope`；没有周期轮询。复制 DB/WAL 前后会做 stat 校验，源文件在复制期间
> 变化就重试，避免读到撕裂快照；stat 本身失败会返回未知，不会把“stat 失败”当成“WAL 不存在”。
> 管理器状态读取失败时显示“管理器状态读取失败，点刷新重试”，已有已知状态时会静默重试一次；
> 不会继续把旧的管理器状态当成当前状态；没有 Root 授权时管理器状态会读取失败，fresh hook
> 状态仍然可用。

App 会在每次进入前台/点“刷新”时显示 LSPosed 的状态：

| 状态 | 显示 | 点芯片 |
|---|---|---|
| 管理器里关闭、当前没有 hook 报告 | 已在管理器中关闭 | 直接打开 LSPosed 管理器 |
| 已启用、尚未注入 | 已启用，重启软件更新后生效（灰点） | 直接打开 LSPosed 管理器 |
| 已启用但作用域未勾选软件更新 | 已启用，但作用域未勾选软件更新 | 直接打开 LSPosed 管理器 |
| fresh hook 失败 | 已注入，hook 未生效，OUC 可能已更新 | 直接打开 LSPosed 管理器 |
| 本次前台收到 hook 成功报告，或本次开机曾 hook 成功 | 已激活，软件更新已返回最新 | 只提示“已激活”，不打开管理器 |
| fresh hook 成功但管理器已关闭 | 已激活（管理器已关闭，新进程不再生效） | 只提示“已激活” |
| 本次前台 2 秒内没有新报告，但本次开机曾 hook 成功 | 已激活，软件更新已返回最新 | 只提示“已激活” |
| 本次开机从未 hook 成功、OUC 尚未启动 | 已启用，重启软件更新后生效（灰点） | 直接打开 LSPosed 管理器 |
| 管理器状态读取失败 | 管理器状态读取失败，点刷新重试 | 直接打开 LSPosed 管理器 |

App 会同时读取 LSPosed 的 `scope` 表，确认 `com.hihonor.ouc` 仍在作用域；取消勾选会提示“作用域未勾选软件更新”。

> 覆盖安装新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 里重新打开并重启即可。
> App 在下一次进入前台/点刷新时显示管理器已关闭；如果管理器仍开启、只是没有 fresh 报告，
> 本次开机曾成功 hook 就仍按“已激活”显示。点 LSPosed 芯片时，已激活只提示“已激活”；
> 未激活才打开 LSPosed 管理器。

## 构建

```powershell
cd D:\win_root\nemu-update-guard
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease
.\gradlew.bat :app:assembleRelease
```

`testDebugUnitTest` 10 项通过（`ProbeStatTest` 5 项 + `StatusModelTest` 5 项）；
`lintRelease` 目前 **0 errors / 0 warnings**（中文硬编码、adaptive icon -v26、targetSdk 36
都在 `lint.xml`/manifest 里注明了有意忽略）。`.github/workflows/android.yml` 会在 push/PR 时
跑测试、lint、release 构建。Release 构建在 CI/没有 `keystore.properties` 的机器上会 fallback
到 debug 签名并打印警告。

Release APK：`app/build/outputs/apk/release/app-release.apk`。
没有 `keystore.properties` 时，release 复用 debug 签名（可覆盖现有 debug 安装，但 `debuggable=false`）；
提供 `keystore.properties`（`storeFile` 相对仓库根目录、`storePassword`、`keyAlias`、`keyPassword`）
即可换成私有签名——签名变化需要先卸载旧版。

> **安全提示**：debug 密钥是公开的。没有 `keystore.properties` 的 release 只能用于本机覆盖安装，
> **不要公开分发**；任何人都能用同一个公开密钥签一个更高 versionCode 的假更新。
> 公开分发前请配置私有 keystore，并先卸载旧版（App 的 prefs 会丢）。

当前本机已配置私有签名：`keystore/nagi-release.jks` + `keystore.properties`
（均在 `.gitignore` 中，**不要提交**），备份在 `D:\win_root\nagi-signing-backup\`。
证书 SHA-256：`1F:45:7A:B9:F6:82:68:E0:20:31:44:6C:CF:6F:F5:2A:5E:ED:AE:42:5F:AF:AB:53:B9:8E:5A:6F:04:82:61:BB`。
密码在 `keystore.properties` 里；丢失 keystore 或密码后无法覆盖更新，只能卸载重装。
安装私有签名版时已卸载旧的 debug 签名版，凪的 prefs 已清空，LSPosed 配置未动。
换签名/卸载重装后，FinaSU/KernelSU 的 allowlist 条目会随卸载删除；重新安装后需要在 App Profile
重新授权 root，并重启 OUC 让模块重新加载。否则管理器开关探测会显示“管理器状态读取失败”
（hook 状态本身不受影响）。

依赖：`compileOnly` Xposed API 82（`app/libs/api-82.jar`）。
LSPosed 入口：`assets/xposed_init` → `com.nemu.updateguard.UpdateHook`。
详细文档在 Nemu 仓库的 `docs/14-update-guard.md`。

## 开源协议

本项目采用 [Creative Commons Attribution-NonCommercial 4.0 International（CC BY-NC 4.0，署名—非商业性使用 4.0 国际）](LICENSE)，Copyright (c) 2026 JiuQing。
允许非商业性使用、修改和再分发，**禁止商业使用**；分发时请保留署名。
App 底部“开源协议”入口展示中英文官方原文（`app/src/main/res/raw/license.txt`）。

> 注意：CC BY-NC 4.0 并非 OSI 认可的软件开源许可证，且明确禁止商业使用。

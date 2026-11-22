# 凪（Nagi）

让 HONOR 软件更新（`com.hihonor.ouc`）显示“当前系统已是最新”的独立 App。
不冻结、不卸载 OUC，不修改系统版本号。

- **applicationId**：`com.nemu.windstop`（源码命名空间 `com.nemu.updateguard`）
- **版本**：`1.3.8` / `versionCode 33`
- **图标**：白底翠绿三角回环，圆点位于正中心；名字是 **凪**。
- **配套项目（Nemu / 眠梦）**：https://github.com/JiuQing1134/Nemu
- **完整地址与产物**：[`PROJECTS.md`](PROJECTS.md)

## 工作方式

- **只保留 LSPosed 模式**：hook `com.hihonor.ouc` 的
  `com.hihonor.android.hnouc.check.model.result.a.d()` 返回 false。
  作用域勾选“软件更新”，启用模块后重启一次。
- 1.3.5 起删除了独立模式（无障碍覆盖层）及 Root / Shizuku / ADB 通道。
  没有 LSPosed 时，本 App 不会拦截更新。

> “已激活”以 OUC 进程内的 hook 是否成功为准。LSPosed 管理器开关是另一层配置：
> 关闭管理器开关不会卸载已经运行的 OUC 进程中的 hook，只影响新进程。
> App 每次进入前台（启动/从后台切回）或点“刷新”时，通过 root 只读查询一次管理器的
> `modules_state` 和作用域；没有周期轮询。管理器关闭时仍显示
> “已激活”，并注明“管理器已关闭，新进程不再生效”。没有 Root 授权时只按 hook 报告显示，不影响激活状态。
> 首次授权弹窗若超过 15 秒未处理，本次探测会超时；点“刷新”可以重新发起。

App 会在每次进入前台/点“刷新”时显示 LSPosed 的状态（管理器关闭但当前 hook 仍生效时单独注明）：

| 状态 | 显示 | 点芯片 |
|---|---|---|
| 管理器里关闭、当前没有 hook 报告 | 已在管理器中关闭 | 弹说明框并跳转管理器 |
| 已启用、尚未注入 | 已启用，重启软件更新后生效 | 弹说明框并跳转管理器 |
| 已启用但作用域未勾选软件更新 | 已启用，但作用域未勾选软件更新 | 弹说明框并跳转管理器 |
| 已注入但 hook 失败 | 已注入，hook 未生效，OUC 可能已更新 | 弹说明框并跳转管理器 |
| 已注入且 hook 成功 | 已激活，软件更新已返回最新 | 打开 LSPosed 管理器 |
| hook 成功但管理器已关闭 | 已激活（管理器已关闭，新进程不再生效） | 说明新进程不再生效 |

App 会同时读取 LSPosed 的 `scope` 表，确认 `com.hihonor.ouc` 仍在作用域；取消勾选会提示“作用域未勾选软件更新”。

> 覆盖安装新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 里重新打开并重启即可。App 会在下一次探测时显示管理器已关闭，但已运行 OUC 进程的 hook 仍按 hook 优先显示。

## 构建

```powershell
cd D:\win_root\nemu-update-guard
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

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

依赖：`compileOnly` Xposed API 82（`app/libs/api-82.jar`）。
LSPosed 入口：`assets/xposed_init` → `com.nemu.updateguard.UpdateHook`。
详细文档在 Nemu 仓库的 `docs/14-update-guard.md`。

## 开源协议

本项目采用 [Creative Commons Attribution-NonCommercial 4.0 International（CC BY-NC 4.0，署名—非商业性使用 4.0 国际）](LICENSE)，Copyright (c) 2026 JiuQing。
允许非商业性使用、修改和再分发，**禁止商业使用**；分发时请保留署名。
App 底部“开源协议”入口展示中英文官方原文（`app/src/main/res/raw/license.txt`）。

> 注意：CC BY-NC 4.0 并非 OSI 认可的软件开源许可证，且明确禁止商业使用。

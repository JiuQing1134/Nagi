# 凪（Nagi）

让 HONOR 系统更新（`com.hihonor.ouc`）的检查结果显示“当前系统已是最新”的独立 App。
不冻结、不卸载 OUC，不修改系统版本号。

- **applicationId**：`com.nemu.windstop`（源码命名空间 `com.nemu.updateguard`）
- **版本**：`1.3.5` / `versionCode 11`
- **图标**：白底翠绿三角回环，圆点位于正中心；名字是 **凪**。
- **配套项目（Nemu / 眠梦）**：https://github.com/JiuQing1134/Nemu
- **完整地址与产物**：[`PROJECTS.md`](PROJECTS.md)

## 工作方式

- **只保留 LSPosed 模式**：hook `com.hihonor.ouc` 的
  `com.hihonor.android.hnouc.check.model.result.a.d()` 返回 false。
  作用域勾选“系统框架”和“软件更新”，启用模块后重启一次。
- 1.3.5 起删除了独立模式（无障碍覆盖层）及 Root / Shizuku / ADB 通道。
  没有 LSPosed 时，本 App 不会拦截更新。

App 会实时显示 LSPosed 的四种状态：

| 状态 | 显示 | 点芯片 |
|---|---|---|
| 管理器里关闭 | 已在管理器中关闭 | 弹说明框并跳转管理器 |
| 已启用、尚未注入 | 已启用，打开软件更新后生效 | 弹说明框并跳转管理器 |
| 已注入但 hook 失败 | 已注入，hook 未生效，OUC 可能已更新 | 弹说明框并跳转管理器 |
| 已注入且 hook 成功 | 已激活，更新检查返回已最新 | 提示已激活 |

> 覆盖安装新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 里重新打开并重启即可。App 会立即显示关闭状态。

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

依赖：`compileOnly` Xposed API 82（`app/libs/api-82.jar`）。
LSPosed 入口：`assets/xposed_init` → `com.nemu.updateguard.UpdateHook`。
详细文档在 Nemu 仓库的 `docs/14-update-guard.md`。

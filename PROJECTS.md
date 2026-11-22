# 两个项目的地址与产物

> 更新：2026-11-22  
> 设备：HONOR AAP-AN00，MagicOS 10.0.0.102（Android 16，SDK 36），当前槽位 `a`  
> 远程仓库：https://github.com/JiuQing1134/Nemu 与 https://github.com/JiuQing1134/Nagi（公开）。

## 1. Nemu（眠梦）

- **本地地址**：`D:\win_root\nemu`
- **GitHub**：https://github.com/JiuQing1134/Nemu
- **applicationId**：`com.nemu.dream`
- **源码命名空间**：`com.nemu.hibernate`
- **版本**：`1.3.3` / `versionCode 9`
- **APK**：`D:\win_root\nemu\app\build\outputs\apk\debug\app-debug.apk`
  - 33,375,281 字节
  - SHA-256：`811c36d6a59a6321613f43a35dc8eb0588a5b7ec4776a2d2b364e4a5af105961`
- **自启动模块**：`D:\win_root\nemu\tools\nemu-boot-autostart\nemu-boot-autostart.zip`
  - 10,043 字节
  - SHA-256：`e3320a28c9c1de004f782af6a94ee1258f5b402a45d17002be2d77cfa59dd89f`
  - 模块 ID：`nemu_boot_autostart`，v1.8；尚未安装到 FinaSU
- **文档**：
  - `D:\win_root\nemu\README.md`
  - `D:\win_root\nemu\PROJECT.md`
  - `D:\win_root\nemu\docs\README.md`
- **用途**：冻结/解冻系统更新、AI、云控/遥测、反诈、COTA 温控；不卸载系统应用。
- **图标**：白底黑色月亮。

## 2. 凪

- **本地地址**：`D:\win_root\nemu-update-guard`
- **GitHub**：https://github.com/JiuQing1134/Nagi
- **applicationId**：`com.nemu.windstop`
- **源码命名空间**：`com.nemu.updateguard`
- **版本**：`1.3.8` / `versionCode 34`
- **APK**：`D:\win_root\nemu-update-guard\app\build\outputs\apk\release\app-release.apk`
  - 2,156,026 字节
  - SHA-256：`d806f6c8da29922cf118157b8fab6455aa14e90d46ad05f4c703d2f29e2d96bd`
- **签名**：私有 keystore `keystore/nagi-release.jks` + `keystore.properties`（均被 `.gitignore` 忽略），
  备份 `D:\win_root\nagi-signing-backup\`；证书 SHA-256 `1F:45:7A:B9:F6:82:68:E0:20:31:44:6C:CF:6F:F5:2A:5E:ED:AE:42:5F:AF:AB:53:B9:8E:5A:6F:04:82:61:BB`。
  丢失 keystore/密码后无法覆盖更新，只能卸载重装。
- **开源协议**：CC BY-NC 4.0（署名—非商业性使用 4.0 国际），Copyright (c) 2026 JiuQing，见 [LICENSE](LICENSE)。
- **文档**：
  - `D:\win_root\nemu\docs\14-update-guard.md`
  - `D:\win_root\nemu\docs\15-task-status-2026-11-22.md`
- **用途**：仅通过 LSPosed 让软件更新显示“已是最新”，不冻结/不卸载 OUC，不修改系统版本号。
- **LSPosed**：作用域仅 `软件更新 (com.hihonor.ouc)`；每次进入前台/点刷新发一次 hook 报告请求，**只把本次请求后收到的 fresh 报告当作当前激活**，2 秒没有报告就显示“当前软件更新进程未上报（上次 hook 已生效）”，不再沿用旧报告。管理器开关和 `scope` 通过 root 只读查询，进入前台/点刷新时检查一次（无周期轮询）；复制 DB/WAL 前后 stat 校验，源变化就重试；读取失败显示“管理器状态读取失败，点刷新重试”。管理器关闭/作用域取消只影响新进程，保留已运行 OUC 进程的 hook 注记。
- **图标**：白底翠绿三角回环，圆点位于正中心。

## 3. 共用信息

- **参考项目**：Hail（雹）— https://github.com/aistra0528/Hail  
  仅参考 suspend 机制与公开文档，不依赖、不调用其 API/Intent/数据。
- **当前设备状态（2026-11-23）**：
  - Nemu：0 个冻结目标，OUC `suspended=false`，SafetyService 未运行。
  - 凪：私有签名版 v34 已安装（UID 10309）；LSPosed 已启用，管理器开关 `enabled=1`、作用域 `com.hihonor.ouc` 可由 root 读取；仅 LSPosed 模式，无独立模式/无障碍入口；桌面图标固定显示。卸载重装后 FinaSU allowlist 已重新授权。
  - 两个 App 均请求 120 Hz（`mActiveModeId=3 / renderFrameRate 120.00001`）。
- **注意**：覆盖安装凪的新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 中重新打开并重启即可。App 会在下一次进入前台/点刷新时显示管理器关闭；只有本次前台收到新的 hook 报告才显示“已激活”，旧报告不会继续冒充当前激活。

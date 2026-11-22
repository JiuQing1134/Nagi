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
- **版本**：`1.3.8` / `versionCode 36`
- **APK**：`D:\win_root\nemu-update-guard\app\build\outputs\apk\release\app-release.apk`
  - 2,159,534 字节
  - SHA-256：`c8e7ae31cd88934e750015ff36ee446022d51c9722474cf4ceac2ba82e5fccb7`
- **签名**：私有 keystore `keystore/nagi-release.jks` + `keystore.properties`（均被 `.gitignore` 忽略），
  备份 `D:\win_root\nagi-signing-backup\`；证书 SHA-256 `1F:45:7A:B9:F6:82:68:E0:20:31:44:6C:CF:6F:F5:2A:5E:ED:AE:42:5F:AF:AB:53:B9:8E:5A:6F:04:82:61:BB`。
  丢失 keystore/密码后无法覆盖更新，只能卸载重装。
- **开源协议**：CC BY-NC 4.0（署名—非商业性使用 4.0 国际），Copyright (c) 2026 JiuQing，见 [LICENSE](LICENSE)。
- **文档**：
  - `D:\win_root\nemu\docs\14-update-guard.md`
  - `D:\win_root\nemu\docs\15-task-status-2026-11-22.md`
- **用途**：仅通过 LSPosed 让软件更新显示“已是最新”，不冻结/不卸载 OUC，不修改系统版本号。
- **LSPosed**：作用域仅 `软件更新 (com.hihonor.ouc)`；每次进入前台/点刷新发一次 hook 报告请求，fresh 报告优先；2 秒内没有新报告时，只要本次开机曾成功 hook 就仍显示“已激活”（不再显示黄色“未上报”）。只有从未成功 hook、fresh 报告失败、管理器关闭或作用域取消才显示非激活。已激活时点 LSPosed 芯片只提示“已激活”，未激活时才打开 LSPosed 管理器。管理器开关和 `scope` 通过 root 只读查询，进入前台/点刷新时检查一次（无周期轮询）；复制 DB/WAL 前后 stat 校验，源变化就重试，stat 本身失败返回未知；读取失败显示“管理器状态读取失败，点刷新重试”，已有已知状态时静默重试一次。管理器关闭/作用域取消只影响新进程，保留已运行 OUC 进程的 hook 注记。状态分支由 `StatusModel` 统一计算，10 项 JVM 测试通过，lint 0 issues。
- **图标**：白底翠绿三角回环，圆点位于正中心。

## 3. 共用信息

- **参考项目**：Hail（雹）— https://github.com/aistra0528/Hail  
  仅参考 suspend 机制与公开文档，不依赖、不调用其 API/Intent/数据。
- **当前设备状态（2026-11-23）**：
  - Nemu：0 个冻结目标，OUC `suspended=false`，SafetyService 未运行。
  - 凪：私有签名版 v36 已安装（UID 10309）；LSPosed 已启用，管理器开关 `enabled=1`、作用域 `com.hihonor.ouc` 可由 root 读取；仅 LSPosed 模式，无独立模式/无障碍入口；桌面图标固定显示。卸载重装后 FinaSU allowlist 已重新授权。force-stop OUC 后仍显示“已激活”（本次开机曾成功 hook），启动 OUC 后刷新报告；未激活时点芯片直接打开 LSPosed，已激活时只提示“已激活”。
  - 两个 App 均请求 120 Hz（`mActiveModeId=3 / renderFrameRate 120.00001`）。
- **注意**：覆盖安装凪的新 APK 后，LSPosed 可能自动把模块开关置为关闭；到 LSPosed 中重新打开并重启即可。App 会在下一次进入前台/点刷新时显示管理器关闭；管理器仍开启、只是没有 fresh 报告时，本次开机曾成功 hook 就仍显示“已激活”。点芯片时已激活只提示“已激活”，未激活才打开 LSPosed。

# Rei 与 Murasaki Zygisk 桥接

当设备上安装了 **murasaki-zygisk-bridge**（Zygisk 桥接模块）时，Rei 可通过 system_server 中的桥接按需获取 Murasaki/Shizuku 的 Binder，而无需依赖 daemon 在本地直接注册服务。本文说明 Rei 的 service 阶段驻留进程、允许列表目录与桥接的配合方式。

## 前置条件

1. **设备已安装 murasaki-zygisk-bridge 模块**  
   在 Magisk/APatch 等环境中安装该 Zygisk 模块，并重启设备。

2. **Rei 或管理器已被授权**  
   允许列表与 Murasaki/Shizuku 白名单统一存放在 **Rei 目录** 下，桥接模块应从此处读取可注入的 UID。

## Rei 目录与允许列表

- **统一允许列表（Root）**：`/data/adb/rei/allowlist`  
  格式：每行 `UID\t包名`，由 reid 与 App 读写，并同步到当前后端（KSU/APatch）。

- **Murasaki/Shizuku 白名单（供桥接读取）**：`/data/adb/rei/.murasaki_allowlist`  
  格式：每行一个 UID。reid 在以下时机会从统一允许列表生成并写入该文件：
  - 每次 `allowlist` 写入/同步时；
  - service 阶段子进程启动时（同步到后端后写入）。

  **Zygisk 桥接模块** 应读取 `/data/adb/rei/.murasaki_allowlist`（或兼容该格式），以声明可向哪些 App（UID）注入 Murasaki/Shizuku Binder；若仍兼容旧路径 `/data/adb/ksu/.murasaki_allowlist`，可同时支持 Rei 与 YukiSU。

## Service 阶段：长时间驻留子进程

设备启动到 **service 阶段** 时，reid（由 init 执行 `reid services` 或 `ksud services`）会：

1. **Fork 一个子进程**，该子进程在后台长时间驻留。
2. 子进程内依次：
   - 确保 `/data/adb/rei` 存在；
   - 根据当前 Root 实现（ksu/apatch）将 **统一允许列表** 同步到内核/APatch 后端；
   - 将允许列表 UID 写入 **`/data/adb/rei/.murasaki_allowlist`**，供桥接模块读取；
   - 打 SEPolicy 补丁、初始化并注册 **Murasaki Binder 服务**、启动 **Shizuku 兼容服务**，并 **join Binder 线程池**（阻塞），从而常驻。

3. 父进程在 fork 后继续执行 `service` 阶段脚本并退出；**不**再在同一进程内异步启动 Murasaki（避免进程退出导致服务退出）。

因此：

- **Murasaki/Shizuku 服务** 运行在 reid 的 **子进程** 中，与 init 启动的「一次性」reid 主进程分离。
- 允许列表已 **挪到 Rei 目录**：`/data/adb/rei/allowlist` 与 `/data/adb/rei/.murasaki_allowlist`。
- 通过 **桥接模块 + Zygisk 注入**，可向白名单中的 App 声明并注入 Murasaki/Shizuku Binder；桥接侧只需按上述路径读取 UID 列表即可。

## Rei App 侧集成

- **依赖**：Rei 已依赖 `murasaki-api:api` 与 `murasaki-api:provider`，无需额外配置。
- **初始化**：在 `ReiApplication.onCreate()` 中调用 `Murasaki.init(packageName)`（后台线程）。  
  `Murasaki.init()` 会按以下顺序尝试获取 Binder：
  1. 直连 ServiceManager（reid 子进程注册的 `io.murasaki.IMurasakiService`）
  2. **通过桥接**：向 `IActivityManager` 发起 MRSK 交易码，从 Zygisk 桥接获取 Murasaki Binder
  3. Sui 服务（若存在）
  4. Shizuku（ContentProvider 等）

只要设备上装有 murasaki-zygisk-bridge 且 Rei 的 UID 在 `/data/adb/rei/.murasaki_allowlist`（或统一 allowlist）内，启动 Rei 后会自动通过桥接或直连拿到 Binder。

## 在 Rei 中使用 Murasaki API

初始化成功后，可调用：

- **权限等级**：`Murasaki.getPrivilegeLevel()`、`Murasaki.isKernelModeAvailable()`
- **Murasaki 主服务**：`Murasaki.getMurasakiService()` → `IMurasakiService`
- **扩展服务**：`Murasaki.getHymoFsService()`、`Murasaki.getKernelService()`
- **Shizuku 兼容**：桥接成功时 Murasaki 会顺带调用 `Shizuku.onBinderReceived()`，之后可直接使用 `Shizuku.newProcess()` 等 Shizuku API

## 管理其他 App 的 Murasaki/Shizuku 权限

Rei 作为管理器且已获得 `IMurasakiService` 时，可代为授权/撤销其他应用对 Murasaki（及 Shizuku 兼容 Binder）的访问：

- `IMurasakiService.grantRoot(int uid)`：将指定 UID 加入白名单（会写入统一 allowlist 并同步到 `.murasaki_allowlist`）
- `IMurasakiService.revokeRoot(int uid)`：从白名单移除
- `IMurasakiService.getRootUids()`：获取当前已授权的 UID 列表

白名单持久化在 **Rei 目录**：`/data/adb/rei/allowlist` 与 `/data/adb/rei/.murasaki_allowlist`，由 reid 子进程与 App 共同维护。

## reid 根目录：Murasaki/Shizuku 分发（ksud 与 ap 共用）

**扫描与分发逻辑** 放在 **reid 根目录**（`daemon/reid/src/murasaki_dispatch.hpp`、`murasaki_dispatch.cpp`），不依赖 ksud 或 apd 内部实现，供两者复用：

- **声明判定**：与 Sui 一致，通过 `dumpsys package <pkg>` 判断是否声明 MRSK/Shizuku（`moe.shizuku.manager.permission.API*` 或 meta `moe.shizuku.client.V3_SUPPORT` / `io.murasaki.client.SUPPORT`）。
- **流程**：从允许列表取包名 → 筛出「声明了 MRSK/Shizuku」的包 → 当前管理器优先 → 依次尝试 `包名.ui.shizuku.BinderDispatcher`，成功者设为管理器。
- **APatch** 同样可复用该逻辑；AP 还可通过 **Murasaki 做超级密钥传递**，由 **reid 发起 syscall** 与内核/APatch 交互。

## 小结

| 步骤 | 说明 |
|------|------|
| 1. 安装模块 | 在 Magisk/APatch 中安装 murasaki-zygisk-bridge，重启 |
| 2. 允许列表 | 统一列表在 `/data/adb/rei/allowlist`；桥接用 UID 列表在 `/data/adb/rei/.murasaki_allowlist` |
| 3. Service 阶段 | reid 在 `services` 时 fork 子进程，子进程注册 Murasaki/Shizuku 并常驻，同时写入 Rei 目录下的白名单 |
| 4. 启动 Rei | Rei 启动时执行 `Murasaki.init(packageName)`，通过桥接或直连获取 Binder |
| 5. 使用 API | 使用 `Murasaki.getMurasakiService()`、`getHymoFsService()`、Shizuku API 等 |
| 6. 管理权限 | 通过 `grantRoot`/`revokeRoot`/`getRootUids` 管理其他 App 的 Murasaki/Shizuku 访问，数据落在 Rei 目录 |

无需在 Rei 中做额外配置即可使用 Zygisk 桥接；桥接模块若需兼容 Rei，应优先读取 `/data/adb/rei/.murasaki_allowlist` 以声明可注入 Murasaki/Shizuku 的 App。

#pragma once

#include "core/allowlist.hpp"

#include <optional>
#include <string>
#include <vector>

namespace ksud {

/**
 * 扫描声明了 MRSK/Shizuku 的 App（与 Sui BridgeService 一致）：
 * - requestedPermissions 含 moe.shizuku.manager.permission.API*
 * - 或 ApplicationInfo.metaData 含 moe.shizuku.client.V3_SUPPORT / io.murasaki.client.SUPPORT
 * 通过 shell 调用 dumpsys package 判定。放在 reid 根目录供 ksud 与 ap 复用。
 *
 * @param candidate_packages 若非空则只检查这些包名；否则先 pm list packages 再逐个检查
 * @return 声明了 MRSK/Shizuku 的包名列表
 */
std::vector<std::string> get_packages_declaring_murasaki_shizuku(
    const std::vector<std::string>* candidate_packages = nullptr);

/**
 * 在声明了 MRSK/Shizuku 的包中尝试启动 Shizuku BinderDispatcher，成功者即“分发持有者”。
 * 若有当前管理器 UID 对应包则优先尝试。
 *
 * @param entries 统一允许列表（用于取包名并与当前管理器排序）
 * @param manager_uid 当前管理器 UID，若存在则其包名排在最前尝试
 * @return 成功执行 BinderDispatcher 的包名；若无一成功则 nullopt
 */
std::optional<std::string> dispatch_shizuku_binder_and_get_owner(
    const std::vector<AllowlistEntry>& entries,
    std::optional<uint32_t> manager_uid);

}  // namespace ksud

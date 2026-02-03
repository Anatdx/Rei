#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ksud {

using AllowlistEntry = std::pair<int32_t, std::string>;

std::vector<AllowlistEntry> allowlist_read_unified();
bool allowlist_write_unified(const std::vector<AllowlistEntry>& entries);
bool allowlist_add(int32_t uid, const std::string& package);
bool allowlist_remove(int32_t uid, const std::string& package);
bool allowlist_remove_by_uid(int32_t uid);
bool allowlist_contains_uid(int32_t uid);
std::vector<int32_t> allowlist_uids();
std::string allowlist_get_package_for_uid(int32_t uid);

void allowlist_sync_to_backend(const std::string& impl);

/** 将当前统一允许列表的 UID 写入 Rei 目录下的 Murasaki 白名单文件，供 Zygisk 桥接读取 */
void allowlist_write_murasaki_allowlist_file();

bool allowlist_grant_to_backend(int32_t uid, const std::string& package);
bool allowlist_revoke_from_backend(int32_t uid);

}  // namespace ksud

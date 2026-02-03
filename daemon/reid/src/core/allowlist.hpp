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
bool allowlist_grant_to_backend(int32_t uid, const std::string& package);
bool allowlist_revoke_from_backend(int32_t uid);

}  // namespace ksud

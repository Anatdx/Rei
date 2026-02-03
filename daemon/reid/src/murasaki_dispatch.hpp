#pragma once

#include "core/allowlist.hpp"

#include <optional>
#include <string>
#include <vector>

namespace ksud {

/**
 * Find apps declaring MRSK/Shizuku (Sui BridgeService style):
 * requestedPermissions contains moe.shizuku.manager.permission.API* or
 * metaData contains moe.shizuku.client.V3_SUPPORT / io.murasaki.client.SUPPORT.
 * Uses dumpsys package via shell. Shared by ksud and ap.
 *
 * @param candidate_packages if non-null, only check these; else pm list packages then check each
 * @return package names declaring MRSK/Shizuku
 */
std::vector<std::string> get_packages_declaring_murasaki_shizuku(
    const std::vector<std::string>* candidate_packages = nullptr);

/**
 * Among packages declaring MRSK/Shizuku, try to start Shizuku BinderDispatcher; first success is owner.
 * Prefer package matching manager_uid if set.
 *
 * @param entries allowlist for package names and manager ordering
 * @param manager_uid if set, its package is tried first
 * @return package that successfully ran BinderDispatcher, or nullopt
 */
std::optional<std::string> dispatch_shizuku_binder_and_get_owner(
    const std::vector<AllowlistEntry>& entries,
    std::optional<uint32_t> manager_uid);

}  // namespace ksud

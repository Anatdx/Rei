#include "allowlist.hpp"
#include "defs.hpp"
#include "log.hpp"
#include "utils.hpp"
#include "ksud/ksucalls.hpp"

#include <fstream>
#include <sstream>

#ifdef __ANDROID__
#include "apd/supercall.hpp"
#endif

#include <algorithm>
#include <cstdlib>
#include <cstring>

namespace ksud {

std::vector<AllowlistEntry> allowlist_read_unified() {
    std::vector<AllowlistEntry> out;
    auto content = read_file(REI_ALLOWLIST_PATH);
    if (!content)
        return out;
    auto lines = split(*content, '\n');
    for (const auto& line : lines) {
        std::string s = trim(line);
        if (s.empty())
            continue;
        size_t tab = s.find('\t');
        if (tab == std::string::npos)
            continue;
        int32_t uid = static_cast<int32_t>(std::strtol(s.substr(0, tab).c_str(), nullptr, 10));
        std::string pkg = trim(s.substr(tab + 1));
        if (pkg.empty())
            continue;
        out.emplace_back(uid, pkg);
    }
    return out;
}

bool allowlist_write_unified(const std::vector<AllowlistEntry>& entries) {
    if (!ensure_dir_exists(REI_DIR)) {
        LOGE("allowlist: failed to create %s", REI_DIR);
        return false;
    }
    std::ostringstream oss;
    for (const auto& e : entries) {
        oss << e.first << '\t' << e.second << '\n';
    }
    return write_file(REI_ALLOWLIST_PATH, oss.str());
}

bool allowlist_add(int32_t uid, const std::string& package) {
    auto entries = allowlist_read_unified();
    auto it = std::find_if(entries.begin(), entries.end(),
                           [uid, &package](const AllowlistEntry& e) {
                               return e.first == uid && e.second == package;
                           });
    if (it != entries.end())
        return true;
    entries.emplace_back(uid, package);
    return allowlist_write_unified(entries);
}

bool allowlist_remove(int32_t uid, const std::string& package) {
    auto entries = allowlist_read_unified();
    auto it = std::remove_if(entries.begin(), entries.end(),
                             [uid, &package](const AllowlistEntry& e) {
                                 return e.first == uid && e.second == package;
                             });
    if (it == entries.end())
        return true;
    entries.erase(it, entries.end());
    return allowlist_write_unified(entries);
}

bool allowlist_remove_by_uid(int32_t uid) {
    auto entries = allowlist_read_unified();
    auto it = std::remove_if(entries.begin(), entries.end(),
                             [uid](const AllowlistEntry& e) { return e.first == uid; });
    if (it == entries.end())
        return true;
    entries.erase(it, entries.end());
    return allowlist_write_unified(entries);
}

bool allowlist_contains_uid(int32_t uid) {
    auto entries = allowlist_read_unified();
    return std::any_of(entries.begin(), entries.end(),
                       [uid](const AllowlistEntry& e) { return e.first == uid; });
}

std::vector<int32_t> allowlist_uids() {
    auto entries = allowlist_read_unified();
    std::vector<int32_t> uids;
    uids.reserve(entries.size());
    for (const auto& e : entries)
        uids.push_back(e.first);
    std::sort(uids.begin(), uids.end());
    uids.erase(std::unique(uids.begin(), uids.end()), uids.end());
    return uids;
}

std::string allowlist_get_package_for_uid(int32_t uid) {
    ExecResult r = exec_command({"/system/bin/cmd", "package", "list", "packages", "-U"});
    if (r.exit_code != 0)
        return "";
    auto lines = split(r.stdout_str, '\n');
    for (const auto& raw : lines) {
        std::string line = trim(raw);
        if (line.empty() || !starts_with(line, "package:"))
            continue;
        std::string rest = line.substr(8);
        size_t sp = rest.find(' ');
        std::string pkg = sp != std::string::npos ? trim(rest.substr(0, sp)) : trim(rest);
        if (pkg.empty())
            continue;
        size_t pos = line.find("uid:");
        if (pos == std::string::npos)
            continue;
        size_t start = line.find(':', pos);
        if (start == std::string::npos)
            continue;
        start++;
        size_t end = line.find_first_of(" \t\r\n", start);
        std::string uid_str = (end == std::string::npos) ? line.substr(start) : line.substr(start, end - start);
        uid_str = trim(uid_str);
        if (uid_str.empty())
            continue;
        if (static_cast<int32_t>(std::strtol(uid_str.c_str(), nullptr, 10)) == uid)
            return pkg;
    }
    return "";
}

static void sync_to_ksu(const std::vector<AllowlistEntry>& entries) {
    std::vector<uint32_t> current = get_allow_list(true);
    if (!current.empty()) {
        ExecResult r = exec_command({"/system/bin/cmd", "package", "list", "packages", "-U"});
        if (r.exit_code == 0) {
            auto lines = split(r.stdout_str, '\n');
            for (const auto& raw : lines) {
                std::string line = trim(raw);
                if (line.empty() || !starts_with(line, "package:"))
                    continue;
                std::string rest = line.substr(8);
                size_t sp = rest.find(' ');
                if (sp != std::string::npos)
                    rest = rest.substr(0, sp);
                std::string pkg = trim(rest);
                if (pkg.empty())
                    continue;
                uint32_t uid = 0;
                size_t pos = line.find("uid:");
                if (pos != std::string::npos) {
                    size_t start = line.find(':', pos);
                    if (start != std::string::npos) {
                        start++;
                        size_t end = line.find_first_of(" \t\r\n", start);
                        std::string uid_str = (end == std::string::npos)
                                                  ? line.substr(start)
                                                  : line.substr(start, end - start);
                        uid_str = trim(uid_str);
                        if (!uid_str.empty())
                            uid = static_cast<uint32_t>(std::strtoul(uid_str.c_str(), nullptr, 10));
                    }
                }
                if (std::find(current.begin(), current.end(), uid) != current.end()) {
                    AppProfile p{};
                    memset(&p, 0, sizeof(p));
                    p.version = KSU_APP_PROFILE_VER;
                    strncpy(p.key, pkg.c_str(), KSU_MAX_PACKAGE_NAME - 1);
                    p.current_uid = static_cast<int32_t>(uid);
                    p.allow_su = 0;
                    set_app_profile(p);
                }
            }
        }
    }
    for (const auto& e : entries) {
        AppProfile p{};
        memset(&p, 0, sizeof(p));
        p.version = KSU_APP_PROFILE_VER;
        strncpy(p.key, e.second.c_str(), KSU_MAX_PACKAGE_NAME - 1);
        p.current_uid = e.first;
        p.allow_su = 1;
        if (set_app_profile(p) < 0) {
            LOGW("allowlist sync ksu: set_app_profile %d %s failed", e.first, e.second.c_str());
        }
    }
    LOGI("allowlist sync to KSU: %zu entries", entries.size());
}

static void sync_to_apatch(const std::vector<AllowlistEntry>& entries) {
#ifdef __ANDROID__
    auto key_opt = read_file(REI_SUPERKEY_PATH);
    std::string key = key_opt ? trim(*key_opt) : "";
    if (key.empty()) {
        LOGW("allowlist sync apatch: no superkey at %s, skip", REI_SUPERKEY_PATH);
        return;
    }
    long num = apd::ScSuUidNums(key);
    if (num > 0) {
        std::vector<int> uids(static_cast<size_t>(num), 0);
        long n = apd::ScSuAllowUids(key, uids);
        if (n > 0) {
            for (int i = 0; i < n; i++) {
                int uid = uids[static_cast<size_t>(i)];
                if (uid == 0 || uid == 2000)
                    continue;
                apd::ScSuRevokeUid(key, uid);
            }
        }
    }
    apd::SuProfile profile{};
    profile.uid = 0;
    profile.to_uid = 0;
    memset(profile.scontext, 0, sizeof(profile.scontext));
    for (const auto& e : entries) {
        profile.uid = e.first;
        profile.to_uid = 0;
        long ret = apd::ScSuGrantUid(key, profile);
        if (ret != 0) {
            LOGW("allowlist sync apatch: ScSuGrantUid %d failed: %ld", e.first, ret);
        }
    }
    LOGI("allowlist sync to APatch: %zu entries", entries.size());
#else
    (void)entries;
#endif
}

void allowlist_sync_to_backend(const std::string& impl) {
    std::vector<AllowlistEntry> entries = allowlist_read_unified();
    if (impl == "apatch") {
        sync_to_apatch(entries);
    } else {
        sync_to_ksu(entries);
    }
}

static std::string get_root_impl() {
    auto opt = read_file(ROOT_IMPL_CONFIG_PATH);
    return opt ? trim(*opt) : "ksu";
}

bool allowlist_grant_to_backend(int32_t uid, const std::string& package) {
    std::string impl = get_root_impl();
    if (impl == "apatch") {
#ifdef __ANDROID__
        auto key_opt = read_file(REI_SUPERKEY_PATH);
        std::string key = key_opt ? trim(*key_opt) : "";
        if (key.empty())
            return false;
        apd::SuProfile profile{};
        profile.uid = uid;
        profile.to_uid = 0;
        memset(profile.scontext, 0, sizeof(profile.scontext));
        return apd::ScSuGrantUid(key, profile) == 0;
#else
        (void)uid;
        (void)package;
        return false;
#endif
    }
    AppProfile p{};
    memset(&p, 0, sizeof(p));
    p.version = KSU_APP_PROFILE_VER;
    strncpy(p.key, package.c_str(), KSU_MAX_PACKAGE_NAME - 1);
    p.current_uid = uid;
    p.allow_su = 1;
    return set_app_profile(p) >= 0;
}

bool allowlist_revoke_from_backend(int32_t uid) {
    std::string impl = get_root_impl();
    if (impl == "apatch") {
#ifdef __ANDROID__
        auto key_opt = read_file(REI_SUPERKEY_PATH);
        std::string key = key_opt ? trim(*key_opt) : "";
        if (key.empty())
            return false;
        return apd::ScSuRevokeUid(key, uid) == 0;
#else
        (void)uid;
        return false;
#endif
    }
    ExecResult r = exec_command({"/system/bin/cmd", "package", "list", "packages", "-U"});
    if (r.exit_code != 0)
        return false;
    bool ok = true;
    auto lines = split(r.stdout_str, '\n');
    for (const auto& raw : lines) {
        std::string line = trim(raw);
        if (line.empty() || !starts_with(line, "package:"))
            continue;
        std::string rest = line.substr(8);
        size_t sp = rest.find(' ');
        std::string pkg = sp != std::string::npos ? trim(rest.substr(0, sp)) : trim(rest);
        if (pkg.empty())
            continue;
        size_t pos = line.find("uid:");
        if (pos == std::string::npos)
            continue;
        size_t start = line.find(':', pos);
        if (start == std::string::npos)
            continue;
        start++;
        size_t end = line.find_first_of(" \t\r\n", start);
        std::string uid_str = (end == std::string::npos) ? line.substr(start) : line.substr(start, end - start);
        uid_str = trim(uid_str);
        if (uid_str.empty())
            continue;
        int32_t line_uid = static_cast<int32_t>(std::strtol(uid_str.c_str(), nullptr, 10));
        if (line_uid != uid)
            continue;
        AppProfile p{};
        memset(&p, 0, sizeof(p));
        p.version = KSU_APP_PROFILE_VER;
        strncpy(p.key, pkg.c_str(), KSU_MAX_PACKAGE_NAME - 1);
        p.current_uid = uid;
        p.allow_su = 0;
        if (set_app_profile(p) < 0)
            ok = false;
    }
    return ok;
}

}  // namespace ksud

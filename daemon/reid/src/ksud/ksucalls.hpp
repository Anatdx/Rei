#pragma once

#include <cstdint>
#include <optional>
#include <vector>
#include <string>
#include <utility>

namespace ksud {

// ioctl macros - cast to unsigned to avoid overflow warnings
// Note: size field is 0 to match kernel _IOC(..., 0) definitions
#define _IOC(dir, type, nr, size) \
    (static_cast<uint32_t>(((dir) << 30) | ((type) << 8) | (nr) | ((size) << 16)))
#define _IO(type, nr) _IOC(0, type, nr, 0)
#define _IOR(type, nr, sz) _IOC(2, type, nr, 0)
#define _IOW(type, nr, sz) _IOC(1, type, nr, 0)
#define _IOWR(type, nr, sz) _IOC(3, type, nr, 0)

constexpr uint32_t K = 'K';

// ioctl commands
constexpr uint32_t KSU_IOCTL_GRANT_ROOT = _IO(K, 1);
constexpr uint32_t KSU_IOCTL_GET_INFO = _IOR(K, 2, uint64_t);
constexpr uint32_t KSU_IOCTL_REPORT_EVENT = _IOW(K, 3, uint64_t);
constexpr uint32_t KSU_IOCTL_SET_SEPOLICY = _IOWR(K, 4, uint64_t);
constexpr uint32_t KSU_IOCTL_CHECK_SAFEMODE = _IOR(K, 5, uint64_t);
constexpr uint32_t KSU_IOCTL_GET_ALLOW_LIST = _IOWR(K, 6, uint64_t);
constexpr uint32_t KSU_IOCTL_GET_DENY_LIST = _IOWR(K, 7, uint64_t);
constexpr uint32_t KSU_IOCTL_UID_GRANTED_ROOT = _IOWR(K, 8, uint64_t);
constexpr uint32_t KSU_IOCTL_UID_SHOULD_UMOUNT = _IOWR(K, 9, uint64_t);
constexpr uint32_t KSU_IOCTL_GET_MANAGER_UID = _IOR(K, 10, uint64_t);
constexpr uint32_t KSU_IOCTL_GET_APP_PROFILE = _IOWR(K, 11, uint64_t);
constexpr uint32_t KSU_IOCTL_SET_APP_PROFILE = _IOW(K, 12, uint64_t);
constexpr uint32_t KSU_IOCTL_GET_FEATURE = _IOWR(K, 13, uint64_t);
constexpr uint32_t KSU_IOCTL_SET_FEATURE = _IOW(K, 14, uint64_t);
constexpr uint32_t KSU_IOCTL_GET_WRAPPER_FD = _IOW(K, 15, uint64_t);
constexpr uint32_t KSU_IOCTL_MANAGE_MARK = _IOWR(K, 16, uint64_t);
constexpr uint32_t KSU_IOCTL_NUKE_EXT4_SYSFS = _IOW(K, 17, uint64_t);
constexpr uint32_t KSU_IOCTL_ADD_TRY_UMOUNT = _IOW(K, 18, uint64_t);
constexpr uint32_t KSU_IOCTL_LIST_TRY_UMOUNT = _IOWR(K, 200, uint64_t);

// Structures for ioctl - use natural C alignment (matching kernel and Rust repr(C))
// Do NOT use #pragma pack(1) as it would misalign structures with the kernel!

struct GetInfoCmd {
    uint32_t version;
    uint32_t flags;
    uint32_t features;  // max feature ID supported
};

struct ReportEventCmd {
    uint32_t event;
};

struct SetSepolicyCmd {
    uint64_t cmd;
    uint64_t arg;
};

struct CheckSafemodeCmd {
    uint8_t in_safe_mode;
};

struct GetAllowListCmd {
    uint32_t uids[128];
    uint32_t count;
    uint8_t allow;
};

struct UidGrantedRootCmd {
    uint32_t uid;
    uint8_t granted;
};

struct UidShouldUmountCmd {
    uint32_t uid;
    uint8_t should_umount;
};

struct GetManagerUidCmd {
    uint32_t uid;
};

// Matches kernel's app_profile.h (KSU_APP_PROFILE_VER=2, KSU_MAX_PACKAGE_NAME=256)
static constexpr uint32_t KSU_APP_PROFILE_VER = 2;
static constexpr size_t KSU_MAX_PACKAGE_NAME = 256;
static constexpr size_t KSU_SELINUX_DOMAIN = 64;
static constexpr size_t KSU_MAX_GROUPS = 32;

struct RootProfile {
    int32_t uid;
    int32_t gid;
    int32_t groups_count;
    int32_t groups[KSU_MAX_GROUPS];
    struct {
        uint64_t effective;
        uint64_t permitted;
        uint64_t inheritable;
    } capabilities;
    char selinux_domain[KSU_SELINUX_DOMAIN];
    int32_t namespaces;
};

struct NonRootProfile {
    uint8_t umount_modules;
};

struct AppProfile {
    uint32_t version;
    char key[KSU_MAX_PACKAGE_NAME];
    int32_t current_uid;
    uint8_t allow_su;
    // rely on compiler padding/alignment to match kernel layout
    union {
        RootProfile root;
        NonRootProfile non_root;
    };
};

struct GetAppProfileCmd {
    AppProfile profile;
};

struct SetAppProfileCmd {
    AppProfile profile;
};

struct GetFeatureCmd {
    uint32_t feature_id;
    uint64_t value;
    uint8_t supported;
};

struct SetFeatureCmd {
    uint32_t feature_id;
    uint64_t value;
};

struct GetWrapperFdCmd {
    int32_t fd;
    uint32_t flags;
};

struct ManageMarkCmd {
    uint32_t operation;
    int32_t pid;
    uint32_t result;
};

struct NukeExt4SysfsCmd {
    uint64_t arg;
};

struct AddTryUmountCmd {
    uint64_t arg;
    uint32_t flags;
    uint8_t mode;
};

struct ListTryUmountCmd {
    uint64_t arg;
    uint32_t buf_size;
};

// API functions
int ksuctl(int request, void* arg);

int32_t get_version();
uint32_t get_flags();

int grant_root();
void report_post_fs_data();
void report_boot_complete();
void report_module_mounted();
bool check_kernel_safemode();

int set_sepolicy(const SetSepolicyCmd& cmd);

std::vector<uint32_t> get_allow_list(bool allow);
bool uid_granted_root(uint32_t uid);
bool uid_should_umount(uint32_t uid);
std::optional<uint32_t> get_manager_uid();
std::optional<AppProfile> get_app_profile(uint32_t uid, const std::string& key);
int set_app_profile(const AppProfile& profile);

// Feature management
// Returns: pair<value, supported>
std::pair<uint64_t, bool> get_feature(uint32_t feature_id);
int set_feature(uint32_t feature_id, uint64_t value);

int get_wrapped_fd(int fd);

// Mark management
uint32_t mark_get(int32_t pid);
int mark_set(int32_t pid);
int mark_unset(int32_t pid);
int mark_refresh();

int nuke_ext4_sysfs(const std::string& mnt);

// Umount list management
int umount_list_wipe();
int umount_list_add(const std::string& path, uint32_t flags);
int umount_list_del(const std::string& path);
std::optional<std::string> umount_list_list();

}  // namespace ksud

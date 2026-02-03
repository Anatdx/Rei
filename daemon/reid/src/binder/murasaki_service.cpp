// Murasaki Service - Binder Service Implementation
// KernelSU kernel API server

#include "murasaki_service.hpp"
#include "../ksud/ksucalls.hpp"
#include "../defs.hpp"
#include "../log.hpp"
#include "../ksud/profile/profile.hpp"
#include "../ksud/sepolicy/sepolicy.hpp"
#include "umount.hpp"

#include <poll.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <atomic>
#include <mutex>
#include <thread>

namespace ksud {
namespace murasaki {

// Murasaki service version
static constexpr int MURASAKI_VERSION = 1;

// Unix socket path (interim; can switch to real Binder later)
static constexpr const char* MURASAKI_SOCKET_PATH = "/dev/socket/murasaki";

// Global service instance
static std::atomic<bool> g_service_running{false};
static std::thread g_service_thread;
static std::mutex g_service_mutex;

// ==================== Helpers ====================

static bool is_ksu_available() {
    return get_version() > 0;
}

static int get_ksu_version() {
    return get_version();
}

static bool is_uid_granted_root(int uid) {
    // TODO: check via ioctl
    return uid == 0;
}

static bool is_uid_should_umount(int uid) {
    // TODO: check via ioctl
    return false;
}

static bool apply_sepolicy_rules(const std::string& rules) {
    // TODO: call sepolicy module
    return false;
}

static bool nuke_ext4_sysfs() {
    (void)0;
    return false;  // HymoFS removed, no-op
}

// ==================== MurasakiService impl ====================

MurasakiService& MurasakiService::getInstance() {
    static MurasakiService instance;
    return instance;
}

int MurasakiService::init() {
    if (initialized_) {
        LOGW("MurasakiService already initialized");
        return 0;
    }

    LOGI("MurasakiService: Initializing...");

    if (!is_ksu_available()) {
        LOGE("MurasakiService: KernelSU not available!");
        return -1;
    }

    // TODO: real Binder registration; Unix socket interim

    initialized_ = true;
    LOGI("MurasakiService: Initialized successfully");
    return 0;
}

// run() is in murasaki_ipc.cpp

void MurasakiService::stop() {
    running_ = false;
}

bool MurasakiService::isRunning() const {
    return running_;
}

// ==================== Service API impl ====================

int MurasakiService::getVersion() {
    return MURASAKI_VERSION;
}

int MurasakiService::getKernelSuVersion() {
    return get_ksu_version();
}

PrivilegeLevel MurasakiService::getPrivilegeLevel(int callingUid) {
    if (is_uid_granted_root(callingUid)) {
        return PrivilegeLevel::ROOT;  // HymoFS removed, no KERNEL
    }
    return PrivilegeLevel::SHELL;
}

bool MurasakiService::isKernelModeAvailable() {
    return false;  // HymoFS removed
}

std::string MurasakiService::getSelinuxContext(int pid) {
    char buf[256] = {0};
    std::string path = "/proc/" + std::to_string(pid == 0 ? getpid() : pid) + "/attr/current";

    FILE* f = fopen(path.c_str(), "r");
    if (f) {
        if (fgets(buf, sizeof(buf), f)) {
            char* nl = strchr(buf, '\n');
            if (nl)
                *nl = '\0';
        }
        fclose(f);
    }
    return std::string(buf);
}

int MurasakiService::setSelinuxContext(const std::string& context) {
    // TODO: set via ioctl (kernel support required)
    LOGW("MurasakiService::setSelinuxContext not implemented yet");
    return -ENOSYS;
}

// ==================== HymoFS (stub, removed) ====================

int MurasakiService::hymoAddRule(const std::string&, const std::string&, int) {
    return -ENOSYS;
}

int MurasakiService::hymoClearRules() {
    return 0;
}

int MurasakiService::hymoSetStealth(bool) {
    return -ENOSYS;
}

int MurasakiService::hymoSetDebug(bool) {
    return -ENOSYS;
}

int MurasakiService::hymoSetMirrorPath(const std::string&) {
    return -ENOSYS;
}

int MurasakiService::hymoFixMounts() {
    return -ENOSYS;
}

std::string MurasakiService::hymoGetActiveRules() {
    return "";
}

// ==================== KSU ops ====================

std::string MurasakiService::getAppProfile(int uid) {
    // TODO: get from profile module
    return "";
}

int MurasakiService::setAppProfile(int uid, const std::string& profileJson) {
    // TODO: set profile
    return -ENOSYS;
}

bool MurasakiService::isUidGrantedRoot(int uid) {
    return is_uid_granted_root(uid);
}

bool MurasakiService::shouldUmountForUid(int uid) {
    return is_uid_should_umount(uid);
}

int MurasakiService::injectSepolicy(const std::string& rules) {
    return apply_sepolicy_rules(rules) ? 0 : -1;
}

int MurasakiService::addTryUmount(const std::string& path) {
    // TODO: implement
    return -ENOSYS;
}

int MurasakiService::nukeExt4Sysfs() {
    return nuke_ext4_sysfs() ? 0 : -1;
}

// ==================== Global helpers ====================

void start_murasaki_service_async() {
    std::lock_guard<std::mutex> lock(g_service_mutex);

    if (g_service_running.load()) {
        LOGW("Murasaki service already running");
        return;
    }

    g_service_thread = std::thread([]() {
        auto& service = MurasakiService::getInstance();
        if (service.init() == 0) {
            g_service_running.store(true);
            service.run();
        }
        g_service_running.store(false);
    });

    // Detach thread to run in background
    g_service_thread.detach();

    LOGI("Murasaki service started in background");
}

void stop_murasaki_service() {
    std::lock_guard<std::mutex> lock(g_service_mutex);

    if (!g_service_running.load()) {
        return;
    }

    MurasakiService::getInstance().stop();
    LOGI("Murasaki service stopped");
}

bool is_murasaki_service_available() {
    return g_service_running.load();
}

}  // namespace murasaki
}  // namespace ksud

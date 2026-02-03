#include "init_event.hpp"
#include "assets.hpp"
#include "core/allowlist.hpp"
#include "binder/murasaki_binder.hpp"
#include "binder/shizuku_service.hpp"
#include "core/hide_bootloader.hpp"
#include "core/restorecon.hpp"
#include "defs.hpp"
#include "log.hpp"
#include "murasaki_dispatch.hpp"
#include "utils.hpp"
#include "ksud/boot/boot_patch.hpp"
#include "ksud/debug.hpp"
#include "ksud/feature.hpp"
#include "ksud/ksucalls.hpp"
#include "ksud/module/metamodule.hpp"
#include "ksud/module/module.hpp"
#include "ksud/module/module_config.hpp"
#include "ksud/profile/profile.hpp"
#include "ksud/sepolicy/sepolicy.hpp"
#include "ksud/umount.hpp"

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <cstring>

namespace ksud {

// Load Murasaki Binder service SEPolicy rules
static void load_murasaki_sepolicy() {
    const uint8_t* data = nullptr;
    size_t size = 0;

    if (!get_asset("murasaki_sepolicy.rule", data, size)) {
        LOGW("Failed to get murasaki_sepolicy.rule asset");
        return;
    }

    std::string rules(reinterpret_cast<const char*>(data), size);
    LOGI("Loading Murasaki SEPolicy rules...");

    int ret = sepolicy_live_patch(rules);
    if (ret != 0) {
        LOGW("Failed to apply Murasaki sepolicy rules: %d", ret);
    } else {
        LOGI("Murasaki SEPolicy rules applied successfully");
    }
}

// Catch boot logs (logcat/dmesg) to file
static void catch_bootlog(const char* logname, const std::vector<const char*>& command) {
    ensure_dir_exists(LOG_DIR);

    std::string bootlog = std::string(LOG_DIR) + "/" + logname + ".log";
    std::string oldbootlog = std::string(LOG_DIR) + "/" + logname + ".old.log";

    // Rotate old log
    if (access(bootlog.c_str(), F_OK) == 0) {
        rename(bootlog.c_str(), oldbootlog.c_str());
    }

    // Fork and exec timeout command
    pid_t pid = fork();
    if (pid < 0) {
        LOGW("Failed to fork for %s: %s", logname, strerror(errno));
        return;
    }

    if (pid == 0) {
        // Child process
        // Create new process group
        setpgid(0, 0);

        // Switch cgroups
        switch_cgroups();

        // Open log file for stdout
        int fd = open(bootlog.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd < 0) {
            _exit(1);
        }
        dup2(fd, STDOUT_FILENO);
        close(fd);

        // Build argv: timeout -s 9 30s <command...>
        std::vector<const char*> argv;
        argv.push_back("timeout");
        argv.push_back("-s");
        argv.push_back("9");
        argv.push_back("30s");
        for (const char* arg : command) {
            argv.push_back(arg);
        }
        argv.push_back(nullptr);

        execvp("timeout", const_cast<char* const*>(argv.data()));
        _exit(127);
    }

    // Parent: don't wait, let it run in background
    LOGI("Started %s capture (pid %d)", logname, pid);
}

static void run_stage(const std::string& stage, bool block) {
    umask(0);

    // Check for Magisk (like Rust version)
    if (has_magisk()) {
        LOGW("Magisk detected, skip %s", stage.c_str());
        return;
    }

    if (is_safe_mode()) {
        LOGW("safe mode, skip %s scripts", stage.c_str());
        return;
    }

    // Execute common scripts first
    exec_common_scripts(stage + ".d", block);

    // Execute metamodule stage script (priority)
    metamodule_exec_stage_script(stage, block);

    // Execute regular modules stage scripts
    exec_stage_script(stage, block);
}

int on_post_data_fs() {
    LOGI("post-fs-data triggered");

    // Report to kernel first
    report_post_fs_data();

    umask(0);

    // Clear all temporary module configs early (like Rust version)
    clear_all_temp_configs();

    // Catch boot logs
    catch_bootlog("logcat", {"logcat", "-b", "all"});
    catch_bootlog("dmesg", {"dmesg", "-w"});

    // Check for Magisk (like Rust version)
    if (has_magisk()) {
        LOGW("Magisk detected, skip post-fs-data!");
        return 0;
    }

    // Check for safe mode FIRST (like Rust version)
    bool safe_mode = is_safe_mode();

    if (safe_mode) {
        LOGW("safe mode, skip common post-fs-data.d scripts");
    } else {
        // Execute common post-fs-data scripts
        exec_common_scripts("post-fs-data.d", true);
    }

    // Ensure directories exist
    ensure_dir_exists(WORKING_DIR);
    ensure_dir_exists(MODULE_DIR);
    ensure_dir_exists(LOG_DIR);
    ensure_dir_exists(PROFILE_DIR);

    // Ensure binaries exist in current backend bin (AFTER safe mode check)
    auto root_impl = read_file(ROOT_IMPL_CONFIG_PATH);
    std::string impl = root_impl ? trim(*root_impl) : "";
    const char* active_bin_dir = (impl == "apatch") ? AP_BIN_DIR : KSU_BIN_DIR;
    if (ensure_binaries(active_bin_dir, true) != 0) {
        LOGW("Failed to ensure binaries");
    }

    // if we are in safe mode, we should disable all modules
    if (safe_mode) {
        LOGW("safe mode, skip post-fs-data scripts and disable all modules!");
        disable_all_modules();
        return 0;
    }

    // Handle updated modules
    handle_updated_modules();

    // Prune modules marked for removal
    prune_modules();

    // Restorecon
    restorecon("/data/adb", true);

    // Load sepolicy rules from modules
    load_sepolicy_rule();

    // Load Murasaki Binder service sepolicy rules
    load_murasaki_sepolicy();

    // Apply profile sepolicies
    apply_profile_sepolies();

    // Load feature config (with init_features handling managed features)
    init_features();

    // Execute metamodule post-fs-data script first (priority)
    metamodule_exec_stage_script("post-fs-data", true);

    // Execute module post-fs-data scripts
    exec_stage_script("post-fs-data", true);

    // Load system.prop from modules
    load_system_prop();

    // Execute metamodule mount script
    metamodule_exec_mount_script();

    // Load umount config and apply to kernel
    umount_apply_config();

    // Run post-mount stage
    run_stage("post-mount", true);

    chdir("/");

    LOGI("post-fs-data completed");
    return 0;
}

void on_services() {
    LOGI("services triggered");

    // Hide bootloader unlock status (soft BL hiding)
    hide_bootloader_status();

    // Long-lived child: register Murasaki service, write allowlist to Rei dir for Zygisk bridge
    pid_t pid = fork();
    if (pid < 0) {
        LOGW("Failed to fork Murasaki daemon: %s", strerror(errno));
        run_stage("service", false);
        return;
    }
    if (pid == 0) {
        // Child: become Murasaki/Shizuku service process, run in background
        (void)ensure_dir_exists(REI_DIR);
        auto root_impl_opt = read_file(ROOT_IMPL_CONFIG_PATH);
        std::string root_impl = root_impl_opt ? trim(*root_impl_opt) : "ksu";
        allowlist_sync_to_backend(root_impl);  // sync to backend and write /data/adb/rei/.murasaki_allowlist
        LOGI("Murasaki daemon child started (pid %d), joining Binder pool...", getpid());
        _exit(run_daemon());
    }
    LOGI("Murasaki daemon forked (child pid %d)", pid);

    run_stage("service", false);
    LOGI("services completed");
}

void on_boot_completed() {
    LOGI("boot-completed triggered");

    // Report to kernel
    report_boot_complete();

    // Run boot-completed stage
    run_stage("boot-completed", false);

    // murasaki_dispatch: scan MRSK/Shizuku apps (Sui style), try BinderDispatcher, elevate first success as manager; shared by ksud/ap
    LOGI("Dispatching Shizuku Binder to apps...");
    std::vector<AllowlistEntry> entries = allowlist_read_unified();
    std::optional<std::string> owner =
        dispatch_shizuku_binder_and_get_owner(entries, get_manager_uid());
    if (owner) {
        LOGI("Shizuku dispatch owner: %s", owner->c_str());
        debug_set_manager(*owner);
    }

    LOGI("boot-completed completed");
}

int run_daemon() {
    LOGI("Starting ksud daemon...");

    // Switch to global mount namespace
    // This is crucial for visibility across Apps
    if (!switch_mnt_ns(1)) {
        LOGE("Failed to switch to global mount namespace (PID 1)");
    } else {
        LOGI("Switched to global mount namespace");
    }

    auto root_impl_opt = read_file(ROOT_IMPL_CONFIG_PATH);
    std::string root_impl = root_impl_opt ? trim(*root_impl_opt) : "ksu";
    allowlist_sync_to_backend(root_impl);

    // Patch SEPolicy to allow Binder communication
    // Essential for App <-> ksud (su domain) communication
    // And for allowing Apps to find the service (which defaults to default_android_service type)
    LOGI("Patching SEPolicy for Binder service...");
    const char* rules =
        "allow appdomain su binder { call transfer };"
        "allow shell su binder { call transfer };"
        "allow su appdomain binder { call transfer };"
        "allow su shell binder { call transfer };"
        "allow appdomain default_android_service service_manager find;"
        "allow shell default_android_service service_manager find;"
        // Also allow untrusted_app explicitly just in case appdomain is not sufficient
        "allow untrusted_app_all su binder { call transfer };"
        "allow untrusted_app_all default_android_service service_manager find;";

    int sepolicy_ret = sepolicy_live_patch(rules);
    if (sepolicy_ret != 0) {
        LOGE("Failed to patch SEPolicy: %d", sepolicy_ret);
    } else {
        LOGI("SEPolicy patched successfully");
    }

    LOGI("Initializing Murasaki Binder service...");
    int ret = murasaki::MurasakiBinderService::getInstance().init();
    if (ret != 0) {
        LOGE("Failed to init Murasaki service: %d", ret);
    }

    LOGI("Initializing Shizuku compatible service...");
    shizuku::start_shizuku_service();

    LOGI("Joining Binder thread pool...");
    murasaki::MurasakiBinderService::getInstance().joinThreadPool();

    return 0;
}

}  // namespace ksud

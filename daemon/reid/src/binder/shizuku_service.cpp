// Shizuku compat service - C++ native impl via libbinder_ndk

#include "shizuku_service.hpp"
#include "../core/allowlist.hpp"
#include "../ksud/ksucalls.hpp"
#include "../log.hpp"
#include "binder_wrapper.hpp"

#include <android/binder_parcel.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <fstream>

// System property access
extern "C" {
int __system_property_get(const char* name, char* value);
int __system_property_set(const char* name, const char* value);
}

namespace ksud {
namespace shizuku {

// Use murasaki BinderWrapper
using murasaki::BinderWrapper;

// Binder callbacks (shared)
static void* Binder_onCreate(void* args) {
    return args;
}

static void Binder_onDestroy(void* userData) {
    (void)userData;
}

// ==================== RemoteProcessHolder ====================

AIBinder_Class* RemoteProcessHolder::binderClass_ = nullptr;

RemoteProcessHolder::RemoteProcessHolder(pid_t pid, int stdin_fd, int stdout_fd, int stderr_fd)
    : pid_(pid),
      stdin_fd_(stdin_fd),
      stdout_fd_(stdout_fd),
      stderr_fd_(stderr_fd),
      exit_code_(-1),
      exited_(false) {
    auto& bw = BinderWrapper::instance();

    // Create Binder class (once)
    if (!binderClass_ && bw.AIBinder_Class_define) {
        binderClass_ = bw.AIBinder_Class_define(REMOTE_PROCESS_DESCRIPTOR, Binder_onCreate,
                                                Binder_onDestroy, RemoteProcessHolder::onTransact);
    }

    // Create Binder instance
    if (bw.AIBinder_new) {
        binder_ = bw.AIBinder_new(binderClass_, this);
    }
}

RemoteProcessHolder::~RemoteProcessHolder() {
    destroy();
    if (stdin_fd_ >= 0)
        close(stdin_fd_);
    if (stdout_fd_ >= 0)
        close(stdout_fd_);
    if (stderr_fd_ >= 0)
        close(stderr_fd_);
    if (binder_) {
        auto& bw = BinderWrapper::instance();
        if (bw.AIBinder_decStrong)
            bw.AIBinder_decStrong(binder_);
    }
}

int RemoteProcessHolder::getOutputStream() {
    return stdin_fd_;
}
int RemoteProcessHolder::getInputStream() {
    return stdout_fd_;
}
int RemoteProcessHolder::getErrorStream() {
    return stderr_fd_;
}

int RemoteProcessHolder::waitFor() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (exited_)
        return exit_code_;

    int status;
    if (waitpid(pid_, &status, 0) > 0) {
        exited_ = true;
        if (WIFEXITED(status)) {
            exit_code_ = WEXITSTATUS(status);
        } else if (WIFSIGNALED(status)) {
            exit_code_ = 128 + WTERMSIG(status);
        }
    }
    return exit_code_;
}

int RemoteProcessHolder::exitValue() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!exited_) {
        // Non-blocking check
        int status;
        pid_t result = waitpid(pid_, &status, WNOHANG);
        if (result > 0) {
            exited_ = true;
            if (WIFEXITED(status)) {
                exit_code_ = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                exit_code_ = 128 + WTERMSIG(status);
            }
        }
    }
    return exited_ ? exit_code_ : -1;
}

void RemoteProcessHolder::destroy() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!exited_ && pid_ > 0) {
        kill(pid_, SIGKILL);
        int status;
        waitpid(pid_, &status, 0);
        exited_ = true;
        exit_code_ = 137;  // 128 + SIGKILL(9)
    }
}

bool RemoteProcessHolder::alive() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (exited_)
        return false;

    int status;
    pid_t result = waitpid(pid_, &status, WNOHANG);
    if (result > 0) {
        exited_ = true;
        if (WIFEXITED(status)) {
            exit_code_ = WEXITSTATUS(status);
        }
        return false;
    }
    return true;
}

bool RemoteProcessHolder::waitForTimeout(int64_t timeout_ms) {
    // Polling wait
    int64_t elapsed = 0;
    while (elapsed < timeout_ms) {
        if (!alive())
            return true;
        usleep(10000);  // 10ms
        elapsed += 10;
    }
    return !alive();
}

AIBinder* RemoteProcessHolder::getBinder() {
    return binder_;
}

binder_status_t RemoteProcessHolder::onTransact(AIBinder* binder, transaction_code_t code,
                                                const AParcel* in, AParcel* out) {
    auto& bw = BinderWrapper::instance();
    auto* holder = static_cast<RemoteProcessHolder*>(
        bw.AIBinder_getUserData ? bw.AIBinder_getUserData(binder) : nullptr);
    if (!holder)
        return STATUS_UNEXPECTED_NULL;

    // Handle INTERFACE_TRANSACTION for Descriptor check
    if (code == 1598968902) {
        if (bw.AParcel_writeString)
            bw.AParcel_writeString(out, REMOTE_PROCESS_DESCRIPTOR, -1);
        return STATUS_OK;
    }

    // Skip Interface Token
    int32_t strict_policy = 0;
    if (bw.AParcel_readInt32)
        bw.AParcel_readInt32(in, &strict_policy);
    std::string token;
    bw.readString(in, token);

// AIDL protocol: write status code (0 = success) before return value
#define WRITE_NO_EXCEPTION_RP() \
    if (bw.AParcel_writeInt32)  \
    bw.AParcel_writeInt32(out, 0)

    switch (code) {
    case TRANSACTION_getOutputStream: {
        int fd = holder->getOutputStream();
        // Return ParcelFileDescriptor (dup for transfer)
        int dupFd = dup(fd);
        WRITE_NO_EXCEPTION_RP();
        if (bw.AParcel_writeParcelFileDescriptor)
            bw.AParcel_writeParcelFileDescriptor(out, dupFd);
        return STATUS_OK;
    }
    case TRANSACTION_getInputStream: {
        int fd = holder->getInputStream();
        int dupFd = dup(fd);
        WRITE_NO_EXCEPTION_RP();
        if (bw.AParcel_writeParcelFileDescriptor)
            bw.AParcel_writeParcelFileDescriptor(out, dupFd);
        return STATUS_OK;
    }
    case TRANSACTION_getErrorStream: {
        int fd = holder->getErrorStream();
        int dupFd = dup(fd);
        WRITE_NO_EXCEPTION_RP();
        if (bw.AParcel_writeParcelFileDescriptor)
            bw.AParcel_writeParcelFileDescriptor(out, dupFd);
        return STATUS_OK;
    }
    case TRANSACTION_waitFor: {
        int result = holder->waitFor();
        WRITE_NO_EXCEPTION_RP();
        if (bw.AParcel_writeInt32)
            bw.AParcel_writeInt32(out, result);
        return STATUS_OK;
    }
    case TRANSACTION_exitValue: {
        int result = holder->exitValue();
        WRITE_NO_EXCEPTION_RP();
        if (bw.AParcel_writeInt32)
            bw.AParcel_writeInt32(out, result);
        return STATUS_OK;
    }
    case TRANSACTION_destroy: {
        holder->destroy();
        WRITE_NO_EXCEPTION_RP();
        return STATUS_OK;
    }
    case TRANSACTION_alive: {
        bool result = holder->alive();
        WRITE_NO_EXCEPTION_RP();
        if (bw.AParcel_writeBool)
            bw.AParcel_writeBool(out, result);
        return STATUS_OK;
    }
    case TRANSACTION_waitForTimeout: {
        int64_t timeout;
        if (bw.AParcel_readInt64)
            bw.AParcel_readInt64(in, &timeout);
        // Ignore unit, assume ms
        const char* unit = nullptr;
        if (bw.AParcel_readString)
            bw.AParcel_readString(in, &unit, nullptr);
        bool result = holder->waitForTimeout(timeout);
        WRITE_NO_EXCEPTION_RP();
        if (bw.AParcel_writeBool)
            bw.AParcel_writeBool(out, result);
        return STATUS_OK;
    }
    default:
        return STATUS_UNKNOWN_TRANSACTION;
    }

#undef WRITE_NO_EXCEPTION_RP
}

// ==================== ShizukuService ====================

ShizukuService& ShizukuService::getInstance() {
    static ShizukuService instance;
    return instance;
}

ShizukuService::~ShizukuService() {
    stop();
    if (binder_) {
        auto& bw = BinderWrapper::instance();
        if (bw.AIBinder_decStrong)
            bw.AIBinder_decStrong(binder_);
    }
}

int ShizukuService::init() {
    if (binder_) {
        LOGW("ShizukuService already initialized");
        return 0;
    }

    LOGI("Initializing Shizuku compatible service...");

    // Init Binder wrapper
    auto& bw = BinderWrapper::instance();
    if (!bw.init()) {
        LOGE("Failed to init binder wrapper for Shizuku");
        return -1;
    }

    if (!bw.AIBinder_Class_define || !bw.AIBinder_new) {
        LOGE("Required binder functions not available for Shizuku");
        return -1;
    }

    // Create Binder class
    binderClass_ = bw.AIBinder_Class_define(SHIZUKU_DESCRIPTOR, Binder_onCreate, Binder_onDestroy,
                                            ShizukuService::onTransact);

    if (!binderClass_) {
        LOGE("Failed to define Shizuku binder class");
        return -1;
    }

    // Create Binder instance
    binder_ = bw.AIBinder_new(binderClass_, this);
    if (!binder_) {
        LOGE("Failed to create Shizuku binder");
        return -1;
    }

    // Register with ServiceManager
    if (!bw.AServiceManager_addService) {
        LOGE("AServiceManager_addService not available");
        return -1;
    }

    // Try multiple service names for compat
    const char* serviceNames[] = {
        "user_service",
        "moe.shizuku.server.IShizukuService",
    };

    bool registered = false;
    for (const char* name : serviceNames) {
        binder_status_t status = bw.AServiceManager_addService(binder_, name);
        if (status == STATUS_OK) {
            LOGI("Shizuku service registered as '%s'", name);
            registered = true;
        } else {
            LOGW("Failed to register as '%s': %d", name, status);
        }
    }

    if (!registered) {
        LOGE("Failed to register Shizuku service with any name");
        return -1;
    }

    return 0;
}

void ShizukuService::startThreadPool() {
    if (running_)
        return;
    running_ = true;

    // Binder thread pool started by Murasaki
    LOGI("Shizuku service ready");
}

void ShizukuService::stop() {
    running_ = false;
}

uid_t ShizukuService::getCallingUid() {
    auto& bw = BinderWrapper::instance();
    return bw.AIBinder_getCallingUid ? bw.AIBinder_getCallingUid() : 0;
}

bool ShizukuService::checkCallerPermission(uid_t uid) {
    if (uid == 0 || uid == 2000)
        return true;  // root and shell

    // 1. Rei allowlist (same as Murasaki app access list)
    if (allowlist_contains_uid(static_cast<int32_t>(uid)))
        return true;

    // 2. Local runtime permission cache
    std::lock_guard<std::mutex> lock(permMutex_);
    auto it = permissions_.find(uid);
    return it != permissions_.end() && it->second;
}

void ShizukuService::allowUid(uid_t uid, bool allow) {
    std::lock_guard<std::mutex> lock(permMutex_);
    permissions_[uid] = allow;
}

ClientRecord* ShizukuService::findClient(uid_t uid, pid_t pid) {
    std::lock_guard<std::mutex> lock(clientsMutex_);
    uint64_t key = (static_cast<uint64_t>(uid) << 32) | pid;
    auto it = clients_.find(key);
    return it != clients_.end() ? it->second.get() : nullptr;
}

ClientRecord* ShizukuService::requireClient(uid_t uid, pid_t pid) {
    std::lock_guard<std::mutex> lock(clientsMutex_);
    uint64_t key = (static_cast<uint64_t>(uid) << 32) | pid;
    auto it = clients_.find(key);
    if (it != clients_.end()) {
        return it->second.get();
    }

    // Create new record
    auto record = std::make_unique<ClientRecord>();
    record->uid = uid;
    record->pid = pid;
    record->allowed = checkCallerPermission(uid);
    record->apiVersion = SHIZUKU_SERVER_VERSION;

    auto* ptr = record.get();
    clients_[key] = std::move(record);
    return ptr;
}

RemoteProcessHolder* ShizukuService::createProcess(const std::vector<std::string>& cmd,
                                                   const std::vector<std::string>& env,
                                                   const std::string& dir) {
    if (cmd.empty())
        return nullptr;

    // Create pipes
    int stdin_pipe[2], stdout_pipe[2], stderr_pipe[2];
    if (pipe(stdin_pipe) < 0 || pipe(stdout_pipe) < 0 || pipe(stderr_pipe) < 0) {
        LOGE("Failed to create pipes");
        return nullptr;
    }

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("Failed to fork");
        close(stdin_pipe[0]);
        close(stdin_pipe[1]);
        close(stdout_pipe[0]);
        close(stdout_pipe[1]);
        close(stderr_pipe[0]);
        close(stderr_pipe[1]);
        return nullptr;
    }

    if (pid == 0) {
        // Child
        close(stdin_pipe[1]);
        close(stdout_pipe[0]);
        close(stderr_pipe[0]);

        dup2(stdin_pipe[0], STDIN_FILENO);
        dup2(stdout_pipe[1], STDOUT_FILENO);
        dup2(stderr_pipe[1], STDERR_FILENO);

        close(stdin_pipe[0]);
        close(stdout_pipe[1]);
        close(stderr_pipe[1]);

        // chdir
        if (!dir.empty()) {
            chdir(dir.c_str());
        }

        // setenv
        for (const auto& e : env) {
            putenv(strdup(e.c_str()));
        }

        // argv
        std::vector<char*> argv;
        for (const auto& c : cmd) {
            argv.push_back(strdup(c.c_str()));
        }
        argv.push_back(nullptr);

        // exec
        execvp(argv[0], argv.data());
        _exit(127);
    }

    // Parent
    close(stdin_pipe[0]);
    close(stdout_pipe[1]);
    close(stderr_pipe[1]);

    return new RemoteProcessHolder(pid, stdin_pipe[1], stdout_pipe[0], stderr_pipe[0]);
}

// ==================== Transaction Handler ====================

binder_status_t ShizukuService::onTransact(AIBinder* binder, transaction_code_t code,
                                           const AParcel* in, AParcel* out) {
    auto& bw = BinderWrapper::instance();
    auto* service = static_cast<ShizukuService*>(
        bw.AIBinder_getUserData ? bw.AIBinder_getUserData(binder) : nullptr);
    if (!service)
        return STATUS_UNEXPECTED_NULL;

    uid_t callingUid = bw.AIBinder_getCallingUid ? bw.AIBinder_getCallingUid() : 0;
    LOGD("Shizuku transaction: code=%d, uid=%d", code, callingUid);

    // Handle INTERFACE_TRANSACTION
    if (code == 1598968902) {
        if (bw.AParcel_writeString)
            bw.AParcel_writeString(out, SHIZUKU_DESCRIPTOR, -1);
        return STATUS_OK;
    }

    // Skip Interface Token
    int32_t strict_policy = 0;
    if (bw.AParcel_readInt32)
        bw.AParcel_readInt32(in, &strict_policy);
    std::string token;
    bw.readString(in, token);

    switch (code) {
    case TRANSACTION_getVersion:
        return service->handleGetVersion(in, out);
    case TRANSACTION_getUid:
        return service->handleGetUid(in, out);
    case TRANSACTION_checkPermission:
        return service->handleCheckPermission(in, out);
    case TRANSACTION_newProcess:
        return service->handleNewProcess(in, out);
    case TRANSACTION_getSELinuxContext:
        return service->handleGetSELinuxContext(in, out);
    case TRANSACTION_getSystemProperty:
        return service->handleGetSystemProperty(in, out);
    case TRANSACTION_setSystemProperty:
        return service->handleSetSystemProperty(in, out);
    case TRANSACTION_checkSelfPermission:
        return service->handleCheckSelfPermission(in, out);
    case TRANSACTION_requestPermission:
        return service->handleRequestPermission(in, out);
    case TRANSACTION_attachApplication:
        return service->handleAttachApplication(in, out);
    case TRANSACTION_exit:
        return service->handleExit(in, out);
    case TRANSACTION_isHidden:
        return service->handleIsHidden(in, out);
    case TRANSACTION_getFlagsForUid:
        return service->handleGetFlagsForUid(in, out);
    case TRANSACTION_updateFlagsForUid:
        return service->handleUpdateFlagsForUid(in, out);
    default:
        LOGW("Unknown Shizuku transaction: %d", code);
        return STATUS_UNKNOWN_TRANSACTION;
    }
}

// ==================== Handler Implementations ====================

// Helper macro to simplify AParcel calls through wrapper
#define BW BinderWrapper::instance()

// AIDL protocol: write status code (0 = success) before return value
#define WRITE_NO_EXCEPTION()   \
    if (BW.AParcel_writeInt32) \
    BW.AParcel_writeInt32(out, 0)

binder_status_t ShizukuService::handleGetVersion(const AParcel* in, AParcel* out) {
    (void)in;
    uid_t uid = getCallingUid();
    if (!checkCallerPermission(uid)) {
        LOGW("getVersion: permission denied for uid %d", uid);
        // Return version anyway but log warning
    }
    WRITE_NO_EXCEPTION();
    if (BW.AParcel_writeInt32)
        BW.AParcel_writeInt32(out, SHIZUKU_SERVER_VERSION);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleGetUid(const AParcel* in, AParcel* out) {
    (void)in;
    WRITE_NO_EXCEPTION();
    if (BW.AParcel_writeInt32)
        BW.AParcel_writeInt32(out, getuid());
    return STATUS_OK;
}

binder_status_t ShizukuService::handleCheckPermission(const AParcel* in, AParcel* out) {
    std::string permission;
    BW.readString(in, permission);

    // Return PERMISSION_GRANTED (0)
    WRITE_NO_EXCEPTION();
    if (BW.AParcel_writeInt32)
        BW.AParcel_writeInt32(out, 0);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleNewProcess(const AParcel* in, AParcel* out) {
    uid_t uid = getCallingUid();

    if (!checkCallerPermission(uid)) {
        LOGE("newProcess: permission denied for uid %d", uid);
        return STATUS_PERMISSION_DENIED;
    }

    // Read command array
    int32_t cmdCount = 0;
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &cmdCount);
    std::vector<std::string> cmd;
    for (int32_t i = 0; i < cmdCount; i++) {
        std::string str;
        BW.readString(in, str);
        cmd.push_back(str);
    }

    // Read env array
    int32_t envCount = 0;
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &envCount);
    std::vector<std::string> env;
    for (int32_t i = 0; i < envCount; i++) {
        std::string str;
        BW.readString(in, str);
        env.push_back(str);
    }

    // Read cwd
    std::string dir;
    BW.readString(in, dir);

    LOGI("newProcess: cmd[0]=%s, uid=%d", cmd.empty() ? "(empty)" : cmd[0].c_str(), uid);

    // Create process
    auto* holder = createProcess(cmd, env, dir);
    if (!holder) {
        LOGE("Failed to create process");
        return STATUS_FAILED_TRANSACTION;
    }

    // AIDL protocol: write status code first
    WRITE_NO_EXCEPTION();
    // Return IRemoteProcess binder
    if (BW.AParcel_writeStrongBinder)
        BW.AParcel_writeStrongBinder(out, holder->getBinder());

    return STATUS_OK;
}

binder_status_t ShizukuService::handleGetSELinuxContext(const AParcel* in, AParcel* out) {
    (void)in;

    char context[256] = {0};
    FILE* f = fopen("/proc/self/attr/current", "r");
    if (f) {
        fread(context, 1, sizeof(context) - 1, f);
        fclose(f);
        // Strip newline
        size_t len = strlen(context);
        if (len > 0 && context[len - 1] == '\n') {
            context[len - 1] = '\0';
        }
    }

    WRITE_NO_EXCEPTION();
    if (BW.AParcel_writeString)
        BW.AParcel_writeString(out, context, strlen(context));
    return STATUS_OK;
}

binder_status_t ShizukuService::handleGetSystemProperty(const AParcel* in, AParcel* out) {
    std::string name;
    std::string defaultValue;
    BW.readString(in, name);
    BW.readString(in, defaultValue);

    char value[92] = {0};
    WRITE_NO_EXCEPTION();
    if (!name.empty() && __system_property_get(name.c_str(), value) > 0) {
        if (BW.AParcel_writeString)
            BW.AParcel_writeString(out, value, strlen(value));
    } else {
        if (BW.AParcel_writeString)
            BW.AParcel_writeString(out, defaultValue.c_str(), defaultValue.length());
    }
    return STATUS_OK;
}

binder_status_t ShizukuService::handleSetSystemProperty(const AParcel* in, AParcel* out) {
    uid_t uid = getCallingUid();
    if (!checkCallerPermission(uid)) {
        return STATUS_PERMISSION_DENIED;
    }

    std::string name;
    std::string value;
    BW.readString(in, name);
    BW.readString(in, value);

    if (!name.empty() && !value.empty()) {
        __system_property_set(name.c_str(), value.c_str());
    }
    return STATUS_OK;
}

binder_status_t ShizukuService::handleCheckSelfPermission(const AParcel* in, AParcel* out) {
    (void)in;
    uid_t uid = getCallingUid();
    pid_t pid = BW.AIBinder_getCallingPid ? BW.AIBinder_getCallingPid() : 0;

    bool allowed = false;

    // 1. Runtime cache (findClient by PID; same UID multi-process may need care)
    if (auto* client = findClient(uid, pid)) {
        if (client->allowed) {
            allowed = true;
        }
    }

    // 2. If not cached, check KSU root
    if (!allowed && checkCallerPermission(uid)) {
        allowed = true;
        // Update cache
        auto* client = requireClient(uid, pid);
        client->allowed = true;
    }

    LOGD("checkSelfPermission: uid=%d pid=%d allowed=%d", uid, pid, allowed);

    WRITE_NO_EXCEPTION();
    if (BW.AParcel_writeBool)
        BW.AParcel_writeBool(out, allowed);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleRequestPermission(const AParcel* in, AParcel* out) {
    int32_t requestCode = 0;
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &requestCode);

    uid_t uid = getCallingUid();
    pid_t pid = BW.AIBinder_getCallingPid ? BW.AIBinder_getCallingPid() : 0;

    // 1. If in KSU allowlist or root, allow
    if (checkCallerPermission(uid)) {
        LOGI("Auto-granting permission for uid %d (in KSU allowlist or root)", uid);
        if (auto* client = requireClient(uid, pid)) {
            client->allowed = true;
            if (client->applicationBinder) {
                // Callback skipped: Bundle is heavy; next checkSelfPermission will pass
            }
        }
    } else {
        // 2. Launch Rei AuthorizeActivity (Murasaki/Shizuku)
        LOGI("Requesting permission for uid %d pid %d via Rei AuthorizeActivity", uid, pid);

        pid_t forks = fork();
        if (forks == 0) {
            std::string uidStr = std::to_string(uid);
            // Rei AuthorizeActivity: rei.extra.UID, rei.extra.SOURCE=murasaki
            execlp("am", "am", "start", "-n",
                   "com.anatdx.rei/com.anatdx.rei.ui.auth.AuthorizeActivity",
                   "--ei", "rei.extra.UID", uidStr.c_str(),
                   "--es", "rei.extra.SOURCE", "murasaki",
                   "--user", "0", nullptr);
            _exit(127);
        } else if (forks > 0) {
            int status;
            waitpid(forks, &status, 0);
        }
    }

    WRITE_NO_EXCEPTION();
    return STATUS_OK;
}

binder_status_t ShizukuService::handleAttachApplication(const AParcel* in, AParcel* out) {
    AIBinder* appBinder = nullptr;
    if (BW.AParcel_readStrongBinder)
        BW.AParcel_readStrongBinder(in, &appBinder);

    // Read Bundle args (simplified); just record client

    uid_t uid = getCallingUid();
    pid_t pid = BW.AIBinder_getCallingPid ? BW.AIBinder_getCallingPid() : 0;

    auto* client = requireClient(uid, pid);
    client->applicationBinder = appBinder;

    LOGI("attachApplication: uid=%d, pid=%d, allowed=%d", uid, pid, client->allowed);

    WRITE_NO_EXCEPTION();
    return STATUS_OK;
}

binder_status_t ShizukuService::handleExit(const AParcel* in, AParcel* out) {
    (void)in;

    uid_t uid = getCallingUid();
    if (uid != 0 && uid != 2000) {
        LOGW("exit called by non-root uid %d, ignoring", uid);
        WRITE_NO_EXCEPTION();
        return STATUS_OK;
    }

    LOGI("Shizuku service exit requested");
    stop();
    WRITE_NO_EXCEPTION();
    return STATUS_OK;
}

binder_status_t ShizukuService::handleIsHidden(const AParcel* in, AParcel* out) {
    int32_t uid = 0;
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &uid);

    // No app hidden
    WRITE_NO_EXCEPTION();
    if (BW.AParcel_writeBool)
        BW.AParcel_writeBool(out, false);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleGetFlagsForUid(const AParcel* in, AParcel* out) {
    int32_t uid = 0, mask = 0;
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &uid);
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &mask);

    // Simplified
    WRITE_NO_EXCEPTION();
    if (BW.AParcel_writeInt32)
        BW.AParcel_writeInt32(out, 0);
    return STATUS_OK;
}

binder_status_t ShizukuService::handleUpdateFlagsForUid(const AParcel* in, AParcel* out) {
    int32_t uid = 0, mask = 0, value = 0;
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &uid);
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &mask);
    if (BW.AParcel_readInt32)
        BW.AParcel_readInt32(in, &value);

    // Only allow Manager (or Root/Shell/KSU-allowed apps) to call this
    uid_t callingUid = getCallingUid();
    if (callingUid != 0 && callingUid != 2000 && !checkCallerPermission(callingUid)) {
        LOGW("updateFlagsForUid: permission denied for caller %d", callingUid);
        return STATUS_PERMISSION_DENIED;
    }

    // Shizuku Constants: FLAG_ALLOWED = 8 (1<<3), MASK_PERMISSION = 4 (1<<2) ?
    // If called with value & 8, grant.
    bool is_allowed = (value & 8) != 0;

    // If mask has MASK_PERMISSION (4), update allowed
    if ((mask & 4) != 0) {
        LOGI("updateFlagsForUid: uid=%d allowed=%d", uid, is_allowed);
        std::lock_guard<std::mutex> lock(clientsMutex_);
        // Update all clients matching UID
        for (auto& pair : clients_) {
            if (pair.second->uid == (uid_t)uid) {
                pair.second->allowed = is_allowed;
            }
        }
    }

    WRITE_NO_EXCEPTION();
    return STATUS_OK;
}

#undef WRITE_NO_EXCEPTION
#undef BW

// ==================== Entry ====================

void start_shizuku_service() {
    auto& service = ShizukuService::getInstance();
    if (service.init() == 0) {
        service.startThreadPool();
        LOGI("Shizuku compatible service started");
    } else {
        LOGE("Failed to start Shizuku service");
    }
}

}  // namespace shizuku
}  // namespace ksud

/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Rei JNI: AP/KP supercall + KSU ioctl. */

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <dirent.h>
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/ioctl.h>
#include <fcntl.h>

#include "supercall.h"
#include "uapi/scdefs.h"

/* KSU manager fd detection: same as YukiSU ksud (prctl get fd / scan /proc/self/fd). */
#ifndef KSU_PRCTL_GET_FD
#define KSU_PRCTL_GET_FD 0x59554B4Au  /* "YUKJ" */
#endif
struct KsuPrctlGetFdCmd {
    int32_t result;
    int32_t fd;
};

/* Align with YukiSU manager: is_manager = (GET_INFO flags & 0x2). */
#ifndef _IOC_READ
#define _IOC_READ  2U
#endif
#ifndef _IOC
#define _IOC(dir, type, nr, size) \
    (((dir) << 30) | ((type) << 8) | (nr) | ((size) << 16))
#endif
#define KSU_IOCTL_GET_INFO _IOC(_IOC_READ, 'K', 2, sizeof(struct KsuGetInfoCmd))
#ifndef _IOC_WRITE
#define _IOC_WRITE 1U
#endif
#define KSU_IOCTL_CHECK_SAFEMODE _IOC(_IOC_READ, 'K', 5, 0)
#define KSU_IOCTL_GET_ALLOW_LIST _IOC(_IOC_READ | _IOC_WRITE, 'K', 6, 0)
#define KSU_IOCTL_SET_APP_PROFILE _IOC(_IOC_WRITE, 'K', 12, 0)
#define KSU_IOCTL_GET_FULL_VERSION _IOC(_IOC_READ, 'K', 100, 0)

struct KsuCheckSafemodeCmd {
    uint8_t in_safe_mode;
};
struct KsuGetAllowListCmd {
    uint32_t uids[128];
    uint32_t count;
    uint8_t allow;
};
static constexpr unsigned KSU_FULL_VERSION_STRING = 255;
struct KsuGetFullVersionCmd {
    char version_full[KSU_FULL_VERSION_STRING];
};

struct KsuGetInfoCmd {
    uint32_t version;
    uint32_t flags;
    uint32_t features;
};

/* Layout for set_app_profile: same as kernel app_profile (version, key[256], current_uid, allow_su). */
static constexpr unsigned KSU_APP_PROFILE_VER = 2;
static constexpr unsigned KSU_MAX_PACKAGE_NAME = 256;
static constexpr unsigned KSU_APP_PROFILE_BUF_SIZE = 768; /* >= sizeof(kernel app_profile) */

static int scan_ksu_driver_fd() {
    DIR* dir = opendir("/proc/self/fd");
    if (!dir) return -1;
    int found = -1;
    char link_path[64];
    char target[256];
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        int fd_num = atoi(entry->d_name);
        snprintf(link_path, sizeof(link_path), "/proc/self/fd/%d", fd_num);
        ssize_t len = readlink(link_path, target, sizeof(target) - 1);
        if (len > 0) {
            target[len] = '\0';
            if (strstr(target, "[ksu_driver]") != nullptr) {
                found = fd_num;
                break;
            }
        }
    }
    closedir(dir);
    return found;
}

/* Returns 1 if current process is KSU manager (same as YukiSU: fd + GET_INFO flags & 0x2). */
static int has_ksu_manager_impl() {
    int fd = scan_ksu_driver_fd();
    bool fd_from_prctl = false;
    if (fd < 0) {
        KsuPrctlGetFdCmd cmd = {-1, -1};
        prctl(static_cast<int>(KSU_PRCTL_GET_FD), &cmd, 0, 0, 0);
        if (cmd.result == 0 && cmd.fd >= 0) {
            fd = cmd.fd;
            fd_from_prctl = true;
        }
    }
    if (fd < 0) return 0;
    struct KsuGetInfoCmd info = {};
    if (ioctl(fd, KSU_IOCTL_GET_INFO, &info) != 0) {
        if (fd_from_prctl) close(fd);
        return 0;
    }
    int is_manager = (info.flags & 0x2u) ? 1 : 0;
    if (fd_from_prctl) close(fd);
    return is_manager;
}

/* Get KSU driver fd (caller must close if fd_from_prctl). Returns -1 on failure. */
static int get_ksu_driver_fd(bool* out_fd_from_prctl) {
    int fd = scan_ksu_driver_fd();
    *out_fd_from_prctl = false;
    if (fd < 0) {
        KsuPrctlGetFdCmd cmd = {-1, -1};
        prctl(static_cast<int>(KSU_PRCTL_GET_FD), &cmd, 0, 0, 0);
        if (cmd.result == 0 && cmd.fd >= 0) {
            fd = cmd.fd;
            *out_fd_from_prctl = true;
        }
    }
    return fd;
}

/* Set KSU app profile allow_su for (uid, pkg). Returns 0 on success, -1 on failure. */
static int set_app_profile_ksu_impl(int uid, const char* pkg, int allow_su) {
    bool fd_from_prctl = false;
    int fd = get_ksu_driver_fd(&fd_from_prctl);
    if (fd < 0) return -1;
    uint8_t buf[KSU_APP_PROFILE_BUF_SIZE];
    memset(buf, 0, sizeof(buf));
    /* version at 0 */
    *reinterpret_cast<uint32_t*>(buf + 0) = KSU_APP_PROFILE_VER;
    /* key at 4, max 255 + NUL */
    if (pkg) {
        size_t len = strlen(pkg);
        if (len >= KSU_MAX_PACKAGE_NAME) len = KSU_MAX_PACKAGE_NAME - 1;
        memcpy(buf + 4, pkg, len);
        buf[4 + len] = '\0';
    }
    /* current_uid at 260 */
    *reinterpret_cast<int32_t*>(buf + 260) = static_cast<int32_t>(uid);
    /* allow_su at 264 */
    buf[264] = (allow_su != 0) ? 1 : 0;

    int ret = ioctl(fd, KSU_IOCTL_SET_APP_PROFILE, buf);
    if (fd_from_prctl) close(fd);
    return (ret == 0) ? 0 : -1;
}

/* True if KSU driver fd can be obtained (scan or prctl). Does not require manager. */
static int is_ksu_driver_present_impl() {
    bool fd_from_prctl = false;
    int fd = get_ksu_driver_fd(&fd_from_prctl);
    if (fd < 0) return 0;
    if (fd_from_prctl) close(fd);
    return 1;
}

/* KSU kernel version from GET_INFO; 0 if unavailable. */
static uint32_t get_ksu_version_impl() {
    bool fd_from_prctl = false;
    int fd = get_ksu_driver_fd(&fd_from_prctl);
    if (fd < 0) return 0;
    struct KsuGetInfoCmd info = {};
    int ret = ioctl(fd, KSU_IOCTL_GET_INFO, &info);
    if (fd_from_prctl) close(fd);
    return (ret == 0) ? info.version : 0;
}

/* KSU allow list UIDs via GET_ALLOW_LIST. Returns count; uids[] filled up to 128. */
static uint32_t get_ksu_allow_list_impl(uint32_t* uids, unsigned max_count) {
    bool fd_from_prctl = false;
    int fd = get_ksu_driver_fd(&fd_from_prctl);
    if (fd < 0 || !uids || max_count == 0) return 0;
    struct KsuGetAllowListCmd cmd = {};
    cmd.allow = 1;
    if (ioctl(fd, KSU_IOCTL_GET_ALLOW_LIST, &cmd) != 0) {
        if (fd_from_prctl) close(fd);
        return 0;
    }
    if (fd_from_prctl) close(fd);
    unsigned n = cmd.count;
    if (n > 128u) n = 128u;
    if (n > max_count) n = max_count;
    memcpy(uids, cmd.uids, n * sizeof(uint32_t));
    return n;
}

/* KSU full version string; empty on failure. */
static void get_ksu_full_version_impl(char* buf, size_t buf_size) {
    if (!buf || buf_size == 0) return;
    buf[0] = '\0';
    bool fd_from_prctl = false;
    int fd = get_ksu_driver_fd(&fd_from_prctl);
    if (fd < 0) return;
    struct KsuGetFullVersionCmd cmd = {};
    if (ioctl(fd, KSU_IOCTL_GET_FULL_VERSION, &cmd) != 0) {
        if (fd_from_prctl) close(fd);
        return;
    }
    if (fd_from_prctl) close(fd);
    size_t len = strnlen(cmd.version_full, KSU_FULL_VERSION_STRING - 1);
    if (len >= buf_size) len = buf_size - 1;
    memcpy(buf, cmd.version_full, len);
    buf[len] = '\0';
}

/* True if KSU reports safe mode. */
static int is_ksu_safe_mode_impl() {
    bool fd_from_prctl = false;
    int fd = get_ksu_driver_fd(&fd_from_prctl);
    if (fd < 0) return 0;
    struct KsuCheckSafemodeCmd cmd = {};
    int ret = ioctl(fd, KSU_IOCTL_CHECK_SAFEMODE, &cmd);
    if (fd_from_prctl) close(fd);
    return (ret == 0 && cmd.in_safe_mode) ? 1 : 0;
}

#define LOG_TAG "ReiJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static void ensureSuperKeyNonNull(JNIEnv *env, jstring super_key_jstr) {
    if (!super_key_jstr) {
        LOGE("Super key is null");
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "superKey must not be null");
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_anatdx_rei_ApNatives_nativeReady(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return JNI_FALSE;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return JNI_FALSE;
    long hello_ret = sc_hello(key);
    jboolean result = (hello_ret == SUPERCALL_HELLO_MAGIC) ? JNI_TRUE : JNI_FALSE;
    if (!result) {
        LOGE("nativeReady: sc_hello returned %ld (expect " "0x11581158)", hello_ret);
    }
    env->ReleaseStringUTFChars(super_key_jstr, key);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_anatdx_rei_ApNatives_nativeSu(JNIEnv *env, jclass clazz,
        jstring super_key_jstr, jint to_uid, jstring selinux_context_jstr) {
    (void)clazz;
    ensureSuperKeyNonNull(env, super_key_jstr);
    if (env->ExceptionCheck()) return -1;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return -1;
    const char *sctx = nullptr;
    if (selinux_context_jstr) {
        sctx = env->GetStringUTFChars(selinux_context_jstr, nullptr);
    }
    struct su_profile profile {};
    profile.uid = getuid();
    profile.to_uid = (uid_t) to_uid;
    if (sctx) {
        strncpy(profile.scontext, sctx, sizeof(profile.scontext) - 1);
        profile.scontext[sizeof(profile.scontext) - 1] = '\0';
        env->ReleaseStringUTFChars(selinux_context_jstr, sctx);
    } else {
        profile.scontext[0] = '\0';
    }
    long rc = sc_su(key, &profile);
    env->ReleaseStringUTFChars(super_key_jstr, key);
    if (rc < 0) {
        LOGE("nativeSu error: %ld", rc);
    }
    return (jlong) rc;
}

JNIEXPORT jstring JNICALL
Java_com_anatdx_rei_ApNatives_nativeSuPath(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return env->NewStringUTF("");
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return env->NewStringUTF("");
    char buf[SU_PATH_MAX_LEN] = {'\0'};
    long rc = sc_su_get_path(key, buf, sizeof(buf));
    env->ReleaseStringUTFChars(super_key_jstr, key);
    if (rc < 0) return env->NewStringUTF("");
    return env->NewStringUTF(buf);
}

JNIEXPORT jlong JNICALL
Java_com_anatdx_rei_ApNatives_nativeKernelPatchVersion(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return 0;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return 0;
    uint32_t ver = sc_kp_ver(key);
    env->ReleaseStringUTFChars(super_key_jstr, key);
    return (jlong) ver;
}

JNIEXPORT jstring JNICALL
Java_com_anatdx_rei_ApNatives_nativeDiag(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return env->NewStringUTF("");
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return env->NewStringUTF("");
    long hello = sc_hello(key);
    long kpver = (long) sc_kp_ver(key);
    long kver = (long) sc_k_ver(key);
    char build_time[512] = {'\0'};
    long bt_rc = sc_get_build_time(key, build_time, sizeof(build_time));
    env->ReleaseStringUTFChars(super_key_jstr, key);
    std::string out;
    out += "hello=" + std::to_string(hello) + "\n";
    out += "kp_ver=" + std::to_string(kpver) + "\n";
    out += "kernel_ver=" + std::to_string(kver) + "\n";
    out += "build_time_rc=" + std::to_string(bt_rc) + "\n";
    if (bt_rc >= 0 && build_time[0]) {
        out += "build_time=";
        out += build_time;
        out += "\n";
    }
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jintArray JNICALL
Java_com_anatdx_rei_ApNatives_nativeSuUids(JNIEnv *env, jclass clazz, jstring super_key_jstr) {
    (void)clazz;
    if (!super_key_jstr) return env->NewIntArray(0);
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return env->NewIntArray(0);
    long num = sc_su_uid_nums(key);
    if (num <= 0) {
        env->ReleaseStringUTFChars(super_key_jstr, key);
        return env->NewIntArray(0);
    }
    std::vector<int> uids(static_cast<size_t>(num), 0);
    long n = sc_su_allow_uids(key, reinterpret_cast<uid_t *>(uids.data()), static_cast<int>(num));
    env->ReleaseStringUTFChars(super_key_jstr, key);
    if (n <= 0) return env->NewIntArray(0);
    jintArray result = env->NewIntArray(static_cast<jsize>(n));
    if (!result) return env->NewIntArray(0);
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(n), uids.data());
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_anatdx_rei_ApNatives_nativeGrantSu(JNIEnv *env, jclass clazz,
        jstring super_key_jstr, jint uid, jint to_uid, jstring selinux_context_jstr) {
    (void)clazz;
    ensureSuperKeyNonNull(env, super_key_jstr);
    if (env->ExceptionCheck()) return -1;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return -1;
    const char *sctx = nullptr;
    if (selinux_context_jstr) sctx = env->GetStringUTFChars(selinux_context_jstr, nullptr);
    struct su_profile profile {};
    profile.uid = static_cast<unsigned int>(uid);
    profile.to_uid = static_cast<unsigned int>(to_uid);
    if (sctx) {
        strncpy(profile.scontext, sctx, sizeof(profile.scontext) - 1);
        profile.scontext[sizeof(profile.scontext) - 1] = '\0';
        env->ReleaseStringUTFChars(selinux_context_jstr, sctx);
    } else {
        profile.scontext[0] = '\0';
    }
    long rc = sc_su_grant_uid(key, &profile);
    env->ReleaseStringUTFChars(super_key_jstr, key);
    return static_cast<jlong>(rc);
}

JNIEXPORT jlong JNICALL
Java_com_anatdx_rei_ApNatives_nativeRevokeSu(JNIEnv *env, jclass clazz,
        jstring super_key_jstr, jint uid) {
    (void)clazz;
    ensureSuperKeyNonNull(env, super_key_jstr);
    if (env->ExceptionCheck()) return -1;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return -1;
    long rc = sc_su_revoke_uid(key, static_cast<uid_t>(uid));
    env->ReleaseStringUTFChars(super_key_jstr, key);
    return static_cast<jlong>(rc);
}

JNIEXPORT jboolean JNICALL
Java_com_anatdx_rei_ApNatives_nativeResetSuPath(JNIEnv *env, jclass clazz,
        jstring super_key_jstr, jstring su_path_jstr) {
    (void)clazz;
    if (!super_key_jstr || !su_path_jstr) return JNI_FALSE;
    const char *key = env->GetStringUTFChars(super_key_jstr, nullptr);
    if (!key) return JNI_FALSE;
    const char *path = env->GetStringUTFChars(su_path_jstr, nullptr);
    if (!path) {
        env->ReleaseStringUTFChars(super_key_jstr, key);
        return JNI_FALSE;
    }
    long rc = sc_su_reset_path(key, path);
    env->ReleaseStringUTFChars(super_key_jstr, key);
    env->ReleaseStringUTFChars(su_path_jstr, path);
    if (rc != 0) {
        LOGE("nativeResetSuPath path=%s rc=%ld (kernel may not support sc 0x1111)", path, rc);
    }
    return (rc == 0) ? JNI_TRUE : JNI_FALSE;
}

/* --- KSU: JNI entries under KsuNatives, same reijni --- */

JNIEXPORT jint JNICALL
Java_com_anatdx_rei_KsuNatives_nGetVersion(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return static_cast<jint>(get_ksu_version_impl());
}

JNIEXPORT jintArray JNICALL
Java_com_anatdx_rei_KsuNatives_nGetAllowList(JNIEnv *env, jclass clazz) {
    (void)clazz;
    uint32_t uids[128];
    uint32_t n = get_ksu_allow_list_impl(uids, 128);
    if (n == 0) return env->NewIntArray(0);
    jintArray result = env->NewIntArray(static_cast<jsize>(n));
    if (!result) return env->NewIntArray(0);
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(n), reinterpret_cast<const jint*>(uids));
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_anatdx_rei_KsuNatives_isSafeModeNative(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return is_ksu_safe_mode_impl() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_anatdx_rei_KsuNatives_isManagerNative(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return has_ksu_manager_impl() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_anatdx_rei_KsuNatives_isKsuDriverPresentNative(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return is_ksu_driver_present_impl() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_anatdx_rei_KsuNatives_nSetAppProfile(JNIEnv *env, jclass clazz, jobject profile_obj) {
    (void)clazz;
    if (!profile_obj) return JNI_FALSE;
    jclass profileClass = env->GetObjectClass(profile_obj);
    if (!profileClass) return JNI_FALSE;
    jfieldID nameId = env->GetFieldID(profileClass, "name", "Ljava/lang/String;");
    jfieldID currentUidId = env->GetFieldID(profileClass, "currentUid", "I");
    jfieldID allowSuId = env->GetFieldID(profileClass, "allowSu", "Z");
    if (!nameId || !currentUidId || !allowSuId) return JNI_FALSE;
    jstring nameJ = (jstring) env->GetObjectField(profile_obj, nameId);
    if (!nameJ) return JNI_FALSE;
    const char *pkg = env->GetStringUTFChars(nameJ, nullptr);
    if (!pkg) return JNI_FALSE;
    jint uid = env->GetIntField(profile_obj, currentUidId);
    jboolean allow = env->GetBooleanField(profile_obj, allowSuId);
    int ret = set_app_profile_ksu_impl(static_cast<int>(uid), pkg, allow ? 1 : 0);
    env->ReleaseStringUTFChars(nameJ, pkg);
    return (ret == 0) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"

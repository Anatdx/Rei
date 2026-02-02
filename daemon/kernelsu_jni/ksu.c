// Copied from YukiSU manager/app/src/main/cpp/ksu.c (minimal edits: none)

#include <android/log.h>
#include <dirent.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <unistd.h>

#include "ksu.h"
#include "prelude.h"

static int fd = -1;
static int last_errno = 0;

static inline int scan_driver_fd() {
  const char *kName = "[ksu_driver]";
  DIR *fd_dir = opendir("/proc/self/fd");
  if (!fd_dir) {
    return -1;
  }

  int found = -1;
  struct dirent *de;
  char path[64];
  char target[PATH_MAX];

  while ((de = readdir(fd_dir)) != NULL) {
    if (de->d_name[0] == '.') {
      continue;
    }

    char *endptr = NULL;
    long fd_long = strtol(de->d_name, &endptr, 10);
    if (!de->d_name[0] || *endptr != '\0' || fd_long < 0 || fd_long > INT_MAX) {
      continue;
    }

    snprintf(path, sizeof(path), "/proc/self/fd/%s", de->d_name);
    ssize_t n = readlink(path, target, sizeof(target) - 1);
    if (n < 0) {
      continue;
    }
    target[n] = '\0';

    const char *base = strrchr(target, '/');
    base = base ? base + 1 : target;

    if (strstr(base, kName)) {
      found = (int)fd_long;
      break;
    }
  }

  closedir(fd_dir);
  return found;
}

static int init_driver_fd() {
  // Method 1: already inherited driver fd
  int found = scan_driver_fd();
  if (found >= 0) return found;

  // Method 2: prctl hook (SECCOMP-safe)
  struct ksu_prctl_get_fd_cmd cmd = {-1, -1};
  prctl(KSU_PRCTL_GET_FD, &cmd, 0, 0, 0);
  if (cmd.result == 0 && cmd.fd >= 0) {
    return cmd.fd;
  }

  // Method 3: reboot syscall fallback
  int out_fd = -1;
  syscall(SYS_reboot, KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0, &out_fd);
  if (out_fd >= 0) return out_fd;

  return -1;
}

static int ksuctl(unsigned long op, void *arg) {
  if (fd < 0) {
    fd = init_driver_fd();
  }
  int ret = ioctl(fd, op, arg);
  if (ret < 0) last_errno = errno;
  else last_errno = 0;
  return ret;
}

static struct ksu_get_info_cmd g_version = {0};

void reset_cached_info() { memset(&g_version, 0, sizeof(g_version)); }

struct ksu_get_info_cmd get_info() {
  if (!g_version.version) {
    ksuctl(KSU_IOCTL_GET_INFO, &g_version);
  }
  return g_version;
}

int get_last_errno() { return last_errno; }

bool ksu_driver_present(void) {
  int test = init_driver_fd();
  if (test >= 0) {
    // Cache for later ioctl calls.
    fd = test;
    return true;
  }
  return false;
}

uint32_t get_version() {
  struct ksu_get_info_cmd info = get_info();
  return info.version;
}

bool get_allow_list(struct ksu_get_allow_list_cmd *cmd) {
  if (ksuctl(KSU_IOCTL_GET_ALLOW_LIST, cmd) == 0) {
    return true;
  }

  int size = 0;
  int uids[1024];
  if (legacy_get_allow_list(uids, &size)) {
    cmd->count = (uint32_t)size;
    memcpy(cmd->uids, uids, sizeof(int) * size);
    return true;
  }

  return false;
}

bool is_safe_mode() {
  struct ksu_check_safemode_cmd cmd = {};
  if (ksuctl(KSU_IOCTL_CHECK_SAFEMODE, &cmd) == 0) {
    return cmd.in_safe_mode;
  }
  return legacy_is_safe_mode();
}

bool is_lkm_mode() {
  struct ksu_get_info_cmd info = get_info();
  if (info.version > 0) {
    return (info.flags & 0x1) != 0;
  }
  return (legacy_get_info().flags & 0x1) != 0;
}

bool is_manager() {
  struct ksu_get_info_cmd info = get_info();
  if (info.version > 0) {
    return (info.flags & 0x2) != 0;
  }
  return legacy_get_info().version > 0;
}

bool uid_should_umount(int uid) {
  struct ksu_uid_should_umount_cmd cmd = {};
  cmd.uid = (uint32_t)uid;
  if (ksuctl(KSU_IOCTL_UID_SHOULD_UMOUNT, &cmd) == 0) {
    return cmd.should_umount;
  }
  return legacy_uid_should_umount(uid);
}

bool set_app_profile(const struct app_profile *profile) {
  // Some forks/kernel versions may have a larger app_profile ABI than the
  // header we build against. Passing a too-small user buffer can cause EFAULT
  // (copy_from_user hits an unmapped page). Use an oversized zeroed buffer.
  const size_t buf_sz = 4096;
  void *buf = calloc(1, buf_sz);
  if (!buf) {
    return legacy_set_app_profile(profile);
  }
  struct ksu_set_app_profile_cmd *cmd = (struct ksu_set_app_profile_cmd *)buf;
  cmd->profile = *profile;

  int ret = ksuctl(KSU_IOCTL_SET_APP_PROFILE, cmd);
  free(buf);

  if (ret == 0) {
    return true;
  }
  return legacy_set_app_profile(profile);
}

int get_app_profile(struct app_profile *profile) {
  // Same reason as set_app_profile(): ensure user buffer is large enough for
  // kernel copy_to_user even if kernel struct grows.
  const size_t buf_sz = 4096;
  void *buf = calloc(1, buf_sz);
  if (!buf) {
    return legacy_get_app_profile(profile->key, profile) ? 0 : -1;
  }
  struct ksu_get_app_profile_cmd *cmd = (struct ksu_get_app_profile_cmd *)buf;
  cmd->profile = *profile;

  int ret = ksuctl(KSU_IOCTL_GET_APP_PROFILE, cmd);
  if (ret == 0) {
    *profile = cmd->profile;
    free(buf);
    return 0;
  }
  free(buf);
  return legacy_get_app_profile(profile->key, profile) ? 0 : -1;
}

bool set_su_enabled(bool enabled) {
  struct ksu_set_feature_cmd cmd = {};
  cmd.feature_id = KSU_FEATURE_SU_COMPAT;
  cmd.value = enabled ? 1 : 0;
  if (ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0) {
    return true;
  }
  return legacy_set_su_enabled(enabled);
}

bool is_su_enabled() {
  struct ksu_get_feature_cmd cmd = {};
  cmd.feature_id = KSU_FEATURE_SU_COMPAT;
  if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) == 0 && cmd.supported) {
    return cmd.value != 0;
  }
  return legacy_is_su_enabled();
}

static inline bool get_feature(uint32_t feature_id, uint64_t *out_value,
                               bool *out_supported) {
  struct ksu_get_feature_cmd cmd = {};
  cmd.feature_id = feature_id;
  if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
    return false;
  }
  if (out_value)
    *out_value = cmd.value;
  if (out_supported)
    *out_supported = cmd.supported;
  return true;
}

static inline bool set_feature(uint32_t feature_id, uint64_t value) {
  struct ksu_set_feature_cmd cmd = {};
  cmd.feature_id = feature_id;
  cmd.value = value;
  return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool set_kernel_umount_enabled(bool enabled) {
  return set_feature(KSU_FEATURE_KERNEL_UMOUNT, enabled ? 1 : 0);
}

bool is_kernel_umount_enabled() {
  uint64_t value = 0;
  bool supported = false;
  if (!get_feature(KSU_FEATURE_KERNEL_UMOUNT, &value, &supported)) {
    return false;
  }
  if (!supported) {
    return false;
  }
  return value != 0;
}

bool set_enhanced_security_enabled(bool enabled) {
  return set_feature(KSU_FEATURE_ENHANCED_SECURITY, enabled ? 1 : 0);
}

bool is_enhanced_security_enabled() {
  uint64_t value = 0;
  bool supported = false;
  if (!get_feature(KSU_FEATURE_ENHANCED_SECURITY, &value, &supported)) {
    return false;
  }
  if (!supported) {
    return false;
  }
  return value != 0;
}

bool set_sulog_enabled(bool enabled) {
  return set_feature(KSU_FEATURE_SULOG, enabled ? 1 : 0);
}

bool is_sulog_enabled() {
  uint64_t value = 0;
  bool supported = false;
  if (!get_feature(KSU_FEATURE_SULOG, &value, &supported)) {
    return false;
  }
  if (!supported) {
    return false;
  }
  return value != 0;
}

void get_hook_type(char *hook_type) { legacy_get_hook_type(hook_type, 32); }

void get_full_version(char *buff) {
  struct ksu_get_full_version_cmd cmd = {};
  if (ksuctl(KSU_IOCTL_GET_FULL_VERSION, &cmd) == 0) {
    strncpy(buff, cmd.version_full, KSU_FULL_VERSION_STRING - 1);
    buff[KSU_FULL_VERSION_STRING - 1] = '\0';
    return;
  }
  legacy_get_full_version(buff);
}


/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Copyright (C) 2023 bmax121. All Rights Reserved. */

#ifndef _KPU_SUPERCALL_H_
#define _KPU_SUPERCALL_H_

#include <unistd.h>
#include <sys/syscall.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <errno.h>

#include "uapi/scdefs.h"
#include "version.h"

static inline long ver_and_cmd(const char *key, long cmd)
{
    (void)key;
    uint32_t version_code = (MAJOR << 16) + (MINOR << 8) + PATCH;
    return ((long)version_code << 32) | (0x1158 << 16) | (cmd & 0xFFFF);
}

static inline long sc_hello(const char *key)
{
    if (!key || !key[0]) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_HELLO));
}

static inline bool sc_ready(const char *key)
{
    return sc_hello(key) == SUPERCALL_HELLO_MAGIC;
}

static inline long sc_su(const char *key, struct su_profile *profile)
{
    if (!key || !key[0]) return -EINVAL;
    if (strlen(profile->scontext) >= SUPERCALL_SCONTEXT_LEN) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_SU), profile);
}

/** KernelPatch version (sc 0x1008). */
static inline uint32_t sc_kp_ver(const char *key)
{
    if (!key || !key[0]) return 0;
    long ret = syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_KERNELPATCH_VER));
    return (uint32_t)ret;
}

/** Kernel version (sc 0x1009). */
static inline uint32_t sc_k_ver(const char *key)
{
    if (!key || !key[0]) return 0;
    long ret = syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_KERNEL_VER));
    return (uint32_t)ret;
}

/** Build time (sc 0x1007); write to buildtime buffer; return byte count or negative errno. */
static inline long sc_get_build_time(const char *key, char *buildtime, size_t len)
{
    if (!key || !key[0]) return -EINVAL;
    if (!buildtime || len == 0) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_BUILD_TIME), buildtime, len);
}

/** Current su path (sc 0x1110). */
static inline long sc_su_get_path(const char *key, char *out_path, int path_len)
{
    if (!key || !key[0]) return -EINVAL;
    if (!out_path || path_len <= 0) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_SU_GET_PATH), out_path, path_len);
}

/** Count of allowed UIDs (sc 0x1102). */
static inline long sc_su_uid_nums(const char *key)
{
    if (!key || !key[0]) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_SU_NUMS));
}

/** Allowed UID list (sc 0x1103); uids preallocated for num; return count written or negative errno. */
static inline long sc_su_allow_uids(const char *key, uid_t *uids, int num)
{
    if (!key || !key[0]) return -EINVAL;
    if (!uids || num <= 0) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_SU_LIST), uids, num);
}

/** Grant UID (sc 0x1100). */
static inline long sc_su_grant_uid(const char *key, struct su_profile *profile)
{
    if (!key || !key[0]) return -EINVAL;
    if (!profile) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_SU_GRANT_UID), profile);
}

/** Revoke UID (sc 0x1101). */
static inline long sc_su_revoke_uid(const char *key, uid_t uid)
{
    if (!key || !key[0]) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_SU_REVOKE_UID), uid);
}

/** Reset su path (sc 0x1111); so kernel uses path for su. Same as IcePatch. */
static inline long sc_su_reset_path(const char *key, const char *path)
{
    if (!key || !key[0]) return -EINVAL;
    if (!path) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_SU_RESET_PATH), path);
}

#endif

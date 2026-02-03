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

/** KernelPatch 版本号 (sc 0x1008) */
static inline uint32_t sc_kp_ver(const char *key)
{
    if (!key || !key[0]) return 0;
    long ret = syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_KERNELPATCH_VER));
    return (uint32_t)ret;
}

/** 内核版本号 (sc 0x1009) */
static inline uint32_t sc_k_ver(const char *key)
{
    if (!key || !key[0]) return 0;
    long ret = syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_KERNEL_VER));
    return (uint32_t)ret;
}

/** 构建时间 (sc 0x1007)，写入 buildtime 缓冲区，返回字节数或负错误码 */
static inline long sc_get_build_time(const char *key, char *buildtime, size_t len)
{
    if (!key || !key[0]) return -EINVAL;
    if (!buildtime || len == 0) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_BUILD_TIME), buildtime, len);
}

/** 当前 su 可执行路径 (sc 0x1110) */
static inline long sc_su_get_path(const char *key, char *out_path, int path_len)
{
    if (!key || !key[0]) return -EINVAL;
    if (!out_path || path_len <= 0) return -EINVAL;
    return syscall(__NR_supercall, key, ver_and_cmd(key, SUPERCALL_SU_GET_PATH), out_path, path_len);
}

#endif

/* SPDX-License-Identifier: GPL-2.0-or-later */
/* Copyright (C) 2023 bmax121. All Rights Reserved. */

#ifndef _KP_UAPI_SCDEF_H_
#define _KP_UAPI_SCDEF_H_

#define __NR_supercall 45
#define SUPERCALL_HELLO 0x1000
#define SUPERCALL_BUILD_TIME 0x1007
#define SUPERCALL_KERNELPATCH_VER 0x1008
#define SUPERCALL_KERNEL_VER 0x1009
#define SUPERCALL_SU 0x1010
#define SUPERCALL_SU_GET_PATH 0x1110
#define SUPERCALL_SU_GRANT_UID 0x1100
#define SUPERCALL_SU_REVOKE_UID 0x1101
#define SUPERCALL_SU_NUMS 0x1102
#define SUPERCALL_SU_LIST 0x1103
#define SUPERCALL_SCONTEXT_LEN 0x60
#define SUPERCALL_HELLO_MAGIC 0x11581158
#define SU_PATH_MAX_LEN 128

struct su_profile {
    unsigned int uid;
    unsigned int to_uid;
    char scontext[SUPERCALL_SCONTEXT_LEN];
};

#endif

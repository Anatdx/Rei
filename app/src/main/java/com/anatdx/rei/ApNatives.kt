package com.anatdx.rei

import androidx.annotation.Keep

/**
 * APatch/KernelPatch supercall JNI: auth and elevate via superkey.
 * nativeReady checks KP backend; nativeSu elevates current thread to root via superkey.
 */
object ApNatives {
    init {
        runCatching { System.loadLibrary("apjni") }
    }

    /** Whether KP backend is ready (supercall auth ok). */
    @Keep
    external fun nativeReady(superKey: String): Boolean

    /**
     * Elevate current thread to toUid (usually 0=root) via superkey.
     * @return 0 on success, negative errno
     */
    @Keep
    external fun nativeSu(superKey: String, toUid: Int, scontext: String?): Long

    /** Current su path (from KP backend). */
    @Keep
    external fun nativeSuPath(superKey: String): String

    /** Reset su path in kernel (sc 0x1111); so authorized apps running su get root. Same as IcePatch. */
    @Keep
    private external fun nativeResetSuPath(superKey: String, path: String): Boolean

    /** KernelPatch version (e.g. 0x000a0700 for 0.10.7). */
    @Keep
    external fun nativeKernelPatchVersion(superKey: String): Long

    /** Diag string: hello, kp_ver, kernel_ver, build_time. */
    @Keep
    external fun nativeDiag(superKey: String): String

    /** Allowed UID list (KP supercall 0x1102+0x1103), fallback. */
    @Keep
    external fun nativeSuUids(superKey: String): IntArray

    /** Grant UID (KP supercall 0x1100), fallback. */
    @Keep
    external fun nativeGrantSu(superKey: String, uid: Int, toUid: Int, scontext: String?): Long

    /** Revoke UID (KP supercall 0x1101), fallback. */
    @Keep
    external fun nativeRevokeSu(superKey: String, uid: Int): Long

    fun ready(superKey: String): Boolean = runCatching { nativeReady(superKey) }.getOrElse { false }
    fun su(superKey: String, toUid: Int = 0, scontext: String? = null): Boolean =
        runCatching { nativeSu(superKey, toUid, scontext) == 0L }.getOrElse { false }
    fun suPath(superKey: String): String = runCatching { nativeSuPath(superKey) }.getOrElse { "" }
    fun resetSuPath(superKey: String, path: String): Boolean = runCatching { nativeResetSuPath(superKey, path) }.getOrElse { false }
    fun kernelPatchVersion(superKey: String): Long = runCatching { nativeKernelPatchVersion(superKey) }.getOrElse { 0L }
    fun diag(superKey: String): String = runCatching { nativeDiag(superKey) }.getOrElse { "" }

    fun suUids(superKey: String): IntArray = runCatching { nativeSuUids(superKey) }.getOrElse { intArrayOf() }
    fun grantSu(superKey: String, uid: Int, toUid: Int = 0, scontext: String? = null): Long =
        runCatching { nativeGrantSu(superKey, uid, toUid, scontext) }.getOrElse { -1L }
    fun revokeSu(superKey: String, uid: Int): Long =
        runCatching { nativeRevokeSu(superKey, uid) }.getOrElse { -1L }
}

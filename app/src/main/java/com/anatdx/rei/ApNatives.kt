package com.anatdx.rei

import androidx.annotation.Keep

/**
 * APatch/KernelPatch supercall JNI - 使用超级密钥通过 syscall 向内核鉴权并提权。
 * 与 IcePatch 一致：nativeReady 检测 KP 后端，nativeSu 用 superkey 将当前线程提权为 root。
 */
object ApNatives {
    init {
        runCatching { System.loadLibrary("apjni") }
    }

    /** KP 后端是否就绪（supercall 鉴权成功） */
    @Keep
    external fun nativeReady(superKey: String): Boolean

    /**
     * 使用超级密钥将当前线程提权为 toUid（通常 0=root）。
     * @return 0 成功，负数错误码
     */
    @Keep
    external fun nativeSu(superKey: String, toUid: Int, scontext: String?): Long

    /** 当前 su 可执行路径（KP 后端返回） */
    @Keep
    external fun nativeSuPath(superKey: String): String

    /** KernelPatch 版本号（如 0x000a0700 表示 0.10.7） */
    @Keep
    external fun nativeKernelPatchVersion(superKey: String): Long

    /** 诊断信息：hello / kp_ver / kernel_ver / build_time（多行文本） */
    @Keep
    external fun nativeDiag(superKey: String): String

    /** 已授权 UID 列表（KP supercall 0x1102+0x1103），备用方案 */
    @Keep
    external fun nativeSuUids(superKey: String): IntArray

    /** 授权 UID（KP supercall 0x1100），备用方案 */
    @Keep
    external fun nativeGrantSu(superKey: String, uid: Int, toUid: Int, scontext: String?): Long

    /** 撤销 UID（KP supercall 0x1101），备用方案 */
    @Keep
    external fun nativeRevokeSu(superKey: String, uid: Int): Long

    fun ready(superKey: String): Boolean = runCatching { nativeReady(superKey) }.getOrElse { false }
    fun su(superKey: String, toUid: Int = 0, scontext: String? = null): Boolean =
        runCatching { nativeSu(superKey, toUid, scontext) == 0L }.getOrElse { false }
    fun suPath(superKey: String): String = runCatching { nativeSuPath(superKey) }.getOrElse { "" }
    fun kernelPatchVersion(superKey: String): Long = runCatching { nativeKernelPatchVersion(superKey) }.getOrElse { 0L }
    fun diag(superKey: String): String = runCatching { nativeDiag(superKey) }.getOrElse { "" }

    fun suUids(superKey: String): IntArray = runCatching { nativeSuUids(superKey) }.getOrElse { intArrayOf() }
    fun grantSu(superKey: String, uid: Int, toUid: Int = 0, scontext: String? = null): Long =
        runCatching { nativeGrantSu(superKey, uid, toUid, scontext) }.getOrElse { -1L }
    fun revokeSu(superKey: String, uid: Int): Long =
        runCatching { nativeRevokeSu(superKey, uid) }.getOrElse { -1L }
}

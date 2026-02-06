package com.anatdx.rei

import androidx.annotation.Keep

/**
 * KernelSU JNI: KSU API via reijni (same .so as ApNatives, different JNI entry points).
 * Loads reijni, no separate libkernelsu.
 */
object KsuNatives {
    init {
        System.loadLibrary("reijni")
    }

    @Keep
    private external fun nGetVersion(): Int
    @Keep
    private external fun nGetAllowList(): IntArray
    @Keep
    private external fun isSafeModeNative(): Boolean
    @Keep
    private external fun isManagerNative(): Boolean
    @Keep
    private external fun isKsuDriverPresentNative(): Boolean
    @Keep
    private external fun nSetAppProfile(profile: Profile): Boolean

    val version: Int get() = runCatching { nGetVersion() }.getOrElse { 0 }

    val allowList: IntArray get() = runCatching { nGetAllowList() }.getOrNull() ?: intArrayOf()

    val isSafeMode: Boolean get() = runCatching { isSafeModeNative() }.getOrElse { false }

    val isLkmMode: Boolean get() = false

    val isManager: Boolean get() = runCatching { isManagerNative() }.getOrElse { false }

    val isKsuDriverPresent: Boolean get() = runCatching { isKsuDriverPresentNative() }.getOrElse { false }

    val lastErrno: Int get() = 0

    fun getAppProfile(key: String, uid: Int): Profile? = null

    fun setAppProfile(profile: Profile): Boolean =
        runCatching { nSetAppProfile(profile) }.getOrElse { false }

    data class Profile(
        val name: String = "",
        val currentUid: Int = 0,
        val allowSu: Boolean = false,
        val nonRootUseDefault: Boolean = true,
    )
}

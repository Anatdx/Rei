package com.anatdx.rei

/**
 * KernelSU JNI bridge (YukiSU-style).
 *
 * This matches the kernel communication model used by YukiSU manager:
 * - `System.loadLibrary("kernelsu")`
 * - direct ioctl via native code (not via ksud CLI)
 */
object KsuNatives {
    init {
        System.loadLibrary("kernelsu")
    }

    val version: Int
        external get

    val allowList: IntArray
        external get

    val isSafeMode: Boolean
        external get

    val isLkmMode: Boolean
        external get

    val isManager: Boolean
        external get

    val isKsuDriverPresent: Boolean
        external get

    val lastErrno: Int
        external get

    external fun getAppProfile(key: String, uid: Int): Profile?
    external fun setAppProfile(profile: Profile): Boolean

    data class Profile(
        val name: String = "",
        val currentUid: Int = 0,
        val allowSu: Boolean = false,
        val nonRootUseDefault: Boolean = true,
    )
}


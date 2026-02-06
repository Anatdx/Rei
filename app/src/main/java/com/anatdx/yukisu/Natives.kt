package com.anatdx.yukisu

import androidx.annotation.Keep

/**
 * YukiSU kernelsu JNI (libkernelsu.so from YukiSU manager sources).
 * Used for allowlist screen when reid exec returns errno=1.
 */
object Natives {
    init {
        System.loadLibrary("kernelsu")
    }

    @Keep
    val version: Int
        external get

    @Keep
    val allowList: IntArray
        external get

    @Keep
    val isManager: Boolean
        external get

    @Keep
    external fun uidShouldUmount(uid: Int): Boolean

    @Keep
    external fun setAppProfile(profile: Profile?): Boolean

    @Keep
    data class Profile(
        val name: String = "",
        val currentUid: Int = 0,
        val allowSu: Boolean = false,
        val rootUseDefault: Boolean = true,
        val rootTemplate: String? = null,
        val uid: Int = 0,
        val gid: Int = 0,
        val groups: List<Int> = emptyList(),
        val capabilities: List<Int> = emptyList(),
        val context: String = "u:r:su:s0",
        val namespace: Int = 0,
        val nonRootUseDefault: Boolean = true,
        val umountModules: Boolean = true,
    )
}

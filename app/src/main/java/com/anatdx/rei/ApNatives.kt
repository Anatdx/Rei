package com.anatdx.rei

import android.util.Log
import androidx.annotation.Keep

/** Rei JNI: AP/KP supercall only. KSU via [KsuNatives] (same reijni, different JNI entries). */
object ApNatives {
    private val libLoaded: Boolean = runCatching { System.loadLibrary("reijni") }.fold(
        onSuccess = { true },
        onFailure = { e -> Log.e("Rei", "loadLibrary(reijni) failed", e); false }
    )

    @Keep
    external fun nativeReady(superKey: String): Boolean

    @Keep
    external fun nativeSu(superKey: String, toUid: Int, scontext: String?): Long

    @Keep
    external fun nativeSuPath(superKey: String): String

    @Keep
    private external fun nativeResetSuPath(superKey: String, path: String): Boolean

    @Keep
    external fun nativeKernelPatchVersion(superKey: String): Long

    @Keep
    external fun nativeDiag(superKey: String): String

    @Keep
    external fun nativeSuUids(superKey: String): IntArray

    @Keep
    external fun nativeGrantSu(superKey: String, uid: Int, toUid: Int, scontext: String?): Long

    @Keep
    external fun nativeRevokeSu(superKey: String, uid: Int): Long

    fun ready(superKey: String): Boolean {
        if (!libLoaded) return false
        return runCatching { nativeReady(superKey) }.getOrElse { e ->
            Log.e("Rei", "nativeReady failed", e)
            false
        }
    }
    fun su(superKey: String, toUid: Int = 0, scontext: String? = null): Boolean =
        runCatching { nativeSu(superKey, toUid, scontext) == 0L }.getOrElse { false }
    fun suPath(superKey: String): String = runCatching { nativeSuPath(superKey) }.getOrElse { "" }
    fun resetSuPath(superKey: String, path: String): Boolean = runCatching { nativeResetSuPath(superKey, path) }.getOrElse { false }
    fun kernelPatchVersion(superKey: String): Long = runCatching { nativeKernelPatchVersion(superKey) }.getOrElse { 0L }
    fun diag(superKey: String): String = runCatching { nativeDiag(superKey) }.getOrElse { "" }

    fun suUids(superKey: String): IntArray = runCatching { nativeSuUids(superKey) }.getOrNull() ?: intArrayOf()
    fun grantSu(superKey: String, uid: Int, toUid: Int = 0, scontext: String? = null): Long =
        runCatching { nativeGrantSu(superKey, uid, toUid, scontext) }.getOrElse { -1L }
    fun revokeSu(superKey: String, uid: Int): Long =
        runCatching { nativeRevokeSu(superKey, uid) }.getOrElse { -1L }
}

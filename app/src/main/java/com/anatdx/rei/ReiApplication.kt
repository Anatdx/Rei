package com.anatdx.rei

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.anatdx.rei.core.auth.ReiKeyHelper
import io.murasaki.Murasaki

/**
 * Application for Rei: holds APatch superkey and shared prefs.
 * When backend is AP/KernelPatch, superkey is used for supercall auth.
 */
class ReiApplication : Application() {

    companion object {
        const val SP_NAME = "rei_config"

        @Volatile
        lateinit var reiApp: ReiApplication
            private set

        lateinit var sharedPreferences: SharedPreferences
            private set

        /** APatch/KernelPatch superkey; empty when not set or backend is YukiSU only. */
        var superKey: String = ""
            set(value) {
                field = value
                if (value.isNotEmpty()) {
                    ReiKeyHelper.writeSuperKey(value)
                }
            }

        /** Root 实现：ksu = 仅 KernelSU（不创建 apd 硬链接），apatch = KernelPatch/APatch（创建 apd）。 */
        var rootImplementation: String
            get() = sharedPreferences.getString(KEY_ROOT_IMPL, VALUE_ROOT_IMPL_APATCH) ?: VALUE_ROOT_IMPL_APATCH
            set(value) {
                sharedPreferences.edit { putString(KEY_ROOT_IMPL, value) }
            }

        const val KEY_ROOT_IMPL = "root_impl"
        const val VALUE_ROOT_IMPL_KSU = "ksu"
        const val VALUE_ROOT_IMPL_APATCH = "apatch"
    }

    override fun onCreate() {
        super.onCreate()
        reiApp = this
        sharedPreferences = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        ReiKeyHelper.setSharedPreferences(this, SP_NAME)
        superKey = ReiKeyHelper.readSuperKey()
        // 初始化 Murasaki：直连 ksud 或通过 Zygisk 桥接（murasaki-zygisk-bridge）获取 Binder
        Thread { Murasaki.init(packageName) }.start()
    }
}

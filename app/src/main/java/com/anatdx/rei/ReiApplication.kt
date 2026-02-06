package com.anatdx.rei

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import com.anatdx.rei.core.auth.ReiKeyHelper
import io.murasaki.Murasaki

/**
 * Rei Application：持有超级密钥与 SP。密钥保存即落盘；鉴权结果仅用于首页状态展示。
 */
class ReiApplication : Application() {

    companion object {
        const val SP_NAME = "rei_config"

        @Volatile
        lateinit var reiApp: ReiApplication
            private set

        lateinit var sharedPreferences: SharedPreferences
            private set

        /** 超级密钥；保存即写入存储，不依赖 nativeReady。鉴权在首页刷新时用于显示 KP 状态。 */
        var superKey: String = ""
            set(value) {
                if (value.isEmpty()) {
                    field = value
                    ReiKeyHelper.clearSuperKey()
                    return
                }
                field = value
                ReiKeyHelper.writeSuperKey(value)
                if (rootImplementation == VALUE_ROOT_IMPL_APATCH) {
                    ApNatives.ready(value)
                    ApNatives.resetSuPath(value, LEGACY_SU_PATH)
                }
            }

        /** 根实现：ksu=仅 KernelSU，apatch=KernelPatch/APatch。 */
        var rootImplementation: String
            get() = sharedPreferences.getString(KEY_ROOT_IMPL, VALUE_ROOT_IMPL_APATCH) ?: VALUE_ROOT_IMPL_APATCH
            set(value) {
                sharedPreferences.edit { putString(KEY_ROOT_IMPL, value) }
            }

        /** 应用语言：空=系统，或 BCP 47 如 zh、en。 */
        var appLanguage: String
            get() = sharedPreferences.getString(KEY_APP_LANG, "") ?: ""
            set(value) {
                sharedPreferences.edit { putString(KEY_APP_LANG, value) }
            }

        const val KEY_ROOT_IMPL = "root_impl"
        const val KEY_APP_LANG = "app_lang"
        const val VALUE_ROOT_IMPL_KSU = "ksu"
        const val VALUE_ROOT_IMPL_APATCH = "apatch"

        private const val LEGACY_SU_PATH = "/system/bin/su"
    }

    override fun onCreate() {
        super.onCreate()
        reiApp = this
        sharedPreferences = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        ReiKeyHelper.setSharedPreferences(this, SP_NAME)
        superKey = ReiKeyHelper.readSuperKey()
        applyAppLanguage()
        Thread { Murasaki.init(packageName) }.start()
    }

    private fun applyAppLanguage() {
        val tag = appLanguage.trim()
        if (tag.isEmpty()) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        } else {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}

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

        /** Root impl: ksu = KernelSU only (no apd link), apatch = KernelPatch/APatch (create apd). */
        var rootImplementation: String
            get() = sharedPreferences.getString(KEY_ROOT_IMPL, VALUE_ROOT_IMPL_APATCH) ?: VALUE_ROOT_IMPL_APATCH
            set(value) {
                sharedPreferences.edit { putString(KEY_ROOT_IMPL, value) }
            }

        /** App language: empty = system, or BCP 47 tag (e.g. zh, zh-TW, en, ja, fr, ru, ko, es). */
        var appLanguage: String
            get() = sharedPreferences.getString(KEY_APP_LANG, "") ?: ""
            set(value) {
                sharedPreferences.edit { putString(KEY_APP_LANG, value) }
            }

        const val KEY_ROOT_IMPL = "root_impl"
        const val KEY_APP_LANG = "app_lang"
        const val VALUE_ROOT_IMPL_KSU = "ksu"
        const val VALUE_ROOT_IMPL_APATCH = "apatch"
    }

    override fun onCreate() {
        super.onCreate()
        reiApp = this
        sharedPreferences = getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
        ReiKeyHelper.setSharedPreferences(this, SP_NAME)
        superKey = ReiKeyHelper.readSuperKey()
        applyAppLanguage()
        // Init Murasaki: direct ksud or Zygisk bridge for Binder
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

package com.anatdx.rei.core.auth

import android.content.Context
import com.anatdx.rei.ui.auth.AuthLevel

object AppAuthStore {
    private const val PREF = "rei_app_auth"
    private const val KEY_PREFIX = "level:"

    fun getLevel(context: Context, packageName: String): AuthLevel {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val v = sp.getInt(KEY_PREFIX + packageName, AuthLevel.Basic.ordinal)
        return AuthLevel.entries.getOrNull(v) ?: AuthLevel.Basic
    }

    fun setLevel(context: Context, packageName: String, level: AuthLevel) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_PREFIX + packageName, level.ordinal).apply()
    }

    fun clear(context: Context) {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().clear().apply()
    }
}


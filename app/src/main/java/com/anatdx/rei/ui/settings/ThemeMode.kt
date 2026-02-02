package com.anatdx.rei.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

enum class ThemeMode {
    System,
    Light,
    Dark;

    companion object {
        @Composable
        fun resolveDark(mode: ThemeMode): Boolean {
            return when (mode) {
                System -> isSystemInDarkTheme()
                Light -> false
                Dark -> true
            }
        }
    }
}


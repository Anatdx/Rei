package com.anatdx.rei.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private fun lightScheme(preset: ThemePreset) = when (preset) {
    ThemePreset.Purple -> lightColorScheme(
        primary = Purple40,
        secondary = PurpleGrey40,
        tertiary = Pink40,
    )
    ThemePreset.IceBlue -> lightColorScheme(
        primary = IceBlue40,
        secondary = IceBlueSecondary40,
        tertiary = IceBlueTertiary40,
    )
    ThemePreset.Emerald -> lightColorScheme(
        primary = Emerald40,
        secondary = EmeraldSecondary40,
        tertiary = EmeraldTertiary40,
    )
    ThemePreset.Sunset -> lightColorScheme(
        primary = Sunset40,
        secondary = SunsetSecondary40,
        tertiary = SunsetTertiary40,
    )
}

private fun darkScheme(preset: ThemePreset) = when (preset) {
    ThemePreset.Purple -> darkColorScheme(
        primary = Purple80,
        secondary = PurpleGrey80,
        tertiary = Pink80,
    )
    ThemePreset.IceBlue -> darkColorScheme(
        primary = IceBlue80,
        secondary = IceBlueSecondary80,
        tertiary = IceBlueTertiary80,
    )
    ThemePreset.Emerald -> darkColorScheme(
        primary = Emerald80,
        secondary = EmeraldSecondary80,
        tertiary = EmeraldTertiary80,
    )
    ThemePreset.Sunset -> darkColorScheme(
        primary = Sunset80,
        secondary = SunsetSecondary80,
        tertiary = SunsetTertiary80,
    )
}

@Composable
fun ReiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    preset: ThemePreset = ThemePreset.IceBlue,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkScheme(preset)
        else -> lightScheme(preset)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
package com.anatdx.rei

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.anatdx.rei.ui.theme.ReiTheme
import com.anatdx.rei.ui.ReiApp
import com.anatdx.rei.ui.settings.ThemeMode
import com.anatdx.rei.ui.theme.ThemePreset
import com.anatdx.rei.core.root.RootAccessState
import com.anatdx.rei.core.root.RootResult
import com.anatdx.rei.core.root.RootShell
import com.anatdx.rei.core.reid.ReidLauncher

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeMode by rememberSaveable { mutableStateOf(ThemeMode.System) }
            var dynamicColor by rememberSaveable { mutableStateOf(true) }
            var themePreset by rememberSaveable { mutableStateOf(ThemePreset.IceBlue) }

            val isDarkTheme = ThemeMode.resolveDark(themeMode)

            var rootNonce by remember { mutableStateOf(0) }
            var rootState by remember { mutableStateOf<RootAccessState>(RootAccessState.Requesting) }

            LaunchedEffect(rootNonce) {
                // Silent background root probe (no in-app dialog). This may still trigger the
                // system/root-manager prompt depending on the backend.
                rootState = RootAccessState.Requesting
                rootState = when (val r = RootShell.request()) {
                    is RootResult.Granted -> {
                        ReidLauncher.start(this@MainActivity)
                        RootAccessState.Granted(r.stdout)
                    }
                    is RootResult.Denied -> RootAccessState.Denied(r.reason)
                }
            }

            ReiTheme(
                darkTheme = isDarkTheme,
                dynamicColor = dynamicColor,
                preset = themePreset,
            ) {
                ReiApp(
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    themePreset = themePreset,
                    onThemeModeChange = { themeMode = it },
                    onDynamicColorChange = { dynamicColor = it },
                    onThemePresetChange = { themePreset = it },
                    rootAccessState = rootState,
                    onRefreshRoot = { rootNonce += 1 },
                )
            }
        }
    }
}
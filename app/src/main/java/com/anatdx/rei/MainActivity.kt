package com.anatdx.rei

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
class MainActivity : AppCompatActivity() {
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
                rootState = RootAccessState.Requesting
                // Request su silently (no UI dialog in-app; provider may still prompt).
                // 不再在获得 root 时自动安装 reid，由用户在首页点击「安装系统补丁」触发。
                when (val r = RootShell.request()) {
                    is RootResult.Granted -> {
                        rootState = RootAccessState.Granted(r.stdout)
                    }
                    is RootResult.Denied -> {
                        rootState = RootAccessState.Denied(r.reason)
                    }
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
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
import com.anatdx.rei.core.reid.ReidClient

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
                rootState = RootAccessState.Requesting

                // Step 1: Ask backend (no su) to allow this manager UID.
                val uid = android.os.Process.myUid()
                val pkg = this@MainActivity.packageName
                val check = ReidClient.execDirect(this@MainActivity, listOf("profile", "uid-granted", uid.toString()), timeoutMs = 5_000L)
                if (check.exitCode != 0) {
                    ReidClient.execDirect(this@MainActivity, listOf("profile", "set-allow", uid.toString(), pkg, "1"), timeoutMs = 5_000L)
                }

                // Step 2: Now request su (KernelSU su should consult allowlist).
                rootState = when (val r = RootShell.request()) {
                    is RootResult.Granted -> RootAccessState.Granted(r.stdout)
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
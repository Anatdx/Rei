package com.anatdx.rei.ui

import androidx.annotation.StringRes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.anatdx.rei.R
import com.anatdx.rei.core.root.RootAccessState
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.rei.ui.modules.ModulesScreen
import com.anatdx.rei.ui.home.HomeScreen
import com.anatdx.rei.ui.logs.LogsScreen
import com.anatdx.rei.ui.auth.AppAccessListScreen
import com.anatdx.rei.ui.settings.SettingsScreen
import com.anatdx.rei.ui.settings.ThemeMode
import com.anatdx.rei.ui.theme.ThemePreset
import com.anatdx.rei.ui.theme.ReiBackgroundLayer
import com.anatdx.rei.ui.theme.rememberBackgroundConfig
import com.anatdx.rei.ui.theme.LocalReiChromeStyle
import com.anatdx.rei.ui.theme.chromeSurfaceColor
import com.anatdx.rei.ui.theme.rememberChromeStyleConfig
import com.anatdx.rei.ui.tools.BootToolsScreen
import com.anatdx.rei.ui.patches.PatchesScreen
import kotlinx.coroutines.launch

private sealed class ReiDest(
    val route: String,
    @param:StringRes val labelRes: Int,
    val icon: @Composable () -> Unit,
    val selectedIcon: @Composable () -> Unit,
    @param:StringRes val titleRes: Int = labelRes,
) {
    data object Home : ReiDest(
        route = "home",
        labelRes = R.string.nav_home,
        titleRes = R.string.title_home,
        icon = { Icon(Icons.Outlined.Home, contentDescription = null) },
        selectedIcon = { Icon(Icons.Filled.Home, contentDescription = null) },
    )

    data object Modules : ReiDest(
        route = "modules",
        labelRes = R.string.nav_modules,
        titleRes = R.string.title_modules,
        icon = { Icon(Icons.Outlined.Extension, contentDescription = null) },
        selectedIcon = { Icon(Icons.Filled.Extension, contentDescription = null) },
    )

    data object AppAccess : ReiDest(
        route = "app_access",
        labelRes = R.string.nav_app_access,
        titleRes = R.string.title_app_access,
        icon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
        selectedIcon = { Icon(Icons.Outlined.Shield, contentDescription = null) },
    )

    data object Settings : ReiDest(
        route = "settings",
        labelRes = R.string.nav_settings,
        titleRes = R.string.title_settings,
        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
        selectedIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReiApp(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    themePreset: ThemePreset,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onThemePresetChange: (ThemePreset) -> Unit,
    rootAccessState: RootAccessState,
    onRefreshRoot: () -> Unit,
) {
    val navController = rememberNavController()
    val destinations = listOf(ReiDest.Home, ReiDest.Modules, ReiDest.AppAccess, ReiDest.Settings)
    val ctx = LocalContext.current
    val bgConfig = rememberBackgroundConfig(ctx)
    val chromeStyle = rememberChromeStyleConfig(ctx)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showPowerMenu by remember { mutableStateOf(false) }

    val isHome = currentRoute == null || currentRoute == ReiDest.Home.route
    val isSettingsChild = currentRoute?.startsWith("settings/") == true
    val topTitle = when {
        isHome -> stringResource(R.string.app_name)
        currentRoute == ReiDest.Modules.route -> stringResource(ReiDest.Modules.titleRes)
        currentRoute == ReiDest.Settings.route -> stringResource(ReiDest.Settings.titleRes)
        currentRoute == ReiDest.AppAccess.route -> stringResource(R.string.title_app_access)
        currentRoute == "settings/logs" -> stringResource(R.string.title_logs)
        currentRoute == "settings/boot_tools" -> stringResource(R.string.title_boot_tools)
        currentRoute == "settings/patches" -> "KP 修补"
        else -> stringResource(R.string.app_name)
    }

    val darkTheme = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }

    CompositionLocalProvider(LocalReiChromeStyle provides chromeStyle) {
        ReiBackgroundLayer(config = bgConfig, darkTheme = darkTheme) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing,
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = chromeSurfaceColor(
                                base = MaterialTheme.colorScheme.surface,
                                darkTheme = darkTheme,
                                level = chromeStyle.level,
                            ),
                            scrolledContainerColor = chromeSurfaceColor(
                                base = MaterialTheme.colorScheme.surface,
                                darkTheme = darkTheme,
                                level = chromeStyle.level,
                            ),
                        ),
                    navigationIcon = {
                        if (isSettingsChild && navController.previousBackStackEntry != null) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                            }
                        }
                    },
                    title = {
                        Text(
                            text = topTitle,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    actions = {
                        if (isHome) {
                            IconButton(onClick = { showPowerMenu = true }) {
                                Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
                            }
                        }
                    },
                )
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = chromeSurfaceColor(
                            base = MaterialTheme.colorScheme.surface,
                            darkTheme = darkTheme,
                            level = chromeStyle.level,
                        ),
                        tonalElevation = 0.dp,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                    destinations.forEach { dest ->
                        val selected = currentRoute == dest.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { if (selected) dest.selectedIcon() else dest.icon() },
                            label = { Text(stringResource(dest.labelRes)) },
                        )
                    }
                    }
                },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = ReiDest.Home.route,
                    enterTransition = { fadeIn(tween(120)) },
                    exitTransition = { fadeOut(tween(120)) },
                    popEnterTransition = { fadeIn(tween(120)) },
                    popExitTransition = { fadeOut(tween(120)) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    composable(ReiDest.Home.route) {
                        HomeScreen(
                            rootAccessState = rootAccessState,
                            onRefreshRoot = onRefreshRoot,
                            onOpenSettings = { navController.navigate(ReiDest.Settings.route) },
                            onOpenLogs = { navController.navigate("settings/logs") },
                            onOpenBootTools = { navController.navigate("settings/boot_tools") },
                        )
                    }
                    composable(ReiDest.Modules.route) { ModulesScreen(rootAccessState) }
                    composable(ReiDest.AppAccess.route) { AppAccessListScreen() }
                    composable(ReiDest.Settings.route) {
                        SettingsScreen(
                            themeMode = themeMode,
                            dynamicColor = dynamicColor,
                            themePreset = themePreset,
                            onThemeModeChange = onThemeModeChange,
                            onDynamicColorChange = onDynamicColorChange,
                            onThemePresetChange = onThemePresetChange,
                            onOpenLogs = { navController.navigate("settings/logs") },
                            onOpenBootTools = { navController.navigate("settings/boot_tools") },
                            onOpenPatches = { navController.navigate("settings/patches") },
                        )
                    }
                    composable("settings/logs") { LogsScreen() }
                    composable("settings/boot_tools") { BootToolsScreen(rootAccessState) }
                    composable("settings/patches") {
                        PatchesScreen(rootGranted = rootAccessState is RootAccessState.Granted)
                    }
                }

                if (showPowerMenu) {
                    PowerMenuDialog(
                        onDismiss = { showPowerMenu = false },
                        onAction = { action ->
                            showPowerMenu = false
                            scope.launch {
                                if (rootAccessState !is RootAccessState.Granted) {
                                    snackbarHostState.showSnackbar("需要 Root 权限")
                                    return@launch
                                }
                                val cmd = when (action) {
                                    "重启" -> "reboot"
                                    "Recovery" -> "reboot recovery"
                                    "Bootloader" -> "reboot bootloader"
                                    "关机" -> "reboot -p"
                                    else -> null
                                }
                                if (cmd == null) {
                                    snackbarHostState.showSnackbar("未知操作：$action")
                                    return@launch
                                }
                                val args = when (cmd) {
                                    "reboot" -> listOf("kernel", "reboot")
                                    "reboot recovery" -> listOf("kernel", "reboot", "recovery")
                                    "reboot bootloader" -> listOf("kernel", "reboot", "bootloader")
                                    "reboot -p" -> listOf("kernel", "reboot", "poweroff")
                                    else -> emptyList()
                                }
                                if (args.isEmpty()) {
                                    snackbarHostState.showSnackbar("未知操作：$action")
                                    return@launch
                                }
                                val r = ReidClient.exec(ctx, args, timeoutMs = 10_000L)
                                snackbarHostState.showSnackbar("执行：$action（exit=${r.exitCode}）")
                            }
                        },
                    )
                }
            }
        }
    }
}


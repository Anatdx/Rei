package com.anatdx.rei.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.anatdx.rei.ui.theme.ThemePreset
import com.anatdx.rei.ui.theme.Emerald40
import com.anatdx.rei.ui.theme.IceBlue40
import com.anatdx.rei.ui.theme.Purple40
import com.anatdx.rei.ui.theme.Sunset40
import com.anatdx.rei.ui.components.ReiCard
import com.anatdx.rei.ui.theme.BackgroundManager
import com.anatdx.rei.ui.theme.BackgroundPrefs
import com.anatdx.rei.ui.theme.rememberBackgroundConfig
import com.anatdx.rei.ui.theme.rememberChromeStyleConfig
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.auth.ReiKeyHelper
import com.anatdx.rei.core.reid.ReidClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    themePreset: ThemePreset,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onThemePresetChange: (ThemePreset) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBootTools: () -> Unit,
    onOpenPatches: () -> Unit = {},
) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

        item {
            SuperkeyCard()
        }

        item {
            RootImplCard()
        }

        item {
            ManageCard(
                onOpenLogs = onOpenLogs,
                onOpenBootTools = onOpenBootTools,
                onOpenPatches = onOpenPatches,
            )
        }

        item {
            AppearanceCard(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                themePreset = themePreset,
                onThemeModeChange = onThemeModeChange,
                onDynamicColorChange = onDynamicColorChange,
                onThemePresetChange = onThemePresetChange,
            )
        }
        item { AboutCard() }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    themePreset: ThemePreset,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onThemePresetChange: (ThemePreset) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bg = rememberBackgroundConfig(ctx)
    val chrome = rememberChromeStyleConfig(ctx)
    // Use system file picker (not media/photo picker).
    val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val path = withContext(Dispatchers.IO) { BackgroundManager.saveBackground(ctx, uri) }
            if (path != null) {
                BackgroundPrefs.setPath(ctx, path)
                BackgroundPrefs.setEnabled(ctx, true)
            }
        }
    }

    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("外观") },
                supportingContent = { Text("主题模式 / 动态取色 / 主题色") },
                leadingContent = { Icon(Icons.Outlined.ColorLens, contentDescription = null) },
                trailingContent = {
                    Icon(
                        if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { expanded = !expanded },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    ThemeModeRow(
                        title = "跟随系统",
                        selected = themeMode == ThemeMode.System,
                        onClick = { onThemeModeChange(ThemeMode.System) },
                    )
                    ThemeModeRow(
                        title = "浅色",
                        selected = themeMode == ThemeMode.Light,
                        onClick = { onThemeModeChange(ThemeMode.Light) },
                    )
                    ThemeModeRow(
                        title = "深色",
                        selected = themeMode == ThemeMode.Dark,
                        onClick = { onThemeModeChange(ThemeMode.Dark) },
                    )

                    ListItem(
                        headlineContent = { Text("动态取色 (Android 12+)") },
                        supportingContent = { Text("开启后跟随系统壁纸配色（开启时主题色选项不生效）") },
                        leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = dynamicColor,
                                onCheckedChange = onDynamicColorChange,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )

                    AnimatedVisibility(
                        visible = !dynamicColor,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            ThemePresetRow(
                                title = "冰蓝",
                                previewColor = IceBlue40,
                                selected = themePreset == ThemePreset.IceBlue,
                                onClick = { onThemePresetChange(ThemePreset.IceBlue) },
                            )
                            ThemePresetRow(
                                title = "翡翠",
                                previewColor = Emerald40,
                                selected = themePreset == ThemePreset.Emerald,
                                onClick = { onThemePresetChange(ThemePreset.Emerald) },
                            )
                            ThemePresetRow(
                                title = "落日",
                                previewColor = Sunset40,
                                selected = themePreset == ThemePreset.Sunset,
                                onClick = { onThemePresetChange(ThemePreset.Sunset) },
                            )
                            ThemePresetRow(
                                title = "紫色（默认）",
                                previewColor = Purple40,
                                selected = themePreset == ThemePreset.Purple,
                                onClick = { onThemePresetChange(ThemePreset.Purple) },
                            )
                        }
                    }

                    val bgName = bg.path?.let { runCatching { File(it).name }.getOrNull() } ?: "未选择"
                    ListItem(
                        headlineContent = { Text("背景图片") },
                        supportingContent = { Text(if (bg.enabled) "已启用：$bgName" else "关闭（使用默认背景）") },
                        leadingContent = { Icon(Icons.Outlined.Image, contentDescription = null) },
                        trailingContent = {
                            Switch(
                                checked = bg.enabled,
                                onCheckedChange = { BackgroundPrefs.setEnabled(ctx, it) },
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (bg.path?.isNotBlank() == true) {
                            Text(
                                text = "清除",
                                modifier = Modifier
                                    .align(Alignment.CenterStart)
                                    .clickable {
                                        BackgroundManager.clearBackground(ctx)
                                        BackgroundPrefs.setPath(ctx, null)
                                        BackgroundPrefs.setEnabled(ctx, false)
                                    }
                                    .padding(vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        FloatingActionButton(
                            onClick = { pickBg.launch(arrayOf("image/*")) },
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        }
                    }

                    AnimatedVisibility(visible = bg.enabled) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                text = "透明度",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = bg.alpha.coerceIn(0.2f, 1.0f),
                                onValueChange = { BackgroundPrefs.setAlpha(ctx, it.coerceIn(0.2f, 1.0f)) },
                                valueRange = 0.2f..1.0f,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "遮罩",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Slider(
                                value = bg.dim.coerceIn(0.0f, 0.75f),
                                onValueChange = { BackgroundPrefs.setDim(ctx, it.coerceIn(0.0f, 0.75f)) },
                                valueRange = 0.0f..0.75f,
                            )
                        }
                    }

                    ListItem(
                        headlineContent = { Text("界面透明度") },
                        supportingContent = { Text("卡片主体 / 页首页脚 / 边缘高光（联动）") },
                        leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Slider(
                            value = chrome.level.coerceIn(0.0f, 1.0f),
                            onValueChange = { BackgroundPrefs.setChromeLevel(ctx, it.coerceIn(0.0f, 1.0f)) },
                            valueRange = 0.0f..1.0f,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RootImplCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootImpl = ReiApplication.rootImplementation

    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("Root 实现") },
                supportingContent = {
                    Text("KernelSU 与 KernelPatch/APatch 二选一：仅创建 ksud 或仅创建 apd 硬链接。")
                },
                leadingContent = { Icon(Icons.Outlined.Tune, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = rootImpl == ReiApplication.VALUE_ROOT_IMPL_KSU,
                    onClick = {
                        ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_KSU
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ReidClient.execReid(ctx, listOf("set-root-impl", "ksu"), timeoutMs = 5_000L)
                            }
                        }
                    },
                )
                Text(
                    "KernelSU",
                    modifier = Modifier.clickable {
                        ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_KSU
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ReidClient.execReid(ctx, listOf("set-root-impl", "ksu"), timeoutMs = 5_000L)
                            }
                        }
                    },
                )
                Spacer(Modifier.padding(horizontal = 24.dp))
                RadioButton(
                    selected = rootImpl == ReiApplication.VALUE_ROOT_IMPL_APATCH,
                    onClick = {
                        ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_APATCH
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ReidClient.execReid(ctx, listOf("set-root-impl", "apatch"), timeoutMs = 5_000L)
                            }
                        }
                    },
                )
                Text(
                    "KernelPatch (APatch)",
                    modifier = Modifier.clickable {
                        ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_APATCH
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                ReidClient.execReid(ctx, listOf("set-root-impl", "apatch"), timeoutMs = 5_000L)
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SuperkeyCard() {
    var input by rememberSaveable { mutableStateOf(ReiApplication.superKey) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("APatch SuperKey") },
                supportingContent = {
                    Text(
                        if (ReiApplication.superKey.isNotEmpty())
                            "已设置（用于 AP/KernelPatch 鉴权）"
                        else
                            "如果您已安装 KP 后端，请输入超级密钥来获得权限。8–63 位，需含字母和数字。"
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        validationError = when {
                            it.isEmpty() -> null
                            !ReiKeyHelper.isValidSuperKey(it) -> "需 8–63 位且含字母和数字"
                            else -> null
                        }
                    },
                    label = { Text("SuperKey") },
                    placeholder = { Text("可选，AP/KP 后端时必填") },
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f),
                    isError = validationError != null,
                    supportingText = validationError?.let { { Text(it) } },
                )
                Icon(
                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (keyVisible) "隐藏" else "显示",
                    modifier = Modifier
                        .size(24.dp)
                        .padding(start = 8.dp)
                        .clickable { keyVisible = !keyVisible },
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (ReiApplication.superKey.isNotEmpty()) {
                    TextButton(onClick = {
                        ReiApplication.superKey = ""
                        ReiKeyHelper.clearSuperKey()
                        input = ""
                        validationError = null
                    }) {
                        Text("清除")
                    }
                }
                TextButton(
                    onClick = {
                        if (input.isEmpty()) {
                            ReiApplication.superKey = ""
                            ReiKeyHelper.clearSuperKey()
                            validationError = null
                            return@TextButton
                        }
                        if (!ReiKeyHelper.isValidSuperKey(input)) {
                            validationError = "需 8–63 位且含字母和数字"
                            return@TextButton
                        }
                        ReiApplication.superKey = input
                        ReiKeyHelper.writeSuperKey(input)
                        validationError = null
                    }
                ) {
                    Text("保存")
                }
            }
        }
    }
}

@Composable
private fun ManageCard(
    onOpenLogs: () -> Unit,
    onOpenBootTools: () -> Unit,
    onOpenPatches: () -> Unit = {},
) {
    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("管理") },
                supportingContent = { Text("日志 / Boot 工具 / KP 修补") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("日志") },
                supportingContent = { Text("应用日志与命令输出") },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenLogs),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("分区管理") },
                supportingContent = { Text("查看、备份、刷写分区，管理 A/B 槽位") },
                leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenBootTools),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("KP 修补") },
                supportingContent = { Text("KernelPatch kpimg 信息 / 修补入口") },
                leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenPatches),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun ThemeModeRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = title,
            modifier = Modifier
                .padding(start = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun ThemePresetRow(
    title: String,
    previewColor: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(previewColor),
        )
        Text(
            text = title,
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AboutCard() {
    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("关于") },
                supportingContent = { Text("Rei · 多后端 Root 管理器（UI 原型）") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}


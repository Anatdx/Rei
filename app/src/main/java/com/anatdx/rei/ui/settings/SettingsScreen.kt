package com.anatdx.rei.ui.settings

import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.launch
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
import androidx.compose.ui.res.stringResource
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.app.Activity
import com.anatdx.rei.R
import com.anatdx.rei.ApNatives
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.auth.ReiKeyHelper
import com.anatdx.rei.core.reid.ReidClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.widget.Toast

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
            ManageCard(
                onOpenLogs = onOpenLogs,
                onOpenBootTools = onOpenBootTools,
                onOpenPatches = onOpenPatches,
            )
        }

        item {
            LanguageCard()
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

private val LANGUAGE_TAGS = listOf("", "zh", "zh-TW", "en", "ja", "fr", "ru", "ko", "es")

@Composable
private fun LanguageDisplayName(tag: String) {
    Text(
        when (tag) {
            "" -> stringResource(R.string.language_system)
            "zh" -> stringResource(R.string.language_zh)
            "zh-TW" -> stringResource(R.string.language_zh_tw)
            "en" -> stringResource(R.string.language_en)
            "ja" -> stringResource(R.string.language_ja)
            "fr" -> stringResource(R.string.language_fr)
            "ru" -> stringResource(R.string.language_ru)
            "ko" -> stringResource(R.string.language_ko)
            "es" -> stringResource(R.string.language_es)
            else -> tag.ifBlank { stringResource(R.string.language_system) }
        }
    )
}

@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    val currentTag = ReiApplication.appLanguage
    var showDialog by rememberSaveable { mutableStateOf(false) }

    ReiCard {
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_language)) },
            supportingContent = { LanguageDisplayName(currentTag) },
            leadingContent = { Icon(Icons.Outlined.Language, contentDescription = null) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForwardIos,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            modifier = Modifier.clickable { showDialog = true },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LANGUAGE_TAGS.forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    ReiApplication.appLanguage = tag
                                    if (tag.isEmpty()) {
                                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                                    } else {
                                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                                    }
                                    showDialog = false
                                    (context as? Activity)?.recreate()
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = currentTag == tag,
                                onClick = null,
                            )
                            Spacer(Modifier.size(8.dp))
                            LanguageDisplayName(tag)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
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
                headlineContent = { Text(stringResource(R.string.settings_appearance)) },
                supportingContent = { Text(stringResource(R.string.settings_appearance_desc)) },
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
                        title = stringResource(R.string.settings_theme_system),
                        selected = themeMode == ThemeMode.System,
                        onClick = { onThemeModeChange(ThemeMode.System) },
                    )
                    ThemeModeRow(
                        title = stringResource(R.string.settings_theme_light),
                        selected = themeMode == ThemeMode.Light,
                        onClick = { onThemeModeChange(ThemeMode.Light) },
                    )
                    ThemeModeRow(
                        title = stringResource(R.string.settings_theme_dark),
                        selected = themeMode == ThemeMode.Dark,
                        onClick = { onThemeModeChange(ThemeMode.Dark) },
                    )

                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_dynamic_color)) },
                        supportingContent = { Text(stringResource(R.string.settings_dynamic_color_desc)) },
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
                                title = stringResource(R.string.settings_preset_ice_blue),
                                previewColor = IceBlue40,
                                selected = themePreset == ThemePreset.IceBlue,
                                onClick = { onThemePresetChange(ThemePreset.IceBlue) },
                            )
                            ThemePresetRow(
                                title = stringResource(R.string.settings_preset_emerald),
                                previewColor = Emerald40,
                                selected = themePreset == ThemePreset.Emerald,
                                onClick = { onThemePresetChange(ThemePreset.Emerald) },
                            )
                            ThemePresetRow(
                                title = stringResource(R.string.settings_preset_sunset),
                                previewColor = Sunset40,
                                selected = themePreset == ThemePreset.Sunset,
                                onClick = { onThemePresetChange(ThemePreset.Sunset) },
                            )
                            ThemePresetRow(
                                title = stringResource(R.string.settings_preset_purple),
                                previewColor = Purple40,
                                selected = themePreset == ThemePreset.Purple,
                                onClick = { onThemePresetChange(ThemePreset.Purple) },
                            )
                        }
                    }

                    val bgName = bg.path?.let { runCatching { File(it).name }.getOrNull() } ?: stringResource(R.string.settings_bg_not_selected)
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_bg_image)) },
                        supportingContent = { Text(if (bg.enabled) stringResource(R.string.settings_bg_enabled, bgName) else stringResource(R.string.settings_bg_off)) },
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
                                text = stringResource(R.string.settings_clear),
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
                                text = stringResource(R.string.settings_opacity),
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
                                text = stringResource(R.string.settings_overlay),
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
                        headlineContent = { Text(stringResource(R.string.settings_ui_opacity)) },
                        supportingContent = { Text(stringResource(R.string.settings_ui_opacity_desc)) },
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
private fun SuperkeyCard() {
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf(ReiApplication.superKey) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    var validationError by rememberSaveable { mutableStateOf<String?>(null) }

    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_apatch_superkey)) },
                supportingContent = {
                    Text(
                        if (ReiApplication.superKey.isNotEmpty())
                            stringResource(R.string.settings_superkey_set)
                        else
                            stringResource(R.string.settings_superkey_hint_short)
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
                            !ReiKeyHelper.isValidSuperKey(it) -> ""
                            else -> null
                        }
                    },
                    label = { Text(stringResource(R.string.settings_superkey_label)) },
                    placeholder = { Text(stringResource(R.string.settings_superkey_placeholder)) },
                    visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f),
                    isError = validationError != null,
                    supportingText = validationError?.let { { Text(stringResource(R.string.settings_superkey_validation)) } },
                )
                Icon(
                    imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (keyVisible) stringResource(R.string.settings_visibility_hide) else stringResource(R.string.settings_visibility_show),
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
                        Text(stringResource(R.string.settings_clear))
                    }
                }
                TextButton(
                    onClick = {
                        if (input.isEmpty()) {
                            ReiApplication.superKey = ""
                            validationError = null
                            return@TextButton
                        }
                        if (!ReiKeyHelper.isValidSuperKey(input)) {
                            validationError = ""
                            return@TextButton
                        }
                        ReiApplication.superKey = input
                        validationError = null
                        Toast.makeText(ctx, ctx.getString(R.string.settings_superkey_saved), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(stringResource(R.string.settings_save))
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
                headlineContent = { Text(stringResource(R.string.settings_manage)) },
                supportingContent = { Text(stringResource(R.string.settings_manage_desc)) },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_logs)) },
                supportingContent = { Text(stringResource(R.string.settings_logs_desc)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenLogs),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.title_partition_manager)) },
                supportingContent = { Text(stringResource(R.string.settings_partition_manager_desc)) },
                leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenBootTools),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_kp_patch)) },
                supportingContent = { Text(stringResource(R.string.settings_kp_patch_desc)) },
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
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val ver = com.anatdx.rei.BuildConfig.VERSION_NAME
    val code = com.anatdx.rei.BuildConfig.VERSION_CODE

    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                supportingContent = { Text(stringResource(R.string.settings_about_desc)) },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                modifier = Modifier.clickable { showDialog = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.app_name))
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Version $ver ($code)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_about_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "Developed by Anatdx",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Based on KernelSU & APatch",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}


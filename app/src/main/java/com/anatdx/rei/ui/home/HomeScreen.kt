package com.anatdx.rei.ui.home

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.anatdx.rei.R
import com.anatdx.rei.core.log.ReiLog
import com.anatdx.rei.ApNatives
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.auth.ReiKeyHelper
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.rei.core.reid.ReidInstallStatus
import com.anatdx.rei.core.reid.ReidLauncher
import com.anatdx.rei.core.reid.ReidStartResult
import com.anatdx.rei.core.root.RootAccessState
import com.anatdx.rei.ui.util.getSELinuxStatus
import com.anatdx.rei.ui.auth.AuthLevel
import com.anatdx.rei.ui.auth.AuthRequest
import com.anatdx.rei.ui.auth.AuthorizeActivity
import com.anatdx.rei.ui.components.PullToRefreshBox
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    rootAccessState: RootAccessState,
    onRefreshRoot: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBootTools: () -> Unit,
) {
    var refreshKey by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(rootAccessState) {
        if (isRefreshing) isRefreshing = false
    }
    PullToRefreshBox(
        refreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            refreshKey++
            onRefreshRoot()
        },
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            if (ReiApplication.superKey.isEmpty()) {
                item {
                    SuperKeyPromptCard(onOpenSettings = onOpenSettings)
                }
            } else if (ReiKeyHelper.isValidSuperKey(ReiApplication.superKey)) {
                item {
                    KpReadyHintCard(onOpenSettings = onOpenSettings)
                }
            }
            item {
                SystemPatchCard(
                    rootAccessState = rootAccessState,
                    refreshTrigger = refreshKey,
                    onRefreshRoot = onRefreshRoot,
                )
            }
            item {
                MurasakiStatusCard(packageName = LocalContext.current.packageName, refreshTrigger = refreshKey)
            }
            item { SystemStatusCard(rootAccessState, refreshTrigger = refreshKey) }
            item {
                ActionsCard(
                    onRefreshRoot = onRefreshRoot,
                    onOpenSettings = onOpenSettings,
                    onOpenLogs = onOpenLogs,
                    onOpenBootTools = onOpenBootTools,
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private data class SystemStatus(
    val kernel: String = "",
    val selinux: String = "",
    val device: String = "",
    val ksu: String = "",
    val kpReady: Boolean = false,
    val kpVersion: String = "",
)

@Composable
private fun SystemStatusCard(rootAccessState: RootAccessState, refreshTrigger: Int = 0) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sys by remember { mutableStateOf(SystemStatus(device = "${Build.MANUFACTURER} ${Build.MODEL}".trim())) }

    fun refresh() {
        scope.launch {
            val kernel = withContext(Dispatchers.IO) {
                runCatching {
                    ProcessBuilder("sh", "-c", "uname -r").redirectErrorStream(true).start()
                        .inputStream.bufferedReader().readText().trim()
                }.getOrNull().orEmpty()
            }
            val selinux = withContext(Dispatchers.IO) { getSELinuxStatus(ctx) }
            val ksu = withContext(Dispatchers.IO) {
                if (rootAccessState is RootAccessState.Granted) {
                    val r = ReidClient.exec(ctx, listOf("debug", "ksu-info"), timeoutMs = 3_000L)
                    if (r.exitCode == 0 && r.output.isNotBlank()) {
                        runCatching {
                            val o = org.json.JSONObject(r.output.trim())
                            val ver = o.optInt("version", -1)
                            val mode = o.optString("mode").ifBlank { "unknown" }
                            val flagsHex = o.optString("flagsHex")
                            buildString {
                                if (ver >= 0) append(ver) else append("unknown")
                                append(" · ").append(mode)
                                if (flagsHex.isNotBlank()) append(" · ").append(flagsHex)
                            }
                        }.getOrElse { r.output.trim().take(80) }
                    } else {
                        ""
                    }
                } else {
                    ""
                }
            }
            val key = ReiApplication.superKey
            val (kpReady, kpVersion) = withContext(Dispatchers.Default) {
                if (key.isNotEmpty() && ReiKeyHelper.isValidSuperKey(key)) {
                    val ready = ApNatives.ready(key)
                    val ver = if (ready) ApNatives.kernelPatchVersion(key) else 0L
                    val verStr = if (ver > 0L) formatKpVersion(ver) else ""
                    ready to verStr
                } else {
                    false to ""
                }
            }
            sys = sys.copy(kernel = kernel, selinux = selinux, ksu = ksu, kpReady = kpReady, kpVersion = kpVersion)
            // 自动探测当前后端：KP 超级密钥通过则用 apatch，否则 KSU 有 fd/ksu-info 则用 ksu
            when {
                kpReady -> ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_APATCH
                ksu.isNotBlank() -> ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_KSU
            }
        }
    }

    LaunchedEffect(ReiApplication.superKey, refreshTrigger) { refresh() }

    val (rootChip, rootIcon) = when (rootAccessState) {
        RootAccessState.Requesting -> stringResource(R.string.home_root_requesting) to Icons.Outlined.Info
        RootAccessState.Ignored -> stringResource(R.string.home_root_ignored) to Icons.Outlined.Info
        is RootAccessState.Denied -> stringResource(R.string.home_root_denied) to Icons.Outlined.ErrorOutline
        is RootAccessState.Granted -> stringResource(R.string.home_root_granted) to Icons.Outlined.CheckCircle
    }

    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_system_status)) },
                supportingContent = { Text(sys.device.ifBlank { stringResource(R.string.home_device) }) },
                leadingContent = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(rootChip) },
                supportingContent = {
                    val detail = when (rootAccessState) {
                        RootAccessState.Requesting -> stringResource(R.string.home_root_requesting)
                        RootAccessState.Ignored -> stringResource(R.string.home_root_ignored)
                        is RootAccessState.Denied -> rootAccessState.reason
                        is RootAccessState.Granted -> rootAccessState.stdout.lineSequence().firstOrNull().orEmpty()
                    }.take(80)
                    Text(detail.ifBlank { "-" })
                },
                leadingContent = { Icon(rootIcon, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("SELinux") },
                supportingContent = { Text(sys.selinux.ifBlank { "unknown" }) },
                leadingContent = { Icon(Icons.Outlined.Security, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            val rootImpl = ReiApplication.rootImplementation
            if (rootImpl == ReiApplication.VALUE_ROOT_IMPL_KSU && sys.ksu.isNotBlank()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_root_impl_ksu)) },
                    supportingContent = { Text(sys.ksu) },
                    leadingContent = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            } else if (rootImpl == ReiApplication.VALUE_ROOT_IMPL_APATCH) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_root_impl_kp)) },
                    supportingContent = {
                        Text(
                            when {
                                sys.kpReady && sys.kpVersion.isNotBlank() -> stringResource(R.string.home_kp_installed, sys.kpVersion)
                                sys.kpReady -> stringResource(R.string.home_kp_installed_short)
                                else -> stringResource(R.string.home_kp_not_installed)
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_kernel)) },
                supportingContent = { Text(sys.kernel.ifBlank { "unknown" }) },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

private fun formatKpVersion(ver: Long): String {
    val major = (ver shr 16) and 0xFF
    val minor = (ver shr 8) and 0xFF
    val patch = ver and 0xFF
    return "$major.$minor.$patch"
}

@Composable
private fun SystemPatchCard(
    rootAccessState: RootAccessState,
    refreshTrigger: Int,
    onRefreshRoot: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var installStatus by remember { mutableStateOf<ReidInstallStatus>(ReidInstallStatus.Unknown) }
    var isInstalling by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        scope.launch {
            installStatus = ReidLauncher.getInstallStatus(ctx)
            installError = null
        }
    }

    LaunchedEffect(Unit, refreshTrigger) {
        if (rootAccessState is RootAccessState.Granted) {
            installStatus = ReidLauncher.getInstallStatus(ctx)
        } else {
            installStatus = ReidInstallStatus.Unknown
        }
        installError = null
    }

    val statusText = when (installStatus) {
        is ReidInstallStatus.Unknown -> stringResource(R.string.home_system_patch_status_unknown)
        is ReidInstallStatus.NotInstalled -> stringResource(R.string.home_system_patch_not_installed)
        is ReidInstallStatus.Installed -> {
            val ver = (installStatus as ReidInstallStatus.Installed).versionLine
            if (!ver.isNullOrBlank()) stringResource(R.string.home_system_patch_installed_version, ver)
            else stringResource(R.string.home_system_patch_installed)
        }
    }

    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_system_patch_title)) },
                supportingContent = {
                    Column {
                        Text(statusText)
                        installError?.let { err ->
                            Text(
                                text = stringResource(R.string.home_system_patch_install_failed, err),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (rootAccessState is RootAccessState.Granted) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isInstalling) return@OutlinedButton
                            isInstalling = true
                            installError = null
                            scope.launch {
                                val result = ReidLauncher.start(ctx)
                                isInstalling = false
                                when (result) {
                                    is ReidStartResult.Started -> {
                                        refreshStatus()
                                        onRefreshRoot()
                                    }
                                    is ReidStartResult.Failed -> {
                                        installError = result.reason
                                        refreshStatus()
                                    }
                                }
                            }
                        },
                        enabled = !isInstalling,
                    ) {
                        if (isInstalling) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            if (isInstalling) stringResource(R.string.home_system_patch_install_installing)
                            else stringResource(R.string.home_system_patch_install_btn)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperKeyPromptCard(onOpenSettings: () -> Unit) {
    ReiCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.home_superkey),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_superkey_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.home_go_settings))
                }
            }
        }
    }
}

@Composable
private fun KpReadyHintCard(onOpenSettings: () -> Unit) {
    val key = ReiApplication.superKey
    var kpReady by remember(key) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(key) {
        kpReady = if (key.isNotEmpty() && ReiKeyHelper.isValidSuperKey(key)) {
            withContext(Dispatchers.Default) { ApNatives.ready(key) }
        } else null
    }
    when (kpReady) {
        true -> { }
        false -> {
            ReiCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            text = stringResource(R.string.home_superkey_set_not_ready),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.home_superkey_check_kp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                                Text(stringResource(R.string.home_set_superkey))
                        }
                    }
                }
            }
        }
        null -> { }
    }
}

@Composable
private fun ActionsCard(
    onRefreshRoot: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBootTools: () -> Unit,
) {
    val ctx = LocalContext.current
    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_quick_actions)) },
                supportingContent = { Text(stringResource(R.string.home_quick_actions_desc)) },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_redetect_root)) },
                supportingContent = { Text(stringResource(R.string.home_redetect_root_desc)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onRefreshRoot),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_partition_manager)) },
                supportingContent = { Text(stringResource(R.string.home_partition_manager_desc)) },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenBootTools),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}


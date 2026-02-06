package com.anatdx.rei.ui.home

import android.content.Intent
import android.os.Build
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
import com.anatdx.rei.KsuNatives
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.auth.ReiKeyHelper
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.rei.core.reid.ReidInstallStatus
import com.anatdx.rei.core.reid.ReidLauncher
import com.anatdx.rei.core.reid.ReidStartResult
import com.anatdx.rei.core.root.RootAccessState
import com.anatdx.rei.core.root.RootShell
import com.anatdx.rei.ui.util.getSELinuxStatus
import com.anatdx.rei.ui.auth.AuthLevel
import com.anatdx.rei.ui.auth.AuthRequest
import com.anatdx.rei.ui.auth.AuthorizeActivity
import com.anatdx.rei.ui.components.PullToRefreshBox
import com.anatdx.rei.ui.components.ReiCard
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    rootAccessState: RootAccessState,
    onRefreshRoot: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var refreshKey by remember { mutableStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }
    var rootImpl by remember { mutableStateOf(ReiApplication.rootImplementation) }
    var systemPatchStatus by remember { mutableStateOf<ReidInstallStatus>(ReidInstallStatus.Unknown) }
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
            } else if (ReiKeyHelper.isValidSuperKey(ReiApplication.superKey) && rootImpl != ReiApplication.VALUE_ROOT_IMPL_KSU) {
                item {
                    KpReadyHintCard(onOpenSettings = onOpenSettings)
                }
            }
            if (systemPatchStatus !is ReidInstallStatus.Installed) {
                item {
                    SystemPatchCard(
                        rootAccessState = rootAccessState,
                        refreshTrigger = refreshKey,
                        onRefreshRoot = onRefreshRoot,
                        onInstallStatusChanged = { systemPatchStatus = it },
                    )
                }
            }
            item {
                MurasakiStatusCard(packageName = LocalContext.current.packageName, refreshTrigger = refreshKey)
            }
            item {
                SystemStatusCard(
                    rootAccessState = rootAccessState,
                    refreshTrigger = refreshKey,
                    onRootImplementationDetected = { rootImpl = it },
                )
            }
            if (rootAccessState is RootAccessState.Granted) {
                item {
                    ModuleImplCard(refreshTrigger = refreshKey)
                }
            }
            if (systemPatchStatus is ReidInstallStatus.Installed) {
                item {
                    SystemPatchCard(
                        rootAccessState = rootAccessState,
                        refreshTrigger = refreshKey,
                        onRefreshRoot = onRefreshRoot,
                        onInstallStatusChanged = { systemPatchStatus = it },
                    )
                }
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
private fun SystemStatusCard(
    rootAccessState: RootAccessState,
    refreshTrigger: Int = 0,
    onRootImplementationDetected: (String) -> Unit = {},
) {
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
            // Probe KSU via ksud directly so detection does not depend on current rootImplementation (otherwise we'd always use apd when rootImplementation was APATCH and never get ksu).
            val ksu = withContext(Dispatchers.IO) {
                if (rootAccessState is RootAccessState.Granted) {
                    val r = RootShell.exec("([ -x /data/adb/ksud ] && /data/adb/ksud debug ksu-info 2>/dev/null) || true", timeoutMs = 3_000L)
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
            val hasKsuFd = withContext(Dispatchers.Default) { KsuNatives.isManager }
            when {
                hasKsuFd -> ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_KSU
                ksu.isNotBlank() -> ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_KSU
                kpReady -> ReiApplication.rootImplementation = ReiApplication.VALUE_ROOT_IMPL_APATCH
            }
            onRootImplementationDetected(ReiApplication.rootImplementation)
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
            if (sys.ksu.isNotBlank()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home_root_impl_ksu)) },
                    supportingContent = { Text(sys.ksu) },
                    leadingContent = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
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

private fun parseModuleListForHome(raw: String): Pair<String?, String?> {
    var metamoduleName = ""
    var zygiskName = ""
    val zygiskIds = setOf("zygisksu", "rezygisk", "shirokozygisk", "murasaki_zygisk_bridge", "murasaki-zygisk-bridge")
    runCatching {
        val arr = JSONArray(raw.trim())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("name").ifBlank { id }
            val enabled = when (val v = o.opt("enabled")) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true) || v == "1"
                is Number -> v.toInt() != 0
                else -> o.optString("enabled").equals("true", ignoreCase = true) || o.optString("enabled") == "1"
            }
            val metamodule = when (val v = o.opt("metamodule")) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true) || v == "1"
                is Number -> v.toInt() != 0
                else -> o.optString("metamodule").equals("true", ignoreCase = true) || o.optString("metamodule") == "1"
            }
            if (enabled && metamodule && metamoduleName.isEmpty()) metamoduleName = name
            if (enabled && zygiskName.isEmpty() && id.lowercase() in zygiskIds) zygiskName = name
        }
    }
    return (if (metamoduleName.isNotEmpty()) metamoduleName else null) to (if (zygiskName.isNotEmpty()) zygiskName else null)
}

@Composable
private fun ModuleImplCard(refreshTrigger: Int) {
    val ctx = LocalContext.current
    var metamoduleName by remember { mutableStateOf<String?>(null) }
    var zygiskName by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(refreshTrigger) {
        loaded = false
        metamoduleName = null
        zygiskName = null
        val r = withContext(Dispatchers.IO) {
            ReidClient.exec(ctx, listOf("module", "list"), timeoutMs = 5_000L)
        }
        if (r.exitCode == 0 && r.output.isNotBlank()) {
            val (meta, zy) = parseModuleListForHome(r.output)
            metamoduleName = meta
            zygiskName = zy
        }
        loaded = true
    }
    val none = stringResource(R.string.home_module_impl_none)
    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_metamodule_implement)) },
                supportingContent = { Text(if (loaded) (metamoduleName ?: none) else "…") },
                leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.home_zygisk_implement)) },
                supportingContent = { Text(if (loaded) (zygiskName ?: none) else "…") },
                leadingContent = { Icon(Icons.Outlined.Extension, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@Composable
private fun SystemPatchCard(
    rootAccessState: RootAccessState,
    refreshTrigger: Int,
    onRefreshRoot: () -> Unit,
    onInstallStatusChanged: (ReidInstallStatus) -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var installStatus by remember { mutableStateOf<ReidInstallStatus>(ReidInstallStatus.Unknown) }
    var isInstalling by remember { mutableStateOf(false) }
    var isUninstalling by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var uninstallError by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        scope.launch {
            installStatus = ReidLauncher.getInstallStatus(ctx)
            installError = null
            uninstallError = null
            onInstallStatusChanged(installStatus)
        }
    }

    LaunchedEffect(Unit, refreshTrigger) {
        if (rootAccessState is RootAccessState.Granted) {
            installStatus = ReidLauncher.getInstallStatus(ctx)
        } else {
            installStatus = ReidInstallStatus.Unknown
        }
        installError = null
        uninstallError = null
        onInstallStatusChanged(installStatus)
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
                        uninstallError?.let { err ->
                            Text(
                                text = stringResource(R.string.home_system_patch_uninstall_failed, err),
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
                    val isInstalled = installStatus is ReidInstallStatus.Installed
                    val busy = isInstalling || isUninstalling
                    OutlinedButton(
                        onClick = {
                            if (busy) return@OutlinedButton
                            if (isInstalled) {
                                isUninstalling = true
                                installError = null
                                uninstallError = null
                                scope.launch {
                                    val result = ReidLauncher.uninstall(ctx)
                                    isUninstalling = false
                                    when (result) {
                                        is ReidStartResult.Started -> {
                                            refreshStatus()
                                            onRefreshRoot()
                                        }
                                        is ReidStartResult.Failed -> {
                                            uninstallError = result.reason
                                            refreshStatus()
                                        }
                                    }
                                }
                            } else {
                                isInstalling = true
                                installError = null
                                uninstallError = null
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
                            }
                        },
                        enabled = !busy,
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                isUninstalling -> stringResource(R.string.home_system_patch_uninstall_uninstalling)
                                isInstalling -> stringResource(R.string.home_system_patch_install_installing)
                                isInstalled -> stringResource(R.string.home_system_patch_uninstall_btn)
                                else -> stringResource(R.string.home_system_patch_install_btn)
                            }
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

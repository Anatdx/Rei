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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.anatdx.rei.core.log.ReiLog
import com.anatdx.rei.ApNatives
import com.anatdx.rei.ReiApplication
import com.anatdx.rei.core.auth.ReiKeyHelper
import com.anatdx.rei.core.reid.ReidClient
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
    PullToRefreshBox(
        refreshing = false,
        onRefresh = { refreshKey++; onRefreshRoot() },
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
        }
    }

    LaunchedEffect(Unit, refreshTrigger) { refresh() }

    val (rootChip, rootIcon) = when (rootAccessState) {
        RootAccessState.Requesting -> "Root: 检测中" to Icons.Outlined.Info
        RootAccessState.Ignored -> "Root: 未检测" to Icons.Outlined.Info
        is RootAccessState.Denied -> "Root: 未授权" to Icons.Outlined.ErrorOutline
        is RootAccessState.Granted -> "Root: 已授权" to Icons.Outlined.CheckCircle
    }

    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("系统状态") },
                supportingContent = { Text(sys.device.ifBlank { "设备" }) },
                leadingContent = { Icon(Icons.Outlined.Storage, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text(rootChip) },
                supportingContent = {
                    val detail = when (rootAccessState) {
                        RootAccessState.Requesting -> "后台探测 su…"
                        RootAccessState.Ignored -> "未执行 su 探测"
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
                    headlineContent = { Text("Root 实现 · KernelSU") },
                    supportingContent = { Text(sys.ksu) },
                    leadingContent = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            } else if (rootImpl == ReiApplication.VALUE_ROOT_IMPL_APATCH) {
                ListItem(
                    headlineContent = { Text("Root 实现 · KernelPatch") },
                    supportingContent = {
                        Text(
                            when {
                                sys.kpReady && sys.kpVersion.isNotBlank() -> "已安装 · ${sys.kpVersion}"
                                sys.kpReady -> "已安装"
                                else -> "未安装或未鉴权"
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Outlined.Shield, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            ListItem(
                headlineContent = { Text("Kernel") },
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
private fun SuperKeyPromptCard(onOpenSettings: () -> Unit) {
    ReiCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "超级密钥",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "如果您已安装 KP 后端，请输入超级密钥来获得权限。8–63 位，需含字母和数字。请在设置中填写并保存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                    Text("去设置")
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
        true -> {
            // KP 已就绪：可选显示一行提示或不做任何卡片
        }
        false -> {
            ReiCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.padding(4.dp))
                        Text(
                            text = "SuperKey 已设置，但 KP 后端未就绪",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "请确认已刷入 KernelPatch/APatch 内核，或检查 SuperKey 是否与设备一致。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                            Text("设置 SuperKey")
                        }
                    }
                }
            }
        }
        null -> { /* 检测中，不显示卡片 */ }
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
                headlineContent = { Text("快速操作") },
                supportingContent = { Text("刷新检测 / 工具入口") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("重新检测 Root") },
                supportingContent = { Text("重新拉起授权并刷新状态") },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onRefreshRoot),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            ListItem(
                headlineContent = { Text("分区管理") },
                supportingContent = { Text("查看、备份、刷写分区，管理 A/B 槽位") },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenBootTools),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}


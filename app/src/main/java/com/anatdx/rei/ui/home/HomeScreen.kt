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
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.AssistChip
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
import com.anatdx.rei.core.root.RootAccessState
import com.anatdx.rei.ui.auth.AuthLevel
import com.anatdx.rei.ui.auth.AuthRequest
import com.anatdx.rei.ui.auth.AuthorizeActivity
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

@Composable
fun HomeScreen(
    rootAccessState: RootAccessState,
    onRefreshRoot: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBootTools: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        item { SystemStatusCard(rootAccessState) }
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

private data class SystemStatus(
    val kernel: String = "",
    val selinux: String = "",
    val device: String = "",
)

@Composable
private fun SystemStatusCard(rootAccessState: RootAccessState) {
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
            val selinux = withContext(Dispatchers.IO) {
                querySelinuxMode()
            }
            sys = sys.copy(kernel = kernel, selinux = selinux)
        }
    }

    LaunchedEffect(Unit) { refresh() }

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
                trailingContent = {
                    AssistChip(
                        onClick = { refresh() },
                        label = { Text("刷新") },
                    )
                },
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
            ListItem(
                headlineContent = { Text("Kernel") },
                supportingContent = { Text(sys.kernel.ifBlank { "unknown" }) },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

private fun querySelinuxMode(): String {
    fun readProc(cmd: List<String>): String? {
        return runCatching {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use(BufferedReader::readText).trim()
        }.getOrNull()
    }

    // Prefer absolute path to avoid PATH issues.
    val ge = readProc(listOf("/system/bin/getenforce"))?.takeIf { it.isNotBlank() }
        ?: readProc(listOf("sh", "-c", "/system/bin/getenforce 2>/dev/null"))?.takeIf { it.isNotBlank() }

    if (ge != null && !ge.equals("unknown", ignoreCase = true)) return ge

    // Fallback: /sys/fs/selinux/enforce -> 1 Enforcing / 0 Permissive
    val enforce = runCatching { File("/sys/fs/selinux/enforce").readText().trim() }.getOrNull()
    return when (enforce) {
        "1" -> "Enforcing"
        "0" -> "Permissive"
        else -> ge ?: "unknown"
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
                headlineContent = { Text("Boot 工具") },
                supportingContent = { Text("boot-info / flash / patch") },
                trailingContent = { Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenBootTools),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}


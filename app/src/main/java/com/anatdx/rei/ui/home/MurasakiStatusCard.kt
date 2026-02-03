package com.anatdx.rei.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.anatdx.rei.ui.components.ReiCard
import io.murasaki.Murasaki
import io.murasaki.server.IHymoFsService
import io.murasaki.server.IMurasakiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class MurasakiStatus(
    val isConnected: Boolean = false,
    val serviceVersion: Int = -1,
    val ksuVersion: Int = -1,
    val privilegeLevel: Int = -1,
    val privilegeLevelName: String = "Unknown",
    val isKernelModeAvailable: Boolean = false,
    val selinuxContext: String? = null,
    val error: String? = null,
)

private data class HymoFsStatus(
    val isAvailable: Boolean = false,
    val stealthEnabled: Boolean = false,
    val hideRulesCount: Int = 0,
    val redirectRulesCount: Int = 0,
)

@Composable
fun MurasakiStatusCard(
    packageName: String,
    modifier: Modifier = Modifier,
    refreshTrigger: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(MurasakiStatus()) }
    var hymoFsStatus by remember { mutableStateOf(HymoFsStatus()) }
    var isLoading by remember { mutableStateOf(false) }

    fun connect() {
        scope.launch {
            isLoading = true
            status = MurasakiStatus()
            try {
                val result = withContext(Dispatchers.IO) {
                    val level = Murasaki.init(packageName)
                    if (level < 0) {
                        return@withContext MurasakiStatus(
                            isConnected = false,
                            error = "无法连接 Murasaki 服务",
                        )
                    }
                    val service: IMurasakiService? = Murasaki.getMurasakiService()
                    MurasakiStatus(
                        isConnected = true,
                        serviceVersion = service?.version ?: -1,
                        ksuVersion = Murasaki.getKernelSuVersion(),
                        privilegeLevel = level,
                        privilegeLevelName = privilegeLevelName(level),
                        isKernelModeAvailable = Murasaki.isKernelModeAvailable(),
                        selinuxContext = Murasaki.getSELinuxContext(),
                    )
                }
                status = result
                if (result.isConnected) {
                    val hfs: IHymoFsService? = Murasaki.getHymoFsService()
                    if (hfs != null) {
                        hymoFsStatus = withContext(Dispatchers.IO) {
                            HymoFsStatus(
                                isAvailable = hfs.isAvailable,
                                stealthEnabled = hfs.isStealthMode,
                                hideRulesCount = hfs.hideRules?.size ?: 0,
                                redirectRulesCount = hfs.redirectRules?.size ?: 0,
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                status = MurasakiStatus(isConnected = false, error = e.message ?: "未知错误")
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(packageName, refreshTrigger) { connect() }

    ReiCard(modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text("Murasaki API") },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isLoading -> Color(0xFFFFC107)
                                        status.isConnected -> Color(0xFF4CAF50)
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                isLoading -> "连接中…"
                                status.isConnected -> "已连接"
                                else -> "未连接"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingContent = { Icon(Icons.Default.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                status.isConnected -> {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    MurasakiDetails(status, hymoFsStatus) {
                        scope.launch {
                            val hfs = withContext(Dispatchers.IO) { Murasaki.getHymoFsService() }
                            if (hfs != null) {
                                withContext(Dispatchers.IO) { hfs.setStealthMode(!hymoFsStatus.stealthEnabled) }
                                hymoFsStatus = hymoFsStatus.copy(stealthEnabled = !hymoFsStatus.stealthEnabled)
                            }
                        }
                    }
                }
                else -> {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    ConnectionError(status.error) { connect() }
                }
            }
        }
    }
}

@Composable
private fun MurasakiDetails(
    status: MurasakiStatus,
    hymoFsStatus: HymoFsStatus,
    onStealthToggle: () -> Unit,
) {
    val (levelColor, levelIcon) = when (status.privilegeLevel) {
        Murasaki.LEVEL_SHELL -> Color(0xFF4CAF50) to Icons.Default.Terminal
        Murasaki.LEVEL_ROOT -> Color(0xFFFF9800) to Icons.Default.AdminPanelSettings
        Murasaki.LEVEL_KERNEL -> Color(0xFFF44336) to Icons.Default.Memory
        else -> MaterialTheme.colorScheme.onSurfaceVariant to Icons.Default.Security
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = levelColor.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(levelIcon, contentDescription = null, tint = levelColor, modifier = Modifier.size(24.dp))
                Column {
                    Text(
                        text = "权限等级",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = status.privilegeLevelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = levelColor,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("服务版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (status.serviceVersion >= 0) "v${status.serviceVersion}" else "N/A", style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("KSU 版本", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (status.ksuVersion >= 0) status.ksuVersion.toString() else "N/A", style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("内核模式", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (status.isKernelModeAvailable) "可用" else "不可用", style = MaterialTheme.typography.bodySmall)
        }
        status.selinuxContext?.let { ctx ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("SELinux", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ctx, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }

        if (hymoFsStatus.isAvailable) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            FilterChip(
                selected = hymoFsStatus.stealthEnabled,
                onClick = onStealthToggle,
                label = { Text("隐身") },
                leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null, Modifier.size(18.dp)) },
            )
            if (hymoFsStatus.hideRulesCount > 0 || hymoFsStatus.redirectRulesCount > 0) {
                Text(
                    text = "活跃规则：${hymoFsStatus.hideRulesCount} 隐藏 / ${hymoFsStatus.redirectRulesCount} 重定向",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConnectionError(error: String?, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "连接失败",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("重试")
        }
    }
}

private fun privilegeLevelName(level: Int): String = when (level) {
    Murasaki.LEVEL_SHELL -> "SHELL (Shizuku 兼容)"
    Murasaki.LEVEL_ROOT -> "ROOT (Sui 兼容)"
    Murasaki.LEVEL_KERNEL -> "KERNEL (Murasaki 独占)"
    else -> "Unknown ($level)"
}

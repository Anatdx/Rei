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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anatdx.rei.R
import com.anatdx.rei.core.reid.ReidClient
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
    cachedStatus: HomeMurasakiStatus? = null,
    cachedHymoFs: HomeHymoFsStatus? = null,
    onLoaded: (HomeMurasakiStatus, HomeHymoFsStatus?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(MurasakiStatus()) }
    var hymoFsStatus by remember { mutableStateOf(HymoFsStatus()) }
    var isLoading by remember { mutableStateOf(false) }

    if (cachedStatus != null) {
        MurasakiStatusCardContent(
            modifier = modifier,
            status = cachedStatus,
            hymoFsStatus = cachedHymoFs,
            isLoading = false,
            context = context,
            onRetry = {},
            onStealthToggle = null,
        )
        return
    }

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
                            error = context.getString(R.string.murasaki_error_connect),
                        )
                    }
                    val service: IMurasakiService? = Murasaki.getMurasakiService()
                    MurasakiStatus(
                        isConnected = true,
                        serviceVersion = service?.version ?: -1,
                        ksuVersion = Murasaki.getKernelSuVersion(),
                        privilegeLevel = level,
                        privilegeLevelName = privilegeLevelName(context, level),
                        isKernelModeAvailable = Murasaki.isKernelModeAvailable(),
                        selinuxContext = Murasaki.getSELinuxContext(),
                    )
                }
                status = result
                var hfsStatus: HomeHymoFsStatus? = null
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
                        hfsStatus = HomeHymoFsStatus(
                            isAvailable = hymoFsStatus.isAvailable,
                            stealthEnabled = hymoFsStatus.stealthEnabled,
                            hideRulesCount = hymoFsStatus.hideRulesCount,
                            redirectRulesCount = hymoFsStatus.redirectRulesCount,
                        )
                    }
                }
                onLoaded(
                    HomeMurasakiStatus(
                        isConnected = result.isConnected,
                        serviceVersion = result.serviceVersion,
                        ksuVersion = result.ksuVersion,
                        privilegeLevel = result.privilegeLevel,
                        privilegeLevelName = result.privilegeLevelName,
                        isKernelModeAvailable = result.isKernelModeAvailable,
                        selinuxContext = result.selinuxContext,
                        error = result.error,
                    ),
                    hfsStatus,
                )
            } catch (e: Exception) {
                status = MurasakiStatus(isConnected = false, error = e.message ?: context.getString(R.string.murasaki_error_unknown))
                onLoaded(
                    HomeMurasakiStatus(isConnected = false, error = e.message ?: context.getString(R.string.murasaki_error_unknown)),
                    null,
                )
            } finally {
                isLoading = false
            }
        }
    }

    /** 先尝试连接 Binder；若未连接再通过 shell 拉起 daemon（services），然后连接。已连 Murasaki 时不再 exec。 */
    fun ensureDaemonThenConnect() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val alreadyConnected = runCatching {
                    Murasaki.init(packageName)
                    Murasaki.getMurasakiService() != null
                }.getOrDefault(false)
                if (!alreadyConnected) {
                    ReidClient.exec(context, listOf("services"), timeoutMs = 5000L)
                }
            }
            connect()
        }
    }

    LaunchedEffect(packageName, refreshTrigger) { ensureDaemonThenConnect() }

    MurasakiStatusCardContent(
        modifier = modifier,
        status = status,
        hymoFsStatus = hymoFsStatus,
        isLoading = isLoading,
        context = context,
        onRetry = { ensureDaemonThenConnect() },
        onStealthToggle = {
            scope.launch {
                val hfs = withContext(Dispatchers.IO) { Murasaki.getHymoFsService() }
                if (hfs != null) {
                    withContext(Dispatchers.IO) { hfs.setStealthMode(!hymoFsStatus.stealthEnabled) }
                    hymoFsStatus = hymoFsStatus.copy(stealthEnabled = !hymoFsStatus.stealthEnabled)
                }
            }
        },
    )
}

@Composable
private fun MurasakiStatusCardContent(
    modifier: Modifier,
    status: Any,
    hymoFsStatus: Any?,
    isLoading: Boolean,
    context: android.content.Context,
    onRetry: () -> Unit = {},
    onStealthToggle: (() -> Unit)? = null,
) {
    val isConnected = when (status) {
        is HomeMurasakiStatus -> status.isConnected
        is MurasakiStatus -> status.isConnected
        else -> false
    }
    val serviceVersion = when (status) {
        is HomeMurasakiStatus -> status.serviceVersion
        is MurasakiStatus -> status.serviceVersion
        else -> -1
    }
    val ksuVersion = when (status) {
        is HomeMurasakiStatus -> status.ksuVersion
        is MurasakiStatus -> status.ksuVersion
        else -> -1
    }
    val privilegeLevel = when (status) {
        is HomeMurasakiStatus -> status.privilegeLevel
        is MurasakiStatus -> status.privilegeLevel
        else -> -1
    }
    val privilegeLevelName = when (status) {
        is HomeMurasakiStatus -> status.privilegeLevelName
        is MurasakiStatus -> status.privilegeLevelName
        else -> "Unknown"
    }
    val isKernelModeAvailable = when (status) {
        is HomeMurasakiStatus -> status.isKernelModeAvailable
        is MurasakiStatus -> status.isKernelModeAvailable
        else -> false
    }
    val selinuxContext = when (status) {
        is HomeMurasakiStatus -> status.selinuxContext
        is MurasakiStatus -> status.selinuxContext
        else -> null
    }
    val error = when (status) {
        is HomeMurasakiStatus -> status.error
        is MurasakiStatus -> status.error
        else -> null
    }
    val hfsAvailable = when (hymoFsStatus) {
        is HomeHymoFsStatus -> hymoFsStatus.isAvailable
        is HymoFsStatus -> hymoFsStatus.isAvailable
        else -> false
    }
    val hfsStealth = when (hymoFsStatus) {
        is HomeHymoFsStatus -> hymoFsStatus.stealthEnabled
        is HymoFsStatus -> hymoFsStatus.stealthEnabled
        else -> false
    }
    val hfsHideCount = when (hymoFsStatus) {
        is HomeHymoFsStatus -> hymoFsStatus.hideRulesCount
        is HymoFsStatus -> hymoFsStatus.hideRulesCount
        else -> 0
    }
    val hfsRedirectCount = when (hymoFsStatus) {
        is HomeHymoFsStatus -> hymoFsStatus.redirectRulesCount
        is HymoFsStatus -> hymoFsStatus.redirectRulesCount
        else -> 0
    }

    ReiCard(modifier = modifier) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.murasaki_api)) },
                supportingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isLoading -> Color(0xFFFFC107)
                                        isConnected -> Color(0xFF4CAF50)
                                        else -> MaterialTheme.colorScheme.error
                                    },
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                isLoading -> stringResource(R.string.murasaki_connecting)
                                isConnected -> stringResource(R.string.murasaki_connected)
                                else -> stringResource(R.string.murasaki_disconnected)
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
                isConnected -> {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    MurasakiDetailsFromFields(
                        privilegeLevel = privilegeLevel,
                        privilegeLevelName = privilegeLevelName,
                        serviceVersion = serviceVersion,
                        ksuVersion = ksuVersion,
                        isKernelModeAvailable = isKernelModeAvailable,
                        selinuxContext = selinuxContext,
                        hfsAvailable = hfsAvailable,
                        hfsStealth = hfsStealth,
                        hfsHideCount = hfsHideCount,
                        hfsRedirectCount = hfsRedirectCount,
                        onStealthToggle = onStealthToggle,
                    )
                }
                else -> {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    ConnectionError(error) { onRetry() }
                }
            }
        }
    }
}

@Composable
private fun MurasakiDetailsFromFields(
    privilegeLevel: Int,
    privilegeLevelName: String,
    serviceVersion: Int,
    ksuVersion: Int,
    isKernelModeAvailable: Boolean,
    selinuxContext: String?,
    hfsAvailable: Boolean,
    hfsStealth: Boolean,
    hfsHideCount: Int,
    hfsRedirectCount: Int,
    onStealthToggle: (() -> Unit)?,
) {
    val (levelColor, levelIcon) = when (privilegeLevel) {
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
                        text = stringResource(R.string.murasaki_privilege_level),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = privilegeLevelName,
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
            Text(stringResource(R.string.murasaki_service_version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (serviceVersion >= 0) "v$serviceVersion" else "N/A", style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.murasaki_ksu_version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (ksuVersion >= 0) ksuVersion.toString() else "N/A", style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.murasaki_kernel_mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (isKernelModeAvailable) stringResource(R.string.murasaki_available) else stringResource(R.string.murasaki_unavailable), style = MaterialTheme.typography.bodySmall)
        }
        selinuxContext?.let { ctx ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("SELinux", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ctx, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }

        if (hfsAvailable && onStealthToggle != null) {
            HorizontalDivider(Modifier.padding(vertical = 2.dp))
            FilterChip(
                selected = hfsStealth,
                onClick = onStealthToggle,
                label = { Text(stringResource(R.string.murasaki_stealth)) },
                leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null, Modifier.size(18.dp)) },
            )
            if (hfsHideCount > 0 || hfsRedirectCount > 0) {
                Text(
                    text = stringResource(R.string.murasaki_rules_active, hfsHideCount, hfsRedirectCount),
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
            text = stringResource(R.string.murasaki_connection_failed),
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
            Text(stringResource(R.string.murasaki_retry))
        }
    }
}

private fun privilegeLevelName(context: android.content.Context, level: Int): String = when (level) {
    Murasaki.LEVEL_SHELL -> context.getString(R.string.murasaki_level_shell)
    Murasaki.LEVEL_ROOT -> context.getString(R.string.murasaki_level_root)
    Murasaki.LEVEL_KERNEL -> context.getString(R.string.murasaki_level_kernel)
    else -> context.getString(R.string.murasaki_level_unknown, level)
}

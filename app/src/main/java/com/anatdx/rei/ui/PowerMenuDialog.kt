package com.anatdx.rei.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PowerMenuDialog(
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("电源菜单") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PowerTile(
                        label = "重启",
                        icon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                        onClick = { onAction("重启") },
                        modifier = Modifier.weight(1f),
                    )
                    PowerTile(
                        label = "Recovery",
                        icon = { Icon(Icons.Outlined.MedicalServices, contentDescription = null) },
                        onClick = { onAction("Recovery") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PowerTile(
                        label = "Bootloader",
                        icon = { Icon(Icons.Outlined.DeveloperMode, contentDescription = null) },
                        onClick = { onAction("Bootloader") },
                        modifier = Modifier.weight(1f),
                    )
                    PowerTile(
                        label = "关机",
                        icon = { Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null) },
                        onClick = { onAction("关机") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PowerTile(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.height(64.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.size(22.dp)) { icon() }
            Text(label)
        }
    }
}


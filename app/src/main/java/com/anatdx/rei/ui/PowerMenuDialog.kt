package com.anatdx.rei.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anatdx.rei.R

@Composable
fun PowerMenuDialog(
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.power_menu_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                listOf(
                    Triple(stringResource(R.string.power_menu_reboot), Icons.Outlined.RestartAlt) { onAction("重启") },
                    Triple(stringResource(R.string.power_menu_soft_reboot), Icons.Outlined.Sync) { onAction("软重启") },
                    Triple("Recovery", Icons.Outlined.MedicalServices) { onAction("Recovery") },
                    Triple("Bootloader", Icons.Outlined.DeveloperMode) { onAction("Bootloader") },
                    Triple("Download", Icons.Outlined.Usb) { onAction("Download") },
                    Triple("EDL", Icons.Outlined.Usb) { onAction("EDL") },
                ).forEach { (label, icon, onClick) ->
                    PowerTile(
                        label = label,
                        icon = { Icon(icon, contentDescription = null) },
                        onClick = onClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.power_menu_cancel)) }
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
        modifier = modifier.height(52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(22.dp)) { icon() }
            Text(label)
        }
    }
}


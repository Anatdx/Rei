package com.anatdx.rei.ui.tools.partition

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import com.anatdx.rei.R
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PartitionManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var partitionList by remember { mutableStateOf<List<PartitionInfo>>(emptyList()) }
    var allPartitionList by remember { mutableStateOf<List<PartitionInfo>>(emptyList()) }
    var slotInfo by remember { mutableStateOf<SlotInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedPartition by remember { mutableStateOf<PartitionInfo?>(null) }
    var showPartitionDialog by remember { mutableStateOf(false) }
    var pendingFlashPartition by remember { mutableStateOf<PartitionInfo?>(null) }
    var showAllPartitions by remember { mutableStateOf(false) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedPartitions by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedSlot by remember { mutableStateOf<String?>(null) }
    var partitionTypeFilter by remember { mutableStateOf("all") }

    var showFlashConfirm by remember { mutableStateOf(false) }
    var flashConfirmTitle by remember { mutableStateOf("") }
    var flashConfirmContent by remember { mutableStateOf("") }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            pendingFlashPartition?.let { partition ->
                scope.launch {
                    val cacheFile = withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openInputStream(selectedUri)?.use { input ->
                                val tempFile = File(context.cacheDir, "flash_temp.img")
                                tempFile.outputStream().use { output -> input.copyTo(output) }
                                tempFile
                            } ?: null
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (cacheFile != null) {
                        snackbarHostState.showSnackbar(context.getString(R.string.partition_flashing, partition.name))
                        withContext(Dispatchers.IO) {
                            val logs = mutableListOf<String>()
                            val success = PartitionManagerHelper.flashPartition(
                                context = context,
                                imagePath = cacheFile.absolutePath,
                                partition = partition.name,
                                slot = slotInfo?.currentSlot,
                                onStdout = { logs.add(it) },
                                onStderr = { logs.add("ERROR: $it") }
                            )
                            withContext(Dispatchers.Main) {
                                cacheFile.delete()
                                if (success) {
                                    snackbarHostState.showSnackbar(context.getString(R.string.partition_flash_success))
                                } else {
                                    val errorMsg = logs.lastOrNull() ?: context.getString(R.string.partition_unknown)
                                    snackbarHostState.showSnackbar(context.getString(R.string.partition_flash_failed, errorMsg))
                                }
                            }
                        }
                    } else {
                        snackbarHostState.showSnackbar(context.getString(R.string.partition_cannot_read_file))
                    }
                }
            }
            pendingFlashPartition = null
        }
    }

    val refreshPartitions: suspend (String?) -> Unit = { slot ->
        withContext(Dispatchers.Main) { isLoading = true }
        try {
            partitionList = PartitionManagerHelper.getPartitionList(context, slot, scanAll = false)
            allPartitionList = PartitionManagerHelper.getPartitionList(context, slot, scanAll = true)
        } catch (e: Exception) {
            android.util.Log.e("PartitionManager", "Failed to refresh partitions", e)
        } finally {
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    val mapLogicalPartitions: suspend (String) -> Unit = { slot ->
        withContext(Dispatchers.Main) {
            snackbarHostState.showSnackbar(context.getString(R.string.partition_mapping, slot))
        }
        withContext(Dispatchers.IO) {
            val logs = mutableListOf<String>()
            val success = PartitionManagerHelper.mapLogicalPartitions(
                context = context,
                slot = slot,
                onStdout = { logs.add(it) },
                onStderr = { logs.add("ERROR: $it") }
            )
            withContext(Dispatchers.Main) {
                if (success) {
                    snackbarHostState.showSnackbar(context.getString(R.string.partition_map_success))
                    scope.launch { refreshPartitions(selectedSlot) }
                } else {
                    val errorMsg = logs.lastOrNull() ?: context.getString(R.string.partition_unknown)
                    snackbarHostState.showSnackbar(context.getString(R.string.partition_map_failed, errorMsg))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main) { isLoading = true }
        try {
            slotInfo = PartitionManagerHelper.getSlotInfo(context)
            selectedSlot = slotInfo?.currentSlot
            partitionList = PartitionManagerHelper.getPartitionList(context, selectedSlot, scanAll = false)
            allPartitionList = PartitionManagerHelper.getPartitionList(context, selectedSlot, scanAll = true)
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                snackbarHostState.showSnackbar(context.getString(R.string.partition_load_failed, e.message ?: ""))
            }
        } finally {
            withContext(Dispatchers.Main) { isLoading = false }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }

                if (slotInfo != null) {
                    item {
                        SlotInfoCard(
                            slotInfo = slotInfo!!,
                            selectedSlot = selectedSlot,
                            onSlotChange = { newSlot ->
                                selectedSlot = newSlot
                                scope.launch { refreshPartitions(newSlot) }
                            }
                        )
                    }
                    if (slotInfo!!.isAbDevice && selectedSlot != slotInfo!!.currentSlot) {
                        item {
                            ReiCard {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                        Text(
                                            text = stringResource(R.string.partition_map_inactive_desc),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    Button(
                                        onClick = { scope.launch { selectedSlot?.let { mapLogicalPartitions(it) } } },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.partition_map_inactive))
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    ReiCard {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            ListItem(
                                headlineContent = { Text(stringResource(R.string.partition_list)) },
                                supportingContent = { Text(stringResource(R.string.partition_filter_all) + " / " + stringResource(R.string.partition_filter_physical) + " / " + stringResource(R.string.partition_filter_logical)) },
                                leadingContent = { Icon(Icons.Default.Storage, contentDescription = null) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = partitionTypeFilter == "all",
                                    onClick = { partitionTypeFilter = "all" },
                                    label = { Text(stringResource(R.string.partition_filter_all)) },
                                    leadingIcon = if (partitionTypeFilter == "all") {
                                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                    } else null,
                                )
                                FilterChip(
                                    selected = partitionTypeFilter == "physical",
                                    onClick = { partitionTypeFilter = "physical" },
                                    label = { Text(stringResource(R.string.partition_filter_physical)) },
                                    leadingIcon = if (partitionTypeFilter == "physical") {
                                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                    } else null,
                                )
                                FilterChip(
                                    selected = partitionTypeFilter == "logical",
                                    onClick = { partitionTypeFilter = "logical" },
                                    label = { Text(stringResource(R.string.partition_filter_logical)) },
                                    leadingIcon = if (partitionTypeFilter == "logical") {
                                        { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                                    } else null,
                                )
                            }
                            OutlinedButton(
                                onClick = { showAllPartitions = !showAllPartitions },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Icon(
                                    if (showAllPartitions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(if (showAllPartitions) R.string.partition_collapse else R.string.partition_show_all))
                            }
                        }
                    }
                }

                if (multiSelectMode && selectedPartitions.isNotEmpty()) {
                    item {
                        ReiCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.partition_selected_count, selectedPartitions.size),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val displayList = if (showAllPartitions) allPartitionList else partitionList
                                    val selectablePartitions = displayList
                                        .filterNot { it.excludeFromBatch || it.isLogical }
                                        .filter {
                                            when (partitionTypeFilter) {
                                                "physical" -> !it.isLogical
                                                "logical" -> it.isLogical
                                                else -> true
                                            }
                                        }
                                    val selectedCount = selectablePartitions.count { it.name in selectedPartitions }
                                    val checkboxState = when {
                                        selectedCount == 0 -> ToggleableState.Off
                                        selectedCount == selectablePartitions.size -> ToggleableState.On
                                        else -> ToggleableState.Indeterminate
                                    }
                                    TriStateCheckbox(
                                        state = checkboxState,
                                        onClick = {
                                            when (checkboxState) {
                                                ToggleableState.Off, ToggleableState.Indeterminate ->
                                                    selectedPartitions = selectablePartitions.map { it.name }.toSet()
                                                ToggleableState.On -> selectedPartitions = emptySet()
                                            }
                                        },
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Button(onClick = {
                                        scope.launch {
                                            handleBatchBackup(
                                                context,
                                                selectedPartitions,
                                                if (showAllPartitions) allPartitionList else partitionList,
                                                snackbarHostState,
                                            )
                                        }
                                    }) {
                                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.partition_batch_backup))
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = stringResource(if (showAllPartitions) R.string.partition_all else R.string.partition_common),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    )
                }

                val displayList = if (showAllPartitions) allPartitionList else partitionList
                val filteredList = when (partitionTypeFilter) {
                    "physical" -> displayList.filter { !it.isLogical }
                    "logical" -> displayList.filter { it.isLogical }
                    else -> displayList
                }
                items(filteredList) { partition ->
                    PartitionCard(
                        partition = partition,
                        isSelected = selectedPartitions.contains(partition.name),
                        multiSelectMode = multiSelectMode,
                        onClick = {
                            if (multiSelectMode) {
                                selectedPartitions = if (selectedPartitions.contains(partition.name)) {
                                    selectedPartitions - partition.name
                                } else {
                                    selectedPartitions + partition.name
                                }
                            } else {
                                selectedPartition = partition
                                showPartitionDialog = true
                            }
                        },
                        onLongClick = {
                            if (!multiSelectMode) multiSelectMode = true
                            selectedPartitions = if (selectedPartitions.contains(partition.name)) {
                                selectedPartitions - partition.name
                            } else {
                                selectedPartitions + partition.name
                            }
                        },
                    )
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }

    if (showPartitionDialog && selectedPartition != null) {
        PartitionActionDialog(
            partition = selectedPartition!!,
            currentSlot = slotInfo?.currentSlot,
            onDismiss = { showPartitionDialog = false },
            onBackup = {
                showPartitionDialog = false
                scope.launch {
                    handlePartitionBackup(context, selectedPartition!!, slotInfo?.currentSlot, snackbarHostState)
                }
            },
            onFlashClick = {
                val p = selectedPartition!!
                flashConfirmTitle = if (p.isDangerous) {
                    context.getString(R.string.partition_dangerous_operation_warning)
                } else {
                    context.getString(R.string.partition_dangerous_operation)
                }
                flashConfirmContent = if (p.isDangerous) {
                    context.getString(R.string.partition_dangerous_flash_warning, p.name, p.name)
                } else {
                    context.getString(R.string.partition_flash_warning, p.name)
                }
                showFlashConfirm = true
            }
        )
    }

    if (showFlashConfirm) {
        AlertDialog(
            onDismissRequest = { showFlashConfirm = false },
            title = { Text(flashConfirmTitle) },
            text = { Text(flashConfirmContent) },
            confirmButton = {
                Button(
                    onClick = {
                        showFlashConfirm = false
                        showPartitionDialog = false
                        pendingFlashPartition = selectedPartition
                        filePickerLauncher.launch("*/*")
                    }
                ) {
                    Text(stringResource(R.string.partition_confirm_flash))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFlashConfirm = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun SlotInfoCard(
    slotInfo: SlotInfo,
    selectedSlot: String?,
    onSlotChange: (String?) -> Unit,
) {
    ReiCard {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.partition_slot_info)) },
                supportingContent = {
                    Text(
                        if (slotInfo.isAbDevice) stringResource(R.string.partition_ab_device)
                        else stringResource(R.string.partition_a_only_device),
                    )
                },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (slotInfo.isAbDevice) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedSlot == slotInfo.currentSlot,
                        onClick = { onSlotChange(slotInfo.currentSlot) },
                        label = {
                            Text("${stringResource(R.string.partition_current_slot)}: ${slotInfo.currentSlot ?: stringResource(R.string.partition_unknown)}")
                        },
                        leadingIcon = if (selectedSlot == slotInfo.currentSlot) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = selectedSlot == slotInfo.otherSlot,
                        onClick = { onSlotChange(slotInfo.otherSlot) },
                        label = {
                            Text("${stringResource(R.string.partition_other_slot)}: ${slotInfo.otherSlot ?: stringResource(R.string.partition_unknown)}")
                        },
                        leadingIcon = if (selectedSlot == slotInfo.otherSlot) {
                            { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PartitionCard(
    partition: PartitionInfo,
    isSelected: Boolean = false,
    multiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ReiCard(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = partition.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    if (partition.isDangerous) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = stringResource(R.string.partition_dangerous_warning),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (partition.excludeFromBatch) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Block, contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                    }
                }
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "${stringResource(if (partition.isLogical) R.string.partition_type_logical else R.string.partition_type_physical)} • ${formatSize(partition.size)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (partition.blockDevice.isNotEmpty()) {
                        Text(
                            text = partition.blockDevice,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            leadingContent = {
                when {
                    multiSelectMode -> Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        enabled = !partition.excludeFromBatch,
                    )
                    else -> Icon(
                        imageVector = if (partition.isLogical) Icons.Default.Layers else Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                if (!multiSelectMode) {
                    Icon(
                        imageVector = if (partition.isLogical) Icons.Default.Layers else Icons.Default.Storage,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun PartitionActionDialog(
    partition: PartitionInfo,
    currentSlot: String?,
    onDismiss: () -> Unit,
    onBackup: () -> Unit,
    onFlashClick: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (partition.isLogical) Icons.Filled.Layers else Icons.Filled.Storage,
                contentDescription = null
            )
        },
        title = { Text(partition.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.partition_info_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                InfoRow(
                    label = stringResource(R.string.partition_info_type),
                    value = stringResource(if (partition.isLogical) R.string.partition_type_logical else R.string.partition_type_physical)
                )
                InfoRow(label = stringResource(R.string.partition_info_size), value = formatSize(partition.size))
                if (partition.blockDevice.isNotEmpty()) {
                    InfoRow(label = stringResource(R.string.partition_info_device), value = partition.blockDevice)
                }
                if (currentSlot != null) {
                    InfoRow(label = stringResource(R.string.partition_info_slot), value = currentSlot)
                }
                if (partition.isDangerous) {
                    ReiCard(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.partition_dangerous_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                } else {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                Text(
                    text = stringResource(R.string.partition_available_operations),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onBackup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.partition_backup_to_file))
                }
                TextButton(
                    onClick = onFlashClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.partition_flash_image))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.2f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.2f MB", mb)
    val gb = mb / 1024.0
    return String.format("%.2f GB", gb)
}

suspend fun handlePartitionBackup(
    context: Context,
    partition: PartitionInfo,
    slot: String?,
    snackbarHostState: SnackbarHostState
) {
    val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    val fileName = "${partition.name}_${format.format(Date())}.img"
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val outputFile = File(downloadsDir, fileName)

    withContext(Dispatchers.Main) {
        snackbarHostState.showSnackbar(context.getString(R.string.partition_backing_up, partition.name))
    }
    withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        val success = PartitionManagerHelper.backupPartition(
            context = context,
            partition = partition.name,
            outputPath = outputFile.absolutePath,
            slot = slot,
            onStdout = { logs.add(it) },
            onStderr = { logs.add("ERROR: $it") }
        )
        withContext(Dispatchers.Main) {
            if (success) {
                snackbarHostState.showSnackbar(context.getString(R.string.partition_backup_success, fileName))
            } else {
                val errorMsg = logs.lastOrNull() ?: context.getString(R.string.partition_unknown)
                snackbarHostState.showSnackbar(context.getString(R.string.partition_backup_failed, errorMsg))
            }
        }
    }
}

suspend fun handleBatchBackup(
    context: Context,
    selectedPartitionNames: Set<String>,
    allPartitions: List<PartitionInfo>,
    snackbarHostState: SnackbarHostState
) {
    val partitionsToBackup = allPartitions.filter { it.name in selectedPartitionNames }
    if (partitionsToBackup.isEmpty()) {
        withContext(Dispatchers.Main) {
            snackbarHostState.showSnackbar(context.getString(R.string.partition_no_selection))
        }
        return
    }
    val format = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    val backupDirName = "partition_backup_${format.format(Date())}"
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val backupDir = File(downloadsDir, backupDirName)
    backupDir.mkdirs()

    withContext(Dispatchers.Main) {
        snackbarHostState.showSnackbar(context.getString(R.string.partition_batch_backup_start, partitionsToBackup.size))
    }
    var successCount = 0
    val failedPartitions = mutableListOf<String>()
    for ((index, partition) in partitionsToBackup.withIndex()) {
        withContext(Dispatchers.Main) {
            snackbarHostState.showSnackbar(context.getString(R.string.partition_batch_backup_progress, index + 1, partitionsToBackup.size, partition.name))
        }
        val outputFile = File(backupDir, "${partition.name}.img")
        withContext(Dispatchers.IO) {
            val success = PartitionManagerHelper.backupPartition(
                context = context,
                partition = partition.name,
                outputPath = outputFile.absolutePath,
                slot = null,
                onStdout = { },
                onStderr = { }
            )
            if (success) successCount++ else failedPartitions.add(partition.name)
        }
    }
    withContext(Dispatchers.Main) {
        if (failedPartitions.isEmpty()) {
            snackbarHostState.showSnackbar(context.getString(R.string.partition_batch_backup_complete, successCount, backupDirName))
        } else {
            snackbarHostState.showSnackbar(context.getString(R.string.partition_batch_backup_partial, successCount, failedPartitions.size, failedPartitions.joinToString()))
        }
    }
}

data class SlotInfo(
    val isAbDevice: Boolean,
    val currentSlot: String?,
    val otherSlot: String?
)

data class PartitionInfo(
    val name: String,
    val blockDevice: String,
    val type: String,
    val size: Long,
    val isLogical: Boolean,
    val isDangerous: Boolean = false,
    val excludeFromBatch: Boolean = false
)

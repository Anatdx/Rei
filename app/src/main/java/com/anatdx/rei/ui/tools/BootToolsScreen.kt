package com.anatdx.rei.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.anatdx.rei.core.io.UriFiles
import com.anatdx.rei.core.reid.ReidClient
import com.anatdx.rei.core.root.RootAccessState
import com.anatdx.rei.ui.components.ReiCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BootToolsScreen(
    rootAccessState: RootAccessState,
    onOpenPartitionManager: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var lastOutput by rememberSaveable { mutableStateOf<String?>(null) }
    var running by rememberSaveable { mutableStateOf(false) }
    var showOutput by rememberSaveable { mutableStateOf(false) }

    var imagePath by rememberSaveable { mutableStateOf("") } // absolute path in app cache
    var imageLabel by rememberSaveable { mutableStateOf("") }
    var partition by rememberSaveable { mutableStateOf("boot") }
    var slot by rememberSaveable { mutableStateOf("") } // "", a, b, _a, _b
    var showFlashConfirm by rememberSaveable { mutableStateOf(false) }

    var ak3Path by rememberSaveable { mutableStateOf("") } // AnyKernel3 zip
    var ak3Label by rememberSaveable { mutableStateOf("") }
    var showAk3Confirm by rememberSaveable { mutableStateOf(false) }

    var lkmPath by rememberSaveable { mutableStateOf("") } // KernelSU LKM (.ko)
    var lkmLabel by rememberSaveable { mutableStateOf("") }
    var lkmPartition by rememberSaveable { mutableStateOf("boot") }
    var lkmPriority by rememberSaveable { mutableStateOf(true) }
    var showLkmConfirm by rememberSaveable { mutableStateOf(false) }

    fun canUseRoot(): Boolean = rootAccessState is RootAccessState.Granted

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                UriFiles.copyToCache(ctx, uri, subDir = "picked/boot", fallbackName = "boot.img")
            }
            if (f != null) {
                imagePath = f.absolutePath
                imageLabel = f.name
            }
        }
    }

    val pickAk3 = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                UriFiles.copyToCache(ctx, uri, subDir = "picked/ak3", fallbackName = "kernel.zip")
            }
            if (f != null) {
                ak3Path = f.absolutePath
                ak3Label = f.name
            }
        }
    }

    val pickLkm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                UriFiles.copyToCache(ctx, uri, subDir = "picked/lkm", fallbackName = "kernelsu.ko")
            }
            if (f != null) {
                lkmPath = f.absolutePath
                lkmLabel = f.name
            }
        }
    }

    suspend fun runReid(args: List<String>) {
        running = true
        val r = withContext(Dispatchers.IO) { ReidClient.exec(ctx, args, timeoutMs = 120_000L) }
        lastOutput = "exit=${r.exitCode}\n${r.output}"
        running = false
        showOutput = true
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        ReiCard {
            ListItem(
                headlineContent = { Text("Boot 工具") },
                supportingContent = {
                    Text(
                        if (canUseRoot()) "已获取 Root，可执行 reid 命令"
                        else "未获取 Root（该页操作需要 Root）"
                    )
                },
                leadingContent = { Icon(Icons.Outlined.Build, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        ReiCard {
            ListItem(
                headlineContent = { Text("读取 boot 信息") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                modifier = Modifier.padding(vertical = 4.dp),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.OutlinedButton(
                    enabled = canUseRoot() && !running,
                    onClick = { scope.launch { runReid(listOf("boot-info", "current-kmi")) } },
                ) {
                    Text("current-kmi")
                }
                androidx.compose.material3.OutlinedButton(
                    enabled = canUseRoot() && !running,
                    onClick = { scope.launch { runReid(listOf("boot-info", "slot-suffix")) } },
                ) {
                    Text("slot-suffix")
                }
                androidx.compose.material3.OutlinedButton(
                    enabled = canUseRoot() && !running,
                    onClick = { scope.launch { runReid(listOf("boot-info", "default-partition")) } },
                ) {
                    Text("default")
                }
            }
        }

        ReiCard {
            ListItem(
                headlineContent = { Text("分区管理") },
                supportingContent = { Text("查看 / 备份 / 刷写分区，管理 A/B 槽位") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenPartitionManager),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        ReiCard {
            ListItem(
                headlineContent = { Text("分区列表（命令行）") },
                leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                androidx.compose.material3.OutlinedButton(
                    enabled = canUseRoot() && !running,
                    onClick = { scope.launch { runReid(listOf("flash", "list")) } },
                ) {
                    Text("list")
                }
                androidx.compose.material3.OutlinedButton(
                    enabled = canUseRoot() && !running,
                    onClick = { scope.launch { runReid(listOf("flash", "list", "--all")) } },
                ) {
                    Text("list --all")
                }
            }
        }

        ReiCard {
            ListItem(
                headlineContent = { Text("刷入镜像") },
                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = imageLabel.ifBlank { "" },
                        onValueChange = {},
                        label = { Text(if (imageLabel.isBlank()) "未选择镜像" else "镜像") },
                        enabled = canUseRoot() && !running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 56.dp),
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = {
                            if (imagePath.isNotBlank() && canUseRoot() && !running) {
                                IconButton(
                                    onClick = {
                                        imagePath = ""
                                        imageLabel = ""
                                    }
                                ) { Icon(Icons.Outlined.Close, contentDescription = null) }
                            }
                        },
                    )
                    FloatingActionButton(
                        onClick = { pickImage.launch(arrayOf("*/*")) },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    ) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = partition,
                    onValueChange = { partition = it },
                    label = { Text("分区名（默认 boot）") },
                    enabled = canUseRoot() && !running,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = slot,
                    onValueChange = { slot = it },
                    label = { Text("Slot（可选：a / b / _a / _b）") },
                    enabled = canUseRoot() && !running,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = canUseRoot() && !running && imagePath.isNotBlank() && partition.isNotBlank(),
                    onClick = { showFlashConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("开始刷入（需要确认）")
                }
            }
        }

        ReiCard {
            ListItem(
                headlineContent = { Text("刷入 AK3") },
                supportingContent = { Text("AnyKernel3 zip") },
                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = ak3Label.ifBlank { "" },
                        onValueChange = {},
                        label = { Text(if (ak3Label.isBlank()) "未选择 zip" else "zip") },
                        enabled = canUseRoot() && !running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 56.dp),
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = {
                            if (ak3Path.isNotBlank() && canUseRoot() && !running) {
                                IconButton(
                                    onClick = {
                                        ak3Path = ""
                                        ak3Label = ""
                                    }
                                ) { Icon(Icons.Outlined.Close, contentDescription = null) }
                            }
                        },
                    )
                    FloatingActionButton(
                        onClick = { pickAk3.launch(arrayOf("*/*")) },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    ) { Icon(Icons.Outlined.FolderOpen, contentDescription = null) }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    enabled = canUseRoot() && !running && ak3Path.isNotBlank(),
                    onClick = { showAk3Confirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始刷入（需要确认）") }
            }
        }

        ReiCard {
            ListItem(
                headlineContent = { Text("安装 LKM") },
                supportingContent = { Text("KernelSU LKM (.ko)") },
                leadingContent = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("优先", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        Switch(
                            checked = lkmPriority,
                            onCheckedChange = { lkmPriority = it },
                            enabled = canUseRoot() && !running,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = lkmLabel.ifBlank { "" },
                        onValueChange = {},
                        label = { Text(if (lkmLabel.isBlank()) "未选择 .ko" else ".ko") },
                        enabled = canUseRoot() && !running,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 56.dp),
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = {
                            if (lkmPath.isNotBlank() && canUseRoot() && !running) {
                                IconButton(
                                    onClick = {
                                        lkmPath = ""
                                        lkmLabel = ""
                                    }
                                ) { Icon(Icons.Outlined.Close, contentDescription = null) }
                            }
                        },
                    )
                    FloatingActionButton(
                        onClick = { pickLkm.launch(arrayOf("*/*")) },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    ) { Icon(Icons.Outlined.FolderOpen, contentDescription = null) }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = lkmPartition,
                    onValueChange = { lkmPartition = it },
                    label = { Text("分区名（默认 boot）") },
                    enabled = canUseRoot() && !running,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    enabled = canUseRoot() && !running && lkmPath.isNotBlank(),
                    onClick = { showLkmConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("开始安装（需要确认）") }
            }
        }

        Spacer(Modifier.height(16.dp))
    }

    if (showFlashConfirm) {
        FlashConfirmDialog(
            onDismiss = { showFlashConfirm = false },
            onConfirm = {
                showFlashConfirm = false
                val args = buildList {
                    add("flash")
                    add("image")
                    add(imagePath.trim())
                    add(partition.trim())
                    val s = slot.trim()
                    if (s.isNotBlank()) {
                        add("--slot")
                        add(s)
                    }
                }
                scope.launch { runReid(args) }
            },
        )
    }

    if (showAk3Confirm) {
        FlashConfirmDialog(
            onDismiss = { showAk3Confirm = false },
            onConfirm = {
                showAk3Confirm = false
                val args = buildList {
                    add("flash")
                    add("ak3")
                    add(ak3Path.trim())
                    val s = slot.trim()
                    if (s.isNotBlank()) {
                        add("--slot")
                        add(s)
                    }
                }
                scope.launch { runReid(args) }
            },
        )
    }

    if (showLkmConfirm) {
        FlashConfirmDialog(
            onDismiss = { showLkmConfirm = false },
            onConfirm = {
                showLkmConfirm = false
                val args = buildList {
                    add("boot-patch")
                    add("--module")
                    add(lkmPath.trim())
                    add("--flash")
                    val p = lkmPartition.trim()
                    if (p.isNotBlank()) {
                        add("--partition")
                        add(p)
                    }
                    add("--lkm-priority")
                    add(if (lkmPriority) "1" else "0")
                }
                scope.launch { runReid(args) }
            },
        )
    }

    if (showOutput && lastOutput != null) {
        AlertDialog(
            onDismissRequest = { showOutput = false },
            title = { Text("reid 输出") },
            text = { Text(lastOutput ?: "") },
            confirmButton = { Button(onClick = { showOutput = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun FlashConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var token by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认刷写") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("此操作会直接写入分区，存在变砖风险。")
                Text("请输入 FLASH 以继续。", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("输入 FLASH") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = token == "FLASH",
            ) { Text("继续") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

